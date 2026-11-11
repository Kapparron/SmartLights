package luces.app

import android.app.Activity
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.widget.*
import luces.LightState
import luces.Schedule
import luces.Tapo
import org.json.JSONArray
import java.util.concurrent.Executors

/** Programaciones de una Tapo: se guardan en la bombilla y se ejecutan aunque el móvil esté apagado. */
class SchedulesActivity : Activity() {
    private val cBgTop = Color.parseColor("#0D0F14")
    private val cBgBottom = Color.parseColor("#151A26")
    private val cSurface = Color.parseColor("#1A1D26")
    private val cSurface2 = Color.parseColor("#252A37")
    private val cStroke = Color.parseColor("#2B3040")
    private val cText = Color.parseColor("#F2F4F8")
    private val cMuted = Color.parseColor("#8B93A7")
    private val cAccent = Color.parseColor("#FFB547")
    private val cOnAccent = Color.parseColor("#1B1304")
    private val cDanger = Color.parseColor("#FF6B6B")

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var tapo: Tapo? = null
    private var info: LightState? = null
    private var rules: List<Schedule> = emptyList()

    private lateinit var root: FrameLayout
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private var toastView: TextView? = null
    private var toastRun: Runnable? = null

    private fun alive() = !isFinishing && !isDestroyed

    // ------------------------------------------------------------ ciclo de vida
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = cBgTop
        window.navigationBarColor = cBgBottom
        tapo = loadTapo(intent.getStringExtra("id") ?: "")
        if (tapo == null) { finish(); return }

