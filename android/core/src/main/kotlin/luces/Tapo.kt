package luces

import org.json.JSONObject

/** Bombilla Tapo (TPAP). `username` = email de la cuenta Tapo, `password` = su contraseña. */
class Tapo(
    override val id: String,
    override val name: String,
    override val host: String,
    val username: String,
    val password: String,
) : Light {
    override val kind = "Tapo"
    private val session = TpapSession(host, username, password)

    override fun state(): LightState {
        val r = session.request("get_device_info", null).getJSONObject("result")
        val hasColor = r.has("hue") && r.has("saturation")
        val range = r.optJSONArray("color_temp_range")?.let { it.getInt(0)..it.getInt(1) }
        val ct = r.optInt("color_temp", 0)
        return LightState(
            on = r.optBoolean("device_on"),
            brightness = r.optInt("brightness", 100),
            temp = if (ct > 0) ct else null,
            rgb = if (hasColor) hsvToRgb(r.getInt("hue"), r.getInt("saturation")) else null,
            hasColor = hasColor,
            tempRange = range,
        )
    }

    override fun set(c: Change) {
        if (c.on == false) {
            session.request("set_device_info", JSONObject().put("device_on", false))
            return
        }
        var changed = false
        val rgb = c.rgb
        if (rgb != null) {
            val (h, s) = rgbToHs(rgb[0], rgb[1], rgb[2])
            session.request("set_device_info", JSONObject().put("hue", h).put("saturation", s).put("color_temp", 0))
            changed = true
        } else if (c.temp != null) {
            session.request("set_device_info", JSONObject().put("color_temp", c.temp.coerceIn(2500, 6500)))
            changed = true
        }
        if (c.brightness != null) {
            session.request("set_device_info", JSONObject().put("brightness", c.brightness.coerceIn(1, 100)))
            changed = true
        }
        if (c.on == true || changed) session.request("set_device_info", JSONObject().put("device_on", true))
    }

    // ------------------------------------------------------------ programaciones (viven en la bombilla)
    /** Lista todas las reglas, paginando por si hubiera más de una página. */
    fun schedules(): List<Schedule> {
        val out = mutableListOf<Schedule>()
        var start = 0
        while (true) {
            val r = session.request("get_schedule_rules", JSONObject().put("start_index", start)).getJSONObject("result")
            val list = r.optJSONArray("rule_list")
            val n = list?.length() ?: 0
            for (i in 0 until n) out += Schedule.fromJson(list!!.getJSONObject(i))
            start += n
            if (n == 0 || start >= r.optInt("sum", start)) break
        }
        return out
    }

    /** Crea una regla y devuelve su id. */
    fun addSchedule(s: Schedule): String =
        session.request("add_schedule_rule", s.toJson()).getJSONObject("result").getString("id")

    fun updateSchedule(s: Schedule) {
        requireNotNull(s.id) { "La regla no tiene id" }
        session.request("edit_schedule_rule", s.toJson())
    }

    /** Borra solo la regla indicada. `remove_all` va siempre a false: en true la bombilla borraría todas. */
    fun removeSchedule(id: String) {
        session.request(
            "remove_schedule_rules",
            JSONObject().put("remove_all", false).put("rule_list", org.json.JSONArray().put(JSONObject().put("id", id))),
        )
    }

    /** Petición cruda (para explorar la API del dispositivo). */
    fun raw(method: String, params: JSONObject?): JSONObject = session.request(method, params)

    override fun close() = session.reset()

    companion object {
        fun hsvToRgb(h: Int, s: Int): IntArray {
            val sat = s / 100.0
            val hh = (h % 360) / 60.0
            val i = hh.toInt()
            val f = hh - i
            val p = 1 - sat
            val q = 1 - sat * f
            val t = 1 - sat * (1 - f)
            val (r, g, b) = when (i) {
                0 -> Triple(1.0, t, p)
                1 -> Triple(q, 1.0, p)
                2 -> Triple(p, 1.0, t)
                3 -> Triple(p, q, 1.0)
                4 -> Triple(t, p, 1.0)
                else -> Triple(1.0, p, q)
            }
            return intArrayOf(Math.round(r * 255).toInt(), Math.round(g * 255).toInt(), Math.round(b * 255).toInt())
        }

        fun rgbToHs(r: Int, g: Int, b: Int): Pair<Int, Int> {
            val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
            val mx = maxOf(rf, gf, bf); val mn = minOf(rf, gf, bf)
            val d = mx - mn
            val h = when {
                d == 0.0 -> 0.0
                mx == rf -> 60 * (((gf - bf) / d) % 6)
                mx == gf -> 60 * ((bf - rf) / d + 2)
                else -> 60 * ((rf - gf) / d + 4)
            }.let { if (it < 0) it + 360 else it }
            val s = if (mx == 0.0) 0.0 else d / mx
            return Math.round(h).toInt() % 360 to Math.round(s * 100).toInt()
        }
    }
}
