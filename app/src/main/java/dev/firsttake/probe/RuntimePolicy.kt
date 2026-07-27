package dev.firsttake.probe

enum class AnalysisProfile(
    val sampleHz: Double,
    val handSampleHz: Double,
    val maximumDutyCycle: Double,
) {
    FULL(sampleHz = 2.0, handSampleHz = 1.0, maximumDutyCycle = 0.15),
    BALANCED(sampleHz = 1.0, handSampleHz = 0.5, maximumDutyCycle = 0.10),
    LOW_POWER(sampleHz = 0.5, handSampleHz = 0.25, maximumDutyCycle = 0.08),
    SAFETY_ONLY(sampleHz = 1.5, handSampleHz = 0.0, maximumDutyCycle = 0.02),
    WRITERS_ONLY(sampleHz = 0.0, handSampleHz = 0.0, maximumDutyCycle = 0.0),
}

enum class ThermalStatus(val severity: Int) {
    UNKNOWN(-1),
    NONE(0),
    LIGHT(1),
    MODERATE(2),
    SEVERE(3),
    CRITICAL(4),
    EMERGENCY(5),
    SHUTDOWN(6),
}

data class PreflightObservation(
    val inferenceP95Ms: Double,
    val writerDroppedFrames: Int,
    val writerGapRegressionMs: Double,
    val thermalStatus: ThermalStatus,
    val thermalSignalAvailable: Boolean,
)

data class RuntimeObservation(
    val writerDroppedFrames: Int,
    val writerGapRegressionMs: Double,
    val analyzerQueueDepth: Int,
    val thermalStatus: ThermalStatus,
    val thermalSignalAvailable: Boolean,
)

data class ProfileDecision(
    val profile: AnalysisProfile,
    val reasons: List<String>,
)

object RuntimePolicy {
    private val profiles = listOf(
        AnalysisProfile.FULL,
        AnalysisProfile.BALANCED,
        AnalysisProfile.LOW_POWER,
    )

    fun selectPreflight(
        observations: Map<AnalysisProfile, PreflightObservation>,
    ): ProfileDecision {
        val failures = mutableListOf<String>()
        for (profile in profiles) {
            val observation = observations[profile]
            if (observation == null) {
                failures += "${profile.name}:NO_OBSERVATION"
                continue
            }
            val reasons = failureReasons(profile, observation)
            if (reasons.isEmpty()) {
                val resultReasons = mutableListOf("${profile.name}:PREFLIGHT_PASSED")
                if (!observation.thermalSignalAvailable) {
                    resultReasons += "THERMAL_SIGNAL_UNAVAILABLE"
                }
                return ProfileDecision(profile, resultReasons)
            }
            failures += reasons.map { "${profile.name}:$it" }
        }
        return ProfileDecision(
            AnalysisProfile.SAFETY_ONLY,
            failures.ifEmpty { listOf("NO_ML_PROFILE_PROVEN_SAFE") },
        )
    }

    fun degrade(
        current: AnalysisProfile,
        observation: RuntimeObservation,
    ): ProfileDecision {
        val reasons = mutableListOf<String>()
        if (observation.writerDroppedFrames > 0) {
            reasons += "WRITER_DROPPED_FRAMES"
        }
        if (observation.writerGapRegressionMs > 2.0) {
            reasons += "WRITER_GAP_REGRESSION"
        }
        if (observation.analyzerQueueDepth > 1) {
            reasons += "ANALYZER_BACKLOG"
        }
        if (
            observation.thermalSignalAvailable &&
            observation.thermalStatus.severity >= ThermalStatus.SEVERE.severity
        ) {
            reasons += "THERMAL_SEVERE"
        }
        if (reasons.isEmpty()) {
            return ProfileDecision(
                current,
                listOf(
                    if (observation.thermalSignalAvailable) {
                        "RUNTIME_WITHIN_BUDGET"
                    } else {
                        "THERMAL_SIGNAL_UNAVAILABLE"
                    },
                ),
            )
        }
        val next = when (current) {
            AnalysisProfile.FULL -> AnalysisProfile.BALANCED
            AnalysisProfile.BALANCED -> AnalysisProfile.LOW_POWER
            AnalysisProfile.LOW_POWER -> AnalysisProfile.SAFETY_ONLY
            AnalysisProfile.SAFETY_ONLY,
            AnalysisProfile.WRITERS_ONLY,
            -> AnalysisProfile.WRITERS_ONLY
        }
        return ProfileDecision(next, reasons)
    }

    private fun failureReasons(
        profile: AnalysisProfile,
        observation: PreflightObservation,
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (observation.writerDroppedFrames > 0) {
            reasons += "WRITER_DROPPED_FRAMES"
        }
        if (observation.writerGapRegressionMs > 2.0) {
            reasons += "WRITER_GAP_REGRESSION"
        }
        if (
            observation.thermalSignalAvailable &&
            observation.thermalStatus.severity >= ThermalStatus.SEVERE.severity
        ) {
            reasons += "THERMAL_SEVERE"
        }
        val dutyCycle = observation.inferenceP95Ms * profile.sampleHz / 1_000.0
        if (dutyCycle > profile.maximumDutyCycle) {
            reasons += "INFERENCE_BUDGET_EXCEEDED"
        }
        return reasons
    }
}
