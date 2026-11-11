package luces

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Primitivas criptográficas sin dependencias externas (válidas en JVM y Android). */
internal object Crypto {
    fun digest(alg: String, data: ByteArray): ByteArray = MessageDigest.getInstance(alg).digest(data)
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun md5Hex(s: String) = hex(digest("MD5", s.toByteArray()))
    fun sha1Hex(s: String) = hex(digest("SHA-1", s.toByteArray()))
    fun sha256Hex(s: String) = hex(digest("SHA-256", s.toByteArray()))

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    /** HKDF (RFC 5869) con SHA-256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    /** PBKDF2-HMAC-SHA256 con la contraseña como bytes. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val out = ByteArray(length)
        var block = 1
        var pos = 0
        while (pos < length) {
            var u = mac.doFinal(salt + byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            block++
        }
        return out
    }

    /** Entero sin signo big-endian. */
    fun bigUnsigned(b: ByteArray) = BigInteger(1, b)

    fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
}

/** AES-CCM (RFC 3610), nonce de 12 bytes, tag de 16, sin datos adicionales. */
internal object Ccm {
    private const val TAG = 16
    private const val L = 3 // 15 - 12

    private fun ecb(key: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }

    private fun ctrBlock(nonce: ByteArray, counter: Int): ByteArray {
        val b = ByteArray(16)
        b[0] = (L - 1).toByte()
        System.arraycopy(nonce, 0, b, 1, 12)
        b[13] = (counter ushr 16).toByte(); b[14] = (counter ushr 8).toByte(); b[15] = counter.toByte()
        return b
    }

    private fun ctr(c: Cipher, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var i = 0
        var counter = 1
        while (i < data.size) {
            val ks = c.doFinal(ctrBlock(nonce, counter++))
            val n = minOf(16, data.size - i)
            for (k in 0 until n) out[i + k] = (data[i + k].toInt() xor ks[k].toInt()).toByte()
            i += n
        }
        return out
    }

    private fun cbcMac(c: Cipher, nonce: ByteArray, msg: ByteArray): ByteArray {
        val b0 = ByteArray(16)
        b0[0] = (8 * ((TAG - 2) / 2) + (L - 1)).toByte()
        System.arraycopy(nonce, 0, b0, 1, 12)
        b0[13] = (msg.size ushr 16).toByte(); b0[14] = (msg.size ushr 8).toByte(); b0[15] = msg.size.toByte()
        var x = c.doFinal(b0)
        var i = 0
        while (i < msg.size) {
            val blk = ByteArray(16)
            System.arraycopy(msg, i, blk, 0, minOf(16, msg.size - i))
            x = c.doFinal(Crypto.xor(x, blk))
            i += 16
        }
        return x
    }

    fun encrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        require(nonce.size == 12)
        val c = ecb(key)
        val tag = Crypto.xor(cbcMac(c, nonce, plain), c.doFinal(ctrBlock(nonce, 0)))
        return ctr(c, nonce, plain) + tag
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        require(nonce.size == 12)
        if (data.size < TAG) throw TapoException("Respuesta cifrada demasiado corta")
        val c = ecb(key)
        val ct = data.copyOfRange(0, data.size - TAG)
        val tag = data.copyOfRange(data.size - TAG, data.size)
        val plain = ctr(c, nonce, ct)
        val expected = Crypto.xor(cbcMac(c, nonce, plain), c.doFinal(ctrBlock(nonce, 0)))
        if (!MessageDigest.isEqual(expected, tag)) throw TapoException("Fallo de autenticación AES-CCM")
        return plain
    }
}

/** Curva NIST P-256 con aritmética afín sobre BigInteger. */
internal object P256 {
    private fun h(s: String) = BigInteger(s, 16)
    val p = h("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF")
    val n = h("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551")
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val a = p - BigInteger.valueOf(3)
    private val b = h("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B")
    val g = Pt(
        h("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"),
        h("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"),
    )

    class Pt(val x: BigInteger, val y: BigInteger)

    private fun inv(v: BigInteger) = v.modInverse(p)

    fun neg(q: Pt) = Pt(q.x, (p - q.y).mod(p))

    private fun dbl(q: Pt): Pt? {
        if (q.y.signum() == 0) return null
        val l = (q.x.pow(2) * BigInteger.valueOf(3) + a) * inv(q.y * TWO) % p
        val x = (l.pow(2) - q.x * TWO).mod(p)
        return Pt(x, (l * (q.x - x) - q.y).mod(p))
    }

    fun add(u: Pt?, v: Pt?): Pt? {
        if (u == null) return v
        if (v == null) return u
        if (u.x == v.x) return if (u.y == v.y) dbl(u) else null
        val l = (v.y - u.y) * inv((v.x - u.x).mod(p)) % p
        val x = (l.pow(2) - u.x - v.x).mod(p)
        return Pt(x, (l * (u.x - x) - u.y).mod(p))
    }

    fun mul(k: BigInteger, q: Pt): Pt? {
        var r: Pt? = null
        var addend: Pt? = q
        var e = k.mod(n)
        while (e.signum() > 0) {
            if (e.testBit(0)) r = add(r, addend)
            addend = addend?.let { dbl(it) }
            e = e.shiftRight(1)
        }
        return r
    }

    private fun onCurve(q: Pt) = (q.y.pow(2) - (q.x.pow(3) + a * q.x + b)).mod(p).signum() == 0

    fun decode(sec1: ByteArray): Pt {
        val pt = when (sec1.firstOrNull()?.toInt()) {
            4 -> {
                if (sec1.size != 65) throw TapoException("Punto EC con longitud inválida")
                Pt(Crypto.bigUnsigned(sec1.copyOfRange(1, 33)), Crypto.bigUnsigned(sec1.copyOfRange(33, 65)))
            }
            2, 3 -> {
                if (sec1.size != 33) throw TapoException("Punto EC con longitud inválida")
                val x = Crypto.bigUnsigned(sec1.copyOfRange(1, 33))
                val rhs = (x.pow(3) + a * x + b).mod(p)
                var y = rhs.modPow((p + BigInteger.ONE).shiftRight(2), p)
                if (y.testBit(0) != (sec1[0].toInt() == 3)) y = p - y
                Pt(x, y)
            }
            else -> throw TapoException("Formato de punto EC desconocido")
        }
        if (!onCurve(pt)) throw TapoException("Punto EC fuera de la curva")
        return pt
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - raw.size) + raw
    }

    fun encode(q: Pt): ByteArray = byteArrayOf(4) + fixed32(q.x) + fixed32(q.y)
}
