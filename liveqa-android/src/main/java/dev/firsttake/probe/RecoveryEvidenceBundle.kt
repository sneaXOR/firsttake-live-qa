package dev.firsttake.probe

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class DeviceEvidence(
    val manufacturer: String,
    val model: String,
    val androidSdk: Int,
    val appVersion: String,
    val buildFingerprint: String?,
)

data class RecoveryEvidenceBundle(
    val directory: File,
    val recoveryReport: File,
    val deviceReport: File,
    val sourceHashes: File,
    val manifest: File,
    val recoveredImu: File?,
)

object RecoveryEvidenceJson {
    fun encode(report: SessionRecoveryEvidence): String {
        val checkpointDelta = if (
            report.lastCheckpointDurationNs != null &&
            report.videoInspection?.durationNs != null
        ) {
            report.videoInspection.durationNs -
                report.lastCheckpointDurationNs
        } else {
            null
        }
        return jsonObject(
            "schemaVersion" to jsonString("firsttake.recovery.v1"),
            "sessionId" to jsonString(report.sessionId),
            "state" to jsonString(report.state.name),
            "lastEventType" to jsonNullableString(report.lastEventType),
            "wal" to jsonObject(
                "validThroughSequence" to jsonNullableLong(
                    report.validThroughSequence,
                ),
                "ignoredTornTailBytes" to report.ignoredTornWalTailBytes.toString(),
                "errors" to jsonStringArray(report.walErrors),
                "artifact" to artifactJson(report.wal),
            ),
            "video" to jsonObject(
                "artifact" to artifactJson(report.video),
                "readable" to jsonNullableBoolean(
                    report.videoInspection?.readable,
                ),
                "durationNs" to jsonNullableLong(
                    report.videoInspection?.durationNs,
                ),
                "sampleCount" to jsonNullableInt(
                    report.videoInspection?.sampleCount,
                ),
                "inspectionError" to jsonNullableString(
                    report.videoInspection?.error,
                ),
                "videoStartedElapsedRealtimeNs" to jsonNullableLong(
                    report.videoStartedElapsedRealtimeNs,
                ),
                "lastCheckpointElapsedRealtimeNs" to jsonNullableLong(
                    report.lastCheckpointElapsedRealtimeNs,
                ),
                "lastCheckpointDurationNs" to jsonNullableLong(
                    report.lastCheckpointDurationNs,
                ),
                "readableEndMinusCheckpointNs" to jsonNullableLong(
                    checkpointDelta,
                ),
                "measuredLossUpperBoundNs" to "null",
            ),
            "imu" to jsonObject(
                "artifact" to artifactJson(report.imu),
                "mcap" to mcapJson(report.imuMcapReport),
            ),
            "telemetry" to jsonObject(
                "artifact" to artifactJson(report.telemetry),
                "format" to jsonString(
                    "firsttake.telemetry-envelope.v1 hash chain",
                ),
            ),
            "warnings" to jsonStringArray(report.warnings),
            "limitations" to jsonStringArray(
                listOf(
                    "A measured media-loss bound requires the physical " +
                        "randomized kill campaign.",
                    "A readable artifact is not evidence of downstream model " +
                        "utility.",
                ),
            ),
        ) + "\n"
    }

    fun encodeDevice(device: DeviceEvidence): String = jsonObject(
        "schemaVersion" to jsonString("firsttake.device-evidence.v1"),
        "manufacturer" to jsonString(device.manufacturer),
        "model" to jsonString(device.model),
        "androidSdk" to device.androidSdk.toString(),
        "appVersion" to jsonString(device.appVersion),
        "buildFingerprint" to jsonNullableString(device.buildFingerprint),
    ) + "\n"

    private fun artifactJson(artifact: ArtifactEvidence): String = jsonObject(
        "name" to jsonString(artifact.name),
        "present" to artifact.present.toString(),
        "bytes" to jsonNullableLong(artifact.bytes),
        "sha256" to jsonNullableString(artifact.sha256),
        "hashState" to jsonString(artifact.hashState.name),
        "error" to jsonNullableString(artifact.error),
    )

    private fun mcapJson(report: McapPrefixReport?): String {
        if (report == null) {
            return "null"
        }
        return jsonObject(
            "state" to jsonString(report.state.name),
            "messageCount" to report.messageCount.toString(),
            "verifiedDataPrefixBytes" to
                report.verifiedDataPrefixBytes.toString(),
            "ignoredTailBytes" to report.ignoredTailBytes.toString(),
            "errors" to jsonStringArray(report.errors),
        )
    }

