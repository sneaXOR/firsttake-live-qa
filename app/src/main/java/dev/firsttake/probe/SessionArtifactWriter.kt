package dev.firsttake.probe

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class SessionManifestInput(
    val sessionId: String,
    val createdAtUnixMs: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    val deviceManufacturer: String,
    val deviceModel: String,
    val deviceName: String,
    val androidRelease: String,
    val androidSdk: Int,
    val cameraId: String?,
    val cameraPhysicalId: String?,
    val cameraHorizontalFovDegrees: Double?,
    val cameraSelectionPolicy: String,
    val cameraTimestampSource: String,
    val cameraTimestampComparableToElapsedRealtime: Boolean,
    val audioEnabled: Boolean,
    val initialAnalysisProfile: AnalysisProfile,
    val finalAnalysisProfile: AnalysisProfile,
    val cameraMinimumZoomRatio: Double? = null,
    val cameraRequestedZoomRatio: Double? = null,
    val cameraAppliedZoomRatio: Double? = null,
)

data class SessionPostflightInput(
    val sessionId: String,
    val finalizedAtUnixMs: Long,
    val cameraXFinalizeError: Int,
    val video: Mp4IntegrityReport,
    val imu: ImuFinalizeReport,
    val telemetry: TelemetryChainReport?,
)

enum class SessionAcceptanceStatus {
    PASS,
    INCOMPLETE,
}

data class SessionArtifactReport(
    val manifest: File,
    val postflight: File,
    val hashes: File,
    val sha256: Map<String, String>,
    val acceptanceStatus: SessionAcceptanceStatus,
)

/**
 * Closes a successful physical session as a self-describing evidence bundle.
 *
 * Source artifacts are only read. New evidence files are written atomically,
 * and existing files are never overwritten.
 */
object SessionArtifactWriter {
    const val VIDEO_NAME = "capture.mp4"
    const val MCAP_NAME = "session.mcap"
    const val TELEMETRY_NAME = "qa-events.jsonl"
    const val WAL_NAME = "session.wal"
    const val MANIFEST_NAME = "manifest.json"
    const val POSTFLIGHT_NAME = "postflight.json"
    const val HASHES_NAME = "hashes.sha256"

    private val hashTargets = listOf(
        VIDEO_NAME,
        MCAP_NAME,
        TELEMETRY_NAME,
        WAL_NAME,
        MANIFEST_NAME,
        POSTFLIGHT_NAME,
    )

    fun write(
        sessionDirectory: File,
        manifest: SessionManifestInput,
        postflight: SessionPostflightInput,
    ): SessionArtifactReport {
        require(sessionDirectory.isDirectory) {
            "session directory does not exist"
        }
        require(manifest.sessionId == postflight.sessionId) {
            "manifest and postflight session identifiers differ"
        }
        val canonicalDirectory = sessionDirectory.canonicalFile
        val postflightFile = File(canonicalDirectory, POSTFLIGHT_NAME)
        val manifestFile = File(canonicalDirectory, MANIFEST_NAME)
        val hashesFile = File(canonicalDirectory, HASHES_NAME)

        atomicCreate(postflightFile, postflightJson(postflight))
        atomicCreate(manifestFile, manifestJson(manifest))

        val hashes = linkedMapOf<String, String>()
        for (name in hashTargets) {
            val artifact = File(canonicalDirectory, name)
            require(artifact.isFile) { "required artifact missing: $name" }
            require(artifact.canonicalFile.parentFile == canonicalDirectory) {
                "artifact escapes session directory: $name"
            }
            hashes[name] = sha256(artifact)
        }
        atomicCreate(
            hashesFile,
            hashes.entries.joinToString(
                separator = "\n",
                postfix = "\n",
            ) { (name, digest) -> "$digest  $name" },
        )
        return SessionArtifactReport(
            manifest = manifestFile,
            postflight = postflightFile,
            hashes = hashesFile,
            sha256 = hashes,
            acceptanceStatus = acceptanceStatus(postflight),
        )
    }

