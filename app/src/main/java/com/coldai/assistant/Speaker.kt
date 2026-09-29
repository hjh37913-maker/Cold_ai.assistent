package com.coldai.assistant

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** Озвучення відповідей. Мова визначається за текстом; onDone викликається лише коли фраза дозвучала природно. */
class Speaker(context: Context, private val prefs: SharedPreferences) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var currentId = ""
    private var counter = 0

    var speaking = false
        private set
    var onDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        main.post { if (utteranceId == currentId) speaking = true }
                    }

                    override fun onDone(utteranceId: String?) {
                        main.post { finish(utteranceId) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { finish(utteranceId) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        main.post { finish(utteranceId) }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        main.post { if (utteranceId == currentId) speaking = false }
                    }
                })
            }
        }
    }

    private fun finish(id: String?) {
        if (id == currentId && id != "") {
            speaking = false
            currentId = ""
            onDone?.invoke()
        }
    }

    fun speak(text: String) {
        val t = tts
        val clean = text.replace(Regex("[*#`_>]+"), " ").replace(Regex("\\s+"), " ").trim().take(3500)
        if (!ready || t == null || clean.isBlank()) {
            main.post { onDone?.invoke() }
            return
        }
        applyVoice(t, clean)
        t.setSpeechRate(prefs.getFloat("rate", 1.0f))
        currentId = "cold_" + (++counter)
        val r = t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, currentId)
        if (r != TextToSpeech.SUCCESS) {
            currentId = ""
            main.post { onDone?.invoke() }
        }
    }

    fun stop() {
        currentId = ""
        speaking = false
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun voices(): List<Voice> = try {
        tts?.voices?.filter { it.locale.language == "uk" || it.locale.language == "ru" }?.sortedBy { it.name } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun applyVoice(t: TextToSpeech, text: String) {
        val pref = prefs.getString("lang", "uk")
        val lang = when {
            looksUkrainian(text) -> "uk"
            looksRussian(text) -> "ru"
            pref == "ru" -> "ru"
            else -> "uk"
        }
        val locale = if (lang == "ru") Locale("ru", "RU") else Locale("uk", "UA")
        val res = t.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(if (lang == "ru") Locale("uk", "UA") else Locale("ru", "RU"))
            return
        }
        val name = prefs.getString("voice", null)
        if (name != null) {
            val v = try { t.voices?.firstOrNull { it.name == name } } catch (_: Exception) { null }
            if (v != null && v.locale.language == lang) t.voice = v
        }
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }
}
