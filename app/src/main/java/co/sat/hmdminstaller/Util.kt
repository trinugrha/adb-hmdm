package co.sat.hmdminstaller

import android.content.Context
import java.io.File

/**
 * Jalankan [block] dan abaikan semua kegagalannya. Untuk pembersihan "best effort"
 * (menutup soket, melepas receiver, dst) yang tidak boleh sampai mematikan aplikasi.
 */
inline fun quietly(block: () -> Unit) {
    try { block() } catch (ignored: Throwable) { }
}

/** Nama sub-folder cache tempat APK pilihan pengguna disalin. */
private const val PICKED_DIR = "picked"

/** Folder cache tempat APK pilihan pengguna disalin. */
fun Context.pickedApksDir(): File = File(cacheDir, PICKED_DIR)

/** Hapus semua APK pilihan yang sudah disalin ke cache. */
fun Context.clearPickedApks() = quietly { pickedApksDir().deleteRecursively() }
