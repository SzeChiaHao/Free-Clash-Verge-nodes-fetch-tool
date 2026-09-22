package com.szech.walls.parse

import com.szech.walls.core.B64
import com.szech.walls.core.Text
import com.szech.walls.model.JsonUtil
import com.szech.walls.model.Node
import org.json.JSONObject
import java.net.InetAddress
import java.util.Locale

/**
 * 把各种分享链接（ss / ssr / vmess / vless / trojan / hy2 / hysteria / tuic）
 * 解析成 Clash 风格的代理字典。逻辑对齐 Windows 版 tools/fetch_nodes.py。
 */
object UriParser {

    val URI_RE = Regex(
        """(?:vmess|vless|trojan|ss|ssr|hy2|hysteria2|hysteria|tuic)://[^\s"'<>\\]+"""
    )
    val HTTP_RE = Regex("""https?://[^\s"'<>()\\]+""")

    fun extractUris(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (m in URI_RE.findAll(text)) seen.add(m.value)
        return seen.toList()
    }

    fun extractHttpLinks(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (m in HTTP_RE.findAll(text)) seen.add(m.value)
        return seen.toList()
    }

    private fun intOf(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: 0
        else -> 0
    }

    private fun isTruthy(v: Any?): Boolean {
        val s = v?.toString()?.lowercase(Locale.ROOT) ?: return false
        return s == "1" || s == "true"
    }

    fun isDecoy(server: String?): Boolean {
        val s = (server ?: "").trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return true
        if (s == "localhost" || s == "127.0.0.1" || s.startsWith("127.")) return true
        val host = s.substringBefore('%')
        return try {
            val addr = InetAddress.getByName(host)
            addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress || addr.isMulticastAddress
        } catch (e: Exception) {
            false
        }
    }

    private fun splitHostPort(hostPortRaw: String): Pair<String, Int>? {
        var hp = hostPortRaw
        val q = hp.indexOf('?')
        if (q >= 0) hp = hp.substring(0, q)
        val h = hp.substringBefore('#').trim()
        if (h.contains(":")) {
            val idx = h.lastIndexOf(':')
            val host = h.substring(0, idx).trim().removePrefix("[").removeSuffix("]")
            val port = h.substring(idx + 1).trim().toIntOrNull()
            if (port != null) return host to port
        }
        return null
    }

    // ---------------------------------------------------------------- vmess

