package dev.firsttake.probe

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class FeedbackMode {
    SILENT,
    HAPTIC,
    HAPTIC_AND_VOICE,
    ;

    companion object {
        fun parse(value: String?): FeedbackMode =
            entries.firstOrNull { it.name == value?.uppercase() }
                ?: HAPTIC_AND_VOICE
    }
}

enum class FeedbackAssessment {
    INFO,
    WARNING,
    RECOVERED,
}

data class FeedbackMessage(
    val category: String,
    val assessment: FeedbackAssessment,
    val spokenText: String,
)

interface CaptureFeedback : AutoCloseable {
    val mode: FeedbackMode

    fun emit(message: FeedbackMessage, allowVoice: Boolean)

    override fun close() = Unit
}

object NoopCaptureFeedback : CaptureFeedback {
    override val mode = FeedbackMode.SILENT

    override fun emit(message: FeedbackMessage, allowVoice: Boolean) = Unit
}

/**
 * Head-mounted feedback that stays off the capture writer path.
 *
 * Voice is additionally gated by the active session's audio setting so an
 * alert cannot silently contaminate an audio-bearing recording.
 */
class AndroidCaptureFeedback(
    context: Context,
    override val mode: FeedbackMode,
) : CaptureFeedback, TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val ready = AtomicBoolean(false)
    private val vibratorManager =
        appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as
            VibratorManager
    private val textToSpeech = if (mode == FeedbackMode.HAPTIC_AND_VOICE) {
        TextToSpeech(appContext, this)
    } else {
        null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            val languageReady =
                result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            ready.set(languageReady)
            if (languageReady) {
                textToSpeech?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) {
                            Log.i(LOG_TAG, "TTS_STARTED id=$utteranceId")
                        }

                        override fun onDone(utteranceId: String) {
                            Log.i(LOG_TAG, "TTS_DONE id=$utteranceId")
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String) {
                            Log.e(LOG_TAG, "TTS_ERROR id=$utteranceId")
                        }

                        override fun onError(
                            utteranceId: String,
                            errorCode: Int,
                        ) {
                            Log.e(
                                LOG_TAG,
                                "TTS_ERROR id=$utteranceId code=$errorCode",
                            )
                        }
                    },
                )
                Log.i(LOG_TAG, "TTS_READY language=en-US")
            } else {
                Log.w(LOG_TAG, "TTS_UNAVAILABLE language=en-US")
            }
        } else {
            ready.set(false)
            Log.w(LOG_TAG, "TTS_INIT_FAILED status=$status")
        }
    }

    override fun emit(message: FeedbackMessage, allowVoice: Boolean) {
        if (mode == FeedbackMode.SILENT) {
            return
        }
        val effect = when (message.assessment) {
            FeedbackAssessment.INFO ->
                VibrationEffect.createOneShot(
                    45,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                )
            FeedbackAssessment.WARNING ->
                VibrationEffect.createWaveform(
                    longArrayOf(0, 110, 80, 110),
                    -1,
                )
            FeedbackAssessment.RECOVERED ->
                VibrationEffect.createOneShot(
                    65,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                )
        }
        vibratorManager.defaultVibrator.vibrate(effect)
        if (
            allowVoice &&
            mode == FeedbackMode.HAPTIC_AND_VOICE &&
            ready.get()
        ) {
            val utteranceId =
                "firsttake-${message.category}-" +
                    SystemClock.elapsedRealtimeNanos()
            val result = textToSpeech?.speak(
                message.spokenText,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId,
            )
            Log.i(
                LOG_TAG,
                "TTS_ENQUEUED id=$utteranceId result=$result " +
                    "text=${message.spokenText}",
            )
        }
    }

    override fun close() {
        ready.set(false)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }

    private companion object {
        const val LOG_TAG = "FirstTakeFeedback"
    }
}
