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
    /** Tidak ada device owner. */
    object None : OwnerState()
    /** HMDM (com.hmdm.launcher) adalah device owner. */
    data class Hmdm(val pkg: String) : OwnerState()
    /** Aplikasi lain yang menjadi device owner. */
    data class Other(val pkg: String) : OwnerState()
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

/**
 * Padanan fungsi ADB di versi Windows: pair, connect, disconnect,
 * install -r, dan dpm set-device-owner. Dipanggil dari thread latar.
 */
class AdbController(context: Context, @Volatile var sink: (LogLevel, String) -> Unit) {

    private fun logStep(s: String) = sink(LogLevel.STEP, s)
    private fun logCmd(s: String) = sink(LogLevel.CMD, s)
    private fun logOk(s: String) = sink(LogLevel.OK, s)
    private fun logWarn(s: String) = sink(LogLevel.WARN, s)
    private fun logErr(s: String) = sink(LogLevel.ERR, s)
    private fun logDetail(s: String) = sink(LogLevel.DETAIL, s)
    private fun logSummary(s: String) = sink(LogLevel.SUMMARY, s)

    /** Penyebab teknis (nama exception + pesan) sebagai baris DETAIL di bawah baris ✗ / !, bukan di dalamnya. */
    private fun logCause(e: Throwable) = logDetail(Fmt.cause(e))

    companion object {
        const val OWNER_COMPONENT = "com.hmdm.launcher/.AdminReceiver"
        const val KASPERSKY_PACKAGE = "com.kaspersky.kes"
        const val TCPIP_PORT = 5555
        /** Jumlah percobaan Connect USB (percobaan pertama bisa habis waktu selagi dialog otorisasi belum disetujui). */
        private const val USB_CONNECT_ATTEMPTS = 3
        /** Batas tanpa kemajuan saat APK dikirim, dan batas menunggu hasil pm setelah APK terkirim penuh. */
        private const val STALL_SENDING_MS = 20_000L
        /** Batas menunggu jawaban uji koneksi (probe), dalam detik. */
        private const val PROBE_TIMEOUT_S = 6L
        private const val STALL_REPLY_MS = 180_000L
        const val HMDM_PACKAGE = "com.hmdm.launcher"
        /** Daftar aplikasi wajib per jenis perangkat (dipakai ceklist "Installation Status"). */
        val DEVICE_PACKAGES: Map<String, List<String>> = linkedMapOf(
            "POS" to emptyList(),
            "TABLET" to emptyList(),
            "PDA" to emptyList()
        )
        /**
         * Versi aplikasi yang diharapkan (versionName) per jenis perangkat. Jenis tanpa daftar versi
         * hanya dicek terpasang atau tidak.
         */
        val EXPECTED_VERSIONS: Map<String, Map<String, String>> = emptyMap()

        /** Versi terbaca sama dengan harapan (abaikan spasi dan huruf besar/kecil). */
        fun versionMatches(expected: String, actual: String?): Boolean =
            actual != null && actual.trim().equals(expected.trim(), ignoreCase = true)

        /** Gabungan semua jenis: satu kali `pm list packages` sudah cukup untuk ketiga ceklist. */
        val REQUIRED_PACKAGES: List<String> = DEVICE_PACKAGES.values.flatten().distinct()
        /** Activity launcher HMDM yang dijadikan home default. */
        const val HOME_COMPONENT = "com.hmdm.launcher/.ui.MainActivity"
        /** Penyebab putus bila soket ditutup pihak lain. */
        const val DROP_CLOSED = "koneksi ditutup oleh HP target atau jaringan"
        /** Satu-satunya teks "belum terhubung", dipakai semua tempat agar tidak berbeda-beda. */
        const val MSG_NOT_CONNECTED = "Belum terhubung ke HP target. Lakukan \"Pair\" lalu \"Connect\", atau \"Connect USB\", terlebih dahulu"
    }

    private val appContext = context.applicationContext
    @Volatile private var mgr: AdbManager? = null

    /** Dibuat saat pertama dipakai (pembuatan kunci RSA bisa lambat), aman dipanggil dari banyak thread. */
    private val manager: AdbManager
        get() = mgr ?: synchronized(this) { mgr ?: AdbManager(appContext).also { mgr = it } }

