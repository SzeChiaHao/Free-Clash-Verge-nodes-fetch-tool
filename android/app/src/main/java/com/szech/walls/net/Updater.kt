package com.szech.walls.net

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.szech.walls.core.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * App 内自动更新。
 *
 * 更新信息不查 GitHub API，而是读仓库里的 version.json —— 因为 jsDelivr / gh-proxy
 * 这类镜像能拿到仓库文件，却不一定能代理 api.github.com；在国内的网络环境下这条更稳。
 *
 * version.json 长这样：
 * {
 *   "versionName": "1.3",
 *   "versionCode": 4,
 *   "apk": "https://github.com/.../releases/download/v1.3/Walls-1.3-vc4.apk",
 *   "size": 4789000,
 *   "sha256": "…",
 *   "notes": "改了啥",
 *   "publishedAt": "2026-09-22"
 * }
 */
object Updater {

    /** 更新通道：仓库 main 分支上的 version.json */
    private const val VERSION_URL =
        "https://raw.githubusercontent.com/SzeChiaHao/FreeNodesDaily/main/version.json"

    private const val JSDELIVR =
        "https://cdn.jsdelivr.net/gh/SzeChiaHao/FreeNodesDaily@main/version.json"

    /**
     * 取 version.json 的候选地址，顺序讲究：直连 raw 最新，然后是实时透传的代理，
     * jsDelivr 有缓存（可能比仓库旧好几小时），只当最后兜底。
     */
    private fun versionUrls(): List<String> = listOf(
        VERSION_URL,
        "https://gh-proxy.com/$VERSION_URL",
        "https://ghproxy.net/$VERSION_URL",
        "https://ghfast.top/$VERSION_URL",
        JSDELIVR,
        JSDELIVR.replace("cdn.jsdelivr.net", "fastly.jsdelivr.net")
    )

    /** 下载 APK 时的镜像前缀兜底 */
    private val DOWNLOAD_PROXIES = listOf("https://gh-proxy.com/", "https://ghproxy.net/")

    data class Release(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val size: Long,
        val sha256: String,
        val notes: String,
        val publishedAt: String
    )

    // ------------------------------------------------------------- 版本比较

    /** 把 "1.10.2" 拆成 [1,10,2] 后逐段比大小；非数字段按 0 处理。 */
    fun compareVersionNames(a: String, b: String): Int {
        val x = a.trim().removePrefix("v").split('.', '-', '_')
        val y = b.trim().removePrefix("v").split('.', '-', '_')
        for (i in 0 until maxOf(x.size, y.size)) {
            val xi = x.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val yi = y.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (xi != yi) return if (xi > yi) 1 else -1
        }
        return 0
    }

    /** 线上版本是否比本机新。优先比 versionCode，拿不到再比版本号。 */
    fun isNewer(latest: Release, currentName: String, currentCode: Int): Boolean {
        if (latest.versionCode > 0 && currentCode > 0) return latest.versionCode > currentCode
        return compareVersionNames(latest.versionName, currentName) > 0
    }

    // ------------------------------------------------------------- 解析

    fun parse(json: String): Release? = try {
        // 注意：记着去掉 BOM。PowerShell 的 Set-Content -Encoding UTF8 会写入 BOM，
        // 而 JSONObject 见到 BOM 直接抛异常；这个坑真的踩过一次。
        val o = JSONObject(json.trim().removePrefix("﻿").trim())
        val apk = o.optString("apk", "")
        val name = o.optString("versionName", "")
        if (apk.isEmpty() || name.isEmpty()) {
            null
        } else {
            Release(
                versionName = name,
                versionCode = o.optInt("versionCode", 0),
                apkUrl = apk,
                size = o.optLong("size", 0L),
                sha256 = o.optString("sha256", ""),
                notes = o.optString("notes", ""),
                publishedAt = o.optString("publishedAt", "")
            )
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 拉取更新信息。直连 raw.githubusercontent 优先，失败再走镜像
     * （镜像有缓存，可能比直连旧，所以只当兜底）。
     */
    fun fetchLatest(): Release? {
        val fetcher = Fetcher(timeoutMs = 8000)
        for (url in versionUrls()) {
            val text = fetcher.getOnce(url) ?: continue
            val r = parse(text)
            if (r != null) return r
        }
        return null
    }

    // ------------------------------------------------------------- 下载

    /** 下载 APK 到 cacheDir，返回文件；失败返回 null。 */
    fun download(ctx: Context, release: Release, onProgress: (Long, Long) -> Unit): File? {
        val dir = File(ctx.cacheDir, "updates")
        val dest = File(dir, "Walls-${release.versionName}.apk")
        return if (downloadTo(dest, release, onProgress)) dest else null
    }

    /**
     * 依次尝试「直连 + 镜像」把安装包下到 dest，并核对 sha256。
     * 和 Android 无关，方便在 JVM 上直接验证整条下载链路。
     */
    fun downloadTo(dest: File, release: Release, onProgress: (Long, Long) -> Unit): Boolean {
        dest.parentFile?.mkdirs()
        if (dest.exists() && release.sha256.isNotEmpty() && sha256(dest) == release.sha256) {
            return true // 上次已经下好了
        }
        dest.delete()

        val candidates = ArrayList<String>()
        candidates.add(release.apkUrl)
        for (p in DOWNLOAD_PROXIES) candidates.add(p + release.apkUrl)

        for (url in candidates) {
            if (tryDownload(url, dest, release.size, onProgress)) {
                if (release.sha256.isEmpty() || sha256(dest) == release.sha256) return true
                Log.add("下载的安装包校验值不对，换下一个地址")
                dest.delete()
            }
        }
        return false
    }

    private fun tryDownload(
        url: String,
        dest: File,
        total: Long,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", Fetcher.UA)
                setRequestProperty("Accept", "application/octet-stream")
            }
            if (conn.responseCode !in 200..299) return false
            val length = if (total > 0) total else conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { inp ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, length)
                    }
                }
            }
            done > 100_000
        } catch (e: Exception) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun sha256(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(65536)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (e: Exception) {
        ""
    }

    // ------------------------------------------------------------- 安装

    /** 本机是否允许这个 App 安装其它应用（Android 8+ 需要）。 */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** 跳到「安装未知应用」的设置页，让用户给本 App 放行。 */
    fun openInstallPermissionSettings(ctx: Context) {
        try {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${ctx.packageName}"))
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            Log.add("打不开安装权限设置页：${e.message}")
        }
    }

    /** 调系统安装器安装已经下好的 APK。返回 null 表示已发起，否则是失败原因。 */
    fun install(ctx: Context, apk: File): String? {
        if (!apk.exists() || apk.length() < 100_000) return "安装包不完整"
        if (!canInstall(ctx)) return "需要先允许「安装未知应用」"
        return try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
