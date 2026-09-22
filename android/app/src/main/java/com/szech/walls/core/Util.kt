package com.szech.walls.core

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object B64 {

    private val DEC = Base64.getDecoder()
    private val ENC = Base64.getEncoder()
    private val URL_DEC = Base64.getUrlDecoder()
    private val URL_ENC = Base64.getUrlEncoder().withoutPadding()

    /** 宽容的 base64 解码：自动补 padding、兼容 url-safe、忽略空白。 */
    fun decodeFlexible(s: String?): ByteArray? {
        if (s == null) return null
        var t = s.trim().replace(Regex("\\s"), "")
        if (t.isEmpty()) return null
        t = t.replace('-', '+').replace('_', '/')
        val pad = (4 - t.length % 4) % 4
        t += "=".repeat(pad)
        return try {
            DEC.decode(t)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun decodeToString(s: String?): String? = decodeFlexible(s)?.toString(Charsets.UTF_8)

    fun encode(s: String): String = ENC.encodeToString(s.toByteArray(Charsets.UTF_8))

    fun encodeUrlNoPad(bytes: ByteArray): String = URL_ENC.encodeToString(bytes)

    fun decodeUrlNoPad(s: String): ByteArray? = try {
        URL_DEC.decode(s.trim().replace("=", ""))
    } catch (e: IllegalArgumentException) {
        null
    }
}

object Text {

    /** 对应 Python urllib.parse.unquote */
    fun unquote(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return try {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3)
                    val v = hex.toIntOrNull(16)
                    if (v != null) {
                        out.write(v)
                        i += 3
                        continue
                    }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            out.toString("UTF-8")
        } catch (e: Exception) {
            s
        }
    }

    /** 对应 Python urllib.parse.parse_qs 里取单值 */
    fun queryParam(query: String?, key: String): String? {
        if (query.isNullOrEmpty()) return null
        val q = query.removePrefix("?")
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            val idx = part.indexOf('=')
            val k = if (idx >= 0) part.substring(0, idx) else part
            if (k == key) {
                val v = if (idx >= 0) part.substring(idx + 1) else ""
                return unquote(v)
            }
        }
        return null
    }
}

object Crypto {

    private val rnd = SecureRandom()

    fun randomBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        rnd.nextBytes(b)
        return b
    }

    fun hkdfSha1(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(20) else salt, "HmacSHA1"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var counter = 1
        var pos = 0
        while (pos < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA1"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    /** AEAD 用的 12 字节 nonce 计数器：按小端整体加一（shadowsocks 规范）。 */
    fun incrementNonce(nonce: ByteArray) {
        for (i in nonce.indices) {
            nonce[i] = (nonce[i] + 1).toByte()
            if (nonce[i] != 0.toByte()) return
        }
    }
}

object Log {

    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val listeners = mutableSetOf<(String) -> Unit>()

    @Synchronized
    fun add(msg: String) {
        val line = msg
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        for (l in listeners.toList()) {
            try {
                l(line)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    @Synchronized
    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
