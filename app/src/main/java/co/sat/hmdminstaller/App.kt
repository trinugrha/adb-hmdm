package co.sat.hmdminstaller

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

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
        // Wireless debugging memakai TLS 1.3; Conscrypt dibutuhkan oleh libadb-android.
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }
}
