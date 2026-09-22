package com.szech.walls.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 更新通道的真机验证：读线上 version.json → 比版本 → 下载安装包 → 核对 sha256。
 * 默认不跑（要联网），加参数才执行：
 *
 *   gradlew testDebugUnitTest -Dwalls.liveUpdate=1
 *
 * 装到手机上之前先在这里过一遍，能确认「发布 → 手机看到 → 下得下来」这条链是通的；
 * 唯一的盲区是最后那步「调系统安装器」，那个只能在真机上点。
 */
class UpdaterLiveTest {

    @Test
    fun `线上 version_json 可读、可下载、校验一致`() {
        if (System.getProperty("walls.liveUpdate") == null) {
            println("[skip] 未指定 -Dwalls.liveUpdate=1，跳过更新通道实测")
            return
        }

        // 1) 版本比较（离线也能跑，这里顺带一起验）
        assertEquals(0, Updater.compareVersionNames("1.3", "1.3"))
        assertEquals(1, Updater.compareVersionNames("1.10", "1.9"))
        assertEquals(-1, Updater.compareVersionNames("1.9", "1.10"))
        assertEquals(1, Updater.compareVersionNames("v2.0.1", "2.0"))
        assertEquals(0, Updater.compareVersionNames("1.3", "1.3.0"))

        // 2) 拉线上版本信息
        val rel = Updater.fetchLatest()
        assertNotNull("拿不到 version.json（网络问题，或者仓库里还没有这个文件）", rel)
        rel!!
        println("线上版本：v${rel.versionName} (versionCode ${rel.versionCode})")
        println("   安装包：${rel.apkUrl}")
        println("   大小：${rel.size} 字节，sha256=${rel.sha256}")
        println("   更新说明：${rel.notes.take(120).replace("\n", " / ")}")
        assertTrue("versionName 不能为空", rel.versionName.isNotEmpty())
        assertTrue("apk 地址应当是 https 的公开下载地址", rel.apkUrl.startsWith("https://"))
        assertTrue("versionCode 应当大于 0", rel.versionCode > 0)

        // 3) 比版本：比线上旧的应当提示更新，比线上新的（或相同）不提示
        assertTrue("低版本应当提示更新", Updater.isNewer(rel, "1.0", rel.versionCode - 1))
        assertTrue("同版本不应当提示更新", !Updater.isNewer(rel, rel.versionName, rel.versionCode))
        assertTrue("更高版本不应当提示更新", !Updater.isNewer(rel, "99.0", rel.versionCode + 1))

        // 4) 真下载一遍，核对 sha256 与文件头
        val dest = File(System.getProperty("java.io.tmpdir"), "walls-update-check.apk")
        dest.delete()
        val ok = Updater.downloadTo(dest, rel) { done, total ->
            if (total > 0 && done / (512 * 1024) != (done - 1).coerceAtLeast(0) / (512 * 1024)) {
                print("  ${done * 100 / total}%")
            }
        }
        println()
        assertTrue("安装包下载失败", ok)
        assertTrue("下载下来的文件太小：${dest.length()}", dest.length() > 1_000_000)
        if (rel.size > 0) assertEquals("文件大小与 version.json 不一致", rel.size, dest.length())
        assertEquals("sha256 与 version.json 不一致", rel.sha256, Updater.sha256(dest))
        val magic = dest.inputStream().use { val b = ByteArray(2); it.read(b); String(b, Charsets.ISO_8859_1) }
        assertEquals("下载的应当是 zip(apk) 文件", "PK", magic)
        println("下载校验通过：${dest.absolutePath}（${dest.length() / 1048576} MB）")
        dest.delete()
    }
}
