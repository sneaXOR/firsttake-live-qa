package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandVisibilityMonitorTest {
    @Test
    fun persistentPositiveEdgeEvidenceAlertsThenRecovers() {
        val monitor = HandVisibilityMonitor(
            alertPersistenceNs = 750_000_000,
            recoveryPersistenceNs = 1_000_000_000,
        )
        assertNull(
            monitor.ingest(
                1_000_000_000,
                HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE,
            ).transition,
        )
        assertEquals(
            HandEdgeTransitionKind.ALERT,
            monitor.ingest(
                2_000_000_000,
                HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE,
            ).transition?.kind,
        )
        assertNull(
            monitor.ingest(
                3_000_000_000,
                HandBaselineStatus.HAND_VISIBLE_CANDIDATE,
            ).transition,
        )
        assertEquals(
            HandEdgeTransitionKind.RECOVERED,
            monitor.ingest(
                4_000_000_000,
                HandBaselineStatus.HAND_VISIBLE_CANDIDATE,
            ).transition?.kind,
        )
    }

    @Test
    fun missingDetectionsRemainUnknownAndNeverAlert() {
        val monitor = HandVisibilityMonitor(alertPersistenceNs = 0)
        val unknown = monitor.ingest(
            1,
            HandBaselineStatus.UNKNOWN,
        )
        val error = monitor.ingest(
            2,
            HandBaselineStatus.MODEL_ERROR,
        )

        assertEquals(HandEdgeAssessment.UNKNOWN, unknown.assessment)
        assertNull(unknown.transition)
        assertEquals(HandEdgeAssessment.UNKNOWN, error.assessment)
        assertNull(error.transition)
    }

    @Test
    fun oneUnknownInferenceDoesNotEraseARecentEdgeRisk() {
        val monitor = HandVisibilityMonitor(
            alertPersistenceNs = 750_000_000,
            unknownGraceNs = 1_500_000_000,
        )
        monitor.ingest(
            1_000_000_000,
            HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE,
        )
        monitor.ingest(1_500_000_000, HandBaselineStatus.UNKNOWN)
        val result = monitor.ingest(
            2_000_000_000,
            HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE,
        )
        assertEquals(
            HandEdgeTransitionKind.ALERT,
            result.transition?.kind,
        )
    }

    @Test
    fun unknownEvidenceAloneNeverCompletesAnEdgeAlert() {
        val monitor = HandVisibilityMonitor(
            alertPersistenceNs = 750_000_000,
            unknownGraceNs = 1_500_000_000,
        )
        monitor.ingest(
            1_000_000_000,
            HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE,
        )

        assertNull(
            monitor.ingest(
                2_000_000_000,
                HandBaselineStatus.UNKNOWN,
            ).transition,
        )
        assertNull(
            monitor.ingest(
                3_000_000_000,
                HandBaselineStatus.UNKNOWN,
            ).transition,
        )
    }

    @Test
    fun unknownBeyondGraceResetsTheEdgeStreak() {
        val monitor = HandVisibilityMonitor(
            alertPersistenceNs = 750_000_000,
            unknownGraceNs = 1_500_000_000,
        )
        monitor.ingest(
            1_000_000_000,
            HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE,
        )
        monitor.ingest(3_000_000_000, HandBaselineStatus.UNKNOWN)

        assertNull(
            monitor.ingest(
                4_000_000_000,
                HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE,
            ).transition,
        )
    }

    @Test
    fun trackedHandThatDisappearsAlertsThenRecovers() {
        val monitor = HandVisibilityMonitor(
            lostPersistenceNs = 1_500_000_000,
            recoveryPersistenceNs = 1_000_000_000,
        )
        monitor.ingest(
            1_000_000_000,
            HandBaselineStatus.HAND_VISIBLE_CANDIDATE,
        )
        monitor.ingest(
            2_000_000_000,
            HandBaselineStatus.HAND_VISIBLE_CANDIDATE,
        )
        assertNull(
            monitor.ingest(
                3_000_000_000,
                HandBaselineStatus.UNKNOWN,
            ).transition,
        )
        val lost = monitor.ingest(
            5_000_000_000,
            HandBaselineStatus.UNKNOWN,
        ).transition
        assertEquals(HandEdgeTransitionKind.ALERT, lost?.kind)
        assertEquals(HandAlertCause.LOST, lost?.cause)

        assertNull(
            monitor.ingest(
                6_000_000_000,
                HandBaselineStatus.HAND_VISIBLE_CANDIDATE,
            ).transition,
        )
        val recovered = monitor.ingest(
            7_000_000_000,
            HandBaselineStatus.HAND_VISIBLE_CANDIDATE,
        ).transition
        assertEquals(HandEdgeTransitionKind.RECOVERED, recovered?.kind)
        assertEquals(HandAlertCause.LOST, recovered?.cause)
    }

    @Test
    fun handNeverSeenDoesNotBecomeLost() {
        val monitor = HandVisibilityMonitor(
            lostPersistenceNs = 0,
        )

        assertNull(
            monitor.ingest(
                1_000_000_000,
                HandBaselineStatus.UNKNOWN,
            ).transition,
        )
        assertNull(
            monitor.ingest(
                2_000_000_000,
                HandBaselineStatus.UNKNOWN,
            ).transition,
        )
    }
}