    /** Panggil dari thread latar saat aplikasi dibuka agar kunci siap sebelum dipakai. */
    fun warmUp() {
        try { manager } catch (e: Throwable) { logWarn("Kunci ADB belum siap"); logCause(e) }
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
    /** true jika sesi aktif berjalan lewat kabel USB (false = ADB wireless atau tidak terhubung). */
    val isViaUsb: Boolean get() = viaUsb

    /** Label & waktu sesi, disimpan di sini (bukan di Activity) agar tampilan bisa dipulihkan bila Activity dibuat ulang. */
    @Volatile var sessionLabel: String? = null
    @Volatile var sessionSince: Long = 0L

    /** Jaga proses tetap hidup selama koneksi aktif (tanpa ini Android mematikan proses saat aplikasi ditutup/di background). */
    private fun keepAlive(on: Boolean) {
        try { if (on) ConnectionService.start(appContext) else ConnectionService.stop(appContext) } catch (e: Throwable) { }
    }

    private val usbBridge by lazy { UsbAdbBridge(appContext) }

    /** Hasil installOne bila pengiriman macet; dipakai untuk mengenali kasus ini tanpa mencocokkan teks lagi. */
    private val stallError = "Error: pengiriman macet (tidak ada kemajuan)"

    /** Cara kirim APK lewat USB: paket 4 KB dengan ZLP (seperti adb di PC). */
    private val usbChunkKb = 4

    /** Daftar HP target di port USB (setara `adb devices`). Ringan, aman dipanggil dari UI thread. */
    fun listUsbTargets(): List<UsbAdbBridge.Target> =
        try { usbBridge.findTargets() } catch (e: Throwable) { emptyList() }

    /** Setara `adb connect` lewat kabel USB ke perangkat yang dipilih. Panggil dari thread latar. Mengembalikan label, atau null bila gagal. */
    fun connectUsb(t: UsbAdbBridge.Target): String? {
        val label = "USB · ${t.label}"
        logCmd("adb -d usb (USB debugging)")
        if (!listUsbTargets().any { it.device.deviceName == t.device.deviceName }) {
            logErr("Connect USB gagal: $label sudah tidak terpasang. Colok ulang kabel lalu pilih lagi")
            return null
        }
        logStep("HP target: $label")
        if (!usbBridge.hasPermission(t.device)) logWarn("Izinkan akses USB pada dialog yang muncul di layar aplikasi ini")
        if (!usbBridge.requestPermission(t.device)) {
            logErr("Connect USB gagal: izin akses USB ditolak atau tidak dijawab. Ketuk \"Pilih Perangkat\" lagi lalu pilih \"Izinkan\"")
            return null
        }
        val port = try { usbBridge.start(t.device) } catch (e: Throwable) {
            logErr("Buka USB gagal: jalur USB ke HP target tidak bisa dibuka. Colok ulang kabel, matikan adb server di PC bila berjalan, lalu coba lagi")
            logCause(e)
            return null
        }
        logWarn("Bila muncul dialog \"Izinkan USB debugging?\" di HP target: centang \"Selalu izinkan\" lalu OK")
        // Percobaan pertama bisa habis waktu selagi dialog di HP target belum disetujui; coba lagi setelah itu.
        var ok = false
        for (attempt in 1..USB_CONNECT_ATTEMPTS) {
            if (connect("127.0.0.1:$port", verbose = false, label = label)) { ok = true; break }
            if (attempt < USB_CONNECT_ATTEMPTS) logWarn("Percobaan $attempt/$USB_CONNECT_ATTEMPTS gagal (${lastConnectReason.ifBlank { "tidak terhubung" }}), mencoba lagi...")
        }
        if (!ok) {
            val pumpErr = usbBridge.lastPumpError
            usbBridge.stop()
            viaUsb = false
            logErr("Connect USB gagal: ${lastConnectReason.ifBlank { "tidak terhubung" }}. Pastikan USB debugging aktif dan dialog otorisasi di HP target disetujui")
            // Penyebab teknis selalu di bawah baris ✗.
            if (lastConnectDetail.isNotEmpty()) logDetail(lastConnectDetail)
            if (pumpErr.isNotEmpty()) logDetail("Jembatan USB: $pumpErr")
            return null
        }
        viaUsb = true
        keepAlive(true)
        return label
    }

    /**
     * Wi-Fi HP teknisi hilang. Sesi wireless tidak mungkin hidup lagi, jadi soket ditutup di thread latar.
     * Dilewati untuk sesi USB dan loopback (127.0.0.1). Pemantau di Activity lalu menandai koneksi terputus.
     */
    fun onWifiLost() {
        if (viaUsb || !connCache) return
        if (lastAddr?.startsWith("127.0.0.1:") == true) return
        forcedDown = true
        connCache = false
        setDrop("Wi-Fi HP teknisi hilang")
        logWarn("Wi-Fi terputus, koneksi ADB ditutup")
        Thread { try { mgr?.disconnect() } catch (e: Throwable) { } }.apply { isDaemon = true; start() }
    }

    /** True bila koneksi lewat USB tetapi kabel sudah dicabut / perangkat hilang dari port. */
    val usbCableGone: Boolean
        get() = viaUsb && !usbBridge.isRunning

    /** Bersihkan sisa koneksi USB setelah kabel dicabut (tanpa menulis log). */
    fun dropUsb() {
        lastAddr = null
        connCache = false
        try { usbBridge.stop() } catch (e: Throwable) { }
        viaUsb = false
        try { mgr?.disconnect() } catch (e: Throwable) { }
        sessionLabel = null
        keepAlive(false)
    }

    private fun tryReconnect(): Boolean {
        val addr = lastAddr ?: return false
        logWarn("Koneksi terputus, menyambung ulang ke ${sessionLabel ?: addr}...")
        // verbose = false: tanpa "> adb connect" dan "Percobaan ..." per percobaan, cukup satu hasil akhir.
        repeat(3) { i ->
            if (i > 0) Thread.sleep(2000)
            if (connect(addr, verbose = false, label = sessionLabel)) return true
        }
        val hint = if (viaUsb) "Pastikan kabel USB masih terpasang dan USB debugging di HP target aktif"
                   else "Pastikan Wireless debugging di HP target masih aktif dan satu jaringan Wi-Fi"
        // Bentuk sama dengan "Connect gagal: alasan. saran".
        logErr("Sambung ulang gagal: $lastConnectReason. $hint")
        if (lastConnectDetail.isNotEmpty()) logDetail(lastConnectDetail)
        return false
    }

    /** Tutup koneksi & lepas resource (dipanggil saat Activity dihancurkan). */
    fun close() {
        connCache = false
        keepAlive(false)
        holdLocks(false)
        try { usbBridge.stop() } catch (e: Throwable) { }
        val m = mgr ?: return
        try { m.disconnect() } catch (e: Throwable) { }
        try { m.close() } catch (e: Throwable) { }
    }

    /** Menanyakan library langsung. Bisa tertahan lock library saat soket mati: JANGAN dipanggil dari main thread. */
    private val libConnected: Boolean
        get() = !forcedDown && try { mgr?.isConnected ?: false } catch (e: Throwable) { false }

    /** true setelah Wi-Fi hilang: sesi dianggap putus walau library belum sadar (soket mati diam-diam). Dihapus saat connect baru. */
    @Volatile private var forcedDown = false

    @Volatile private var connCache = false

    /**
     * Status koneksi untuk UI / main thread: hanya membaca cache, tidak menyentuh library (tidak bisa hang).
     * Cache diperbarui oleh [syncConnected] (thread pemantau) dan tiap connect/disconnect.
     */
    val isConnected: Boolean get() = connCache

    /** Cek library lalu perbarui cache. Panggil dari thread latar saja. */
    fun syncConnected(): Boolean {
        val c = libConnected
        if (!c && connCache && !forcedDown) setDrop(DROP_CLOSED)
        connCache = c
        return c
    }

    /** Alasan probe/sesi terakhir gagal, untuk diagnosis. Ditampilkan di log saat koneksi dinyatakan putus. */
    @Volatile var lastDropReason: String = ""
    /** Rincian teknis penyebab putus (nama exception, jawaban mentah). Hanya untuk baris DETAIL di log, tidak untuk popup. */
    @Volatile var lastDropDetail: String = ""

    private fun setDrop(reason: String, detail: String = "") { lastDropReason = reason; lastDropDetail = detail }

    /**
     * Cek ringan apakah koneksi benar-benar hidup (bukan sekadar flag): jalankan `echo` di device.
     * Dipanggil dari thread pemantau; menunggu paling lama 6 detik.
     */
    fun probe(addr: String? = lastAddr): Boolean {
        if (!libConnected) { setDrop(DROP_CLOSED); return false }
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = java.util.concurrent.CountDownLatch(1)
        val holder = java.util.concurrent.atomic.AtomicReference<io.github.muntashirakon.adb.AdbStream?>(null)
        val why = java.util.concurrent.atomic.AtomicReference("")
        val whyDetail = java.util.concurrent.atomic.AtomicReference("")
        // openStream() sendiri bisa menggantung di soket yang mati, jadi seluruhnya dijalankan di thread terpisah
        // dan pemanggil (thread pemantau) menunggu paling lama 6 detik.
        Thread {
            try {
                // Tes TCP langsung ke adbd (tanpa library): gagal seketika bila Wi-Fi mati / HP target tak terjangkau.
                // Sesi USB lewat jembatan loopback: JANGAN buka soket uji ke 127.0.0.1:<port>. Jembatan menganggapnya
                // sebagai klien baru dan menutup soket ADB yang sedang aktif, sehingga probe selalu gagal.
                val loopback = addr?.startsWith("127.0.0.1:") == true
                if (loopback || reachable(addr)) {
                    val s = manager.openStream("shell:echo ok")
                    holder.set(s)
                    val out = readAll(s.openInputStream())
                    ok.set(out.contains("ok"))
                    if (!ok.get()) { why.set("HP target memberi jawaban tak terduga"); whyDetail.set("Jawaban: \"${Fmt.clip(out.trim(), 40)}\"") }
                } else { why.set("HP target tidak terjangkau di jaringan"); whyDetail.set("Koneksi TCP ke $addr gagal") }
            } catch (e: Throwable) {
                why.set("pemeriksaan koneksi gagal"); whyDetail.set(Fmt.cause(e))
            } finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        val finished = done.await(PROBE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        Thread { try { holder.get()?.close() } catch (e: Throwable) { } }.apply { isDaemon = true; start() }
        if (!finished) { why.set("HP target tidak menjawab dalam $PROBE_TIMEOUT_S dtk"); whyDetail.set("") }
        val result = finished && ok.get()
        if (!result) setDrop(why.get().ifEmpty { "pemeriksaan koneksi gagal" }, whyDetail.get())
        return result
    }

    private fun reachable(addr: String?): Boolean {
        val (h, p) = split(addr ?: return true) ?: return true
        return try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(h, p), 2_000) }
            true
        } catch (e: Throwable) { false }
    }

