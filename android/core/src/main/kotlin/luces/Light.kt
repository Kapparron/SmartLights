package luces

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Estado de una bombilla. rgb y temp pueden ser null (p. ej. WiZ en modo color no da temp). */
data class LightState(
    val on: Boolean,
    val brightness: Int,
    val temp: Int?,
    val rgb: IntArray?,
    val hasColor: Boolean,
    val tempRange: IntRange?,
)

/** Cambio parcial: solo se aplican los campos no nulos. */
data class Change(
    val on: Boolean? = null,
    val brightness: Int? = null,
    val temp: Int? = null,
    val rgb: IntArray? = null,
)

/** Todas las llamadas son bloqueantes: usar desde un hilo de fondo. */
interface Light {
    val id: String
    val name: String
    val host: String
    val kind: String
    fun state(): LightState
    fun set(c: Change)
    fun close() {}
}

class WizException(msg: String) : Exception(msg)

class Wiz(
    override val id: String,
    override val name: String,
    @Volatile override var host: String,
    /** MAC de la bombilla: identidad estable aunque el router le cambie la IP. */
    @Volatile var mac: String? = null,
) : Light {
    override val kind = "WiZ"
    private var color: Boolean? = null

    private fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
        val msg = JSONObject().put("method", method).put("params", params).toString().toByteArray()
        var last: Exception? = null
        repeat(3) {
            try {
                DatagramSocket().use { s ->
                    s.soTimeout = 1000
                    s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(host), PORT))
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val resp = JSONObject(String(p.data, 0, p.length))
                    resp.optJSONObject("error")?.let {
                        throw WizException("WiZ rechaza $method: ${it.optString("message")}")
                    }
                    return resp
                }
            } catch (e: WizException) {
                throw e
            } catch (e: SocketTimeoutException) {
                last = e
            } catch (e: java.io.IOException) {
                last = e
            }
        }
        throw WizException("Sin respuesta de $host (${last?.message ?: "timeout"})")
    }

    override fun state(): LightState {
        if (color == null) {
            color = try {
                rpc("getSystemConfig").getJSONObject("result").optString("moduleName").uppercase().contains("RGB")
            } catch (e: Exception) {
                false
            }
        }
        val r = rpc("getPilot").getJSONObject("result")
        r.optString("mac").takeIf { it.isNotEmpty() }?.let { mac = it.lowercase() }
        val rgb = if (r.has("r")) intArrayOf(r.getInt("r"), r.getInt("g"), r.getInt("b")) else null
        return LightState(
            on = r.optBoolean("state"),
            brightness = r.optInt("dimming", 100),
            temp = if (r.has("temp")) r.getInt("temp") else null,
            rgb = rgb,
            hasColor = color == true,
            tempRange = 2200..6500,
        )
    }

    override fun set(c: Change) {
        val p = JSONObject()
        c.brightness?.let { p.put("dimming", it.coerceIn(10, 100)); p.put("state", true) }
        c.temp?.let { p.put("temp", it.coerceIn(2200, 6500)); p.put("state", true) }
        c.rgb?.let { p.put("r", it[0]).put("g", it[1]).put("b", it[2]); p.put("state", true) }
        c.on?.let { p.put("state", it) }
        if (p.length() > 0) rpc("setPilot", p)
    }

    companion object {
        const val PORT = 38899

        /** Busca bombillas WiZ por broadcast UDP. Devuelve IPs. */
        fun discover(timeoutMs: Int = 2000): List<String> = discoverMacs(timeoutMs).values.toList()

        /** Como [discover] pero devuelve MAC -> IP (la MAC puede ser "" si la bombilla no la envía). */
        fun discoverMacs(timeoutMs: Int = 2000): Map<String, String> {
            val found = linkedMapOf<String, String>()
            val msg = """{"method":"getPilot","params":{}}""".toByteArray()
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 500
                repeat(2) { s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), PORT)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val ip = p.address.hostAddress!!
                        val mac = runCatching {
                            JSONObject(String(p.data, 0, p.length)).getJSONObject("result").optString("mac")
                        }.getOrDefault("").lowercase()
                        found[mac.ifEmpty { ip }] = ip
                    } catch (e: SocketTimeoutException) {
                    }
                }
            }
            return found
        }
    }
}
