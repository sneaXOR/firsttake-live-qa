package dev.firsttake.probe

enum class ActiveCaptureWarning {
    DARK_OR_COVERED,
    OVEREXPOSED,
    POSSIBLE_BLUR,
    FRAME_STALLED,
    HAND_EDGE_RISK,
    HAND_LOST,
    CAMERA_SHAKE,
    CAMERA_ANGLE,
}

/**
 * Keeps UI truth stateful: recovery of one signal cannot hide another active
 * problem. It contains no inference and is updated only from rare transitions.
 */
class OperatorFeedbackState {
    private val activeWarnings = linkedSetOf<ActiveCaptureWarning>()
    private var recorderState = RecorderHealthState.HEALTHY

    fun reset() {
        activeWarnings.clear()
        recorderState = RecorderHealthState.HEALTHY
    }

    fun apply(transition: QualityTransition) {
        val warning = when (transition.defect) {
            FrameDefect.DARK_OR_COVERED ->
                ActiveCaptureWarning.DARK_OR_COVERED
            FrameDefect.OVEREXPOSED ->
                ActiveCaptureWarning.OVEREXPOSED
            FrameDefect.POSSIBLE_BLUR ->
                ActiveCaptureWarning.POSSIBLE_BLUR
            FrameDefect.FRAME_NOT_RESPONDING_TO_MOTION ->
                ActiveCaptureWarning.FRAME_STALLED
            FrameDefect.CAMERA_SHAKE ->
                ActiveCaptureWarning.CAMERA_SHAKE
            FrameDefect.CAMERA_ANGLE ->
                ActiveCaptureWarning.CAMERA_ANGLE
        }
        when (transition.kind) {
            QualityTransitionKind.ALERT -> activeWarnings += warning
            QualityTransitionKind.RECOVERED -> activeWarnings -= warning
        }
    }

    fun apply(transition: HandEdgeTransition) {
        when (transition.kind) {
            HandEdgeTransitionKind.ALERT -> {
                activeWarnings -= ActiveCaptureWarning.HAND_EDGE_RISK
                activeWarnings -= ActiveCaptureWarning.HAND_LOST
                activeWarnings += when (transition.cause) {
                    HandAlertCause.EDGE_RISK ->
                        ActiveCaptureWarning.HAND_EDGE_RISK
                    HandAlertCause.LOST ->
                        ActiveCaptureWarning.HAND_LOST
                }
            }
            HandEdgeTransitionKind.RECOVERED -> {
                activeWarnings -= ActiveCaptureWarning.HAND_EDGE_RISK
                activeWarnings -= ActiveCaptureWarning.HAND_LOST
            }
        }
    }

    fun apply(event: RecorderHealthEvent) {
        recorderState = event.newState
    }

    fun hasActionableProblems(): Boolean =
        activeWarnings.isNotEmpty() ||
            recorderState != RecorderHealthState.HEALTHY

    fun render(profile: AnalysisProfile): String {
        val instructions = buildList {
            when (recorderState) {
                RecorderHealthState.HEALTHY -> Unit
                RecorderHealthState.STORAGE_WARNING ->
                    add("Storage low · finish this take soon")
                RecorderHealthState.STORAGE_CRITICAL ->
                    add("Storage critical · stopping safely")
                RecorderHealthState.WRITER_STALLED ->
                    add("Recorder stalled · analysis disabled")
            }
            activeWarnings.forEach { warning ->
                add(instruction(warning))
            }
        }
        return buildString {
            append("Recording · ${profile.name}")
            instructions.take(MAX_VISIBLE_WARNINGS).forEach { instruction ->
                append("\nFix now · ")
                append(instruction)
            }
            if (instructions.size > MAX_VISIBLE_WARNINGS) {
                append("\n+")
                append(instructions.size - MAX_VISIBLE_WARNINGS)
                append(" other active issue(s)")
            }
        }
    }

    private fun instruction(warning: ActiveCaptureWarning): String =
        when (warning) {
            ActiveCaptureWarning.DARK_OR_COVERED ->
                "check the lens or add light"
            ActiveCaptureWarning.OVEREXPOSED ->
                "reduce glare"
            ActiveCaptureWarning.POSSIBLE_BLUR ->
                "hold the camera steady"
            ActiveCaptureWarning.FRAME_STALLED ->
                "check the camera"
            ActiveCaptureWarning.HAND_EDGE_RISK ->
                "move your hand to the center"
            ActiveCaptureWarning.HAND_LOST ->
                "bring your hand back into frame"
            ActiveCaptureWarning.CAMERA_SHAKE ->
                "adjust the mount"
            ActiveCaptureWarning.CAMERA_ANGLE ->
                "point the camera down at the task"
        }

    private companion object {
        const val MAX_VISIBLE_WARNINGS = 2
    }
}
