package com.szech.walls.server

import com.szech.walls.export.Formats
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把仓库里最新的节点渲染成各种订阅格式。 */
object SubContent {

    private fun text(s: String, name: String? = null) =
        SubResponse(s.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8", name)

    private fun json(s: String, name: String? = null) =
        SubResponse(s.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", name)

    private fun yaml(s: String, name: String? = null) =
        SubResponse(s.toByteArray(Charsets.UTF_8), "text/yaml; charset=utf-8", name)

    fun nodes() = Repo.nodes

    fun route(path: String): SubResponse? {
        val nodes = Repo.nodes
        return when (path.lowercase()) {
            "/", "/index.html", "/sub" -> index()
            "/ss", "/ss.txt" -> text(Formats.plainSs(nodes), "ss.txt")
            "/ss64", "/sub-ss", "/ss-sub" -> text(Formats.base64Ss(nodes), "ss-sub.txt")
            "/sip008", "/ss.json", "/sip008.json" -> json(Formats.sip008(nodes), "sip008.json")
            "/ssconf", "/gui-config.json" -> json(Formats.ssGuiConfig(nodes), "gui-config.json")
            "/clash", "/clash.yaml", "/clash.yml", "/sub.yaml" -> yaml(
                Formats.clashYaml(nodes, "(按速度排序)"), "clash.yaml"
            )
            "/all", "/links" -> text(Formats.plainAllLinks(nodes), "all-links.txt")
            "/all64", "/v2ray", "/v2ray64" -> text(Formats.base64AllLinks(nodes), "v2ray-sub.txt")
            "/report", "/report.md" -> text(Formats.reportMd(nodes, "节点测速报告（${SubServer.nowText()}）"), "report.md")
            "/api/status" -> json(statusJson(), "status.json")
            "/favicon.ico" -> SubResponse(ByteArray(0), "image/x-icon", null)
            else -> null
        }
    }

    fun statusJson(): String {
        val s = Repo.status
        val o = JSONObject()
        o.put("last_run_at", s.lastRunAt)
        o.put("last_run_text", if (s.lastRunAt > 0) fmt(s.lastRunAt) else "")
        o.put("running", s.running)
        o.put("candidates", s.candidates)
        o.put("alive", s.alive)
        o.put("measured", s.measured)
        o.put("sources_ok", s.sourcesOk)
        o.put("sources_fail", s.sourcesFail)
        o.put("kept", s.kept)
        o.put("ss_nodes", Repo.nodes.count { it.type == "ss" })
        o.put("message", s.message)
        return o.toString(2)
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))

    private fun index(): SubResponse {
        val s = Repo.status
        val port = Prefs.port
        val base = "http://127.0.0.1:$port"
        val ssCount = Repo.nodes.count { it.type == "ss" }
        val sb = StringBuilder()
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>节点管家 · 本地订阅</title><style>")
        sb.append("body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;margin:16px;background:#f5f6f8;color:#1b1f24}")
        sb.append("h1{font-size:19px} .card{background:#fff;border-radius:12px;padding:12px 14px;margin:10px 0;box-shadow:0 1px 2px rgba(0,0,0,.06)}")
        sb.append("a{color:#1f6feb;text-decoration:none;word-break:break-all} li{margin:9px 0;line-height:1.45}")
        sb.append(".dim{color:#6a737d;font-size:13px} code{background:#eef1f4;padding:1px 5px;border-radius:5px}")
        sb.append("</style></head><body>")
        sb.append("<h1>🧱 节点管家 · 本地订阅服务</h1>")
        sb.append("<div class=\"card\"><div>节点总数：<b>").append(Repo.nodes.size)
            .append("</b>（其中 Shadowsocks ").append(ssCount).append(" 个）</div>")
        sb.append("<div class=\"dim\">上次更新：").append(if (s.lastRunAt > 0) fmt(s.lastRunAt) else "尚未运行")
            .append("　候选 ").append(s.candidates).append(" / 存活 ").append(s.alive)
            .append(" / 实测 ").append(s.measured).append("</div></div>")
        sb.append("<div class=\"card\"><b>把这些地址填进客户端</b><ul>")
        for ((p, desc, _) in SubServer.INDEX_ROUTES) {
            if (p.startsWith("/api")) continue
            sb.append("<li><a href=\"").append(base).append(p).append("\">")
                .append(base).append(p).append("</a><br><span class=\"dim\">").append(desc)
                .append("</span></li>")
        }
        sb.append("</ul></div>")
        sb.append("<div class=\"card\"><b>shadowsocks-android 订阅怎么填</b>")
        sb.append("<div class=\"dim\">打开 Shadowsocks → 右上角三点 → 「订阅」→ 新增 → 地址填 ")
        sb.append("<code>").append(base).append("/sip008</code>（或 <code>").append(base)
            .append("/ss64</code>）→ 确定后下拉刷新即可。</div></div>")
        sb.append("<div class=\"card dim\">本服务只监听 127.0.0.1，不对外开放；手机自身的 App 才能访问。</div>")
        sb.append("</body></html>")
        return SubResponse(sb.toString().toByteArray(Charsets.UTF_8), "text/html; charset=utf-8", null)
    }
}
