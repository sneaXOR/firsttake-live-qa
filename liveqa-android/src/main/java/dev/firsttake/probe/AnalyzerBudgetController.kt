package dev.firsttake.probe

import kotlin.math.ceil

data class AnalyzerBudgetTransition(
    val previousProfile: AnalysisProfile,
    val newProfile: AnalysisProfile,
    val reasons: List<String>,
    val observedP95Ms: Double,
    val samplesInWindow: Int,
    val thermalStatus: ThermalStatus,
    val thermalSignalAvailable: Boolean,
)

/**
 * A one-way circuit breaker for the analysis sidecar.
 *
 * The capture writer does not depend on this controller. Analysis can only
 * become less frequent and eventually stop; it can never promote itself
 * during a recording.
 */
class AnalyzerBudgetController(
    private val gate: AnalyzerGate,
    private val windowCapacity: Int = 12,
    private val minimumSamples: Int = 3,
    private val consecutiveBreachesRequired: Int = 3,
    private val budgetWarmupSamples: Int = 6,
) {
    private val durationsNs = ArrayDeque<Long>()
    private var consecutiveBreaches = 0

    init {
        require(windowCapacity >= minimumSamples)
        require(minimumSamples > 0)
        require(consecutiveBreachesRequired > 0)
        require(budgetWarmupSamples >= minimumSamples)
        require(windowCapacity >= budgetWarmupSamples)
    }

    fun observe(
        analysisNs: Long,
        thermalStatus: ThermalStatus,
        thermalSignalAvailable: Boolean,
        estimatedDutyCycle: Double? = null,
    ): AnalyzerBudgetTransition? {
        val profile = gate.currentProfile()
        if (profile == AnalysisProfile.WRITERS_ONLY) {
            return null
        }

        durationsNs.addLast(analysisNs.coerceAtLeast(0))
        while (durationsNs.size > windowCapacity) {
            durationsNs.removeFirst()
        }
        val p95Ms = percentile95Ns(durationsNs) / 1_000_000.0

        if (
            thermalSignalAvailable &&
            thermalStatus.severity >= ThermalStatus.CRITICAL.severity
        ) {
            return transition(
                previous = profile,
                candidate = AnalysisProfile.WRITERS_ONLY,
                reasons = listOf("THERMAL_CRITICAL_WRITERS_ONLY"),
                p95Ms = p95Ms,
                thermalStatus = thermalStatus,
                thermalSignalAvailable = true,
            )
        }

        if (profile == AnalysisProfile.SAFETY_ONLY) {
            return null
        }

        if (
            thermalSignalAvailable &&
            thermalStatus.severity >= ThermalStatus.SEVERE.severity
        ) {
            return transition(
                previous = profile,
                candidate = AnalysisProfile.SAFETY_ONLY,
                reasons = listOf("THERMAL_SEVERE_CAPTURE_PRIORITY"),
                p95Ms = p95Ms,
                thermalStatus = thermalStatus,
                thermalSignalAvailable = true,
            )
        }

        if (durationsNs.size < budgetWarmupSamples) {
            return null
        }
        val currentDutyCycle = estimatedDutyCycle
            ?: (analysisNs / 1_000_000.0) * profile.sampleHz / 1_000.0
        if (currentDutyCycle > profile.maximumDutyCycle) {
            consecutiveBreaches += 1
        } else {
            consecutiveBreaches = 0
            return null
        }
        if (consecutiveBreaches < consecutiveBreachesRequired) {
            return null
        }

        val next = when (profile) {
            AnalysisProfile.FULL -> AnalysisProfile.BALANCED
            AnalysisProfile.BALANCED -> AnalysisProfile.LOW_POWER
            AnalysisProfile.LOW_POWER -> AnalysisProfile.SAFETY_ONLY
            AnalysisProfile.SAFETY_ONLY -> AnalysisProfile.SAFETY_ONLY
            AnalysisProfile.WRITERS_ONLY -> AnalysisProfile.WRITERS_ONLY
        }
        return transition(
            previous = profile,
            candidate = next,
            reasons = listOf(
                "ANALYSIS_DUTY_CYCLE_EXCEEDED",
                "P95_${"%.2f".format(p95Ms)}MS",
                "BUDGET_${"%.2f".format(profile.maximumDutyCycle * 100)}PCT",
            ),
            p95Ms = p95Ms,
            thermalStatus = thermalStatus,
            thermalSignalAvailable = thermalSignalAvailable,
        )
    }

    private fun transition(
        previous: AnalysisProfile,
        candidate: AnalysisProfile,
        reasons: List<String>,
        p95Ms: Double,
        thermalStatus: ThermalStatus,
        thermalSignalAvailable: Boolean,
    ): AnalyzerBudgetTransition? {
        if (!gate.degradeTo(candidate) || gate.currentProfile() == previous) {
            return null
        }
        val samples = durationsNs.size
        durationsNs.clear()
        consecutiveBreaches = 0
        return AnalyzerBudgetTransition(
            previousProfile = previous,
            newProfile = gate.currentProfile(),
            reasons = reasons,
            observedP95Ms = p95Ms,
            samplesInWindow = samples,
            thermalStatus = thermalStatus,
            thermalSignalAvailable = thermalSignalAvailable,
        )
    }

    private fun percentile95Ns(values: Collection<Long>): Long {
        if (values.isEmpty()) {
            return 0
        }
        val sorted = values.sorted()
        val index = (ceil(sorted.size * 0.95).toInt() - 1)
            .coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
