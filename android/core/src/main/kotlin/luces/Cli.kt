package luces

/** Prueba desde el PC:  gradle :core:run --args="state 192.168.1.50"  |  "set 192.168.1.50 brightness 40"  |  "discover" */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "discover" -> Wiz.discover().forEach(::println)
        "state" -> println(Wiz("x", "x", args[1]).state())
        "set" -> {
            val w = Wiz("x", "x", args[1])
            val v = args[3]
            w.set(
                when (args[2]) {
                    "on" -> Change(on = v == "1")
                    "brightness" -> Change(brightness = v.toInt())
                    "temp" -> Change(temp = v.toInt())
                    else -> error("campo desconocido: ${args[2]}")
                }
            )
            println(w.state())
        }
        "tapo-sched", "tapo-sched-selftest", "tapo", "tapo-raw" -> {
            val path = System.getenv("TAPO_CONFIG") ?: (System.getProperty("user.home") + "/.config/luces/tapo.json")
            val o = org.json.JSONObject(java.io.File(path).readText())
            val t = Tapo("tapo", "Tapo", o.getString("host"), o.getString("username"), o.getString("password"))
            if (args[0] == "tapo-sched-selftest") {
                val before = t.schedules().first { it.id == "S1" }.raw.toString()
                val created = mutableListOf<String>()
                try {
                    val a = t.addSchedule(Schedule(null, false, 180, 1, true, 50, 3000, 0, 0, 600_000))
                    created += a
                    println("add(desactivada) -> $a")
                    val cur = t.schedules().first { it.id == a }
                    t.updateSchedule(cur.copy(enabled = true, startMin = 185, colorTemp = 0, hue = 120, saturation = 100))
                    println("edit(activar+color) -> " + t.schedules().first { it.id == a }.copy(raw = null))
                    val b = t.addSchedule(Schedule(null, true, 190, 1, false, 100, 0, 0, 0, 0))
                    created += b
                    println("add(apagar, activa) -> " + t.schedules().first { it.id == b }.copy(raw = null))
                } finally {
                    created.forEach { runCatching { t.removeSchedule(it) }.onFailure { e -> println("NO SE PUDO BORRAR $it: ${e.message}") } }
                }
                val after = t.schedules()
                println("tras borrar: ${after.map { it.id }}")
                println("S1 intacta: " + (after.first { it.id == "S1" }.raw.toString() == before))
            } else if (args[0] == "tapo-sched") {
                t.schedules().forEach { println(it.copy(raw = null)) }
            } else if (args[0] == "tapo-raw") {
                // tapo-raw <metodo> [json-params]  (solo para explorar; usar métodos get_*)
                val params = args.getOrNull(2)?.let { org.json.JSONObject(it) }
                println(runCatching { t.raw(args[1], params).toString(2) }.getOrElse { "ERROR: ${it.message}" })
            } else {
                if (args.size >= 3) {
                    val v = args[2]
                    t.set(
                        when (args[1]) {
                            "on" -> Change(on = v == "1")
                            "brightness" -> Change(brightness = v.toInt())
                            "temp" -> Change(temp = v.toInt())
                            "hue" -> Change(rgb = Tapo.hsvToRgb(v.toInt(), 100))
                            else -> error("campo desconocido: ${args[1]}")
                        }
                    )
                }
                println(t.state())
            }
        }
        else -> println("uso: discover | state <ip> | set <ip> on|brightness|temp <valor> | tapo [on|brightness|temp|hue <valor>]")
    }
}
