package luces

import org.json.JSONObject
import java.time.LocalDate

/**
 * Programación guardada en la propia bombilla Tapo.
 *
 * `weekMask`: bit 0 = domingo, bit 1 = lunes … bit 6 = sábado (62 = lunes a viernes).
 * `raw` conserva los campos que no tocamos, para no perderlos al editar.
 */
data class Schedule(
    val id: String?,
    val enabled: Boolean,
    val startMin: Int,
    val weekMask: Int,
    val on: Boolean,
    val brightness: Int,
    val colorTemp: Int,   // 0 si es modo color
    val hue: Int,
    val saturation: Int,
    val transitionMs: Int,
    val raw: JSONObject? = null,
) {
    val isColor get() = on && colorTemp == 0

    fun toJson(today: LocalDate = LocalDate.now()): JSONObject {
        val o = if (raw != null) JSONObject(raw.toString()) else JSONObject()
            .put("e_min", 0).put("time_offset", 0).put("e_action", "none")
            .put("s_type", "normal").put("mode", "repeat").put("e_type", "normal")
        o.put("enable", enabled)
        o.put("s_min", startMin)
        o.put("week_day", weekMask)
        // Las reglas activas guardan la fecha de referencia; las desactivadas el firmware las deja a 0.
        if (enabled || raw == null) {
            o.put("year", today.year).put("month", today.monthValue).put("day", today.dayOfMonth)
        }
        val d = (raw?.optJSONObject("desired_states")?.let { JSONObject(it.toString()) } ?: JSONObject())
            .put("on", on)
            .put("brightness", brightness)
            .put("color_temp", colorTemp)
            .put("hue", hue)
            .put("saturation", saturation)
            .put("transition_period", transitionMs)
            .put("auto", false)
        o.put("desired_states", d)
        if (id != null) o.put("id", id)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Schedule {
            val d = o.optJSONObject("desired_states") ?: JSONObject()
            return Schedule(
                id = o.optString("id").ifEmpty { null },
                enabled = o.optBoolean("enable"),
                startMin = o.optInt("s_min"),
                weekMask = o.optInt("week_day"),
                on = d.optBoolean("on", true),
                brightness = d.optInt("brightness", 100),
                colorTemp = d.optInt("color_temp", 0),
                hue = d.optInt("hue", 0),
                saturation = d.optInt("saturation", 0),
                transitionMs = d.optInt("transition_period", 0),
                raw = o,
            )
        }

        private val NAMES = listOf("Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb")
        const val WEEKDAYS = 62
        const val WEEKEND = 65
        const val ALL = 127

        /** Orden de visualización lunes→domingo: bits 1..6 y 0. */
        val DISPLAY_BITS = intArrayOf(1, 2, 3, 4, 5, 6, 0)

        fun daysText(mask: Int): String = when (mask) {
            ALL -> "Todos los días"
            WEEKDAYS -> "Lun–Vie"
            WEEKEND -> "Fin de semana"
            0 -> "Sin días"
            else -> DISPLAY_BITS.filter { mask and (1 shl it) != 0 }.joinToString(" ") { NAMES[it] }
        }
    }
}
