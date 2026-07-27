package dev.firsttake.probe

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

enum class SessionRecoveryState {
    COMPLETE,
    INTERRUPTED_RECOVERABLE,
    INTERRUPTED_PARTIAL,
    CORRUPT,
    EMPTY,
    NOT_ASSESSABLE,
}

enum class ArtifactHashState {
    NOT_REQUESTED,
    COMPUTED,
    FAILED,
}

data class ArtifactEvidence(
    val name: String,
    val present: Boolean,
    val bytes: Long?,
    val sha256: String?,
    val hashState: ArtifactHashState,
    val error: String?,
)

data class VideoArtifactInspection(
    val readable: Boolean,
    val durationNs: Long?,
    val sampleCount: Int?,
    val error: String?,
)

fun interface RecoveryVideoInspector {
    fun inspect(file: File): VideoArtifactInspection
}

data class SessionRecoveryEvidence(
    val sessionId: String,
    val state: SessionRecoveryState,
    val lastEventType: String?,
    val validThroughSequence: Long?,
    val ignoredTornWalTailBytes: Int,
    val walErrors: List<String>,
    val wal: ArtifactEvidence,
    val videoStartedElapsedRealtimeNs: Long?,
    val lastCheckpointElapsedRealtimeNs: Long?,
    val lastCheckpointDurationNs: Long?,
    val video: ArtifactEvidence,
    val videoInspection: VideoArtifactInspection?,
    val imu: ArtifactEvidence,
    val imuMcapReport: McapPrefixReport?,
    val telemetry: ArtifactEvidence,
    val warnings: List<String>,
)

/**
 * Read-only startup inspection of session directories.
 *
 * Recovery and export are intentionally separate operations. This scanner
 * never truncates a WAL, finalizes a container, or writes a report beside the
 * source artifacts.
 */
