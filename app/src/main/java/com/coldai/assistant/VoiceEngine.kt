package com.coldai.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Обгортка над SpeechRecognizer:
 *  - завжди новий екземпляр розпізнавача (без «завислого» мікрофона);
 *  - N-best (до 5 гіпотез), часткові результати;
 *  - власний контроль паузи: якщо текст перестав змінюватись, слухання завершується;
 *  - сторожові таймери проти зависань;
 *  - автоповтор (для режиму «Авто» повтор іде іншою мовою).
 */
class VoiceEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onReady(passive: Boolean)
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onSpeechEnded(passive: Boolean)
        fun onRetry()
        fun onResult(candidates: List<String>, passive: Boolean)
        fun onFailed(reason: Failure, passive: Boolean)
    }

    enum class Failure { NO_SPEECH, PERMISSION, UNAVAILABLE, NETWORK, AUDIO, OTHER }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var mode = "uk"
    private var passive = false
    private var maxRetries = 0
    private var attempt = 0
    private var passiveCycle = 0
    private var speechBegan = false
    private var lastPartial = ""

    var isActive = false
        private set

    private val silenceTask = Runnable {
        if (isActive) {
            try { recognizer?.stopListening() } catch (_: Exception) {}
            armGuard()
        }
    }
    private val guardTask = Runnable { if (isActive) forceFinish() }
    private val noSpeechTask = Runnable {
        if (isActive && !speechBegan) {
            try { recognizer?.stopListening() } catch (_: Exception) {}
            armGuard()
        }
    }
    private val hardTask = Runnable { if (isActive) forceFinish() }

    fun start(mode: String, passive: Boolean, retries: Int) {
        cancel()
        this.mode = mode
        this.passive = passive
        this.maxRetries = retries
        this.attempt = 0
        if (passive) passiveCycle++
        begin()
    }

    fun cancel() {
        if (!isActive && recognizer == null) return
        session++
        isActive = false
        handler.removeCallbacksAndMessages(null)
        destroyNow()
    }

    fun release() = cancel()

    private fun begin() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            finishFail(Failure.UNAVAILABLE)
            return
        }
        val id = ++session
        isActive = true
        speechBegan = false
        lastPartial = ""
        handler.removeCallbacksAndMessages(null)
        destroyNow()
        val r = try { SpeechRecognizer.createSpeechRecognizer(context) } catch (_: Exception) { null }
        if (r == null) {
            finishFail(Failure.OTHER)
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (id == session) listener.onReady(passive)
            }

            override fun onBeginningOfSpeech() {
                if (id != session) return
                speechBegan = true
                handler.removeCallbacks(noSpeechTask)
                handler.removeCallbacks(silenceTask)
                handler.postDelayed(silenceTask, FIRST_WORDS_MS)
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (id == session) listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (id != session) return
                handler.removeCallbacks(silenceTask)
                listener.onSpeechEnded(passive)
                armGuard()
            }

            override fun onError(error: Int) {
                if (id != session) return
                handleError(error)
            }

            override fun onResults(results: Bundle?) {
                if (id != session) return
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.filter { it.isNotBlank() }.orEmpty()
                if (list.isEmpty()) retryOrFail(Failure.NO_SPEECH, 350) else deliver(list)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (id != session) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (t.isBlank()) return
                lastPartial = t
                if (!passive) listener.onPartial(t)
                handler.removeCallbacks(silenceTask)
                handler.postDelayed(silenceTask, SILENCE_MS)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            r.startListening(buildIntent())
        } catch (_: Exception) {
            finishFail(Failure.OTHER)
            return
        }
        if (!passive) handler.postDelayed(noSpeechTask, NO_SPEECH_MS)
        handler.postDelayed(hardTask, HARD_MS)
    }

    private fun buildIntent(): Intent {
        val idx = attempt + (if (passive) passiveCycle else 0)
        val tag = when (mode) {
            "ru" -> "ru-RU"
            "auto" -> if (idx % 2 == 0) "uk-UA" else "ru-RU"
            else -> "uk-UA"
        }
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600L)
        }
    }

    private fun handleError(code: Int) {
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                if (lastPartial.isNotBlank()) deliver(listOf(lastPartial)) else retryOrFail(Failure.NO_SPEECH, 350)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> retryOrFail(Failure.OTHER, 900)
            SpeechRecognizer.ERROR_CLIENT -> retryOrFail(Failure.OTHER, 500)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finishFail(Failure.PERMISSION)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                finishFail(Failure.NETWORK)
            SpeechRecognizer.ERROR_AUDIO -> retryOrFail(Failure.AUDIO, 700)
            12, 13 -> if (mode == "auto") retryOrFail(Failure.UNAVAILABLE, 300) else finishFail(Failure.UNAVAILABLE)
            else -> finishFail(Failure.OTHER)
        }
    }

    private fun retryOrFail(f: Failure, delay: Long) {
        if (!passive && attempt < maxRetries) {
            attempt++
            val id = session
            handler.removeCallbacksAndMessages(null)
            detachLater()
            listener.onRetry()
            handler.postDelayed({ if (id == session && isActive) begin() }, delay)
        } else {
            finishFail(f)
        }
    }

    private fun forceFinish() {
        if (lastPartial.isNotBlank()) deliver(listOf(lastPartial)) else retryOrFail(Failure.NO_SPEECH, 350)
    }

    private fun armGuard() {
        handler.removeCallbacks(guardTask)
        handler.postDelayed(guardTask, GUARD_MS)
    }

    private fun deliver(list: List<String>) {
        val p = passive
        teardown()
        listener.onResult(list, p)
    }

    private fun finishFail(f: Failure) {
        val p = passive
        teardown()
        listener.onFailed(f, p)
    }

    private fun teardown() {
        session++
        isActive = false
        handler.removeCallbacksAndMessages(null)
        detachLater()
    }

    /** Знищення розпізнавача поза його власним колбеком. */
    private fun detachLater() {
        val r = recognizer
        recognizer = null
        if (r != null) handler.post {
            try { r.cancel() } catch (_: Exception) {}
            try { r.destroy() } catch (_: Exception) {}
        }
    }

    private fun destroyNow() {
        val r = recognizer
        recognizer = null
        if (r != null) {
            try { r.cancel() } catch (_: Exception) {}
            try { r.destroy() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val SILENCE_MS = 1800L
        private const val FIRST_WORDS_MS = 3500L
        private const val NO_SPEECH_MS = 8000L
        private const val GUARD_MS = 4000L
        private const val HARD_MS = 25000L
    }
}