    /** Sesi dinyatakan mati (probe gagal berulang): tandai putus dan tutup soket di thread latar. */
    fun markDead() {
        logWarn("Pemeriksaan koneksi gagal berulang, koneksi ditutup")
        forcedDown = true
        connCache = false
        Thread { try { mgr?.disconnect() } catch (e: Throwable) { } }.apply { isDaemon = true; start() }
    }

    /**
     * Cek siapa device owner di HP target (tanpa menulis log). Dipanggil dari thread latar.
     * Memakai `dpm list-owners`; bila tidak didukung, fallback ke `dumpsys device_policy`.
     */
    fun queryDeviceOwner(): OwnerState {
        if (!libConnected) return OwnerState.Unknown
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

    /**
     * Cek launcher default aktif (tanpa menulis log) lewat
     * `cmd package resolve-activity --brief -a MAIN -c HOME`. Baris terakhir berisi pkg/.Kelas.
     */
    fun queryDefaultLauncher(): HomeState {
        if (!libConnected) return HomeState.Unknown
        return try {
            val out = shell(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                5_000, quiet = true
            )
            val comp = out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() && it.contains('/') && !it.contains(' ') }
                ?: return if (out.isBlank()) HomeState.Unknown else HomeState.None
            when {
                comp.startsWith("android/") && comp.contains("ResolverActivity") -> HomeState.None
                comp.substringBefore('/') == HOME_COMPONENT.substringBefore('/') -> HomeState.Hmdm(comp)
                else -> HomeState.Other(comp)
            }
        } catch (e: Throwable) {
            HomeState.Unknown
        }
    }

    /**
     * Cek diam-diam (tanpa log) apakah [KASPERSKY_PACKAGE] terpasang di HP target (`pm list packages`).
     * true = terpasang, false = tidak ada, null = tidak terbaca (mis. koneksi bermasalah).
     */
    fun queryKasperskyInstalled(): Boolean? = queryPackageInstalled(KASPERSKY_PACKAGE)

    /** Cek diam-diam apakah [pkg] terpasang di HP target: true / false / null (tidak terbaca). */
    fun queryHmdmInstalled(): Boolean? = queryPackageInstalled(HMDM_PACKAGE)

