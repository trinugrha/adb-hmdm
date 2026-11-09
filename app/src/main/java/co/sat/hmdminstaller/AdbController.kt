package co.sat.hmdminstaller

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Satu APK di daftar: file hasil pilihan user (disalin ke cache). */
data class ApkItem(val name: String, val file: File, val builtIn: Boolean = false) {
    val sizeText: String
        get() {
            val n = file.length()
            return when {
                n >= 1048576 -> "%.1f MB".format(n / 1048576.0)
                n >= 1024 -> "${n / 1024} KB"
                else -> "$n B"
            }
        }
}

/** Status device owner di HP target. */
sealed class OwnerState {
    /** Belum terhubung / belum dicek / gagal dicek. */
    object Unknown : OwnerState()
    /** Tidak ada device owner. */
    object None : OwnerState()
    /** HMDM (com.hmdm.launcher) adalah device owner. */
    data class Hmdm(val pkg: String) : OwnerState()
    /** Aplikasi lain yang menjadi device owner. */
    data class Other(val pkg: String) : OwnerState()
}

/**
 * Padanan fungsi ADB di versi Windows: pair, connect, disconnect,
 * install -r, dan dpm set-device-owner. Dipanggil dari thread latar.
 */
class AdbController(context: Context, private val log: (String) -> Unit) {

    companion object {
        const val OWNER_COMPONENT = "com.hmdm.launcher/.AdminReceiver"
        const val KASPERSKY_PACKAGE = "com.kaspersky.kes"
    }

    private val appContext = context.applicationContext
    @Volatile private var mgr: AdbManager? = null

    /** Dibuat saat pertama dipakai (pembuatan kunci RSA bisa lambat), aman dipanggil dari banyak thread. */
    private val manager: AdbManager
        get() = mgr ?: synchronized(this) { mgr ?: AdbManager(appContext).also { mgr = it } }

    /** Panggil dari thread latar saat aplikasi dibuka agar kunci siap sebelum dipakai. */
    fun warmUp() {
        try { manager } catch (e: Throwable) { log("Peringatan: gagal menyiapkan kunci ADB: ${e.message ?: e.javaClass.simpleName}") }
    }

    /** Dipakai agar probe dari thread pemantau tidak tumpang tindih dengan operasi di thread kerja. */
    val probeLock = Any()

    /** Tutup koneksi & lepas resource (dipanggil saat Activity dihancurkan). */
    fun close() {
        val m = mgr ?: return
        try { m.disconnect() } catch (e: Throwable) { }
        try { m.close() } catch (e: Throwable) { }
    }

    val isConnected: Boolean
        get() = try { mgr?.isConnected ?: false } catch (e: Throwable) { false }

    /**
     * Cek ringan apakah koneksi benar-benar hidup (bukan sekadar flag): jalankan `echo` di device.
     * Dipanggil dari thread pemantau; stream ditutup paksa bila tidak membalas dalam 4 detik.
     */
    fun probe(): Boolean {
        if (!isConnected) return false
        var stream: io.github.muntashirakon.adb.AdbStream? = null
        val watchdog = Thread {
            try { Thread.sleep(4_000); stream?.close() } catch (e: InterruptedException) { } catch (e: Throwable) { }
        }.apply { isDaemon = true; start() }
        return try {
            val s = manager.openStream("shell:echo ok")
            stream = s
            readAll(s.openInputStream()).contains("ok")
        } catch (e: Throwable) {
            false
        } finally {
            watchdog.interrupt()
            try { stream?.close() } catch (e: Throwable) { }
        }
    }

    /**
     * Cek siapa device owner di HP target (tanpa menulis log). Dipanggil dari thread latar.
     * Memakai `dpm list-owners`; bila tidak didukung, fallback ke `dumpsys device_policy`.
     */
    fun queryDeviceOwner(): OwnerState {
        if (!isConnected) return OwnerState.Unknown
        return try {
            // Quiet: ini cek latar belakang, jangan memenuhi log dengan peringatan timeout.
            val a = shell("dpm list-owners", 5_000, quiet = true)
            parseOwner(a)?.let { return it }
            // Bila dpm menggantung (device_policy sibuk), dumpsys hampir pasti ikut menggantung: jangan tunggu lagi.
            if (lastShellTimedOut && a.isBlank()) return OwnerState.Unknown
            val b = shell("dumpsys device_policy", 6_000, quiet = true)
            parseOwner(b)?.let { return it }
            if (a.isBlank() && b.isBlank()) OwnerState.Unknown else OwnerState.None
        } catch (e: Throwable) {
            OwnerState.Unknown
        }
    }

