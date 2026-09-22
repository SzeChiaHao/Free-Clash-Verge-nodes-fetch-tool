package com.szech.walls.store

import android.content.Context
import android.content.SharedPreferences
import com.szech.walls.model.JsonUtil
import com.szech.walls.model.Node
import com.szech.walls.pipeline.DefaultSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Prefs {

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.getSharedPreferences("walls", Context.MODE_PRIVATE)
        }
    }

    var port: Int
        get() = sp.getInt("port", 8787)
        set(v) = sp.edit().putInt("port", v).apply()

    var autoDaily: Boolean
        get() = sp.getBoolean("autoDaily", true)
        set(v) = sp.edit().putBoolean("autoDaily", v).apply()

    var quick: Boolean
        get() = sp.getBoolean("quick", false)
        set(v) = sp.edit().putBoolean("quick", v).apply()

    var maxNodes: Int
        get() = sp.getInt("maxNodes", 600)
        set(v) = sp.edit().putInt("maxNodes", v).apply()

    var keepTop: Int
        get() = sp.getInt("keepTop", 40)
        set(v) = sp.edit().putInt("keepTop", v).apply()

    var speedTop: Int
        get() = sp.getInt("speedTop", 40)
        set(v) = sp.edit().putInt("speedTop", v).apply()

    var speedBytesKb: Int
        get() = sp.getInt("speedBytesKb", 512)
        set(v) = sp.edit().putInt("speedBytesKb", v).apply()

    var minSpeedTenths: Int
        get() = sp.getInt("minSpeedTenths", 2)
        set(v) = sp.edit().putInt("minSpeedTenths", v).apply()

    var ssOnly: Boolean
        get() = sp.getBoolean("ssOnly", true)
        set(v) = sp.edit().putBoolean("ssOnly", v).apply()

    var labelSpeed: Boolean
        get() = sp.getBoolean("labelSpeed", true)
        set(v) = sp.edit().putBoolean("labelSpeed", v).apply()

    var mirrorFirst: Boolean
        get() = sp.getBoolean("mirrorFirst", true)
        set(v) = sp.edit().putBoolean("mirrorFirst", v).apply()

    var sources: List<String>
        get() {
            val raw = sp.getString("sources", null) ?: return DefaultSources.LIST
            return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }
        set(v) = sp.edit().putString("sources", v.joinToString("\n")).apply()

    var discovered: List<String>
        get() {
            val raw = sp.getString("discovered", null) ?: return emptyList()
            return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
        set(v) = sp.edit().putString("discovered", v.joinToString("\n")).apply()

    fun resetSources() {
        sp.edit().remove("sources").apply()
    }
}

class RunStatus(
    var lastRunAt: Long = 0L,
    var candidates: Int = 0,
    var alive: Int = 0,
    var measured: Int = 0,
    var sourcesOk: Int = 0,
    var sourcesFail: Int = 0,
    var kept: Int = 0,
    var message: String = "",
    var running: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lastRunAt", lastRunAt)
        put("candidates", candidates)
        put("alive", alive)
        put("measured", measured)
        put("sourcesOk", sourcesOk)
        put("sourcesFail", sourcesFail)
        put("kept", kept)
        put("message", message)
        put("running", running)
    }

    companion object {
        fun from(o: JSONObject): RunStatus = RunStatus(
            o.optLong("lastRunAt", 0L),
            o.optInt("candidates"),
            o.optInt("alive"),
            o.optInt("measured"),
            o.optInt("sourcesOk"),
            o.optInt("sourcesFail"),
            o.optInt("kept"),
            o.optString("message", ""),
            o.optBoolean("running", false)
        )
    }
}

/** 结果仓库：节点列表 + 状态，落盘到 filesDir，重启后还在。 */
object Repo {

    private lateinit var dir: File

    var nodes: List<Node> = emptyList()
        internal set
    var status: RunStatus = RunStatus()
        internal set

    fun setResult(list: List<Node>, st: RunStatus) {
        nodes = list
        status = st
        save()
    }

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "walls").apply { mkdirs() }
        load()
    }

    val workDir: File get() = dir

    fun fileOf(name: String): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        return f
    }

    fun load() {
        try {
            val nf = File(dir, "nodes.json")
            if (nf.exists()) {
                val arr = JSONArray(nf.readText())
                val list = ArrayList<Node>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val map = JsonUtil.jsonToMap(o.optJSONObject("p") ?: JSONObject())
                    val n = Node(map)
                    n.delay = o.optInt("d", -1)
                    n.speed = o.optDouble("s", -1.0)
                    n.realDelay = o.optInt("r", -1)
                    n.note = o.optString("n", "")
                    list.add(n)
                }
                nodes = list
            }
            val sf = File(dir, "status.json")
            if (sf.exists()) status = RunStatus.from(JSONObject(sf.readText()))
        } catch (e: Exception) {
            // 数据损坏就从头来
        }
    }

    fun save() {
        try {
            val arr = JSONArray()
            for (n in nodes) {
                arr.put(JSONObject().apply {
                    put("p", JsonUtil.mapToJson(n.map))
                    put("d", n.delay)
                    put("s", n.speed)
                    put("r", n.realDelay)
                    put("n", n.note)
                })
            }
            File(dir, "nodes.json").writeText(arr.toString())
            File(dir, "status.json").writeText(status.toJson().toString())
        } catch (e: Exception) {
            // ignore
        }
    }
}
