package site.devstyle.aiserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class ServerService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("devstyle_server", "DEV STYLE AI Server", NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(this, "devstyle_server")
                .setContentTitle("DEV STYLE AI Server")
                .setContentText("Local AI service is running")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true).build()
            startForeground(81, notification)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
