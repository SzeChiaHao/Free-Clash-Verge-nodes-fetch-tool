package com.szech.walls.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个代理节点。内部用 LinkedHashMap 保存，字段名与 Clash / mihomo 配置完全一致，
 * 这样导出的 YAML 可以直接喂给 Clash 系客户端；同时额外挂上测速结果（不参与导出）。
 */
class Node(val map: LinkedHashMap<String, Any?>) {

    val name: String
        get() = (map["name"] as? String)?.takeIf { it.isNotBlank() } ?: "$server:$port"

    val type: String
        get() = ((map["type"] as? String) ?: "").lowercase()

    val server: String
        get() = (map["server"] as? String)?.trim() ?: ""

    val port: Int
        get() = when (val p = map["port"]) {
            is Number -> p.toInt()
            is String -> p.toIntOrNull() ?: 0
            else -> 0
        }

    val cipher: String
        get() = ((map["cipher"] as? String) ?: "").lowercase()

    val password: String
        get() = (map["password"] as? String) ?: ""

    /** TCP 探测延迟（毫秒），-1 表示未测出 / 不可达 */
    var delay: Int = -1

    /** 真实下载速度（MB/s），-1 表示未做或失败 */
    var speed: Double = -1.0

    /** 真实握手延迟（毫秒），-1 表示未做或失败。仅对可测协议有意义。 */
    var realDelay: Int = -1

    /** 附加说明，例如 "仅延迟" / "加密方式暂不支持实测" */
    var note: String = ""

    val isShadowsocks: Boolean
        get() = type == "ss"

    fun withName(newName: String): Node {
        val m = LinkedHashMap(map)
        m["name"] = newName
        val n = Node(m)
        n.delay = delay
        n.speed = speed
        n.realDelay = realDelay
        n.note = note
        return n
    }

    override fun toString(): String = name
}

/** Map <-> JSONObject 递归互转，用于把节点列表持久化到磁盘。 */
object JsonUtil {

    fun mapToJson(map: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, anyToJson(v))
        return o
    }

    private fun anyToJson(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            val o = JSONObject()
            for ((k, vv) in v) o.put(k.toString(), anyToJson(vv))
            o
        }
        is List<*> -> {
            val a = JSONArray()
            for (vv in v) a.put(anyToJson(vv))
            a
        }
        else -> v
    }

    fun jsonToMap(o: JSONObject): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = o.opt(k)
            m[k] = jsonToAny(v)
        }
        return m
    }

    private fun jsonToAny(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonToMap(v)
        is JSONArray -> {
            val list = ArrayList<Any?>()
            for (i in 0 until v.length()) list.add(jsonToAny(v.opt(i)))
            list
        }
        else -> v
    }
}
