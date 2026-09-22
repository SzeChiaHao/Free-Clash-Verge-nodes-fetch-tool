package com.szech.walls.net

import com.szech.walls.model.Node
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * 一个「可测速」的代理隧道：连上服务器 -> 完成外层握手(TLS/WS) -> 按协议写好目标地址。
 *
 * 手机上没有 mihomo 内核，所以这里是自己实现的极简客户端。目的只有一个：
 * 借这条隧道去访问一个已知 URL，量出真实的握手延迟和下载速度。
 * 它不做 UDP、不做多路复用、不做 reality，那些测不了的一律标成「不支持实测」。
 */
interface Tunnel : ProxyStream {
    val supported: Boolean
    fun unsupportedReason(): String

    /** 建立到服务器的连接，并完成外层 TLS / WebSocket 握手。 */
    fun connect()

    /** 告诉服务器「我要访问 targetHost:targetPort」。 */
    fun open(targetHost: String, targetPort: Int)
}

// ====================================================================== SS

class SsTunnel(private val conn: SsConnection) : Tunnel {

    override val supported: Boolean get() = conn.isSupported()
    override fun unsupportedReason(): String = conn.unsupportedReason()
    override fun connect() = conn.connectSocket()
    override fun open(targetHost: String, targetPort: Int) = conn.openTunnel(targetHost, targetPort)
    override val input: InputStream get() = conn.inputStream
    override val output: java.io.OutputStream get() = conn.outputStream
    override fun setReadTimeout(ms: Int) = conn.setReadTimeout(ms)
    override fun close() = conn.close()
}

// ================================================================== Trojan

/**
 * trojan 请求：
 *   hex(SHA224(password)) CRLF  0x01  ATYP ADDR PORT  CRLF  payload
 */
