package co.sat.hmdminstaller

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Looper
import android.util.Log
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security
import java.util.Date

class App : Application() {

    /** Pendengar log milik Activity yang sedang tampil (null bila Activity tidak ada). */
    @Volatile var logListener: ((LogLevel, String) -> Unit)? = null

    /**
     * Koneksi ADB hidup di level aplikasi, BUKAN di Activity. Dengan begitu koneksi tidak ikut putus
     * ketika Activity ditutup / dibuat ulang; koneksi hanya putus bila user menekan Disconnect,
     * HP target memutus, atau proses benar-benar dimatikan.
     */
    val adb: AdbController by lazy {
        AdbController(this) { level, line -> logListener?.invoke(level, line) }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashGuard()
        watchWifi()
        // Wireless debugging memakai TLS 1.3; Conscrypt dibutuhkan oleh libadb-android.
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }

    /**
     * Pengaman crash. Saat Wi-Fi putus, soket ADB mati dan thread (termasuk thread internal library ADB)
     * bisa melempar exception yang tidak tertangkap; itu dulu langsung mematikan aplikasi.
     * - Thread latar: exception dicatat saja, proses tetap hidup (koneksi dianggap putus oleh pemantau).
     * - Main thread: jejaknya disimpan ke file lalu crash diteruskan seperti biasa (ditampilkan di log saat dibuka lagi).
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val mainThread = Looper.getMainLooper().thread
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, CRASH_FILE).writeText("${Date()}  thread=${t.name}\n${Log.getStackTraceString(e)}")
            } catch (ignored: Throwable) { }
            if (t === mainThread) {
                previous?.uncaughtException(t, e)
            } else {
                // Tanpa ": " menggantung bila exception tidak punya pesan.
                val detail = e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
                try { logListener?.invoke(LogLevel.WARN, "Kesalahan di thread '${t.name}': ${e.javaClass.simpleName}$detail") } catch (ignored: Throwable) { }
            }
        }
    }

    /** Wi-Fi hilang = sesi ADB wireless pasti mati: tutup lebih awal, tanpa menunggu timeout soket. */
    private fun watchWifi() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    try { adb.onWifiLost() } catch (ignored: Throwable) { }
                }
            })
        } catch (ignored: Throwable) { }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
