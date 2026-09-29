package com.coldai.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val TWO_PI = 6.2831855f

object Pal {
    const val BG_TOP = 0xFF050A16.toInt()
    const val BG_BOTTOM = 0xFF01030A.toInt()
    const val ACCENT = 0xFF62D5FA.toInt()
    const val ICE = 0xFFEAF6FF.toInt()
    const val TEXT = 0xFFE9F1FB.toInt()
    const val MUTED = 0xFF93A4BA.toInt()
    const val LISTEN = 0xFF7CF7D4.toInt()
    const val THINK = 0xFFB7A6FF.toInt()
    const val SPEAK = 0xFF9CC9FF.toInt()
}

fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

/** Глибокий синьо-чорний фон з повільно рухомими холодними світловими плямами. */
class AuroraView(context: Context) : View(context) {
    private class Blob(val x: Float, val y: Float, val r: Float, val color: Int, val sx: Int, val sy: Int, val ph: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint()
    private var t = 0f
    private var anim: ValueAnimator? = null
    private var shown = 0f
    var energy = 0f

    private val blobs = listOf(
        Blob(0.15f, 0.12f, 0.60f, 0xFF1B5EDB.toInt(), 1, 2, 0f),
        Blob(0.88f, 0.34f, 0.52f, 0xFF0FA3C7.toInt(), 2, 1, 2f),
        Blob(0.28f, 0.86f, 0.62f, 0xFF3B2FB5.toInt(), 1, 3, 4f),
        Blob(0.92f, 0.92f, 0.42f, 0xFF6FA8FF.toInt(), 3, 2, 1f)
    )

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Pal.BG_TOP, Pal.BG_BOTTOM, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, TWO_PI).apply {
            duration = 45000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        shown += (energy - shown) * 0.04f
        val big = max(w, h)
        for (b in blobs) {
            val x = w * (b.x + 0.07f * sin(t * b.sx + b.ph))
            val y = h * (b.y + 0.05f * kotlin.math.cos(t * b.sy + b.ph))
            val r = big * b.r
            val a = (70 + 60 * shown).toInt()
            paint.shader = RadialGradient(x, y, r, intArrayOf(withAlpha(b.color, a), withAlpha(b.color, 0)), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, paint)
        }
    }
}

/** Велика скляна кнопка мікрофона. */
class MicOrbView(context: Context) : View(context) {
    enum class Mode { IDLE, LISTENING, PROCESSING, SPEAKING }

    var mode = Mode.IDLE
    var armed = false
    var level = 0f