    private fun manifestJson(input: SessionManifestInput): String = """
        {
          "schemaVersion":"firsttake.session-manifest.v1",
          "sessionId":${jsonString(input.sessionId)},
          "createdAtUnixMs":${input.createdAtUnixMs},
          "app":{
            "package":"dev.firsttake.probe",
            "versionName":${jsonString(input.appVersionName)},
            "versionCode":${input.appVersionCode}
          },
          "device":{
            "manufacturer":${jsonString(input.deviceManufacturer)},
            "model":${jsonString(input.deviceModel)},
            "device":${jsonString(input.deviceName)},
            "androidRelease":${jsonString(input.androidRelease)},
            "androidSdk":${input.androidSdk}
          },
          "camera":{
            "cameraId":${jsonNullableString(input.cameraId)},
            "physicalCameraId":${jsonNullableString(input.cameraPhysicalId)},
            "horizontalFovDegrees":${jsonNullableNumber(input.cameraHorizontalFovDegrees)},
            "minimumZoomRatio":${jsonNullableNumber(input.cameraMinimumZoomRatio)},
            "requestedZoomRatio":${jsonNullableNumber(input.cameraRequestedZoomRatio)},
            "appliedZoomRatio":${jsonNullableNumber(input.cameraAppliedZoomRatio)},
            "timestampSource":${jsonString(input.cameraTimestampSource)},
            "timestampComparableToElapsedRealtime":${input.cameraTimestampComparableToElapsedRealtime},
            "requestedQuality":"FHD_1080P",
            "selectionPolicy":${jsonString(input.cameraSelectionPolicy)}
          },
          "capture":{
            "audioEnabled":${input.audioEnabled},
            "initialAnalysisProfile":${jsonString(input.initialAnalysisProfile.name)},
            "finalAnalysisProfile":${jsonString(input.finalAnalysisProfile.name)},
            "offline":true
          },
          "artifacts":[
            "capture.mp4",
            "session.mcap",
            "qa-events.jsonl",
            "session.wal",
            "manifest.json",
            "postflight.json",
            "hashes.sha256"
          ],
          "evidenceLevel":"SINGLE_DEVICE_SESSION",
          "limitations":[
            "This manifest proves artifact identity, not semantic task correctness.",
            "RGB-IMU content alignment requires a separate motion-rich validation."
          ]
        }
    """.trimIndent() + "\n"

    private fun postflightJson(input: SessionPostflightInput): String {
        val video = input.video.video
        val audio = input.video.audio
        return """
            {
              "schemaVersion":"firsttake.postflight.v1",
              "sessionId":${jsonString(input.sessionId)},
              "finalizedAtUnixMs":${input.finalizedAtUnixMs},
              "verdict":${jsonString(acceptanceStatus(input).name)},
              "cameraXFinalizeError":${input.cameraXFinalizeError},
              "video":{
                "readable":${input.video.readable},
                "mime":${jsonNullableString(video?.mime)},
                "width":${jsonNullableNumber(video?.width)},
                "height":${jsonNullableNumber(video?.height)},
                "declaredFrameRate":${jsonNullableNumber(video?.declaredFrameRate)},
                "sampleCount":${jsonNullableNumber(video?.sampleCount)},
                "firstTimestampUs":${jsonNullableNumber(video?.firstTimestampUs)},
                "lastTimestampUs":${jsonNullableNumber(video?.lastTimestampUs)},
                "medianDeltaUs":${jsonNullableNumber(video?.medianDeltaUs)},
                "p95DeltaUs":${jsonNullableNumber(video?.p95DeltaUs)},
                "maximumDeltaUs":${jsonNullableNumber(video?.maximumDeltaUs)},
                "largeGapCount":${jsonNullableNumber(video?.largeGapCount)},
                "error":${jsonNullableString(input.video.error)}
              },
              "audio":{
                "present":${audio != null},
                "mime":${jsonNullableString(audio?.mime)},
                "sampleCount":${jsonNullableNumber(audio?.sampleCount)},
                "audioVideoEndDeltaUs":${jsonNullableNumber(input.video.audioVideoEndDeltaUs)}
              },
              "mcap":{
                "finalized":${input.imu.finalized},
                "error":${jsonNullableString(input.imu.error)}
              },
              "telemetry":{
                "complete":${input.telemetry?.complete ?: false},
                "writtenRecords":${jsonNullableNumber(input.telemetry?.writtenRecords)},
                "droppedRecords":${jsonNullableNumber(input.telemetry?.droppedRecords)},
                "lastHash":${jsonNullableString(input.telemetry?.lastHash)},
                "error":${jsonNullableString(input.telemetry?.error)}
              },
              "rgbImuContentAlignment":"NOT_ASSESSED"
            }
        """.trimIndent() + "\n"
    }

    fun acceptanceStatus(
        input: SessionPostflightInput,
    ): SessionAcceptanceStatus =
        if (
            input.cameraXFinalizeError == 0 &&
            input.video.readable &&
            setOf(input.video.video?.width, input.video.video?.height) ==
                setOf(1_920, 1_080) &&
            input.imu.finalized &&
            input.telemetry?.complete == true
        ) {
            SessionAcceptanceStatus.PASS
        } else {
            SessionAcceptanceStatus.INCOMPLETE
        }

    private fun atomicCreate(target: File, content: String) {
        require(!target.exists()) {
            "refusing to overwrite existing artifact: ${target.name}"
        }
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        require(!temporary.exists()) {
            "stale temporary artifact exists: ${temporary.name}"
        }
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            check(temporary.renameTo(target)) {
                "could not publish ${target.name} atomically"
            }
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private fun jsonNullableString(value: String?): String =
        value?.let(::jsonString) ?: "null"

    private fun jsonNullableNumber(value: Number?): String =
        value?.toString() ?: "null"
}
