package com.szech.walls

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.szech.walls.core.Log
import com.szech.walls.export.Formats
import com.szech.walls.model.Node
import com.szech.walls.pipeline.PipelineResult
import com.szech.walls.pipeline.Runner
import com.szech.walls.server.SubServer
import com.szech.walls.server.SubService
import com.szech.walls.server.SubServiceHolder
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var nodeBox: LinearLayout
    private lateinit var logView: TextView
    private lateinit var serviceSwitch: SwitchCompat
    private lateinit var urlBox: LinearLayout

    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Repo.init(this)
        Log.addListener { line ->
            runOnUiThread { renderLog() }
        }
        setContentView(buildUi())
        renderAll()
        if (Repo.nodes.isEmpty()) {
            Log.add("提示：点「开始更新」就会抓取 + 测速 + 生成订阅。首次运行约 1~3 分钟。")
        }
    }

    override fun onResume() {
        super.onResume()
        serviceSwitch.isChecked = isServiceRunning()
        renderUrls()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // ================================================================== UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun title(text: String, size: Float = 15f): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFF1B1F24.toInt())
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun dim(text: String, size: Float = 12.5f): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFF6A737D.toInt())
        textSize = size
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun body(text: String, size: Float = 13.5f): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFF1B1F24.toInt())
        textSize = size
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun button(text: String, outlined: Boolean = false): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            textSize = 12.5f
            minHeight = dp(40)
            minimumHeight = dp(40)
            setPadding(dp(8), 0, dp(8), 0)
            if (outlined) {
                setBackgroundColor(0x00000000)
                setStrokeWidth(dp(1))
                setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF1F6FEB.toInt()))
                setTextColor(0xFF1F6FEB.toInt())
            } else {
                setBackgroundColor(0xFF1F6FEB.toInt())
                setTextColor(0xFFFFFFFF.toInt())
            }
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        setBackgroundColor(0xFFFFFFFF.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(10), dp(6), dp(10), dp(4))
        }
        elevation = dp(1).toFloat()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F6F8.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 顶部标题
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(10))
            setBackgroundColor(0xFF1F6FEB.toInt())
        }
        header.addView(TextView(this).apply {
            text = "🧱 节点管家"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "免费节点抓取 · 实测测速 · 按速度排序 · 本地订阅"
            setTextColor(0xFFD6E4FF.toInt())
            textSize = 12f
            setPadding(0, dp(3), 0, 0)
        })
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(0, 0, 0, dp(20))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        root.addView(scroll)

        // ---- 状态卡片 ----
        val c1 = card()
        c1.addView(title("运行状态"))
        statusView = body("尚未运行过")
        c1.addView(statusView)
        progressText = dim("")
        c1.addView(progressText)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { setMargins(0, dp(6), 0, dp(4)) }
            visibility = View.GONE
        }
        c1.addView(progress)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val btnStart = button("开始更新")
        btnStart.layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(0, 0, dp(6), 0)
        }
        btnStart.setOnClickListener { startRun(false) }
        row1.addView(btnStart)

        val btnQuick = button("快速更新", true)
        btnQuick.layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
        btnQuick.setOnClickListener { startRun(true) }
        row1.addView(btnQuick)

        val btnStop = button("停止", true)
        btnStop.layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(dp(6), 0, 0, 0)
        }
        btnStop.setOnClickListener { running = false }
        row1.addView(btnStop)
        c1.addView(row1)
        col.addView(c1)

        // ---- 订阅服务卡片 ----
        val c2 = card()
        c2.addView(title("本地订阅服务"))
        val swRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        swRow.addView(TextView(this).apply {
            text = "常驻运行（客户端随时可拉取）"
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        serviceSwitch = SwitchCompat(this).apply {
            isChecked = isServiceRunning()
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    ensureNotifyPermission()
                    SubService.start(this@MainActivity)
                    serviceSwitch.postDelayed({ serviceSwitch.isChecked = isServiceRunning() }, 300)
                } else {
                    SubService.stop(this@MainActivity)
                }
                renderUrls()
            }
        }
        swRow.addView(serviceSwitch)
        c2.addView(swRow)

        c2.addView(dim("↑ 打开后本地订阅地址才会一直有效；关掉开关即停止。"))

        urlBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        c2.addView(urlBox)

        val rowShare = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val btnExport = button("导出到下载目录", true)
        btnExport.layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        btnExport.setOnClickListener { exportToDownloads() }
        rowShare.addView(btnExport)
        c2.addView(rowShare)
        col.addView(c2)

        // ---- 节点列表卡片 ----
        val c3 = card()
        c3.addView(title("节点排名"))
        c3.addView(dim("按实测下载速度降序。点一行可看详情 / 复制分享链接。"))
        nodeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c3.addView(nodeBox)
        col.addView(c3)

        // ---- 设置卡片 ----
        val c4 = card()
        c4.addView(title("设置"))
        val settings = listOf(
            "订阅端口" to { showNumberDialog("订阅端口", Prefs.port, 1024, 65535) { Prefs.port = it; renderUrls() } },
            "候选节点上限" to { showNumberDialog("进入测速的候选节点上限", Prefs.maxNodes, 20, 3000) { Prefs.maxNodes = it } },
            "保留节点数量" to { showNumberDialog("最终保留多少个节点", Prefs.keepTop, 5, 500) { Prefs.keepTop = it } },
            "测速节点数量" to { showNumberDialog("做下载测速的节点个数", Prefs.speedTop, 0, 300) { Prefs.speedTop = it } },
            "每个节点测速流量(KB)" to {
                showNumberDialog("每个节点下载多少流量（KB）。40 个节点 × 512KB ≈ 20MB 流量", Prefs.speedBytesKb, 64, 4096) { Prefs.speedBytesKb = it }
            },
            "最低合格速度(0.1MB/s)" to {
                showNumberDialog("低于该速度的节点被过滤（单位 0.1 MB/s）", Prefs.minSpeedTenths, 0, 100) { Prefs.minSpeedTenths = it }
            },
            "只保留 Shadowsocks 节点" to { toggleBool("只保留 Shadowsocks 节点") { Prefs.ssOnly = it } },
            "节点名加上速度前缀" to { toggleBool("节点名加上速度前缀") { Prefs.labelSpeed = it } },
            "每天自动更新" to { toggleBool("每天自动更新") { Prefs.autoDaily = it; Runner.schedule(this, it) } },
            "编辑订阅源" to { showSourcesDialog() }
        )
        for ((label, action) in settings) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 13.5f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = settingValue(label)
                textSize = 13f
                setTextColor(0xFF6A737D.toInt())
                setPadding(0, 0, dp(8), 0)
            })
            row.addView(TextView(this).apply {
                text = "›"
                textSize = 20f
                setTextColor(0xFF9AA0A6.toInt())
            })
            row.isClickable = true
            row.setOnClickListener { action(); renderAll() }
            c4.addView(row)
        }
        col.addView(c4)

        // ---- 日志卡片 ----
        val c5 = card()
        c5.addView(title("运行日志"))
        logView = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF37474F.toInt())
            setTextIsSelectable(true)
        }
        c5.addView(logView)
        col.addView(c5)

        renderUrls()
        renderLog()
        return root
    }

    private fun settingValue(label: String): String = when (label) {
        "订阅端口" -> Prefs.port.toString()
        "候选节点上限" -> Prefs.maxNodes.toString()
        "保留节点数量" -> Prefs.keepTop.toString()
        "测速节点数量" -> Prefs.speedTop.toString()
        "每个节点测速流量(KB)" -> "${Prefs.speedBytesKb} KB"
        "最低合格速度(0.1MB/s)" -> "${Prefs.minSpeedTenths / 10.0} MB/s"
        "只保留 Shadowsocks 节点" -> if (Prefs.ssOnly) "开" else "关"
        "节点名加上速度前缀" -> if (Prefs.labelSpeed) "开" else "关"
        "每天自动更新" -> if (Prefs.autoDaily) "开" else "关"
        "编辑订阅源" -> "${Prefs.sources.size} 个"
        else -> ""
    }

    // =============================================================== 渲染

    private fun renderAll() {
        renderStatus()
        renderUrls()
        renderNodes()
        renderLog()
    }

    private fun fmtTime(ts: Long): String =
        if (ts <= 0) "从未" else SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
        ).format(Date(ts))

    private fun renderStatus() {
        val s = Repo.status
        val ss = Repo.nodes.count { it.type == "ss" }
        statusView.text = buildString {
            append("上次更新：").append(fmtTime(s.lastRunAt)).append('\n')
            append("当前可用节点：").append(Repo.nodes.size).append(" 个（Shadowsocks ").append(ss).append(" 个）\n")
            append("候选 ").append(s.candidates)
                .append(" · 存活 ").append(s.alive)
                .append(" · 实测 ").append(s.measured)
                .append(" · 源 ").append(s.sourcesOk).append('/').append(s.sourcesOk + s.sourcesFail)
            if (s.message.isNotEmpty()) append('\n').append(s.message)
        }
    }

    private fun renderUrls() {
        if (!::urlBox.isInitialized) return
        urlBox.removeAllViews()
        val on = isServiceRunning()
        for ((path, desc, _) in SubServer.INDEX_ROUTES) {
            if (path.startsWith("/api")) continue
            val url = "http://127.0.0.1:${Prefs.port}$path"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(TextView(this).apply {
                text = path
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val bCopy = button("复制", true).apply {
                layoutParams = LinearLayout.LayoutParams(dp(58), dp(34))
                textSize = 12f
                minHeight = dp(34)
                setOnClickListener { copy(url, "已复制 $path") }
            }
            val bOpen = button("打开", true).apply {
                layoutParams = LinearLayout.LayoutParams(dp(58), dp(34))
                textSize = 12f
                minHeight = dp(34)
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        toast("打不开：${e.message}")
                    }
                }
            }
            head.addView(bCopy)
            head.addView(bOpen)
            row.addView(head)
            row.addView(TextView(this).apply {
                text = url
                textSize = 12f
                setTextColor(if (on) 0xFF1F6FEB.toInt() else 0xFF9AA0A6.toInt())
                setTextIsSelectable(true)
            })
            row.addView(TextView(this).apply {
                text = desc
                textSize = 11.5f
                setTextColor(0xFF6A737D.toInt())
            })
            urlBox.addView(row)
        }
        urlBox.addView(TextView(this).apply {
            text = if (on) "✓ 服务运行中：把 http://127.0.0.1:${Prefs.port}/sip008 填进 Shadowsocks 的「订阅」即可。"
            else "✗ 服务未启动：打开上面的开关，客户端才能拉到订阅。"
            textSize = 12.5f
            setTextColor(if (on) 0xFF1B7F3B.toInt() else 0xFFB3261E.toInt())
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun renderNodes() {
        if (!::nodeBox.isInitialized) return
        nodeBox.removeAllViews()
        if (Repo.nodes.isEmpty()) {
            nodeBox.addView(dim("还没有节点，点上面的「开始更新」。"))
            return
        }
        for ((i, n) in Repo.nodes.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            val l1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            l1.addView(TextView(this).apply {
                text = "${i + 1}"
                textSize = 13f
                setTextColor(0xFF9AA0A6.toInt())
                width = dp(26)
            })
            l1.addView(TextView(this).apply {
                text = n.name
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            l1.addView(TextView(this).apply {
                text = buildString {
                    append(if (n.speed > 0) String.format("%.2fMB/s", n.speed) else "—")
                    append("  ")
                    append(if (n.realDelay > 0) "${n.realDelay}ms" else if (n.delay > 0) "${n.delay}ms" else "")
                }
                textSize = 12f
                setTextColor(0xFF1B7F3B.toInt())
            })
            row.addView(l1)
            row.addView(TextView(this).apply {
                text = "${n.type} · ${n.server}:${n.port}" + if (n.note.isNotEmpty()) " · ${n.note}" else ""
                textSize = 11f
                setTextColor(0xFF6A737D.toInt())
            })
            row.setBackgroundColor(0xFFFFFFFF.toInt())
            row.isClickable = true
            row.setOnClickListener { showNodeDialog(n) }
            nodeBox.addView(row)
            nodeBox.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                )
                setBackgroundColor(0xFFEEF1F4.toInt())
            })
        }
    }

    private fun renderLog() {
        if (!::logView.isInitialized) return
        val lines = Log.snapshot()
        logView.text = lines.takeLast(80).joinToString("\n").ifEmpty { "（暂无日志）" }
    }

    // =============================================================== 动作

    private fun startRun(quick: Boolean) {
        if (running) {
            toast("正在运行中，请稍候")
            return
        }
        running = true
        progress.visibility = View.VISIBLE
        progress.progress = 0
        progressText.text = "准备中…"
        if (quick) Prefs.quick = true else Prefs.quick = false
        Log.add(if (quick) "=== 开始快速更新（只测 TCP 延迟）===" else "=== 开始完整更新（抓取 + 实测测速）===")
        lifecycleScope.launch {
            val res: PipelineResult = withContext(Dispatchers.IO) {
                try {
                    Runner.runNow(
                        onLog = { Log.add(it) },
                        onProgress = { stage, done, total ->
                            runOnUiThread {
                                progressText.text = "$stage $done/$total"
                                progress.progress = if (total > 0) done * 100 / total else 0
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.add("出错：${e.message}")
                    PipelineResult(emptyList(), 0, 0, 0, 0, 0, 0)
                }
            }
            running = false
            progress.visibility = View.GONE
            progressText.text = ""
            Log.add("=== 结束：入选 ${res.nodes.size} 个节点 ===")
            if (res.nodes.isEmpty()) toast("没有抓到可用节点，保留上次结果")
            else toast("完成，共 ${res.nodes.size} 个节点")
            renderAll()
        }
    }

    private fun isServiceRunning(): Boolean {
        return try {
            val svc = SubServiceHolder.instance
            svc?.isRunning() ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun showNodeDialog(n: Node) {
        val link = if (n.type == "ss") Formats.ssUri(n) else Formats.v2rayUri(n)
        val info = buildString {
            append("名称：").append(n.name).append('\n')
            append("类型：").append(n.type).append('\n')
            append("地址：").append(n.server).append(':').append(n.port).append('\n')
            if (n.cipher.isNotEmpty()) append("加密：").append(n.cipher).append('\n')
            if (n.speed > 0) append("实测速度：").append(String.format("%.2f MB/s", n.speed)).append('\n')
            if (n.realDelay > 0) append("实测延迟：").append(n.realDelay).append(" ms\n")
            if (n.delay > 0) append("TCP 延迟：").append(n.delay).append(" ms\n")
            if (n.note.isNotEmpty()) append("备注：").append(n.note).append('\n')
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("节点详情")
            .setMessage(info + "\n" + (link ?: ""))
            .setPositiveButton("复制分享链接") { _, _ -> link?.let { copy(it, "已复制分享链接") } }
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
    }

    private fun toggleBool(label: String, set: (Boolean) -> Unit) {
        val cur = when (label) {
            "只保留 Shadowsocks 节点" -> Prefs.ssOnly
            "节点名加上速度前缀" -> Prefs.labelSpeed
            "每天自动更新" -> Prefs.autoDaily
            else -> false
        }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setSingleChoiceItems(arrayOf("开", "关"), if (cur) 0 else 1) { d, which ->
                set(which == 0)
                d.dismiss()
                renderAll()
            }
            .show()
    }

    private fun showNumberDialog(label: String, cur: Int, min: Int, max: Int, set: (Int) -> Unit) {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cur.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val v = edit.text.toString().toIntOrNull()
                if (v == null || v < min || v > max) {
                    toast("请输入 $min ~ $max 之间的整数")
                } else {
                    set(v)
                }
                renderAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSourcesDialog() {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(Prefs.sources.joinToString("\n"))
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            textSize = 12f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = "每行一个订阅链接；末尾加 #type=list 表示这是订阅合集页（会自动展开里面的订阅）。"
                textSize = 12f
                setTextColor(0xFF6A737D.toInt())
            })
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle("编辑订阅源")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                Prefs.sources = edit.text.toString().split('\n')
                toast("已保存 ${Prefs.sources.size} 个源")
                renderAll()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                Prefs.resetSources()
                renderAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copy(text: String, tip: String) {
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("walls", text))
            toast(tip)
        } catch (e: Exception) {
            toast("复制失败：${e.message}")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    /** 把各种格式的文件写进「下载」目录，方便用别的 App 导入 */
    private fun exportToDownloads() {
        val items = listOf(
            Triple("ss-明文.txt", Formats.plainSs(Repo.nodes), "text/plain"),
            Triple("ss-订阅-base64.txt", Formats.base64Ss(Repo.nodes), "text/plain"),
            Triple("sip008.json", Formats.sip008(Repo.nodes), "application/json"),
            Triple("clash.yaml", Formats.clashYaml(Repo.nodes, "(按速度排序)"), "text/yaml"),
            Triple("v2ray-订阅.txt", Formats.base64AllLinks(Repo.nodes), "text/plain"),
            Triple("全协议-明文.txt", Formats.plainAllLinks(Repo.nodes), "text/plain"),
            Triple("report.md", Formats.reportMd(Repo.nodes, "节点测速报告"), "text/markdown")
        )
        try {
            for ((name, content, mime) in items) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/Walls"
                        )
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(content.toByteArray(Charsets.UTF_8))
                        }
                    }
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "Walls"
                    )
                    dir.mkdirs()
                    File(dir, name).writeText(content)
                }
            }
            toast("已导出到 下载/Walls/")
            Log.add("已导出 ${items.size} 个文件到 下载/Walls/")
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    /** 分享文本文件 */
    @Suppress("unused")
    private fun share(name: String, content: String, mime: String) {
        try {
            val f = Repo.fileOf("export/$name")
            f.writeText(content)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "分享订阅"))
        } catch (e: Exception) {
            toast("分享失败：${e.message}")
        }
    }
}
