package com.coldai.assistant

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ChatMsg(val user: Boolean, val text: String)

class MainActivity : Activity(), VoiceEngine.Listener {
    private enum class Phase { READY, LISTENING, PROCESSING, SPEAKING }

    private val prefs by lazy { getSharedPreferences("cold_ai", MODE_PRIVATE) }
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var messages = mutableListOf<ChatMsg>()
    private lateinit var engine: VoiceEngine
    private lateinit var speaker: Speaker

    private var phase = Phase.READY
    private var resumed = false
    private var micOpen = false
    private var micPassive = false
    private var dialogsOpen = 0
    private var passiveFails = 0
    private var geminiToken = 0
    private var speaking = true
    private var langMode = "uk"
    private var wakeOn = false
    private var bubbleMax = 0
    private var permCallback: ((Boolean) -> Unit)? = null

    private lateinit var aurora: AuroraView
    private lateinit var orb: MicOrbView
    private lateinit var wave: WaveformView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var entry: EditText
    private lateinit var controls: LinearLayout
    private lateinit var langChip: TextView
    private lateinit var micText: TextView
    private lateinit var dot: View
    private val dotBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var dotAnim: ValueAnimator? = null
    private var wide = false

    private val wakeTask = Runnable { startPassive() }
    private val revertTask = Runnable { setPhase(phase) }

