package com.coldai.assistant

import android.provider.Settings
import android.view.KeyEvent
import java.util.Locale

/** Нормалізація, щоб українські й російські варіанти слів збігалися (і/ы/ї → и, є/э/ё → е, ґ → г). */
fun norm(s: String): String {
    val sb = StringBuilder(s.length)
    var lastSpace = true
    for (ch0 in s.lowercase(Locale.ROOT)) {
        val ch = when (ch0) {
            'ё', 'є', 'э' -> 'е'
            'ї', 'і', 'ы' -> 'и'
            'ґ' -> 'г'
            else -> ch0
        }
        if (ch in ",.!?;:«»\"“”()…") continue
        if (ch.isWhitespace()) {
            if (!lastSpace) sb.append(' ')
            lastSpace = true
        } else {
            sb.append(ch)
            lastSpace = false
        }
    }
    return sb.toString().trim()
}

fun looksUkrainian(s: String) = s.any { it in "іїєґІЇЄҐ" }
fun looksRussian(s: String) = s.any { it in "ыэъёЫЭЪЁ" }

sealed class Cmd {
    object Greet : Cmd()
    object Time : Cmd()
    object DateNow : Cmd()
    object OpenMusic : Cmd()
    data class Torch(val on: Boolean) : Cmd()
    data class Volume(val up: Boolean) : Cmd()
    data class Media(val key: Int, val uk: String, val ru: String) : Cmd()
    data class Sys(val action: String, val uk: String, val ru: String) : Cmd()
    data class Search(val query: String) : Cmd()
    data class Call(val target: String) : Cmd()
    data class Sms(val target: String, val body: String) : Cmd()
    data class Open(val target: String) : Cmd()
    data class Ask(val text: String) : Cmd()
}

object CommandParser {
    private val WAKE = setOf("колд", "колт", "cold", "голд", "колдд")
    private val GREET = setOf("привит", "привет", "вітаю", "витаю", "здравствуй", "здравствуйте", "добрий день", "добрый день")
        .map { norm(it) }.toSet()
    private val CALL_VERBS = setOf("зателефонуй", "подзвони", "набери", "телефонуй", "дзвони", "позвони", "позвонить", "набрать", "зателефонувати", "подзвонити")
        .map { norm(it) }.toSet()
    private val SMS_VERBS = setOf("надішли", "відправ", "напиши", "отправь", "надіслати", "пошли", "відправити", "написати")
        .map { norm(it) }.toSet()
    private val SMS_WORDS = setOf("смс", "sms", "повідомлення", "сообщение", "смску", "меседж")
        .map { norm(it) }.toSet()
    private val FILLERS = setOf("до", "для", "на", "кому", "по", "номеру", "номер", "контакту", "контакт")
    private val SEARCH_VERBS = setOf("знайди", "найди", "пошукай", "поищи", "пошук", "поиск", "загугли")
        .map { norm(it) }.toSet()
    private val OPEN_VERBS = setOf("відкрий", "открой", "запусти", "відкрити", "открыть", "запустити", "запустить")
        .map { norm(it) }.toSet()

    /** Повертає (є ключове слово «Колд», решта фрази). */
    fun splitWake(raw: String): Pair<Boolean, String> {
        val s = raw.trim()
        val parts = s.split(Regex("[\\s,.!?:;]+"), 2)
        if (parts.isNotEmpty() && norm(parts[0]) in WAKE) return true to (parts.getOrNull(1) ?: "").trim()
        return false to s
    }

