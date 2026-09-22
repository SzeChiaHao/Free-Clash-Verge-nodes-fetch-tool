package com.szech.walls.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.szech.walls.MainActivity
import com.szech.walls.R
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo

/** 常驻前台服务：让本地订阅地址一直可用，客户端随时能拉到最新节点。 */
class SubService : Service() {

    private var server: SubServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Repo.init(this)
        SubServiceHolder.instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        if (server?.isRunning != true) {
            val port = Prefs.port
            val s = SubServer(port)
            val ok = s.start { path, query ->
                val fmt = query["format"]
                if (path == "/sub" && !fmt.isNullOrEmpty()) {
                    SubContent.route("/" + fmt.lowercase())
                } else {
                    SubContent.route(path)
                }
            }
            server = if (ok) s else null
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(NOTIF_ID, buildNotification())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        if (SubServiceHolder.instance === this) SubServiceHolder.instance = null
        super.onDestroy()
    }

    fun isRunning(): Boolean = server?.isRunning == true

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "订阅服务", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持本地订阅服务运行"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val port = Prefs.port
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SubService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = "订阅地址 http://127.0.0.1:$port/ （共 ${com.szech.walls.store.Repo.nodes.size} 个节点）"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("节点管家运行中")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(0, "停止", stop)
            .build()
    }

    companion object {
        const val CHANNEL = "walls_sub_service"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.szech.walls.STOP_SUB"

        fun start(ctx: Context) {
            val i = Intent(ctx, SubService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SubService::class.java))
        }
    }
}
