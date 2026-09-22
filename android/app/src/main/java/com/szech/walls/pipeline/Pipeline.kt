package com.szech.walls.pipeline

import com.szech.walls.core.Log
import com.szech.walls.model.Node
import com.szech.walls.net.Fetcher
import com.szech.walls.net.SsConnection
import com.szech.walls.net.TunnelHttp
import com.szech.walls.parse.UriParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class PipelineConfig(
    var sources: List<String> = DefaultSources.LIST,
    var maxNodes: Int = 600,
    var tcpTimeoutMs: Int = 2500,
    var maxDelayMs: Int = 3000,
    var speedTop: Int = 40,
    var speedMaxDelayMs: Int = 2000,
    var minSpeedMbps: Double = 0.2,
    var keepTop: Int = 40,
    var speedBytes: Int = 512 * 1024,
    var ssOnly: Boolean = true,
    var labelSpeed: Boolean = true,
    var quick: Boolean = false
)

class PipelineResult(
    val nodes: List<Node>,
    val candidates: Int,
    val alive: Int,
    val measured: Int,
    val sourcesOk: Int,
    val sourcesFail: Int,
    val elapsedMs: Long
)

/** 一个被测目标 */
data class Target(val host: String, val port: Int, val path: String, val tls: Boolean)

object Pipeline {

    private val LATENCY_TARGETS = listOf(
        Target("www.gstatic.com", 443, "/generate_204", true),
        Target("cp.cloudflare.com", 443, "/generate_204", true),
        Target("connectivitycheck.gstatic.com", 80, "/generate_204", false)
    )

    private val SPEED_TARGETS = listOf(
        Target("speed.cloudflare.com", 443, "/__down?bytes=%%N%%", true),
        Target("speed.cloudflare.com", 80, "/__down?bytes=%%N%%", false),
        Target("cachefly.cachefly.net", 80, "/1mb.test", false)
    )

    fun tcpDelay(host: String, port: Int, timeoutMs: Int): Int {
        val t0 = System.currentTimeMillis()
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.close()
            (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            -1
        }
    }

    // ------------------------------------------------------------ 阶段 1

    private class FetchOutcome(
        val uris: List<String>,
        val nodes: List<Node>,
        val ok: Boolean
    )

    private suspend fun fetchOne(src: String, fetcher: Fetcher, listLimit: Int): FetchOutcome {
        val url = src.substringBefore('#').trim()
        val isList = src.contains("#type=list")
        val text = fetcher.get(url) ?: return FetchOutcome(emptyList(), emptyList(), false)
        val (uris, nodes) = if (isList) {
            fetcher.expandListPage(text, listLimit)
        } else {
            fetcher.parseContent(text)
        }
        return FetchOutcome(uris, nodes, true)
    }

    suspend fun run(
        cfg: PipelineConfig,
        onLog: (String) -> Unit,
        onProgress: (String, Int, Int) -> Unit
    ): PipelineResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val fetcher = Fetcher(timeoutMs = 12000)

        // ---------- 阶段 1：抓取 ----------
        onLog("== 阶段 1/3：抓取订阅源（共 ${cfg.sources.size} 个）==")
        val perSource = Collections.synchronizedList(ArrayList<FetchOutcome>())
        val doneCount = AtomicInteger(0)
        val srcSem = Semaphore(8)
        coroutineScope {
            cfg.sources.distinct().map { src ->
                async {
                    srcSem.withPermit {
                        val r = try {
                            fetchOne(src, fetcher, 16)
                        } catch (e: Exception) {
                            FetchOutcome(emptyList(), emptyList(), false)
                        }
                        perSource.add(r)
                        val d = doneCount.incrementAndGet()
                        onProgress("抓取中", d, cfg.sources.size)
                        if (r.ok) {
                            onLog("  [${r.uris.size + r.nodes.size}] ${src.substringBefore('#')}")
                        } else {
                            onLog("  [失败] ${src.substringBefore('#')}")
                        }
                    }
                }
            }.awaitAll()
        }

        val okCount = perSource.count { it.ok }
        val failCount = perSource.size - okCount

