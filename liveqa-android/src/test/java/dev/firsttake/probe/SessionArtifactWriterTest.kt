package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class SessionArtifactWriterTest {
    @Test
    fun `writes self describing bundle and correct hashes`() {
        val session = fixture()

        val report = SessionArtifactWriter.write(
            sessionDirectory = session,
            manifest = manifest(),
            postflight = postflight(),
        )

        assertTrue(report.manifest.readText().contains("\"sessionId\":\"s-1\""))
        assertTrue(
            report.manifest.readText().contains("\"appliedZoomRatio\":0.5"),
        )
        assertTrue(report.postflight.readText().contains("\"verdict\":\"PASS\""))
        assertEquals(SessionAcceptanceStatus.PASS, report.acceptanceStatus)
        val hashLines = report.hashes.readLines()
        assertEquals(6, hashLines.size)
        assertEquals(
            sha256(File(session, "capture.mp4")),
            report.sha256.getValue("capture.mp4"),
        )
        assertTrue(
            hashLines.contains(
                "${report.sha256.getValue("session.wal")}  session.wal",
            ),
        )
    }

    @Test
    fun `never overwrites published evidence`() {
        val session = fixture()
        SessionArtifactWriter.write(session, manifest(), postflight())
        val originalManifest = File(session, "manifest.json").readBytes()

        assertThrows(IllegalArgumentException::class.java) {
            SessionArtifactWriter.write(session, manifest(), postflight())
        }
        assertTrue(
            originalManifest.contentEquals(
                File(session, "manifest.json").readBytes(),
            ),
        )
    }

    @Test
    fun `reports incomplete when video is not full hd`() {
        val session = fixture()
        val passing = postflight()
        val incomplete = passing.copy(
            video = passing.video.copy(
                video = passing.video.video?.copy(
                    width = 1_280,
                    height = 720,
                ),
            ),
        )

        val report = SessionArtifactWriter.write(
            sessionDirectory = session,
            manifest = manifest(),
            postflight = incomplete,
        )

        assertEquals(
            SessionAcceptanceStatus.INCOMPLETE,
            report.acceptanceStatus,
        )
        assertTrue(
            report.postflight.readText()
                .contains("\"verdict\":\"INCOMPLETE\""),
        )
    }

    private fun fixture(): File =
        Files.createTempDirectory("firsttake-artifacts").toFile().also { root ->
            File(root, "capture.mp4").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "session.mcap").writeBytes(byteArrayOf(4, 5))
            File(root, "qa-events.jsonl").writeText("event\n")
            File(root, "session.wal").writeText("wal\n")
        }

    private fun manifest() = SessionManifestInput(
        sessionId = "s-1",
        createdAtUnixMs = 1_700_000_000_000,
        appVersionName = "0.1.0",
        appVersionCode = 1,
        deviceManufacturer = "Nothing",
        deviceModel = "A059",
        deviceName = "test",
        androidRelease = "16",
        androidSdk = 36,
        cameraId = "0",
        cameraPhysicalId = "2",
        cameraHorizontalFovDegrees = 96.5,
        cameraSelectionPolicy = "WIDEST_PHYSICAL_REAR_FHD",
        cameraTimestampSource = "REALTIME",
        cameraTimestampComparableToElapsedRealtime = true,
        audioEnabled = false,
        initialAnalysisProfile = AnalysisProfile.FULL,
        finalAnalysisProfile = AnalysisProfile.FULL,
        cameraMinimumZoomRatio = 0.5,
        cameraRequestedZoomRatio = 0.5,
        cameraAppliedZoomRatio = 0.5,
    )

    private fun postflight() = SessionPostflightInput(
        sessionId = "s-1",
        finalizedAtUnixMs = 1_700_000_001_000,
        cameraXFinalizeError = 0,
        video = Mp4IntegrityReport(
            readable = true,
            video = TrackTiming(
                mime = "video/avc",
                sampleCount = 30,
                firstTimestampUs = 0,
                lastTimestampUs = 966_666,
                medianDeltaUs = 33_333,
                p95DeltaUs = 33_334,
                maximumDeltaUs = 33_334,
                largeGapCount = 0,
                width = 1_920,
                height = 1_080,
                declaredFrameRate = 30,
            ),
            audio = null,
            audioVideoEndDeltaUs = null,
            error = null,
        ),
        imu = ImuFinalizeReport(finalized = true, error = null),
        telemetry = TelemetryChainReport(
            writtenRecords = 10,
            droppedRecords = 0,
            lastHash = "abc",
            complete = true,
            error = null,
        ),
    )

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
