package com.szech.walls.export

import com.szech.walls.core.B64
import com.szech.walls.model.Node
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.net.URLEncoder
import java.util.UUID

/**
 * 订阅导出：一次性给出好几种常见客户端都认的格式。
 *
 *  - Clash / mihomo YAML（Clash Verge、Clash Meta for Android、FlClash、NekoBox…）
 *  - Shadowsocks(base64) 订阅：整个 ss:// 列表做 base64 —— shadowsocks-android 的「订阅」直接吃
 *  - SIP008 JSON：Shadowsocks 官方 JSON 订阅格式（shadowsocks-android / SS-libev 都认）
 *  - shadowsocks GUI 配置 JSON（ss-windows / ss-android 的「导入配置」）
 *  - 明文 ss:// 列表（复制粘贴就能导入）
 *  - v2ray 通用订阅（base64 的分享链接列表，v2rayNG / v2rayN 用）
 *  - 全协议分享链接明文（ss / vmess / vless / trojan / hy2…）
 */
object Formats {

    private val yaml: Yaml by lazy {
        val opts = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
            indent = 2
            isPrettyFlow = false
            splitLines = false
            lineBreak = DumperOptions.LineBreak.UNIX
        }
        Yaml(opts)
    }

    fun clean(map: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in map) {
            if (k.startsWith("_")) continue
            if (v == null) continue
            if (v is String && v.trim().lowercase() in listOf("", "none", "null")) continue
            out[k] = v
        }
        return out
    }

    // ------------------------------------------------------------ 分享链接

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun ssUri(n: Node): String? {
        (n.map["_uri"] as? String)?.let { if (it.startsWith("ss://")) return it }
        if (n.type != "ss") return null
        val method = n.cipher
        val password = n.password
        if (method.isEmpty()) return null
        val userinfo = B64.encodeUrlNoPad("$method:$password".toByteArray(Charsets.UTF_8))
        val sb = StringBuilder("ss://$userinfo@${n.server}:${n.port}")
        val plugin = n.map["plugin"] as? String
        val opts = n.map["plugin-opts"] as? Map<*, *>
        if (plugin != null && opts != null) {
            val parts = ArrayList<String>()
            when (plugin) {
                "obfs" -> {
                    parts.add("obfs-local")
                    parts.add("obfs=${opts["mode"] ?: "http"}")
                    (opts["host"] as? String)?.let { parts.add("obfs-host=$it") }
                }
                "v2ray-plugin" -> {
                    parts.add("v2ray-plugin")
                    parts.add("mode=${opts["mode"] ?: "websocket"}")
                    (opts["host"] as? String)?.let { parts.add("host=$it") }
                    (opts["path"] as? String)?.let { parts.add("path=$it") }
                    if (opts["tls"] == true) parts.add("tls")
                }
            }
            if (parts.isNotEmpty()) {
                sb.append("/?plugin=").append(enc(parts.joinToString(";")))
            }
        }
        sb.append('#').append(enc(n.name))
        return sb.toString()
    }

    fun v2rayUri(n: Node): String? {
        (n.map["_uri"] as? String)?.let { if (it.isNotEmpty()) return it }
        return when (n.type) {
            "vmess" -> {
                val o = JSONObject()
                o.put("v", "2")
                o.put("ps", n.name)
                o.put("add", n.server)
                o.put("port", n.port.toString())
                o.put("id", n.map["uuid"]?.toString() ?: "")
                o.put("aid", (n.map["alterId"] as? Number)?.toInt()?.toString() ?: "0")
                o.put("scy", n.cipher.ifEmpty { "auto" })
                o.put("net", (n.map["network"] as? String) ?: "tcp")
                o.put("type", "none")
                o.put("host", hostOf(n))
                o.put("path", pathOf(n))
                o.put("tls", if (n.map["tls"] == true) "tls" else "")
                o.put("sni", (n.map["servername"] as? String) ?: "")
                "vmess://" + java.util.Base64.getEncoder().encodeToString(o.toString().toByteArray())
            }
            "vless" -> {
                val sb = StringBuilder("vless://${n.map["uuid"]}@${n.server}:${n.port}")
                sb.append("?")
                sb.append("type=").append((n.map["network"] as? String) ?: "tcp")
                if (n.map["tls"] == true) {
                    sb.append("&security=").append(if (n.map.containsKey("reality-opts")) "reality" else "tls")
                }
                (n.map["servername"] as? String)?.let { sb.append("&sni=").append(enc(it)) }
                (n.map["flow"] as? String)?.let { sb.append("&flow=").append(enc(it)) }
                val ro = n.map["reality-opts"] as? Map<*, *>
                if (ro != null) {
                    sb.append("&pbk=").append(enc(ro["public-key"]?.toString() ?: ""))
                    sb.append("&sid=").append(enc(ro["short-id"]?.toString() ?: ""))
                }
                if ((n.map["network"] as? String) == "ws") {
                    sb.append("&path=").append(enc(pathOf(n)))
                    val h = hostOf(n)
                    if (h.isNotEmpty()) sb.append("&host=").append(enc(h))
                }
                if (n.map["skip-cert-verify"] == true) sb.append("&allowInsecure=1")
                sb.append('#').append(enc(n.name))
                sb.toString()
            }
            "trojan" -> {
                val sb = StringBuilder("trojan://${n.map["password"]}@${n.server}:${n.port}?")
                sb.append("security=").append(if (n.map["tls"] == true) "tls" else "none")
                (n.map["servername"] as? String)?.let { sb.append("&sni=").append(enc(it)) }
                if ((n.map["network"] as? String) == "ws") {
                    sb.append("&type=ws&path=").append(enc(pathOf(n)))
                    val h = hostOf(n)
                    if (h.isNotEmpty()) sb.append("&host=").append(enc(h))
                }
                if (n.map["skip-cert-verify"] == true) sb.append("&allowInsecure=1")
                sb.append('#').append(enc(n.name))
                sb.toString()
            }
            "hysteria2" -> {
                val sb = StringBuilder("hysteria2://${n.map["password"]}@${n.server}:${n.port}?")
                (n.map["sni"] as? String)?.let { sb.append("sni=").append(enc(it)).append("&") }
                (n.map["obfs"] as? String)?.let { sb.append("obfs=").append(enc(it)).append("&") }
                (n.map["obfs-password"] as? String)?.let { sb.append("obfs-password=").append(enc(it)).append("&") }
                if (n.map["skip-cert-verify"] == true) sb.append("insecure=1&")
                var s = sb.toString().trimEnd('?', '&')
                s += "#" + enc(n.name)
                s
            }
            "tuic" -> {
                val sb = StringBuilder("tuic://${n.map["uuid"]}:${n.map["password"]}@${n.server}:${n.port}?")
                (n.map["sni"] as? String)?.let { sb.append("sni=").append(enc(it)).append("&") }
                if (n.map["skip-cert-verify"] == true) sb.append("allow_insecure=1&")
                var s = sb.toString().trimEnd('?', '&')
                s += "#" + enc(n.name)
                s
            }
            "ss" -> ssUri(n)
            else -> null
        }
    }

    private fun hostOf(n: Node): String {
        val ws = n.map["ws-opts"] as? Map<*, *> ?: return ""
        val headers = ws["headers"] as? Map<*, *> ?: return ""
        return headers["Host"]?.toString() ?: ""
    }

    private fun pathOf(n: Node): String {
        val ws = n.map["ws-opts"] as? Map<*, *> ?: return "/"
        return ws["path"]?.toString() ?: "/"
    }

    // ---------------------------------------------------------------- 输出

    fun ssList(nodes: List<Node>): List<Node> = nodes.filter { it.type == "ss" }

    fun plainSs(nodes: List<Node>): String =
        ssList(nodes).mapNotNull { ssUri(it) }.joinToString("\n")

    fun base64Ss(nodes: List<Node>): String =
        B64.encode(plainSs(nodes)) + "\n"

    fun plainAllLinks(nodes: List<Node>): String =
        nodes.mapNotNull { node -> if (node.type == "ss") ssUri(node) else v2rayUri(node) }
            .joinToString("\n")

    fun base64AllLinks(nodes: List<Node>): String = B64.encode(plainAllLinks(nodes)) + "\n"

    fun sip008(nodes: List<Node>): String {
        val arr = JSONArray()
        for (n in ssList(nodes)) {
            val o = JSONObject()
            o.put("id", UUID.nameUUIDFromBytes("${n.server}:${n.port}:${n.cipher}".toByteArray()).toString())
            o.put("remarks", n.name)
            o.put("server", n.server)
            o.put("server_port", n.port)
            o.put("method", n.cipher)
            o.put("password", n.password)
            val plugin = n.map["plugin"] as? String
            val opts = n.map["plugin-opts"] as? Map<*, *>
            if (plugin != null && opts != null) {
                val parts = ArrayList<String>()
                parts.add(if (plugin == "obfs") "obfs-local" else plugin)
                (opts["mode"] as? String)?.let { parts.add("obfs=$it") }
                (opts["host"] as? String)?.let { parts.add("obfs-host=$it") }
                o.put("plugin", parts.joinToString(";"))
            }
            arr.put(o)
        }
        val root = JSONObject()
        root.put("version", 1)
        root.put("servers", arr)
        return root.toString(2)
    }

    /** 旧版 shadowsocks 客户端的 gui-config.json 数组格式 */
    fun ssGuiConfig(nodes: List<Node>): String {
        val arr = JSONArray()
        for (n in ssList(nodes)) {
            val o = JSONObject()
            o.put("server", n.server)
            o.put("server_port", n.port)
            o.put("password", n.password)
            o.put("method", n.cipher)
            o.put("remarks", n.name)
            o.put("timeout", 300)
            arr.put(o)
        }
        return arr.toString(2)
    }

    fun clashYaml(nodes: List<Node>, groupNote: String = ""): String {
        val proxies = nodes.map { clean(it.map) }
        val names = nodes.map { (it.map["name"] as? String) ?: it.name }
        val root = LinkedHashMap<String, Any?>()
        root["proxies"] = proxies
        root["proxy-groups"] = listOf(
            linkedMapOf<String, Any?>(
                "name" to "🚀 自动选择",
                "type" to "url-test",
                "url" to "http://www.gstatic.com/generate_204",
                "interval" to 300,
                "tolerance" to 50,
                "proxies" to names
            ),
            linkedMapOf<String, Any?>(
                "name" to "🐟 手动选择" + if (groupNote.isEmpty()) "" else groupNote,
                "type" to "select",
                "proxies" to names
            )
        )
        root["rules"] = listOf("MATCH,🚀 自动选择")
        return yaml.dump(root)
    }

    fun reportMd(nodes: List<Node>, title: String): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")
        sb.append("- 节点数: ").append(nodes.size).append("\n\n")
        for ((i, n) in nodes.withIndex()) {
            val sp = if (n.speed >= 0) String.format("%.2f MB/s", n.speed) else "-"
            val d = if (n.realDelay > 0) "${n.realDelay} ms" else if (n.delay > 0) "${n.delay} ms(TCP)" else "-"
            sb.append(i + 1).append(". ").append(n.name)
                .append("  |  ").append(n.type)
                .append("  |  延迟 ").append(d)
                .append("  |  速度 ").append(sp).append("\n")
        }
        return sb.toString()
    }
}
