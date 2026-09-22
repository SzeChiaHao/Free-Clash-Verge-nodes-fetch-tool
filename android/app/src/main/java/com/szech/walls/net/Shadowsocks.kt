package com.szech.walls.net

import com.szech.walls.core.Crypto
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 纯 Kotlin 的 Shadowsocks 客户端，只服务于「真机实测」：
 * 通过节点访问一个 URL，量出真实延迟与真实下载速度。
 *
 * 支持 AES-128/192/256-GCM、ChaCha20-IETF-Poly1305（AEAD 系）与
 * AES-CFB/CTR、none（流式系）；SS2022（blake3 系）标为不支持，只做 TCP 延迟测试。
 */
object SsCipher {

    enum class Kind { AEAD, STREAM, NONE, UNSUPPORTED }

    class Spec(
        val name: String,
        val kind: Kind,
        val keyLen: Int,
        val saltLen: Int,
        val transformation: String = "",
        val useIvSpec: Boolean = false
    )

    private val LIST = listOf(
        Spec("aes-128-gcm", Kind.AEAD, 16, 16, "AES/GCM/NoPadding"),
        Spec("aes-192-gcm", Kind.AEAD, 24, 24, "AES/GCM/NoPadding"),
        Spec("aes-256-gcm", Kind.AEAD, 32, 32, "AES/GCM/NoPadding"),
        Spec("chacha20-ietf-poly1305", Kind.AEAD, 32, 32, "ChaCha20-Poly1305", true),
        Spec("xchacha20-ietf-poly1305", Kind.UNSUPPORTED, 32, 32),
        Spec("2022-blake3-aes-128-gcm", Kind.UNSUPPORTED, 16, 16),
        Spec("2022-blake3-aes-256-gcm", Kind.UNSUPPORTED, 32, 32),
        Spec("2022-blake3-chacha20-poly1305", Kind.UNSUPPORTED, 32, 32),
        Spec("aes-128-cfb", Kind.STREAM, 16, 16, "AES/CFB/NoPadding"),
        Spec("aes-192-cfb", Kind.STREAM, 24, 24, "AES/CFB/NoPadding"),
        Spec("aes-256-cfb", Kind.STREAM, 32, 32, "AES/CFB/NoPadding"),
        Spec("aes-128-ctr", Kind.STREAM, 16, 16, "AES/CTR/NoPadding"),
        Spec("aes-192-ctr", Kind.STREAM, 24, 24, "AES/CTR/NoPadding"),
        Spec("aes-256-ctr", Kind.STREAM, 32, 32, "AES/CTR/NoPadding"),
        Spec("none", Kind.NONE, 0, 0),
        Spec("plain", Kind.NONE, 0, 0)
    )

    private val TABLE: Map<String, Spec> = LIST.associateBy { it.name }

    fun lookup(name: String): Spec {
        val n = name.trim().lowercase().replace('_', '-')
        TABLE[n]?.let { return it }
        return Spec(n, Kind.UNSUPPORTED, 0, 0)
    }
}