class TrojanTunnel(
    private val server: String,
    private val port: Int,
    private val password: String,
    private val network: String,
    private val tls: Boolean,
    private val sni: String,
    private val wsPath: String,
    private val wsHost: String,
    private val wsHeaders: Map<String, String>,
    private val insecure: Boolean,
    private val connectTimeoutMs: Int = 6000,
    private val handshakeTimeoutMs: Int = 8000
) : Tunnel {

    private var rawSocket: Socket? = null
    private var streamRef: ProxyStream? = null
    private var failReason: String? = null

    private val hexPassword: ByteArray? by lazy {
        try {
            hexSha224(password)
        } catch (e: Exception) {
            failReason = "本机不支持 SHA-224，无法实测 trojan"
            null
        }
    }

    init {
        if (network !in SUPPORTED_NETWORKS) failReason = "trojan 的 $network 传输暂不支持实测"
    }

    override val supported: Boolean get() = failReason == null && hexPassword != null
    override fun unsupportedReason(): String = failReason ?: "trojan 无法实测"

    override fun connect() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(server, port), connectTimeoutMs)
        s.soTimeout = handshakeTimeoutMs
        rawSocket = s
        var st: ProxyStream = RawStream(s)
        if (tls) {
            st = TlsStream(st, server, port, sni.ifBlank { server }, insecure, handshakeTimeoutMs)
        }
        if (network == "ws") {
            // ws 的 Host 头：节点没写就用服务器地址（与 mihomo / xray 一致，
            // 不是 SNI —— 用 SNI 会被 Cloudflare 边缘按别的站点路由掉）
            st = WsStream.connect(st, wsHost.ifBlank { server }, wsPath, wsHeaders, handshakeTimeoutMs)
        }
        streamRef = st
    }

    override fun open(targetHost: String, targetPort: Int) {
        val st = streamRef ?: throw IllegalStateException("tunnel not connected")
        val hp = hexPassword ?: throw IllegalStateException("sha224 unavailable")
        st.output.write(buildRequest(hp, targetHost, targetPort))
        st.output.flush()
    }

    override val input: InputStream
        get() = streamRef?.input ?: throw IllegalStateException("tunnel not connected")
    override val output: java.io.OutputStream
        get() = streamRef?.output ?: throw IllegalStateException("tunnel not connected")

    override fun setReadTimeout(ms: Int) {
        streamRef?.setReadTimeout(ms) ?: rawSocket?.let {
            try {
                it.soTimeout = ms
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun close() {
        try {
            streamRef?.close()
        } catch (e: Exception) {
            // ignore
        }
        try {
            rawSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {
        val SUPPORTED_NETWORKS = setOf("tcp", "ws")

        /** password -> hex(SHA224(password)) 的 56 字节 ASCII */
        fun hexSha224(password: String): ByteArray {
            val d = MessageDigest.getInstance("SHA-224").digest(password.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(d.size * 2)
            for (b in d) sb.append("%02x".format(b.toInt() and 0xFF))
            return sb.toString().toByteArray(Charsets.US_ASCII)
        }

        /** hex(SHA224(password)) CRLF 0x01 ATYP ADDR PORT CRLF */
        fun buildRequest(hexPassword: ByteArray, host: String, port: Int): ByteArray {
            val bos = ByteArrayOutputStream()
            bos.write(hexPassword)
            bos.write('\r'.code)
            bos.write('\n'.code)
            bos.write(0x01) // CONNECT
            writeSocksAddr(bos, host, port)
            bos.write('\r'.code)
            bos.write('\n'.code)
            return bos.toByteArray()
        }
    }
}

// =================================================================== VLESS

/**
 * vless 请求头（version 0）：
 *   0x00 | UUID(16) | addonLen(0) | cmd(1=TCP) | port(2,BE) | ATYP | ADDR
 * 服务器回 2 字节（version + addonLen），要先吃掉再读正文。
 */
class VlessTunnel(
    private val server: String,
    private val port: Int,
    uuid: String,
    private val network: String,
    private val tls: Boolean,
    private val sni: String,
    private val wsPath: String,
    private val wsHost: String,
    private val wsHeaders: Map<String, String>,
    private val insecure: Boolean,
    private val connectTimeoutMs: Int = 6000,
    private val handshakeTimeoutMs: Int = 8000
) : Tunnel {

    private var rawSocket: Socket? = null
    private var streamRef: ProxyStream? = null
    private var failReason: String? = null

    private val uid: ByteArray? = parseUuid(uuid)

    init {
        if (uid == null) {
            failReason = "vless 的 UUID 不合法"
        } else if (network !in SUPPORTED_NETWORKS) {
            failReason = "vless 的 $network 传输暂不支持实测"
        }
    }

    override val supported: Boolean get() = failReason == null && uid != null
    override fun unsupportedReason(): String = failReason ?: "vless 无法实测"

    override fun connect() {
        // 注意：这里别写成 Socket().apply { connect(InetSocketAddress(server, port), ...) }，
        // apply 里的裸 port 会解析成 Socket.getPort()（连接前是 0），
        // 结果连到 0 端口，报 BindException: Cannot assign requested address。
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(server, port), connectTimeoutMs)
        s.soTimeout = handshakeTimeoutMs
        rawSocket = s
        var st: ProxyStream = RawStream(s)
        if (tls) {
            st = TlsStream(st, server, port, sni.ifBlank { server }, insecure, handshakeTimeoutMs)
        }
        if (network == "ws") {
            // ws 的 Host 头：节点没写就用服务器地址（与 mihomo / xray 一致，
            // 不是 SNI —— 用 SNI 会被 Cloudflare 边缘按别的站点路由掉）
            st = WsStream.connect(st, wsHost.ifBlank { server }, wsPath, wsHeaders, handshakeTimeoutMs)
        }
        // 服务端的 2 字节响应头要挡在正文前面
        streamRef = VlessResponseStream(st)
    }

    override fun open(targetHost: String, targetPort: Int) {
        val st = streamRef ?: throw IllegalStateException("tunnel not connected")
        val id = uid ?: throw IllegalStateException("bad uuid")
        st.output.write(buildRequest(id, targetHost, targetPort))
        st.output.flush()
    }

    override val input: InputStream
        get() = streamRef?.input ?: throw IllegalStateException("tunnel not connected")
    override val output: java.io.OutputStream
        get() = streamRef?.output ?: throw IllegalStateException("tunnel not connected")

    override fun setReadTimeout(ms: Int) {
        streamRef?.setReadTimeout(ms) ?: rawSocket?.let {
            try {
                it.soTimeout = ms
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun close() {
        try {
            streamRef?.close()
        } catch (e: Exception) {
            // ignore
        }
        try {
            rawSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {
        val SUPPORTED_NETWORKS = setOf("tcp", "ws")

        /** 0x00 | UUID(16) | addonLen(0) | cmd(1) | port(2,BE) | ATYP | ADDR */
        fun buildRequest(uid: ByteArray, host: String, port: Int): ByteArray {
            val bos = ByteArrayOutputStream()
            bos.write(0x00) // version
            bos.write(uid, 0, uid.size)
            bos.write(0x00) // addon length
            bos.write(0x01) // command: TCP
            bos.write((port ushr 8) and 0xFF)
            bos.write(port and 0xFF)
            writeSocksAddr(bos, host, port, withPort = false, atyp = Atyp.VLESS)
            return bos.toByteArray()
        }

        /** "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" -> 16 bytes */
        fun parseUuid(s: String): ByteArray? {
            val t = s.trim().replace("-", "")
            if (t.length != 32) return null
            val out = ByteArray(16)
            for (i in 0 until 16) {
                val v = t.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
                out[i] = v.toByte()
            }
            return out
        }
    }
}

/** 吃掉 vless 服务端的 2 字节响应头（version + addon 长度）后再放行正文。 */
private class VlessResponseStream(private val base: ProxyStream) : ProxyStream {

    private var headerRead = false

    private fun ensureHeader() {
        if (headerRead) return
        headerRead = true
        val b0 = base.input.read()
        val b1 = base.input.read()
        if (b0 < 0 || b1 < 0) throw EOFException("vless 响应头被截断")
        if (b0 != 0x00) throw java.io.IOException("vless 响应版本异常：$b0")
        val addonLen = b1 and 0xFF
        var left = addonLen
        while (left > 0) {
            val n = base.input.read()
            if (n < 0) throw EOFException("vless 响应 addon 被截断")
            left--
        }
    }

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            ensureHeader()
            return base.input.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            ensureHeader()
            return base.input.read(b, off, len)
        }

        override fun available(): Int = if (headerRead) base.input.available() else 0
    }

    override val output: java.io.OutputStream get() = base.output
    override fun setReadTimeout(ms: Int) = base.setReadTimeout(ms)
    override fun close() = base.close()
}

// ===================================================================== 工具

/** SOCKS5 地址格式：ATYP + ADDR (+ PORT)。
 *  注意：VLESS 的 ATYP 编号和 SOCKS5 不一样（域名 2 vs 3，IPv6 3 vs 4），所以要用 Atyp 区分。 */
data class Atyp(val ipv4: Int, val domain: Int, val ipv6: Int) {
    companion object {
        /** trojan 与 SOCKS5 一致：1 / 3 / 4 */
        val SOCKS5 = Atyp(0x01, 0x03, 0x04)

        /** vless 自己的定义：1=IPv4, 2=域名, 3=IPv6 */
        val VLESS = Atyp(0x01, 0x02, 0x03)
    }
}

fun writeSocksAddr(
    out: ByteArrayOutputStream,
    host: String,
    port: Int,
    withPort: Boolean = true,
    atyp: Atyp = Atyp.SOCKS5
) {
    val h = host.trim().removePrefix("[").removeSuffix("]")
    val literal = try {
        if (h.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || h.contains(':')) {
            InetAddress.getByName(h).address
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
    when {
        literal != null && literal.size == 4 -> {
            out.write(atyp.ipv4)
            out.write(literal, 0, 4)
        }
        literal != null && literal.size == 16 -> {
            out.write(atyp.ipv6)
            out.write(literal, 0, 16)
        }
        else -> {
            val b = h.toByteArray(Charsets.UTF_8)
            out.write(atyp.domain)
            out.write(b.size and 0xFF)
            out.write(b, 0, b.size)
        }
    }
    if (withPort) {
        out.write((port ushr 8) and 0xFF)
        out.write(port and 0xFF)
    }
}

// ================================================================== 工厂

object TunnelFactory {

    /**
     * 按节点类型造一条可测隧道；返回 null 表示这个节点没法在本机实测。
     * @param insecure 忽略证书校验（有些免费节点用自签证书）
     */
    fun create(
        n: Node,
        insecure: Boolean = false,
        connectTimeoutMs: Int = 6000,
        handshakeTimeoutMs: Int = 8000
    ): Tunnel? = when (n.type) {
        "ss" -> SsTunnel(
            SsConnection(n.server, n.port, n.cipher, n.password, connectTimeoutMs, handshakeTimeoutMs)
        ).takeIf { it.supported }

        "trojan" -> {
            val network = n.network
            TrojanTunnel(
                server = n.server,
                port = n.port,
                password = n.password,
                network = network,
                tls = n.map["tls"] != false, // trojan 默认就是 TLS
                sni = n.sni,
                wsPath = n.wsPath,
                wsHost = n.wsHost,
                wsHeaders = n.wsHeaders,
                insecure = insecure || n.skipCertVerify,
                connectTimeoutMs = connectTimeoutMs,
                handshakeTimeoutMs = handshakeTimeoutMs
            ).takeIf { it.supported }
        }

        "vless" -> {
            if (n.map.containsKey("reality-opts")) {
                null // reality 需要伪造 TLS 指纹，平台 TLS 做不到
            } else if (n.flow.contains("vision")) {
                null // xtls-rprx-vision 要改 TLS 记录层，做不到
            } else {
                VlessTunnel(
                    server = n.server,
                    port = n.port,
                    uuid = (n.map["uuid"] as? String) ?: "",
                    network = n.network,
                    tls = n.map["tls"] == true,
                    sni = n.sni,
                    wsPath = n.wsPath,
                    wsHost = n.wsHost,
                    wsHeaders = n.wsHeaders,
                    insecure = insecure || n.skipCertVerify,
                    connectTimeoutMs = connectTimeoutMs,
                    handshakeTimeoutMs = handshakeTimeoutMs
                ).takeIf { it.supported }
            }
        }

        else -> null
    }

    /** 说明「为什么这个节点测不了」，写进报告里给用户看。 */
    fun unsupportedReason(n: Node): String = when (n.type) {
        "ss" -> {
            val spec = SsCipher.lookup(n.cipher)
            if (spec.kind == SsCipher.Kind.UNSUPPORTED) "加密方式 ${n.cipher} 暂不支持实测" else "ss 实测不可用"
        }
        "trojan" -> if (n.network !in TrojanTunnel.SUPPORTED_NETWORKS) {
            "trojan 的 ${n.network} 传输暂不支持实测"
        } else {
            "trojan 实测失败"
        }
        "vless" -> when {
            n.map.containsKey("reality-opts") -> "reality 暂不支持实测"
            n.flow.contains("vision") -> "flow=${n.flow} 暂不支持实测"
            n.network !in VlessTunnel.SUPPORTED_NETWORKS -> "vless 的 ${n.network} 传输暂不支持实测"
            else -> "vless 实测失败"
        }
        "vmess" -> "vmess 实测尚未实现"
        "hysteria2", "hysteria" -> "hysteria 走 QUIC，暂不支持实测"
        "tuic" -> "tuic 走 QUIC，暂不支持实测"
        "ssr" -> "ssr 暂不支持实测"
        "socks5" -> "socks5 暂不支持实测"
        "http" -> "http 代理暂不支持实测"
        else -> "${n.type} 暂不支持实测"
    }

    /** 本机技术上能实测的协议 */
    val MEASURABLE_TYPES = setOf("ss", "trojan", "vless")

    fun measurable(n: Node): Boolean = MEASURABLE_TYPES.contains(n.type)
}

/** Node 上几个和传输有关的取值，统一放这里，避免各处各写一遍。 */
val Node.network: String
    get() = ((map["network"] as? String) ?: "tcp").lowercase().ifBlank { "tcp" }

val Node.sni: String
    get() = (map["servername"] as? String)
        ?: (map["sni"] as? String)
        ?: (map["host"] as? String)
        ?: ""

val Node.skipCertVerify: Boolean
    get() = map["skip-cert-verify"] == true

val Node.wsPath: String
    get() {
        val ws = map["ws-opts"] as? Map<*, *> ?: return "/"
        return (ws["path"] as? String) ?: "/"
    }

val Node.wsHost: String
    get() {
        val ws = map["ws-opts"] as? Map<*, *> ?: return ""
        val headers = ws["headers"] as? Map<*, *> ?: return ""
        for ((k, v) in headers) {
            if (k.toString().equals("Host", ignoreCase = true)) return v?.toString() ?: ""
        }
        return ""
    }

val Node.wsHeaders: Map<String, String>
    get() {
        val ws = map["ws-opts"] as? Map<*, *> ?: return emptyMap()
        val headers = ws["headers"] as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in headers) out[k.toString()] = v?.toString() ?: ""
        return out
    }

val Node.flow: String
    get() = (map["flow"] as? String) ?: ""

/** 这个节点的外层链路是否走 TLS（trojan 默认走）。 */
val Node.usesTls: Boolean
    get() = if (type == "trojan") map["tls"] != false else map["tls"] == true
