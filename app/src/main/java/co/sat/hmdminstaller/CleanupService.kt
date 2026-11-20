package co.sat.hmdminstaller

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/**
 * Service ringan (non-foreground) yang hanya menunggu aplikasi digeser dari recent apps.
 * Saat itu terjadi: putuskan koneksi ADB dan hapus daftar APK yang dipilih.
 * Dijalankan dari MainActivity, jadi berlaku juga ketika belum terhubung ke device.
 */
class CleanupService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = application as App
        Thread {
            try { app.adb.close() } catch (e: Throwable) { }
            try { File(cacheDir, "picked").deleteRecursively() } catch (e: Throwable) { }
            stopSelf()
        }.start()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
