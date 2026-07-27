package dev.firsttake.probe

import android.Manifest
import android.content.res.ColorStateList
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var handsView: TextView
    private lateinit var recordingIndicatorView: TextView
    private lateinit var captureFrameView: View
    private lateinit var debugView: TextView
    private lateinit var debugButton: Button
    private lateinit var recordButton: Button
    private lateinit var exportEvidenceButton: Button
    private lateinit var coordinator: CaptureCoordinator
    private lateinit var captureFeedback: CaptureFeedback
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startupIo = Executors.newSingleThreadExecutor()
    private var primaryStatus = ""
    private var recoveryNotice: String? = null
    private var recoverySessionIds: Set<String> = emptySet()
    private var autoStartRequested = false
    private var autoExportRequested = false
    private var requestedRecoverySessionId: String? = null
    private var autoStopAfterSeconds = 0L
    private var autoStartDelaySeconds = 0L
    private var autoExposureProbeProfile = ExposureProbeProfile.NONE
    private var lastRecordingUiState = false
    private var recordingStartedUiMs: Long? = null
    private val recordingClock = object : Runnable {
        override fun run() {
            val startedAtMs = recordingStartedUiMs ?: return
            val elapsedSeconds = (
                SystemClock.elapsedRealtime() - startedAtMs
                ).coerceAtLeast(0L) / 1_000L
            recordingIndicatorView.text = String.format(
                java.util.Locale.US,
                "●  REC  %02d:%02d",
                elapsedSeconds / 60,
                elapsedSeconds % 60,
            )
            mainHandler.postDelayed(this, 250L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) {
            coordinator.bindCamera(::maybeAutoStart)
        } else {
            statusView.setText(R.string.camera_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        autoStartRequested = intent.getBooleanExtra(EXTRA_AUTO_START, false)
        autoExportRequested = intent.getBooleanExtra(
            EXTRA_AUTO_EXPORT_RECOVERY,
            false,
        )
        requestedRecoverySessionId = intent.getStringExtra(
            EXTRA_RECOVERY_SESSION_ID,
        )
        autoStopAfterSeconds = intent.getLongExtra(
            EXTRA_AUTO_STOP_AFTER_SECONDS,
            0L,
        ).coerceAtLeast(0L)
        autoStartDelaySeconds = intent.getLongExtra(
            EXTRA_AUTO_START_DELAY_SECONDS,
            0L,
        ).coerceAtLeast(0L)
        autoExposureProbeProfile = ExposureProbeProfile.parse(
            intent.getStringExtra(EXTRA_AUTO_EXPOSURE_PROBE_PROFILE),
        )
        if (
            autoExposureProbeProfile == ExposureProbeProfile.NONE &&
            intent.getBooleanExtra(EXTRA_AUTO_EXPOSURE_PROBE, false)
        ) {
            autoExposureProbeProfile = ExposureProbeProfile.FULL_RANGE
        }
        val initialProfile = intent.getStringExtra(
            EXTRA_INITIAL_ANALYSIS_PROFILE,
        )?.let { requested ->
            AnalysisProfile.entries.firstOrNull {
                it.name == requested.uppercase()
            }
        } ?: AnalysisProfile.FULL
        val feedbackMode = FeedbackMode.parse(
            intent.getStringExtra(EXTRA_FEEDBACK_MODE),
        )
        setContentView(buildContent())
        captureFeedback = AndroidCaptureFeedback(this, feedbackMode)
        coordinator = CaptureCoordinator(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            initialAnalysisProfile = initialProfile,
            feedback = captureFeedback,
            onHandStatusChanged = ::renderHandStatus,
        ) { message ->
            renderStatus(message)
            renderRecordButton()
        }
        val handFixturePath = intent.getStringExtra(EXTRA_HAND_FIXTURE_PATH)
        val debuggable = (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
            ) != 0
        if (debuggable && handFixturePath != null) {
            runHandFixtureProbe(handFixturePath)
            return
        }
        runStartupRecoveryScan()
        requestPermissionsOrBind()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        coordinator.close()
        captureFeedback.close()
        startupIo.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        captureFrameView = View(this).apply {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        root.addView(
            captureFrameView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                setMargins(8, 8, 8, 8)
            },
        )

        val brandView = TextView(this).apply {
            text = "FIRSTTAKE  •  LIVE QA"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setPadding(24, 14, 24, 14)
            background = roundedBackground(
                color = Color.argb(190, 2, 6, 23),
                radiusDp = 18f,
            )
        }
        root.addView(
            brandView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                setMargins(36, 30, 24, 24)
            },
        )

        recordingIndicatorView = TextView(this).apply {
            text = "●  REC  00:00"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(18, 10, 18, 10)
            background = roundedBackground(
                color = Color.argb(225, 185, 28, 28),
                radiusDp = 16f,
            )
            visibility = View.GONE
        }
        root.addView(
            recordingIndicatorView,
            FrameLayout.LayoutParams(
                430,
                62,
                Gravity.TOP or Gravity.START,
            ).apply {
                setMargins(36, 102, 24, 24)
            },
        )

        handsView = TextView(this).apply {
            text = "HAND CHECK READY"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(20, 12, 20, 12)
            background = roundedBackground(
                color = COLOR_NEUTRAL,
                radiusDp = 16f,
            )
        }
        root.addView(
            handsView,
            FrameLayout.LayoutParams(
                430,
                72,
                Gravity.TOP or Gravity.START,
            ).apply {
                setMargins(36, 178, 24, 24)
            },
        )

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(6f, 1.0f)
            setPadding(34, 22, 34, 24)
            text = "GETTING READY\nChecking camera and sensors"
            background = roundedBackground(
                color = COLOR_NEUTRAL,
                radiusDp = 24f,
            )
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                940,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                setMargins(24, 28, 24, 24)
            },
        )

        debugButton = Button(this).apply {
            text = "DETAILS"
            textSize = 13f
            setTextColor(Color.WHITE)
            minWidth = 0
            minHeight = 0
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            backgroundTintList = ColorStateList.valueOf(
                Color.argb(210, 15, 23, 42),
            )
            setOnClickListener {
                debugView.visibility = if (
                    debugView.visibility == View.VISIBLE
                ) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            }
        }
        root.addView(
            debugButton,
            FrameLayout.LayoutParams(
                260,
                88,
                Gravity.TOP or Gravity.END,
            ).apply {
                setMargins(24, 28, 36, 24)
            },
        )

        debugView = TextView(this).apply {
            setTextColor(Color.rgb(226, 232, 240))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(22, 18, 22, 18)
            background = roundedBackground(
                color = Color.argb(235, 2, 6, 23),
                radiusDp = 18f,
                strokeColor = Color.rgb(71, 85, 105),
            )
            visibility = View.GONE
        }
        root.addView(
            debugView,
            FrameLayout.LayoutParams(
                720,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                setMargins(24, 122, 36, 24)
            },
        )

        recordButton = Button(this).apply {
            text = "START"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            minWidth = 0
            minHeight = 0
            gravity = Gravity.CENTER
            setPadding(18, 0, 18, 0)
            backgroundTintList = ColorStateList.valueOf(COLOR_RECORD)
            setOnClickListener {
                if (coordinator.isRecording()) {
                    coordinator.stopRecording()
                } else {
                    // The flagship is a head-worn voice-guided capture. Keep
                    // the MP4 silent so spoken corrections stay available.
                    coordinator.startRecording(withAudio = false)
                }
            }
        }
        root.addView(
            recordButton,
            FrameLayout.LayoutParams(
                500,
                126,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                setMargins(24, 24, 24, 38)
            },
        )

        exportEvidenceButton = Button(this).apply {
            text = "EXPORT EVIDENCE"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(
                Color.rgb(30, 41, 59),
            )
            visibility = View.GONE
            setOnClickListener {
                exportRecoveryEvidence()
            }
        }
        root.addView(
            exportEvidenceButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                110,
                Gravity.BOTTOM or Gravity.START,
            ).apply {
                setMargins(24, 24, 24, 32)
            },
        )
        return root
    }

    private fun requestPermissionsOrBind() {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            coordinator.bindCamera(::maybeAutoStart)
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                ),
            )
        }
    }

    private fun runHandFixtureProbe(path: String) {
        recordButton.visibility = View.GONE
        debugButton.visibility = View.GONE
        statusView.text = "CHECKING ONE HAND\nOn-device model"
        startupIo.execute {
            val bitmap = BitmapFactory.decodeFile(path)
            val observation = if (bitmap == null) {
                null
            } else {
                try {
                    OnDeviceHandBaseline(this).use { baseline ->
                        baseline.analyze(bitmap)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            val logLine = if (observation == null) {
                "HAND_FIXTURE_RESULT status=DECODE_ERROR path=$path"
            } else {
                "HAND_FIXTURE_RESULT status=${observation.status.name} " +
                "globalHands=${observation.global?.detectedHands} " +
                "bottomHands=${observation.bottom?.detectedHands} " +
                "globalBoxes=${observation.global?.boxes} " +
                "bottomBoxes=${observation.bottom?.boxes} " +
                "inferenceNs=${observation.inferenceNs} " +
                    "modelSha256=${observation.modelSha256} " +
                    "error=${observation.error}"
            }
            Log.i(LOG_TAG, logLine)
            runOnUiThread {
                if (!isDestroyed) {
                    if (observation == null) {
                        statusView.text = "FIXTURE ERROR\nImage could not open"
                        statusView.background = roundedBackground(
                            color = COLOR_ERROR,
                            radiusDp = 24f,
                        )
                    } else {
                        renderHandStatus(observation.status)
                        statusView.text =
                            "ONE-HAND MODEL\n${observation.status.name}"
                        statusView.background = roundedBackground(
                            color = when (observation.status) {
                                HandBaselineStatus.HAND_VISIBLE_CANDIDATE ->
                                    COLOR_GOOD
                                HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE ->
                                    COLOR_WARNING
                                HandBaselineStatus.UNKNOWN -> COLOR_NEUTRAL
                                HandBaselineStatus.MODEL_ERROR -> COLOR_ERROR
                            },
                            radiusDp = 24f,
                        )
                    }
                    debugView.text = logLine
                }
            }
        }
    }

    private fun runStartupRecoveryScan() {
        startupIo.execute {
            val scanner = SessionRecoveryScanner(
                sessionsRoot = sessionsRoot(),
                videoInspector = AndroidRecoveryVideoInspector,
            )
            val requestedId = requestedRecoverySessionId
            val reports = if (requestedId == null) {
                scanner.scan()
            } else {
                listOf(scanner.scanSession(requestedId))
            }
            val attention = reports.filter {
                it.state in setOf(
                    SessionRecoveryState.INTERRUPTED_RECOVERABLE,
                    SessionRecoveryState.INTERRUPTED_PARTIAL,
                    SessionRecoveryState.CORRUPT,
                    SessionRecoveryState.NOT_ASSESSABLE,
                )
            }
            val notice = when {
                attention.isEmpty() -> null
                attention.size == 1 ->
                    "Recovery scan · 1 session needs inspection " +
                        "(${attention.single().state})"
                else ->
                    "Recovery scan · ${attention.size} sessions need inspection"
            }
            runOnUiThread {
                if (!isDestroyed) {
                    recoverySessionIds = attention.map { it.sessionId }.toSet()
                    recoveryNotice = notice
                    exportEvidenceButton.visibility = if (attention.isEmpty()) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                    renderStatus(primaryStatus)
                    if (autoExportRequested && attention.isNotEmpty()) {
                        autoExportRequested = false
                        val requestedId = requestedRecoverySessionId
                        if (
                            requestedId != null &&
                            attention.any { it.sessionId == requestedId }
                        ) {
                            recoverySessionIds = setOf(requestedId)
                        }
                        exportRecoveryEvidence()
                    }
                }
            }
        }
    }

    private fun exportRecoveryEvidence() {
        val targetSessionIds = recoverySessionIds
        if (targetSessionIds.isEmpty()) {
            return
        }
        exportEvidenceButton.isEnabled = false
        renderStatus("Hashing source artifacts for evidence export…")
        startupIo.execute {
            val scanner = SessionRecoveryScanner(
                sessionsRoot = sessionsRoot(),
                videoInspector = AndroidRecoveryVideoInspector,
            )
            val reports = targetSessionIds.map { sessionId ->
                scanner.scanSession(
                    sessionId = sessionId,
                    hashArtifacts = true,
                )
            }
            val evidenceRoot = File(
                getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir,
                "FirstTakeEvidence",
            )
            val device = DeviceEvidence(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidSdk = Build.VERSION.SDK_INT,
                appVersion = packageManager.getPackageInfo(
                    packageName,
                    0,
                ).versionName ?: "unknown",
                buildFingerprint = Build.FINGERPRINT,
            )
            val exports = mutableListOf<File>()
            val errors = mutableListOf<String>()
            reports.forEach { report ->
                val safeSessionId = report.sessionId.replace(
                    Regex("[^A-Za-z0-9._-]"),
                    "_",
                )
                val target = File(
                    evidenceRoot,
                    "$safeSessionId-${System.currentTimeMillis()}",
                )
                try {
                    exports += RecoveryEvidenceBundleExporter.export(
                        bundleDirectory = target,
                        report = report,
                        device = device,
                        sourceSessionDirectory = File(
                            sessionsRoot(),
                            report.sessionId,
                        ),
                    ).directory
                } catch (error: Exception) {
                    errors += "${report.sessionId}: ${error.message}"
                }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    exportEvidenceButton.isEnabled = true
                    val message = when {
                        errors.isNotEmpty() ->
                            "Evidence export failed · ${errors.joinToString()}"
                        exports.isEmpty() -> "No recovery evidence exported"
                        else ->
                            "Evidence exported · ${exports.size} bundle(s)\n" +
                                evidenceRoot.absolutePath
                    }
                    renderStatus(message)
                    exports.forEach { directory ->
                        Log.i(
                            LOG_TAG,
                            "EVIDENCE_EXPORTED path=${directory.absolutePath}",
                        )
                    }
                }
            }
        }
    }

    private fun maybeAutoStart() {
        if (!autoStartRequested) {
            return
        }
        autoStartRequested = false
        if (autoStartDelaySeconds > 0) {
            mainHandler.postDelayed(
                ::startAutomatedRecording,
                autoStartDelaySeconds * 1_000L,
            )
        } else {
            startAutomatedRecording()
        }
    }

    private fun startAutomatedRecording() {
        if (isDestroyed || coordinator.isRecording()) {
            return
        }
        coordinator.startRecording(withAudio = false)
        if (autoExposureProbeProfile != ExposureProbeProfile.NONE) {
            scheduleControlledExposureProbe()
        }
        if (autoStopAfterSeconds > 0) {
            mainHandler.postDelayed(
                {
                    if (
                        !isDestroyed &&
                        coordinator.isRecording()
                    ) {
                        coordinator.stopRecording()
                    }
                },
                autoStopAfterSeconds * 1_000L,
            )
        }
    }

    private fun scheduleControlledExposureProbe() {
        val steps = when (autoExposureProbeProfile) {
            ExposureProbeProfile.NONE -> emptyList()
            ExposureProbeProfile.FULL_RANGE -> listOf(
                5_000L to ControlledExposureLevel.MINIMUM,
                11_000L to ControlledExposureLevel.NOMINAL,
                16_000L to ControlledExposureLevel.MAXIMUM,
                22_000L to ControlledExposureLevel.NOMINAL,
            )
            ExposureProbeProfile.PERSISTENT_BRIGHT -> listOf(
                5_000L to ControlledExposureLevel.MAXIMUM,
                11_000L to ControlledExposureLevel.NOMINAL,
            )
            ExposureProbeProfile.TRANSIENT_BRIGHT -> listOf(
                5_000L to ControlledExposureLevel.MAXIMUM,
                5_750L to ControlledExposureLevel.NOMINAL,
            )
        }
        steps.forEach { (delayMs, level) ->
            mainHandler.postDelayed(
                {
                    if (
                        !isDestroyed &&
                        coordinator.isRecording()
                    ) {
                        coordinator.setControlledExposureProbe(level)
                    }
                },
                delayMs,
            )
        }
    }

    private fun sessionsRoot(): File {
        val moviesRoot = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: filesDir
        return File(moviesRoot, "FirstTake")
    }

    private fun renderStatus(message: String) {
        primaryStatus = message
        val presentation = operatorPresentation(message)
        statusView.text = buildString {
            append(presentation.title)
            presentation.instruction
                .takeIf { it.isNotBlank() }
                ?.let {
                    append("\n")
                    append(it)
                }
        }
        statusView.background = roundedBackground(
            color = presentation.color,
            radiusDp = 24f,
        )
        debugView.text = listOfNotNull(
            message.takeIf { it.isNotBlank() },
            recoveryNotice,
        ).joinToString("\n")
        renderRecordButton()
        renderCaptureFrame(presentation)
        if (
            coordinator.isRecording() &&
            message.contains("SAFETY_ONLY")
        ) {
            renderHandsPaused("HAND CHECK PAUSED - DEVICE HOT")
        } else if (
            coordinator.isRecording() &&
            message.contains("WRITERS_ONLY")
        ) {
            renderHandsPaused("HAND CHECK PAUSED - CAPTURE FIRST")
        }
    }

    private fun renderRecordButton() {
        if (!::recordButton.isInitialized || !::coordinator.isInitialized) {
            return
        }
        val recording = coordinator.isRecording()
        if (recording != lastRecordingUiState) {
            lastRecordingUiState = recording
            if (recording) {
                recordingStartedUiMs = SystemClock.elapsedRealtime()
                recordingIndicatorView.visibility = View.VISIBLE
                mainHandler.removeCallbacks(recordingClock)
                recordingClock.run()
                renderHandStatus(HandBaselineStatus.UNKNOWN)
            } else {
                recordingStartedUiMs = null
                mainHandler.removeCallbacks(recordingClock)
                recordingIndicatorView.visibility = View.GONE
                handsView.text = "HAND CHECK READY"
                handsView.background = roundedBackground(
                    color = COLOR_NEUTRAL,
                    radiusDp = 16f,
                )
            }
        }
        recordButton.text = if (recording) {
            "STOP & SAVE"
        } else {
            "START"
        }
        recordButton.backgroundTintList = ColorStateList.valueOf(
            if (recording) COLOR_STOP else COLOR_RECORD,
        )
    }

    private fun renderHandStatus(status: HandBaselineStatus) {
        if (!::handsView.isInitialized) {
            return
        }
        val (text, color) = when (status) {
            HandBaselineStatus.HAND_VISIBLE_CANDIDATE ->
                "HAND VISIBLE" to COLOR_GOOD
            HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE ->
                "MOVE HAND INWARD" to COLOR_WARNING
            HandBaselineStatus.UNKNOWN ->
                "LOOKING FOR HAND" to COLOR_NEUTRAL
            HandBaselineStatus.MODEL_ERROR ->
                "HAND CHECK OFF" to COLOR_ERROR
        }
        handsView.text = text
        handsView.background = roundedBackground(
            color = color,
            radiusDp = 16f,
        )
    }

    private fun renderHandsPaused(message: String) {
        handsView.text = message
        handsView.background = roundedBackground(
            color = COLOR_NEUTRAL,
            radiusDp = 16f,
        )
    }

    private fun operatorPresentation(message: String): OperatorPresentation {
        val normalized = message.trim()
        return when {
            normalized.startsWith("Camera ready") ->
                OperatorPresentation(
                    title = "READY",
                    instruction = "Tap START to begin",
                    color = COLOR_NEUTRAL,
                )
            normalized.startsWith("Recording") -> {
                val fixes = normalized.lineSequence()
                    .map(String::trim)
                    .filter { it.startsWith("Fix now") }
                    .map {
                        it.substringAfter("Fix now")
                            .trim()
                            .trimStart('·', '-', ' ')
                    }
                    .filter(String::isNotBlank)
                    .toList()
                if (fixes.isEmpty()) {
                    OperatorPresentation(
                        title = "CAPTURE LOOKS GOOD",
                        instruction = "Keep going",
                        color = COLOR_GOOD,
                    )
                } else {
                    OperatorPresentation(
                        title = "FIX NOW",
                        instruction = fixes.joinToString("\n"),
                        color = COLOR_WARNING,
                    )
                }
            }
            normalized.startsWith("Finalizing") ->
                OperatorPresentation(
                    title = "SAVING CAPTURE",
                    instruction = "Do not close the app",
                    color = COLOR_NEUTRAL,
                )
            normalized.startsWith("Saved") ->
                OperatorPresentation(
                    title = "CAPTURE SAVED",
                    instruction = "Video and sensor data verified",
                    color = COLOR_GOOD,
                )
            normalized.startsWith("Capture incomplete") ->
                OperatorPresentation(
                    title = "CAPTURE NEEDS REVIEW",
                    instruction = "Open DETAILS for the failed check",
                    color = COLOR_ERROR,
                )
            normalized == "Capture recovered" ->
                OperatorPresentation(
                    title = "CAPTURE RECOVERED",
                    instruction = "Keep going",
                    color = COLOR_GOOD,
                )
            normalized == "Hand back in frame" ->
                OperatorPresentation(
                    title = "HAND BACK IN FRAME",
                    instruction = "Keep going",
                    color = COLOR_GOOD,
                )
            normalized.startsWith("Phone too hot") ->
                OperatorPresentation(
                    title = "PHONE TOO HOT",
                    instruction = "Wait before recording",
                    color = COLOR_ERROR,
                )
            normalized.startsWith("Could not") ||
                normalized.contains("failed", ignoreCase = true) ->
                OperatorPresentation(
                    title = "CAPTURE NOT STARTED",
                    instruction = "Open DETAILS for the cause",
                    color = COLOR_ERROR,
                )
            normalized.isBlank() ->
                OperatorPresentation(
                    title = "GETTING READY",
                    instruction = "Checking camera and sensors",
                    color = COLOR_NEUTRAL,
                )
            else ->
                OperatorPresentation(
                    title = "GETTING READY",
                    instruction = "Checking camera and sensors",
                    color = COLOR_NEUTRAL,
                )
        }
    }

    private fun renderCaptureFrame(presentation: OperatorPresentation) {
        if (!::captureFrameView.isInitialized || !::coordinator.isInitialized) {
            return
        }
        if (!coordinator.isRecording()) {
            captureFrameView.visibility = View.GONE
            return
        }
        val warning = presentation.title == "FIX NOW"
        captureFrameView.visibility = View.VISIBLE
        captureFrameView.background = roundedBackground(
            color = Color.TRANSPARENT,
            radiusDp = 12f,
            strokeColor = if (warning) COLOR_WARNING else COLOR_GOOD,
            strokeWidthDp = if (warning) 8 else 3,
        )
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
        strokeColor?.let {
            setStroke(
                (
                    resources.displayMetrics.density * strokeWidthDp
                    ).toInt().coerceAtLeast(1),
                it,
            )
        }
    }

    private companion object {
        const val EXTRA_AUTO_START = "firsttake.auto_start"
        const val EXTRA_AUTO_EXPORT_RECOVERY =
            "firsttake.auto_export_recovery"
        const val EXTRA_RECOVERY_SESSION_ID =
            "firsttake.recovery_session_id"
        const val EXTRA_INITIAL_ANALYSIS_PROFILE =
            "firsttake.initial_analysis_profile"
        const val EXTRA_AUTO_STOP_AFTER_SECONDS =
            "firsttake.auto_stop_after_seconds"
        const val EXTRA_AUTO_START_DELAY_SECONDS =
            "firsttake.auto_start_delay_seconds"
        const val EXTRA_FEEDBACK_MODE = "firsttake.feedback_mode"
        const val EXTRA_AUTO_EXPOSURE_PROBE =
            "firsttake.auto_exposure_probe"
        const val EXTRA_AUTO_EXPOSURE_PROBE_PROFILE =
            "firsttake.auto_exposure_probe_profile"
        const val EXTRA_HAND_FIXTURE_PATH =
            "firsttake.hand_fixture_path"
        const val LOG_TAG = "FirstTakeProbe"
        val COLOR_NEUTRAL = Color.argb(225, 15, 23, 42)
        val COLOR_GOOD = Color.argb(230, 21, 128, 61)
        val COLOR_WARNING = Color.argb(235, 180, 83, 9)
        val COLOR_ERROR = Color.argb(235, 185, 28, 28)
        val COLOR_RECORD = Color.rgb(220, 38, 38)
        val COLOR_STOP = Color.rgb(127, 29, 29)
    }

    private data class OperatorPresentation(
        val title: String,
        val instruction: String,
        val color: Int,
    )
}

private enum class ExposureProbeProfile {
    NONE,
    FULL_RANGE,
    PERSISTENT_BRIGHT,
    TRANSIENT_BRIGHT;

    companion object {
        fun parse(value: String?): ExposureProbeProfile =
            entries.firstOrNull {
                it.name == value?.uppercase()
            } ?: NONE
    }
}