        // ---------- 合并 / 去重 ----------
        val rawList = ArrayList<Node>()
        for (o in perSource) {
            for (n in o.nodes) rawList.add(n)
            for (u in o.uris) UriParser.uriToProxy(u)?.let { rawList.add(it) }
        }
        val (merged, dups) = dedupe(rawList)
        onLog("抓取完成：原始 ${rawList.size} 条，去重过滤后 ${merged.size} 条（丢弃 ${dups} 条）")

        if (merged.isEmpty()) {
            return@withContext PipelineResult(emptyList(), 0, 0, 0, okCount, failCount, System.currentTimeMillis() - start)
        }

        // 轮转挑选，避免总是偏向排在前面的源
        val limited = merged.take(cfg.maxNodes)
        onLog("进入测速的候选节点：${limited.size}")

        // ---------- 阶段 2：TCP 延迟 ----------
        onLog("== 阶段 2/3：TCP 延迟测试 ==")
        val aliveNodes = Collections.synchronizedList(ArrayList<Node>())
        val tcpDone = AtomicInteger(0)
        val tcpSem = Semaphore(64)
        coroutineScope {
            limited.map { n ->
                async {
                    tcpSem.withPermit {
                        val d = tcpDelay(n.server, n.port, cfg.tcpTimeoutMs)
                        if (d > 0 && d <= cfg.maxDelayMs) {
                            n.delay = d
                            aliveNodes.add(n)
                        }
                        val c = tcpDone.incrementAndGet()
                        if (c % 25 == 0 || c == limited.size) onProgress("延迟测试", c, limited.size)
                    }
                }
            }.awaitAll()
        }
        val alive = aliveNodes.sortedBy { it.delay }
        onLog("TCP 可达：${alive.size} / ${limited.size}")

        if (alive.isEmpty()) {
            return@withContext PipelineResult(emptyList(), limited.size, 0, 0, okCount, failCount, System.currentTimeMillis() - start)
        }

