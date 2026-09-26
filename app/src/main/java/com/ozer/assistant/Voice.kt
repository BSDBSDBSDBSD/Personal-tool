package com.ozer.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Speech-to-text. Prefers the phone's on-device (offline) recognizer when it has one. */
class VoiceInput(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onFail: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var triedOnDevice = false

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

    fun start() {
        stop()
        val useOnDevice = !triedOnDevice && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        val r = when {
            useOnDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            SpeechRecognizer.isRecognitionAvailable(ctx) -> SpeechRecognizer.createSpeechRecognizer(ctx)
            else -> {
                onFail(NO_OFFLINE)
                return
            }
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListening(true)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onListening(false)
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                onListening(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) onFail("לא שמעתי כלום, נסה שוב.") else onResult(text)
            }
            override fun onError(error: Int) {
                onListening(false)
                // The on-device recognizer may not have Hebrew; fall back to the regular one once.
                if (useOnDevice && error in setOf(SpeechRecognizer.ERROR_CLIENT, 12, 13)) {
                    triedOnDevice = true
                    start()
                    return
                }
                onFail(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "לא שמעתי טוב, נסה שוב."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, 12, 13 -> NO_OFFLINE
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "צריך הרשאת מיקרופון."
                        else -> "שגיאה בזיהוי דיבור ($error)."
                    },
                )
            }
        })
        r.startListening(intent())
    }

    companion object {
        const val NO_OFFLINE = "זיהוי הדיבור של הטלפון לא עובד בעברית בלי אינטרנט. " +
            "כדי לדבר אופליין צריך להוסיף מודל Whisper: לשונית הגדרות ← זיהוי דיבור ← בחירת קובץ."
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }
}

/** Text-to-speech with the phone's Hebrew voice (works offline if the voice is installed). */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ready = false
    var hebrewAvailable = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale("he", "IL"))
        hebrewAvailable = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        ready = true
    }

    fun speak(text: String) {
        if (!ready || !hebrewAvailable) return
        tts.speak(text.replace("•", ""), TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    fun stop() { if (ready) tts.stop() }

    fun shutdown() = tts.shutdown()
}
