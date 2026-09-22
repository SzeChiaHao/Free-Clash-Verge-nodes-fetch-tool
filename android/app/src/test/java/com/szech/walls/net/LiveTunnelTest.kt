package com.szech.walls.net

import com.szech.walls.model.Node
import com.szech.walls.pipeline.Prober
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真机实测：拿一批「已知在 mihomo 里能跑」的节点，让自研隧道去访问 gstatic/generate_204。
 *
 * 这些节点随时会失效，所以默认不跑；要跑就给 gradle 传语料文件路径：
 *
 *   gradlew testDebugUnitTest -Dwalls.liveCorpus=D:\Walls\.tmp\corpus.tsv
 *
 * 语料是 TSV，一行一个节点（# 开头是注释）：
 *   type  server  port  secret  network  tls  sni  host  path
 * tls 列：1=开，0=关，-=原配置里没有这个字段（按协议默认来）
 *
 * 这个测试是这类项目里最重要的一个：协议实现只要差一个字节，就会「所有节点都测不通」，
 * 而单测很难发现。做法是把 mihomo 能跑通的节点当成标准答案，看自研客户端能不能对上。
 */
class LiveTunnelTest {

    private fun nodeOf(f: List<String>): Node {
        val type = f[0]
        val server = f[1]
        val port = f[2].toInt()
        val secret = f[3]
        val network = f[4]
        val tlsCol = f[5]
        val sni = f[6]
        val host = f[7]
        val path = f[8]
        val map = linkedMapOf<String, Any?>(
            "name" to "$server:$port",
            "type" to type,
            "server" to server,
            "port" to port,
            "network" to network,
            "udp" to true
        )
        when (type) {
            "vless" -> {
                map["uuid"] = secret
                map["tls"] = tlsCol == "1"
                if (sni.isNotEmpty()) map["servername"] = sni
            }
            "trojan" -> {
                map["password"] = secret
                // 只有语料明确写了 tls=0 才关掉；写 '-' 表示原配置里没有这个字段，
                // 此时按 trojan 的默认（TLS）处理
                if (tlsCol == "0") map["tls"] = false
                if (sni.isNotEmpty()) map["sni"] = sni
            }
            else -> {
                map["cipher"] = "aes-256-gcm"
                map["password"] = secret
            }
        }
        if (network == "ws") {
            val opts = linkedMapOf<String, Any?>("path" to path)
            if (host.isNotEmpty()) opts["headers"] = linkedMapOf<String, Any?>("Host" to host)
            map["ws-opts"] = opts
        }
        return Node(map)
    }

    /** 失败时补一步诊断：到底断在哪一层 */
    private fun diag(n: Node): String {
        val t = TunnelFactory.create(n, insecure = true) ?: return "create=null"
        return try {
            t.connect()
            t.close()
            "connect=OK（问题在数据阶段）"
        } catch (e: Throwable) {
            "connect=${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Test
    fun `真实节点能被自研隧道测通`() {
        val path = System.getProperty("walls.liveCorpus")
        if (path == null) {
            println("[skip] 未指定 -Dwalls.liveCorpus，跳过真机实测")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            println("[skip] 语料文件不存在：$path")
            return
        }
        val lines = file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        var tried = 0
        var ok = 0
        val failed = ArrayList<String>()
        for (line in lines) {
            val f = line.split('\t')
            if (f.size < 9) continue
            val n = try {
                nodeOf(f)
            } catch (e: Exception) {
                continue
            }
            tried++
            val r = try {
                Prober.measureWithFallback(n, speedBytes = 256 * 1024)
            } catch (e: Exception) {
                println("  [异常] ${n.server}:${n.port} ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (r != null) {
                ok++
                println(
                    "  [OK ] ${n.type} ${n.server}:${n.port} 延迟 ${r.latencyMs}ms" +
                        (if (r.speedMbps > 0) " 速度 ${"%.2f".format(r.speedMbps)} MB/s" else " 速度未测出")
                )
            } else {
                failed.add("${n.type} ${n.server}:${n.port}")
                println("  [失败] ${n.type} ${n.server}:${n.port} ${diag(n)}")
            }
        }
        println("自研隧道实测：$ok / $tried 通过")
        assertTrue("语料里应当有节点", tried > 0)
        // 免费节点随时失效，mihomo 那边的通过率也不是 100%；这里要求至少一半能过
        assertTrue("通过率太低（$ok/$tried），协议实现可能有问题。失败：$failed", ok * 2 >= tried)
    }
}
