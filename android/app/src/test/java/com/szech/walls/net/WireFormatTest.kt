package com.szech.walls.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

/**
 * 不联网的线格式测试：vless / trojan 请求头、WebSocket 握手与分片。
 * 这几处一旦写错，表现是「所有节点都测不通」，很难排查，所以用测试钉住。
 */
class WireFormatTest {

    /** 测试用假流：写出去的东西攒起来，喂进来的东西由 handler 按需生成。 */
    private class FakeStream(private val handler: (ByteArray) -> ByteArray) : ProxyStream {
        val written = ByteArrayOutputStream()
        private val inp = object : InputStream() {
            private var data: ByteArray? = null
            private var pos = 0

            private fun ensure(): ByteArray {
                var d = data
                if (d == null) {
                    d = handler(written.toByteArray())
                    data = d
                }
                return d
            }

            override fun read(): Int {
                val d = ensure()
                return if (pos >= d.size) -1 else d[pos++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val d = ensure()
                if (pos >= d.size) return -1
                val n = minOf(len, d.size - pos)
                System.arraycopy(d, pos, b, off, n)
                pos += n
                return n
            }
        }

        override val input: InputStream get() = inp
        override val output: java.io.OutputStream get() = written
        override fun setReadTimeout(ms: Int) {}
        override fun close() {}
    }

    private fun header(text: String, name: String): String {
        for (line in text.split("\r\n")) {
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals(name, ignoreCase = true)) {
                return line.substring(i + 1).trim()
            }
        }
        return ""
    }