    private fun jsonObject(vararg fields: Pair<String, String>): String =
        fields.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ",",
        ) { (key, value) -> "${jsonString(key)}:$value" }

    private fun jsonStringArray(values: List<String>): String =
        values.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
            transform = ::jsonString,
        )

    private fun jsonNullableString(value: String?): String =
        value?.let(::jsonString) ?: "null"

    private fun jsonNullableLong(value: Long?): String =
        value?.toString() ?: "null"

    private fun jsonNullableInt(value: Int?): String =
        value?.toString() ?: "null"

    private fun jsonNullableBoolean(value: Boolean?): String =
        value?.toString() ?: "null"

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
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
}

object RecoveryEvidenceBundleExporter {
    fun export(
        bundleDirectory: File,
        report: SessionRecoveryEvidence,
        device: DeviceEvidence,
        sourceSessionDirectory: File? = null,
    ): RecoveryEvidenceBundle {
        require(!bundleDirectory.exists()) {
            "Refusing to overwrite evidence bundle"
        }
        validateHashes(report)
        check(bundleDirectory.mkdirs()) {
            "Could not create evidence bundle directory"
        }

        val reportFile = File(bundleDirectory, "recovery-report.json")
        val deviceFile = File(bundleDirectory, "device.json")
        val sourceHashesFile = File(bundleDirectory, "source-hashes.sha256")
        val manifestFile = File(bundleDirectory, "bundle-manifest.json")

        writeDurably(reportFile, RecoveryEvidenceJson.encode(report))
        writeDurably(deviceFile, RecoveryEvidenceJson.encodeDevice(device))
        writeDurably(
            sourceHashesFile,
            sourceHashLines(report).joinToString(
                separator = "\n",
                postfix = "\n",
            ),
        )
        val recoveredImu = recoverImuIfNeeded(
            bundleDirectory = bundleDirectory,
            report = report,
            sourceSessionDirectory = sourceSessionDirectory,
        )
        val bundleHashes = linkedMapOf(
            "recovery-report.json" to sha256(reportFile),
            "device.json" to sha256(deviceFile),
            "source-hashes.sha256" to sha256(sourceHashesFile),
        )
        if (recoveredImu != null) {
            bundleHashes[recoveredImu.name] = sha256(recoveredImu)
        }
        val manifest = jsonManifest(bundleHashes)
        writeDurably(manifestFile, manifest)

        return RecoveryEvidenceBundle(
            directory = bundleDirectory,
            recoveryReport = reportFile,
            deviceReport = deviceFile,
            sourceHashes = sourceHashesFile,
            manifest = manifestFile,
            recoveredImu = recoveredImu,
        )
    }

    private fun validateHashes(report: SessionRecoveryEvidence) {
        listOf(
            report.wal,
            report.video,
            report.imu,
            report.telemetry,
        ).forEach { artifact ->
            if (artifact.present) {
                require(
                    artifact.hashState == ArtifactHashState.COMPUTED &&
                        artifact.sha256 != null,
                ) {
                    "Artifact ${artifact.name} must be hashed before export"
                }
            }
        }
    }

    private fun sourceHashLines(
        report: SessionRecoveryEvidence,
    ): List<String> = listOf(
        report.wal,
        report.video,
        report.imu,
        report.telemetry,
    )
        .filter { it.present }
        .map { artifact ->
            "${artifact.sha256}  ${report.sessionId}/${artifact.name}"
        }

    private fun recoverImuIfNeeded(
        bundleDirectory: File,
        report: SessionRecoveryEvidence,
        sourceSessionDirectory: File?,
    ): File? {
        if (
            report.imuMcapReport?.state !=
            McapFileState.RECOVERABLE_PREFIX
        ) {
            return null
        }
        require(sourceSessionDirectory != null) {
            "Source session directory is required to recover IMU MCAP"
        }
        val canonicalSession = sourceSessionDirectory.canonicalFile
        val source = File(canonicalSession, report.imu.name).canonicalFile
        require(source.parentFile == canonicalSession) {
            "IMU source escapes session directory"
        }
        val destination = File(bundleDirectory, "recovered-imu.mcap")
        FirstTakeMcapRecovery.recoverTo(source, destination)
        return destination
    }

    private fun jsonManifest(files: Map<String, String>): String {
        val fileJson = files.entries.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { (name, hash) -> "\"$name\":\"$hash\"" }
        return """
            {
              "schemaVersion":"firsttake.evidence-bundle.v1",
              "files":$fileJson
            }
        """.trimIndent() + "\n"
    }

    private fun writeDurably(file: File, content: String) {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        FileOutputStream(file, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
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
}
