package com.szech.walls.net

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 一条字节流。最底下是 TCP socket，往上可以套 TLS、再套 WebSocket，
 * 每一层都还是 ProxyStream，于是「协议头写在谁身上」就变得很清楚：
 *
 *   TCP -> [TLS] -> [WebSocket] -> 代理协议头(vless / trojan / vmess) -> 真实数据
 */
interface ProxyStream : Closeable {
    val input: InputStream
    val output: OutputStream
    fun setReadTimeout(ms: Int)
}

class RawStream(private val socket: Socket) : ProxyStream {
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()

    override fun setReadTimeout(ms: Int) {
        try {
            socket.soTimeout = ms
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            // ignore
        }
    }
}

/**
 * 把一个 ProxyStream 伪装成 Socket，交给平台自带的 SSLSocketFactory 做真正的 TLS。
 * 既用于「连代理服务器的外层 TLS」，也用于「客户端最终要访问的 HTTPS 目标」。
 */
class StreamSocket(
    private val stream: ProxyStream,
    private val host: String,
    private val port: Int
) : Socket() {

    override fun getInputStream(): InputStream = stream.input
    override fun getOutputStream(): OutputStream = stream.output
    override fun isConnected(): Boolean = true
    override fun isClosed(): Boolean = false
    override fun close() = stream.close()
    override fun getInetAddress(): InetAddress = ipOrLoopback(host)
    override fun getPort(): Int = port
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getLocalPort(): Int = 0
    override fun getSoTimeout(): Int = 0
    override fun setSoTimeout(timeout: Int) = stream.setReadTimeout(timeout)
    override fun getTcpNoDelay(): Boolean = true
    override fun setTcpNoDelay(on: Boolean) {}
    override fun isInputShutdown(): Boolean = false
    override fun isOutputShutdown(): Boolean = false
    override fun shutdownInput() {}
    override fun shutdownOutput() {}
    override fun getChannel(): SocketChannel? = null
    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(host, port)
    override fun getLocalSocketAddress(): SocketAddress = InetSocketAddress("127.0.0.1", 0)
    override fun setKeepAlive(on: Boolean) {}
    override fun getKeepAlive(): Boolean = false
    override fun setSoLinger(on: Boolean, linger: Int) {}
    override fun getSoLinger(): Int = -1
    override fun toString(): String = "StreamSocket($host:$port)"

    private fun ipOrLoopback(h: String): InetAddress = try {
        // 只对 IP 字面量解析，域名一律返回回环，避免在握手路径上做 DNS
        if (h.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || h.contains(':')) {
            InetAddress.getByName(h.trim().removePrefix("[").removeSuffix("]"))
        } else {
            InetAddress.getLoopbackAddress()
        }
    } catch (e: Exception) {
        InetAddress.getLoopbackAddress()
    }
}

