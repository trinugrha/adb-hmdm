package co.sat.hmdminstaller

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
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
class AdbController(context: Context, private val sink: (LogLevel, String) -> Unit) {

    private fun logStep(s: String) = sink(LogLevel.STEP, s)
    private fun logCmd(s: String) = sink(LogLevel.CMD, s)
    private fun logOk(s: String) = sink(LogLevel.OK, s)
    private fun logWarn(s: String) = sink(LogLevel.WARN, s)
    private fun logErr(s: String) = sink(LogLevel.ERR, s)
    private fun logDetail(s: String) = sink(LogLevel.DETAIL, s)
    private fun logSummary(s: String) = sink(LogLevel.SUMMARY, s)

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
        try { manager } catch (e: Throwable) { logWarn("Kunci ADB belum siap: ${e.message ?: e.javaClass.simpleName}") }
    }

    /** Dipakai agar probe dari thread pemantau tidak tumpang tindih dengan operasi di thread kerja. */
    val probeLock = Any()

    /** Laporan kemajuan install: (nomor APK, jumlah APK, nama, byte terkirim, ukuran file). Dipanggil dari thread kerja. */
    @Volatile var onProgress: ((Int, Int, String, Long, Long) -> Unit)? = null
    @Volatile private var curIdx = 0
    @Volatile private var curTotal = 0
    @Volatile private var curName = ""

    // ---- Cegah Wi-Fi / CPU HP teknisi tidur saat operasi panjang (install APK besar) ----
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Tahan Wi-Fi (performa tinggi) + CPU tetap aktif selama operasi berjalan. Panggil dengan false setelah selesai. */
    @Synchronized
    fun holdLocks(on: Boolean) {
        try {
            if (on) {
                if (wifiLock == null) {
                    val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                               else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wifiLock = wm.createWifiLock(mode, "hmdm:adb").apply { setReferenceCounted(false) }
                }
                if (wakeLock == null) {
                    val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hmdm:adb").apply { setReferenceCounted(false) }
                }
                if (wifiLock?.isHeld == false) wifiLock?.acquire()
                if (wakeLock?.isHeld == false) wakeLock?.acquire(30 * 60 * 1000L)
            } else {
                if (wifiLock?.isHeld == true) wifiLock?.release()
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        } catch (e: Throwable) { }
    }

    /** Alamat connect terakhir yang berhasil, dipakai untuk sambung ulang otomatis bila koneksi putus saat install. */
    @Volatile private var lastAddr: String? = null
    @Volatile private var viaUsb = false

    private val usbBridge by lazy { UsbAdbBridge(appContext) }

    /** Daftar HP target di port USB (setara `adb devices`). Ringan, aman dipanggil dari UI thread. */
    fun listUsbTargets(): List<UsbAdbBridge.Target> =
        try { usbBridge.findTargets() } catch (e: Throwable) { emptyList() }

    /** Setara `adb connect` lewat kabel USB ke perangkat yang dipilih. Panggil dari thread latar. Mengembalikan label, atau null bila gagal. */
    fun connectUsb(t: UsbAdbBridge.Target): String? {
        logCmd("adb -d usb (USB debugging)")
        if (!listUsbTargets().any { it.device.deviceName == t.device.deviceName }) {
            logErr("Perangkat ${t.label} sudah tidak terpasang. Colok ulang kabel lalu pilih lagi.")
            return null
        }
        logStep("Perangkat USB: ${t.label}")
        if (!usbBridge.hasPermission(t.device)) logWarn("Izinkan akses USB pada dialog yang muncul di layar HP ini.")
        if (!usbBridge.requestPermission(t.device)) {
            logErr("Izin akses USB ditolak atau tidak dijawab.")
            return null
        }
        val port = try { usbBridge.start(t.device) } catch (e: Throwable) {
            logErr("Gagal membuka USB: ${e.message ?: e.javaClass.simpleName}")
            return null
        }
        logWarn("Bila muncul dialog 'Izinkan USB debugging?' di HP target: centang 'Selalu izinkan' lalu OK.")
        val label = "USB · ${t.label}"
        // Percobaan pertama bisa habis waktu selagi dialog di HP target belum disetujui; coba lagi setelah itu.
        var ok = false
        for (attempt in 1..3) {
            if (connect("127.0.0.1:$port", verbose = false)) { ok = true; break }
            if (attempt < 3) logWarn("Percobaan $attempt/3 belum berhasil ($lastConnectReason). Setujui dialog di HP target, lalu mencoba lagi...")
        }
        if (!ok) {
            usbBridge.stop()
            viaUsb = false
            logErr("Connect USB gagal: pastikan USB debugging aktif dan dialog otorisasi di HP target disetujui.")
            return null
        }
        viaUsb = true
        return label
    }

    /** True bila koneksi lewat USB tetapi kabel sudah dicabut / perangkat hilang dari port. */
    val usbCableGone: Boolean
        get() = viaUsb && !usbBridge.isRunning

    /** Bersihkan sisa koneksi USB setelah kabel dicabut (tanpa menulis log). */
    fun dropUsb() {
        lastAddr = null
        try { usbBridge.stop() } catch (e: Throwable) { }
        viaUsb = false
        try { mgr?.disconnect() } catch (e: Throwable) { }
    }

    private fun tryReconnect(): Boolean {
        val addr = lastAddr ?: return false
        logWarn("Koneksi terputus, menyambung ulang ke $addr...")
        repeat(3) { i ->
            if (i > 0) Thread.sleep(2000)
            if (connect(addr)) return true
        }
        if (viaUsb) logErr("Sambung ulang gagal: pastikan kabel USB masih terpasang dan USB debugging di HP target aktif.")
        else logErr("Sambung ulang gagal: pastikan Wireless debugging di HP target masih aktif dan satu jaringan Wi-Fi.")
        return false
    }

    /** Tutup koneksi & lepas resource (dipanggil saat Activity dihancurkan). */
    fun close() {
        holdLocks(false)
        try { usbBridge.stop() } catch (e: Throwable) { }
        val m = mgr ?: return
        try { m.disconnect() } catch (e: Throwable) { }
        try { m.close() } catch (e: Throwable) { }
    }

    val isConnected: Boolean
        get() = try { mgr?.isConnected ?: false } catch (e: Throwable) { false }

    /**
     * Cek ringan apakah koneksi benar-benar hidup (bukan sekadar flag): jalankan `echo` di device.
     * Dipanggil dari thread pemantau; stream ditutup paksa bila tidak membalas dalam 8 detik.
     */
    fun probe(): Boolean {
        if (!isConnected) return false
        var stream: io.github.muntashirakon.adb.AdbStream? = null
        val watchdog = Thread {
            try { Thread.sleep(8_000); stream?.close() } catch (e: InterruptedException) { } catch (e: Throwable) { }
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
            logWarn("Isi IP:Port pairing dan kode pairing terlebih dahulu.")
            return false
        }
        val (host, port) = split(addr) ?: run { logErr("Alamat pairing tidak valid: $addr"); return false }
        logCmd("adb pair $addr ******")
        return try {
            // Ditampung sebagai Any? agar aman baik pair() mengembalikan Boolean maupun void.
            val result: Any? = manager.pair(host, port, code)
            if (result == false) {
                logErr("Pairing gagal: ditolak device (cek kode dan port pairing).")
                false
            } else {
                logOk("Pairing berhasil: $addr")
                true
            }
        } catch (e: Throwable) {
            logErr("Pairing gagal: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    @Volatile private var lastConnectReason = ""

    /** [verbose] false = jangan cetak perintah dan kegagalan akhir (dipakai pemanggil yang mengulang sendiri, mis. USB). */
    fun connect(addr: String, verbose: Boolean = true): Boolean {
        if (addr.isEmpty()) {
            logWarn("Isi IP:Port connect terlebih dahulu.")
            return false
        }
        val (host, port) = split(addr) ?: run { logErr("Alamat connect tidak valid: $addr"); return false }
        // connect() di library langsung "sukses" bila masih ada koneksi lama (mis. ke IP/port lain),
        // jadi putuskan dulu agar benar-benar menyambung ke alamat yang diminta.
        if (isConnected) {
            try { manager.disconnect() } catch (e: Throwable) { }
        }
        if (viaUsb && !addr.startsWith("127.0.0.1:")) { usbBridge.stop(); viaUsb = false }
        val maxAttempts = 2
        var ok = false
        var reason = ""
        if (verbose) logCmd("adb connect $addr")
        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                if (verbose) logWarn("Percobaan ${attempt - 1}/$maxAttempts gagal ($reason), mencoba lagi...")
                Thread.sleep(1500)
            }
            try {
                val result: Any? = manager.connect(host, port)
                if (result == false || !manager.isConnected) {
                    reason = "tidak terhubung"
                } else if (probe()) {
                    // Terbukti: device menjawab perintah shell, bukan sekadar flag.
                    ok = true
                    lastAddr = addr
                    break
                } else {
                    reason = "device tidak menjawab; belum di-pair, Wireless debugging mati, atau port salah"
                    try { manager.disconnect() } catch (e: Throwable) { }
                }
            } catch (e: Throwable) {
                reason = e.message ?: e.javaClass.simpleName
            }
        }
        lastConnectReason = reason
        if (ok) logOk("Terhubung: $addr") else if (verbose) logErr("Connect gagal: $reason")
        return ok
    }

    fun disconnect(): Boolean {
        logCmd("adb disconnect")
        lastAddr = null
        return try {
            manager.disconnect()
            if (viaUsb) { usbBridge.stop(); viaUsb = false }
            logOk("Koneksi diputus.")
            true
        } catch (e: Throwable) {
            logErr("Disconnect gagal: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /** Pasang semua APK berurutan; yang gagal tidak menghentikan yang lain. */
    fun install(apks: List<ApkItem>): Boolean {
        if (!ensureConnected()) return false
        if (apks.isEmpty()) {
            logWarn("Belum ada APK. Pilih file APK terlebih dahulu.")
            return false
        }
        var ok = 0
        val failed = mutableListOf<Pair<String, String>>() // nama APK -> alasan
        apks.forEachIndexed { i, apk ->
            logStep("[${i + 1}/${apks.size}] ${apk.name} (${apk.sizeText})")
            if (!apk.file.exists()) {
                logErr("File tidak ditemukan di penyimpanan sementara.")
                failed += apk.name to "file tidak ditemukan"
                return@forEachIndexed
            }
            if (!isConnected && !tryReconnect()) {
                failed += apk.name to "koneksi terputus"
                return@forEachIndexed
            }
            logCmd("adb install -r ${apk.name}")
            curIdx = i + 1; curTotal = apks.size; curName = apk.name
            onProgress?.invoke(curIdx, curTotal, curName, 0L, apk.file.length())
            val t0 = System.currentTimeMillis()
            var out = installTry(apk.file)
            if (!isSuccess(out) && isTransient(out)) {
                logWarn("Pengiriman terputus, mencoba sekali lagi...")
                if (isConnected) Thread.sleep(1500) else tryReconnect()
                if (isConnected) out = installTry(apk.file)
            }
            if (isSuccess(out)) {
                ok++
                logOk("Terpasang · ${formatDuration(System.currentTimeMillis() - t0)}")
            } else {
                val reason = installFailureReason(out)
                failed += apk.name to reason
                logErr("Gagal: $reason")
                logRaw(out)
            }
        }
        // Satu APK: hasilnya sudah jelas dari baris di atas dan baris penutup, tak perlu ringkasan.
        if (apks.size > 1) {
            logSummary("Ringkasan: $ok berhasil, ${failed.size} gagal dari ${apks.size} APK")
            failed.forEach { (name, reason) -> logErr("$name: $reason") }
        }
        return failed.isEmpty()
    }

    fun setDeviceOwner(): Boolean {
        if (!ensureConnected()) return false
        logCmd("adb shell dpm set-device-owner $OWNER_COMPONENT")
        val out = try { shell("dpm set-device-owner $OWNER_COMPONENT", doneWhen = { it.contains("Success") }) } catch (e: Throwable) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        if (isSuccess(out)) {
            logOk("Device owner aktif: ${OWNER_COMPONENT.substringBefore('/')}")
            return true
        }
        logErr("Set device owner gagal: ${ownerFailureReason(out)}")
        logRaw(out)
        return false
    }

    /** Setara `adb shell pm clear com.kaspersky.kes`: hapus semua data aplikasi Kaspersky di device. */
    fun clearKasperskyData(): Boolean {
        if (!ensureConnected()) return false
        logCmd("adb shell pm clear $KASPERSKY_PACKAGE")
        val out = try { shell("pm clear $KASPERSKY_PACKAGE", doneWhen = pmDone) } catch (e: Throwable) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        if (!isSuccess(out)) {
            logErr("Hapus data Kaspersky gagal: pastikan $KASPERSKY_PACKAGE terpasang di device.")
            logRaw(out)
            return false
        }
        logOk("Data Kaspersky dihapus.")
        return true
    }

    /** Setara `adb shell pm uninstall -k --user 0 com.kaspersky.kes`: uninstall untuk user 0, data tetap disimpan (-k). */
    fun uninstallKaspersky(): Boolean {
        if (!ensureConnected()) return false
        logCmd("adb shell pm uninstall -k --user 0 $KASPERSKY_PACKAGE")
        val out = try { shell("pm uninstall -k --user 0 $KASPERSKY_PACKAGE", doneWhen = pmDone) } catch (e: Throwable) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        if (!isSuccess(out)) {
            val reason = when {
                out.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER") ->
                    "Device Admin belum dimatikan. Ulangi Langkah 2 (Pengaturan > Keamanan > Aplikasi admin perangkat > Kaspersky > Nonaktifkan)."
                out.contains("DELETE_FAILED_USER_RESTRICTED") || out.contains("OWNER_BLOCKED") ->
                    "diblokir kebijakan Device Owner atau Profile Owner."
                out.contains("Unknown package", ignoreCase = true) || out.contains("not installed", ignoreCase = true) ->
                    "Kaspersky sudah tidak terpasang."
                else -> "pastikan $KASPERSKY_PACKAGE terpasang di device."
            }
            logErr("Uninstall Kaspersky gagal: $reason")
            logRaw(out)
            return false
        }
        logOk("Kaspersky di-uninstall.")
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
        logWarn("Belum terhubung ke device. Klik Connect terlebih dahulu.")
        return false
    }

    private fun isSuccess(out: String) = out.contains("Success", ignoreCase = true)

    private fun formatDuration(ms: Long): String =
        if (ms < 1000) "$ms ms" else "%.1f dtk".format(java.util.Locale.US, ms / 1000.0)

    /** Output mentah device sebagai baris rincian (maks. 6 baris) untuk diagnosis. */
    private fun logRaw(out: String, max: Int = 6) {
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("Performing ") }
        lines.take(max).forEach { logDetail(it.take(240)) }
        if (lines.size > max) logDetail("... ${lines.size - max} baris lain tidak ditampilkan")
    }

    private val installReasons = mapOf(
        "INSTALL_FAILED_ALREADY_EXISTS" to "sudah terpasang",
        "INSTALL_FAILED_VERSION_DOWNGRADE" to "versi lebih rendah dari yang terpasang",
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "tanda tangan berbeda dengan versi terpasang (uninstall dulu)",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "penyimpanan HP target penuh",
        "INSTALL_FAILED_INVALID_APK" to "file APK rusak atau tidak valid",
        "INSTALL_FAILED_NO_MATCHING_ABIS" to "arsitektur CPU tidak cocok",
        "INSTALL_FAILED_OLDER_SDK" to "versi Android HP target terlalu lama",
        "INSTALL_FAILED_USER_RESTRICTED" to "install dibatasi kebijakan device",
        "INSTALL_FAILED_TEST_ONLY" to "APK bertanda test-only"
    )

    /** Alasan singkat dari keluaran `cmd package install` yang gagal. */
    private fun installFailureReason(out: String): String {
        val code = Regex("INSTALL_[A-Z_]+").find(out)?.value
        val friendly = code?.let { installReasons[it] }
        return when {
            friendly != null -> "$friendly ($code)"
            code != null -> code
            out.isBlank() -> "tidak ada balasan dari device"
            else -> out.lines().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("Error:")?.trim()?.take(120)
                ?: "tidak diketahui"
        }
    }

    /** Alasan singkat dari keluaran `dpm set-device-owner` yang gagal. */
    private fun ownerFailureReason(out: String): String {
        val l = out.lowercase()
        return when {
            "already has a device owner" in l || "device owner is already set" in l -> "device sudah memiliki device owner."
            "accounts" in l -> "masih ada akun (Google/lainnya) di device. Hapus akun atau reset device."
            "provisioned" in l || "setup" in l -> "device sudah melewati setup awal. Reset ke pengaturan pabrik."
            "unknown admin" in l || "does not exist" in l || "not found" in l -> "HMDM Launcher belum terpasang di device."
            else -> "pastikan device baru di-reset dan belum ada akun (Google/lainnya)."
        }
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
                logWarn("Perintah '$cmd' tidak selesai dalam ${timeoutMs / 1000} detik, dilanjutkan.")
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
        val total = file.length()
        // Lewat USB, adbd di HP lama (Android 7/8, mis. SUNMI) membatasi payload per paket (4 KB).
        // Paket WRTE yang lebih besar membuat transfer macet di 0%. Lewat Wi-Fi 32 KB aman.
        val chunk = if (viaUsb) 4 * 1024 else 32 * 1024
        if (viaUsb) logDetail("Mode USB: data dikirim per ${chunk / 1024} KB")
        val stream = manager.openStream("exec:cmd package install -r -S $total")
        val lastActivity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val sendingDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
        // Pengaman: saat mengirim, tidak ada kemajuan 20 dtk = macet. Setelah terkirim, tunggu hasil pm maks. 3 menit.
        val watchdog = Thread {
            try {
                while (true) {
                    Thread.sleep(1000)
                    val idle = System.currentTimeMillis() - lastActivity.get()
                    val limit = if (sendingDone.get()) 180_000L else 20_000L
                    if (idle > limit) {
                        stalled.set(true)
                        stream.close()
                        break
                    }
                }
            } catch (e: InterruptedException) { } catch (e: Throwable) { }
        }.apply { isDaemon = true; start() }
        try {
            var writeError: java.io.IOException? = null
            try {
                val os = stream.openOutputStream()
                var sent = 0L
                var lastReport = 0L
                file.inputStream().use { input ->
                    val buf = ByteArray(chunk)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        sent += n
                        val now = System.currentTimeMillis()
                        lastActivity.set(now)
                        if (now - lastReport >= 100 || sent >= total) {
                            lastReport = now
                            onProgress?.invoke(curIdx, curTotal, curName, sent, total)
                        }
                    }
                }
                os.flush()
                sendingDone.set(true)
                lastActivity.set(System.currentTimeMillis())
            } catch (e: java.io.IOException) {
                // Device menutup stream lebih awal (mis. install ditolak). Tetap baca pesan aslinya.
                writeError = e
            }
            if (stalled.get()) {
                logWarn("Pengiriman macet: tidak ada kemajuan 20 detik. Cabut-colok kabel USB lalu Connect USB ulang.")
                return "Error: pengiriman macet (tidak ada kemajuan)"
            }
            // Jangan tunggu EOF: device sering tidak menutup stream walau "Success" sudah tercetak (install terlihat menggantung).
            val out = readAll(stream.openInputStream(), pmDone)
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
    private fun readAll(input: InputStream, doneWhen: ((String) -> Boolean)? = null): String {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                bos.write(buf, 0, n)
                if (doneWhen != null && doneWhen(bos.toString("UTF-8"))) break
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