    /**
     * Cek apakah adbd HP target sudah dalam mode TCP/IP (setara hasil `adb tcpip`):
     * properti service.adb.tcp.port / persist.adb.tcp.port berisi port > 0.
     * null = belum bisa dipastikan.
     */
    fun queryTcpipActive(): Boolean? {
        if (!libConnected) return null
        return try {
            val out = shell("getprop service.adb.tcp.port; getprop persist.adb.tcp.port", 5_000, quiet = true)
            if (out.isBlank() && lastShellTimedOut) return null
            out.lineSequence().any { (it.trim().toIntOrNull() ?: 0) > 0 }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Cek diam-diam (tanpa log) versi paket milik jenis perangkat [type] saja (bukan semua jenis).
     * Hasil: paket -> versionName; null = tidak terpasang; "" = terpasang tapi versi tidak terbaca.
     * Versi hanya dibaca untuk paket di [EXPECTED_VERSIONS] jenis itu. Seluruh hasil null = daftar tidak terbaca utuh
     * (kosong / timeout), supaya tidak salah menyimpulkan "belum terpasang".
     */
    fun queryRequiredPackages(type: String): Map<String, String?>? {
        if (type !in DEVICE_PACKAGES) return null
        val wanted = VersionStore.packages(type)
        if (!libConnected) return null
        return try {
            val out = shell("pm list packages --user 0", 8_000, quiet = true)
            if (lastShellTimedOut || !out.contains("package:")) return null
            val installed = out.lineSequence().map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:") }
                .toSet()
            // Di mode WiFi daftar paket bisa terpotong. Paket yang tidak muncul dikonfirmasi satu per satu;
            // bila ada yang tidak terbaca, hasil dianggap tidak utuh (null) supaya status lama tidak tertimpa.
            val present = LinkedHashMap<String, Boolean>()
            for (pkg in wanted) {
                if (pkg in installed) { present[pkg] = true; continue }
                present[pkg] = queryPackageInstalled(pkg) ?: return null
            }
            val withVersion = VersionStore.expected(type)
            val versions = queryVersions(wanted.filter { withVersion.containsKey(it) && present[it] == true })
            val result = LinkedHashMap<String, String?>()
            for ((pkg, ok) in present) result[pkg] = if (ok) (versions[pkg] ?: "") else null
            result
        } catch (e: Throwable) {
            null
        }
    }

    /** Baca versionName tiap paket di [pkgs] dengan satu perintah shell. Paket yang tidak terbaca tidak masuk hasil. */
    private fun queryVersions(pkgs: List<String>): Map<String, String> {
        if (pkgs.isEmpty()) return emptyMap()
        val cmd = pkgs.joinToString("; ") { p ->
            "echo \"PKG|$p|\$(dumpsys package $p | grep -m1 versionName=)\""
        }
        val out = shell(cmd, 30_000, quiet = true)
        val res = LinkedHashMap<String, String>()
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("PKG|")) continue
            val parts = t.split("|", limit = 3)
            if (parts.size < 3) continue
            val v = parts[2].substringAfter("versionName=", "").trim().substringBefore(' ')
            if (v.isNotEmpty()) res[parts[1]] = v
        }
        return res
    }

    /** Cek manual (ketuk Cek Status): versi aplikasi di HP target ditulis ke log. [type] = jenis perangkat yang dipilih. */
    fun checkRequiredPackages(type: String): Map<String, String?>? {
        if (!ensureConnected()) return null
        logCmd("adb shell dumpsys package <paket> | grep versionName")
        val all = queryRequiredPackages(type)
        if (all == null) {
            logErr("Baca versi aplikasi gagal: HP target tidak menjawab. Cek koneksi lalu coba lagi")
            return null
        }
        val pkgs = VersionStore.packages(type)
        val expected = VersionStore.expected(type)
        if (pkgs.isEmpty()) {
            logWarn("Daftar aplikasi untuk $type kosong, tidak ada yang dicek. Tambahkan aplikasi lewat ikon gerigi di kartu \"Informasi Status\"")
            return all
        }
        val missing = pkgs.filter { all[it] == null }
        val wrong = pkgs.filter { all[it] != null && expected[it] != null && !versionMatches(expected.getValue(it), all[it]) }
        if (missing.isEmpty() && wrong.isEmpty()) {
            logOk("Semua aplikasi terpasang dan versinya sesuai (${pkgs.size}/${pkgs.size})")
        } else {
            if (missing.isNotEmpty())
                logWarn("Belum terpasang ${missing.size} dari ${pkgs.size} aplikasi: ${missing.joinToString(", ")}")
            for (pkg in wrong) {
                val v = all[pkg]?.takeIf { it.isNotBlank() } ?: "tidak terbaca"
                logWarn("Versi $pkg belum sesuai: terpasang $v, seharusnya ${expected[pkg]}")
            }
        }
        return all
    }

    /**
     * Cek diam-diam tiga permission [HMDM_PACKAGE]: admin perangkat (dumpsys device_policy),
     * overlay (appops SYSTEM_ALERT_WINDOW) dan akses riwayat penggunaan (appops GET_USAGE_STATS).
     * null = belum terhubung.
     */
    fun queryHmdmPermissions(): HmdmPermissions? {
        if (!libConnected) return null
        return try {
            HmdmPermissions(
                admin = queryHmdmAdminActive(),
                overlay = queryAppOpAllowed("SYSTEM_ALERT_WINDOW", "android.permission.SYSTEM_ALERT_WINDOW"),
                usage = queryAppOpAllowed("GET_USAGE_STATS", "android.permission.PACKAGE_USAGE_STATS")
            )
        } catch (e: Throwable) {
            null
        }
    }

    /** Admin perangkat aktif bila HMDM tercatat di daftar device admin (device owner otomatis termasuk). */
    private fun queryHmdmAdminActive(): Boolean? {
        val out = shell("dumpsys device_policy | grep -F 'ComponentInfo{$HMDM_PACKAGE/'", 8_000, quiet = true)
        return when {
            out.contains("$HMDM_PACKAGE/") -> true
            out.isBlank() && lastShellTimedOut -> null
            else -> false
        }
    }

    /**
     * Cek mode appops [op] milik [HMDM_PACKAGE]: "allow" = aktif; "ignore/deny/errored" = belum aktif.
     * Bila mode masih default, status diambil dari izin [permName] di `dumpsys package` (granted=true).
     */
    private fun queryAppOpAllowed(op: String, permName: String): Boolean? {
        val out = shell("appops get $HMDM_PACKAGE $op", 5_000, quiet = true)
        if (out.isBlank() && lastShellTimedOut) return null
        val line = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$op:") }
        if (line != null) {
            val mode = line.removePrefix("$op:").trim()
            if (mode.startsWith("allow")) return true
            if (mode.startsWith("ignore") || mode.startsWith("deny") || mode.startsWith("errored")) return false
        }
        val dump = shell("dumpsys package $HMDM_PACKAGE | grep -F $permName", 8_000, quiet = true)
        if (dump.isBlank() && lastShellTimedOut) return null
        return dump.contains("$permName: granted=true")
    }