    /** Ambil package device owner dari output; null bila tidak ada penanda device owner. */
    private fun parseOwner(out: String): OwnerState? {
        val adminRe = Regex("admin=(?:ComponentInfo\\{)?([\\w.]+)/")
        val markerRe = Regex("device\\s*owner", RegexOption.IGNORE_CASE)
        // 1) `dpm list-owners`: satu baris berisi admin=pkg/.Cls dan penanda DeviceOwner.
        var pkg: String? = null
        for (line in out.lineSequence()) {
            if (markerRe.containsMatchIn(line)) {
                pkg = adminRe.find(line)?.groupValues?.get(1)
                if (pkg != null) break
            }
        }
        // 2) `dumpsys device_policy`: header "Device Owner:" lalu detail di baris-baris berikutnya.
        if (pkg == null) {
            val m = Regex("device\\s+owner\\s*:", RegexOption.IGNORE_CASE).find(out) ?: return null
            val tail = out.substring(m.range.first, minOf(out.length, m.range.first + 400))
            pkg = Regex("ComponentInfo\\{([^/}\\s]+)/").find(tail)?.groupValues?.get(1)
                ?: adminRe.find(tail)?.groupValues?.get(1)
                ?: Regex("package=([\\w.]+)").find(tail)?.groupValues?.get(1)
                ?: return null
        }
        val hmdmPkg = OWNER_COMPONENT.substringBefore('/')
        return if (pkg == hmdmPkg) OwnerState.Hmdm(pkg) else OwnerState.Other(pkg)
    }

