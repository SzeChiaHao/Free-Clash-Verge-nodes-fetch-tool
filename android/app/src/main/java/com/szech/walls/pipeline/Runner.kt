package com.szech.walls.pipeline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.szech.walls.core.Log
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo
import com.szech.walls.store.RunStatus
import com.szech.walls.work.DailyWorker
import java.util.concurrent.TimeUnit

/** 把偏好设置变成一次流水线配置，并负责跑完落盘、重启订阅服务。 */
object Runner {

    fun config(): PipelineConfig = PipelineConfig(
        sources = Prefs.sources + Prefs.discovered,
        maxNodes = Prefs.maxNodes,
        speedTop = Prefs.speedTop,
        speedBytes = Prefs.speedBytesKb * 1024,
        minSpeedMbps = Prefs.minSpeedTenths / 10.0,
        keepTop = Prefs.keepTop,
        ssOnly = Prefs.ssOnly,
        labelSpeed = Prefs.labelSpeed,
        quick = Prefs.quick
    )

    suspend fun runNow(
        onLog: (String) -> Unit = { Log.add(it) },
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }
    ): PipelineResult {
        Repo.status = RunStatus(
            lastRunAt = Repo.status.lastRunAt,
            running = true,
            message = "运行中…"
        )
        val t0 = System.currentTimeMillis()
        val res = Pipeline.run(config(), onLog, onProgress)
        val st = RunStatus(
            lastRunAt = System.currentTimeMillis(),
            candidates = res.candidates,
            alive = res.alive,
            measured = res.measured,
            sourcesOk = res.sourcesOk,
            sourcesFail = res.sourcesFail,
            kept = res.nodes.size,
            message = if (res.nodes.isEmpty()) "本次没有可用节点，保留上次结果" else "完成，用时 ${
                (System.currentTimeMillis() - t0) / 1000
            } 秒",
            running = false
        )
        if (res.nodes.isNotEmpty()) {
            Repo.setResult(res.nodes, st)
        } else {
            Repo.setResult(Repo.nodes, st)
        }
        return res
    }

    // ------------------------------------------------------------ 定时任务

    private const val WORK_NAME = "walls-daily"

    fun schedule(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }
}
