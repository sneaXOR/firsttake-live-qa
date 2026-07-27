package dev.firsttake.probe

data class CaptureStartDecision(
    val allowed: Boolean,
    val analysisProfile: AnalysisProfile,
    val reason: String,
)

object CaptureStartPolicy {
    fun decide(
        requestedProfile: AnalysisProfile,
        thermalStatus: ThermalStatus,
        thermalSignalAvailable: Boolean,
    ): CaptureStartDecision {
        if (!thermalSignalAvailable) {
            return CaptureStartDecision(
                allowed = true,
                analysisProfile = requestedProfile,
                reason = "THERMAL_SIGNAL_UNAVAILABLE",
            )
        }
        if (thermalStatus.severity >= ThermalStatus.CRITICAL.severity) {
            return CaptureStartDecision(
                allowed = false,
                analysisProfile = AnalysisProfile.WRITERS_ONLY,
                reason = "THERMAL_CRITICAL_CAPTURE_BLOCKED",
            )
        }
        if (
            thermalStatus.severity >= ThermalStatus.SEVERE.severity &&
            requestedProfile.ordinal < AnalysisProfile.SAFETY_ONLY.ordinal
        ) {
            return CaptureStartDecision(
                allowed = true,
                analysisProfile = AnalysisProfile.SAFETY_ONLY,
                reason = "THERMAL_SEVERE_SAFETY_ONLY",
            )
        }
        return CaptureStartDecision(
            allowed = true,
            analysisProfile = requestedProfile,
            reason = "CAPTURE_START_ALLOWED",
        )
    }
}
