package com.szech.walls.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.szech.walls.core.Log
import com.szech.walls.pipeline.Runner
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo

/** 每天自动跑一次「抓取 → 测速 → 排序 → 生成订阅」。 */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        Prefs.init(applicationContext)
        Repo.init(applicationContext)
        Log.add("（后台任务）开始每日更新")
        return try {
            val res = Runner.runNow()
            Log.add("（后台任务）每日更新结束：入选 ${res.nodes.size} 个节点")
            if (res.nodes.isEmpty()) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.add("（后台任务）出错：${e.message}")
            Result.retry()
        }
    }
}
