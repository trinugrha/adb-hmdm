package co.sat.hmdminstaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Foreground service: menjaga proses aplikasi tetap hidup selama ada koneksi ADB.
 * Berhenti sendiri bila koneksi sudah tidak ada.
 */
class ConnectionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            try {
                val adb = (application as App).adb
                if (!adb.isConnected) { stopSelf(); return }
                handler.postDelayed(this, 5_000)
            } catch (e: Throwable) { stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HMDM Installer")
            .setContentText("Koneksi ADB ke HP target aktif")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        } catch (e: Throwable) {
            // Mis. larangan foreground service saat di background: jangan sampai mematikan aplikasi.
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(check)
        handler.postDelayed(check, 5_000)
        return START_NOT_STICKY
    }

    // Aplikasi digeser dari recent apps: putuskan koneksi ADB, lepas semua resource, lalu matikan service.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val adb = (application as App).adb
        Thread {
            try { adb.close() } catch (e: Throwable) { }
            // Daftar APK dipulihkan dari cache/picked saat aplikasi dibuka; hapus agar daftar kosong lagi.
            try { java.io.File(cacheDir, "picked").deleteRecursively() } catch (e: Throwable) { }
            handler.post { stopSelf() }
        }.start()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Koneksi ADB", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val CHANNEL = "adb_connection"
        private const val ID = 1001
        fun start(ctx: Context) {
            val i = Intent(ctx, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ConnectionService::class.java)) }
    }
}
