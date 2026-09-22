package com.szech.walls.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 借一条已经建好的代理隧道，发一个真正的 HTTP(S) 请求。
 *
 * 关键点：如果是 HTTPS，就在隧道之上再套一层平台 TLS —— 用 StreamSocket 把隧道
 * 伪装成 Socket 喂给 SSLSocketFactory，这样测出来的延迟/速度和 Clash 里跑的一致。
 */
object TunnelHttp {

    class Resp(
        val status: Int,
        val ttfbMs: Long,
        val elapsedMs: Long,
        val bytes: Long,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null && status in 200..399
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

    /**
     * @param maxBytes 最多读取多少响应体（0 表示只读响应头）
     */
    fun get(
        tunnel: Tunnel,
        tls: Boolean,
        host: String,
        port: Int,
        path: String,
        maxBytes: Int,
        handshakeTimeoutMs: Int,
        bodyTimeoutMs: Int
    ): Resp {
        val t0 = System.currentTimeMillis()
        var sock: Socket = StreamSocket(tunnel, host, port)
        try {
            if (tls) {
                tunnel.setReadTimeout(handshakeTimeoutMs)
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(sock, host, port, false) as SSLSocket
                ssl.soTimeout = handshakeTimeoutMs
                ssl.startHandshake()
                sock = ssl
            }
            val out = sock.getOutputStream()
            val req = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: ").append(Fetcher.UA).append("\r\n")
                append("Accept: */*\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(req.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val inp = BufferedInputStream(sock.getInputStream(), 65536)
            val statusLine = readLine(inp)
                ?: return Resp(0, -1, System.currentTimeMillis() - t0, 0, "无响应")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            var contentLength = -1L
            var chunked = false
            while (true) {
                val line = readLine(inp) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val k = line.substring(0, idx).trim().lowercase()
                val v = line.substring(idx + 1).trim()
                when (k) {
                    "content-length" -> contentLength = v.toLongOrNull() ?: -1L
                    "transfer-encoding" -> if (v.lowercase().contains("chunked")) chunked = true
                }
            }
            val ttfb = System.currentTimeMillis() - t0

            var total = 0L
            if (maxBytes > 0) {
                tunnel.setReadTimeout(bodyTimeoutMs)
                val buf = ByteArray(65536)
                if (chunked) {
                    while (total < maxBytes) {
                        val sizeLine = readLine(inp) ?: break
                        val size = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: break
                        if (size == 0) break
                        var left = size
                        while (left > 0 && total < maxBytes) {
                            val n = inp.read(buf, 0, minOf(buf.size, left))
                            if (n < 0) break
                            left -= n
                            total += n
                        }
                        readLine(inp)
                    }
                } else {
                    val cap = if (contentLength > 0) minOf(maxBytes.toLong(), contentLength) else maxBytes.toLong()
                    while (total < cap) {
                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), cap - total).toInt())
                        if (n < 0) break
                        total += n
                    }
                }
            }
            return Resp(status, ttfb, System.currentTimeMillis() - t0, total, null)
        } catch (e: SocketTimeoutException) {
            val ttfb = System.currentTimeMillis() - t0
            return Resp(0, ttfb, ttfb, 0, "超时")
        } catch (e: IOException) {
            return Resp(0, -1, System.currentTimeMillis() - t0, 0, e.javaClass.simpleName)
        } catch (e: Exception) {
            return Resp(0, -1, System.currentTimeMillis() - t0, 0, e.javaClass.simpleName)
        } finally {
            try {
                sock.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