    // ───────────────────────── життєвий цикл ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaking = prefs.getBoolean("speak", true)
        langMode = prefs.getString("lang", "uk") ?: "uk"
        wakeOn = prefs.getBoolean("wake", false)
        speaker = Speaker(this, prefs)
        speaker.onDone = { afterSpeech() }
        engine = VoiceEngine(this, this)
        setupWindow()
        buildUi()
        loadHistory()
        if (messages.isEmpty()) {
            addMessage(false, "Привіт! Я Колд. Торкніться мікрофона або напишіть команду. Для Gemini додайте API-ключ у налаштуваннях.")
        } else messages.forEach { addBubble(it.user, it.text) }
        setPhase(Phase.READY)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (phase == Phase.LISTENING) setPhase(Phase.READY)
        scheduleWake(600)
    }

    override fun onPause() {
        // Мікрофон ніколи не слухає у фоні чи при вимкненому екрані.
        resumed = false
        main.removeCallbacks(wakeTask)
        stopMic()
        if (phase == Phase.LISTENING) setPhase(Phase.READY)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        dotAnim?.cancel()
        engine.release()
        speaker.stop(); speaker.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun setupWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // ───────────────────────── інтерфейс ─────────────────────────

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun glass(
        radius: Float, top: Int = 0x2CFFFFFF, bottom: Int = 0x0EFFFFFF, stroke: Int = 0x4DBFE6FF
    ) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
        cornerRadius = radius
        setStroke(max(1, dp(1)), stroke)
    }

    private fun ripple(r: Float) = RippleDrawable(
        ColorStateList.valueOf(0x40FFFFFF), null,
        GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = r }
    )

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(0xFFDCEBFF.toInt())
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = glass(dp(22).toFloat())
        foreground = ripple(dp(22).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val dm = resources.displayMetrics
        val cfg = resources.configuration
        wide = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE || cfg.screenWidthDp >= 840
        bubbleMax = (dm.widthPixels * (if (wide) 0.34f else 0.74f)).toInt()

        val root = FrameLayout(this)
        aurora = AuroraView(this)
        root.addView(aurora, FrameLayout.LayoutParams(-1, -1))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, ins -> applyInsets(content, ins); ins }

        // Шапка: назва + мова + індикатор мікрофона
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val sp = SpannableString("COLD AI")
        sp.setSpan(ForegroundColorSpan(Pal.ACCENT), 5, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val title = TextView(this).apply {
            text = sp; textSize = 22f; setTextColor(Color.WHITE); letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        langChip = chip(langLabel()) { cycleLang() }.apply { minHeight = dp(36); textSize = 13f; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        header.addView(langChip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        dot = View(this).apply { background = dotBg }
        micText = TextView(this).apply { textSize = 12f; setTextColor(Pal.TEXT) }
        val pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = glass(dp(18).toFloat())
            setPadding(dp(12), dp(7), dp(14), dp(7))
        }
        pill.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(8) })
        pill.addView(micText)
        header.addView(pill)
        content.addView(header, LinearLayout.LayoutParams(-1, -2))

        // Центральна частина: кнопка, хвиля, статус, швидкі кнопки
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (wide) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
        }
        val orbSize = min(dp(280), (dm.heightPixels * (if (wide) 0.42f else 0.30f)).toInt()).coerceAtLeast(dp(150))
        orb = MicOrbView(this).apply {
            contentDescription = "Мікрофон"
            setOnClickListener { toggleMic() }
        }
        controls.addView(orb, LinearLayout.LayoutParams(orbSize, orbSize))
        wave = WaveformView(this)
        controls.addView(wave, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(8), 0, dp(8), 0) })
        status = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; letterSpacing = 0.04f
            setPadding(0, dp(8), 0, dp(2))
        }
        controls.addView(status, LinearLayout.LayoutParams(-1, -2))
        transcript = TextView(this).apply {
            textSize = 14f; setTextColor(Pal.MUTED); gravity = Gravity.CENTER; maxLines = 2
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            visibility = View.GONE
        }
        controls.addView(transcript, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), 0) })
        val chips = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val gap = dp(4)
        chips.addView(chip("♪  Музика") { showMusic() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(gap, 0, gap, 0) })
        chips.addView(chip("◫  Застосунки") { showApps() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(gap, 0, gap, 0) })
        chips.addView(chip("⚙  Налаштування") { showSettings() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(gap, 0, gap, 0) })
        controls.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        // Скляна панель чату
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dp(28).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(2), dp(2), dp(8)) }
        scroll.addView(chat)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        entry = EditText(this).apply {
            hint = "Напишіть команду або запитання…"
            setTextColor(Pal.TEXT); setHintTextColor(Pal.MUTED); textSize = 15f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = glass(dp(24).toFloat())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
            }
        }
        row.addView(entry, LinearLayout.LayoutParams(0, -2, 1f))
        val send = TextView(this).apply {
            text = "➤"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Pal.ICE)
            background = glass(dp(23).toFloat(), 0x5562D5FA, 0x2262D5FA, 0x7062D5FA)
            foreground = ripple(dp(23).toFloat())
            isClickable = true
            contentDescription = "Надіслати"
            setOnClickListener { sendTyped() }
        }
        row.addView(send, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) })
        panel.addView(row, LinearLayout.LayoutParams(-1, -2))

        if (wide) {
            val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            body.addView(controls, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(12) })
            body.addView(panel, LinearLayout.LayoutParams(0, -1, 1.15f))
            content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        } else {
            content.addView(controls, LinearLayout.LayoutParams(-1, -2))
            content.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        }
        setContentView(root)
        refreshIndicator()
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(content: View, ins: WindowInsets) {
        val v: IntArray
        val imeUp: Boolean
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            v = intArrayOf(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            imeUp = ime.bottom > 0
        } else {
            v = intArrayOf(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            imeUp = v[3] > resources.displayMetrics.heightPixels * 0.15f
        }
        content.setPadding(v[0] + dp(16), v[1] + dp(12), v[2] + dp(16), v[3] + dp(12))
        // Коли відкрита клавіатура на телефоні, ховаємо верхню частину, щоб чат мав місце.
        controls.visibility = if (imeUp && !wide) View.GONE else View.VISIBLE
    }

    private fun addBubble(user: Boolean, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Pal.TEXT)
            setLineSpacing(0f, 1.12f)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            maxWidth = bubbleMax
            background = if (user) glass(dp(20).toFloat(), 0x5562D5FA, 0x2262D5FA, 0x7062D5FA) else glass(dp(20).toFloat())
            setOnLongClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("COLD AI", text))
                flashStatus("Скопійовано")
                true
            }
        }
        chat.addView(tv, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (user) Gravity.END else Gravity.START
            setMargins(if (user) dp(40) else 0, dp(4), if (user) 0 else dp(40), dp(4))
        })
        tv.alpha = 0f
        tv.translationY = dp(10).toFloat()
        tv.animate().alpha(1f).translationY(0f).setDuration(240).start()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addMessage(user: Boolean, text: String) {
        messages.add(ChatMsg(user, text))
        if (messages.size > 100) messages = messages.takeLast(100).toMutableList()
        saveHistory()
        addBubble(user, text)
    }

    private fun sendTyped() {
        val s = entry.text.toString().trim()
        if (s.isEmpty()) return
        entry.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(entry.windowToken, 0)
        handle(s)
    }

    // ───────────────────────── стан і індикація ─────────────────────────

    private fun setPhase(p: Phase, custom: String? = null) {
        main.removeCallbacks(revertTask)
        phase = p
        val (label, mode) = when (p) {
            Phase.READY -> (if (wakeOn) "Готовий слухати · скажіть «Колд»" else "Готовий слухати") to MicOrbView.Mode.IDLE
            Phase.LISTENING -> "Слухаю" to MicOrbView.Mode.LISTENING
            Phase.PROCESSING -> "Обробляю запит" to MicOrbView.Mode.PROCESSING
            Phase.SPEAKING -> "Відповідаю" to MicOrbView.Mode.SPEAKING
        }
        status.text = custom ?: label
        orb.mode = mode
        wave.mode = mode
        orb.armed = wakeOn && p == Phase.READY
        aurora.energy = when (p) {
            Phase.LISTENING -> 0.8f
            Phase.SPEAKING -> 0.5f
            Phase.PROCESSING -> 0.4f
            Phase.READY -> 0f
        }
        if (p != Phase.LISTENING) { orb.level = 0f; wave.level = 0f }
        if (p == Phase.READY || p == Phase.SPEAKING) transcript.visibility = View.GONE
        refreshIndicator()
    }

    private fun flashStatus(s: String) {
        status.text = s
        main.removeCallbacks(revertTask)
        main.postDelayed(revertTask, 3000)
    }

    private fun refreshIndicator() {
        val text: String
        val color: Int
        val pulse: Boolean
        when {
            micOpen && !micPassive -> { text = "Мікрофон активний"; color = Pal.LISTEN; pulse = true }
            micOpen && micPassive -> { text = "Чекаю «Колд»"; color = Pal.ACCENT; pulse = true }
            else -> { text = "Мікрофон вимкнено"; color = Pal.MUTED; pulse = false }
        }
        micText.text = text
        dotBg.setColor(color)
        dot.invalidate()
        if (pulse) {
            if (dotAnim == null) {
                dotAnim = ValueAnimator.ofFloat(1f, 0.3f).apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { dot.alpha = it.animatedValue as Float }
                    start()
                }
            }
        } else {
            dotAnim?.cancel(); dotAnim = null
            dot.alpha = 1f
        }
    }

    private fun langLabel() = when (langMode) { "ru" -> "РУС"; "auto" -> "АВТО"; else -> "УКР" }

    private fun cycleLang() {
        langMode = when (langMode) { "uk" -> "ru"; "ru" -> "auto"; else -> "uk" }
        prefs.edit().putString("lang", langMode).apply()
        langChip.text = langLabel()
        flashStatus(when (langMode) {
            "ru" -> "Розпізнавання: російська"
            "auto" -> "Розпізнавання: авто (укр → рос)"
            else -> "Розпізнавання: українська"
        })
    }

    // ───────────────────────── мікрофон ─────────────────────────

    private fun ensurePermission(perm: String, onResult: (Boolean) -> Unit) {
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) { onResult(true); return }
        permCallback = onResult
        requestPermissions(arrayOf(perm), 77)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77) {
            val cb = permCallback
            permCallback = null
            cb?.invoke(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        }
    }

    private fun stopMic() {
        engine.cancel()
        micOpen = false
        micPassive = false
        refreshIndicator()
    }

    private fun toggleMic() {
        when (phase) {
            Phase.LISTENING -> { stopMic(); setPhase(Phase.READY); scheduleWake(600) }
            Phase.PROCESSING -> { geminiToken++; stopMic(); setPhase(Phase.READY); scheduleWake(600) }
            Phase.SPEAKING -> { speaker.stop(); startListening() }
            Phase.READY -> startListening()
        }
    }

    private fun startListening() {
        ensurePermission(Manifest.permission.RECORD_AUDIO) { ok ->
            if (!ok) { flashStatus("Потрібен дозвіл на мікрофон"); return@ensurePermission }
            main.removeCallbacks(wakeTask)
            speaker.stop()
            engine.cancel()
            transcript.visibility = View.GONE
            setPhase(Phase.LISTENING)
            engine.start(langMode, false, 2)
        }
    }

    private fun startPassive() {
        if (!wakeOn || !resumed || dialogsOpen > 0 || phase != Phase.READY || engine.isActive) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        engine.start(langMode, true, 0)
    }

    private fun scheduleWake(delay: Long) {
        main.removeCallbacks(wakeTask)
        if (wakeOn && resumed) main.postDelayed(wakeTask, delay)
    }

    private fun setWake(on: Boolean) {
        if (on) {
            ensurePermission(Manifest.permission.RECORD_AUDIO) { ok ->
                wakeOn = ok
                prefs.edit().putBoolean("wake", ok).apply()
                if (!ok) flashStatus("Для режиму «Колд» потрібен мікрофон")
                if (phase == Phase.READY) setPhase(Phase.READY)
                scheduleWake(500)
            }
        } else {
            wakeOn = false
            prefs.edit().putBoolean("wake", false).apply()
            main.removeCallbacks(wakeTask)
            if (phase == Phase.READY) stopMic()
            if (phase == Phase.READY) setPhase(Phase.READY)
        }
    }

    private fun disableWake(msg: String) {
        wakeOn = false
        prefs.edit().putBoolean("wake", false).apply()
        setPhase(Phase.READY, msg)
        main.postDelayed(revertTask, 4000)
    }

    // VoiceEngine.Listener

    override fun onReady(passive: Boolean) {
        micOpen = true
        micPassive = passive
        if (!passive) setPhase(Phase.LISTENING) else refreshIndicator()
    }

    override fun onLevel(level: Float) {
        if (!micPassive && phase == Phase.LISTENING) { orb.level = level; wave.level = level }
    }

    override fun onPartial(text: String) {
        transcript.text = "“$text”"
        transcript.visibility = View.VISIBLE
    }

    override fun onSpeechEnded(passive: Boolean) {
        micOpen = false
        if (!passive) setPhase(Phase.PROCESSING) else refreshIndicator()
    }

    override fun onRetry() {
        micOpen = false
        transcript.visibility = View.GONE
        setPhase(Phase.LISTENING, "Не розчув — слухаю ще раз…")
    }

    override fun onResult(candidates: List<String>, passive: Boolean) {
        micOpen = false
        micPassive = false
        refreshIndicator()
        if (passive) {
            passiveFails = 0
            // Фрази без слова «Колд» відкидаються й не зберігаються.
            val rests = candidates.map { CommandParser.splitWake(it) }.filter { it.first }.map { it.second }
            if (rests.isEmpty()) { scheduleWake(400); return }
            val best = pickBest(rests)
            if (best.isBlank()) {
                main.postDelayed({ if (resumed && phase == Phase.READY) startListening() }, 250)
            } else handle(best)
        } else {
            handle(pickBest(candidates))
        }
    }

    override fun onFailed(reason: VoiceEngine.Failure, passive: Boolean) {
        micOpen = false
        micPassive = false
        refreshIndicator()
        if (passive) {
            passiveFails++
            if (reason == VoiceEngine.Failure.PERMISSION || reason == VoiceEngine.Failure.UNAVAILABLE || passiveFails > 8) {
                disableWake("Режим «Колд» вимкнено: розпізнавання недоступне")
                return
            }
            scheduleWake(if (reason == VoiceEngine.Failure.NO_SPEECH) 300 else 1500)
            return
        }
        setPhase(Phase.READY)
        when (reason) {
            VoiceEngine.Failure.NO_SPEECH -> flashStatus("Не розчув. Торкніться мікрофона ще раз")
            VoiceEngine.Failure.PERMISSION -> flashStatus("Потрібен дозвіл на мікрофон")
            VoiceEngine.Failure.UNAVAILABLE ->
                say("Служба розпізнавання мовлення недоступна. Встановіть або оновіть застосунок Google.")
            VoiceEngine.Failure.NETWORK ->
                say("Розпізнавання не спрацювало. Перевірте інтернет або завантажте офлайн-мову в налаштуваннях голосового введення Google.")
            else -> flashStatus("Помилка мікрофона. Спробуйте ще раз")
        }
        scheduleWake(900)
    }

    /** Із N-best вибираємо першу гіпотезу, що збігається з відомою командою; інакше — найімовірнішу. */
    private fun pickBest(list: List<String>): String =
        list.firstOrNull { it.isNotBlank() && CommandParser.parse(it) !is Cmd.Ask } ?: list.first()

    // ───────────────────────── відповіді ─────────────────────────

    private fun say(s: String) {
        if (isDestroyed) return
        stopMic()
        addMessage(false, s)
        if (speaking && resumed) { setPhase(Phase.SPEAKING); speaker.speak(s) } else afterSpeech()
    }

    private fun afterSpeech() {
        if (phase == Phase.SPEAKING || phase == Phase.PROCESSING) setPhase(Phase.READY)
        scheduleWake(600)
    }

    // ───────────────────────── діалоги ─────────────────────────

    private val darkTheme = android.R.style.Theme_Material_Dialog_Alert

    private fun show(b: AlertDialog.Builder): AlertDialog {
        val d = b.create()
        d.setOnDismissListener { dialogsOpen--; scheduleWake(700) }
        dialogsOpen++
        stopMic()
        d.show()
        d.window?.setBackgroundDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF2101B33.toInt(), 0xF2070D1C.toInt())
            ).apply { cornerRadius = dp(24).toFloat(); setStroke(max(1, dp(1)), 0x55BFE6FF) }
        )
        return d
    }

    private fun confirm(title: String, message: String, yes: String, no: String, onYes: () -> Unit) {
        show(
            AlertDialog.Builder(this, darkTheme).setTitle(title).setMessage(message)
                .setNegativeButton(no) { _, _ -> flashStatus("Скасовано") }
                .setPositiveButton(yes) { _, _ -> onYes() }
        )
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(Pal.ACCENT); letterSpacing = 0.12f
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun field(hintText: String, value: String, secret: Boolean) = EditText(this).apply {
        hint = hintText; setText(value); setTextColor(Pal.TEXT); setHintTextColor(Pal.MUTED); textSize = 15f
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = glass(dp(14).toFloat())
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        fun add(v: View) = box.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        box.addView(sectionLabel("МОВА РОЗПІЗНАВАННЯ"))
        val group = RadioGroup(this)
        val opts = listOf("uk" to "Українська", "ru" to "Русский", "auto" to "Авто (спочатку укр, повтор — рос)")
        opts.forEachIndexed { i, o ->
            group.addView(RadioButton(this).apply { id = 100 + i; text = o.second; setTextColor(Pal.TEXT); isChecked = o.first == langMode })
        }
        box.addView(group)

        box.addView(sectionLabel("ГОЛОС"))
        val speakSw = Switch(this).apply { text = "Озвучувати відповіді"; setTextColor(Pal.TEXT); isChecked = speaking }
        add(speakSw)
        val voiceBtn = chip("Голос: " + (prefs.getString("voice", null) ?: "автоматично")) {}
        voiceBtn.setOnClickListener { pickVoice { voiceBtn.text = "Голос: " + (prefs.getString("voice", null) ?: "автоматично") } }
        add(voiceBtn)
        add(TextView(this).apply { text = "Швидкість мовлення"; setTextColor(Pal.MUTED); textSize = 12f })
        val rateBar = SeekBar(this).apply {
            max = 10
            progress = ((prefs.getFloat("rate", 1.0f) - 0.6f) / 0.1f).roundToInt().coerceIn(0, 10)
        }
        add(rateBar)

        box.addView(sectionLabel("РЕЖИМ «КОЛД»"))
        val wakeSw = Switch(this).apply { text = "Слухати фразу «Колд»"; setTextColor(Pal.TEXT); isChecked = wakeOn }
        add(wakeSw)
        add(TextView(this).apply {
            text = "Працює лише поки застосунок відкритий, а екран увімкнений. У фоні чи при вимкненому екрані мікрофон не використовується."
            setTextColor(Pal.MUTED); textSize = 12f
        })

        box.addView(sectionLabel("GEMINI"))
        val key = field("Gemini API key", prefs.getString("api_key", "").orEmpty(), true)
        add(key)
        val model = field("Модель Gemini", prefs.getString("model", "gemini-2.0-flash").orEmpty(), false)
        add(model)

        box.addView(sectionLabel("ІСТОРІЯ"))
        add(chip("Очистити історію чату") { clearHistory() })

        val sv = ScrollView(this).apply { addView(box) }
        show(
            AlertDialog.Builder(this, darkTheme).setTitle("Налаштування").setView(sv)
                .setNegativeButton("Скасувати", null)
                .setPositiveButton("Зберегти") { _, _ ->
                    val idx = group.checkedRadioButtonId - 100
                    langMode = opts.getOrNull(idx)?.first ?: langMode
                    speaking = speakSw.isChecked
                    prefs.edit()
                        .putString("lang", langMode)
                        .putBoolean("speak", speaking)
                        .putFloat("rate", 0.6f + rateBar.progress * 0.1f)
                        .putString("api_key", key.text.toString().trim())
                        .putString("model", model.text.toString().trim().ifBlank { "gemini-2.0-flash" })
                        .apply()
                    langChip.text = langLabel()
                    if (wakeSw.isChecked != wakeOn) setWake(wakeSw.isChecked)
                    flashStatus("Налаштування збережено")
                }
        )
    }

    private fun pickVoice(after: () -> Unit) {
        val vs = speaker.voices()
        if (vs.isEmpty()) {
            Toast.makeText(this, "Українські чи російські голоси не знайдено. Встановіть мовні дані TTS.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = arrayOf("Автоматично") + vs.map { it.locale.displayLanguage + " · " + it.name }
        show(
            AlertDialog.Builder(this, darkTheme).setTitle("Голос").setItems(labels) { _, i ->
                if (i == 0) prefs.edit().remove("voice").apply() else prefs.edit().putString("voice", vs[i - 1].name).apply()
                after()
                speaker.speak(if (vs.getOrNull(i - 1)?.locale?.language == "ru") "Привет, я Колд." else "Привіт, я Колд.")
            }.setNegativeButton("Скасувати", null)
        )
    }

    private fun clearHistory() {
        show(
            AlertDialog.Builder(this, darkTheme).setTitle("Очистити історію?")
                .setNegativeButton("Скасувати", null)
                .setPositiveButton("Очистити") { _, _ ->
                    messages.clear(); chat.removeAllViews(); saveHistory()
                    addMessage(false, "Історію очищено.")
                }
        )
    }

    private fun showMusic() {
        val items = arrayOf("Відкрити музичний плеєр", "Відтворити / пауза", "Наступний трек", "Попередній трек")
        show(
            AlertDialog.Builder(this, darkTheme).setTitle("Музика").setItems(items) { _, i ->
                when (i) {
                    0 -> openMusicApp { flashStatus("Музичний плеєр не знайдено") }
                    1 -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); flashStatus("Відтворення / пауза") }
                    2 -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); flashStatus("Наступний трек") }
                    3 -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); flashStatus("Попередній трек") }
                }
            }
        )
    }

    private fun showApps() {
        val apps = launchables()
        if (apps.isEmpty()) { flashStatus("Застосунки не знайдено"); return }
        show(
            AlertDialog.Builder(this, darkTheme).setTitle("Застосунки")
                .setItems(apps.map { it.first }.toTypedArray()) { _, i -> launchPackage(apps[i].second, apps[i].first) }
        )
    }

    // ───────────────────────── історія ─────────────────────────

    private fun saveHistory() {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("u", it.user).put("t", it.text)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    private fun loadHistory() {
        try {
            val a = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                messages.add(ChatMsg(o.optBoolean("u"), o.optString("t")))
            }
        } catch (_: Exception) { messages.clear() }
    }

    // ───────────────────────── команди ─────────────────────────

    private fun handle(raw: String) {
        val wake = CommandParser.splitWake(raw)
        if (wake.first && wake.second.isBlank()) {
            addMessage(true, raw.trim())
            say("Слухаю вас.")
            return
        }
        val text = wake.second.trim()
        if (text.isEmpty()) { setPhase(Phase.READY); return }
        stopMic()
        speaker.stop()
        val cmd = CommandParser.parse(text)
        addMessage(true, text)
        setPhase(Phase.PROCESSING)
        execute(cmd, text)
    }

    private fun execute(cmd: Cmd, text: String) {
        val uk = when {
            looksUkrainian(text) -> true
            looksRussian(text) -> false
            else -> langMode != "ru"
        }
        fun tr(a: String, b: String) = if (uk) a else b
        when (cmd) {
            is Cmd.Greet -> say(tr("Привіт! Чим допомогти?", "Привет! Чем помочь?"))
            is Cmd.Time -> say(tr("Зараз ", "Сейчас ") + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
            is Cmd.DateNow -> say(tr("Сьогодні ", "Сегодня ") +
                SimpleDateFormat("d MMMM yyyy", Locale(if (uk) "uk" else "ru")).format(Date()))
            is Cmd.Torch -> {
                try {
                    val cm = getSystemService(CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.firstOrNull {
                        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    if (id == null) say(tr("Ліхтарик не знайдено.", "Фонарик не найден.")) else {
                        cm.setTorchMode(id, cmd.on)
                        say(if (cmd.on) tr("Ліхтарик увімкнено.", "Фонарик включён.") else tr("Ліхтарик вимкнено.", "Фонарик выключен."))
                    }
                } catch (_: Exception) { say(tr("Не вдалося керувати ліхтариком.", "Не удалось управлять фонариком.")) }
            }
            is Cmd.Volume -> {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (cmd.up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                say(if (cmd.up) tr("Зробив гучніше.", "Сделал громче.") else tr("Зробив тихіше.", "Сделал тише."))
            }
            is Cmd.Media -> { sendMediaKey(cmd.key); say(tr(cmd.uk, cmd.ru)) }
            is Cmd.OpenMusic -> {
                val ok = openMusicApp { say(tr("Музичний плеєр не знайдено.", "Музыкальный плеер не найден.")) }
                if (ok) say(tr("Відкриваю музичний плеєр.", "Открываю музыкальный плеер."))
            }
            is Cmd.Sys -> {
                try { startActivity(Intent(cmd.action)); say(tr(cmd.uk, cmd.ru)) }
                catch (_: Exception) { say(tr("Ці налаштування недоступні на пристрої.", "Эти настройки недоступны на устройстве.")) }
            }
            is Cmd.Search -> {
                try {
                    startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, cmd.query))
                    say(tr("Шукаю в інтернеті: ", "Ищу в интернете: ") + cmd.query)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(cmd.query))))
                        say(tr("Шукаю в інтернеті: ", "Ищу в интернете: ") + cmd.query)
                    } catch (_: Exception) { say(tr("Не вдалося відкрити пошук.", "Не удалось открыть поиск.")) }
                }
            }
            is Cmd.Open -> openTarget(cmd.target, uk)
            is Cmd.Call -> { setPhase(Phase.READY); startCall(cmd.target, text, uk) }
            is Cmd.Sms -> { setPhase(Phase.READY); startSms(cmd.target, cmd.body, text, uk) }
            is Cmd.Ask -> askGemini(cmd.text, uk)
        }
    }

    private fun sendMediaKey(code: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** true — плеєр відкрито; false — не знайдено (викликається onFail). */
    private fun openMusicApp(onFail: () -> Unit): Boolean {
        return try {
            startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
            true
        } catch (_: Exception) { onFail(); false }
    }

    private fun launchables(): List<Pair<String, String>> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(i, 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
        } catch (_: Exception) { emptyList() }
    }

    private fun launchPackage(pkg: String, label: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try { startActivity(intent); true } catch (_: Exception) { false }
    }

    private fun openTarget(target: String, uk: Boolean) {
        fun tr(a: String, b: String) = if (uk) a else b
        if (target.contains(".") && !target.contains(" ")) {
            val url = if (target.startsWith("http")) target else "https://$target"
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); say(tr("Відкриваю ", "Открываю ") + target) }
            catch (_: Exception) { say(tr("Не вдалося відкрити сайт.", "Не удалось открыть сайт.")) }
            return
        }
        val q = norm(target)
        val found = launchables().firstOrNull { norm(it.first).contains(q) }
        if (found == null) { say(tr("Не знайшов застосунок ", "Не нашёл приложение ") + target + "."); return }
        if (launchPackage(found.second, found.first)) say(tr("Відкриваю ", "Открываю ") + found.first)
        else say(tr("Не вдалося запустити застосунок.", "Не удалось запустить приложение."))
    }

    // ───────────────────────── дзвінки та SMS (завжди з підтвердженням) ─────────────────────────

    private fun isNumber(s: String) = s.count { it.isDigit() } >= 3 && s.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }

    private fun pickContact(spoken: String, uk: Boolean, onPicked: (String, String) -> Unit) {
        ensurePermission(Manifest.permission.READ_CONTACTS) { ok ->
            if (!ok) {
                say(if (uk) "Потрібен дозвіл на доступ до контактів." else "Нужен доступ к контактам.")
                return@ensurePermission
            }
            io.execute {
                val found = ContactSearch.find(contentResolver, spoken)
                main.post {
                    if (isDestroyed) return@post
                    when {
                        found.isEmpty() -> say((if (uk) "Не знайшов контакт " else "Не нашёл контакт ") + spoken + ".")
                        found.size == 1 -> onPicked(found[0].first, found[0].second)
                        else -> show(
                            AlertDialog.Builder(this, darkTheme)
                                .setTitle(if (uk) "Оберіть контакт" else "Выберите контакт")
                                .setItems(found.map { it.first + " · " + it.second }.toTypedArray()) { _, i ->
                                    onPicked(found[i].first, found[i].second)
                                }
                                .setNegativeButton(if (uk) "Скасувати" else "Отмена", null)
                        )
                    }
                }
            }
        }
    }

    private fun startCall(target: String, spoken: String, uk: Boolean) {
        if (target.isBlank()) { say(if (uk) "Кому подзвонити?" else "Кому позвонить?"); return }
        if (isNumber(target)) { confirmCall("", target.filter { it.isDigit() || it == '+' }, spoken, uk); return }
        pickContact(target, uk) { name, number -> confirmCall(name, number, spoken, uk) }
    }

    private fun confirmCall(name: String, number: String, spoken: String, uk: Boolean) {
        val who = if (name.isNotBlank()) "$name\n$number" else number
        confirm(
            if (uk) "Виконати дзвінок?" else "Выполнить звонок?",
            "«$spoken»\n\n$who",
            if (uk) "Подзвонити" else "Позвонить",
            if (uk) "Скасувати" else "Отмена"
        ) {
            ensurePermission(Manifest.permission.CALL_PHONE) { ok ->
                val uri = Uri.parse("tel:" + Uri.encode(number))
                try {
                    startActivity(Intent(if (ok) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
                    say((if (uk) "Телефоную: " else "Звоню: ") + (if (name.isNotBlank()) name else number))
                } catch (_: Exception) { say(if (uk) "Не вдалося здійснити дзвінок." else "Не удалось совершить звонок.") }
            }
        }
    }

    private fun startSms(target: String, body: String, spoken: String, uk: Boolean) {
        if (target.isBlank()) { say(if (uk) "Кому надіслати повідомлення?" else "Кому отправить сообщение?"); return }
        if (isNumber(target)) { confirmSms("", target.filter { it.isDigit() || it == '+' }, body, spoken, uk); return }
        pickContact(target, uk) { name, number -> confirmSms(name, number, body, spoken, uk) }
    }

    private fun confirmSms(name: String, number: String, body: String, spoken: String, uk: Boolean) {
        val who = if (name.isNotBlank()) "$name · $number" else number
        val txt = if (body.isBlank()) (if (uk) "(текст введете в застосунку)" else "(текст введёте в приложении)") else body
        confirm(
            if (uk) "Підготувати повідомлення?" else "Подготовить сообщение?",
            "«$spoken»\n\n${if (uk) "Кому" else "Кому"}: $who\n$txt",
            if (uk) "Відкрити SMS" else "Открыть SMS",
            if (uk) "Скасувати" else "Отмена"
        ) {
            try {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", body))
                say(if (uk) "Повідомлення підготовлено. Натисніть «Надіслати» в застосунку SMS." else "Сообщение подготовлено. Нажмите «Отправить» в приложении SMS.")
            } catch (_: Exception) { say(if (uk) "Не вдалося відкрити SMS." else "Не удалось открыть SMS.") }
        }
    }

    // ───────────────────────── Gemini ─────────────────────────

    private fun askGemini(question: String, uk: Boolean) {
        val key = prefs.getString("api_key", "").orEmpty()
        if (key.isBlank()) {
            say(if (uk) "Щоб відповідати на запитання, додайте Gemini API-ключ у налаштуваннях."
            else "Чтобы отвечать на вопросы, добавьте Gemini API-ключ в настройках.")
            return
        }
        val history = messages.takeLast(14).dropWhile { !it.user }.toList()
        val model = prefs.getString("model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
        val token = ++geminiToken
        io.execute {
            try {
                val contents = JSONArray()
                history.forEach { m ->
                    contents.put(JSONObject()
                        .put("role", if (m.user) "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
                }
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                        "Ти — Колд, привітний голосовий помічник на Android. Відповідай мовою користувача " +
                            "(українською або російською), коротко й зрозуміло, без markdown-розмітки."))))
                    .put("contents", contents)
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000; conn.readTimeout = 45000; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", key)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                conn.disconnect()
                if (code !in 200..299) throw Exception("Gemini повернув код $code: ${response.take(300)}")
                val parts = JSONObject(response).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val answer = if (parts != null) (0 until parts.length())
                    .joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }.trim() else ""
                if (answer.isBlank()) throw Exception("Порожня відповідь Gemini")
                main.post { if (!isDestroyed && token == geminiToken) say(answer) }
            } catch (e: Exception) {
                main.post { if (!isDestroyed && token == geminiToken) say("Помилка Gemini: ${e.message ?: "перевірте інтернет і ключ"}") }
            }
        }
    }
}