    /** Cek manual (ketuk status): hasilnya ditulis ke log. */
    fun checkHmdmPermissions(): HmdmPermissions? {
        if (!ensureConnected()) return null
        logCmd("adb shell dumpsys device_policy")
        logCmd("adb shell appops get $HMDM_PACKAGE SYSTEM_ALERT_WINDOW")
        logCmd("adb shell appops get $HMDM_PACKAGE GET_USAGE_STATS")
        val r = queryHmdmPermissions() ?: run {
            logErr("Baca hak akses gagal: HP target tidak menjawab. Cek koneksi lalu coba lagi")
            return null
        }
        fun line(name: String, v: Boolean?) = when (v) {
            true -> logOk("$name: aktif")
            false -> logWarn("$name: belum aktif")
            null -> logWarn("$name: tidak terbaca")
        }
        line("Admin perangkat", r.admin)
        line("Tampil di atas aplikasi lain", r.overlay)
        line("Akses riwayat penggunaan", r.usage)
        return r
    }

    private fun queryPackageInstalled(pkg: String): Boolean? {
        if (!libConnected) return null
        return try {
            val out = shell("pm list packages --user 0 $pkg", 5_000, quiet = true)
            when {
                out.lineSequence().any { it.trim() == "package:$pkg" } -> true
                // Kosong + timeout = HP target belum sempat menjawab, bukan "tidak terpasang".
                out.isBlank() && lastShellTimedOut -> null
                out.contains("package:") || out.isBlank() -> false
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** Cek manual (tombol status launcher): hasilnya ditulis ke log. */
    fun checkDefaultLauncher(): HomeState {
        if (!ensureConnected()) return HomeState.Unknown
        logCmd("adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        val st = queryDefaultLauncher()
        when (st) {
            is HomeState.Hmdm -> logOk("Launcher default: HMDM (${st.component})")
            is HomeState.Other -> logWarn("Launcher default: aplikasi lain (${st.component})")
            is HomeState.None -> logWarn("Belum ada launcher default di HP target")
            is HomeState.Unknown -> logErr("Baca launcher default gagal: HP target tidak menjawab. Cek koneksi lalu coba lagi")
        }
        return st
    }

    /** Cek manual (tombol Status MDM Mode): hasilnya ditulis ke log. Unknown bila tidak terbaca. */
    fun checkDeviceOwner(): OwnerState {
        if (!ensureConnected()) return OwnerState.Unknown
        logCmd("adb shell dpm list-owners")
        val st = queryDeviceOwner()
        when (st) {
            is OwnerState.Hmdm -> logOk("MDM Mode: HMDM (${st.pkg})")
            is OwnerState.Other -> logWarn("MDM Mode: aplikasi lain (${st.pkg})")
            is OwnerState.None -> logWarn("Belum ada MDM Mode di HP target")
            is OwnerState.Unknown -> logErr("Baca MDM Mode gagal: HP target tidak menjawab. Cek koneksi lalu coba lagi")
        }
        return st
    }

    fun pair(addr: String, code: String): Boolean {
        if (addr.isEmpty() || code.isEmpty()) {
            logWarn("Isi IP:Port pairing dan kode pairing terlebih dahulu")
            return false
        }
        val (host, port) = split(addr) ?: run { logErr("Pairing gagal: alamat $addr tidak valid. Gunakan format IP:Port"); return false }
        logCmd("adb pair $addr ******")
        return try {
            // Ditampung sebagai Any? agar aman baik pair() mengembalikan Boolean maupun void.
            val result: Any? = manager.pair(host, port, code)
            if (result == false) {
                logErr("Pairing gagal: ditolak HP target. Cek kode dan port pairing")
                false
            } else {
                logOk("Pairing berhasil: $addr")
                true
            }
        } catch (e: Throwable) {
            logErr("Pairing gagal: proses pairing tidak selesai. Cek kode dan port pairing, lalu coba lagi")
            logCause(e)
            false
        }
    }

    /** Cari port connect HP target (layanan mDNS `_adb-tls-connect`) pada [host] yang sama. Null bila tidak ketemu. */
    fun findConnectAddr(host: String, timeoutMs: Long = 15_000): String? {
        logStep("Mencari port connect $host lewat mDNS...")
        return AdbDiscovery.find(appContext, AdbDiscovery.TYPE_CONNECT, timeoutMs, java.util.concurrent.atomic.AtomicBoolean(false)) { _, h, p -> h == host && reachable("$h:$p") }?.addr
    }

    @Volatile private var lastConnectReason = ""
    /** Rantai exception teknis dari percobaan connect terakhir; dicetak sebagai DETAIL oleh pemanggil yang menahan log (USB, sambung ulang). */
    @Volatile private var lastConnectDetail = ""

    /**
     * [verbose] false = jangan cetak perintah dan kegagalan akhir (dipakai pemanggil yang mengulang sendiri, mis. USB).
     * [label] = nama yang ditampilkan di baris ✓ menggantikan alamat (USB memakai port loopback internal yang tidak berarti bagi teknisi).
     */
    fun connect(addr: String, verbose: Boolean = true, label: String? = null): Boolean {
        if (addr.isEmpty()) {
            logWarn("Isi IP:Port connect terlebih dahulu")
            return false
        }
        val (host, port) = split(addr) ?: run { logErr("Connect gagal: alamat $addr tidak valid. Gunakan format IP:Port"); return false }
        // connect() di library langsung "sukses" bila masih ada koneksi lama (mis. ke IP/port lain),
        // jadi putuskan dulu agar benar-benar menyambung ke alamat yang diminta.
        if (forcedDown || libConnected) {
            try { manager.disconnect() } catch (e: Throwable) { }
        }
        if (viaUsb && !addr.startsWith("127.0.0.1:")) { usbBridge.stop(); viaUsb = false }
        val maxAttempts = 2
        var ok = false
        var reason = ""   // singkat, tampil di baris percobaan ulang
        var advice = ""   // saran perbaikan, hanya di baris ✗ akhir
        var detail = ""   // rantai exception teknis, hanya sebagai baris rincian
        forcedDown = false
        if (verbose) logCmd("adb connect $addr")
        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                if (verbose) logWarn("Percobaan ${attempt - 1}/$maxAttempts gagal ($reason), mencoba lagi...")
                Thread.sleep(1500)
            }
            detail = ""
            try {
                val result: Any? = manager.connect(host, port)
                if (result == false || !manager.isConnected) {
                    reason = "tidak terhubung"
                    advice = "Pastikan Wireless debugging aktif di HP target dan port connect benar"
                } else if (probe(addr)) {
                    // Terbukti: device menjawab perintah shell, bukan sekadar flag.
                    ok = true
                    lastAddr = addr
                    keepAlive(true)
                    break
                } else {
                    reason = "HP target tidak menjawab"
                    advice = "Pastikan sudah di-pair, Wireless debugging aktif, dan port connect benar"
                    try { manager.disconnect() } catch (e: Throwable) { }
                }
            } catch (e: Throwable) {
                android.util.Log.e("AdbConnect", "connect $addr", e)
                reason = connectErrorReason(e)
                advice = "Pastikan satu jaringan Wi-Fi dan Wireless debugging aktif. Port connect berubah setiap Wireless debugging dinyalakan ulang, jadi cek port terbaru"
                detail = generateSequence(e) { it.cause }.take(4)
                    .joinToString(" <- ") { Fmt.cause(it) }
                try { manager.disconnect() } catch (e: Throwable) { }
            }
        }
        lastConnectReason = reason
        lastConnectDetail = detail
        syncConnected()
        if (ok) {
            logOk("Terhubung: ${label ?: addr}")
        } else if (verbose) {
            logErr("Connect gagal: $reason. $advice")
            if (detail.isNotEmpty()) logDetail(detail)
        }
        return ok
    }

    /** Alasan singkat (tanpa saran) dari exception saat connect. Rantai teknis lengkap dicetak terpisah sebagai DETAIL. */
    private fun connectErrorReason(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(4).toList()
        val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            chain.any { it is java.net.SocketTimeoutException } || "timed out" in text -> "waktu habis, HP target tidak menjawab"
            chain.any { it is java.net.ConnectException } || "refused" in text -> "koneksi ditolak oleh HP target"
            chain.any { it is java.net.UnknownHostException || it is java.net.NoRouteToHostException } || "unreachable" in text -> "HP target tidak terjangkau"
            chain.any { it is javax.net.ssl.SSLException } || "handshake" in text || "certificate" in text -> "jabat tangan TLS gagal"
            else -> "kesalahan koneksi tak terduga"
        }
    }

    fun disconnect(): Boolean {
        logCmd("adb disconnect")
        lastAddr = null
        sessionLabel = null
        keepAlive(false)
        return try {
            manager.disconnect()
            connCache = false
            if (viaUsb) { usbBridge.stop(); viaUsb = false }
            logOk("Koneksi diputus")
            true
        } catch (e: Throwable) {
            logErr("Disconnect gagal: koneksi tidak bisa ditutup dengan bersih. Cabut kabel atau matikan Wireless debugging di HP target, lalu cek status koneksi")
            logCause(e)
            false
        }
    }

    /** Pasang semua APK berurutan; yang gagal tidak menghentikan yang lain. */
    /** Hasil install terakhir (untuk popup ringkasan di UI). */
    class InstallSummary(val total: Int, val ok: Int, val failed: List<Pair<String, String>>)
    @Volatile var lastInstall: InstallSummary? = null

    fun install(apks: List<ApkItem>): Boolean {
        lastInstall = null
        if (!ensureConnected()) return false
        if (apks.isEmpty()) {
            logWarn("Belum ada APK. Pilih file APK terlebih dahulu")
            return false
        }
        var ok = 0
        val failed = mutableListOf<Pair<String, String>>() // nama APK -> alasan
        apks.forEachIndexed { i, apk ->
            logStep("[${i + 1}/${apks.size}] ${apk.name} (${apk.sizeText})")
            if (!apk.file.exists()) {
                logErr("Gagal: file tidak ditemukan di penyimpanan sementara. Pilih ulang APK")
                failed += apk.name to "file tidak ditemukan"
                return@forEachIndexed
            }
            if (!libConnected && !tryReconnect()) {
                failed += apk.name to "koneksi terputus"
                return@forEachIndexed
            }
            logCmd("adb install -r ${apk.name}")
            curIdx = i + 1; curTotal = apks.size; curName = apk.name
            onProgress?.invoke(curIdx, curTotal, curName, 0L, apk.file.length())
            val t0 = System.currentTimeMillis()
            // Bila macet / gagal sementara: satu kali ulang.
            val maxTries = 2
            var tries = 0
            var out = ""
            while (true) {
                out = installTry(apk.file)
                tries++
                if (isSuccess(out) || !isTransient(out) || tries >= maxTries) break
                // Alasan singkat saja: saran lengkap baru ditampilkan di baris ✗ bila akhirnya tetap gagal.
                logWarn("Percobaan $tries/$maxTries gagal (${installFailureReason(out, advice = false)}), mencoba lagi...")
                if (libConnected) Thread.sleep(1500) else tryReconnect()
                if (!libConnected) break
            }
            if (isSuccess(out)) {
                ok++
                logOk("Terpasang · ${Fmt.duration(System.currentTimeMillis() - t0)}")
            } else {
                val reason = installFailureReason(out)
                // Ringkasan memakai alasan singkat; saran lengkap sudah tampil di baris ✗ di atas.
                failed += apk.name to installFailureReason(out, advice = false)
                logErr("Gagal: $reason")
                if (out != stallError) logRaw(out, reason)
            }
        }
        lastInstall = InstallSummary(apks.size, ok, failed.toList())
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
            logOk("MDM Mode aktif: ${OWNER_COMPONENT.substringBefore('/')}")
            return true
        }
        val reason = ownerFailureReason(out)
        logErr("Jadikan MDM Mode gagal: $reason")
        logRaw(out, reason)
        return false
    }

