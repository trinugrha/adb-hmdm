package co.sat.hmdminstaller

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Sisi berkas dari daftar APK pilihan: membaca nama file, menyalin ke cache, memulihkan salinan lama, dan
 * membaca nama paket APK. Tidak menyentuh tampilan. Salin dan baca paket dipanggil dari thread latar.
 */
internal class ApkStore(context: Context) {

    private val appContext = context.applicationContext

    /** Cache nama paket per file APK (kunci: path + ukuran + waktu ubah) agar file tidak di-parse berulang. */
    private val pkgCache = ConcurrentHashMap<String, String>()

    private fun key(f: File) = "${f.absolutePath}|${f.length()}|${f.lastModified()}"

    /** Nama paket (mis. com.hmdm.launcher) dari file APK; "" bila tidak terbaca. */
    fun packageName(item: ApkItem): String {
        val f = item.file
        return pkgCache.getOrPut(key(f)) {
            try {
                appContext.packageManager.getPackageArchiveInfo(f.absolutePath, 0)?.packageName ?: ""
            } catch (e: Throwable) { "" }
        }
    }

    /** Hanya membaca cache (aman di UI thread); cache diisi di thread latar oleh [packageName]. */
    fun cachedPackage(item: ApkItem): String? = pkgCache[key(item.file)]

    /** Nama file dari [uri]; bila tidak terbaca dipakai nama sementara. */
    fun displayName(uri: Uri): String {
        var name = "file-${System.currentTimeMillis()}.apk"
        try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (e: Exception) { }
        // Cegah path traversal dari nama file yang aneh.
        return name.substringAfterLast('/')
    }

    /** Salin ke cache. Bila gagal, file setengah jadi dihapus, [onFail] dipanggil, dan hasilnya null. */
    fun copyToCache(uri: Uri, name: String, onFail: (Exception) -> Unit): ApkItem? {
        val dir = appContext.pickedApksDir().apply { mkdirs() }
        val f = File(dir, name)
        return try {
            val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("tidak bisa membuka file")
            input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            ApkItem(name, f)
        } catch (e: Exception) {
            f.delete() // jangan tinggalkan file setengah jadi
            onFail(e)
            null
        }
    }

    /** APK yang sudah disalin ke cache sebelumnya (mis. setelah proses aplikasi dimatikan sistem), urut nama. */
    fun restore(): List<ApkItem> {
        val files = appContext.pickedApksDir()
            .listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) } ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.map { ApkItem(it.name, it) }
    }
}
