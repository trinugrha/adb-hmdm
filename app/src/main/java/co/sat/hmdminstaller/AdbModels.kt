package co.sat.hmdminstaller

import java.io.File

/** Satu APK di daftar: file hasil pilihan user (disalin ke cache). */
data class ApkItem(val name: String, val file: File, val builtIn: Boolean = false) {
    val sizeText: String
        get() = Fmt.size(file.length())
}

/**
 * Status tiga permission com.hmdm.launcher di HP target. Tiap nilai: true = aktif, false = belum aktif,
 * null = tidak terbaca (mis. timeout).
 */
data class HmdmPermissions(
    /** Permission to install as device administrator (admin perangkat aktif). */
    val admin: Boolean?,
    /** Permission to overlay on top of other windows (SYSTEM_ALERT_WINDOW). */
    val overlay: Boolean?,
    /** Permission to access the use of history (akses penggunaan / GET_USAGE_STATS). */
    val usage: Boolean?
) {
    val allActive: Boolean get() = admin == true && overlay == true && usage == true
}

/** Status device owner di HP target. */
sealed class OwnerState {
    /** Belum terhubung / belum dicek / gagal dicek. */
    object Unknown : OwnerState()
    /** Tidak ada device owner (atau bukan HMDM). */
    object None : OwnerState()
    /** HMDM (com.hmdm.launcher) adalah device owner. */
    data class Hmdm(val pkg: String) : OwnerState()
}

/** Status launcher (home) default di HP target. */
sealed class HomeState {
    /** Belum terhubung / belum dicek / gagal dicek. */
    object Unknown : HomeState()
    /** Belum ada launcher default (Android akan menampilkan pilihan launcher). */
    object None : HomeState()
    /** HMDM launcher adalah default. */
    data class Hmdm(val component: String) : HomeState()
    /** Launcher lain yang menjadi default. */
    data class Other(val component: String) : HomeState()
}