class SessionRecoveryScanner(
    private val sessionsRoot: File,
    private val videoInspector: RecoveryVideoInspector,
) {
    fun scan(hashArtifacts: Boolean = false): List<SessionRecoveryEvidence> {
        if (!sessionsRoot.exists()) {
            return emptyList()
        }
        if (!sessionsRoot.isDirectory) {
            return listOf(
                notAssessableRoot("session root is not a directory"),
            )
        }

        val canonicalRoot = try {
            sessionsRoot.canonicalFile
        } catch (error: Exception) {
            return listOf(
                notAssessableRoot(
                    "could not resolve session root: ${safeMessage(error)}",
                ),
            )
        }

        return sessionsRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .map { directory ->
                inspectSession(
                    canonicalRoot = canonicalRoot,
                    directory = directory,
                    hashArtifacts = hashArtifacts,
                )
            }
    }

    fun scanSession(
        sessionId: String,
        hashArtifacts: Boolean = false,
    ): SessionRecoveryEvidence {
        if (
            sessionId.isBlank() ||
            File(sessionId).name != sessionId ||
            sessionId in setOf(".", "..")
        ) {
            return notAssessableSession(
                sessionId = sessionId,
                warning = "invalid session identifier",
            )
        }
        if (!sessionsRoot.isDirectory) {
            return notAssessableSession(
                sessionId = sessionId,
                warning = "session root is not a directory",
            )
        }
        val canonicalRoot = try {
            sessionsRoot.canonicalFile
        } catch (error: Exception) {
            return notAssessableSession(
                sessionId = sessionId,
                warning =
                    "could not resolve session root: ${safeMessage(error)}",
            )
        }
        val directory = File(canonicalRoot, sessionId)
        if (!directory.isDirectory) {
            return notAssessableSession(
                sessionId = sessionId,
                warning = "requested session directory does not exist",
            )
        }
        return inspectSession(
            canonicalRoot = canonicalRoot,
            directory = directory,
            hashArtifacts = hashArtifacts,
        )
    }

    private fun inspectSession(
        canonicalRoot: File,
        directory: File,
        hashArtifacts: Boolean,
    ): SessionRecoveryEvidence {
        val canonicalDirectory = try {
            directory.canonicalFile
        } catch (error: Exception) {
            return notAssessableSession(
                sessionId = directory.name,
                warning = "could not resolve session path: ${safeMessage(error)}",
            )
        }
        if (canonicalDirectory.parentFile != canonicalRoot) {
            return notAssessableSession(
                sessionId = directory.name,
                warning = "session path escapes the managed root",
            )
        }

        val walFile = File(canonicalDirectory, WAL_NAME)
        val videoFile = File(canonicalDirectory, VIDEO_NAME)
        val imuFile = selectImuFile(canonicalDirectory)
        val telemetryFile = selectTelemetryFile(canonicalDirectory)
        val hasAnyArtifact =
            walFile.exists() ||
                videoFile.exists() ||
                imuFile?.exists() == true ||
                telemetryFile?.exists() == true

        if (!hasAnyArtifact) {
            return emptySession(directory.name)
        }

        val wal = DurableSessionJournal.recover(walFile)
        val walEvidence = inspectArtifact(walFile, hashArtifacts)
        val videoEvidence = inspectArtifact(videoFile, hashArtifacts)
        val imuEvidence = if (imuFile == null) {
            absentArtifact(IMU_MCAP_NAME)
        } else {
            inspectArtifact(imuFile, hashArtifacts)
        }
        val telemetryEvidence = if (telemetryFile == null) {
            absentArtifact(TELEMETRY_NAME)
        } else {
            inspectArtifact(telemetryFile, hashArtifacts)
        }
        val videoInspection = if (videoFile.isFile && videoFile.length() > 0L) {
            try {
                videoInspector.inspect(videoFile)
            } catch (error: Exception) {
                VideoArtifactInspection(
                    readable = false,
                    durationNs = null,
                    sampleCount = null,
                    error = safeMessage(error),
                )
            }
        } else {
            null
        }
        val imuMcapReport = if (
            imuFile != null &&
            imuFile.extension.equals("mcap", ignoreCase = true) &&
            imuFile.isFile &&
            imuFile.length() > 0L
        ) {
            FirstTakeMcapRecovery.scan(imuFile)
        } else {
            null
        }
        val lastEvent = wal.records.lastOrNull()?.type
        val hasCommit = wal.records.any { it.type == "SESSION_COMMITTED" }
        val videoStartedElapsedRealtimeNs = wal.records
            .firstOrNull { it.type == "VIDEO_STARTED" }
            ?.elapsedRealtimeNs
        val lastCheckpoint = wal.records
            .asReversed()
            .firstOrNull { it.type == "VIDEO_CHECKPOINT" }
        val lastCheckpointDurationNs = lastCheckpoint
            ?.payload
            ?.let(::extractRecordedDurationNs)
        val videoUsable = videoEvidence.present &&
            videoInspection?.readable == true
        val imuUsable = imuEvidence.present &&
            (imuEvidence.bytes ?: 0L) > 0L &&
            (
                imuMcapReport == null ||
                    imuMcapReport.state in setOf(
                        McapFileState.FINALIZED_VALID,
                        McapFileState.RECOVERABLE_PREFIX,
                    )
                )
        val imuFinalized = imuMcapReport?.state ==
            McapFileState.FINALIZED_VALID || (
            imuMcapReport == null && imuUsable
            )
        val warnings = buildList {
            if (!walFile.exists()) {
                add("session WAL is missing")
            }
            if (wal.ignoredTornTailBytes > 0) {
                add(
                    "ignored ${wal.ignoredTornTailBytes} bytes after the " +
                        "last complete WAL record",
                )
            }
            if (videoEvidence.present && videoInspection?.readable != true) {
                add("video artifact is present but not readable")
            }
            if (!imuUsable) {
                add("IMU artifact is missing or empty")
            }
            if (imuMcapReport?.state == McapFileState.RECOVERABLE_PREFIX) {
                add("IMU MCAP needs recovery from its complete record prefix")
            }
            if (imuMcapReport?.state == McapFileState.CORRUPT) {
                add("IMU MCAP has a corrupt structural prefix")
            }
            videoEvidence.error?.let { add("video artifact: $it") }
            imuEvidence.error?.let { add("IMU artifact: $it") }
            telemetryEvidence.error?.let { add("telemetry artifact: $it") }
        }

        val state = when {
            wal.errors.isNotEmpty() -> SessionRecoveryState.CORRUPT
            !walFile.exists() -> SessionRecoveryState.CORRUPT
            hasCommit && videoUsable && imuUsable && imuFinalized ->
                SessionRecoveryState.COMPLETE
            hasCommit -> SessionRecoveryState.CORRUPT
            videoUsable && imuUsable ->
                SessionRecoveryState.INTERRUPTED_RECOVERABLE
            videoUsable || imuUsable ->
                SessionRecoveryState.INTERRUPTED_PARTIAL
            wal.records.isEmpty() ->
                SessionRecoveryState.EMPTY
            else -> SessionRecoveryState.INTERRUPTED_PARTIAL
        }

        return SessionRecoveryEvidence(
            sessionId = directory.name,
            state = state,
            lastEventType = lastEvent,
            validThroughSequence = wal.validThroughSequence,
            ignoredTornWalTailBytes = wal.ignoredTornTailBytes,
            walErrors = wal.errors,
            wal = walEvidence,
            videoStartedElapsedRealtimeNs = videoStartedElapsedRealtimeNs,
            lastCheckpointElapsedRealtimeNs =
                lastCheckpoint?.elapsedRealtimeNs,
            lastCheckpointDurationNs = lastCheckpointDurationNs,
            video = videoEvidence,
            videoInspection = videoInspection,
            imu = imuEvidence,
            imuMcapReport = imuMcapReport,
            telemetry = telemetryEvidence,
            warnings = warnings,
        )
    }

    private fun inspectArtifact(
        file: File,
        hashArtifacts: Boolean,
    ): ArtifactEvidence {
        if (!file.exists()) {
            return absentArtifact(file.name)
        }
        if (!file.isFile) {
            return ArtifactEvidence(
                name = file.name,
                present = true,
                bytes = null,
                sha256 = null,
                hashState = ArtifactHashState.FAILED,
                error = "artifact path is not a regular file",
            )
        }
        if (!hashArtifacts) {
            return ArtifactEvidence(
                name = file.name,
                present = true,
                bytes = file.length(),
                sha256 = null,
                hashState = ArtifactHashState.NOT_REQUESTED,
                error = null,
            )
        }
        return try {
            ArtifactEvidence(
                name = file.name,
                present = true,
                bytes = file.length(),
                sha256 = sha256(file),
                hashState = ArtifactHashState.COMPUTED,
                error = null,
            )
        } catch (error: Exception) {
            ArtifactEvidence(
                name = file.name,
                present = true,
                bytes = file.length(),
                sha256 = null,
                hashState = ArtifactHashState.FAILED,
                error = safeMessage(error),
            )
        }
    }

    private fun selectImuFile(directory: File): File? {
        val mcap = File(directory, SESSION_MCAP_NAME)
        if (mcap.exists()) {
            return mcap
        }
        val legacyMcap = File(directory, IMU_MCAP_NAME)
        if (legacyMcap.exists()) {
            return legacyMcap
        }
        val legacyJsonl = File(directory, IMU_JSONL_NAME)
        return legacyJsonl.takeIf { it.exists() }
    }

    private fun selectTelemetryFile(directory: File): File? {
        val qaEvents = File(directory, TELEMETRY_NAME)
        if (qaEvents.exists()) {
            return qaEvents
        }
        return File(directory, LEGACY_TELEMETRY_NAME)
            .takeIf { it.exists() }
    }

    private fun emptySession(sessionId: String): SessionRecoveryEvidence =
        SessionRecoveryEvidence(
            sessionId = sessionId,
            state = SessionRecoveryState.EMPTY,
            lastEventType = null,
            validThroughSequence = null,
            ignoredTornWalTailBytes = 0,
            walErrors = emptyList(),
            wal = absentArtifact(WAL_NAME),
            videoStartedElapsedRealtimeNs = null,
            lastCheckpointElapsedRealtimeNs = null,
            lastCheckpointDurationNs = null,
            video = absentArtifact(VIDEO_NAME),
            videoInspection = null,
            imu = absentArtifact(IMU_MCAP_NAME),
            imuMcapReport = null,
            telemetry = absentArtifact(TELEMETRY_NAME),
            warnings = emptyList(),
        )

    private fun notAssessableRoot(warning: String): SessionRecoveryEvidence =
        notAssessableSession(
            sessionId = "<sessions-root>",
            warning = warning,
        )

    private fun notAssessableSession(
        sessionId: String,
        warning: String,
    ): SessionRecoveryEvidence = SessionRecoveryEvidence(
        sessionId = sessionId,
        state = SessionRecoveryState.NOT_ASSESSABLE,
        lastEventType = null,
        validThroughSequence = null,
        ignoredTornWalTailBytes = 0,
        walErrors = emptyList(),
        wal = absentArtifact(WAL_NAME),
        videoStartedElapsedRealtimeNs = null,
        lastCheckpointElapsedRealtimeNs = null,
        lastCheckpointDurationNs = null,
        video = absentArtifact(VIDEO_NAME),
        videoInspection = null,
        imu = absentArtifact(IMU_MCAP_NAME),
        imuMcapReport = null,
        telemetry = absentArtifact(TELEMETRY_NAME),
        warnings = listOf(warning),
    )

    private fun absentArtifact(name: String): ArtifactEvidence =
        ArtifactEvidence(
            name = name,
            present = false,
            bytes = null,
            sha256 = null,
            hashState = ArtifactHashState.NOT_REQUESTED,
            error = null,
        )

    private fun extractRecordedDurationNs(payload: String): Long? =
        RECORDED_DURATION_PATTERN.find(payload)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun safeMessage(error: Exception): String =
        error.message ?: error.javaClass.simpleName

    private companion object {
        const val WAL_NAME = "session.wal"
        const val VIDEO_NAME = "capture.mp4"
        const val SESSION_MCAP_NAME = "session.mcap"
        const val IMU_MCAP_NAME = "imu.mcap"
        const val IMU_JSONL_NAME = "imu.jsonl"
        const val TELEMETRY_NAME = "qa-events.jsonl"
        const val LEGACY_TELEMETRY_NAME = "probe-telemetry.jsonl"
        val RECORDED_DURATION_PATTERN =
            Regex("\"recordedDurationNs\"\\s*:\\s*(\\d+)")
    }
}

object AndroidRecoveryVideoInspector : RecoveryVideoInspector {
    override fun inspect(file: File): VideoArtifactInspection {
        val report = Mp4IntegrityInspector.inspect(file)
        val firstTimestampUs = report.video?.firstTimestampUs
        val lastTimestampUs = report.video?.lastTimestampUs
        val durationNs = if (
            firstTimestampUs != null &&
            lastTimestampUs != null &&
            lastTimestampUs >= firstTimestampUs
        ) {
            (lastTimestampUs - firstTimestampUs) * 1_000L
        } else {
            null
        }
        return VideoArtifactInspection(
            readable = report.readable,
            durationNs = durationNs,
            sampleCount = report.video?.sampleCount,
            error = report.error,
        )
    }
}