    /** Setara `adb shell cmd package set-home-activity "com.hmdm.launcher/.ui.MainActivity"`: jadikan HMDM launcher default. */
    fun setDefaultLauncher(): Boolean {
        if (!ensureConnected()) return false
        logCmd("adb shell cmd package set-home-activity \"$HOME_COMPONENT\"")
        val out = try { shell("cmd package set-home-activity \"$HOME_COMPONENT\"", doneWhen = pmDone) } catch (e: Throwable) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        if (!isSuccess(out)) {
            val reason = when {
                out.contains("Unknown", ignoreCase = true) || out.contains("not found", ignoreCase = true) ||
                    out.contains("does not exist", ignoreCase = true) ->
                    "komponen $HOME_COMPONENT tidak ditemukan. Pastikan HMDM sudah terpasang"
                out.contains("Permission", ignoreCase = true) || out.contains("SecurityException") ->
                    "ditolak sistem (izin shell tidak cukup)"
                else -> "perintah tidak berhasil. Pastikan HMDM sudah terpasang di HP target"
            }
            logErr("Atur launcher default gagal: $reason")
            logRaw(out, reason)
            return false
        }
        logOk("Launcher default aktif: $HOME_COMPONENT")
        return true
    }

    /** Setara `adb shell pm clear com.kaspersky.kes`: hapus semua data aplikasi Kaspersky di device. */
    fun clearKasperskyData(): Boolean {
        if (!ensureConnected()) return false
        logCmd("adb shell pm clear $KASPERSKY_PACKAGE")
        val out = try { shell("pm clear $KASPERSKY_PACKAGE", doneWhen = pmDone) } catch (e: Throwable) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        if (!isSuccess(out)) {
            logErr("Hapus data aplikasi Kaspersky gagal: perintah tidak berhasil. Pastikan $KASPERSKY_PACKAGE terpasang di HP target")
            logRaw(out)
            return false
        }
        logOk("Data aplikasi Kaspersky dihapus")
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
                    "Kaspersky masih aktif sebagai aplikasi admin perangkat. Ulangi Langkah 2: Pengaturan › Keamanan › Aplikasi admin perangkat › Kaspersky › Nonaktifkan"
                out.contains("DELETE_FAILED_USER_RESTRICTED") || out.contains("OWNER_BLOCKED") ->
                    "diblokir kebijakan device owner atau profile owner"
                out.contains("Unknown package", ignoreCase = true) || out.contains("not installed", ignoreCase = true) ->
                    "Kaspersky sudah tidak terpasang"
                else -> "perintah tidak berhasil. Pastikan $KASPERSKY_PACKAGE terpasang di HP target"
            }
            logErr("Uninstall aplikasi Kaspersky gagal: $reason")
            logRaw(out, reason)
            return false
        }
        logOk("Aplikasi Kaspersky di-uninstall")
        return true
    }

    /**
     * Setara `adb tcpip 5555`: adbd di HP target restart dalam mode TCP di port 5555.
     * Setelah itu sesi USB ini pasti putus, jadi sesi dibersihkan di sini tanpa log error.
     */
    fun enableTcpip(): Boolean {
        if (!ensureConnected()) return false
        if (!viaUsb) {
            logErr("Ubah ke Mode Nirkabel gagal: perintah ini hanya untuk koneksi USB. Gunakan tab \"USB\" lalu ketuk \"Pilih Perangkat\"")
            return false
        }
        if (queryTcpipActive() == true) {
            logOk("Mode TCP/IP sudah aktif di HP target, tidak perlu diubah lagi")
            return true
        }
        logCmd("adb tcpip $TCPIP_PORT")
        val out = try {
            shell("$TCPIP_PORT", timeoutMs = 6_000, quiet = true, service = "tcpip") {
                it.contains("restarting", ignoreCase = true) || it.contains("error", ignoreCase = true) || it.contains("failed", ignoreCase = true)
            }
        } catch (e: Throwable) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        val failed = out.contains("error", ignoreCase = true) || out.contains("failed", ignoreCase = true)
        val restarted = out.contains("restarting", ignoreCase = true)
        if (failed || (!restarted && out.isNotBlank())) {
            val reason = "HP target menolak perintah tcpip. Cek koneksi USB, lalu coba lagi"
            logErr("Ubah ke Mode Nirkabel gagal: $reason")
            logRaw(out, reason)
            return false
        }
        // Tanpa output = adbd sudah restart dan menutup stream lebih dulu, tetap dianggap berhasil.
        logOk("Mode TCP/IP aktif di port $TCPIP_PORT, koneksi USB terputus otomatis")
        logStep("Berikutnya: cabut kabel, buka tab \"Wireless\", isi IP HP target dan port $TCPIP_PORT, lalu ketuk \"Connect\"")
        lastAddr = null
        sessionLabel = null
        keepAlive(false)
        try { manager.disconnect() } catch (e: Throwable) { }
        connCache = false
        try { usbBridge.stop() } catch (e: Throwable) { }
        viaUsb = false
        return true
    }

    fun installAndOwner(apks: List<ApkItem>): Boolean {
        if (!ensureConnected()) return false
        // Walau sebagian APK gagal, tetap coba set device owner (HMDM mungkin sudah terpasang).
        val installOk = install(apks)
        logStep("Jadikan MDM Mode")
        val ownerOk = setDeviceOwner()
        return installOk && ownerOk
    }

    // ---------- internal ----------

    private fun ensureConnected(): Boolean {
        if (libConnected) return true
        logWarn(MSG_NOT_CONNECTED)
        return false
    }

    private fun isSuccess(out: String) = out.contains("Success", ignoreCase = true)

    /**
     * Output mentah device sebagai baris rincian (maks. 6 baris) untuk diagnosis.
     * Dibuang: baris "Performing ...", stack trace Java ("at ...", "... N more"), dan baris yang
     * hanya mengulang alasan yang sudah tampil di baris [shown] (mis. "Failure: INSTALL_FAILED_...").
     */
    private fun logRaw(out: String, shown: String = "", max: Int = 6) {
        val lines = out.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !isRawNoise(it) && !restates(it, shown) }
            .distinct()
        lines.take(max).forEach { logDetail(it) }
        if (lines.size > max) logDetail("… ${lines.size - max} baris lain tidak ditampilkan")
    }

    private val stackFrameRe = Regex("^at [\\w.$<>/]+\\(.*\\)$")
    private val moreRe = Regex("^\\.\\.\\. \\d+ more$")

    private fun isRawNoise(l: String) =
        l.startsWith("Performing ") || stackFrameRe.matches(l) || moreRe.matches(l)

    private fun restates(line: String, shown: String): Boolean {
        if (shown.isEmpty()) return false
        val body = line.removePrefix("Error:").trim()
        if (body.isNotEmpty() && shown.contains(body, ignoreCase = true)) return true
        val code = Regex("^Failure \\[([A-Z_]+)").find(line)?.groupValues?.get(1)
        return code != null && shown.contains(code)
    }

    private val installReasons = mapOf(
        "INSTALL_FAILED_ALREADY_EXISTS" to "sudah terpasang",
        "INSTALL_FAILED_VERSION_DOWNGRADE" to "versi lebih rendah dari yang terpasang",
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "tanda tangan berbeda dengan versi terpasang",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "penyimpanan HP target penuh",
        "INSTALL_FAILED_INVALID_APK" to "file APK rusak atau tidak valid",
        "INSTALL_FAILED_NO_MATCHING_ABIS" to "arsitektur CPU tidak cocok",
        "INSTALL_FAILED_OLDER_SDK" to "versi Android HP target terlalu lama",
        "INSTALL_FAILED_USER_RESTRICTED" to "pemasangan dibatasi kebijakan HP target",
        "INSTALL_FAILED_TEST_ONLY" to "APK bertanda test-only"
    )

    /** Saran perbaikan per kode install; hanya dipakai di baris ✗ akhir, ditulis sebagai kalimat sendiri. */
    private val installAdvice = mapOf(
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "Uninstall versi yang terpasang dulu, lalu pasang ulang",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "Kosongkan penyimpanan HP target, lalu coba lagi"
    )

    /** Alasan singkat dari keluaran `cmd package install` yang gagal. [advice] = sertakan saran perbaikan (untuk baris ✗ akhir). */
    private fun installFailureReason(out: String, advice: Boolean = true): String {
        val code = Regex("INSTALL_[A-Z_]+").find(out)?.value
        val friendly = code?.let { installReasons[it] }
        return when {
            out == stallError ->
                if (!advice) "pengiriman macet"
                else if (viaUsb) "pengiriman macet. Cabut-colok kabel USB, lalu ketuk \"Pilih Perangkat\" lagi"
                else "pengiriman macet. Pastikan HP target menyala dan satu jaringan Wi-Fi, lalu ketuk \"Connect\" lagi"
            friendly != null -> "$friendly ($code)" + (if (advice) code?.let { installAdvice[it] }?.let { ". $it" }.orEmpty() else "")
            code != null -> code
            // Kegagalan kirim tanpa kode install (stream putus, exception): teks mentahnya (sering berbahasa Inggris,
            // mis. "stream closed") hanya tampil di baris DETAIL, bukan di baris ✗.
            out.startsWith("Error:") ->
                if (advice) "pengiriman APK gagal. Cek koneksi ke HP target, lalu coba lagi" else "pengiriman APK gagal"
            out.isBlank() -> "tidak ada balasan dari HP target"
            // Baris pertama yang bukan noise ("Performing Streamed Install", stack trace) dan tidak kosong.
            else -> out.lines().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !isRawNoise(it) }
                ?.removePrefix("Error:")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { Fmt.clip(it, 120) }
                ?: "tidak diketahui"
        }
    }

    /** Alasan singkat dari keluaran `dpm set-device-owner` yang gagal. */
    private fun ownerFailureReason(out: String): String {
        val l = out.lowercase()
        return when {
            "already has a device owner" in l || "device owner is already set" in l -> "HP target sudah memiliki device owner. Reset HP target ke pengaturan pabrik bila ingin menggantinya"
            "accounts" in l -> "masih ada akun (Google/lainnya) di HP target. Hapus akun atau reset HP target"
            "provisioned" in l || "setup" in l -> "HP target sudah melewati setup awal. Reset ke pengaturan pabrik"
            "unknown admin" in l || "does not exist" in l || "not found" in l -> "HMDM Launcher belum terpasang di HP target. Jalankan \"Pasang Aplikasi\" terlebih dahulu"
            else -> "perintah ditolak HP target. Pastikan HP target baru di-reset dan belum ada akun (Google/lainnya)"
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
        service: String = "shell",
        doneWhen: ((String) -> Boolean)? = null
    ): String {
        val bos = ByteArrayOutputStream()
        val done = java.util.concurrent.CountDownLatch(1)
        var stream: io.github.muntashirakon.adb.AdbStream? = null
        var error: Throwable? = null
        Thread {
            try {
                val s = manager.openStream("$service:$cmd")
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
                logWarn("Perintah \"$cmd\" tidak selesai dalam ${Fmt.duration(timeoutMs)}, dilanjutkan")
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
        // Tanpa ZLP sebagian HP macet di 0%, jadi lewat USB ZLP selalu dikirim.
        val chunk = if (viaUsb) usbChunkKb * 1024 else 32 * 1024
        if (viaUsb) {
            usbBridge.sendZlp = true
            usbBridge.resetStats()
        }
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
                    val limit = if (sendingDone.get()) STALL_REPLY_MS else STALL_SENDING_MS
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
            var sent = 0L
            try {
                val os = stream.openOutputStream()
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
                // Setelah APK terkirim penuh, "terkirim X dari X" tidak berguna; cukup sebut ukurannya.
                if (sendingDone.get()) logWarn("Pengiriman macet: tidak ada jawaban dari HP target selama ${STALL_REPLY_MS / 1000} dtk setelah APK terkirim penuh (${Fmt.size(total)})")
                else logWarn("Pengiriman macet: tidak ada kemajuan selama ${STALL_SENDING_MS / 1000} dtk (terkirim ${Fmt.size(sent)} dari ${Fmt.size(total)})")
                if (viaUsb) logDetail("USB: ${usbBridge.stats()}")
                return stallError
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
