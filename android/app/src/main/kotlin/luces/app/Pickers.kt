package luces.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.ln
import kotlin.math.pow

/** Color aproximado de un blanco de temperatura `k` (Tanner Helland). */
fun kelvinToRgb(k: Int): Int {
    val t = k / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val b = if (t >= 66) 255.0 else if (t <= 19) 0.0 else 138.5177312231 * ln(t - 10) - 305.0447927307
    fun c(x: Double) = x.coerceIn(0.0, 255.0).toInt()
    return Color.rgb(c(r), c(g), c(b))
}

private fun View.dpf(v: Float) = v * resources.displayMetrics.density

/** Base: gestiona el arrastre, evita que el ScrollView robe el gesto y avisa de cuándo se está tocando. */
abstract class DragView(ctx: Context) : View(ctx) {
    var touching = false; protected set
    var onTouchState: ((Boolean) -> Unit)? = null

    protected abstract fun drag(x: Float, y: Float)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touching = true; onTouchState?.invoke(true)
                drag(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> drag(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false; onTouchState?.invoke(false)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}

/** Barra gruesa en forma de píldora (como la de brillo de las apps de las bombillas). */
class PillSlider(ctx: Context, private val min: Int, private val max: Int) : DragView(ctx) {
    var value = max; private set
    var onChange: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init { minimumHeight = dpf(46f).toInt() }

    fun set(v: Int) {
        if (touching) return
        value = v.coerceIn(min, max); invalidate()
    }

    override fun drag(x: Float, y: Float) {
        val h = height.toFloat()
        val frac = ((x - h / 2) / (width - h)).coerceIn(0f, 1f)
        val v = (min + frac * (max - min)).toInt().coerceIn(min, max)
        if (v != value) { value = v; invalidate(); onChange?.invoke(v) }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dpf(46f).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
        p.color = Color.parseColor("#4A4D57")
        rect.set(0f, 0f, w, h); c.drawRoundRect(rect, r, r, p)
        val frac = (value - min).toFloat() / (max - min)
        val fillW = h + (w - h) * frac
        p.color = Color.parseColor("#FFB547")
        rect.set(0f, 0f, fillW, h); c.drawRoundRect(rect, r, r, p)
    }
}

/** Panel de blanco: degradado cálido → frío con marcas. */
class WhitePanel(ctx: Context, private val lo: Int, private val hi: Int) : DragView(ctx) {
    var value = (lo + hi) / 2; private set
    var onChange: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun set(k: Int) {
        if (touching) return
        value = k.coerceIn(lo, hi); invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dpf(64f).toInt())

    override fun drag(x: Float, y: Float) {
        val frac = (x / width).coerceIn(0f, 1f)
        val k = (lo + frac * (hi - lo)).toInt().coerceIn(lo, hi)
        if (k != value) { value = k; invalidate(); onChange?.invoke(k) }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = dpf(16f)
        p.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(Color.parseColor("#FFE6BD"), Color.parseColor("#FFF6E6"), Color.WHITE, Color.parseColor("#EAF5FF"), Color.parseColor("#D7ECFF")),
            floatArrayOf(0f, 0.28f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
        rect.set(0f, 0f, w, h); c.drawRoundRect(rect, r, r, p)
        p.shader = null
        // marcas
        p.color = Color.parseColor("#22000000"); p.strokeWidth = dpf(1f)
        for (i in 1..7) { val x = w * i / 8; c.drawLine(x, h * 0.3f, x, h * 0.7f, p) }
        // selector
        val frac = (value - lo).toFloat() / (hi - lo)
        val cx = (dpf(7f) + (w - 2 * dpf(7f)) * frac)
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        rect.set(cx - dpf(6f), dpf(6f), cx + dpf(6f), h - dpf(6f)); c.drawRoundRect(rect, dpf(6f), dpf(6f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dpf(2f); p.color = Color.parseColor("#B0B6C4")
        c.drawRoundRect(rect, dpf(6f), dpf(6f), p)
        p.style = Paint.Style.FILL
    }
}

/** Panel de color 2D: tono en horizontal, saturación en vertical (arriba pálido, abajo intenso). */
class ColorPanel(ctx: Context) : DragView(ctx) {
    var hue = 0; private set
    var sat = 100; private set
    var onChange: ((Int, Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val satMin = 8

    fun set(h: Int, s: Int) {
        if (touching) return
        hue = h.coerceIn(0, 359); sat = s.coerceIn(satMin, 100); invalidate()
    }

    fun currentColor() = Color.HSVToColor(floatArrayOf(hue.toFloat(), sat / 100f, 1f))

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dpf(130f).toInt())

    private fun thumbR() = dpf(18f)

    override fun drag(x: Float, y: Float) {
        val r = thumbR()
        val fx = ((x - r) / (width - 2 * r)).coerceIn(0f, 1f)
        val fy = ((y - r) / (height - 2 * r)).coerceIn(0f, 1f)
        val nh = (fx * 359).toInt(); val ns = (satMin + fy * (100 - satMin)).toInt()
        if (nh != hue || ns != sat) { hue = nh; sat = ns; invalidate(); onChange?.invoke(hue, sat) }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val rad = dpf(16f)
        val save = c.save()
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), rad, rad, Path.Direction.CW) }
        c.clipPath(clip)
        p.shader = LinearGradient(0f, 0f, w, 0f, IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = LinearGradient(0f, 0f, 0f, h, Color.WHITE, Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        c.restoreToCount(save)
        // selector
        val r = thumbR()
        val cx = r + (w - 2 * r) * hue / 359f
        val cy = r + (h - 2 * r) * (sat - satMin) / (100f - satMin)
        p.style = Paint.Style.FILL; p.color = currentColor(); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dpf(3f); p.color = Color.WHITE; c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL
    }
}

/** Botón redondo con el símbolo de encendido dibujado a mano. Relleno ámbar si hay alguna luz encendida. */
class PowerButton(ctx: Context) : View(ctx) {
    var active = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        p.style = Paint.Style.FILL
        p.color = if (active) Color.parseColor("#FFB547") else Color.parseColor("#1A1D26")
        c.drawCircle(cx, cy, w / 2 - 1f, p)
        if (!active) {
            p.style = Paint.Style.STROKE; p.strokeWidth = dpf(1f); p.color = Color.parseColor("#2B3040")
            c.drawCircle(cx, cy, w / 2 - dpf(0.5f), p)
        }
        val fg = if (active) Color.parseColor("#1B1304") else Color.parseColor("#F2F4F8")
        val r = w * 0.2f
        p.style = Paint.Style.STROKE; p.strokeWidth = dpf(2.2f); p.strokeCap = Paint.Cap.ROUND; p.color = fg
        rect.set(cx - r, cy - r + dpf(1f), cx + r, cy + r + dpf(1f))
        c.drawArc(rect, 300f, 300f, false, p)           // círculo abierto por arriba
        c.drawLine(cx, cy - r * 1.15f, cx, cy - r * 0.05f, p)   // raya vertical
        p.style = Paint.Style.FILL
    }
}

/**
 * Páginas que se cambian deslizando con el dedo (o con `goTo`). Pensado para ir dentro de un ScrollView:
 * solo roba el gesto cuando el movimiento es claramente horizontal. Su alto es el de la página actual.
 */
class SwipePager(ctx: Context, private val pages: List<View>) : FrameLayout(ctx) {
    var page = 0; private set
    var onPage: ((Int) -> Unit)? = null

    private var drag = 0f
    private var dragging = false
    private var animating = false
    private var downX = 0f
    private var downY = 0f
    private var tracker: VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    init {
        pages.forEach { addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    fun setPage(i: Int) {
        page = i.coerceIn(0, pages.size - 1); drag = 0f; requestLayout(); applyOffsets()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val width = MeasureSpec.getSize(wSpec)
        var maxH = 0; var curH = 0
        pages.forEachIndexed { i, v ->
            v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            maxH = maxOf(maxH, v.measuredHeight); if (i == page) curH = v.measuredHeight
        }
        setMeasuredDimension(width, if (dragging || animating) maxH else curH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        pages.forEach { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        applyOffsets()
    }

    private fun applyOffsets() {
        val w = width.toFloat()
        pages.forEachIndexed { i, v -> v.translationX = (i - page) * w + drag }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (!dragging && !animating && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    tracker?.recycle(); tracker = VelocityTracker.obtain()
                    requestLayout()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return false
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                var d = e.x - downX
                if ((page == 0 && d > 0) || (page == pages.size - 1 && d < 0)) d *= 0.3f   // goma en los extremos
                drag = d; applyOffsets()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                tracker?.recycle(); tracker = null
                val w = width.toFloat()
                var target = page
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    if (drag < -w * 0.25f || vx < -900f) target = minOf(page + 1, pages.size - 1)
                    else if (drag > w * 0.25f || vx > 900f) target = maxOf(page - 1, 0)
                }
                settle(target)
            }
        }
        return true
    }

    fun goTo(i: Int) {
        val t = i.coerceIn(0, pages.size - 1)
        if (t == page && drag == 0f) return
        dragging = false; settle(t)
    }

    private fun settle(target: Int) {
        val from = drag
        val to = (page - target) * width.toFloat()
        animating = true; requestLayout()
        ValueAnimator.ofFloat(from, to).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { drag = it.animatedValue as Float; applyOffsets() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    val changed = target != page
                    page = target; drag = 0f; dragging = false; animating = false
                    requestLayout(); applyOffsets()
                    if (changed) onPage?.invoke(page)
                }
            })
            start()
        }
    }
}

/** Iconos sencillos dibujados a trazo: 0 = lupa, 1 = más. */
class GlyphView(ctx: Context, private val kind: Int, private val tint: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.STROKE; p.strokeWidth = dpf(2.2f); p.strokeCap = Paint.Cap.ROUND; p.color = tint
        when (kind) {
            0 -> {
                val cx = w * 0.45f; val cy = h * 0.45f; val r = w * 0.19f
                c.drawCircle(cx, cy, r, p)
                c.drawLine(cx + r * 0.72f, cy + r * 0.72f, w * 0.74f, h * 0.74f, p)
            }
            else -> {
                val cx = w / 2; val cy = h / 2; val r = w * 0.2f
                c.drawLine(cx - r, cy, cx + r, cy, p)
                c.drawLine(cx, cy - r, cx, cy + r, p)
            }
        }
    }
}