        // ---------- 阶段 2b：真实隧道测速（Shadowsocks） ----------
        var measured = 0
        if (!cfg.quick) {
            val ssPool = alive.filter { it.type == "ss" }
                .filter { it.delay <= cfg.speedMaxDelayMs }
                .take(cfg.speedTop)
            onLog("== 阶段 2b/3：通过节点实测延迟与下载速度（${ssPool.size} 个 ss 节点）==")
            if (ssPool.isNotEmpty()) {
                val speedDone = AtomicInteger(0)
                val speedSem = Semaphore(12)
                coroutineScope {
                    ssPool.map { n ->
                        async {
                            speedSem.withPermit {
                                testOne(n, cfg)
                                if (n.speed >= 0) measured++
                                val c = speedDone.incrementAndGet()
                                onProgress("实测速度", c, ssPool.size)
                                if (c % 5 == 0 || c == ssPool.size) {
                                    onLog("  实测 $c/${ssPool.size}：${n.name} -> " +
                                        (if (n.speed >= 0) String.format("%.2f MB/s", n.speed) else "失败"))
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        // 没测出不支持实测的节点，标注一下
        for (n in alive) {
            if (n.type != "ss") n.note = "仅 TCP 延迟（${n.type}）"
            else if (n.speed < 0 && n.realDelay < 0) {
                if (n.note.isEmpty()) n.note = "实测未通"
            }
        }

        // ---------- 阶段 3：排序输出 ----------
        val ordered = order(alive, cfg)
        onLog("== 阶段 3/3：排序完成，入选 ${ordered.size} 个节点 ==")
        PipelineResult(
            ordered, limited.size, alive.size, measured, okCount, failCount,
            System.currentTimeMillis() - start
        )
    }

    // ------------------------------------------------------------ 实测

    private fun testOne(n: Node, cfg: PipelineConfig) {
        val probe = SsConnection(
            n.server, n.port, n.cipher, n.password,
            connectTimeoutMs = 6000, soTimeoutMs = 6000
        )
        if (!probe.isSupported()) {
            n.note = probe.unsupportedReason()
            return
        }
        try {
            // 1) 真实延迟：204 探测
            var latOk = false
            for (t in LATENCY_TARGETS) {
                val lat = try {
                    val c = SsConnection(n.server, n.port, n.cipher, n.password, 6000, 6000)
                    c.connectSocket()
                    c.openTunnel(t.host, t.port)
                    val r = TunnelHttp.get(c, t.tls, t.host, t.port, t.path, 0, 6000, 6000)
                    c.close()
                    r
                } catch (e: Exception) {
                    null
                }
                if (lat != null && lat.ok) {
                    n.realDelay = lat.ttfbMs.toInt()
                    latOk = true
                    break
                }
            }
            if (!latOk) {
                n.note = if (n.note.isEmpty()) "实测未通" else n.note
                return
            }

            // 2) 真实下载速度
            if (cfg.speedBytes <= 0) return
            for (t in SPEED_TARGETS) {
                val path = t.path.replace("%%N%%", cfg.speedBytes.toString())
                val c = SsConnection(n.server, n.port, n.cipher, n.password, 6000, 9000)
                try {
                    c.connectSocket()
                    c.openTunnel(t.host, t.port)
                    val r = TunnelHttp.get(c, t.tls, t.host, t.port, path, cfg.speedBytes, 8000, 9000)
                    if (r.ok && r.bytes > 16 * 1024 && r.elapsedMs > 0) {
                        val mbps = r.bytes.toDouble() / (r.elapsedMs.toDouble() / 1000.0) / 1e6
                        n.speed = mbps
                        n.note = ""
                        break
                    }
                } catch (e: Exception) {
                    // 换下一个目标
                } finally {
                    c.close()
                }
            }
        } catch (e: Exception) {
            if (n.note.isEmpty()) n.note = "实测异常"
        }
    }

    // ------------------------------------------------------------ 工具

    private fun dedupe(input: List<Node>): Pair<List<Node>, Int> {
        val seen = HashSet<String>()
        val out = ArrayList<Node>()
        var dropped = 0
        for (n in input) {
            if (n.server.isBlank() || n.port <= 0 || n.type.isBlank()) {
                dropped++
                continue
            }
            if (UriParser.isDecoy(n.server)) {
                dropped++
                continue
            }
            val key = "${n.type}|${n.server}|${n.port}|${(n.map["uuid"] ?: n.map["password"] ?: "").toString().take(12)}"
            if (!seen.add(key)) {
                dropped++
                continue
            }
            out.add(n)
        }
        // 节点名去重
        val names = HashSet<String>()
        var i = 1
        for (n in out) {
            var base = n.name
            if (base.isBlank()) base = "${n.server}:${n.port}"
            var name = base
            var k = 2
            while (!names.add(name)) {
                name = "$base #$k"
                k++
            }
            if (name != n.map["name"]) n.map["name"] = name
            i++
        }
        return out to dropped
    }

    private fun order(alive: List<Node>, cfg: PipelineConfig): List<Node> {
        val list = ArrayList<Node>()
        for (n in alive) {
            if (cfg.ssOnly && !n.isShadowsocks) continue
            list.add(n)
        }
        val withSpeed = list.filter { it.speed > 0 }.sortedWith(
            compareByDescending<Node> { it.speed }.thenBy { it.realDelay }
        )
        val qualified = withSpeed.filter { it.speed >= cfg.minSpeedMbps }
        val pick = if (qualified.isNotEmpty()) qualified else withSpeed

        val result = ArrayList<Node>()
        for (n in pick) result.add(n)

        // 慢一点但延迟低、没测出速度的节点排后面补齐
        val rest = list.filter { it.speed <= 0 }
            .sortedBy { if (it.realDelay > 0) it.realDelay else it.delay }
        for (n in rest) {
            if (result.size >= cfg.keepTop) break
            result.add(n)
        }

        val out = ArrayList<Node>()
        var rank = 1
        for (n in result.take(cfg.keepTop)) {
            val node = if (cfg.labelSpeed && n.speed > 0 && !n.name.contains("MB/s")) {
                n.withName(String.format("%.1fMB/s ", n.speed) + n.name)
            } else n
            out.add(node)
            rank++
        }
        return out
    }
}
