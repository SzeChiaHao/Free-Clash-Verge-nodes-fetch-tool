package com.szech.walls.net

import com.szech.walls.core.B64
import com.szech.walls.core.Log
import com.szech.walls.model.Node
import com.szech.walls.parse.UriParser
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * 订阅抓取器：自带多镜像兜底（GitHub raw -> jsDelivr / gh-proxy 等），
 * 并对下载内容做「Clash YAML / 明文链接 / base64 链接列表」三种格式的识别。
 */
class Fetcher(
    private val timeoutMs: Int = 12000,
    private val mirrorFirst: Boolean = true
) {

    companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /** 把一个订阅链接展开成候选（原链 + 各种镜像），按可用性排序。 */
        fun mirrors(url: String, mirrorFirst: Boolean = true): List<String> {
            val out = LinkedHashSet<String>()
            val m = Regex("""^https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$""").find(url)
            val proxied = listOf(
                "https://gh-proxy.com/$url",
                "https://ghproxy.net/$url",
                "https://ghfast.top/$url"
            )
            if (m != null) {
                val user = m.groupValues[1]
                val repo = m.groupValues[2]
                val branch = m.groupValues[3]
                val path = m.groupValues[4]
                val jsd = listOf(
                    "https://fastly.jsdelivr.net/gh/$user/$repo@$branch/$path",
                    "https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path",
                    "https://gf.zukizuki.org/gh/$user/$repo@$branch/$path",
                    "https://raw.gitmirror.com/$user/$repo/$branch/$path"
                )
                if (mirrorFirst) {
                    out.addAll(jsd)
                    out.addAll(proxied)
                    out.add(url)
                } else {
                    out.add(url)
                    out.addAll(jsd)
                    out.addAll(proxied)
                }
            } else {
                out.add(url)
                if (url.contains("github.com/") && !url.contains("raw.githubusercontent.com")) {
                    out.addAll(proxied)
                }
            }
            return out.toList()
        }
    }

    private fun readAll(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code !in 200..299) return ""
        val raw = conn.inputStream
        val stream = when (val enc = conn.contentEncoding?.lowercase(Locale.ROOT)) {
            "gzip" -> GZIPInputStream(raw)
            else -> raw
        }
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(32768)
        stream.use { s ->
            var n: Int
            var total = 0
            while (true) {
                n = s.read(buf)
                if (n <= 0) break
                total += n
                if (total > 12 * 1024 * 1024) break
                bos.write(buf, 0, n)
            }
        }
        return bos.toString("UTF-8")
    }

    /** 直接抓取一个 URL（不展开镜像）。失败返回 null。 */
    fun getOnce(url: String, timeoutOverride: Int = timeoutMs): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutOverride
                readTimeout = timeoutOverride
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val text = readAll(conn)
            text.ifEmpty { null }
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /** 带镜像兜底抓取。 */
    fun get(url: String): String? {
        for (cand in mirrors(url, mirrorFirst)) {
            val t = getOnce(cand)
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    fun getWithTimeout(url: String, ms: Int): String? {
        for (cand in mirrors(url, mirrorFirst)) {
            val t = getOnce(cand, ms)
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    // ------------------------------------------------------------ 内容解析

    private fun yamlProxies(text: String): List<Node>? {
        if (!text.contains("proxies")) return null
        return try {
            val opts = LoaderOptions()
            opts.maxAliasesForCollections = 100
            opts.isAllowDuplicateKeys = true
            val yaml = Yaml(SafeConstructor(opts))
            val data = yaml.load<Any?>(text) ?: return null
            val root = data as? Map<*, *> ?: return null
            val list = root["proxies"] as? List<*> ?: return null
            val out = ArrayList<Node>()
            for (item in list) {
                val mm = item as? Map<*, *> ?: continue
                val server = mm["server"]?.toString()
                val name = mm["name"]?.toString()
                if (server.isNullOrBlank() || name.isNullOrBlank()) continue
                val lm = LinkedHashMap<String, Any?>()
                for ((k, v) in mm) lm[k.toString()] = normalize(v)
                out.add(Node(lm))
            }
            out.ifEmpty { null }
        } catch (e: Throwable) {
            null
        }
    }

    private fun normalize(v: Any?): Any? = when (v) {
        is Map<*, *> -> {
            val m = LinkedHashMap<String, Any?>()
            for ((k, vv) in v) m[k.toString()] = normalize(vv)
            m
        }
        is List<*> -> v.map { normalize(it) }
        else -> v
    }

    /** 返回 (原始 uri 列表, 已解析好的节点列表) */
    fun parseContent(text: String): Pair<List<String>, List<Node>> {
        yamlProxies(text)?.let { return emptyList<String>() to it }

        val uris = UriParser.extractUris(text)
        if (uris.isNotEmpty()) return uris to emptyList()

        // 可能是整段 base64
        val dec = B64.decodeToString(text.trim().take(200000))
        if (dec != null) {
            val u2 = UriParser.extractUris(dec)
            if (u2.isNotEmpty()) return u2 to emptyList()
        }
        return emptyList<String>() to emptyList()
    }

    /** 订阅列表页：把页面里出现的订阅链接逐个抓下来 */
    fun expandListPage(text: String, limit: Int = 20): Pair<List<String>, List<Node>> {
        val uris = ArrayList<String>()
        val nodes = ArrayList<Node>()
        val links = UriParser.extractHttpLinks(text)
            .map { it.trimEnd('.', ',', ';', ')', ']', '}') }
            .filter {
                val low = it.lowercase(Locale.ROOT)
                !low.contains(".png") && !low.contains(".jpg") && !low.contains(".svg") &&
                    !low.contains(".gif") && !low.contains(".ico") && !low.contains(".css") &&
                    !low.contains(".js") && !low.contains("github.com/") &&
                    !low.contains("shields.io")
            }
            .distinct()
        for (link in links.take(limit)) {
            val t = get(link) ?: continue
            val (u, p) = parseContent(t)
            uris.addAll(u)
            nodes.addAll(p)
        }
        return uris to nodes
    }
}
