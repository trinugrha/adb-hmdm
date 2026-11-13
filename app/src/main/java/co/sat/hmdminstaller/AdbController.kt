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

    companion object {
        const val OWNER_COMPONENT = "com.hmdm.launcher/.AdminReceiver"
        const val KASPERSKY_PACKAGE = "com.kaspersky.kes"
        /** Activity launcher HMDM yang dijadikan home default. */
        const val HOME_COMPONENT = "com.hmdm.launcher/.ui.MainActivity"
        /** Satu-satunya teks "belum terhubung", dipakai semua tempat agar tidak berbeda-beda. */
        const val MSG_NOT_CONNECTED = "Belum terhubung ke HP target. Lakukan Pair dan Connect (atau Connect USB) dulu"
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
        logCmd("adb -d usb (USB debugging)")
        if (!listUsbTargets().any { it.device.deviceName == t.device.deviceName }) {
            logErr("Perangkat ${t.label} sudah tidak terpasang. Colok ulang kabel lalu pilih lagi")
            return null
        }
        logStep("Perangkat USB: ${t.label}")
        if (!usbBridge.hasPermission(t.device)) logWarn("Izinkan akses USB pada dialog yang muncul di layar aplikasi ini")
        if (!usbBridge.requestPermission(t.device)) {
            logErr("Izin akses USB ditolak atau tidak dijawab")
            return null
        }
        val port = try { usbBridge.start(t.device) } catch (e: Throwable) {
            logErr("Gagal membuka USB: ${e.message ?: e.javaClass.simpleName}")
            return null
        }
        logWarn("Bila muncul dialog 'Izinkan USB debugging?' di HP target: centang 'Selalu izinkan' lalu OK")
        val label = "USB · ${t.label}"
        // Percobaan pertama bisa habis waktu selagi dialog di HP target belum disetujui; coba lagi setelah itu.
        var ok = false
        for (attempt in 1..3) {
            if (connect("127.0.0.1:$port", verbose = false)) { ok = true; break }
            if (attempt < 3) logWarn("Percobaan $attempt/3 gagal ($lastConnectReason), mencoba lagi...")
        }
        if (!ok) {
            usbBridge.stop()
            viaUsb = false
            logErr("Connect USB gagal: pastikan USB debugging aktif dan dialog otorisasi di HP target disetujui")
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
        logWarn("Koneksi terputus, menyambung ulang ke $addr...")
        // verbose = false: tanpa "> adb connect" dan "Percobaan ..." per percobaan, cukup satu hasil akhir.
        repeat(3) { i ->
            if (i > 0) Thread.sleep(2000)
            if (connect(addr, verbose = false)) return true
        }
        val hint = if (viaUsb) "Pastikan kabel USB masih terpasang dan USB debugging di HP target aktif"
                   else "Pastikan Wireless debugging di HP target masih aktif dan satu jaringan Wi-Fi"
        // Bentuk sama dengan "Connect gagal: alasan. saran".
        logErr("Sambung ulang gagal: $lastConnectReason. $hint")
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
    fun syncConnected(): Boolean { val c = libConnected; connCache = c; return c }

    /**
     * Cek ringan apakah koneksi benar-benar hidup (bukan sekadar flag): jalankan `echo` di device.
     * Dipanggil dari thread pemantau; stream ditutup paksa bila tidak membalas dalam 8 detik.
     */
    fun probe(addr: String? = lastAddr): Boolean {
        if (!libConnected) return false
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = java.util.concurrent.CountDownLatch(1)
        val holder = java.util.concurrent.atomic.AtomicReference<io.github.muntashirakon.adb.AdbStream?>(null)
        // openStream() sendiri bisa menggantung di soket yang mati, jadi seluruhnya dijalankan di thread terpisah
        // dan pemanggil (thread pemantau) menunggu paling lama 6 detik.
        Thread {
            try {
                // Tes TCP langsung ke adbd (tanpa library): gagal seketika bila Wi-Fi mati / HP target tak terjangkau.
                if (reachable(addr)) {
                    val s = manager.openStream("shell:echo ok")
                    holder.set(s)
                    ok.set(readAll(s.openInputStream()).contains("ok"))
                }
            } catch (e: Throwable) {
            } finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        val finished = done.await(6, java.util.concurrent.TimeUnit.SECONDS)
        Thread { try { holder.get()?.close() } catch (e: Throwable) { } }.apply { isDaemon = true; start() }
        return finished && ok.get()
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

    /** Cek manual (tombol status launcher): hasilnya ditulis ke log. */
    fun checkDefaultLauncher(): HomeState {
        if (!ensureConnected()) return HomeState.Unknown
        logCmd("adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        val st = queryDefaultLauncher()
        when (st) {
            is HomeState.Hmdm -> logOk("Launcher default: HMDM (${st.component})")
            is HomeState.Other -> logWarn("Launcher default: aplikasi lain (${st.component})")
            is HomeState.None -> logWarn("Belum ada launcher default di HP target")
            is HomeState.Unknown -> logErr("Launcher default tidak terbaca: HP target tidak menjawab")
        }
        return st
    }

    /** Cek manual (tombol status Device Owner): hasilnya ditulis ke log. Unknown bila tidak terbaca. */
    fun checkDeviceOwner(): OwnerState {
        if (!ensureConnected()) return OwnerState.Unknown
        logCmd("adb shell dpm list-owners")
        val st = queryDeviceOwner()
        when (st) {
            is OwnerState.Hmdm -> logOk("Device owner: HMDM (${st.pkg})")
            is OwnerState.Other -> logWarn("Device owner: aplikasi lain (${st.pkg})")
            is OwnerState.None -> logWarn("Belum ada device owner di HP target")
            is OwnerState.Unknown -> logErr("Status device owner tidak terbaca: HP target tidak menjawab")
        }
        return st
    }

    fun pair(addr: String, code: String): Boolean {
        if (addr.isEmpty() || code.isEmpty()) {
            logWarn("Isi IP:Port pairing dan kode pairing terlebih dahulu")
            return false
        }
        val (host, port) = split(addr) ?: run { logErr("Alamat pairing tidak valid: $addr"); return false }
        logCmd("adb pair $addr ******")
        return try {
            // Ditampung sebagai Any? agar aman baik pair() mengembalikan Boolean maupun void.
            val result: Any? = manager.pair(host, port, code)
            if (result == false) {
                logErr("Pairing gagal: ditolak HP target (cek kode dan port pairing)")
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

    /** Cari port connect HP target (layanan mDNS `_adb-tls-connect`) pada [host] yang sama. Null bila tidak ketemu. */
    fun findConnectAddr(host: String, timeoutMs: Long = 15_000): String? {
        logStep("Mencari port connect $host lewat mDNS...")
        return AdbDiscovery.find(appContext, AdbDiscovery.TYPE_CONNECT, timeoutMs, java.util.concurrent.atomic.AtomicBoolean(false)) { _, h, p -> h == host && reachable("$h:$p") }?.addr
    }

    @Volatile private var lastConnectReason = ""

    /** [verbose] false = jangan cetak perintah dan kegagalan akhir (dipakai pemanggil yang mengulang sendiri, mis. USB). */
    fun connect(addr: String, verbose: Boolean = true): Boolean {
        if (addr.isEmpty()) {
            logWarn("Isi IP:Port connect terlebih dahulu")
            return false
        }
        val (host, port) = split(addr) ?: run { logErr("Alamat connect tidak valid: $addr"); return false }
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
                    .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message ?: "-"}" }
                try { manager.disconnect() } catch (_: Throwable) { }
            }
        }
        lastConnectReason = reason
        syncConnected()
        if (ok) {
            logOk("Terhubung: $addr")
        } else if (verbose) {
            logErr("Connect gagal: $reason. $advice")
            if (detail.isNotEmpty()) logDetail(detail.take(240))
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
            else -> "koneksi gagal (${e.javaClass.simpleName})"
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
            logErr("Disconnect gagal: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /** Pasang semua APK berurutan; yang gagal tidak menghentikan yang lain. */
    fun install(apks: List<ApkItem>): Boolean {
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
                logErr("File tidak ditemukan di penyimpanan sementara")
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
        val reason = ownerFailureReason(out)
        logErr("Set device owner gagal: $reason")
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
                    "komponen $HOME_COMPONENT tidak ditemukan, pastikan HMDM sudah terpasang"
                out.contains("Permission", ignoreCase = true) || out.contains("SecurityException") ->
                    "ditolak sistem (izin shell tidak cukup)"
                else -> "pastikan HMDM sudah terpasang di HP target"
            }
            logErr("Set launcher default gagal: $reason")
            logRaw(out, reason)
            return false
        }
        logOk("Launcher default: $HOME_COMPONENT")
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
            logErr("Hapus data Kaspersky gagal: pastikan $KASPERSKY_PACKAGE terpasang di HP target")
            logRaw(out)
            return false
        }
        logOk("Data Kaspersky dihapus")
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
                    "Aplikasi Admin Kaspersky belum dimatikan. Ulangi Langkah 2 (Pengaturan > Keamanan > Aplikasi admin perangkat > Kaspersky > Nonaktifkan)"
                out.contains("DELETE_FAILED_USER_RESTRICTED") || out.contains("OWNER_BLOCKED") ->
                    "diblokir kebijakan device owner atau profile owner"
                out.contains("Unknown package", ignoreCase = true) || out.contains("not installed", ignoreCase = true) ->
                    "Kaspersky sudah tidak terpasang"
                else -> "pastikan $KASPERSKY_PACKAGE terpasang di HP target"
            }
            logErr("Uninstall Kaspersky gagal: $reason")
            logRaw(out, reason)
            return false
        }
        logOk("Kaspersky di-uninstall")
        return true
    }

    fun installAndOwner(apks: List<ApkItem>): Boolean {
        if (!ensureConnected()) return false
        // Walau sebagian APK gagal, tetap coba set device owner (HMDM mungkin sudah terpasang).
        val installOk = install(apks)
        logStep("Set device owner")
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
        lines.take(max).forEach { logDetail(it.take(240)) }
        if (lines.size > max) logDetail("... ${lines.size - max} baris lain tidak ditampilkan")
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
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "tanda tangan berbeda dengan versi terpasang (uninstall dulu)",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "penyimpanan HP target penuh",
        "INSTALL_FAILED_INVALID_APK" to "file APK rusak atau tidak valid",
        "INSTALL_FAILED_NO_MATCHING_ABIS" to "arsitektur CPU tidak cocok",
        "INSTALL_FAILED_OLDER_SDK" to "versi Android HP target terlalu lama",
        "INSTALL_FAILED_USER_RESTRICTED" to "install dibatasi kebijakan HP target",
        "INSTALL_FAILED_TEST_ONLY" to "APK bertanda test-only"
    )

    /** Alasan singkat dari keluaran `cmd package install` yang gagal. [advice] = sertakan saran perbaikan (untuk baris ✗ akhir). */
    private fun installFailureReason(out: String, advice: Boolean = true): String {
        val code = Regex("INSTALL_[A-Z_]+").find(out)?.value
        val friendly = code?.let { installReasons[it] }
        return when {
            out == stallError ->
                if (!advice) "pengiriman macet"
                else if (viaUsb) "pengiriman macet. Cabut-colok kabel USB, lalu Connect USB ulang"
                else "pengiriman macet. Pastikan HP target menyala dan satu jaringan Wi-Fi, lalu Connect ulang"
            friendly != null -> "$friendly ($code)"
            code != null -> code
            out.isBlank() -> "tidak ada balasan dari HP target"
            else -> out.lines().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("Error:")?.trim()?.take(120)
                ?: "tidak diketahui"
        }
    }

    /** Alasan singkat dari keluaran `dpm set-device-owner` yang gagal. */
    private fun ownerFailureReason(out: String): String {
        val l = out.lowercase()
        return when {
            "already has a device owner" in l || "device owner is already set" in l -> "HP target sudah memiliki device owner"
            "accounts" in l -> "masih ada akun (Google/lainnya) di HP target. Hapus akun atau reset HP target"
            "provisioned" in l || "setup" in l -> "HP target sudah melewati setup awal. Reset ke pengaturan pabrik"
            "unknown admin" in l || "does not exist" in l || "not found" in l -> "HMDM Launcher belum terpasang di HP target"
            else -> "pastikan HP target baru di-reset dan belum ada akun (Google/lainnya)"
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
                logWarn("Perintah '$cmd' tidak selesai dalam ${timeoutMs / 1000} dtk, dilanjutkan")
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
                val fase = if (sendingDone.get()) "menunggu hasil dari HP target (180 dtk)" else "mengirim APK (20 dtk tanpa kemajuan)"
                logWarn("Macet saat $fase, terkirim ${Fmt.size(sent)} dari ${Fmt.size(total)}")
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
