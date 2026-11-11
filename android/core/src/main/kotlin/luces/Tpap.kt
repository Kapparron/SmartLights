package luces

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Base64

class TapoException(msg: String, val code: Int? = null) : Exception(msg)

/**
 * Cliente TPAP (Tapo, firmware nuevo) sobre HTTP plano.
 * Soporta lo que anuncia la bombilla probada: tls=0, suite 1 (P-256/SHA-256, HMAC), aes_128_ccm.
 * Portado de plugp100 (TpapEncryptionSession).
 */
class TpapSession(private val host: String, private val username: String, private val password: String) {
    private val rnd = SecureRandom()
    private var port = 80
    private var dsUrl: String? = null
    private var key: ByteArray? = null
    private var baseNonce: ByteArray? = null
    private var seq = 0L

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    // ------------------------------------------------------------ HTTP
    private fun post(url: String, body: ByteArray, contentType: String): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 4000
            c.readTimeout = 6000
            c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val data = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            return code to data
        } finally {
            c.disconnect()
        }
    }

    private fun login(params: JSONObject, step: String): JSONObject {
        val body = JSONObject().put("method", "login").put("params", params).toString().toByteArray()
        val (status, data) = post("http://$host:$port/", body, "application/json")
        if (status != 200) throw TapoException("Tapo $step: HTTP $status")
        val r = JSONObject(String(data))
        val ec = r.optInt("error_code", 0)
        if (ec != 0) throw TapoException("Tapo $step falló (error_code $ec)", ec)
        return r.getJSONObject("result")
    }

    // ------------------------------------------------------------ handshake
    val established get() = dsUrl != null

    fun reset() {
        dsUrl = null; key = null; baseNonce = null
    }

    fun handshake() {
        reset()
        port = 80
        val disc = login(JSONObject().put("sub_method", "discover"), "discover")
        val mac = disc.optString("mac")
        val tpap = disc.optJSONObject("tpap") ?: throw TapoException("Tapo no anuncia TPAP")
        val tls = tpap.optInt("tls", -1)
        if (tls == 1 || tls == 2) throw TapoException("Esta Tapo exige TLS (tls=$tls), aún no soportado")
        port = tpap.optInt("port", 80).let { if (it > 0) it else 80 }
        val pake = tpap.optJSONArray("pake")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
        val userHashType = if (tpap.has("user_hash_type")) tpap.optInt("user_hash_type") else null

        val passcodeType = when {
            0 in pake -> "default_userpw"
            2 in pake || 5 in pake -> "userpw"
            3 in pake -> "shared_token"
            else -> "default_userpw"
        }
        val candidate = if (passcodeType == "default_userpw") macPass(mac) else password

        val userRandom = ByteArray(32).also(rnd::nextBytes)
        val registerUser = if (userHashType == 1) Crypto.sha256Hex("admin").uppercase() else Crypto.md5Hex("admin")
        val reg = login(
            JSONObject()
                .put("sub_method", "pake_register")
                .put("username", registerUser)
                .put("user_random", b64(userRandom))
                .put("cipher_suites", JSONArray().put(1))
                .put("encryption", JSONArray().put("aes_128_ccm"))
                .put("passcode_type", passcodeType)
                .put("stok", JSONObject.NULL),
            "pake_register",
        )

        val creds = if (passcodeType == "default_userpw") candidate
        else buildCredentials(reg.optJSONObject("extra_crypt"), username, candidate, mac.replace(":", "").replace("-", ""))

        val suite = reg.optInt("cipher_suites", -1)
        if (suite != 1) throw TapoException("Suite TPAP $suite no soportada (solo 1)")
        val encryption = reg.optString("encryption").lowercase().replace("-", "_")
        if (encryption != "aes_128_ccm") throw TapoException("Cifrado TPAP '$encryption' no soportado")
        val iterations = reg.optInt("iterations", 0)
        if (iterations <= 0) throw TapoException("Respuesta TPAP con iteraciones inválidas")
        val devRandom = unb64(reg.getString("dev_random"))
        val devSalt = unb64(reg.getString("dev_salt"))
        val devShare = unb64(reg.getString("dev_share"))

        // ---- SPAKE2+ (P-256)
        val m = P256.decode(hexBytes("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))
        val nPt = P256.decode(hexBytes("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))
        val order = P256.n
        val derived = Crypto.pbkdf2(creds.toByteArray(), devSalt, iterations, 80)
        val w = Crypto.bigUnsigned(derived.copyOfRange(0, 40)).mod(order)
        val h = Crypto.bigUnsigned(derived.copyOfRange(40, 80)).mod(order)
        val x = BigInteger(order.bitLength() + 64, rnd).mod(order - BigInteger.ONE) + BigInteger.ONE

        val l = P256.add(P256.mul(x, P256.g), P256.mul(w, m))!!
        val r = P256.decode(devShare)
        val rPrime = P256.add(r, P256.neg(P256.mul(w, nPt)!!))!!
        val z = P256.mul(x, rPrime)!!
        val v = P256.mul(h, rPrime)!!

        val lEnc = P256.encode(l)
        val rEnc = P256.encode(r)
        val contextHash = Crypto.digest("SHA-256", "PAKE V1".toByteArray() + userRandom + devRandom)
        val transcript = len8(contextHash) + len8(ByteArray(0)) + len8(ByteArray(0)) +
            len8(P256.encode(m)) + len8(P256.encode(nPt)) + len8(lEnc) + len8(rEnc) +
            len8(P256.encode(z)) + len8(P256.encode(v)) + len8(encodeW(w))
        val th = Crypto.digest("SHA-256", transcript)
        val confKeys = hkdfExpand("ConfirmationKeys", th, 64)
        val sharedKey = hkdfExpand("SharedKey", th, 32)
        val userConfirm = Crypto.hmacSha256(confKeys.copyOfRange(0, 32), rEnc)
        val expectedDev = b64(Crypto.hmacSha256(confKeys.copyOfRange(32, 64), lEnc))

        val share = login(
            JSONObject().put("sub_method", "pake_share").put("user_share", b64(lEnc)).put("user_confirm", b64(userConfirm)),
            "pake_share",
        )
        if (!share.optString("dev_confirm").equals(expectedDev, ignoreCase = true))
            throw TapoException("Confirmación TPAP incorrecta: ¿contraseña de Tapo errónea?")
        val stok = share.optString("sessionId").ifEmpty { share.optString("stok") }
        if (stok.isEmpty() || !share.has("start_seq")) throw TapoException("Tapo no devolvió sesión")

        key = Crypto.hkdf(sharedKey, "tp-kdf-salt-aes128-key".toByteArray(), "tp-kdf-info-aes128-key".toByteArray(), 16)
        baseNonce = Crypto.hkdf(sharedKey, "tp-kdf-salt-aes128-iv".toByteArray(), "tp-kdf-info-aes128-iv".toByteArray(), 12)
        seq = share.getLong("start_seq")
        dsUrl = "http://$host:$port/stok=$stok/ds"
    }

    // ------------------------------------------------------------ peticiones
    /** Envía una petición cifrada; reintenta una vez con sesión nueva si falla. */
    @Synchronized
    fun request(method: String, params: JSONObject?): JSONObject {
        var last: Exception? = null
        repeat(2) {
            try {
                if (!established) handshake()
                return once(method, params)
            } catch (e: TapoException) {
                if (e.message?.contains("contraseña") == true || e.message?.contains("soportad") == true) throw e
                last = e; reset()
            } catch (e: IOException) {
                last = e; reset()
            }
        }
        throw TapoException("Tapo no responde (${last?.message})")
    }

    private fun once(method: String, params: JSONObject?): JSONObject {
        val body = JSONObject().put("method", method).put("params", params ?: JSONObject.NULL).toString().toByteArray()
        val s = seq
        val nonce = nonceFor(s)
        val payload = byteArrayOf((s ushr 24).toByte(), (s ushr 16).toByte(), (s ushr 8).toByte(), s.toByte()) +
            Ccm.encrypt(key!!, nonce, body)
        seq = s + 1
        val (status, data) = post(dsUrl!!, payload, "application/octet-stream")
        if (status != 200) throw TapoException("Tapo: HTTP $status")
        if (data.size < 4 + 16) throw TapoException("Respuesta Tapo demasiado corta")
        val rseq = ((data[0].toLong() and 0xFF) shl 24) or ((data[1].toLong() and 0xFF) shl 16) or
            ((data[2].toLong() and 0xFF) shl 8) or (data[3].toLong() and 0xFF)
        val plain = Ccm.decrypt(key!!, nonceFor(rseq), data.copyOfRange(4, data.size))
        val resp = JSONObject(String(plain))
        val ec = resp.optInt("error_code", 0)
        if (ec != 0) throw TapoException("Tapo error_code $ec en $method", ec)
        return resp
    }

    private fun nonceFor(s: Long): ByteArray {
        val n = baseNonce!!.copyOf()
        n[8] = (s ushr 24).toByte(); n[9] = (s ushr 16).toByte(); n[10] = (s ushr 8).toByte(); n[11] = s.toByte()
        return n
    }

    // ------------------------------------------------------------ utilidades del handshake
    private fun hexBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun len8(b: ByteArray): ByteArray {
        var n = b.size.toLong()
        return ByteArray(8) { val v = (n and 0xFF).toByte(); n = n ushr 8; v } + b
    }

    private fun encodeW(w: BigInteger): ByteArray {
        val len = if (w.signum() == 0) 1 else (w.bitLength() + 7) / 8
        val raw = w.toByteArray().let { if (it.size > len) it.copyOfRange(it.size - len, it.size) else ByteArray(len - it.size) + it }
        if (len % 2 == 0) return raw
        return if (raw[0].toInt() and 0x80 != 0) byteArrayOf(0) + raw else raw
    }

    private fun hkdfExpand(label: String, prk: ByteArray, length: Int) =
        Crypto.hkdf(prk, ByteArray(length), label.toByteArray(), length)

    private fun macPass(mac: String): String {
        val m = hexBytes(mac.replace(":", "").replace("-", ""))
        if (m.size < 6) throw TapoException("MAC de Tapo inválida")
        val ikm = "GqY5o136oa4i6VprTlMW2DpVXxmfW8".toByteArray() + m.copyOfRange(3, 6) + m.copyOfRange(0, 3)
        return Crypto.hex(
            Crypto.hkdf(ikm, "tp-kdf-salt-default-passcode".toByteArray(), "tp-kdf-info-default-passcode".toByteArray(), 32)
        ).uppercase()
    }

    private fun buildCredentials(extra: JSONObject?, user: String, pass: String, macNoColon: String): String {
        if (extra == null || extra.length() == 0) return if (user.isNotEmpty()) "$user/$pass" else pass
        val params = extra.optJSONObject("params") ?: JSONObject()
        return when (extra.optString("type").lowercase()) {
            "password_shadow" -> when (val id = params.optInt("passwd_id", 0)) {
                2 -> Crypto.sha1Hex(pass)
                3 -> {
                    if (user.isEmpty() || macNoColon.length != 12 || !macNoColon.all { it in "0123456789abcdefABCDEF" }) pass
                    else {
                        val mac = macNoColon.chunked(2).joinToString(":").uppercase()
                        Crypto.sha1Hex(Crypto.md5Hex(user) + "_" + mac)
                    }
                }
                1, 5 -> throw TapoException("Tapo usa hash crypt (passwd_id $id), aún no soportado")
                else -> pass
            }
            "password_authkey" -> {
                val tmp = params.optString("authkey_tmpkey")
                val dict = params.optString("authkey_dictionary")
                if (tmp.isEmpty() || dict.isEmpty()) pass
                else (0 until maxOf(tmp.length, pass.length)).joinToString("") { i ->
                    val l = if (i < pass.length) pass[i].code else 0xBB
                    val r = if (i < tmp.length) tmp[i].code else 0xBB
                    dict[(l xor r) % dict.length].toString()
                }
            }
            "password_sha_with_salt" -> {
                val hint = if (params.optInt("sha_name", -1) == 0) "admin" else "user"
                val salt = try { String(unb64(params.optString("sha_salt"))) } catch (e: Exception) { return pass }
                Crypto.sha256Hex(hint + salt + pass)
            }
            else -> if (user.isNotEmpty()) "$user/$pass" else pass
        }
    }
}
