package co.sat.hmdminstaller

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var edtPairIp: TextInputEditText
    private lateinit var edtPairPort: TextInputEditText
    private lateinit var edtPairCode: TextInputEditText
    private lateinit var edtConnIp: TextInputEditText
    private lateinit var edtConnPort: TextInputEditText
    private lateinit var txtLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var txtConn: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtApkTitle: TextView
    private lateinit var txtApkEmpty: TextView
    private lateinit var txtDropSub: TextView
    private lateinit var dropZone: View
    private lateinit var ownerPill: View
    private lateinit var apkListBox: android.widget.LinearLayout
    private lateinit var tabMode: TabLayout
    private lateinit var statusDot: View
    private lateinit var ownerDot: View
    private lateinit var txtOwner: TextView
    private lateinit var homePill: View
    private lateinit var homeDot: View
    private lateinit var txtHome: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: List<MaterialButton>
    // Tombol yang butuh koneksi ADB aktif: nonaktif (tidak bisa ditekan) selama belum terhubung.
    private lateinit var needsConnection: Set<MaterialButton>
    /** Tombol yang membuka sambungan baru: mati selama masih ada sambungan aktif (harus Disconnect dulu). */
    private lateinit var blockedWhenConnected: Set<MaterialButton>

    private val worker = Executors.newSingleThreadExecutor()
    // Penyalinan APK ke cache dipisah dari worker ADB agar tidak antre di belakang install, dan tidak membebani UI thread.
    private val io = Executors.newSingleThreadExecutor()
    private val apks = mutableListOf<ApkItem>()
    private lateinit var adb: AdbController
    @Volatile private var busy = false
    @Volatile private var connectedAddr: String? = null
    private var connectedSince = 0L
    private var lostAddr: String? = null
    @Volatile private var ownerState: OwnerState = OwnerState.Unknown
    @Volatile private var homeState: HomeState = HomeState.Unknown

    // Pemantau status koneksi (live): jalan hanya saat layar terlihat.
    private val poller = Executors.newSingleThreadScheduledExecutor()
    private var pollTask: ScheduledFuture<*>? = null
    private var lastProbe = 0L
    private var probeFails = 0

    // ---- Kunci PIN ----
    private lateinit var pinStore: PinStore
    private var lockView: PinLockView? = null
    private var lastActivityAt = SystemClock.elapsedRealtime()
    private lateinit var txtSession: TextView
    private val backWhileLocked = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { moveTaskToBack(true) }
    }

    private val pickApks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        // Di UI thread: hanya baca nama (cepat) dan saring duplikat / non-APK.
        // Setiap pemilihan baru mereset daftar sebelumnya (APK lama diganti).
        val seen = mutableSetOf<String>()
        val todo = mutableListOf<Pair<Uri, String>>()
        var skipped = 0
        uris.forEach { uri ->
            val name = displayName(uri)
            when {
                !name.endsWith(".apk", ignoreCase = true) -> skipped++
                !seen.add(name.lowercase()) -> skipped++
                else -> todo.add(uri to name)
            }
        }
        if (skipped > 0) log(LogLevel.WARN, "$skipped file dilewati (bukan .apk atau duplikat).")
        if (todo.isEmpty()) return@registerForActivityResult
        // Penyalinan file (bisa puluhan-ratusan MB) di thread latar.
        io.execute {
            // Hapus file APK lama dari cache dulu (sebelum menyalin, agar nama yang sama tidak ikut terhapus).
            val old = apks.toList()
            old.filter { !it.builtIn }.forEach { it.file.delete() }
            val items = mutableListOf<ApkItem>()
            var failed = 0
            todo.forEach { (uri, name) ->
                val item = copyToCache(uri, name)
                if (item == null) failed++ else items.add(item)
            }
            runOnUiThread {
                apks.clear()
                apks.addAll(items)
                if (failed > 0) log(LogLevel.ERR, "Gagal menyalin $failed file APK.")
                refreshApkList()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        edtPairIp = findViewById(R.id.edtPairIp)
        edtPairPort = findViewById(R.id.edtPairPort)
        edtPairCode = findViewById(R.id.edtPairCode)
        edtConnIp = findViewById(R.id.edtConnIp)
        edtConnPort = findViewById(R.id.edtConnPort)
        txtLog = findViewById(R.id.txtLog)
        logScroll = findViewById(R.id.logScroll)
        txtConn = findViewById(R.id.txtConn)
        txtStatus = findViewById(R.id.txtStatus)
        txtApkTitle = findViewById(R.id.txtApkTitle)
        txtApkEmpty = findViewById(R.id.txtApkEmpty)
        txtDropSub = findViewById(R.id.txtDropSub)
        dropZone = findViewById(R.id.dropZone)
        ownerPill = findViewById(R.id.ownerPill)
        apkListBox = findViewById(R.id.apkListBox)
        statusDot = findViewById(R.id.statusDot)
        ownerDot = findViewById(R.id.ownerDot)
        txtOwner = findViewById(R.id.txtOwner)
        homePill = findViewById(R.id.homePill)
        homeDot = findViewById(R.id.homeDot)
        txtHome = findViewById(R.id.txtHome)
        progress = findViewById(R.id.progress)

        val btnPair: MaterialButton = findViewById(R.id.btnPair)
        val btnConnect: MaterialButton = findViewById(R.id.btnConnect)
        val btnConnectUsb: MaterialButton = findViewById(R.id.btnConnectUsb)
        val btnDisconnect: MaterialButton = findViewById(R.id.btnDisconnect)
        val btnInstallOnly: MaterialButton = findViewById(R.id.btnInstallOnly)
        val btnOwner: MaterialButton = findViewById(R.id.btnOwner)
        val btnHome: MaterialButton = findViewById(R.id.btnSetHome)
        val btnAll: MaterialButton = findViewById(R.id.btnAll)
        val btnClearKaspersky: MaterialButton = findViewById(R.id.btnClearKaspersky)
        val btnUninstallKaspersky: MaterialButton = findViewById(R.id.btnUninstallKaspersky)
        val btnCopyLog: MaterialButton = findViewById(R.id.btnCopyLog)
        val btnClearLog: MaterialButton = findViewById(R.id.btnClearLog)
        buttons = listOf(btnPair, btnConnect, btnConnectUsb, btnDisconnect,
            btnInstallOnly, btnOwner, btnHome, btnAll, btnClearKaspersky, btnUninstallKaspersky)
        needsConnection = setOf(btnDisconnect, btnInstallOnly, btnOwner, btnHome, btnAll, btnClearKaspersky, btnUninstallKaspersky)
        blockedWhenConnected = setOf(btnConnect, btnConnectUsb)

        // Log (UI thread) → controller memakai callback ini dari thread kerja.
        val app = application as App
        // Pantau penggeseran dari recent apps (putus koneksi + kosongkan daftar APK).
        try { startService(Intent(this, CleanupService::class.java)) } catch (e: Throwable) { }
        adb = app.adb
        app.logListener = { level, line -> runOnUiThread { log(level, line) } }
        // Koneksi bisa masih hidup dari Activity sebelumnya: pulihkan tampilan, jangan sambung ulang.
        if (adb.isConnected && adb.sessionLabel != null) {
            connectedAddr = adb.sessionLabel
            connectedSince = adb.sessionSince
            lastProbe = System.currentTimeMillis()
        }
        // Izin notifikasi (Android 13+) agar notifikasi "koneksi aktif" terlihat.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        adb.onProgress = { idx, total, name, sent, size -> runOnUiThread { showInstallProgress(idx, total, name, sent, size) } }

        // Siapkan kunci ADB di thread latar (bukan UI thread).
        io.execute { adb.warmUp() }
        // Log punya scroll sendiri di dalam ScrollView halaman: cegah halaman merebut gesture.
        logScroll.setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }

        ownerPill.setOnClickListener {
            if (busy) return@setOnClickListener
            if (!adb.isConnected) { log(LogLevel.WARN, AdbController.MSG_NOT_CONNECTED); return@setOnClickListener }
            run("Cek Device Owner") {
                val st = adb.checkDeviceOwner()
                if (st != OwnerState.Unknown) ownerState = st
                st != OwnerState.Unknown
            }
        }
        homePill.setOnClickListener {
            if (busy) return@setOnClickListener
            if (!adb.isConnected) { log(LogLevel.WARN, AdbController.MSG_NOT_CONNECTED); return@setOnClickListener }
            run("Cek Launcher Default") {
                val st = adb.checkDefaultLauncher()
                if (st != HomeState.Unknown) homeState = st
                st != HomeState.Unknown
            }
        }
        tabMode = findViewById(R.id.tabMode)
        val tabWireless: View = findViewById(R.id.tabWireless)
        val tabUsb: View = findViewById(R.id.tabUsb)
        tabMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val usb = tab.position == 1
                tabWireless.visibility = if (usb) View.GONE else View.VISIBLE
                tabUsb.visibility = if (usb) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        btnPair.setOnClickListener {
            val addr = addr(edtPairIp.text.toString(), edtPairPort.text.toString())
            val code = clean(edtPairCode.text.toString())
            run("Pairing") { doPair(addr, code) }
        }
        btnConnect.setOnClickListener {
            val addr = addr(edtConnIp.text.toString(), edtConnPort.text.toString())
            run("Connect") { doConnect(addr) }
        }
        btnConnectUsb.setOnClickListener {
            if (busy) return@setOnClickListener
            val targets = adb.listUsbTargets()
            if (targets.isEmpty()) {
                val t0 = System.currentTimeMillis()
                logHead("Connect USB", t0)
                log(LogLevel.ERR, "Tidak ada HP target di port USB. Colok lewat kabel OTG, aktifkan USB debugging di Opsi Developer HP target, dan pilih mode USB 'Transfer file'.")
                logEnd("Connect USB", false, t0)
                return@setOnClickListener
            }
            val names = targets.map { it.label + "\n" + it.device.deviceName }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Pilih perangkat USB")
                .setItems(names) { _, i ->
                    val t = targets[i]
                    showUsbDevice(t.label)
                    run("Connect USB") { doConnectUsb(t) }
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        btnDisconnect.setOnClickListener {
            run("Disconnect") { doDisconnect() }
        }
        dropZone.setOnClickListener {
            if (busy || !(connectedAddr != null && adb.isConnected)) return@setOnClickListener
            pickApks.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
        }
        btnInstallOnly.setOnClickListener {
            val list = apks.toList()
            run("Install APK") { adb.install(list) }
        }
        btnOwner.setOnClickListener {
            run("Set Device Owner") { doSetOwner() }
        }
        btnHome.setOnClickListener {
            run("Set Launcher Default") { adb.setDefaultLauncher() }
        }
        btnClearKaspersky.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Hapus data Kaspersky?")
                .setMessage("Menjalankan: pm clear ${AdbController.KASPERSKY_PACKAGE}\n\nSemua data & pengaturan aplikasi Kaspersky di HP target akan dihapus.\n\nSetelah itu lanjut ke Langkah 2 (matikan Aplikasi Admin Kaspersky di HP).")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ ->
                    run("Hapus Data Kaspersky") { adb.clearKasperskyData() }
                }
                .show()
        }
        btnUninstallKaspersky.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Uninstall Kaspersky?")
                .setMessage("Menjalankan: pm uninstall -k --user 0 ${AdbController.KASPERSKY_PACKAGE}\n\nPastikan Langkah 2 (Aplikasi Admin Kaspersky) sudah dinonaktifkan, kalau tidak uninstall akan ditolak.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Uninstall") { _, _ ->
                    run("Uninstall Kaspersky") { adb.uninstallKaspersky() }
                }
                .show()
        }
        btnAll.setOnClickListener {
            // Otomatis hanya: install APK + set device owner. Pair & Connect dilakukan manual sebelumnya.
            val list = apks.toList()
            run("Install APK + Set Owner") { adb.installAndOwner(list) }
        }
        btnCopyLog.setOnClickListener {
            if (txtLog.length() > 0) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", txtLog.text))
                Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show()
            }
        }
        btnClearLog.setOnClickListener {
            txtLog.text = ""
        }

        // ---- PIN ----
        pinStore = PinStore(this)
        if (!pinStore.isSet) pinStore.set(PinStore.DEFAULT_PIN)
        onBackPressedDispatcher.addCallback(this, backWhileLocked)
        txtSession = findViewById(R.id.txtSession)
        findViewById<View>(R.id.btnLogout).setOnClickListener { showLock() }
        showLock()

        restorePickedApks()
        refreshApkList()
        refreshHeader()
    }

    override fun onStart() {
        super.onStart()
        // Kunci ulang bila aplikasi ditinggalkan lebih lama dari batas waktu (koneksi ADB tetap berjalan).
        if (lockView == null && !busy && sessionRemainingMs() <= 0) showLock()
        pollTask?.cancel(false)
        pollTask = poller.scheduleWithFixedDelay({ pollConnection() }, 0, 1, TimeUnit.SECONDS)
    }

    override fun onStop() {
        super.onStop()
        pollTask?.cancel(false)
        pollTask = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideInstallProgress()
        worker.shutdown()
        io.shutdown()
        poller.shutdownNow()
        // Koneksi ADB sengaja TIDAK ditutup di sini: ia hidup di App + ConnectionService.
        // Hanya lepas pendengar log milik Activity ini.
        (application as App).logListener = null
        adb.onProgress = null
    }

    // ---------- Kunci PIN ----------

    private fun showLock() {
        if (isFinishing || lockView != null) return
        val v = PinLockView(this, pinStore) { removeLock() }
        lockView = v
        addContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        backWhileLocked.isEnabled = true
        currentFocus?.clearFocus()
    }

    /** Sisa waktu sesi (ms) sebelum aplikasi terkunci otomatis. Hitung mundur dimulai saat login dan TIDAK direset oleh sentuhan layar. */
    private fun sessionRemainingMs() = LOCK_TIMEOUT_MS - (SystemClock.elapsedRealtime() - lastActivityAt)

    /** Dipanggil tiap detik dari pemantau. Saat operasi (install dll) berjalan, timer ditahan penuh. */
    private fun updateSessionTimer() {
        if (lockView != null) return
        if (busy) lastActivityAt = SystemClock.elapsedRealtime()
        val left = sessionRemainingMs()
        if (left <= 0) { showLock(); return }
        val sec = (left + 999) / 1000
        txtSession.text = if (sec >= 3600) "Sesi %d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
                          else "Sesi %02d:%02d".format(sec / 60, sec % 60)
        txtSession.setTextColor(if (sec <= 60) Color.rgb(251, 191, 36) else ContextCompat.getColor(this, R.color.header_subtext))
    }

    private fun removeLock() {
        lockView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        lockView = null
        backWhileLocked.isEnabled = false
        lastActivityAt = SystemClock.elapsedRealtime()
        updateSessionTimer()
    }

    // ---------- Status koneksi live ----------

    /** Jalan di thread pemantau tiap 1 detik. */
    private fun pollConnection() {
        try {
            val wasBusy = busy
            var alive = adb.isConnected
            var cableGone = false
            // Kabel USB dicabut: terdeteksi seketika (tanpa menunggu probe 3x gagal).
            if (!wasBusy && adb.usbCableGone) {
                adb.dropUsb()
                alive = false
                cableGone = true
                probeFails = 0
            }
            val now = System.currentTimeMillis()
            // Probe sungguhan tiap ±8 detik (hanya saat tidak ada operasi lain) untuk mendeteksi koneksi mati diam-diam.
            if (alive && !wasBusy && now - lastProbe >= 8_000) {
                lastProbe = now
                // Cek busy lagi di dalam lock: worker menunggu lock yang sama sebelum mulai bekerja.
                synchronized(adb.probeLock) {
                    if (!busy) {
                        alive = adb.probe()
                        // Toleransi: HP target yang sibuk (dexopt/verifikasi APK) bisa telat membalas; putus baru dinyatakan setelah 3x gagal beruntun.
                        if (alive) probeFails = 0 else if (++probeFails < 3) alive = true
                    }
                }
            }
            runOnUiThread { applyLiveState(alive, wasBusy, cableGone) }
        } catch (e: Throwable) { }
    }

    private fun applyLiveState(alive: Boolean, polledWhileBusy: Boolean, cableGone: Boolean = false) {
        if (isFinishing) return
        updateSessionTimer()
        val addr = connectedAddr
        // Saat operasi berjalan (mis. connect/install) jangan simpulkan putus: hasilnya bisa basi.
        if (addr != null && !alive && !polledWhileBusy && !busy) {
            log(LogLevel.ERR, if (cableGone) "Kabel USB dicabut: koneksi ke $addr terputus." else "Koneksi ke $addr terputus.")
            showUsbDevice(null)
            lostAddr = addr
            adb.sessionLabel = null
            connectedAddr = null
            connectedSince = 0L
            ownerState = OwnerState.Unknown; homeState = HomeState.Unknown
        }
        refreshHeader()
    }

    // ---------- Langkah (jalan di thread kerja) ----------

    private fun doPair(addr: String, code: String): Boolean {
        val ok = adb.pair(addr, code)
        if (ok) runOnUiThread { refreshHeader() }
        return ok
    }

    private fun doConnect(addr: String): Boolean {
        val ok = adb.connect(addr)
        if (ok) {
            connectedAddr = addr
            connectedSince = System.currentTimeMillis()
            adb.sessionLabel = addr; adb.sessionSince = connectedSince
            lostAddr = null
            lastProbe = connectedSince
            showUsbDevice(null)
            updateOwner()
        } else {
            // Connect gagal: koneksi lama sudah diputus, jangan tampilkan alamat lama sebagai terhubung.
            connectedAddr = null
            connectedSince = 0L
            ownerState = OwnerState.Unknown; homeState = HomeState.Unknown
        }
        runOnUiThread { refreshHeader() }
        return ok
    }

    /** Tampilkan nama device yang dipilih di tab USB (null = sembunyikan). Aman dipanggil dari thread mana pun. */
    private fun showUsbDevice(label: String?) = runOnUiThread {
        findViewById<TextView>(R.id.txtUsbDevice).apply {
            text = if (label == null) "" else "Perangkat: $label"
            visibility = if (label == null) View.GONE else View.VISIBLE
        }
    }

    private fun doConnectUsb(t: UsbAdbBridge.Target): Boolean {
        val label = adb.connectUsb(t)
        if (label != null) {
            connectedAddr = label
            connectedSince = System.currentTimeMillis()
            adb.sessionLabel = label; adb.sessionSince = connectedSince
            lostAddr = null
            lastProbe = connectedSince
            updateOwner()
        } else {
            connectedAddr = null
            connectedSince = 0L
            ownerState = OwnerState.Unknown; homeState = HomeState.Unknown
            showUsbDevice(null)
        }
        runOnUiThread { refreshHeader() }
        return label != null
    }

    /** Cek ulang device owner di HP target (panggil dari thread kerja). */
    private fun updateOwner() {
        ownerState = adb.queryDeviceOwner()
        homeState = adb.queryDefaultLauncher()
        runOnUiThread { refreshHeader() }
    }

    private fun doDisconnect(): Boolean {
        val ok = adb.disconnect()
        if (ok) { connectedAddr = null; connectedSince = 0L; lostAddr = null; ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; showUsbDevice(null) }
        runOnUiThread { refreshHeader() }
        return ok
    }

    private fun doSetOwner(): Boolean {
        val ok = adb.setDeviceOwner()
        runOnUiThread { refreshHeader() }
        return ok
    }

    private fun run(title: String, work: () -> Boolean) {
        if (busy) return
        busy = true
        setBusy(true, "Sedang berjalan: $title...")
        val startedAt = System.currentTimeMillis()
        logHead(title, startedAt)
        worker.execute {
            // Tunggu probe yang sedang berjalan (bila ada) selesai sebelum memakai koneksi.
            synchronized(adb.probeLock) { }
            var ok = false
            try { ok = work() } catch (e: Throwable) {
                runOnUiThread { log(LogLevel.ERR, "Kesalahan tak terduga: ${e.message ?: e.javaClass.simpleName}") }
            }
            // Perbarui status device owner setelah setiap operasi (install / set owner / dll).
            try { if (adb.isConnected) { val st = adb.queryDeviceOwner(); if (st != OwnerState.Unknown) ownerState = st; val hs = adb.queryDefaultLauncher(); if (hs != HomeState.Unknown) homeState = hs } } catch (e: Throwable) { }
            runOnUiThread {
                logEnd(title, ok, startedAt)
                busy = false
                setBusy(false, if (ok) "Selesai: $title" else "Gagal: $title (lihat log)")
                refreshHeader()
            }
        }
    }

    // ---------- Progress install (inline di halaman, di atas log) ----------

    private var progShown = false

    private fun showInstallProgress(idx: Int, total: Int, name: String, sent: Long, size: Long) {
        if (isFinishing || !busy) return
        val box = findViewById<View>(R.id.progBox)
        val bar = findViewById<ProgressBar>(R.id.barInstall)
        val n = findViewById<TextView>(R.id.txtProgName)
        val info = findViewById<TextView>(R.id.txtProgInfo)
        if (!progShown) {
            progShown = true
            box.visibility = View.VISIBLE
            // Gulir halaman sekali agar progress terlihat.
            box.post { box.requestRectangleOnScreen(android.graphics.Rect(0, 0, box.width, box.height)) }
        }
        n.text = "Memasang APK $idx dari $total: $name"
        if (size > 0 && sent >= size) {
            bar.isIndeterminate = true
            info.text = "Terkirim ${Fmt.mb(size)}. Menunggu HP target memasang..."
        } else {
            bar.isIndeterminate = false
            val pct = if (size > 0) (sent * 100 / size).toInt() else 0
            bar.progress = if (size > 0) (sent * 1000 / size).toInt() else 0
            info.text = "$pct%  (${Fmt.mb(sent)} / ${Fmt.mb(size)})"
        }
    }

    private fun hideInstallProgress() {
        if (!progShown) return
        progShown = false
        findViewById<View>(R.id.progBox)?.visibility = View.GONE
    }

    private fun setBusy(on: Boolean, status: String) {
        if (!on) hideInstallProgress()
        adb.holdLocks(on)
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyButtonState(on)
        apkListBox.alpha = if (on) 0.5f else 1f
        progress.visibility = if (on) View.VISIBLE else View.GONE
        txtStatus.text = status
    }

    // ---------- UI helper ----------

    private fun refreshHeader() {
        val addr = connectedAddr
        val lost = lostAddr
        val on = addr != null && adb.isConnected
        when {
            on -> {
                val s = ((System.currentTimeMillis() - connectedSince) / 1000).coerceAtLeast(0)
                val dur = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
                          else "%02d:%02d".format(s / 60, s % 60)
                txtConn.text = "Terhubung · $addr · $dur"
                statusDot.backgroundTintList = ColorStateList.valueOf(Color.rgb(74, 222, 128))
            }
            lost != null -> {
                txtConn.text = "Terputus · $lost · Connect ulang"
                statusDot.backgroundTintList = ColorStateList.valueOf(Color.rgb(248, 113, 113))
            }
            else -> {
                txtConn.text = "Belum terhubung · Pair lalu Connect, atau Connect USB"
                statusDot.backgroundTintList = ColorStateList.valueOf(Color.rgb(148, 163, 184))
            }
        }
        refreshOwner()
        refreshHome()
        applyButtonState(busy)
    }

    /**
     * Atur status tombol: semua tombol mati saat ada proses berjalan; tombol yang butuh device
     * (Pasang APK, set-device-owner, Otomatis, Hapus data & Uninstall Kaspersky) juga mati selama belum terhubung.
     */
    private fun applyButtonState(isBusy: Boolean) {
        val connected = connectedAddr != null && adb.isConnected
        buttons.forEach { b ->
            val enabled = !isBusy && (connected || b !in needsConnection) && !(connected && b in blockedWhenConnected)
            b.isEnabled = enabled
            // Beberapa gaya tombol (mis. DangerFilled) tidak punya tampilan "disabled" sendiri.
            b.alpha = if (enabled) 1f else 0.4f
        }
        // Area pilih file APK: hanya bisa ditekan saat sudah terhubung dan tidak sedang ada proses.
        if (::dropZone.isInitialized) {
            val pickEnabled = !isBusy && connected
            dropZone.isEnabled = pickEnabled
            dropZone.isClickable = pickEnabled
            dropZone.alpha = if (pickEnabled) 1f else 0.4f
        }
        // Tab metode koneksi: yang tidak sedang dipakai terkunci selama ada koneksi aktif
        // (wireless terhubung -> tab USB mati; USB terhubung -> tab Wireless mati).
        setModeTabsEnabled(wirelessEnabled = !(connected && adb.isViaUsb), usbEnabled = !(connected && !adb.isViaUsb))
        // Tombol hapus di daftar aplikasi: hanya bisa ditekan saat terhubung dan tidak sedang ada proses.
        setApkRemoveButtonsEnabled(!isBusy && connected)
        // Kotak status Device Owner (ketuk untuk cek ulang): hanya bisa ditekan saat terhubung dan tidak sibuk.
        if (::homePill.isInitialized) {
            val checkEnabled = !isBusy && connected
            homePill.isEnabled = checkEnabled
            homePill.isClickable = checkEnabled
            homePill.alpha = if (checkEnabled) 1f else 0.5f
        }
        if (::ownerPill.isInitialized) {
            val checkEnabled = !isBusy && connected
            ownerPill.isEnabled = checkEnabled
            ownerPill.isClickable = checkEnabled
            ownerPill.alpha = if (checkEnabled) 1f else 0.5f
        }
    }

    private fun setModeTabsEnabled(wirelessEnabled: Boolean, usbEnabled: Boolean) {
        if (!::tabMode.isInitialized) return
        listOf(0 to wirelessEnabled, 1 to usbEnabled).forEach { (pos, enabled) ->
            val tab = tabMode.getTabAt(pos) ?: return@forEach
            tab.view.isEnabled = enabled
            tab.view.isClickable = enabled
            tab.view.alpha = if (enabled) 1f else 0.4f
        }
        // Jaga-jaga: kalau tab yang terkunci sedang terbuka, pindah ke tab yang masih aktif.
        val sel = tabMode.selectedTabPosition
        if (sel == 1 && !usbEnabled) tabMode.getTabAt(0)?.select()
        else if (sel == 0 && !wirelessEnabled) tabMode.getTabAt(1)?.select()
    }

    private fun setApkRemoveButtonsEnabled(enabled: Boolean) {
        if (!::apkListBox.isInitialized) return
        for (i in 0 until apkListBox.childCount) {
            apkListBox.getChildAt(i).findViewById<View>(R.id.apkRemoveTag)?.let {
                it.isEnabled = enabled
                it.isClickable = enabled
                it.alpha = if (enabled) 1f else 0.3f
            }
        }
    }

    private fun refreshHome() {
        val connected = connectedAddr != null && adb.isConnected
        val st = if (connected) homeState else HomeState.Unknown
        val (text, color) = when (st) {
            is HomeState.Hmdm -> "HMDM Launcher aktif ✓" to Color.rgb(74, 222, 128)
            is HomeState.Other -> "Launcher lain (${st.component.substringBefore('/')})" to Color.rgb(248, 113, 113)
            HomeState.None -> "Belum ada default" to Color.rgb(251, 191, 36)
            HomeState.Unknown ->
                (if (connected) "Belum diperiksa" else "Belum terhubung") to Color.rgb(148, 163, 184)
        }
        txtHome.text = text
        homeDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun refreshOwner() {
        val connected = connectedAddr != null && adb.isConnected
        val st = if (connected) ownerState else OwnerState.Unknown
        val (text, color) = when (st) {
            is OwnerState.Hmdm -> "HMDM aktif ✓" to Color.rgb(74, 222, 128)
            is OwnerState.Other -> "Aplikasi lain (${st.pkg})" to Color.rgb(248, 113, 113)
            OwnerState.None -> "Belum diset" to Color.rgb(251, 191, 36)
            OwnerState.Unknown ->
                (if (connected) "Belum diperiksa" else "Belum terhubung") to Color.rgb(148, 163, 184)
        }
        txtOwner.text = text
        ownerDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun refreshApkList() {
        if (apks.isEmpty()) {
            txtApkEmpty.text = "Pilih File APK Di Sini"
            txtDropSub.text = "Dapat memilih lebih dari satu file APK"
            txtApkTitle.text = "Unggah File APK (Bisa Banyak)"
        } else {
            txtApkEmpty.text = "${apks.size} APK dipilih"
            txtDropSub.text = "Ketuk untuk memilih ulang (daftar sebelumnya diganti)"
            txtApkTitle.text = "Unggah File APK (${apks.size})"
        }
        renderApkList()
        refreshHeader()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Tampilkan daftar APK yang akan dipasang (urut sesuai nama) lengkap dengan ukuran & tombol hapus. */
    private fun renderApkList() {
        apkListBox.removeAllViews()
        if (apks.isEmpty()) { apkListBox.visibility = View.GONE; return }
        apkListBox.visibility = View.VISIBLE

        apkListBox.addView(TextView(this).apply {
            text = "DAFTAR APLIKASI YANG AKAN DIPASANG (${apks.size})"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            setPadding(dp(2), 0, 0, dp(6))
        })

        apks.forEachIndexed { i, item ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(12), dp(8), dp(4), dp(8))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (i > 0) topMargin = dp(6) }
            }
            row.addView(TextView(this).apply {
                text = "${i + 1}"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                minWidth = dp(22)
            })
            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = item.name
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            col.addView(TextView(this).apply {
                text = item.sizeText
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            })
            row.addView(col)
            row.addView(android.widget.ImageButton(this).apply {
                id = R.id.apkRemoveTag
                setImageResource(R.drawable.ic_delete)
                setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.danger))
                background = null
                contentDescription = "Hapus ${item.name} dari daftar"
                val canRemove = !busy && connectedAddr != null && adb.isConnected
                isEnabled = canRemove
                isClickable = canRemove
                alpha = if (canRemove) 1f else 0.3f
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener {
                    if (busy || !(connectedAddr != null && adb.isConnected)) return@setOnClickListener
                    apks.remove(item)
                    if (!item.builtIn) io.execute { item.file.delete() }
                    refreshApkList()
                }
            })
            apkListBox.addView(row)
        }
    }

    /** Pembuka satu proses: "── Judul · 14:02:11". */
    private fun logHead(title: String, at: Long = System.currentTimeMillis()) =
        log(LogLevel.HEAD, "$title · ${Fmt.clock(at)}")

    /** Penutup satu proses: "✓ Selesai: Judul · 4.2 dtk" atau "✗ Gagal: Judul · 4.2 dtk", lalu baris kosong pemisah. */
    private fun logEnd(title: String, ok: Boolean, startedAt: Long) {
        val took = Fmt.duration(System.currentTimeMillis() - startedAt)
        if (ok) log(LogLevel.END_OK, "Selesai: $title · $took")
        else log(LogLevel.END_ERR, "Gagal: $title · $took")
        logBlank()
    }

    /** Satu baris kosong sebagai pemisah antar proses. */
    private fun logBlank() {
        txtLog.append("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Satu entri log berwarna. Warna dan penanda ditentukan oleh [level];
     * pesan multi-baris dirapikan (baris lanjutan diberi indent selebar penanda), dan titik di
     * akhir pesan dibuang supaya semua baris seragam (elipsis "..." dibiarkan).
     */
    private fun log(level: LogLevel, s: String) {
        if (s.isBlank()) return
        val indent = " ".repeat(level.marker.length)
        val body = s.trimEnd().let {
            // Output mentah device (DETAIL) tidak diubah sama sekali.
            if (level != LogLevel.DETAIL && it.endsWith(".") && !it.endsWith("..")) it.dropLast(1) else it
        }
        val lines = body.lines().map { it.trimEnd() }
        val text = lines.mapIndexed { i, l -> (if (i == 0) level.marker else indent) + l }.joinToString("\n") + "\n"
        val sp = SpannableString(text)
        sp.setSpan(ForegroundColorSpan(levelColor(level)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (level.bold) sp.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        txtLog.append(sp)
        trimLog()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Batasi panjang log agar tidak membengkak: buang baris paling lama (dipotong di batas baris). */
    private fun trimLog() {
        val ed = txtLog.text as? android.text.Editable ?: return
        if (ed.length <= LOG_MAX_CHARS) return
        val cut = ed.indexOf("\n", ed.length - LOG_KEEP_CHARS)
        ed.delete(0, if (cut < 0) ed.length - LOG_KEEP_CHARS else cut + 1)
    }

    private fun levelColor(level: LogLevel): Int = when (level) {
        LogLevel.HEAD, LogLevel.SUMMARY -> Color.WHITE
        LogLevel.STEP -> Color.rgb(252, 211, 77)
        LogLevel.CMD -> Color.rgb(56, 189, 248)
        LogLevel.OK, LogLevel.END_OK -> Color.rgb(74, 222, 128)
        LogLevel.WARN -> Color.rgb(251, 191, 36)
        LogLevel.ERR, LogLevel.END_ERR -> Color.rgb(248, 113, 113)
        LogLevel.DETAIL -> Color.rgb(148, 163, 184)
    }

    private fun clean(s: String) = s.trim().replace("\"", "").replace(" ", "")

    /** Gabung IP + port jadi "ip:port"; toleran bila user menempel "ip:port" di kolom IP. */
    private fun addr(ipText: String, portText: String): String {
        var ip = clean(ipText)
        var port = clean(portText)
        val i = ip.lastIndexOf(':')
        if (i > 0 && port.isEmpty()) {
            port = ip.substring(i + 1)
            ip = ip.substring(0, i)
        }
        return if (ip.isEmpty() || port.isEmpty()) "" else "$ip:$port"
    }

    /** Muat ulang APK yang sudah disalin ke cache (mis. setelah proses aplikasi dimatikan sistem). */
    private fun restorePickedApks() {
        val dir = File(cacheDir, "picked")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) } ?: return
        files.sortedBy { it.name.lowercase() }.forEach { apks.add(ApkItem(it.name, it)) }
    }

    private fun displayName(uri: Uri): String {
        var name = "file-${System.currentTimeMillis()}.apk"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (e: Exception) { }
        // Cegah path traversal dari nama file yang aneh.
        return name.substringAfterLast('/')
    }

    /** Salin ke cache. Dipanggil dari thread latar (bukan UI thread). */
    private fun copyToCache(uri: Uri, name: String): ApkItem? {
        val dir = File(cacheDir, "picked").apply { mkdirs() }
        val f = File(dir, name)
        return try {
            val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("tidak bisa membuka file")
            input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            ApkItem(name, f)
        } catch (e: Exception) {
            f.delete() // jangan tinggalkan file setengah jadi
            runOnUiThread { log(LogLevel.ERR, "Gagal membaca $name: ${e.message ?: e.javaClass.simpleName}") }
            null
        }
    }

    companion object {
        /** Aplikasi dikunci lagi bila ditinggalkan lebih dari ini (ms). */
        private const val LOCK_TIMEOUT_MS = 30 * 60_000L
        private const val LOG_MAX_CHARS = 60_000
        private const val LOG_KEEP_CHARS = 40_000
    }
}