/** 在 ProxyStream 上做 TLS 握手，得到一个能读能写的加密流。 */
class TlsStream(
    base: ProxyStream,
    host: String,
    port: Int,
    sni: String,
    insecure: Boolean,
    handshakeTimeoutMs: Int,
    alpn: List<String> = DEFAULT_ALPN
) : ProxyStream {

    private val ssl: SSLSocket

    init {
        val factory = if (insecure) insecureFactory() else defaultFactory()
        val raw = factory.createSocket(StreamSocket(base, host, port), sni.ifBlank { host }, port, true)
        ssl = raw as SSLSocket
        ssl.soTimeout = handshakeTimeoutMs
        // ALPN：API 29 以下没有这个方法，会抛 NoSuchMethodError（属于 Error），下面一并接住
        if (alpn.isNotEmpty()) {
            try {
                val p = ssl.sslParameters
                p.applicationProtocols = alpn.toTypedArray()
                ssl.sslParameters = p
            } catch (e: Throwable) {
                // 设不上就算了，服务端一般不强求 ALPN
            }
        }
        ssl.startHandshake()
    }

    override val input: InputStream get() = ssl.inputStream
    override val output: OutputStream get() = ssl.outputStream

    override fun setReadTimeout(ms: Int) {
        try {
            ssl.soTimeout = ms
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun close() {
        try {
            ssl.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {
        /** 只测 HTTP/1.1，所以固定请求 http/1.1，避免服务端协商出 h2 后我们又说不上 h2。 */
        val DEFAULT_ALPN = listOf("http/1.1")

        private fun defaultFactory(): SSLSocketFactory =
            SSLSocketFactory.getDefault() as SSLSocketFactory

        private fun insecureFactory(): SSLSocketFactory {
            val tm = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, tm, SecureRandom())
            return ctx.socketFactory
        }
    }
}

/**
 * RFC 6455 客户端。只为「借 WebSocket 传代理协议」服务：
 * 握手 + 分片收发 + ping/pong，够用就行，不做扩展协商。
 */
class WsStream private constructor(
    private val base: ProxyStream,
    private val path: String
) : ProxyStream {

    override val input: InputStream = WsInput()
    override val output: OutputStream = WsOutput()

    override fun setReadTimeout(ms: Int) = base.setReadTimeout(ms)
    override fun close() = base.close()

    // ------------------------------------------------------------------ 写

    private inner class WsOutput : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            writeFrame(OP_BINARY, b, off, len)
            base.output.flush()
        }

        override fun flush() {
            try {
                base.output.flush()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun writeFrame(opcode: Int, data: ByteArray, off: Int, len: Int) {
        val head = ByteArrayOutputStream()
        head.write(0x80 or opcode)
        val mask = ByteArray(4)
        RANDOM.nextBytes(mask)
        when {
            len < 126 -> head.write(0x80 or len)
            len < 65536 -> {
                head.write(0x80 or 126)
                head.write((len ushr 8) and 0xFF)
                head.write(len and 0xFF)
            }
            else -> {
                head.write(0x80 or 127)
                for (i in 7 downTo 0) head.write(((len.toLong() ushr (8 * i)) and 0xFF).toInt())
            }
        }
        head.write(mask, 0, 4)
        base.output.write(head.toByteArray())
        val masked = ByteArray(len)
        for (i in 0 until len) {
            masked[i] = (data[off + i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        base.output.write(masked)
    }

    // ------------------------------------------------------------------ 读

    private inner class WsInput : InputStream() {
        private var buf = ByteArray(0)
        private var pos = 0

        private fun readByteOrEof(): Int = base.input.read()

        private fun readFully(b: ByteArray, off: Int, len: Int) {
            var got = 0
            while (got < len) {
                val n = base.input.read(b, off + got, len - got)
                if (n < 0) throw EOFException("ws 帧被截断")
                got += n
            }
        }

        private fun skipBytes(n: Long) {
            var left = n
            val tmp = ByteArray(4096)
            while (left > 0) {
                val n2 = base.input.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
                if (n2 < 0) throw EOFException("ws 帧被截断")
                left -= n2
            }
        }

        /** 取下一个数据帧；控制帧就地处理掉。返回 false 表示流结束。 */
        private fun nextFrame(): Boolean {
            while (true) {
                val b0 = readByteOrEof()
                if (b0 < 0) return false
                val opcode = b0 and 0x0F
                val b1 = readByteOrEof()
                if (b1 < 0) return false
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    val a = readByteOrEof()
                    val b = readByteOrEof()
                    if (a < 0 || b < 0) return false
                    len = (((a shl 8) or b) and 0xFFFF).toLong()
                } else if (len == 127L) {
                    len = 0
                    for (i in 0 until 8) {
                        val x = readByteOrEof()
                        if (x < 0) return false
                        len = (len shl 8) or x.toLong()
                    }
                }
                val maskKey = if (masked) {
                    ByteArray(4).also { readFully(it, 0, 4) }
                } else {
                    null
                }
                when (opcode) {
                    OP_CLOSE -> return false
                    OP_PING -> {
                        val payload = ByteArray(len.toInt().coerceAtLeast(0))
                        if (payload.isNotEmpty()) readFully(payload, 0, payload.size)
                        try {
                            writeFrame(OP_PONG, payload, 0, payload.size)
                            base.output.flush()
                        } catch (e: Exception) {
                            return false
                        }
                    }
                    OP_PONG -> skipBytes(len)
                    else -> {
                        if (len > MAX_FRAME) return false
                        val data = ByteArray(len.toInt())
                        if (data.isNotEmpty()) readFully(data, 0, data.size)
                        if (maskKey != null) {
                            for (i in data.indices) {
                                data[i] = (data[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                            }
                        }
                        buf = data
                        pos = 0
                        return true
                    }
                }
            }
        }

        override fun read(): Int {
            if (pos >= buf.size && !nextFrame()) return -1
            return buf[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= buf.size && !nextFrame()) return -1
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = buf.size - pos
    }

    companion object {
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val OP_BINARY = 0x2
        private const val MAX_FRAME = 8 * 1024 * 1024

        private val RANDOM = SecureRandom()

        /**
         * 在 base 上完成 WebSocket 握手。
         * @param hostHeader Host 头（v2ray 的 ws-opts.headers.Host，缺省用 SNI / 服务器地址）
         */
        fun connect(
            base: ProxyStream,
            hostHeader: String,
            path: String,
            extraHeaders: Map<String, String>,
            timeoutMs: Int
        ): WsStream {
            base.setReadTimeout(timeoutMs)
            val key = ByteArray(16).also { RANDOM.nextBytes(it) }
            val keyB64 = Base64.getEncoder().encodeToString(key)
            val p = if (path.startsWith("/")) path else "/$path"
            // 默认 UA 用 Go 的默认值：mihomo / xray 客户端就是这么发的，
            // 服务端（尤其是 Cloudflare Worker 版）也按这个认；节点自带 UA 时以节点为准。
            var ua = "Go-http-client/1.1"
            for ((k, v) in extraHeaders) {
                if (k.equals("User-Agent", ignoreCase = true)) ua = v
            }
            val sb = StringBuilder()
            sb.append("GET ").append(p).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(hostHeader).append("\r\n")
            sb.append("User-Agent: ").append(ua).append("\r\n")
            sb.append("Upgrade: websocket\r\n")
            sb.append("Connection: Upgrade\r\n")
            sb.append("Sec-WebSocket-Key: ").append(keyB64).append("\r\n")
            sb.append("Sec-WebSocket-Version: 13\r\n")
            for ((k, v) in extraHeaders) {
                if (k.equals("Host", ignoreCase = true)) continue
                if (k.equals("User-Agent", ignoreCase = true)) continue
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            sb.append("\r\n")
            base.output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            base.output.flush()

            // 逐字节读响应头，避免越界读进第一个 ws 帧
            val status = readLine(base.input) ?: throw IOException("ws 握手无响应")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            var accept = ""
            while (true) {
                val line = readLine(base.input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                if (line.substring(0, idx).trim().equals("Sec-WebSocket-Accept", ignoreCase = true)) {
                    accept = line.substring(idx + 1).trim()
                }
            }
            if (code != 101) throw IOException("ws 握手失败：HTTP $code")
            if (accept.isNotEmpty() && accept != expectedAccept(keyB64)) {
                throw IOException("ws 握手失败：Accept 校验不过")
            }
            return WsStream(base, p)
        }

        private fun expectedAccept(key: String): String {
            val sha = java.security.MessageDigest.getInstance("SHA-1")
            val raw = sha.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
            return Base64.getEncoder().encodeToString(raw)
        }

        private fun readLine(inp: InputStream): String? {
            val bos = ByteArrayOutputStream()
            while (true) {
                val b = inp.read()
                if (b < 0) return if (bos.size() == 0) null else bos.toString("ISO-8859-1")
                if (b == '\n'.code) return bos.toString("ISO-8859-1").trimEnd('\r')
                bos.write(b)
                if (bos.size() > 8192) return bos.toString("ISO-8859-1")
            }
        }
    }
}