    private fun parseVmess(uri: String): LinkedHashMap<String, Any?>? {
        val raw = uri.removePrefix("vmess://")
        val decoded = B64.decodeToString(raw) ?: raw
        val data = try {
            JSONObject(decoded)
        } catch (e: Exception) {
            return null
        }
        val host = data.optString("add", "")
        val port = intOf(data.opt("port"))
        if (host.isEmpty() || port <= 0) return null
        val name = data.optString("ps", "").ifBlank { "$host:$port" }
        val p = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "vmess",
            "server" to host,
            "port" to port,
            "uuid" to data.optString("id", ""),
            "alterId" to intOf(data.opt("aid")),
            "cipher" to data.optString("scy", "").ifBlank { "auto" },
            "_uri" to uri
        )
        val net = data.optString("net", "tcp").ifBlank { "tcp" }
        if (net != "tcp") p["network"] = net
        if (net == "ws") {
            val opts = linkedMapOf<String, Any?>(
                "path" to data.optString("path", "").ifBlank { "/" }
            )
            val h = data.optString("host", "")
            if (h.isNotEmpty()) opts["headers"] = linkedMapOf<String, Any?>("Host" to h)
            p["ws-opts"] = opts
        } else if (net == "grpc") {
            p["grpc-opts"] = linkedMapOf<String, Any?>(
                "grpc-service-name" to data.optString("path", "")
            )
        }
        val tlsV = data.opt("tls")
        if (tlsV != null && isTruthy(tlsV)) {
            p["tls"] = true
            p["servername"] = data.optString("sni", "").ifBlank {
                data.optString("host", "").ifBlank { host }
            }
        }
        return p
    }

    // ---------------------------------------------------------------- vless

    private val VLESS_RE = Regex("""vless://([^@]+)@([^:/?#]+):(\d+)([^#]*)(?:#(.*))?""")

    private fun parseVless(uri: String): LinkedHashMap<String, Any?>? {
        val m = VLESS_RE.find(uri) ?: return null
        val uuid = m.groupValues[1]
        val host = m.groupValues[2]
        val port = m.groupValues[3].toIntOrNull() ?: return null
        val query = m.groupValues[4]
        val frag = m.groupValues[5]
        val name = Text.unquote(frag).ifBlank { "$host:$port" }
        val p = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "vless",
            "server" to host,
            "port" to port,
            "uuid" to uuid,
            "cipher" to "auto",
            "_uri" to uri
        )
        val net = Text.queryParam(query, "type") ?: "tcp"
        if (net != "tcp" && net != "none") p["network"] = net
        val security = Text.queryParam(query, "security") ?: ""
        if (security == "tls" || security == "reality" || security == "xtls") {
            p["tls"] = true
            p["servername"] = Text.queryParam(query, "sni") ?: host
            if (security == "reality") {
                val pk = Text.queryParam(query, "pbk")
                if (!pk.isNullOrEmpty()) {
                    p["reality-opts"] = linkedMapOf<String, Any?>(
                        "public-key" to pk,
                        "short-id" to (Text.queryParam(query, "sid") ?: "")
                    )
                }
            }
        }
        val flow = Text.queryParam(query, "flow")
        if (!flow.isNullOrEmpty()) p["flow"] = flow
        if (isTruthy(Text.queryParam(query, "allowInsecure")) || isTruthy(Text.queryParam(query, "insecure"))) {
            p["skip-cert-verify"] = true
        }
        val fp = Text.queryParam(query, "fp")
        if (!fp.isNullOrEmpty()) p["client-fingerprint"] = fp
        if (net == "ws") {
            val opts = linkedMapOf<String, Any?>("path" to (Text.queryParam(query, "path") ?: "/"))
            val h = Text.queryParam(query, "host")
            if (!h.isNullOrEmpty()) opts["headers"] = linkedMapOf<String, Any?>("Host" to h)
            p["ws-opts"] = opts
        } else if (net == "grpc") {
            p["grpc-opts"] = linkedMapOf<String, Any?>(
                "grpc-service-name" to (Text.queryParam(query, "serviceName") ?: "")
            )
        }
        return p
    }

    // --------------------------------------------------------------- trojan

    private val TROJAN_RE = Regex("""trojan://([^@]*)@([^:/?#]+):(\d+)([^#]*)(?:#(.*))?""")

    private fun parseTrojan(uri: String): LinkedHashMap<String, Any?>? {
        val m = TROJAN_RE.find(uri) ?: return null
        val password = m.groupValues[1]
        val host = m.groupValues[2]
        val port = m.groupValues[3].toIntOrNull() ?: return null
        val query = m.groupValues[4]
        val frag = m.groupValues[5]
        val p = linkedMapOf<String, Any?>(
            "name" to Text.unquote(frag).ifBlank { "$host:$port" },
            "type" to "trojan",
            "server" to host,
            "port" to port,
            "password" to password,
            "udp" to true,
            "_uri" to uri
        )
        val security = Text.queryParam(query, "security") ?: "tls"
        if (security == "tls" || security == "reality") {
            p["tls"] = true
            p["servername"] = Text.queryParam(query, "sni") ?: host
        }
        if (isTruthy(Text.queryParam(query, "allowInsecure"))) p["skip-cert-verify"] = true
        val fp = Text.queryParam(query, "fp")
        if (!fp.isNullOrEmpty()) p["client-fingerprint"] = fp
        val net = Text.queryParam(query, "type") ?: "tcp"
        if (net == "ws") {
            p["network"] = "ws"
            val opts = linkedMapOf<String, Any?>("path" to (Text.queryParam(query, "path") ?: "/"))
            val h = Text.queryParam(query, "host")
            if (!h.isNullOrEmpty()) opts["headers"] = linkedMapOf<String, Any?>("Host" to h)
            p["ws-opts"] = opts
        } else if (net == "grpc") {
            p["network"] = "grpc"
            p["grpc-opts"] = linkedMapOf<String, Any?>(
                "grpc-service-name" to (Text.queryParam(query, "serviceName") ?: "")
            )
        }
        return p
    }

    // ------------------------------------------------------------------- ss

    private fun parsePlugin(p: LinkedHashMap<String, Any?>, pluginSpec: String) {
        val parts = pluginSpec.split(';')
        if (parts.isEmpty()) return
        val pluginName = parts[0].trim()
        val kv = linkedMapOf<String, String>()
        for (i in 1 until parts.size) {
            val seg = parts[i]
            if (seg.isEmpty()) continue
            val eq = seg.indexOf('=')
            if (eq >= 0) kv[seg.substring(0, eq)] = Text.unquote(seg.substring(eq + 1))
            else kv[seg] = "true"
        }
        when {
            pluginName.startsWith("obfs-local") || pluginName == "simple-obfs" || pluginName == "obfs" -> {
                p["plugin"] = "obfs"
                val opts = linkedMapOf<String, Any?>(
                    "mode" to (kv["obfs"] ?: "http")
                )
                if (!kv["obfs-host"].isNullOrEmpty()) opts["host"] = kv["obfs-host"]
                p["plugin-opts"] = opts
            }
            pluginName.startsWith("v2ray-plugin") -> {
                p["plugin"] = "v2ray-plugin"
                val opts = linkedMapOf<String, Any?>(
                    "mode" to (kv["mode"] ?: "websocket")
                )
                if (!kv["host"].isNullOrEmpty()) opts["host"] = kv["host"]
                if (!kv["path"].isNullOrEmpty()) opts["path"] = kv["path"]
                if (kv.containsKey("tls")) opts["tls"] = true
                p["plugin-opts"] = opts
            }
        }
    }

    private fun parseSs(uri: String): LinkedHashMap<String, Any?>? {
        var raw = uri.removePrefix("ss://")
        var frag = ""
        val hash = raw.indexOf('#')
        if (hash >= 0) {
            frag = raw.substring(hash + 1)
            raw = raw.substring(0, hash)
        }
        var method = ""
        var password = ""
        var host = ""
        var port = 0

        val at = raw.indexOf('@')
        if (at >= 0) {
            val userinfo = raw.substring(0, at)
            val hostPart = raw.substring(at + 1)
            var dec = B64.decodeToString(userinfo)
            if (dec == null || !dec.contains(':')) dec = Text.unquote(userinfo)
            if (dec == null || !dec.contains(':')) return null
            method = dec.substringBefore(':').trim()
            password = dec.substringAfter(':')
            val hp = splitHostPort(hostPart) ?: return null
            host = hp.first
            port = hp.second
        } else {
            val dec = B64.decodeToString(raw) ?: return null
            val parts = dec.split(':')
            if (parts.size < 4) return null
            method = parts[0].trim()
            password = parts[1]
            host = parts[2]
            port = parts[3].trim().toIntOrNull() ?: return null
        }
        if (host.isEmpty() || port <= 0 || method.isEmpty()) return null
        val p = linkedMapOf<String, Any?>(
            "name" to Text.unquote(frag).ifBlank { "$host:$port" },
            "type" to "ss",
            "server" to host,
            "port" to port,
            "cipher" to method,
            "password" to password,
            "udp" to true,
            "_uri" to uri
        )
        val plugin = Text.queryParam(raw.substringAfter('?', ""), "plugin")
        if (!plugin.isNullOrEmpty()) parsePlugin(p, plugin)
        return p
    }

    // ------------------------------------------------------------------ ssr

    private fun parseSsr(uri: String): LinkedHashMap<String, Any?>? {
        val raw = uri.removePrefix("ssr://")
        val dec = B64.decodeToString(raw) ?: return null
        val body = dec.substringBefore('?')
        val query = dec.substringAfter('?', "")
        val parts = body.split(':')
        if (parts.size < 6) return null
        val server = parts[0]
        val port = parts[1].toIntOrNull() ?: return null
        val protocol = parts[2]
        val method = parts[3]
        val obfs = parts[4]
        val passB64 = parts[5]
        val p = linkedMapOf<String, Any?>(
            "name" to (B64.decodeToString(Text.queryParam(query, "remarks")) ?: "$server:$port"),
            "type" to "ssr",
            "server" to server,
            "port" to port,
            "cipher" to method,
            "password" to (B64.decodeToString(passB64) ?: ""),
            "protocol" to protocol,
            "obfs" to obfs,
            "udp" to true,
            "_uri" to uri
        )
        val obfsparam = Text.queryParam(query, "obfsparam")
        if (!obfsparam.isNullOrEmpty()) {
            val v = B64.decodeToString(obfsparam)
            if (!v.isNullOrEmpty()) p["obfs-param"] = v
        }
        val protoparam = Text.queryParam(query, "protoparam")
        if (!protoparam.isNullOrEmpty()) {
            val v = B64.decodeToString(protoparam)
            if (!v.isNullOrEmpty()) p["protocol-param"] = v
        }
        return p
    }

    // ------------------------------------------------------------- hysteria

    private val HY2_RE = Regex("""(?:hysteria2|hy2)://([^@]*)@([^:/?#]+):(\d+)([^#]*)(?:#(.*))?""")

    private fun parseHysteria2(uri: String): LinkedHashMap<String, Any?>? {
        val m = HY2_RE.find(uri) ?: return null
        val auth = m.groupValues[1]
        val host = m.groupValues[2]
        val port = m.groupValues[3].toIntOrNull() ?: return null
        val query = m.groupValues[4]
        val p = linkedMapOf<String, Any?>(
            "name" to Text.unquote(m.groupValues[5]).ifBlank { "$host:$port" },
            "type" to "hysteria2",
            "server" to host,
            "port" to port,
            "password" to auth,
            "_uri" to uri
        )
        Text.queryParam(query, "sni")?.let { if (it.isNotEmpty()) p["sni"] = it }
        if (isTruthy(Text.queryParam(query, "insecure"))) p["skip-cert-verify"] = true
        Text.queryParam(query, "obfs")?.let { if (it.isNotEmpty()) p["obfs"] = it }
        Text.queryParam(query, "obfs-password")?.let { if (it.isNotEmpty()) p["obfs-password"] = it }
        return p
    }

    private val HY1_RE = Regex("""hysteria://([^:/?#]+):(\d+)([^#]*)(?:#(.*))?""")

    private fun parseHysteria(uri: String): LinkedHashMap<String, Any?>? {
        val m = HY1_RE.find(uri) ?: return null
        val host = m.groupValues[1]
        val port = m.groupValues[2].toIntOrNull() ?: return null
        val query = m.groupValues[3]
        val p = linkedMapOf<String, Any?>(
            "name" to Text.unquote(m.groupValues[4]).ifBlank { "$host:$port" },
            "type" to "hysteria",
            "server" to host,
            "port" to port,
            "protocol" to (Text.queryParam(query, "protocol") ?: "udp"),
            "auth-str" to (Text.queryParam(query, "auth") ?: ""),
            "up" to (Text.queryParam(query, "up") ?: "100"),
            "down" to (Text.queryParam(query, "down") ?: "100"),
            "udp" to true,
            "_uri" to uri
        )
        Text.queryParam(query, "sni")?.let { if (it.isNotEmpty()) p["sni"] = it }
        if (isTruthy(Text.queryParam(query, "insecure"))) p["skip-cert-verify"] = true
        return p
    }

    // ----------------------------------------------------------------- tuic

    private val TUIC_RE = Regex("""tuic://([^@]+)@([^:/?#]+):(\d+)([^#]*)(?:#(.*))?""")

    private fun parseTuic(uri: String): LinkedHashMap<String, Any?>? {
        val m = TUIC_RE.find(uri) ?: return null
        val userinfo = m.groupValues[1]
        val host = m.groupValues[2]
        val port = m.groupValues[3].toIntOrNull() ?: return null
        val query = m.groupValues[4]
        val uuid = userinfo.substringBefore(':')
        val password = if (userinfo.contains(':')) userinfo.substringAfter(':') else ""
        val p = linkedMapOf<String, Any?>(
            "name" to Text.unquote(m.groupValues[5]).ifBlank { "$host:$port" },
            "type" to "tuic",
            "server" to host,
            "port" to port,
            "uuid" to uuid,
            "password" to password,
            "_uri" to uri
        )
        Text.queryParam(query, "congestion_control")?.let { if (it.isNotEmpty()) p["congestion-controller"] = it }
        Text.queryParam(query, "udp_relay_mode")?.let { if (it.isNotEmpty()) p["udp-relay-mode"] = it }
        Text.queryParam(query, "alpn")?.let { if (it.isNotEmpty()) p["alpn"] = it.split(',') }
        Text.queryParam(query, "sni")?.let { if (it.isNotEmpty()) p["sni"] = it }
        if (isTruthy(Text.queryParam(query, "allow_insecure"))) p["skip-cert-verify"] = true
        return p
    }

    fun uriToProxy(uri: String): Node? {
        val map = try {
            val scheme = uri.substringBefore(':').lowercase(Locale.ROOT)
            when (scheme) {
                "vmess" -> parseVmess(uri)
                "vless" -> parseVless(uri)
                "trojan" -> parseTrojan(uri)
                "ss" -> parseSs(uri)
                "ssr" -> parseSsr(uri)
                "hy2", "hysteria2" -> parseHysteria2(uri)
                "hysteria" -> parseHysteria(uri)
                "tuic" -> parseTuic(uri)
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return null
        if (map["server"] == null) return null
        return Node(map)
    }
}
