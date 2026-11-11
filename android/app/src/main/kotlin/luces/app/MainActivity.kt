package luces.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.widget.*
import luces.Change
import luces.Light
import luces.LightState
import luces.Tapo
import luces.Wiz
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Habitación: agrupa bombillas (por id) para controlarlas a la vez. */
data class Group(val id: String, val name: String, val members: List<String>)

class MainActivity : Activity() {
    // ------------------------------------------------------------ paleta
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
    private val cOk = Color.parseColor("#4ADE80")

    private val io = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    private val lights = mutableListOf<Light>()
    private val cards = LinkedHashMap<String, LightCard>()
    private val groups = mutableListOf<Group>()
    /** Preajustes de color [tono, saturación, brillo]; brillo -1 = no cambia el brillo. Compartidos por todas las bombillas. */
    private val presets = mutableListOf<IntArray>()
    private val groupCards = LinkedHashMap<String, GroupCard>()
    private val inFlight = HashSet<String>()
    private val pending = HashMap<String, Runnable>()

    private lateinit var list: LinearLayout
    private lateinit var groupList: LinearLayout
    private lateinit var pager: SwipePager
    private lateinit var power: PowerButton
    private lateinit var emptyGroups: TextView
    private val pageTabs = ArrayList<TextView>()
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private lateinit var rootFrame: FrameLayout
    private var snackRunnable: Runnable? = null
    private var snackView: TextView? = null

    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 4000) }
    }

    // ------------------------------------------------------------ ciclo de vida
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = cBgTop
        window.navigationBarColor = cBgBottom
        loadLights()
        loadGroups()
        loadPresets()

        rootFrame = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cBgTop, cBgBottom))
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(110))
        }
        scroll.addView(col, MATCH_PARENT, WRAP_CONTENT)
        rootFrame.addView(scroll, MATCH_PARENT, MATCH_PARENT)

        // cabecera
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "SmartLights"; textSize = 32f; setTextColor(cText); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        summary = TextView(this).apply { textSize = 14f; setTextColor(cMuted); text = " " }
        titles.addView(summary)
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(circleButton("↻", 44, cSurface, cText, 20f) { refresh(); snack("Actualizando…") },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        power = PowerButton(this).apply { setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); toggleAll() } }
        header.addView(power, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(header)

        // páginas: Bombillas | Habitaciones (se cambian con las pestañas o deslizando)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        empty = TextView(this).apply {
            text = "Aún no hay bombillas.\nPulsa  +  para buscar WiZ o añadir una Tapo."
            setTextColor(cMuted); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(50), 0, dp(50))
            visibility = View.GONE
        }
        val lightsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(list); addView(empty) }
        groupList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emptyGroups = TextView(this).apply {
            text = "Aún no hay habitaciones.\nPulsa  +  para crear una y controlar varias bombillas a la vez."
            setTextColor(cMuted); textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(16), dp(50), dp(16), dp(50))
            visibility = View.GONE
        }
        val groupsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(groupList); addView(emptyGroups) }
        pager = SwipePager(this, listOf(lightsPage, groupsPage))

        val tabRow = LinearLayout(this)
        listOf("Bombillas", "Habitaciones").forEachIndexed { i, name ->
            val t = TextView(this).apply {
                text = name; textSize = 18f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(4))
                setOnClickListener { pager.goTo(i) }
            }
            pageTabs += t
            tabRow.addView(t, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { marginEnd = dp(26) })
        }
        col.addView(tabRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(18); bottomMargin = dp(10) })
        col.addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        pager.onPage = { paintTabs(it); prefs().edit().putInt("page", it).apply() }
        pager.setPage(prefs().getInt("page", 0))
        paintTabs(pager.page)

        // botón flotante
        val fab = circleButton("+", 60, cAccent, cOnAccent, 32f) { if (pager.page == 1) groupSheet(null) else showAddMenu(it) }
        fab.elevation = dp(10).toFloat()
        rootFrame.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(22), dp(26))
        })
        setContentView(rootFrame)
        syncCards()
        syncGroups()
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    // ------------------------------------------------------------ datos
    private fun prefs() = getSharedPreferences("luces", Context.MODE_PRIVATE)

    private fun loadLights() {
        val raw = prefs().getString("lights", null)
        val arr = if (raw != null) JSONArray(raw) else JSONArray()
        var migrar = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            lights += if (o.optString("type") == "Tapo") {
                val pass = when {
                    o.has("password_enc") -> SecretStore.decrypt(o.getString("password_enc")) ?: ""
                    o.has("password") -> { migrar = true; o.getString("password") }  // formato antiguo en claro
                    else -> ""
                }
                Tapo(o.getString("id"), o.getString("name"), o.getString("host"), o.optString("username"), pass)
            } else Wiz(o.getString("id"), o.getString("name"), o.getString("host"))
        }
        if (migrar) saveLights()  // reescribe con la contraseña cifrada
    }

    private fun saveLights() {
        val arr = JSONArray()
        lights.forEach {
            val o = JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("type", it.kind)
            if (it is Tapo) o.put("username", it.username).put("password_enc", SecretStore.encrypt(it.password))
            arr.put(o)
        }
        prefs().edit().putString("lights", arr.toString()).apply()
    }

    private val defaultPresets = listOf(0, 30, 120, 175, 215, 275, 330).map { intArrayOf(it, 100, -1) }
    private val maxPresets = 9

    private fun loadPresets() {
        presets.clear()
        val raw = prefs().getString("presets", null)
        if (raw == null) { defaultPresets.forEach { presets += it.copyOf() }; return }
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) arr.getJSONArray(i).let {
            presets += intArrayOf(it.getInt(0), it.getInt(1), if (it.length() > 2) it.getInt(2) else -1)  // formato antiguo: sin brillo
        }
    }

    private fun savePresets() {
        val arr = JSONArray()
        presets.forEach { arr.put(JSONArray().put(it[0]).put(it[1]).put(it[2])) }
        prefs().edit().putString("presets", arr.toString()).apply()
    }

    private fun refreshPresetRows() {
        cards.values.forEach { it.controls?.rebuildPresets() }
        groupCards.values.forEach { it.controls?.rebuildPresets() }
    }

    private fun loadGroups() {
        val raw = prefs().getString("groups", null) ?: return
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val m = o.getJSONArray("members")
            groups += Group(o.getString("id"), o.getString("name"), (0 until m.length()).map { m.getString(it) })
        }
    }

    private fun saveGroups() {
        val arr = JSONArray()
        groups.forEach { g -> arr.put(JSONObject().put("id", g.id).put("name", g.name).put("members", JSONArray(g.members))) }
        prefs().edit().putString("groups", arr.toString()).apply()
    }

    // ------------------------------------------------------------ red
    private fun refresh() {
        for (l in lights.toList()) {
            if (!inFlight.add(l.id)) continue
            io.execute {
                val r = runCatching { l.state() }
                ui.post {
                    inFlight.remove(l.id)
                    cards[l.id]?.let { c -> r.fold({ c.bind(it) }, { e -> c.showError(e.message ?: "Sin respuesta") }) }
                    updateSummary()
                    updateGroups()
                }
            }
        }
    }

    private fun send(l: Light, c: Change, key: String = l.id + "x", delay: Long = 0) {
        cards[l.id]?.quiet()
        pending.remove(key)?.let(ui::removeCallbacks)
        val r = Runnable {
            pending.remove(key)
            io.execute {
                val err = runCatching { l.set(c) }.exceptionOrNull()
                ui.post {
                    if (err != null) snack("${l.name}: ${err.message}")
                    cards[l.id]?.quietUntil = 0
                    groupCards.values.forEach { if (l.id in it.group.members) it.quietUntil = 0 }
                    ui.postDelayed({ refresh() }, 400)
                }
            }
        }
        pending[key] = r
        ui.postDelayed(r, delay)
    }

    /** Un solo botón: si hay alguna encendida las apaga todas; si no, las enciende todas. */
    private fun toggleAll() {
        if (lights.isEmpty()) { snack("No hay bombillas"); return }
        val on = cards.values.none { it.isOn }
        power.active = on
        cards.values.forEach { it.isOn = on }
        all(on)
    }

    private fun tabUnderline(): Drawable = LayerDrawable(arrayOf<Drawable>(rounded(cAccent, 2))).apply {
        setLayerGravity(0, Gravity.BOTTOM)
        setLayerHeight(0, dp(3))
    }

    private fun paintTabs(sel: Int) = pageTabs.forEachIndexed { i, t ->
        t.setTextColor(if (i == sel) cAccent else cText)
        t.background = if (i == sel) tabUnderline() else null
    }

    private fun all(on: Boolean) {
        lights.forEach { send(it, Change(on = on)) }
        snack(if (on) "Encendiendo todas…" else "Apagando todas…")
    }

    private fun discover() {
        snack("Buscando bombillas WiZ…")
        io.execute {
            val ips = runCatching { Wiz.discover() }.getOrDefault(emptyList())
            ui.post {
                val nuevas = ips.filter { ip -> lights.none { it.host == ip } }
                nuevas.forEach { lights += Wiz(it, "WiZ ${lights.size + 1}", it) }
                if (nuevas.isNotEmpty()) { saveLights(); syncCards(); refresh() }
                snack(if (nuevas.isEmpty()) "No hay bombillas nuevas (${ips.size} encontradas)" else "Añadidas ${nuevas.size} bombillas")
            }
        }
    }

    // ------------------------------------------------------------ tarjetas
    private fun syncCards() {
        val ids = lights.map { it.id }.toSet()
        cards.keys.filter { it !in ids }.forEach { id -> cards.remove(id)?.let { list.removeView(it.root) } }
        for (l in lights) {
            val existing = cards[l.id]
            if (existing != null) { existing.light = l; existing.title.text = l.name; continue }
            val c = LightCard(l)
            cards[l.id] = c
            list.addView(c.root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })
            c.root.alpha = 0f
            c.root.animate().alpha(1f).setDuration(250).start()
        }
        empty.visibility = if (lights.isEmpty()) View.VISIBLE else View.GONE
        updateSummary()
    }

    private fun updateSummary() {
        val known = cards.values.filter { it.online != null }
        val on = known.count { it.isOn }
        val off = known.count { it.online == false }
        power.active = on > 0
        summary.text = when {
            lights.isEmpty() -> "Sin bombillas"
            known.isEmpty() -> "Conectando…"
            else -> "$on de ${lights.size} encendidas" + if (off > 0) "  ·  $off sin conexión" else ""
        }
    }

    inner class LightCard(var light: Light) {
        val root = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(cSurface, 24, stroke = cStroke)
        }
        private val bulb = BulbView(this@MainActivity)
        val title = TextView(this@MainActivity).apply {
            text = light.name; textSize = 18f; setTextColor(cText); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val dot = View(this@MainActivity).apply { background = rounded(cMuted, 99) }
        private val sub = TextView(this@MainActivity).apply { textSize = 12.5f; setTextColor(cMuted); text = "${light.kind} · ${light.host}" }
        private val sw = Switch(this@MainActivity).apply {
            thumbTintList = stateList(Color.WHITE, Color.parseColor("#C9CEDA"))
            trackTintList = stateList(cAccent, cSurface2)
        }
        private val body = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        private val err = TextView(this@MainActivity).apply { textSize = 13f; setTextColor(cDanger); visibility = View.GONE; setPadding(0, dp(12), 0, 0) }
        private val retry = pill("Reintentar", filled = false) { refresh() }.apply { visibility = View.GONE }

        var controls: Controls? = null
        private var binding = false
        var quietUntil = 0L
        var online: Boolean? = null
        var isOn = false
        var last: LightState? = null

        init {
            val head = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(bulb, LinearLayout.LayoutParams(dp(60), dp(60)))
            val names = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            names.addView(title)
            val subRow = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, 0) }
            subRow.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
            subRow.addView(sub)
            names.addView(subRow)
            head.addView(names, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            head.addView(sw)
            head.addView(TextView(this@MainActivity).apply {
                text = "⋮"; textSize = 24f; setTextColor(cMuted); gravity = Gravity.CENTER
                setOnClickListener { showCardMenu(it, light) }
            }, LinearLayout.LayoutParams(dp(36), dp(44)))
            root.addView(head)
            root.addView(err)
            root.addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { topMargin = dp(10) })
            root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

            sw.setOnCheckedChangeListener { v, on ->
                if (binding) return@setOnCheckedChangeListener
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                isOn = on
                bulb.set(on, bulb.color, bulb.level)
                body.alpha = if (on) 1f else 0.45f
                send(light, Change(on = on))
                updateSummary()
            }
        }

        fun quiet() { quietUntil = System.currentTimeMillis() + 2500 }

        private fun build(s: LightState) {
            val minB = if (light.kind == "WiZ") 10 else 1
            controls = Controls(
                body, s.tempRange, s.hasColor, minB,
                onBrightness = { send(light, Change(brightness = it), light.id + "b", 120) },
                onTemp = { send(light, Change(temp = it), light.id + "t", 120) },
                onColor = { h, sat ->
                    val c = Color.HSVToColor(floatArrayOf(h.toFloat(), sat / 100f, 1f))
                    send(light, Change(rgb = intArrayOf(Color.red(c), Color.green(c), Color.blue(c))), light.id + "c", 120)
                },
            )
            controls?.onLocalChange = {
                binding = true; sw.isChecked = true; binding = false
                isOn = true; body.alpha = 1f
                bulb.set(true, controls!!.previewColor(), controls!!.previewLevel())
                updateSummary()
            }
            body.visibility = View.VISIBLE
        }

        fun bind(s: LightState) {
            online = true
            last = s
            isOn = s.on
            err.visibility = View.GONE; retry.visibility = View.GONE
            root.alpha = 1f
            dot.background = rounded(cOk, 99)
            sub.text = "${light.kind} · ${light.host}"
            if (controls == null) build(s)
            if (System.currentTimeMillis() < quietUntil || controls?.touching == true) return  // acabas de tocar algo: no pisarlo
            binding = true
            sw.isChecked = s.on
            binding = false
            controls?.set(s.brightness, s.temp, s.rgb)
            body.alpha = if (s.on) 1f else 0.45f
            val t = s.temp
            val rgb = s.rgb
            val shown = when {
                t != null -> kelvinToRgb(t)
                rgb != null -> Color.rgb(rgb[0], rgb[1], rgb[2])
                else -> kelvinToRgb(2700)
            }
            bulb.set(s.on, shown, s.brightness / 100f)
        }

        fun showError(msg: String) {
            online = false
            last = null
            dot.background = rounded(cDanger, 99)
            sub.text = "Sin conexión"
            err.text = msg; err.visibility = View.VISIBLE
            retry.visibility = View.VISIBLE
            root.alpha = 0.75f
            bulb.set(false, bulb.color, 0.5f)
        }
    }

    // ------------------------------------------------------------ habitaciones
    private fun syncGroups() {
        val ids = groups.map { it.id }.toSet()
        groupCards.keys.filter { it !in ids }.forEach { id -> groupCards.remove(id)?.let { groupList.removeView(it.root) } }
        for (g in groups) {
            val existing = groupCards[g.id]
            if (existing != null) { existing.group = g; existing.title.text = g.name; continue }
            val c = GroupCard(g)
            groupCards[g.id] = c
            groupList.addView(c.root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })
            c.root.alpha = 0f
            c.root.animate().alpha(1f).setDuration(250).start()
        }
        emptyGroups.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        updateGroups()
    }

    private fun updateGroups() = groupCards.values.forEach { it.update() }

    /** Manda un cambio a todas las bombillas de la habitación; `build` decide qué le toca a cada una. */
    private fun sendGroup(g: Group, key: String, delay: Long = 0, build: (LightCard?) -> Change?) {
        groupCards[g.id]?.quiet()
        for (id in g.members) {
            val l = lights.firstOrNull { it.id == id } ?: continue
            val c = build(cards[id]) ?: continue
            send(l, c, l.id + key, delay)
        }
    }

    inner class GroupCard(var group: Group) {
        val root = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(cSurface, 24, stroke = cAccent and 0x66FFFFFF.toInt())
        }
        private val bulb = BulbView(this@MainActivity)
        val title = TextView(this@MainActivity).apply {
            text = group.name; textSize = 18f; setTextColor(cText); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val sub = TextView(this@MainActivity).apply { textSize = 12.5f; setTextColor(cMuted); setPadding(0, dp(3), 0, 0) }
        private val sw = Switch(this@MainActivity).apply {
            thumbTintList = stateList(Color.WHITE, Color.parseColor("#C9CEDA"))
            trackTintList = stateList(cAccent, cSurface2)
        }
        private val body = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        var controls: Controls? = null
        private var built = false
        private var binding = false
        var quietUntil = 0L

        init {
            val head = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(bulb, LinearLayout.LayoutParams(dp(60), dp(60)))
            val names = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            names.addView(title); names.addView(sub)
            head.addView(names, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            head.addView(sw)
            head.addView(TextView(this@MainActivity).apply {
                text = "⋮"; textSize = 24f; setTextColor(cMuted); gravity = Gravity.CENTER
                setOnClickListener { showGroupMenu(it, group) }
            }, LinearLayout.LayoutParams(dp(36), dp(44)))
            root.addView(head)
            root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

            sw.setOnCheckedChangeListener { v, on ->
                if (binding) return@setOnCheckedChangeListener
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                body.alpha = if (on) 1f else 0.45f
                bulb.set(on, bulb.color, bulb.level)
                sendGroup(group, "x") { Change(on = on) }
            }
        }

        fun quiet() { quietUntil = System.currentTimeMillis() + 2500 }

        private fun states() = group.members.mapNotNull { cards[it]?.last }

        private fun build(st: List<LightState>) {
            val temps = st.mapNotNull { it.tempRange }
            val range = if (temps.isEmpty()) null else temps.minOf { it.first }..temps.maxOf { it.last }
            controls = Controls(
                body, range, st.any { it.hasColor }, 1,
                onBrightness = { v -> sendGroup(group, "b", 120) { Change(brightness = v) } },
                onTemp = { v -> sendGroup(group, "t", 120) { Change(temp = v) } },
                onColor = { h, sat ->
                    val c = Color.HSVToColor(floatArrayOf(h.toFloat(), sat / 100f, 1f))
                    val rgb = intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
                    sendGroup(group, "c", 120) { m -> if (m?.last?.hasColor == true) Change(rgb = rgb) else null }
                },
            )
            controls?.onLocalChange = {
                binding = true; sw.isChecked = true; binding = false
                body.alpha = 1f
                bulb.set(true, controls!!.previewColor(), controls!!.previewLevel())
            }
            body.visibility = View.VISIBLE
            built = true
        }

        fun update() {
            val members = group.members.mapNotNull { cards[it] }
            val st = states()
            if (members.isEmpty()) {
                sub.text = "Sin bombillas · edítala desde ⋮"; bulb.set(false, bulb.color, 0.5f); root.alpha = 0.7f; return
            }
            root.alpha = 1f
            val onCount = st.count { it.on }
            val offline = members.count { it.online == false }
            sub.text = when {
                st.isEmpty() && offline == 0 -> "${members.size} bombillas · conectando…"
                st.isEmpty() -> "${members.size} bombillas · sin conexión"
                else -> "${members.size} bombillas · $onCount encendidas" + if (offline > 0) " · $offline sin conexión" else ""
            }
            if (st.isEmpty()) return
            if (!built) build(st)
            if (System.currentTimeMillis() < quietUntil || controls?.touching == true) return
            val on = onCount > 0
            binding = true; sw.isChecked = on; binding = false
            body.alpha = if (on) 1f else 0.45f
            val ref = st.filter { it.on }.ifEmpty { st }
            controls?.set(ref.map { it.brightness }.average().toInt(), ref.firstNotNullOfOrNull { it.temp }, ref.firstNotNullOfOrNull { it.rgb })
            val t = ref.firstNotNullOfOrNull { it.temp }
            val rgb = ref.firstNotNullOfOrNull { it.rgb }
            val shown = when {
                t != null -> kelvinToRgb(t)
                rgb != null -> Color.rgb(rgb[0], rgb[1], rgb[2])
                else -> kelvinToRgb(2700)
            }
            bulb.set(on, shown, ref.map { it.brightness }.average().toFloat() / 100f)
        }
    }

    private fun showGroupMenu(anchor: View, g: Group) {
        val pm = PopupMenu(this, anchor, Gravity.END)
        pm.menu.add(0, 1, 0, "Editar habitación")
        pm.menu.add(0, 2, 1, "Quitar habitación")
        pm.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> groupSheet(g); 2 -> removeGroupSheet(g) }
            true
        }
        pm.show()
    }

    private fun groupSheet(existing: Group?) {
        if (lights.isEmpty()) { snack("Primero añade alguna bombilla"); return }
        val name = field("Nombre (p. ej. Salón)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, existing?.name ?: "")
        val picked = HashSet(existing?.members ?: emptyList())
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(name)
        box.addView(TextView(this).apply { text = "Bombillas de la habitación"; textSize = 13f; setTextColor(cMuted); setPadding(dp(4), dp(16), 0, dp(4)) })
        for (l in lights) {
            box.addView(CheckBox(this).apply {
                text = "${l.name}  ·  ${l.kind}"; textSize = 15.5f; setTextColor(cText)
                buttonTintList = stateList(cAccent, cMuted)
                isChecked = l.id in picked
                setPadding(dp(8), dp(10), 0, dp(10))
                setOnCheckedChangeListener { _, on -> if (on) picked += l.id else picked -= l.id }
            })
        }
        val msg = TextView(this).apply { textSize = 13f; setTextColor(cDanger); visibility = View.GONE }
        box.addView(msg, lp(10))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box) }
        sheet(if (existing == null) "Nueva habitación" else "Editar habitación", scroll, "Guardar") { dismiss, _ ->
            val n = name.text.toString().trim()
            when {
                n.isEmpty() -> { msg.text = "Ponle un nombre"; msg.visibility = View.VISIBLE }
                picked.isEmpty() -> { msg.text = "Elige al menos una bombilla"; msg.visibility = View.VISIBLE }
                else -> {
                    val members = lights.map { it.id }.filter { it in picked }
                    if (existing == null) groups += Group("g" + System.currentTimeMillis(), n, members)
                    else { val i = groups.indexOfFirst { it.id == existing.id }; if (i >= 0) groups[i] = existing.copy(name = n, members = members) }
                    saveGroups(); syncGroups(); dismiss()
                    snack(if (existing == null) "Habitación creada" else "Habitación actualizada")
                }
            }
        }
    }

    private fun removeGroupSheet(g: Group) {
        val txt = TextView(this).apply { text = "Se quitará la habitación «${g.name}». Las bombillas siguen en la app, no se tocan."; textSize = 15f; setTextColor(cMuted) }
        sheet("Quitar habitación", txt, "Quitar") { dismiss, _ ->
            groups.removeAll { it.id == g.id }; saveGroups(); syncGroups(); dismiss(); snack("Habitación quitada")
        }
    }

    /** Brillo (píldora) + pestañas Blanco/Color + panel correspondiente, como en las apps oficiales. */
    inner class Controls(
        host: LinearLayout, tempRange: IntRange?, hasColor: Boolean, minBright: Int,
        onBrightness: (Int) -> Unit, onTemp: (Int) -> Unit, onColor: (Int, Int) -> Unit,
    ) {
        private val bright = PillSlider(this@MainActivity, minBright, 100)
        private val brightValue = TextView(this@MainActivity).apply { textSize = 15f; setTextColor(cMuted); text = "—" }
        private val white: WhitePanel? = tempRange?.let { WhitePanel(this@MainActivity, it.first, it.last) }
        private val color: ColorPanel? = if (hasColor) ColorPanel(this@MainActivity) else null
        private val tabs = ArrayList<TextView>()   // 0 = Blanco, 1 = Color
        private val tabModes = ArrayList<Int>()
        private val sideText = TextView(this@MainActivity).apply { textSize = 15f; setTextColor(cMuted); visibility = View.GONE }
        private val swatch = View(this@MainActivity).apply { visibility = View.GONE }
        private var mode = 0
        private var derived = -1
        private val onColorCb = onColor
        private val onBrightnessCb = onBrightness
        /** Se avisa en cuanto el usuario cambia algo, para que el icono responda sin esperar a la bombilla. */
        var onLocalChange: (() -> Unit)? = null
        private var litMode = 0   // 0 = la luz está en blanco, 1 = en color
        val touching: Boolean get() = bright.touching || white?.touching == true || color?.touching == true
        private val colorBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val presetRow = LinearLayout(this@MainActivity)

        init {
            val top = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this@MainActivity).apply { text = "Brillo"; textSize = 17f; setTextColor(cText) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            top.addView(brightValue)
            host.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            host.addView(bright, LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })
            bright.onChange = { brightValue.text = "$it%"; onBrightness(it); onLocalChange?.invoke() }

            if (white != null || color != null) {
                host.addView(View(this@MainActivity).apply { setBackgroundColor(cStroke) },
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(18) })

                // fila de pestañas: altura fija; pestañas a la izquierda y valor/muestra a la derecha
                val tabRow = FrameLayout(this@MainActivity)
                val left = LinearLayout(this@MainActivity)
                if (white != null) addTab(left, "Blanco", 0)
                if (color != null) addTab(left, "Color", 1)
                tabRow.addView(left, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.START))
                val right = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                right.addView(sideText)
                right.addView(swatch, LinearLayout.LayoutParams(dp(30), dp(30)))
                tabRow.addView(right, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.END))
                host.addView(tabRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })

                val panels = FrameLayout(this@MainActivity)
                white?.let { panels.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, dp(64))) }
                color?.let { c ->
                    colorBox.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, dp(130)))
                    colorBox.addView(presetRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)).apply { topMargin = dp(14) })
                    colorBox.addView(TextView(this@MainActivity).apply {
                        text = "Los colores guardan también el brillo · mantén pulsado uno para editarlo · «+» guarda el actual"
                        textSize = 11.5f; setTextColor(cMuted); setPadding(dp(2), dp(6), 0, 0)
                    })
                    panels.addView(colorBox, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    rebuildPresets()
                }
                host.addView(panels, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(4) })

                white?.onChange = { litMode = 0; sideUpdate(); onTemp(it); onLocalChange?.invoke() }
                color?.onChange = { h, sat -> litMode = 1; sideUpdate(); onColor(h, sat); onLocalChange?.invoke() }
                setMode(if (white != null) 0 else 1)
            }
        }

        /** Color del círculo: se oscurece según el brillo guardado, para que se note de un vistazo. */
        private fun hsColor(h: Int, s: Int, b: Int) = Color.HSVToColor(floatArrayOf(h.toFloat(), s / 100f, if (b < 0) 1f else (0.3f + 0.7f * b / 100f)))

        fun rebuildPresets() {
            val panel = color ?: return
            presetRow.removeAllViews()
            presets.forEachIndexed { i, hs ->
                val cell = FrameLayout(this@MainActivity)
                cell.addView(View(this@MainActivity).apply {
                    background = rounded(hsColor(hs[0], hs[1], hs[2]), 99, stroke = Color.parseColor("#55FFFFFF"), strokeDp = 1)
                    setOnClickListener { v ->
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        panel.set(hs[0], hs[1]); sideUpdate(); onColorCb(hs[0], hs[1])
                        if (hs[2] >= 0) { bright.set(hs[2]); brightValue.text = "${hs[2]}%"; onBrightnessCb(hs[2]) }
                        litMode = 1; onLocalChange?.invoke()
                    }
                    setOnLongClickListener { v ->
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        presetMenu(v, i, panel); true
                    }
                }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                presetRow.addView(cell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }
            if (presets.size < maxPresets) {
                val cell = FrameLayout(this@MainActivity)
                cell.addView(TextView(this@MainActivity).apply {
                    text = "+"; textSize = 18f; setTextColor(cMuted); gravity = Gravity.CENTER
                    background = rounded(Color.TRANSPARENT, 99, stroke = cMuted, strokeDp = 1)
                    setOnClickListener {
                        presets += intArrayOf(panel.hue, panel.sat, bright.value); savePresets(); refreshPresetRows()
                        snack("Color y brillo guardados en los preajustes")
                    }
                }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                presetRow.addView(cell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }
        }

        private fun presetMenu(anchor: View, index: Int, panel: ColorPanel) {
            val pm = PopupMenu(this@MainActivity, anchor)
            pm.menu.add(0, 1, 0, "Guardar el color actual aquí")
            pm.menu.add(0, 2, 1, "Borrar este color")
            pm.menu.add(0, 3, 2, "Restablecer predeterminados")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { presets[index] = intArrayOf(panel.hue, panel.sat, bright.value); snack("Preajuste actualizado (color y brillo)") }
                    2 -> { presets.removeAt(index); snack("Preajuste borrado") }
                    3 -> { presets.clear(); defaultPresets.forEach { d -> presets += d.copyOf() }; snack("Preajustes restablecidos") }
                }
                savePresets(); refreshPresetRows()
                true
            }
            pm.show()
        }

        private fun addTab(row: LinearLayout, label: String, m: Int) {
            val t = TextView(this@MainActivity).apply {
                text = label; textSize = 17f; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), dp(4))
                setOnClickListener { setMode(m) }
            }
            row.addView(t, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { marginEnd = dp(22) })
            tabs += t; tabModes += m
        }

        /** Subrayado dibujado en la propia pestaña: una barra de 3 dp pegada abajo. */
        private fun underline(): Drawable = LayerDrawable(arrayOf<Drawable>(rounded(cAccent, 2))).apply {
            setLayerGravity(0, Gravity.BOTTOM)
            setLayerHeight(0, dp(3))
        }

        private fun setMode(m: Int) {
            mode = m
            white?.visibility = if (m == 0) View.VISIBLE else View.GONE
            colorBox.visibility = if (m == 1 && color != null) View.VISIBLE else View.GONE
            tabs.forEachIndexed { i, t ->
                val sel = tabModes[i] == m
                t.setTextColor(if (sel) cAccent else cText)
                t.background = if (sel) underline() else null
            }
            sideUpdate()
        }

        private fun sideUpdate() {
            if (mode == 1 && color != null) {
                swatch.background = rounded(color.currentColor(), 8); swatch.visibility = View.VISIBLE; sideText.visibility = View.GONE
            } else if (white != null) {
                sideText.text = "${white.value} K"; sideText.visibility = View.VISIBLE; swatch.visibility = View.GONE
            }
        }

        fun previewColor(): Int =
            if (litMode == 1 && color != null) color.currentColor()
            else if (white != null) kelvinToRgb(white.value)
            else color?.currentColor() ?: kelvinToRgb(2700)

        fun previewLevel(): Float = bright.value / 100f

        /** Refleja el estado real de la bombilla, sin pisar lo que el usuario esté tocando. */
        fun set(brightness: Int, temp: Int?, rgb: IntArray?) {
            if (!bright.touching) { bright.set(brightness); brightValue.text = "$brightness%" }
            temp?.let { white?.set(it) }
            rgb?.let {
                val hsv = FloatArray(3); Color.RGBToHSV(it[0], it[1], it[2], hsv)
                color?.set(hsv[0].toInt(), (hsv[1] * 100).toInt())
            }
            // solo cambiamos de pestaña si la bombilla cambia de modo (blanco ↔ color) por fuera
            val d = if (temp != null) 0 else if (rgb != null && color != null) 1 else if (white != null) 0 else 1
            litMode = d
            if (d != derived) { derived = d; if (white != null || color != null) setMode(d) }
            sideUpdate()
        }
    }

    // ------------------------------------------------------------ menús y hojas
    /** Menú del botón «+»: tarjeta flotante sobre el botón, con icono y descripción por opción. */
    private fun showAddMenu(fab: View) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cSurface, 22, stroke = cStroke)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            alpha = 0f
        }
        // margen alrededor de la tarjeta para que quepa su sombra dentro de la ventana
        val holder = FrameLayout(this).apply { setPadding(dp(12), dp(12), dp(12), dp(12)); addView(card, MATCH_PARENT, WRAP_CONTENT) }
        val popup = PopupWindow(holder, dp(300), WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
        }
        card.elevation = dp(14).toFloat()

        fun item(kind: Int, tint: Int, title: String, sub: String, action: () -> Unit): View {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(14), dp(10))
            }
            val icon = FrameLayout(this).apply { background = rounded((tint and 0x00FFFFFF) or 0x33000000, 99) }
            icon.addView(GlyphView(this, kind, tint), FrameLayout.LayoutParams(dp(44), dp(44)))
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
            texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(cText); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            texts.addView(TextView(this).apply { text = sub; textSize = 12.5f; setTextColor(cMuted); setPadding(0, dp(2), 0, 0) })
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> v.background = rounded(Color.parseColor("#1AFFFFFF"), 16)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.background = null
                }
                false
            }
            row.setOnClickListener { popup.dismiss(); action() }
            return row
        }

        card.addView(item(0, cAccent, "Añadir bombilla WiZ", "Busca las bombillas de tu red") { discover() })
        card.addView(View(this).apply { setBackgroundColor(cStroke) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { marginStart = dp(68); marginEnd = dp(12) })
        card.addView(item(1, Color.parseColor("#4FD1C5"), "Añadir bombilla Tapo", "Con su IP y tu cuenta de Tapo") { addTapoSheet() })

        fab.animate().rotation(45f).setDuration(180).start()
        popup.setOnDismissListener { fab.animate().rotation(0f).setDuration(180).start() }
        // justo encima del botón (margen derecho del botón 22 dp + 12 dp de holgura de sombra)
        popup.showAtLocation(rootFrame, Gravity.BOTTOM or Gravity.END, dp(10), dp(26 + 60 + 6 - 12))
        card.post {
            card.pivotX = card.width.toFloat(); card.pivotY = card.height.toFloat()
            card.scaleX = 0.85f; card.scaleY = 0.85f; card.translationY = dp(14).toFloat()
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun showCardMenu(anchor: View, l: Light) {
        val pm = PopupMenu(this, anchor, Gravity.END)
        if (l is Tapo) pm.menu.add(0, 3, 0, "Programaciones")
        pm.menu.add(0, 1, 1, "Renombrar")
        pm.menu.add(0, 2, 2, "Quitar")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> renameSheet(l)
                2 -> removeSheet(l)
                3 -> startActivity(android.content.Intent(this, SchedulesActivity::class.java).putExtra("id", l.id))
            }
            true
        }
        pm.show()
    }

    private fun field(hint: String, type: Int, text: String = ""): EditText = EditText(this).apply {
        this.hint = hint; inputType = type; setText(text); setSingleLine()
        setTextColor(cText); setHintTextColor(cMuted); textSize = 16f
        background = rounded(cSurface2, 14)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun lp(top: Int = 10) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    /** Hoja inferior con título, contenido y dos botones. */
    private fun sheet(title: String, content: View, ok: String, onOk: (dismiss: () -> Unit, btn: TextView) -> Unit): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = GradientDrawable().apply {
                setColor(cSurface); cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        panel.addView(TextView(this).apply { text = title; textSize = 21f; setTextColor(cText); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        panel.addView(content, lp(14))
        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        buttons.addView(pill("Cancelar", filled = false) { d.dismiss() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) })
        val okBtn = pill(ok, filled = true) {}
        okBtn.setOnClickListener { onOk({ d.dismiss() }, okBtn) }
        buttons.addView(okBtn, LinearLayout.LayoutParams(0, dp(46), 1f))
        panel.addView(buttons, lp(20))
        d.setContentView(panel)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setDimAmount(0.6f)
        }
        d.show()
        return d
    }

    private fun addTapoSheet() {
        val ip = field("IP de la Tapo (p. ej. 192.168.1.50)", InputType.TYPE_CLASS_PHONE)
        val user = field("Email de la cuenta Tapo", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass = field("Contraseña de Tapo", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val msg = TextView(this).apply { textSize = 13f; setTextColor(cDanger); visibility = View.GONE }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "La contraseña se guarda cifrada en este móvil."; textSize = 13f; setTextColor(cMuted) })
            addView(ip, lp(14)); addView(user, lp()); addView(pass, lp()); addView(msg, lp(12))
        }
        sheet("Añadir bombilla Tapo", form, "Añadir") { dismiss, btn ->
            val host = ip.text.toString().trim()
            val t = Tapo("tapo-$host", "Tapo", host, user.text.toString().trim(), pass.text.toString())
            if (host.isEmpty() || t.username.isEmpty() || t.password.isEmpty()) {
                msg.text = "Rellena los tres campos"; msg.visibility = View.VISIBLE; return@sheet
            }
            btn.text = "Conectando…"; btn.isEnabled = false; msg.visibility = View.GONE
            io.execute {
                val e = runCatching { t.state() }.exceptionOrNull()
                ui.post {
                    if (e != null) {
                        btn.text = "Añadir"; btn.isEnabled = true
                        msg.text = e.message ?: "No se pudo conectar"; msg.visibility = View.VISIBLE
                    } else {
                        lights.removeAll { it.host == t.host }
                        lights += t; saveLights(); syncCards(); refresh()
                        dismiss(); snack("Tapo añadida")
                    }
                }
            }
        }
    }

    private fun renameSheet(l: Light) {
        val input = field("Nombre", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, l.name)
        sheet("Renombrar", input, "Guardar") { dismiss, _ ->
            val nuevo = input.text.toString().trim().ifBlank { l.name }
            val i = lights.indexOf(l)
            if (i >= 0) {
                lights[i] = if (l is Tapo) Tapo(l.id, nuevo, l.host, l.username, l.password) else Wiz(l.id, nuevo, l.host)
                saveLights(); syncCards()
            }
            dismiss()
        }
        input.requestFocus()
    }

    private fun removeSheet(l: Light) {
        val txt = TextView(this).apply { text = "Se quitará «${l.name}» (${l.host}) de la app. La bombilla no se toca."; textSize = 15f; setTextColor(cMuted) }
        sheet("Quitar bombilla", txt, "Quitar") { dismiss, _ ->
            lights.remove(l); saveLights(); syncCards()
            groups.replaceAll { g -> g.copy(members = g.members.filter { it != l.id }) }
            saveGroups(); syncGroups()
            dismiss(); snack("Bombilla quitada")
        }
    }

    // ------------------------------------------------------------ piezas
    private fun snack(msg: String) {
        snackRunnable?.let(ui::removeCallbacks)
        snackView?.let { rootFrame.removeView(it) }
        val v = TextView(this).apply {
            text = msg; textSize = 14f; setTextColor(cText); setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(Color.parseColor("#2F3445"), 16, stroke = cStroke)
            elevation = dp(8).toFloat(); alpha = 0f
        }
        snackView = v
        rootFrame.addView(v, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(18), 0, dp(96), dp(34))
        })
        v.animate().alpha(1f).setDuration(180).start()
        snackRunnable = Runnable { v.animate().alpha(0f).setDuration(220).withEndAction { rootFrame.removeView(v) }.start() }
        ui.postDelayed(snackRunnable!!, 2600)
    }

    private fun pill(text: String, filled: Boolean, onClick: (View) -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14.5f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) cOnAccent else cText)
        background = if (filled) rounded(cAccent, 99) else rounded(Color.TRANSPARENT, 99, stroke = cStroke, strokeDp = 1)
        setPadding(dp(18), 0, dp(18), 0)
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        setOnClickListener { onClick(it) }
    }

    private fun circleButton(glyph: String, sizeDp: Int, bg: Int, fg: Int, textSp: Float, onClick: (View) -> Unit) = TextView(this).apply {
        text = glyph; textSize = textSp; setTextColor(fg); gravity = Gravity.CENTER
        background = if (bg == cSurface) rounded(bg, 99, stroke = cStroke, strokeDp = 1) else rounded(bg, 99)
        minimumWidth = dp(sizeDp); minimumHeight = dp(sizeDp)
        setOnClickListener { onClick(it) }
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(strokeDp), stroke)
    }

    private fun stateList(checked: Int, unchecked: Int) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(checked, unchecked),
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** Bombilla dibujada: se ilumina con el color real de la luz. */
class BulbView(ctx: Context) : View(ctx) {
    var color = Color.parseColor("#FFB547"); private set
    var level = 1f; private set
    private var on = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(on: Boolean, color: Int, level: Float) {
        this.on = on; this.color = color; this.level = level.coerceIn(0f, 1f); invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h * 0.40f; val r = w * 0.23f
        val cr = Color.red(color); val cg = Color.green(color); val cb = Color.blue(color)
        if (on) {
            // el halo llega a transparente justo en el borde de la vista, para que no se vea recortado
            val glow = minOf(cy, w / 2f)
            val a = (70 + 150 * level).toInt().coerceIn(0, 255)
            p.shader = RadialGradient(
                cx, cy, glow,
                intArrayOf(Color.argb(a, cr, cg, cb), Color.argb((a * 0.4f).toInt(), cr, cg, cb), Color.argb(0, cr, cg, cb)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawCircle(cx, cy, glow, p)
            p.shader = null
        }
        p.style = Paint.Style.FILL
        p.color = if (on) color else Color.parseColor("#3A4052")
        c.drawCircle(cx, cy, r, p)
        if (on) { p.color = Color.argb(90, 255, 255, 255); c.drawCircle(cx - r * 0.3f, cy - r * 0.35f, r * 0.28f, p) }
        p.color = if (on) Color.rgb((cr * 0.75f).toInt(), (cg * 0.75f).toInt(), (cb * 0.75f).toInt()) else Color.parseColor("#323747")
        c.drawRoundRect(cx - r * 0.55f, cy + r * 0.75f, cx + r * 0.55f, cy + r * 1.3f, 4f, 4f, p)
        p.color = Color.parseColor("#8B93A7")
        c.drawRoundRect(cx - r * 0.5f, cy + r * 1.38f, cx + r * 0.5f, cy + r * 1.62f, 4f, 4f, p)
        c.drawRoundRect(cx - r * 0.3f, cy + r * 1.7f, cx + r * 0.3f, cy + r * 1.9f, 4f, 4f, p)
    }
}