    fun parse(raw: String): Cmd {
        val text = raw.trim()
        val pairs = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            .map { it to norm(it) }.filter { it.second.isNotEmpty() }
        if (pairs.isEmpty()) return Cmd.Ask(text)
        val orig = pairs.map { it.first }
        val w = pairs.map { it.second }
        val t = w.joinToString(" ")
        fun has(vararg k: String) = k.any { t.contains(norm(it)) }
        val first = w[0]

        if (t in GREET || t == "привит колд") return Cmd.Greet

        if (has("котра година", "скільки часу", "який час", "который час", "сколько времени", "сколько сейчас времени") ||
            t == "час" || t == "время") return Cmd.Time
        if (has("яка сьогодні дата", "яке сьогодні число", "какая сегодня дата", "какое сегодня число", "яка дата", "какое число") ||
            t == "дата" || t == "число") return Cmd.DateNow

        if (first in CALL_VERBS) {
            return Cmd.Call(w.drop(1).filter { it !in FILLERS }.joinToString(" "))
        }

        if (first in SMS_VERBS && w.size >= 2 && w[1] in SMS_WORDS) {
            var idx = 2
            if (idx < w.size && w[idx] in FILLERS) idx++
            if (idx >= w.size) return Cmd.Sms("", "")
            return Cmd.Sms(w[idx], orig.drop(idx + 1).joinToString(" "))
        }

        if (has("ліхтар", "фонарик", "фонарь")) {
            val off = has("вимкн", "выключ", "отключ", "погас", "выкл", "выруб")
            return Cmd.Torch(!off)
        }

        if (has("гучніше", "громче", "додай звук", "прибав") ) return Cmd.Volume(true)
        if (has("тихіше", "тише") || (has("зменш", "убав", "знизь", "понизь") && has("звук", "гучн", "громк"))) return Cmd.Volume(false)

        if (has("відкрий музик", "открой музык", "музичний плеєр", "музыкальный плеер")) return Cmd.OpenMusic
        if (has("пауза", "на паузу", "зупини музику", "останови музыку", "стоп музик", "вимкни музику", "выключи музыку"))
            return Cmd.Media(KeyEvent.KEYCODE_MEDIA_PAUSE, "Музику призупинено.", "Музыка на паузе.")
        if (has("увімкни музику", "включи музыку", "грай музику", "играй музыку", "запусти музику", "запусти музыку",
                "відтвори", "воспроизведи", "продовжи музику", "продолжи музыку"))
            return Cmd.Media(KeyEvent.KEYCODE_MEDIA_PLAY, "Відтворюю.", "Воспроизвожу.")
        val track = has("трек", "пісн", "песн", "композиц", "музик")
        if (has("наступн", "следующ") && track)
            return Cmd.Media(KeyEvent.KEYCODE_MEDIA_NEXT, "Наступний трек.", "Следующий трек.")
        if (has("попередн", "предыдущ") && track)
            return Cmd.Media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Попередній трек.", "Предыдущий трек.")

        if (has("налаштув", "настройк")) {
            when {
                has("wi-fi", "wifi", "вай фай", "вайфай", "вай-фай") ->
                    return Cmd.Sys(Settings.ACTION_WIFI_SETTINGS, "Відкриваю налаштування Wi-Fi.", "Открываю настройки Wi-Fi.")
                has("bluetooth", "блютуз", "блютус") ->
                    return Cmd.Sys(Settings.ACTION_BLUETOOTH_SETTINGS, "Відкриваю налаштування Bluetooth.", "Открываю настройки Bluetooth.")
                has("екран", "яскравіст", "яркост", "дисплей") ->
                    return Cmd.Sys(Settings.ACTION_DISPLAY_SETTINGS, "Відкриваю налаштування екрана.", "Открываю настройки экрана.")
                has("звук", "гучност", "громкост") ->
                    return Cmd.Sys(Settings.ACTION_SOUND_SETTINGS, "Відкриваю налаштування звуку.", "Открываю настройки звука.")
                has("батаре", "акумулятор", "аккумулятор") ->
                    return Cmd.Sys(Settings.ACTION_BATTERY_SAVER_SETTINGS, "Відкриваю налаштування батареї.", "Открываю настройки батареи.")
                t == "налаштування" || t == "настройки" || has("відкрий налаштування", "открой настройки") ->
                    return Cmd.Sys(Settings.ACTION_SETTINGS, "Відкриваю налаштування Android.", "Открываю настройки Android.")
            }
        }
        if (has("яскравіст", "яркост"))
            return Cmd.Sys(Settings.ACTION_DISPLAY_SETTINGS, "Відкриваю налаштування екрана.", "Открываю настройки экрана.")

        if (first in SEARCH_VERBS && w.size > 1) return Cmd.Search(orig.drop(1).joinToString(" "))
        if (first in OPEN_VERBS && w.size > 1) return Cmd.Open(orig.drop(1).joinToString(" "))

        return Cmd.Ask(text)
    }
}