    private fun serverFrame(payload: ByteArray, opcode: Int = 0x2): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size < 65536 -> {
                out.write(126)
                out.write((payload.size ushr 8) and 0xFF)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(127)
                for (i in 7 downTo 0) out.write(((payload.size.toLong() ushr (8 * i)) and 0xFF).toInt())
            }
        }
        out.write(payload, 0, payload.size)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ WebSocket

    @Test
    fun `ws 握手会带上正确的请求头，并校验 Accept`() {
        val body = "hello".toByteArray()
        val fake = FakeStream { req ->
            val text = String(req, Charsets.ISO_8859_1)
            val key = header(text, "Sec-WebSocket-Key")
            assertTrue("必须带 Sec-WebSocket-Key", key.isNotEmpty())
            assertEquals(16, Base64.getDecoder().decode(key).size)
            val accept = Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
            )
            val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n"
            resp.toByteArray(Charsets.ISO_8859_1) + serverFrame(body)
        }
        val ws = WsStream.connect(fake, "example.com", "/sub", emptyMap(), 3000)
        val req = String(fake.written.toByteArray(), Charsets.ISO_8859_1)
        assertTrue("请求行要对", req.startsWith("GET /sub HTTP/1.1\r\n"))
        assertEquals("example.com", header(req, "Host"))
        assertEquals("websocket", header(req, "Upgrade"))
        assertEquals("13", header(req, "Sec-WebSocket-Version"))

        val got = ByteArray(body.size)
        var read = 0
        while (read < got.size) {
            val n = ws.input.read(got, read, got.size - read)
            if (n < 0) break
            read += n
        }
        assertEquals(body.size, read)
        assertArrayEquals(body, got)
    }

    @Test
    fun `客户端帧必须加掩码且 opcode 是 binary`() {
        val fake = FakeStream { req ->
            assertTrue(String(req, Charsets.ISO_8859_1).startsWith("GET / HTTP/1.1"))
            "HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        }
        val ws = WsStream.connect(fake, "h", "/", emptyMap(), 3000)
        val before = fake.written.size()
        val payload = "abcdefghij".toByteArray()
        ws.output.write(payload)
        ws.output.flush()
        val frame = fake.written.toByteArray().copyOfRange(before, fake.written.size())

        assertEquals(0x2, frame[0].toInt() and 0x0F)
        assertTrue("FIN 要置位", (frame[0].toInt() and 0x80) != 0)
        assertTrue("客户端帧必须打掩码", (frame[1].toInt() and 0x80) != 0)
        assertEquals(payload.size, frame[1].toInt() and 0x7F)
        val mask = frame.copyOfRange(2, 6)
        val body = frame.copyOfRange(6, frame.size)
        val plain = ByteArray(body.size) { (body[it].toInt() xor mask[it and 3].toInt()).toByte() }
        assertArrayEquals(payload, plain)
    }

    @Test
    fun `收到 ping 要回 pong`() {
        val fake = FakeStream {
            ("HTTP/1.1 101 Switching Protocols\r\n\r\n").toByteArray(Charsets.ISO_8859_1) +
                serverFrame("ping-payload".toByteArray(), 0x9) +
                serverFrame("data".toByteArray(), 0x2)
        }
        val ws = WsStream.connect(fake, "h", "/", emptyMap(), 3000)
        val b = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = ws.input.read(b, read, 4 - read)
            if (n < 0) break
            read += n
        }
        assertEquals("data", String(b, Charsets.UTF_8))
        val out = fake.written.toByteArray()
        // 写出去的 ping 之后应该跟一个 pong 帧
        var found = false
        for (i in out.indices) {
            if ((out[i].toInt() and 0x0F) == 0xA && (out[i].toInt() and 0x80) != 0) {
                found = true
                break
            }
        }
        assertTrue("应当回一个 pong 帧", found)
    }

    @Test
    fun `超过 125 字节的帧长度字段要用 126`() {
        val fake = FakeStream { "HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray(Charsets.ISO_8859_1) }
        val ws = WsStream.connect(fake, "h", "/", emptyMap(), 3000)
        val before = fake.written.size()
        val payload = ByteArray(300) { it.toByte() }
        ws.output.write(payload)
        val frame = fake.written.toByteArray().copyOfRange(before, fake.written.size())
        assertEquals(126, frame[1].toInt() and 0x7F)
        assertEquals(300, ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF))
    }

    // ------------------------------------------------------------ 协议头

    @Test
    fun `vless 请求头字节序正确`() {
        val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
        val uid = VlessTunnel.parseUuid(uuid)!!
        val h = VlessTunnel.buildRequest(uid, "www.gstatic.com", 443)
        assertEquals(0, h[0].toInt())          // version
        assertEquals(16, uid.size)
        assertEquals(0xb8, h[1].toInt() and 0xFF)
        assertEquals(0x11, h[16].toInt() and 0xFF)
        assertEquals(0, h[17].toInt())         // addon length
        assertEquals(1, h[18].toInt())         // command TCP
        assertEquals(1, h[19].toInt())         // port 443 = 0x01BB
        assertEquals(0xBB, h[20].toInt() and 0xFF)
        assertEquals(2, h[21].toInt())         // ATYP = 域名（vless 里是 2，不是 socks5 的 3）
        assertEquals("www.gstatic.com".length, h[22].toInt())
        assertEquals("www.gstatic.com", String(h, 23, 15, Charsets.UTF_8))
        assertEquals(38, h.size)
    }

    @Test
    fun `vless 对 IPv4 目标用 ATYP 1`() {
        val uid = VlessTunnel.parseUuid("b831381d-6324-4d53-ad4f-8cda48b30811")!!
        val h = VlessTunnel.buildRequest(uid, "1.2.3.4", 80)
        assertEquals(1, h[21].toInt())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), h.copyOfRange(22, 26))
        assertEquals(26, h.size)
    }

    @Test
    fun `trojan 请求头是 hex(sha224) 加 CRLF`() {
        val hp = TrojanTunnel.hexSha224("humanity")
        assertEquals(
            "3fc36f0f81e6b9642a15eefdb7f6af69a1f82d4509a58e2803656dcc",
            String(hp, Charsets.US_ASCII)
        )
        assertEquals(56, hp.size)
        val h = TrojanTunnel.buildRequest(hp, "example.com", 443)
        assertArrayEquals(hp, h.copyOfRange(0, 56))
        assertEquals('\r'.code, h[56].toInt())
        assertEquals('\n'.code, h[57].toInt())
        assertEquals(1, h[58].toInt())         // CONNECT
        assertEquals(3, h[59].toInt())         // ATYP = domain
        assertEquals(11, h[60].toInt())
        assertEquals("example.com", String(h, 61, 11, Charsets.UTF_8))
        assertEquals(1, h[72].toInt())         // port 443 = 0x01BB
        assertEquals(0xBB, h[73].toInt() and 0xFF)
        assertEquals('\r'.code, h[74].toInt())
        assertEquals('\n'.code, h[75].toInt())
        assertEquals(76, h.size)
    }

    @Test
    fun `坏 uuid 会被拒绝`() {
        assertEquals(null, VlessTunnel.parseUuid("not-a-uuid"))
        assertEquals(null, VlessTunnel.parseUuid("b831381d-6324-4d53-ad4f-8cda48b3081"))
    }

    @Test
    fun `socks 地址编码三种类型都对`() {
        val a = ByteArrayOutputStream()
        writeSocksAddr(a, "1.2.3.4", 443)
        assertArrayEquals(byteArrayOf(1, 1, 2, 3, 4, 1, 0xBB.toByte()), a.toByteArray())

        val b = ByteArrayOutputStream()
        writeSocksAddr(b, "example.com", 443)
        assertEquals(3, b.toByteArray()[0].toInt())
        assertEquals(11, b.toByteArray()[1].toInt())

        val c = ByteArrayOutputStream()
        writeSocksAddr(c, "2001:db8::1", 443, withPort = false)
        assertEquals(4, c.toByteArray()[0].toInt())
        assertEquals(17, c.toByteArray().size)
    }

    @Test
    fun `vless 的 ATYP 编号与 socks5 不同`() {
        val d = ByteArrayOutputStream()
        writeSocksAddr(d, "example.com", 443, atyp = Atyp.VLESS)
        assertEquals(2, d.toByteArray()[0].toInt())

        val v6 = ByteArrayOutputStream()
        writeSocksAddr(v6, "2001:db8::1", 443, withPort = false, atyp = Atyp.VLESS)
        assertEquals(3, v6.toByteArray()[0].toInt())
    }
}