    fun pair(addr: String, code: String): Boolean {
        if (addr.isEmpty() || code.isEmpty()) {
            log("Isi IP dan Port pairing dan kode pairing terlebih dahulu.")
            return false
        }
        val (host, port) = split(addr) ?: run { log("Alamat pairing tidak valid: $addr"); return false }
        log("> adb pair $addr ******")
        return try {
            // Ditampung sebagai Any? agar aman baik pair() mengembalikan Boolean maupun void.
            val result: Any? = manager.pair(host, port, code)
            if (result == false) {
                log("Pairing gagal: ditolak oleh device (cek kode / port pairing).")
                false
            } else {
                log("Successfully paired to $addr")
                true
            }
        } catch (e: Throwable) {
            log("Pairing gagal: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun connect(addr: String): Boolean {
        if (addr.isEmpty()) {
            log("Isi IP dan Port connect terlebih dahulu.")
            return false
        }
        val (host, port) = split(addr) ?: run { log("Alamat connect tidak valid: $addr"); return false }
        // connect() di library langsung "sukses" bila masih ada koneksi lama (mis. ke IP/port lain),
        // jadi putuskan dulu agar benar-benar menyambung ke alamat yang diminta.
        if (isConnected) {
            try { manager.disconnect() } catch (e: Throwable) { }
        }
        var ok = false
        for (attempt in 1..2) {
            if (ok) break
            if (attempt > 1) {
                log("Connect gagal, mencoba lagi (1x)...")
                Thread.sleep(1500)
            }
            log("> adb connect $addr")
            try {
                val result: Any? = manager.connect(host, port)
                if (result == false || !manager.isConnected) {
                    log("Connect gagal: tidak terhubung")
                } else if (probe()) {
                    // Terbukti: device menjawab perintah shell, bukan sekadar flag.
                    ok = true
                    log("connected to $addr")
                } else {
                    log("Connect gagal: device tidak menjawab (belum di-pair / Wireless debugging mati / port salah)")
                    try { manager.disconnect() } catch (e: Throwable) { }
                }
            } catch (e: Throwable) {
                log("Connect gagal: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return ok
    }

    fun disconnect(): Boolean {
        log("> adb disconnect")
        return try {
            manager.disconnect()
            log("disconnected")
            true
        } catch (e: Throwable) {
            log("Disconnect error: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /** Pasang semua APK berurutan; yang gagal tidak menghentikan yang lain. */
    fun install(apks: List<ApkItem>): Boolean {
        if (!ensureConnected()) return false
        if (apks.isEmpty()) {
            log("Daftar APK kosong. Tambahkan APK terlebih dahulu.")
            return false
        }
        var ok = 0
        val failed = StringBuilder()
        apks.forEachIndexed { i, apk ->
            log("")
            log("[${i + 1}/${apks.size}] ${apk.name}")
            if (!apk.file.exists()) {
                log("File tidak ditemukan: ${apk.file}")
                failed.appendLine("  - ${apk.name} (file tidak ditemukan)")
                return@forEachIndexed
            }
            log("> adb install -r ${apk.name}")
            var out = installTry(apk.file)
            if (!out.contains("Success", ignoreCase = true) && isTransient(out) && isConnected) {
                log("Stream tertutup, mencoba lagi (1x)...")
                Thread.sleep(1500)
                out = installTry(apk.file)
            }
            out.lines().filter { it.isNotBlank() }.forEach { log(it.trim()) }
            if (out.contains("Success", ignoreCase = true)) ok++ else failed.appendLine("  - ${apk.name}")
        }
        log("")
        log("Ringkasan install: $ok berhasil, ${apks.size - ok} gagal dari ${apks.size} APK.")
        if (failed.isNotEmpty()) {
            log("Yang gagal:")
            log(failed.toString().trimEnd())
            return false
        }
        return true
    }

    fun setDeviceOwner(): Boolean {
        if (!ensureConnected()) return false
        log("> adb shell dpm set-device-owner $OWNER_COMPONENT")
        val out = try { shell("dpm set-device-owner $OWNER_COMPONENT", doneWhen = { it.contains("Success") }) } catch (e: Throwable) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        out.lines().filter { it.isNotBlank() }.forEach { log(it.trim()) }
        if (!out.contains("Success", ignoreCase = true)) {
            log("Set device owner gagal. Pastikan device baru di-reset dan belum ada akun (Google/lainnya).")
            return false
        }
        return true
    }

    /** Setara `adb shell pm clear com.kaspersky.kes`: hapus semua data aplikasi Kaspersky di device. */
    fun clearKasperskyData(): Boolean {
        if (!ensureConnected()) return false
        log("> adb shell pm clear $KASPERSKY_PACKAGE")
        val out = try { shell("pm clear $KASPERSKY_PACKAGE", doneWhen = pmDone) } catch (e: Throwable) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        out.lines().filter { it.isNotBlank() }.forEach { log(it.trim()) }
        if (!out.contains("Success", ignoreCase = true)) {
            log("Hapus data Kaspersky gagal. Pastikan $KASPERSKY_PACKAGE terpasang di device.")
            return false
        }
        log("Data Kaspersky berhasil dihapus.")
        return true
    }

    /** Setara `adb shell pm uninstall -k --user 0 com.kaspersky.kes`: uninstall untuk user 0, data tetap disimpan (-k). */
    fun uninstallKaspersky(): Boolean {
        if (!ensureConnected()) return false
        log("> adb shell pm uninstall -k --user 0 $KASPERSKY_PACKAGE")
        val out = try { shell("pm uninstall -k --user 0 $KASPERSKY_PACKAGE", doneWhen = pmDone) } catch (e: Throwable) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        out.lines().filter { it.isNotBlank() }.forEach { log(it.trim()) }
        if (!out.contains("Success", ignoreCase = true)) {
            when {
                out.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER") ->
                    log("Device Admin belum dimatikan. Ulangi Langkah 2 (Pengaturan > Keamanan > Aplikasi admin perangkat > Kaspersky > Nonaktifkan).")
                out.contains("DELETE_FAILED_USER_RESTRICTED") || out.contains("OWNER_BLOCKED") ->
                    log("Uninstall diblokir kebijakan Device Owner atau Profile Owner.")
                out.contains("Unknown package", ignoreCase = true) || out.contains("not installed", ignoreCase = true) ->
                    log("Aplikasi tidak ditemukan: Kaspersky sudah tidak terpasang.")
                else -> log("Uninstall Kaspersky gagal. Pastikan $KASPERSKY_PACKAGE terpasang di device.")
            }
            return false
        }
        log("Kaspersky berhasil di-uninstall.")
        return true
    }

    fun installAndOwner(apks: List<ApkItem>): Boolean {
        // Walau sebagian APK gagal, tetap coba set device owner (HMDM mungkin sudah terpasang).
        val installOk = install(apks)
        val ownerOk = setDeviceOwner()
        return installOk && ownerOk
    }

    // ---------- internal ----------

    private fun ensureConnected(): Boolean {
        if (isConnected) return true
        log("Belum terhubung ke device. Klik Connect terlebih dahulu.")
        return false
    }

    /** Hasil `pm` dianggap lengkap setelah satu baris Success / Failure / Error selesai dibaca. */
    private val pmDone: (String) -> Boolean = { out ->
        out.endsWith("\n") && (out.contains("Success") || out.contains("Failure") || out.contains("Error"))
    }

    @Volatile private var lastShellTimedOut = false

    /**
     * Jalankan `shell:<cmd>` dengan batas waktu. Output dikumpulkan bertahap; bila [doneWhen]
     * terpenuhi, pembacaan dihentikan tanpa menunggu EOF (device kadang tidak segera menutup
     * stream walau perintah sudah selesai dan "Success" sudah tercetak).
     * Peringatan timeout hanya dicatat bila memang belum ada output sama sekali.
     */
    private fun shell(
        cmd: String,
        timeoutMs: Long = 15_000,
        quiet: Boolean = false,
        doneWhen: ((String) -> Boolean)? = null
    ): String {
        val bos = ByteArrayOutputStream()
        val done = java.util.concurrent.CountDownLatch(1)
        var stream: io.github.muntashirakon.adb.AdbStream? = null
        var error: Throwable? = null
        Thread {
            try {
                val s = manager.openStream("shell:$cmd")
                stream = s
                val input = s.openInputStream()
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        bos.write(buf, 0, n)
                        if (doneWhen != null && doneWhen(bos.toString("UTF-8"))) break
                    }
                } catch (e: java.io.IOException) {
                    // Stream ditutup device = akhir output yang normal.
                }
            } catch (e: Throwable) {
                error = e
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true; start() }

        val finished = done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        lastShellTimedOut = !finished
        try { stream?.close() } catch (e: Throwable) { }
        if (!finished) {
            done.await(1, java.util.concurrent.TimeUnit.SECONDS)
            if (!quiet && bos.size() == 0) {
                log("Peringatan: perintah '$cmd' tidak selesai dalam ${timeoutMs / 1000} detik, dilanjutkan.")
            }
        }
        error?.let { throw it }
        return bos.toString("UTF-8")
    }

    private fun installTry(file: File): String =
        try { installOne(file) } catch (e: Throwable) { "Error: ${e.message ?: e.javaClass.simpleName}" }

    /** Kegagalan tanpa pesan dari pm (stream putus), bukan penolakan install seperti INSTALL_FAILED_*. */
    private fun isTransient(out: String): Boolean =
        out.isBlank() || (out.startsWith("Error:") && !out.contains("INSTALL_", ignoreCase = true))

    /** Setara `adb install -r`: stream APK ke `cmd package install -S <ukuran>` lewat service exec. */
    private fun installOne(file: File): String {
        val stream = manager.openStream("exec:cmd package install -r -S ${file.length()}")
        // Pengaman: tutup stream bila tidak selesai dalam 5 menit.
        val watchdog = Thread {
            try { Thread.sleep(300_000); stream.close() } catch (e: InterruptedException) { } catch (e: Throwable) { }
        }.apply { isDaemon = true; start() }
        try {
            var writeError: java.io.IOException? = null
            try {
                val os = stream.openOutputStream()
                file.inputStream().use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                    }
                }
                os.flush()
            } catch (e: java.io.IOException) {
                // Device menutup stream lebih awal (mis. install ditolak). Tetap baca pesan aslinya.
                writeError = e
            }
            val out = readAll(stream.openInputStream())
            if (out.isBlank() && writeError != null) {
                return "Error: ${writeError.message ?: "stream closed"}"
            }
            return out
        } finally {
            watchdog.interrupt()
            try { stream.close() } catch (e: Throwable) { }
        }
    }

    /** Baca sampai EOF. Bila stream ditutup device (IOException), kembalikan yang sudah terbaca. */
    private fun readAll(input: InputStream): String {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                bos.write(buf, 0, n)
            }
        } catch (e: java.io.IOException) {
            // "Stream closed" saat EOF itu normal: device menutup stream setelah perintah selesai.
        }
        return bos.toString("UTF-8")
    }

    private fun split(addr: String): Pair<String, Int>? {
        val i = addr.lastIndexOf(':')
        if (i <= 0) return null
        val port = addr.substring(i + 1).toIntOrNull() ?: return null
        return addr.substring(0, i) to port
    }
}