        root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cBgTop, cBgBottom))
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(110))
        }
        scroll.addView(col, MATCH_PARENT, WRAP_CONTENT)
        root.addView(scroll, MATCH_PARENT, MATCH_PARENT)

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(circle("←", 44, cSurface, cText, 20f) { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Programaciones"; textSize = 26f; setTextColor(cText); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        subtitle = TextView(this).apply { textSize = 13.5f; setTextColor(cMuted); text = tapo!!.name }
        titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(header)

        col.addView(TextView(this).apply {
            text = "Se guardan en la propia bombilla y se ejecutan aunque el móvil esté apagado."
            textSize = 13f; setTextColor(cMuted); setPadding(0, dp(14), 0, dp(14))
        })

        status = TextView(this).apply { textSize = 14.5f; setTextColor(cMuted); gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) }
        col.addView(status)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)

        val fab = circle("+", 60, cAccent, cOnAccent, 32f) { openEditor(null) }
        fab.elevation = dp(10).toFloat()
        root.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(22), dp(26)) })
        setContentView(root)
        load()
    }

    override fun onDestroy() { super.onDestroy(); tapo?.close(); io.shutdown() }

    private fun loadTapo(id: String): Tapo? {
        val raw = getSharedPreferences("luces", Context.MODE_PRIVATE).getString("lights", null) ?: return null
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") != id || o.optString("type") != "Tapo") continue
            val pass = if (o.has("password_enc")) SecretStore.decrypt(o.getString("password_enc")) ?: "" else o.optString("password")
            return Tapo(id, o.getString("name"), o.getString("host"), o.optString("username"), pass)
        }
        return null
    }

    // ------------------------------------------------------------ datos
    private fun load() {
        status.text = "Leyendo la bombilla…"; status.setTextColor(cMuted); status.visibility = View.VISIBLE
        io.execute {
            val r = runCatching { info = tapo!!.state(); tapo!!.schedules() }
            ui.post {
                if (!alive()) return@post
                r.onSuccess { rules = it.sortedBy { s -> s.startMin }; render() }
                    .onFailure { status.text = "No se pudo leer: ${it.message}\n\nToca para reintentar"; status.setTextColor(cDanger); status.setOnClickListener { load() } }
            }
        }
    }

    private fun render() {
        listBox.removeAllViews()
        status.setOnClickListener(null)
        if (rules.isEmpty()) {
            status.text = "No hay programaciones.\nPulsa  +  para crear la primera."; status.setTextColor(cMuted); status.visibility = View.VISIBLE
        } else status.visibility = View.GONE
        subtitle.text = "${tapo!!.name} · ${rules.size} programación${if (rules.size == 1) "" else "es"}"
        for (s in rules) listBox.addView(card(s), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
    }

    private fun clock(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private fun summary(s: Schedule): String {
        if (!s.on) return "Apagar"
        val what = if (s.isColor) "color" else "${s.colorTemp} K"
        val ramp = if (s.transitionMs >= 60_000) "${s.transitionMs / 60_000} min hasta" else "Encender a"
        return "$ramp ${s.brightness} % · $what"
    }

    private fun card(s: Schedule): View {
        val c = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(14), dp(16))
            background = rounded(cSurface, 22, cStroke)
            alpha = if (s.enabled) 1f else 0.55f
            setOnClickListener { openEditor(s) }
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = clock(s.startMin); textSize = 30f; setTextColor(if (s.enabled) cText else cMuted)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        left.addView(TextView(this).apply { text = Schedule.daysText(s.weekMask); textSize = 14f; setTextColor(cAccent) })
        left.addView(TextView(this).apply { text = summary(s); textSize = 12.5f; setTextColor(cMuted); setPadding(0, dp(2), 0, 0) })
        c.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = s.enabled
            thumbTintList = states(Color.WHITE, Color.parseColor("#C9CEDA"))
            trackTintList = states(cAccent, cSurface2)
        }
        sw.setOnCheckedChangeListener { v, on ->
            if (!v.isPressed) return@setOnCheckedChangeListener
            c.alpha = if (on) 1f else 0.55f
            io.execute {
                val err = runCatching { tapo!!.updateSchedule(s.copy(enabled = on)) }.exceptionOrNull()
                ui.post {
                    if (!alive()) return@post
                    if (err != null) toast("No se pudo cambiar: ${err.message}")
                    load()
                }
            }
        }
        c.addView(sw)
        return c
    }

    // ------------------------------------------------------------ editor
    private fun openEditor(existing: Schedule?) {
        val st = info
        val tMin = st?.tempRange?.first ?: 2500
        val tMax = st?.tempRange?.last ?: 6500
        val hasColor = st?.hasColor ?: true

        var minute = existing?.startMin ?: 7 * 60 + 30
        var mask = existing?.weekMask ?: Schedule.WEEKDAYS
        var turnOn = existing?.on ?: true
        var colorMode = existing?.isColor ?: false
        var bright = existing?.brightness?.coerceIn(1, 100) ?: 100
        var temp = (existing?.colorTemp?.takeIf { it > 0 } ?: 4000).coerceIn(tMin, tMax)
        var hue = existing?.hue ?: 0
        var sat = existing?.saturation?.takeIf { it > 0 } ?: 100
        var rampMin = ((existing?.transitionMs ?: 1_800_000) / 60_000).coerceIn(0, 60)
        val enabled = existing?.enabled ?: true

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // hora
        val timeBtn = TextView(this).apply {
            text = clock(minute); textSize = 44f; setTextColor(cText); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            background = rounded(cSurface2, 18)
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener {
                TimePickerDialog(this@SchedulesActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, h, m ->
                    minute = h * 60 + m; text = clock(minute)
                }, minute / 60, minute % 60, true).show()
            }
        }
        form.addView(timeBtn, lp(0))

        // días
        form.addView(label("Días"), lp(16))
        val chips = LinearLayout(this)
        val names = listOf("L", "M", "X", "J", "V", "S", "D")
        Schedule.DISPLAY_BITS.forEachIndexed { i, bit ->
            val chip = TextView(this).apply { text = names[i]; textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            fun paint() {
                val on = mask and (1 shl bit) != 0
                chip.setTextColor(if (on) cOnAccent else cText)
                chip.background = if (on) rounded(cAccent, 99) else rounded(Color.TRANSPARENT, 99, cStroke)
            }
            paint()
            chip.setOnClickListener { mask = mask xor (1 shl bit); paint() }
            chips.addView(chip, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = if (i < 6) dp(6) else 0 })
        }
        form.addView(chips, lp(8))

        // acción
        form.addView(label("Acción"), lp(18))
        val lightBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actionSeg = segmented(listOf("Encender", "Apagar"), if (turnOn) 0 else 1) { turnOn = it == 0; lightBox.visibility = if (turnOn) View.VISIBLE else View.GONE }
        form.addView(actionSeg, lp(8))

        // ajustes de luz (solo al encender): mismos controles que las tarjetas
        fun headRow(title: String, value: TextView) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = title; textSize = 17f; setTextColor(cText) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(value)
        }
        fun valueText(t: String) = TextView(this).apply { textSize = 15f; setTextColor(cMuted); text = t }

        val brightValue = valueText("$bright%")
        lightBox.addView(headRow("Brillo final", brightValue), lp(18))
        lightBox.addView(PillSlider(this, 1, 100).also { it.set(bright); it.onChange = { v -> bright = v; brightValue.text = "$v%" } },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })

        fun rampText(m: Int) = if (m == 0) "Instantánea" else "$m min"
        val rampValue = valueText(rampText(rampMin))
        lightBox.addView(headRow("Subida gradual", rampValue), lp(22))
        lightBox.addView(PillSlider(this, 0, 60).also { it.set(rampMin); it.onChange = { v -> rampMin = v; rampValue.text = rampText(v) } },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })

        if (!hasColor) colorMode = false
        val whitePanel = WhitePanel(this, tMin, tMax)
        val colorPanel = if (hasColor) ColorPanel(this) else null
        val sideText = TextView(this).apply { textSize = 15f; setTextColor(cMuted) }
        val swatch = View(this)
        val tabViews = ArrayList<TextView>()   // 0 = Blanco, 1 = Color

        fun paint() {
            whitePanel.visibility = if (colorMode) View.GONE else View.VISIBLE
            colorPanel?.visibility = if (colorMode) View.VISIBLE else View.GONE
            tabViews.forEachIndexed { i, t ->
                val sel = (i == 1) == colorMode
                t.setTextColor(if (sel) cAccent else cText)
                t.background = if (sel) underline() else null
            }
            if (colorMode && colorPanel != null) {
                swatch.background = rounded(colorPanel.currentColor(), 8); swatch.visibility = View.VISIBLE; sideText.visibility = View.GONE
            } else {
                sideText.text = "$temp K"; sideText.visibility = View.VISIBLE; swatch.visibility = View.GONE
            }
        }
        fun tab(label: String, isColor: Boolean) = TextView(this).apply {
            text = label; textSize = 17f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(4))
            setOnClickListener { colorMode = isColor; paint() }
        }.also { tabViews += it }

        whitePanel.set(temp); whitePanel.onChange = { k -> temp = k; paint() }
        colorPanel?.let { it.set(hue, sat); it.onChange = { h, sa -> hue = h; sat = sa; paint() } }

        lightBox.addView(View(this).apply { setBackgroundColor(cStroke) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(22) })
        val tabRow = FrameLayout(this)
        val left = LinearLayout(this)
        left.addView(tab("Blanco", false), LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { marginEnd = dp(22) })
        if (colorPanel != null) left.addView(tab("Color", true), LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { marginEnd = dp(22) })
        tabRow.addView(left, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.START))
        val right = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        right.addView(sideText); right.addView(swatch, LinearLayout.LayoutParams(dp(30), dp(30)))
        tabRow.addView(right, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.END))
        lightBox.addView(tabRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })

        val panels = FrameLayout(this)
        panels.addView(whitePanel, FrameLayout.LayoutParams(MATCH_PARENT, dp(64)))
        colorPanel?.let { panels.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, dp(130))) }
        lightBox.addView(panels, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(4) })
        paint()
        lightBox.visibility = if (turnOn) View.VISIBLE else View.GONE
        form.addView(lightBox)

        val msg = TextView(this).apply { textSize = 13f; setTextColor(cDanger); visibility = View.GONE }
        form.addView(msg, lp(12))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(form) }
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = GradientDrawable().apply {
                setColor(cSurface)
                cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = if (existing == null) "Nueva programación" else "Editar programación"
            textSize = 20f; setTextColor(cText); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (existing != null) {
            head.addView(TextView(this).apply {
                var armed = false
                text = "Borrar"; textSize = 14f; setTextColor(cDanger); setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener {
                    if (!armed) { armed = true; text = "¿Seguro?"; return@setOnClickListener }
                    isEnabled = false; text = "Borrando…"
                    io.execute {
                        val err = runCatching { tapo!!.removeSchedule(existing.id!!) }.exceptionOrNull()
                        ui.post {
                            if (!alive()) return@post
                            if (err != null) { text = "Borrar"; armed = false; isEnabled = true; msg.text = err.message; msg.visibility = View.VISIBLE }
                            else { d.dismiss(); toast("Programación borrada"); load() }
                        }
                    }
                }
            })
        }
        panel.addView(head)
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
        val buttons = LinearLayout(this)
        buttons.addView(pill("Cancelar", false) { d.dismiss() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) })
        val save = pill("Guardar", true) {}
        save.setOnClickListener {
            if (mask == 0) { msg.text = "Elige al menos un día"; msg.visibility = View.VISIBLE; return@setOnClickListener }
            val sched = (existing ?: Schedule(null, true, 0, 0, true, 100, 4000, 0, 0, 0)).copy(
                enabled = enabled, startMin = minute, weekMask = mask, on = turnOn,
                brightness = if (turnOn) bright else (existing?.brightness ?: 100),
                colorTemp = if (!turnOn) 0 else if (colorMode) 0 else temp,
                hue = if (turnOn && colorMode) hue else 0,
                saturation = if (turnOn && colorMode) sat else 0,
                transitionMs = if (turnOn) rampMin * 60_000 else 0,
            )
            save.text = "Guardando…"; save.isEnabled = false; msg.visibility = View.GONE
            io.execute {
                val err = runCatching { if (sched.id == null) tapo!!.addSchedule(sched) else tapo!!.updateSchedule(sched) }.exceptionOrNull()
                ui.post {
                    if (!alive()) return@post
                    if (err != null) { save.text = "Guardar"; save.isEnabled = true; msg.text = err.message ?: "Error"; msg.visibility = View.VISIBLE }
                    else { d.dismiss(); toast(if (existing == null) "Programación creada" else "Cambios guardados"); load() }
                }
            }
        }
        buttons.addView(save, LinearLayout.LayoutParams(0, dp(46), 1f))
        panel.addView(buttons, lp(18))
        d.setContentView(panel)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.92).toInt())
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setDimAmount(0.6f)
        }
        d.show()
    }

    // ------------------------------------------------------------ piezas
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(cMuted) }

    private fun lp(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { background = rounded(cSurface2, 14); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val tabs = options.map { TextView(this).apply { text = it; textSize = 14.5f; gravity = Gravity.CENTER } }
        fun paint(sel: Int) = tabs.forEachIndexed { i, t ->
            t.setTextColor(if (i == sel) cOnAccent else cText)
            t.background = if (i == sel) rounded(cAccent, 11) else rounded(Color.TRANSPARENT, 11)
            t.typeface = Typeface.create("sans-serif-medium", if (i == sel) Typeface.BOLD else Typeface.NORMAL)
        }
        paint(selected)
        tabs.forEachIndexed { i, t ->
            t.setOnClickListener { paint(i); onPick(i) }
            row.addView(t, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        return row
    }

    /** Subrayado de la pestaña activa: barra de 3 dp pegada abajo. */
    private fun underline(): Drawable = LayerDrawable(arrayOf<Drawable>(rounded(cAccent, 2))).apply {
        setLayerGravity(0, Gravity.BOTTOM)
        setLayerHeight(0, dp(3))
    }

    private fun toast(msg: String) {
        toastRun?.let(ui::removeCallbacks)
        toastView?.let { root.removeView(it) }
        val v = TextView(this).apply {
            text = msg; textSize = 14f; setTextColor(cText); setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(Color.parseColor("#2F3445"), 16, cStroke); elevation = dp(8).toFloat(); alpha = 0f
        }
        toastView = v
        root.addView(v, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(18), 0, dp(96), dp(34)) })
        v.animate().alpha(1f).setDuration(180).start()
        toastRun = Runnable { v.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(v) }.start() }
        ui.postDelayed(toastRun!!, 2600)
    }

    private fun pill(text: String, filled: Boolean, onClick: (View) -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14.5f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) cOnAccent else cText)
        background = if (filled) rounded(cAccent, 99) else rounded(Color.TRANSPARENT, 99, cStroke)
        setPadding(dp(18), 0, dp(18), 0); isClickable = true
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        setOnClickListener { onClick(it) }
    }

    private fun circle(glyph: String, sizeDp: Int, bg: Int, fg: Int, sp: Float, onClick: (View) -> Unit) = TextView(this).apply {
        text = glyph; textSize = sp; setTextColor(fg); gravity = Gravity.CENTER
        background = if (bg == cSurface) rounded(bg, 99, cStroke) else rounded(bg, 99)
        minimumWidth = dp(sizeDp); minimumHeight = dp(sizeDp)
        setOnClickListener { onClick(it) }
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun states(checked: Int, unchecked: Int) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(checked, unchecked),
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