class SsConnection(
    private val server: String,
    private val port: Int,
    private val method: String,
    private val password: String,
    private val connectTimeoutMs: Int = 6000,
    var soTimeoutMs: Int = 6000
) : Closeable {

    private var socket: Socket? = null
    private var rawIn: InputStream? = null
    private var rawOut: OutputStream? = null
    private var spec = SsCipher.lookup(method)

    private var encKey = ByteArray(0)
    private var decKey = ByteArray(0)
    private var encNonce = ByteArray(12)
    private var decNonce = ByteArray(12)

    private var inputStreamRef: InputStream? = null
    private var outputStreamRef: OutputStream? = null
    private var closed = false

    /** 该加密方式是否支持真实隧道测试 */
    fun isSupported(): Boolean {
        if (spec.kind == SsCipher.Kind.UNSUPPORTED) return false
        if (spec.kind == SsCipher.Kind.AEAD && spec.useIvSpec) {
            // ChaCha20-Poly1305 需要 API 28+ 的平台实现
            return try {
                Cipher.getInstance("ChaCha20-Poly1305")
                true
            } catch (e: Exception) {
                false
            }
        }
        return true
    }

    fun unsupportedReason(): String = "加密方式 ${spec.name} 暂不支持实测"

    val remoteAddress: InetAddress
        get() = socket?.inetAddress ?: InetAddress.getByName("127.0.0.1")
    val localAddress: InetAddress
        get() = socket?.localAddress ?: InetAddress.getByName("127.0.0.1")
    val localPort: Int get() = socket?.localPort ?: 0
    val isClosed: Boolean get() = closed || (socket?.isClosed ?: true)

    fun connectSocket() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(server, port), connectTimeoutMs)
        s.soTimeout = soTimeoutMs
        socket = s
        rawIn = s.getInputStream()
        rawOut = s.getOutputStream()
    }

    fun openTunnel(targetHost: String, targetPort: Int) {
        val out = rawOut ?: throw IllegalStateException("socket not connected")
        val inp = rawIn ?: throw IllegalStateException("socket not connected")
        val header = addressHeader(targetHost, targetPort)
        when (spec.kind) {
            SsCipher.Kind.AEAD -> {
                val salt = Crypto.randomBytes(spec.saltLen)
                out.write(salt)
                out.flush()
                encKey = Crypto.hkdfSha1(
                    password.toByteArray(Charsets.UTF_8), salt,
                    "ss-subkey".toByteArray(Charsets.US_ASCII), spec.keyLen
                )
                encNonce = ByteArray(12)
                decNonce = ByteArray(12)
                outputStreamRef = AeadOutputStream()
                outputStreamRef!!.write(header)
                outputStreamRef!!.flush()
                inputStreamRef = AeadInputStream(inp)
            }
            SsCipher.Kind.STREAM -> {
                val iv = Crypto.randomBytes(spec.saltLen)
                out.write(iv)
                out.flush()
                val key = evpBytesToKey(password.toByteArray(Charsets.UTF_8), spec.keyLen)
                val co = Cipher.getInstance(spec.transformation)
                co.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val encOut = javax.crypto.CipherOutputStream(out, co)
                encOut.write(header)
                encOut.flush()
                outputStreamRef = encOut
                inputStreamRef = StreamInputStream(inp, key)
            }
            SsCipher.Kind.NONE -> {
                out.write(header)
                out.flush()
                outputStreamRef = out
                inputStreamRef = inp
            }
            else -> throw UnsupportedOperationException("cipher not supported: ${spec.name}")
        }
    }

    val inputStream: InputStream
        get() = inputStreamRef ?: throw IllegalStateException("tunnel not open")

    val outputStream: OutputStream
        get() = outputStreamRef ?: throw IllegalStateException("tunnel not open")

    fun setReadTimeout(ms: Int) {
        soTimeoutMs = ms
        try {
            socket?.soTimeout = ms
        } catch (e: Exception) {
            // ignore
        }
    }

    // ------------------------------------------------------------- AEAD

    private fun aeadCipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher {
        val c = Cipher.getInstance(spec.transformation)
        if (spec.useIvSpec) {
            c.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        } else {
            c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }
        return c
    }

    private fun writeChunk(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val out = rawOut!!
        var p = off
        var remaining = len
        while (remaining > 0) {
            val n = minOf(0x3FFF, remaining)
            val lenBuf = byteArrayOf(((n ushr 8) and 0xFF).toByte(), (n and 0xFF).toByte())
            var c = aeadCipher(Cipher.ENCRYPT_MODE, encKey, encNonce)
            out.write(c.doFinal(lenBuf))
            Crypto.incrementNonce(encNonce)
            c = aeadCipher(Cipher.ENCRYPT_MODE, encKey, encNonce)
            out.write(c.doFinal(data.copyOfRange(p, p + n)))
            Crypto.incrementNonce(encNonce)
            p += n
            remaining -= n
        }
    }

    private inner class AeadOutputStream : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = writeChunk(b, off, len)
        override fun flush() {
            try {
                rawOut?.flush()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private inner class AeadInputStream(private val src: InputStream) : InputStream() {
        private val buf = ByteArray(0x4000)
        private var bufPos = 0
        private var bufLen = 0
        private var eof = false

        private fun fillOnce(): Boolean {
            if (eof) return false
            try {
                val lenBlock = readExactly(src, 2 + 16)
                var c = aeadCipher(Cipher.DECRYPT_MODE, decKey, decNonce)
                val lenP = c.doFinal(lenBlock)
                Crypto.incrementNonce(decNonce)
                val len = ((lenP[0].toInt() and 0xFF) shl 8) or (lenP[1].toInt() and 0xFF)
                if (len <= 0 || len > 0x3FFF) {
                    eof = true
                    return false
                }
                val payload = readExactly(src, len + 16)
                c = aeadCipher(Cipher.DECRYPT_MODE, decKey, decNonce)
                val plain = c.doFinal(payload)
                Crypto.incrementNonce(decNonce)
                System.arraycopy(plain, 0, buf, 0, plain.size)
                bufPos = 0
                bufLen = plain.size
                return true
            } catch (e: EOFException) {
                eof = true
                return false
            }
        }

        private fun ensureInited() {
            if (decKey.isNotEmpty() || spec.kind != SsCipher.Kind.AEAD) return
            val salt = readExactly(src, spec.saltLen)
            decKey = Crypto.hkdfSha1(
                password.toByteArray(Charsets.UTF_8), salt,
                "ss-subkey".toByteArray(Charsets.US_ASCII), spec.keyLen
            )
        }

        override fun read(): Int {
            if (bufPos >= bufLen) {
                ensureInited()
                if (!fillOnce()) return -1
            }
            return buf[bufPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (bufPos >= bufLen) {
                ensureInited()
                if (!fillOnce()) return -1
            }
            val n = minOf(len, bufLen - bufPos)
            System.arraycopy(buf, bufPos, b, off, n)
            bufPos += n
            return n
        }

        override fun available(): Int = bufLen - bufPos
    }

    /** 流式加密（CFB/CTR）的入方向：延迟到第一次读时才取服务端 IV */
    private inner class StreamInputStream(private val src: InputStream, private val key: ByteArray) :
        InputStream() {
        private var inner: InputStream? = null

        private fun ensure() {
            if (inner != null) return
            val iv = readExactly(src, spec.saltLen)
            val ci = Cipher.getInstance(spec.transformation)
            ci.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            inner = javax.crypto.CipherInputStream(src, ci)
        }

        override fun read(): Int {
            ensure()
            return inner!!.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            ensure()
            var r: Int
            do {
                r = inner!!.read(b, off, len)
            } while (r == 0)
            return r
        }
    }

    // ------------------------------------------------------------ 工具

    private fun addressHeader(host: String, port: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val literal = parseIpLiteral(host)
        if (literal != null && literal.size == 4) {
            out.write(1)
            out.write(literal)
        } else if (literal != null && literal.size == 16) {
            out.write(4)
            out.write(literal)
        } else {
            val bytes = host.toByteArray(Charsets.UTF_8)
            out.write(3)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write((port ushr 8) and 0xFF)
        out.write(port and 0xFF)
        return out.toByteArray()
    }

    private fun parseIpLiteral(host: String): ByteArray? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return null
        val looksV4 = h.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        val looksV6 = h.contains(':')
        if (!looksV4 && !looksV6) return null
        return try {
            InetAddress.getByName(h).address
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            rawIn?.close()
        } catch (e: Exception) {
            // ignore
        }
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {
        fun evpBytesToKey(password: ByteArray, keyLen: Int): ByteArray {
            val md5 = MessageDigest.getInstance("MD5")
            val out = ByteArrayOutputStream()
            var prev = ByteArray(0)
            while (out.size() < keyLen) {
                md5.reset()
                md5.update(prev)
                md5.update(password)
                prev = md5.digest()
                out.write(prev)
            }
            return out.toByteArray().copyOf(keyLen)
        }

        fun readExactly(src: InputStream, n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = src.read(out, got, n - got)
                if (r < 0) throw EOFException("unexpected end of stream")
                got += r
            }
            return out
        }
    }
}