    private val d = resources.displayMetrics.density
    private var phase = 0f
    private var smooth = 0f
    private var anim: ValueAnimator? = null
    private var sweep: SweepGradient? = null
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = withAlpha(0xFFFFFF, 120)
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.ICE }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Pal.ICE
    }

    init { isClickable = true; isFocusable = true }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sweep = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(0xFFFFFFFF.toInt(), withAlpha(Pal.ACCENT, 60), withAlpha(Pal.ACCENT, 230), withAlpha(0xFFFFFF, 50), 0xFFFFFFFF.toInt()),
            null
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedFraction; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r0 = min(width, height) * 0.27f
        val tw = phase * TWO_PI
        smooth += (level - smooth) * 0.25f

        val tint = when (mode) {
            Mode.PROCESSING -> Pal.THINK
            Mode.SPEAKING -> Pal.SPEAK
            else -> Pal.ACCENT
        }
        val haloA = when (mode) {
            Mode.LISTENING -> 0.55f + 0.4f * smooth
            Mode.SPEAKING -> 0.5f
            Mode.PROCESSING -> 0.4f
            Mode.IDLE -> if (armed) 0.30f + 0.08f * sin(tw) else 0.22f
        }
        val haloR = r0 * 1.75f
        fill.shader = RadialGradient(cx, cy, haloR, intArrayOf(withAlpha(tint, (255 * haloA * 0.55f).toInt()), withAlpha(tint, 0)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, haloR, fill)
        fill.shader = null

        if (mode != Mode.IDLE) {
            ring.strokeWidth = 1.5f * d
            for (i in 0..2) {
                val p = (phase + i / 3f) % 1f
                val rr = r0 * (1.05f + 0.5f * p * (0.6f + 0.8f * smooth))
                ring.color = withAlpha(tint, ((1f - p) * 90).toInt())
                c.drawCircle(cx, cy, rr, ring)
            }
        }

        val rb = r0 * (1f + 0.02f * sin(tw) + (if (mode == Mode.LISTENING) 0.09f * smooth else 0f))
        fill.shader = RadialGradient(
            cx - rb * 0.35f, cy - rb * 0.4f, rb * 1.5f,
            intArrayOf(withAlpha(0xFFFFFF, 112), withAlpha(tint, 48), withAlpha(0x081428, 60)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, rb, fill)
        fill.shader = null

        edge.strokeWidth = 2f * d
        edge.shader = sweep
        c.save()
        c.rotate(if (mode == Mode.PROCESSING) phase * 360f else -25f, cx, cy)
        c.drawCircle(cx, cy, rb, edge)
        c.restore()

        spec.strokeWidth = 3f * d
        rect.set(cx - rb * 0.82f, cy - rb * 0.82f, cx + rb * 0.82f, cy + rb * 0.82f)
        c.drawArc(rect, 200f, 100f, false, spec)

        val s = rb * 0.75f * (1f + 0.08f * smooth)
        iconFill.color = Pal.ICE
        rect.set(cx - s * 0.22f, cy - s * 0.58f, cx + s * 0.22f, cy + s * 0.12f)
        c.drawRoundRect(rect, s * 0.22f, s * 0.22f, iconFill)
        iconStroke.strokeWidth = s * 0.09f
        rect.set(cx - s * 0.42f, cy - s * 0.32f, cx + s * 0.42f, cy + s * 0.40f)
        c.drawArc(rect, 0f, 180f, false, iconStroke)
        c.drawLine(cx, cy + s * 0.40f, cx, cy + s * 0.62f, iconStroke)
        c.drawLine(cx - s * 0.2f, cy + s * 0.62f, cx + s * 0.2f, cy + s * 0.62f, iconStroke)
    }
}

/** Хвиля, що реагує на гучність голосу. */
class WaveformView(context: Context) : View(context) {
    var mode = MicOrbView.Mode.IDLE
    var level = 0f

    private val d = resources.displayMetrics.density
    private var phase = 0f
    private var smooth = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bars = 43

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(withAlpha(Pal.ACCENT, 0), Pal.ACCENT, Pal.ICE, Pal.ACCENT, withAlpha(Pal.ACCENT, 0)),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedFraction; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        smooth += (level - smooth) * 0.3f
        val w = width.toFloat()
        val h = height.toFloat()
        val slot = w / bars
        val bw = slot * 0.55f
        val mid = h / 2f
        val minH = 3f * d
        for (i in 0 until bars) {
            val x = (i + 0.5f) * slot
            val pos = (i - (bars - 1) / 2f) / ((bars - 1) / 2f)
            val env = exp(-(pos * pos) * 2.2f)
            val a = when (mode) {
                MicOrbView.Mode.LISTENING -> smooth * env * (0.55f + 0.45f * sin(phase * TWO_PI * 3f + i * 0.7f))
                MicOrbView.Mode.PROCESSING -> 0.28f * env * (0.5f + 0.5f * sin(phase * TWO_PI * 2f - i * 0.45f))
                MicOrbView.Mode.SPEAKING -> 0.55f * env * (0.5f + 0.5f * sin(phase * TWO_PI * 3f + i * 0.5f)) *
                    (0.7f + 0.3f * sin(phase * TWO_PI + i * 0.2f))
                MicOrbView.Mode.IDLE -> 0.03f * env
            }
            val bh = minH + a * (h - minH)
            rect.set(x - bw / 2f, mid - bh / 2f, x + bw / 2f, mid + bh / 2f)
            c.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
    }
}
