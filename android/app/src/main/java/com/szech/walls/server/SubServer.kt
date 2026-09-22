package com.szech.walls.server

import android.util.Log
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class SubResponse(
    val body: ByteArray,
    val contentType: String = "text/plain; charset=utf-8",
    val filename: String? = null
)

/**
 * 一个只监听 127.0.0.1 的迷你 HTTP 服务器：
 * 手机上的 Shadowsocks / Clash 客户端把订阅地址填成 http://127.0.0.1:端口/xxx 就能自动拉取。
 */
class SubServer(private val port: Int) {

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun urlOf(path: String): String = "http://127.0.0.1:$port$path"

    fun start(handler: (String, Map<String, String>) -> SubResponse?): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            running = true
            thread = Thread {
                while (running) {
                    val sock = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    pool.submit { handle(sock, handler) }
                }
            }.apply {
                name = "sub-server"
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            Log.w("Walls", "start sub server failed", e)
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
        thread = null
    }

    private fun handle(sock: Socket, handler: (String, Map<String, String>) -> SubResponse?) {
        try {
            sock.soTimeout = 8000
            val inp = sock.getInputStream()
            val first = readLine(inp) ?: return
            val parts = first.split(' ')
            if (parts.size < 2) return
            val rawPath = parts[1]
            // 读完请求头
            while (true) {
                val l = readLine(inp) ?: break
                if (l.isEmpty()) break
            }
            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")
            val qmap = LinkedHashMap<String, String>()
            if (query.isNotEmpty()) {
                for (kv in query.split('&')) {
                    val i = kv.indexOf('=')
                    if (i > 0) {
                        qmap[URLDecoder.decode(kv.substring(0, i), "UTF-8")] =
                            URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                    }
                }
            }
            val resp = handler(path, qmap)
            val out = BufferedOutputStream(sock.getOutputStream())
            if (resp == null) {
                val body = "404 not found\n可用地址见 http://127.0.0.1:$port/\n".toByteArray()
                writeHead(out, 404, "text/plain; charset=utf-8", body.size, null)
                out.write(body)
            } else {
                writeHead(out, 200, resp.contentType, resp.body.size, resp.filename)
                out.write(resp.body)
            }
            out.flush()
        } catch (e: Exception) {
            // ignore
        } finally {
            try {
                sock.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun writeHead(
        out: BufferedOutputStream,
        code: Int,
        contentType: String,
        length: Int,
        filename: String?
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(if (code == 200) " OK\r\n" else " Not Found\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        if (filename != null) sb.append("Content-Disposition: inline; filename=\"").append(filename).append("\"\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun readLine(inp: InputStream): String? {
        val bos = java.io.ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (bos.size() == 0) null else bos.toString("ISO-8859-1")
            if (b == '\n'.code) return bos.toString("ISO-8859-1").trimEnd('\r')
            bos.write(b)
        }
    }

    companion object {
        val INDEX_ROUTES = listOf(
            Triple("/ss", "Shadowsocks 明文列表（ss:// 一行一个，复制粘贴即可导入）", "ss-明文.txt"),
            Triple("/ss64", "Shadowsocks 订阅（base64 编码，shadowsocks-android 的『订阅』填这个）", "ss-订阅.txt"),
            Triple("/sip008", "SIP008 JSON 订阅（Shadowsocks 官方 JSON 订阅格式）", "sip008.json"),
            Triple("/ssconf", "shadowsocks 配置 JSON（旧版客户端『导入配置』）", "gui-config.json"),
            Triple("/clash", "Clash / mihomo YAML（Clash Verge、Clash Meta for Android、FlClash、NekoBox）", "clash.yaml"),
            Triple("/all", "全协议分享链接明文（ss / vmess / vless / trojan / hy2）", "all-明文.txt"),
            Triple("/all64", "v2ray 通用订阅（分享链接 base64，v2rayNG / v2rayN）", "v2ray-订阅.txt"),
            Triple("/report", "测速报告（Markdown）", "report.md"),
            Triple("/api/status", "状态 JSON（给脚本用）", "status.json")
        )

        fun nowText(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }
}
