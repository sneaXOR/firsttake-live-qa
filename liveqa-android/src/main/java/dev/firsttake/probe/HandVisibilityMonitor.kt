package dev.firsttake.probe

enum class HandEdgeAssessment {
    EDGE_RISK,
    CLEAR,
    UNKNOWN,
}

enum class HandEdgeTransitionKind {
    ALERT,
    RECOVERED,
}

enum class HandAlertCause {
    EDGE_RISK,
    LOST,
}

data class HandEdgeTransition(
    val kind: HandEdgeTransitionKind,
    val cause: HandAlertCause,
    val observedAtElapsedRealtimeNs: Long,
    val reason: String,
)

data class HandVisibilityEvaluation(
    val assessment: HandEdgeAssessment,
    val transition: HandEdgeTransition?,
)

/**
 * Turns only positive model evidence into action. Two edge observations are
 * required, so one noisy frame cannot alert. A short UNKNOWN gap preserves an
 * existing edge streak because a hand commonly becomes undetectable exactly
 * as it crosses the frame. Once two positive observations have armed tracking,
 * persistent UNKNOWN evidence means the tracked hand was lost. UNKNOWN before
 * tracking is armed can still never create an alert.
 */
class HandVisibilityMonitor(
    private val alertPersistenceNs: Long = 750_000_000L,
    private val recoveryPersistenceNs: Long = 1_000_000_000L,
    private val unknownGraceNs: Long = 1_500_000_000L,
    private val lostPersistenceNs: Long = 1_500_000_000L,
    private val minimumEdgeObservations: Int = 2,
    private val minimumTrackingObservations: Int = 2,
) {
    private var detectedSinceNs: Long? = null
    private var lastDetectedNs: Long? = null
    private var edgeObservationCount = 0
    private var positiveObservationCount = 0
    private var trackingArmed = false
    private var lostSinceNs: Long? = null
    private var clearSinceNs: Long? = null
    private var activeAlert: HandAlertCause? = null
    private var lastObservationNs = Long.MIN_VALUE

    fun ingest(
        observedAtElapsedRealtimeNs: Long,
        status: HandBaselineStatus,
    ): HandVisibilityEvaluation {
        require(observedAtElapsedRealtimeNs > lastObservationNs)
        lastObservationNs = observedAtElapsedRealtimeNs
        if (status == HandBaselineStatus.MODEL_ERROR) {
            return HandVisibilityEvaluation(
                assessment = HandEdgeAssessment.UNKNOWN,
                transition = null,
            )
        }
        val assessment = when (status) {
            HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE ->
                HandEdgeAssessment.EDGE_RISK
            HandBaselineStatus.HAND_VISIBLE_CANDIDATE ->
                HandEdgeAssessment.CLEAR
            HandBaselineStatus.UNKNOWN -> HandEdgeAssessment.UNKNOWN
            HandBaselineStatus.MODEL_ERROR -> error(
                "MODEL_ERROR handled before assessment mapping",
            )
        }
        val transition = when (assessment) {
            HandEdgeAssessment.EDGE_RISK -> {
                registerPositiveObservation()
                lostSinceNs = null
                clearSinceNs = null
                val detectedSince = detectedSinceNs
                    ?: observedAtElapsedRealtimeNs.also {
                        detectedSinceNs = it
                        edgeObservationCount = 0
                    }
                lastDetectedNs = observedAtElapsedRealtimeNs
                edgeObservationCount += 1
                if (
                    activeAlert == null &&
                    edgeObservationCount >= minimumEdgeObservations &&
                    observedAtElapsedRealtimeNs - detectedSince >=
                    alertPersistenceNs
                ) {
                    activeAlert = HandAlertCause.EDGE_RISK
                    HandEdgeTransition(
                        kind = HandEdgeTransitionKind.ALERT,
                        cause = HandAlertCause.EDGE_RISK,
                        observedAtElapsedRealtimeNs =
                            observedAtElapsedRealtimeNs,
                        reason =
                            "A detected hand remained close to a frame edge",
                    )
                } else {
                    null
                }
            }

            HandEdgeAssessment.CLEAR -> {
                registerPositiveObservation()
                lostSinceNs = null
                resetEdgeStreak()
                val alert = activeAlert
                if (alert == null) {
                    clearSinceNs = null
                    null
                } else {
                    val clearSince = clearSinceNs
                        ?: observedAtElapsedRealtimeNs.also {
                            clearSinceNs = it
                        }
                    if (
                        observedAtElapsedRealtimeNs - clearSince >=
                        recoveryPersistenceNs
                    ) {
                        activeAlert = null
                        clearSinceNs = null
                        HandEdgeTransition(
                            kind = HandEdgeTransitionKind.RECOVERED,
                            cause = alert,
                            observedAtElapsedRealtimeNs =
                                observedAtElapsedRealtimeNs,
                            reason = "The detected hand returned inside frame",
                        )
                    } else {
                        null
                    }
                }
            }

            HandEdgeAssessment.UNKNOWN -> {
                clearSinceNs = null
                if (trackingArmed) {
                    val lostSince = lostSinceNs
                        ?: observedAtElapsedRealtimeNs.also {
                            lostSinceNs = it
                        }
                    if (
                        activeAlert != HandAlertCause.LOST &&
                        observedAtElapsedRealtimeNs - lostSince >=
                        lostPersistenceNs
                    ) {
                        activeAlert = HandAlertCause.LOST
                        resetEdgeStreak()
                        HandEdgeTransition(
                            kind = HandEdgeTransitionKind.ALERT,
                            cause = HandAlertCause.LOST,
                            observedAtElapsedRealtimeNs =
                                observedAtElapsedRealtimeNs,
                            reason =
                                "A previously tracked hand disappeared",
                        )
                    } else {
                        null
                    }
                } else {
                    val lastDetected = lastDetectedNs
                    if (
                        lastDetected == null ||
                        observedAtElapsedRealtimeNs - lastDetected >
                        unknownGraceNs
                    ) {
                        resetEdgeStreak()
                        positiveObservationCount = 0
                    }
                    null
                }
            }
        }
        return HandVisibilityEvaluation(assessment, transition)
    }

    private fun registerPositiveObservation() {
        positiveObservationCount += 1
        if (positiveObservationCount >= minimumTrackingObservations) {
            trackingArmed = true
        }
    }

    private fun resetEdgeStreak() {
        detectedSinceNs = null
        lastDetectedNs = null
        edgeObservationCount = 0
    }
}
