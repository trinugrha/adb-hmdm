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
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    /** Swipe horizontal di mana saja pindah ke tab sebelah (Wireless <-> USB), selama tab tujuan tidak terkunci. */
    private val swipeDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || !::tabMode.isInitialized) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val d = resources.displayMetrics.density
                if (Math.abs(dx) < 80 * d || Math.abs(dx) < 2 * Math.abs(dy) || Math.abs(vx) < 400 * d) return false
                val target = tabMode.selectedTabPosition + if (dx < 0) 1 else -1
                val tab = tabMode.getTabAt(target) ?: return false
                if (!tab.view.isEnabled) return false
                tab.select()
                return true
            }
        })
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var kbHandledForGesture = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeDetector.onTouchEvent(ev)
        // Geser layar ke atas/bawah => keyboard menutup.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
                kbHandledForGesture = false
            }
            MotionEvent.ACTION_MOVE -> if (!kbHandledForGesture) {
                val dx = Math.abs(ev.x - touchDownX)
                val dy = Math.abs(ev.y - touchDownY)
                if (dy > ViewConfiguration.get(this).scaledTouchSlop && dy > dx) {
                    kbHandledForGesture = true
                    hideKeyboard()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hideKeyboard() {
        val visible = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        val focus = currentFocus ?: return
        if (!visible) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(focus.windowToken, 0)
        focus.clearFocus()
    }

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
    private lateinit var btnRefreshOwner: View
    private lateinit var apkListBox: android.widget.LinearLayout
    private lateinit var tabMode: TabLayout
    private lateinit var statusDot: View
    private lateinit var ownerDot: View
    private lateinit var txtOwner: TextView
    private lateinit var tcpipDot: View
    private lateinit var txtTcpip: TextView
    private lateinit var appsPill: View
    private lateinit var btnRefreshInfo: View
    private lateinit var appsDot: View
    private lateinit var txtApps: TextView
    private lateinit var txtAppsDetail: TextView
    private lateinit var homeDot: View
    private lateinit var txtHome: TextView
    private lateinit var permAdminDot: View
    private lateinit var txtPermAdmin: TextView
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
    /** Apakah com.kaspersky.kes terpasang di HP target: null = belum diketahui (tombol Kaspersky mati). */
    @Volatile private var kasperskyInstalled: Boolean? = null
    /** Apakah com.hmdm.launcher terpasang di HP target: null = belum diketahui (tombol Owner/Launcher mati). */
    @Volatile private var hmdmInstalled: Boolean? = null
    /** Apakah adbd HP target sudah berjalan dalam mode TCP/IP (port aktif): true = tombol "Ubah Mode Nirkabel (TCP/IP)" dimatikan. */
    @Volatile private var tcpipActive: Boolean? = null
    /** Status tiap aplikasi wajib (AdbController.REQUIRED_PACKAGES) di HP target: null = belum diketahui. */
    @Volatile private var appsInstalled: Map<String, Boolean>? = null
    /** True sesaat setelah Connect berhasil: Informasi Status (instalasi, launcher, permission) jangan dibaca otomatis; baru dibaca saat kartunya diketuk. */
    @Volatile private var skipInfoRefresh = false
    /** Jenis perangkat yang dipilih untuk ceklist instalasi: POS / TABLET / PDA (disimpan di preferensi). */
    private var deviceType = "POS"
    private lateinit var typeChips: Map<String, android.widget.CheckBox>
    /** Status tiga permission com.hmdm.launcher di HP target: null = belum diketahui. */
    @Volatile private var hmdmPerms: HmdmPermissions? = null

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
        if (skipped > 0) log(LogLevel.WARN, "$skipped file dilewati (bukan .apk atau duplikat)")
        if (todo.isEmpty()) return@registerForActivityResult
        // Penyalinan file (bisa puluhan-ratusan MB) di thread latar.
        // Tandai sibuk selama menyalin: tombol Pasang dan pilih file mati, jadi daftar tidak dipakai saat APK lama sudah dihapus.
        val old = apks.toList()
        busy = true
        setBusy(true, "Menyalin APK...")
        io.execute {
            val items = mutableListOf<ApkItem>()
            var failed = 0
            try {
                // Hapus file APK lama dari cache dulu (sebelum menyalin, agar nama yang sama tidak ikut terhapus).
                old.filter { !it.builtIn }.forEach { it.file.delete() }
                todo.forEach { (uri, name) ->
                    val item = copyToCache(uri, name)
                    if (item == null) failed++ else items.add(item)
                }
                // Baca nama paket tiap APK di thread latar, supaya UI thread tidak perlu mem-parsing file besar.
                items.forEach { apkPackage(it) }
            } finally {
                runOnUiThread {
                    apks.clear()
                    apks.addAll(items)
                    if (failed > 1) log(LogLevel.ERR, "Salin APK gagal: $failed dari ${todo.size} file. Pilih ulang file yang gagal")
                    busy = false
                    setBusy(false, if (items.isEmpty()) "Belum ada APK" else "${items.size} APK siap dipasang")
                    refreshApkList()
                }
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
        btnRefreshOwner = findViewById(R.id.btnRefreshOwner)
        apkListBox = findViewById(R.id.apkListBox)
        statusDot = findViewById(R.id.statusDot)
        ownerDot = findViewById(R.id.ownerDot)
        txtOwner = findViewById(R.id.txtOwner)
        tcpipDot = findViewById(R.id.tcpipDot)
        txtTcpip = findViewById(R.id.txtTcpip)
        appsPill = findViewById(R.id.appsPill)
        btnRefreshInfo = findViewById(R.id.btnRefreshInfo)
        typeChips = mapOf(
            "POS" to findViewById<android.widget.CheckBox>(R.id.typePos),
            "TABLET" to findViewById<android.widget.CheckBox>(R.id.typeTablet),
            "PDA" to findViewById<android.widget.CheckBox>(R.id.typePda)
        )
        val typePrefs = getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
        deviceType = typePrefs.getString("device_type", "POS")?.takeIf { it in AdbController.DEVICE_PACKAGES } ?: "POS"
        typeChips.forEach { (type, chip) ->
            chip.setOnClickListener {
                deviceType = type
                typePrefs.edit().putString("device_type", type).apply()
                refreshApps()
            }
        }
        appsDot = findViewById(R.id.appsDot)
        txtApps = findViewById(R.id.txtApps)
        txtAppsDetail = findViewById(R.id.txtAppsDetail)
        homeDot = findViewById(R.id.homeDot)
        txtHome = findViewById(R.id.txtHome)
        permAdminDot = findViewById(R.id.permAdminDot)
        txtPermAdmin = findViewById(R.id.txtPermAdmin)
        progress = findViewById(R.id.progress)

        val btnPair: MaterialButton = findViewById(R.id.btnPair)
        val btnPairQr: MaterialButton = findViewById(R.id.btnPairQr)
        val btnConnect: MaterialButton = findViewById(R.id.btnConnect)
        val btnConnectUsb: MaterialButton = findViewById(R.id.btnConnectUsb)
        val btnDisconnect: MaterialButton = findViewById(R.id.btnDisconnect)
        val btnInstallOnly: MaterialButton = findViewById(R.id.btnInstallOnly)
        val btnOwner: MaterialButton = findViewById(R.id.btnOwner)
        val btnAll: MaterialButton = findViewById(R.id.btnAll)
        val btnClearKaspersky: MaterialButton = findViewById(R.id.btnClearKaspersky)
        val btnUninstallKaspersky: MaterialButton = findViewById(R.id.btnUninstallKaspersky)
        val btnTcpip: MaterialButton = findViewById(R.id.btnTcpip)
        val btnCopyLog: MaterialButton = findViewById(R.id.btnCopyLog)
        val btnClearLog: MaterialButton = findViewById(R.id.btnClearLog)
        val btnTerminalRun: MaterialButton = findViewById(R.id.btnTerminalRun)
        buttons = listOf(btnPair, btnPairQr, btnConnect, btnConnectUsb, btnDisconnect,
            btnInstallOnly, btnOwner, btnAll, btnClearKaspersky, btnUninstallKaspersky, btnTcpip, btnTerminalRun)
        needsConnection = setOf(btnDisconnect, btnInstallOnly, btnOwner, btnAll, btnClearKaspersky, btnUninstallKaspersky, btnTcpip)
        blockedWhenConnected = setOf(btnPair, btnPairQr, btnConnect, btnConnectUsb)

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
        // Bila aplikasi sempat crash sebelumnya, tampilkan jejaknya di log (lalu hapus) agar penyebabnya bisa dibaca / disalin.
        io.execute {
            val f = java.io.File(filesDir, App.CRASH_FILE)
            val txt = try { if (f.exists()) f.readText().take(1500) else null } catch (e: Throwable) { null }
            if (txt != null) {
                f.delete()
                runOnUiThread { log(LogLevel.ERR, "Aplikasi sempat berhenti tiba-tiba. Jejak terakhir:"); txt.lines().take(14).forEach { log(LogLevel.DETAIL, it) } }
            }
        }
        setupTerminal(btnTerminalRun)
        // Log punya scroll sendiri di dalam ScrollView halaman: cegah halaman merebut gesture.
        logScroll.setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }

        // Status MDM Mode hanya dicek ulang lewat ikon refresh (bukan ketuk kotak status).
        btnRefreshOwner.setOnClickListener {
            if (busy) return@setOnClickListener
            if (!adb.isConnected) { log(LogLevel.WARN, AdbController.MSG_NOT_CONNECTED); return@setOnClickListener }
            // Hanya status MDM Mode yang dibaca; instalasi, launcher, dan permission tidak ikut diperbarui.
            skipInfoRefresh = true
            run("Cek Status MDM Mode") {
                val st = adb.checkDeviceOwner()
                if (st != OwnerState.Unknown) ownerState = st
                st != OwnerState.Unknown
            }
        }
        // Informasi Status hanya dibaca lewat tombol refresh (bukan ketuk area kartu).
        btnRefreshInfo.setOnClickListener {
            if (busy) return@setOnClickListener
            if (!adb.isConnected) { log(LogLevel.WARN, AdbController.MSG_NOT_CONNECTED); return@setOnClickListener }
            val pkgs = AdbController.DEVICE_PACKAGES[deviceType]
            // Hasil cek manual sudah dipakai kartu; jangan dibaca ulang diam-diam (bisa menimpa dengan hasil terpotong).
            skipInfoRefresh = true
            run("Cek Informasi Status") {
                val r = adb.checkRequiredPackages(pkgs)
                if (r != null) appsInstalled = r
                val st = adb.checkDefaultLauncher()
                if (st != HomeState.Unknown) homeState = st
                val pr = adb.checkHmdmPermissions()
                if (pr != null) hmdmPerms = pr
                r != null || st != HomeState.Unknown || pr != null
            }
        }
        val btnMoreToggle: View = findViewById(R.id.btnMoreToggle)
        val moreContainer: View = findViewById(R.id.moreContainer)
        val txtMoreToggle: TextView = findViewById(R.id.txtMoreToggle)
        val icMoreToggle: View = findViewById(R.id.icMoreToggle)
        btnMoreToggle.setOnClickListener {
            val show = moreContainer.visibility != View.VISIBLE
            moreContainer.visibility = if (show) View.VISIBLE else View.GONE
            txtMoreToggle.text = if (show) "Sembunyikan" else "Klik lainnya"
            icMoreToggle.rotation = if (show) 180f else 0f
        }
        tabMode = findViewById(R.id.tabMode)
        val tabWireless: View = findViewById(R.id.tabWireless)
        val tabUsb: View = findViewById(R.id.tabUsb)
        val cardTcpip: View = findViewById(R.id.cardTcpip)
        tabMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val usb = tab.position == 1
                tabWireless.visibility = if (usb) View.GONE else View.VISIBLE
                tabUsb.visibility = if (usb) View.VISIBLE else View.GONE
                cardTcpip.visibility = if (usb) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        btnPair.setOnClickListener {
            if (connectedAddr != null && adb.isConnected) return@setOnClickListener
            val addr = addr(edtPairIp.text.toString(), edtPairPort.text.toString())
            val code = clean(edtPairCode.text.toString())
            run("Pairing") { doPair(addr, code) }
        }
        btnPairQr.setOnClickListener {
            if (connectedAddr != null && adb.isConnected) return@setOnClickListener
            showQrPairing()
        }
        btnConnect.setOnClickListener {
            if (connectedAddr != null && adb.isConnected) return@setOnClickListener
            val addr = addr(edtConnIp.text.toString(), edtConnPort.text.toString())
            run("Connect") { doConnect(addr) }
        }
        btnConnectUsb.setOnClickListener {
            if (busy || (connectedAddr != null && adb.isConnected)) return@setOnClickListener
            val targets = adb.listUsbTargets()
            if (targets.isEmpty()) {
                val t0 = System.currentTimeMillis()
                logHead("Connect USB", t0)
                log(LogLevel.ERR, "Connect USB gagal: tidak ada HP target di port USB. Colok lewat kabel OTG, aktifkan USB debugging di Opsi Developer HP target, dan pilih mode USB 'Transfer file'")
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
            // Selesai pasang: Informasi Status (instalasi, default launcher, permission) tidak ikut dicek.
            run("Install APK") { skipInfoRefresh = true; adb.install(list) }
        }
        btnOwner.setOnClickListener {
            run("Set MDM Mode") { skipInfoRefresh = true; doSetOwner() }
        }
        btnClearKaspersky.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Hapus data aplikasi Kaspersky?")
                .setMessage("Menjalankan: pm clear ${AdbController.KASPERSKY_PACKAGE}\n\nSemua data & pengaturan aplikasi Kaspersky di HP target akan dihapus.\n\nSetelah itu lanjut ke Langkah 2 (matikan Aplikasi Admin Kaspersky di HP).")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ ->
                    run("Hapus Data Aplikasi Kaspersky") { adb.clearKasperskyData() }
                }
                .show()
        }
        btnUninstallKaspersky.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Uninstall Aplikasi Kaspersky?")
                .setMessage("Menjalankan: pm uninstall -k --user 0 ${AdbController.KASPERSKY_PACKAGE}\n\nPastikan Langkah 2 (Aplikasi Admin Kaspersky) sudah dinonaktifkan, kalau tidak uninstall akan ditolak.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Uninstall") { _, _ ->
                    run("Uninstall Aplikasi Kaspersky") { adb.uninstallKaspersky() }
                }
                .show()
        }
        btnTcpip.setOnClickListener {
            run("Ubah Mode Nirkabel (TCP/IP)") { doEnableTcpip() }
        }
        btnAll.setOnClickListener {
            // Otomatis hanya: install APK + set device owner. Pair & Connect dilakukan manual sebelumnya.
            val list = apks.toList()
            // Selesai pasang: Informasi Status (instalasi, default launcher, permission) tidak ikut dicek.
            run("Pasang Aplikasi + Jadikan MDM Mode") { skipInfoRefresh = true; adb.installAndOwner(list) }
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
        findViewById<View>(R.id.btnLogout).setOnClickListener { confirmExit() }
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

    /** Minta konfirmasi sebelum keluar; peringatkan bila masih ada proses berjalan. */
    private fun confirmExit() {
        if (isFinishing) return
        val warn = if (busy) "\n\nPerhatian: masih ada proses yang berjalan dan akan ikut terhenti." else ""
        AlertDialog.Builder(this)
            .setTitle("Keluar dari aplikasi?")
            .setMessage("Koneksi ADB akan diputus, service dihentikan, dan daftar APK dikosongkan.$warn")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Keluar") { _, _ -> exitApp() }
            .show()
    }

    /** Keluar penuh: putuskan ADB, hentikan ConnectionService (lewat adb.close()), bersihkan daftar APK, lalu tutup aplikasi. */
    private fun exitApp() {
        findViewById<View>(R.id.btnLogout).isEnabled = false
        val adbCtl = (application as App).adb
        val cache = cacheDir
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val finishOnce = Runnable { if (done.compareAndSet(false, true)) finishAndRemoveTask() }
        // close() bisa tertahan bila soket sudah mati, jadi jalan di thread latar.
        Thread {
            try { adbCtl.close() } catch (e: Throwable) { }
            try { java.io.File(cache, "picked").deleteRecursively() } catch (e: Throwable) { }
            runOnUiThread(finishOnce)
        }.start()
        // Pengaman: tetap tutup aplikasi walau pemutusan macet lebih dari 3 detik.
        window.decorView.postDelayed(finishOnce, 3000)
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
            var alive = adb.syncConnected()
            var cableGone = false
            // Kabel USB dicabut: terdeteksi seketika (tanpa menunggu probe 3x gagal).
            if (!wasBusy && adb.usbCableGone) {
                adb.dropUsb()
                alive = false
                cableGone = true
                probeFails = 0
            }
            val now = System.currentTimeMillis()
            // Probe sungguhan tiap ±10 detik (hanya saat tidak ada operasi lain) untuk mendeteksi koneksi mati diam-diam.
            if (alive && !wasBusy && now - lastProbe >= 10_000) {
                lastProbe = now
                // Cek busy lagi di dalam lock: worker menunggu lock yang sama sebelum mulai bekerja.
                synchronized(adb.probeLock) {
                    if (!busy) {
                        alive = adb.probe()
                        // Toleransi: HP target yang sibuk (dexopt/verifikasi APK) bisa telat membalas; putus baru dinyatakan setelah 3x gagal beruntun.
                        if (alive) probeFails = 0 else {
                            val n = ++probeFails
                            if (n < 3) alive = true else { probeFails = 0; if (!adb.isViaUsb) adb.markDead() }
                        }
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
            log(LogLevel.ERR, (if (cableGone) "Kabel USB dicabut: koneksi ke $addr terputus" else "Koneksi ke $addr terputus") + " · ${Fmt.clock(System.currentTimeMillis())}")
            adb.lastDropReason.takeIf { it.isNotEmpty() }?.let { log(LogLevel.DETAIL, "Penyebab: $it") }
            showUsbDevice(null)
            lostAddr = addr
            adb.sessionLabel = null
            connectedAddr = null
            connectedSince = 0L
            ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; kasperskyInstalled = null; hmdmInstalled = null; tcpipActive = null; appsInstalled = null; hmdmPerms = null
        }
        refreshHeader()
    }

    // ---------- Pair dengan QR ----------

    /** Tampilkan QR; HP target memindainya, lalu aplikasi menemukan layanan pairing via mDNS dan pair + connect otomatis. */
    private fun showQrPairing() {
        if (busy) return
        val session = AdbDiscovery.newQrSession()
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val size = dp(260)
        val img = android.widget.ImageView(this).apply {
            setImageBitmap(AdbDiscovery.qrBitmap(session.payload, size))
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        }
        val hint = TextView(this).apply {
            text = "HP target: Opsi Developer › Wireless debugging › Pair device with QR code, lalu arahkan kamera ke QR ini.\n\nHP teknisi dan HP target harus di Wi-Fi yang sama."
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setPadding(0, dp(12), 0, 0)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
            addView(img); addView(hint)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Scan QR untuk Pair")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setOnDismissListener { cancel.set(true) }
            .create()
        val shownAt = System.currentTimeMillis()
        dialog.show()
        io.execute {
            val found = AdbDiscovery.find(this, AdbDiscovery.TYPE_PAIRING, 120_000, cancel) { name, _, _ -> name == session.serviceName }
            if (found == null) {
                if (!cancel.get()) runOnUiThread {
                    dialog.dismiss()
                    logHead("Pairing QR", shownAt)
                    log(LogLevel.ERR, "Pairing QR gagal: HP target tidak memindai QR dalam 2 menit atau layanan pairing tidak ditemukan. Pastikan satu Wi-Fi, lalu coba lagi")
                    logEnd("Pairing QR", false, shownAt)
                }
                return@execute
            }
            runOnUiThread {
                dialog.dismiss()
                if (busy) return@runOnUiThread
                edtPairIp.setText(found.host); edtPairPort.setText(found.port.toString()); edtPairCode.setText(session.password)
                run("Pairing QR") { doPairQr(found, session.password) }
            }
        }
    }

    private fun doPairQr(f: AdbDiscovery.Found, password: String): Boolean {
        if (!doPair(f.addr, password)) return false
        // Setelah pair, cari port connect HP yang sama lalu sambung otomatis.
        // Beri waktu adbd di HP target siap menerima koneksi TLS setelah pairing.
        try { Thread.sleep(2_000) } catch (e: InterruptedException) { }
        val ca = adb.findConnectAddr(f.host)
        if (ca == null) {
            log(LogLevel.WARN, "Port connect tidak ditemukan otomatis. Isi IP dan port connect (layar utama Wireless debugging) lalu ketuk Connect")
            return true
        }
        val (ip, port) = ca.substringBeforeLast(':') to ca.substringAfterLast(':')
        runOnUiThread { edtConnIp.setText(ip); edtConnPort.setText(port) }
        // Hasil connect menentukan penutup proses: jangan "✓ Selesai" tepat setelah "✗ Connect gagal".
        return doConnect(ca)
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
            skipInfoRefresh = true
            updateOwner()
        } else {
            // Connect gagal: koneksi lama sudah diputus, jangan tampilkan alamat lama sebagai terhubung.
            connectedAddr = null
            connectedSince = 0L
            ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; kasperskyInstalled = null; hmdmInstalled = null; tcpipActive = null; appsInstalled = null; hmdmPerms = null
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
            skipInfoRefresh = true
            updateOwner()
        } else {
            connectedAddr = null
            connectedSince = 0L
            ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; kasperskyInstalled = null; hmdmInstalled = null; tcpipActive = null; appsInstalled = null; hmdmPerms = null
            showUsbDevice(null)
        }
        runOnUiThread { refreshHeader() }
        return label != null
    }

    /** Cek ulang device owner di HP target (panggil dari thread kerja). */
    private fun updateOwner() {
        ownerState = adb.queryDeviceOwner()
        // Informasi Status (launcher default, instalasi aplikasi, permission) sengaja TIDAK dibaca di sini:
        // baru dibaca saat pengguna mengetuk kartu Informasi Status.
        kasperskyInstalled = adb.queryKasperskyInstalled()
        hmdmInstalled = adb.queryHmdmInstalled()
        tcpipActive = adb.queryTcpipActive()
        runOnUiThread { refreshHeader() }
    }

    /** `adb tcpip 5555`: adbd di HP target restart dalam mode TCP, sesi USB ini otomatis putus. */
    private fun doEnableTcpip(): Boolean {
        // Bila TCP/IP sudah aktif di HP target, sesi USB dibiarkan tetap tersambung (tidak ada yang diubah).
        val already = adb.queryTcpipActive() == true
        val ok = adb.enableTcpip()
        if (ok && already) { tcpipActive = true; runOnUiThread { refreshHeader() }; return true }
        if (ok) { connectedAddr = null; connectedSince = 0L; lostAddr = null; ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; kasperskyInstalled = null; hmdmInstalled = null; tcpipActive = null; appsInstalled = null; hmdmPerms = null; showUsbDevice(null) }
        runOnUiThread { refreshHeader() }
        return ok
    }

    private fun doDisconnect(): Boolean {
        val ok = adb.disconnect()
        if (ok) { connectedAddr = null; connectedSince = 0L; lostAddr = null; ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; kasperskyInstalled = null; hmdmInstalled = null; tcpipActive = null; appsInstalled = null; hmdmPerms = null; showUsbDevice(null) }
        runOnUiThread { refreshHeader() }
        return ok
    }

    // ---------- Terminal ----------

    private val edtTerminal by lazy { findViewById<TextInputEditText>(R.id.edtTerminal) }
    /** true = mode "adb shell" (semua teks jadi perintah shell); false = mode "adb" (perintah non-shell). */
    private var termShell = true
    private val termHistory = mutableListOf<String>()
    private var termIdx = 0

    private fun setupTerminal(btnRun: MaterialButton) {
        val til = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilTerminal)
        val toggle = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleTermMode)
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            termShell = id == R.id.btnTermShell
            til.hint = if (termShell) "adb shell ..." else "adb ... (mis. devices, install, reboot)"
        }
        btnRun.setOnClickListener { submitTerminal() }
        edtTerminal.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { submitTerminal(); true } else false
        }
        findViewById<View>(R.id.btnTermPrev).setOnClickListener { termRecall(-1) }
        findViewById<View>(R.id.btnTermNext).setOnClickListener { termRecall(+1) }
    }

    /** Panggil kembali perintah lama: delta -1 = lebih lama, +1 = lebih baru (melewati yang terbaru = kolom kosong). */
    private fun termRecall(delta: Int) {
        if (termHistory.isEmpty()) return
        termIdx = (termIdx + delta).coerceIn(0, termHistory.size)
        val t = if (termIdx >= termHistory.size) "" else termHistory[termIdx]
        edtTerminal.setText(t)
        edtTerminal.setSelection(t.length)
    }

    private var pendingPushRemote: String? = null
    private var pendingPullRemote: String? = null

    private val pickPushFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val remote = pendingPushRemote
        pendingPushRemote = null
        if (uri == null || remote == null) { log(LogLevel.WARN, "Push dibatalkan: tidak ada file yang dipilih"); return@registerForActivityResult }
        val name = displayName(uri)
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
            }
        } catch (e: Exception) { }
        run("Push", refresh = false) {
            val input = try { contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            if (input == null) {
                runOnUiThread { log(LogLevel.ERR, "Push gagal: file '$name' tidak bisa dibuka. Pilih ulang file-nya") }
                false
            } else input.use { adb.push(name, it, size, remote) }
        }
    }

    private val pickPullDest = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val remote = pendingPullRemote
        pendingPullRemote = null
        if (uri == null || remote == null) { log(LogLevel.WARN, "Pull dibatalkan: tempat simpan tidak dipilih"); return@registerForActivityResult }
        run("Pull", refresh = false) {
            val out = try { contentResolver.openOutputStream(uri) } catch (e: Exception) { null }
            val ok = if (out == null) {
                runOnUiThread { log(LogLevel.ERR, "Pull gagal: tempat simpan tidak bisa ditulis. Pilih lokasi lain") }
                false
            } else out.use { adb.pull(remote, it) }
            // Jangan tinggalkan file kosong bila pull gagal.
            if (!ok) try { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) } catch (e: Exception) { }
            ok
        }
    }

    /**
     * `adb push [file] <tujuan>` dan `adb pull <file di HP target> [tujuan]`.
     * Tanpa path lokal yang bisa dibaca aplikasi, pemilih file Android dibuka (aplikasi tidak boleh membaca /sdcard bebas).
     */
    private fun terminalTransfer(cmd: String, argText: String) {
        val args = argText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val t0 = System.currentTimeMillis()
        fun fail(msg: String) { logHead("Terminal", t0); log(LogLevel.ERR, msg); logEnd("Terminal", false, t0) }
        if (!adb.isConnected) {
            logHead("Terminal", t0); log(LogLevel.WARN, AdbController.MSG_NOT_CONNECTED); logEnd("Terminal", false, t0); return
        }
        if (cmd == "push") {
            when (args.size) {
                1 -> {
                    pendingPushRemote = args[0]
                    log(LogLevel.STEP, "Pilih file di HP ini untuk dikirim ke ${args[0]}")
                    pickPushFile.launch(arrayOf("*/*"))
                }
                2 -> {
                    val f = java.io.File(args[0])
                    if (!f.isFile || !f.canRead()) {
                        fail("Push gagal: file '${args[0]}' tidak bisa dibaca aplikasi. Tulis hanya tujuannya (adb push ${args[1]}) agar muncul pemilih file")
                    } else run("Push", refresh = false) { f.inputStream().use { adb.push(f.name, it, f.length(), args[1]) } }
                }
                else -> fail("Push gagal: format salah. Tulis: adb push <tujuan di HP target>  atau  adb push <file> <tujuan>")
            }
        } else {
            when (args.size) {
                1 -> {
                    pendingPullRemote = args[0]
                    log(LogLevel.STEP, "Pilih tempat menyimpan hasil pull dari ${args[0]}")
                    pickPullDest.launch(args[0].trimEnd('/').substringAfterLast('/').ifEmpty { "pull.bin" })
                }
                2 -> {
                    val f = java.io.File(args[1])
                    val dir = if (f.isDirectory) f else f.absoluteFile.parentFile
                    if (dir == null || !dir.isDirectory || !dir.canWrite()) {
                        fail("Pull gagal: folder '${args[1]}' tidak bisa ditulis aplikasi. Tulis hanya sumbernya (adb pull ${args[0]}) agar muncul pemilih lokasi simpan")
                    } else {
                        val dest = if (f.isDirectory) java.io.File(f, args[0].trimEnd('/').substringAfterLast('/').ifEmpty { "pull.bin" }) else f
                        run("Pull", refresh = false) {
                            val ok = dest.outputStream().use { adb.pull(args[0], it) }
                            if (!ok) dest.delete()
                            ok
                        }
                    }
                }
                else -> fail("Pull gagal: format salah. Tulis: adb pull <file di HP target>  atau  adb pull <file di HP target> <tujuan>")
            }
        }
    }

    /** `adb pair <ip:port> <kode>` dan `adb connect <ip[:port]>`: memakai langkah yang sama dengan kartu Pair / Connect. */
    private fun terminalPairConnect(cmd: String, argText: String) {
        val args = argText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val title = if (cmd == "pair") "Pairing" else "Connect"
        fun fail(msg: String) {
            val t0 = System.currentTimeMillis()
            logHead(title, t0); log(LogLevel.ERR, msg); logEnd(title, false, t0)
        }
        if (connectedAddr != null && adb.isConnected) {
            fail("$title gagal: HP target masih terhubung. Jalankan adb disconnect dulu"); return
        }
        if (cmd == "pair") {
            if (args.size != 2) { fail("Pairing gagal: format salah. Tulis: adb pair <IP:Port pairing> <kode pairing>"); return }
            val addr = clean(args[0]); val code = clean(args[1])
            run(title) { doPair(addr, code) }
        } else {
            if (args.size != 1) { fail("Connect gagal: format salah. Tulis: adb connect <IP:Port> (port kosong = 5555)"); return }
            val a = clean(args[0])
            val addr = if (a.contains(':')) a else "$a:${AdbController.TCPIP_PORT}"
            run(title) { doConnect(addr) }
        }
    }

    private fun submitTerminal() {
        if (busy) return
        val text = edtTerminal.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val body = text.removePrefix("adb ").trim()
        val head = body.substringBefore(' ')
        // Kode pairing bersifat sementara tapi rahasia: perintah pair tidak disimpan di riwayat.
        if (head != "pair" && termHistory.lastOrNull() != text) termHistory.add(text)
        if (termHistory.size > 50) termHistory.removeAt(0)
        termIdx = termHistory.size
        edtTerminal.setText("")
        hideKeyboard()
        val shellMode = termShell
        if (!shellMode && (head == "pair" || head == "connect")) { terminalPairConnect(head, body.removePrefix(head).trim()); return }
        if (!shellMode && (head == "push" || head == "pull")) { terminalTransfer(head, body.removePrefix(head).trim()); return }
        // Status (device owner, aplikasi terpasang) dibaca ulang hanya bila perintah kemungkinan mengubahnya.
        val refresh = if (shellMode || head == "shell") Regex("\\b(dpm|pm|cmd|am|install|uninstall|settings|appops)\\b").containsMatchIn(body)
                      else head == "install" || head == "install-multiple" || head == "uninstall"
        val picked = apks.toList()
        run("Terminal", refresh) {
            val ok = adb.terminal(text, shellMode, picked)
            // Perintah seperti reboot / tcpip / usb / disconnect memutus sesi: samakan tampilan dengan kenyataan.
            if (!adb.isConnected && connectedAddr != null) {
                connectedAddr = null; connectedSince = 0L; lostAddr = null
                ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; kasperskyInstalled = null
                hmdmInstalled = null; tcpipActive = null; appsInstalled = null; hmdmPerms = null
                showUsbDevice(null)
            }
            runOnUiThread { refreshHeader() }
            ok
        }
    }

    private fun doSetOwner(): Boolean {
        val ok = adb.setDeviceOwner()
        runOnUiThread { refreshHeader() }
        return ok
    }

    private fun run(title: String, refresh: Boolean = true, work: () -> Boolean) {
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
                runOnUiThread {
                    log(LogLevel.ERR, "$title gagal: kesalahan tak terduga. Coba lagi, dan laporkan bila berulang")
                    log(LogLevel.DETAIL, Fmt.cause(e))
                }
            }
            // Perbarui status device owner setelah setiap operasi (install / set owner / dll).
            // Tepat setelah Connect, Informasi Status (launcher, instalasi, permission) tidak ikut dibaca.
            val skipInfo = skipInfoRefresh
            skipInfoRefresh = false
            try {
                if (refresh && adb.isConnected) {
                    val st = adb.queryDeviceOwner(); if (st != OwnerState.Unknown) ownerState = st
                    adb.queryKasperskyInstalled()?.let { kasperskyInstalled = it }
                    adb.queryHmdmInstalled()?.let { hmdmInstalled = it }
                    adb.queryTcpipActive()?.let { tcpipActive = it }
                    if (!skipInfo) {
                        val hs = adb.queryDefaultLauncher(); if (hs != HomeState.Unknown) homeState = hs
                        adb.queryRequiredPackages()?.let { appsInstalled = it }
                        adb.queryHmdmPermissions()?.let { hmdmPerms = it }
                    }
                }
            } catch (e: Throwable) { }
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
        val transfer = total == 0
        n.text = if (transfer) "Transfer file: $name" else "Memasang APK $idx dari $total: $name"
        if (transfer) {
            bar.isIndeterminate = false
            val pct = if (size > 0) (sent * 100 / size).toInt().coerceAtMost(100) else 0
            bar.progress = if (size > 0) (sent * 1000 / size).toInt().coerceAtMost(1000) else 0
            info.text = if (size > 0) "$pct% · ${Fmt.size(sent)} / ${Fmt.size(size)}" else Fmt.size(sent)
        } else if (size > 0 && sent >= size) {
            bar.isIndeterminate = true
            info.text = "Terkirim ${Fmt.mb(size)}. Menunggu HP target memasang..."
        } else {
            bar.isIndeterminate = false
            val pct = if (size > 0) (sent * 100 / size).toInt() else 0
            bar.progress = if (size > 0) (sent * 1000 / size).toInt() else 0
            info.text = "$pct% · ${Fmt.mb(sent)} / ${Fmt.mb(size)}"
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
        refreshApps()
        refreshHome()
        refreshPerms()
        refreshTcpip()
        applyButtonState(busy)
    }

    /**
     * Atur status tombol: semua tombol mati saat ada proses berjalan; tombol yang butuh device
     * (Pasang APK, set-device-owner, Otomatis, Hapus data & Uninstall Kaspersky) juga mati selama belum terhubung.
     */
    private fun applyButtonState(isBusy: Boolean) {
        val connected = connectedAddr != null && adb.isConnected
        buttons.forEach { b ->
            // "Pasang Aplikasi" dan "Otomatis (pasang + owner)" tidak bisa ditekan selama daftar APK masih kosong.
            // Hapus Data & Uninstall Kaspersky hanya aktif bila com.kaspersky.kes terdeteksi di HP target.
            val noKaspersky = (b.id == R.id.btnClearKaspersky || b.id == R.id.btnUninstallKaspersky) && kasperskyInstalled != true
            // Jadikan MDM Mode hanya aktif bila com.hmdm.launcher terdeteksi di HP target.
            val noHmdm = b.id == R.id.btnOwner && hmdmInstalled != true
            // "Jadikan MDM Mode" dan "Pasang Aplikasi + Jadikan MDM Mode" mati bila HMDM sudah menjadi device owner (status "HMDM aktif ✓").
            val ownerActive = (b.id == R.id.btnOwner || b.id == R.id.btnAll) && connected && ownerState is OwnerState.Hmdm
            val needsApk = (b.id == R.id.btnInstallOnly || b.id == R.id.btnAll) && apks.isEmpty()
            // "Pasang Aplikasi + Jadikan MDM Mode" hanya aktif bila com.hmdm.launcher ada di daftar APK yang akan dipasang.
            val noHmdmApk = b.id == R.id.btnAll && !hasHmdmApk()
            // "Ubah Mode Nirkabel (TCP/IP)" (adb tcpip) hanya untuk sesi yang berjalan lewat kabel USB.
            val notUsb = b.id == R.id.btnTcpip && connected && !adb.isViaUsb
            // Mode TCP/IP sudah aktif di HP target: tombol tidak perlu ditekan lagi.
            val tcpipAlready = b.id == R.id.btnTcpip && connected && tcpipActive == true
            val enabled = !isBusy && !notUsb && !tcpipAlready && !needsApk && !noHmdmApk && !noKaspersky && !noHmdm && !ownerActive && (connected || b !in needsConnection) && !(connected && b in blockedWhenConnected)
            b.isEnabled = enabled
            // Beberapa gaya tombol (mis. DangerFilled) tidak punya tampilan "disabled" sendiri.
            b.alpha = if (enabled) 1f else 0.4f
        }
        // Kolom Terminal: hanya bisa diketik saat terhubung dan tidak sedang ada proses.
        // Terminal aktif walau belum terhubung: pair / connect bisa diketik di sini.
        val termOn = !isBusy
        edtTerminal.isEnabled = termOn
        findViewById<View>(R.id.tilTerminal).alpha = if (termOn) 1f else 0.4f
        listOf(R.id.btnTermPrev, R.id.btnTermNext).forEach { id ->
            findViewById<View>(id).apply { isEnabled = termOn; alpha = if (termOn) 1f else 0.4f }
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
        // Ikon refresh Status MDM Mode & tombol Cek Status Semua Aplikasi: hanya bisa ditekan saat terhubung dan tidak sibuk.
        if (::btnRefreshInfo.isInitialized) {
            val checkEnabled = !isBusy && connected
            btnRefreshInfo.isEnabled = checkEnabled
            btnRefreshInfo.isClickable = checkEnabled
            btnRefreshInfo.alpha = if (checkEnabled) 1f else 0.4f
        }
        if (::btnRefreshOwner.isInitialized) {
            val checkEnabled = !isBusy && connected
            btnRefreshOwner.isEnabled = checkEnabled
            btnRefreshOwner.isClickable = checkEnabled
            btnRefreshOwner.alpha = if (checkEnabled) 1f else 0.4f
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

    /** Status gabungan tiga permission com.hmdm.launcher (admin perangkat, overlay, riwayat penggunaan). */
    private fun refreshPerms() {
        if (!::txtPermAdmin.isInitialized) return
        val connected = connectedAddr != null && adb.isConnected
        val p = if (connected) hmdmPerms else null
        val grey = Color.rgb(148, 163, 184)
        val green = Color.rgb(74, 222, 128)
        val red = Color.rgb(248, 113, 113)
        val amber = Color.rgb(251, 191, 36)
        // Device owner otomatis juga admin perangkat aktif.
        val admin = if (p?.admin == true || (connected && ownerState is OwnerState.Hmdm)) true else p?.admin
        val values = listOf(admin, p?.overlay, p?.usage)
        val (text, color) = when {
            !connected -> "Belum terhubung" to grey
            p == null || values.any { it == null } -> "Belum diperiksa" to grey
            values.all { it == true } -> "Aktif ✓" to green
            values.none { it == true } -> "Belum aktif" to red
            else -> "Belum lengkap (${values.count { it == true }}/3)" to amber
        }
        txtPermAdmin.text = text
        permAdminDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    /** Status default launcher: aktif bila launcher home default HP target adalah com.hmdm.launcher. */
    private fun refreshHome() {
        if (!::txtHome.isInitialized) return
        val connected = connectedAddr != null && adb.isConnected
        val st = if (connected) homeState else HomeState.Unknown
        val (text, color) = when (st) {
            is HomeState.Hmdm -> "Aktif ✓" to Color.rgb(74, 222, 128)
            is HomeState.Other -> "Belum aktif · launcher lain" to Color.rgb(248, 113, 113)
            HomeState.None -> "Belum ada default" to Color.rgb(251, 191, 36)
            HomeState.Unknown ->
                (if (connected) "Belum diperiksa" else "Belum terhubung") to Color.rgb(148, 163, 184)
        }
        txtHome.text = text
        homeDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun refreshApps() {
        if (!::txtApps.isInitialized) return
        val connected = connectedAddr != null && adb.isConnected
        val st = if (connected) appsInstalled else null
        val pkgs = AdbController.DEVICE_PACKAGES[deviceType] ?: emptyList()
        val total = pkgs.size
        val grey = Color.rgb(148, 163, 184)
        val green = Color.rgb(74, 222, 128)
        val red = Color.rgb(248, 113, 113)
        // Checkbox jenis perangkat: hanya satu yang tercentang (yang dipilih), selalu ada satu terpilih.
        // Selama belum terhubung, checkbox dinonaktifkan (tidak bisa diubah) dan tampil redup.
        typeChips.forEach { (type, box) ->
            box.isChecked = type == deviceType
            box.isEnabled = connected
            box.alpha = if (connected) 1f else 0.4f
        }
        val missing = if (st == null) emptyList() else pkgs.filter { st[it] != true }
        val (text, color) = when {
            st == null -> (if (connected) "Belum diperiksa" else "Belum terhubung") to grey
            missing.isEmpty() -> "Semua terpasang ✓ ($total/$total)" to green
            else -> "Belum lengkap (${total - missing.size}/$total)" to red
        }
        txtApps.text = text
        appsDot.backgroundTintList = ColorStateList.valueOf(color)
        // Daftar paket tidak ditampilkan; hanya ringkasan jumlah terpasang.
        txtAppsDetail.text = ""
        txtAppsDetail.visibility = View.GONE
    }

    private fun refreshTcpip() {
        if (!::txtTcpip.isInitialized) return
        val connected = connectedAddr != null && adb.isConnected
        val st = if (connected) tcpipActive else null
        val (text, color) = when (st) {
            true -> "Aktif ✓" to Color.rgb(74, 222, 128)
            false -> "Belum aktif" to Color.rgb(251, 191, 36)
            null -> (if (connected) "Belum diperiksa" else "Belum terhubung") to Color.rgb(148, 163, 184)
        }
        txtTcpip.text = text
        tcpipDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun refreshOwner() {
        val connected = connectedAddr != null && adb.isConnected
        val st = if (connected) ownerState else OwnerState.Unknown
        val (text, color) = when (st) {
            is OwnerState.Hmdm -> "HMDM aktif ✓" to Color.rgb(74, 222, 128)
            is OwnerState.Other -> "Aplikasi lain" to Color.rgb(248, 113, 113)
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
            txtApkTitle.text = "Pilih File APK (Bisa Banyak)"
        } else {
            txtApkEmpty.text = "${apks.size} APK dipilih"
            txtDropSub.text = "Ketuk untuk memilih ulang (daftar sebelumnya diganti)"
            txtApkTitle.text = "Pilih File APK (${apks.size})"
        }
        renderApkList()
        refreshHeader()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Cache nama paket per file APK (kunci: path + ukuran + waktu ubah) agar file tidak di-parse berulang. */
    private val apkPkgCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Nama paket (mis. com.hmdm.launcher) dari file APK; "" bila tidak terbaca. */
    private fun apkPackage(item: ApkItem): String {
        val f = item.file
        val key = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
        return apkPkgCache.getOrPut(key) {
            try {
                packageManager.getPackageArchiveInfo(f.absolutePath, 0)?.packageName ?: ""
            } catch (e: Throwable) { "" }
        }
    }

    /** Apakah daftar APK yang dipilih berisi com.hmdm.launcher. */
    private fun hasHmdmApk(): Boolean = apks.any { apkPackage(it) == AdbController.HMDM_PACKAGE }

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
    private fun logHead(title: String, at: Long = System.currentTimeMillis()) {
        // Pesan lepas (mis. APK dilewati, koneksi terputus) tidak diakhiri baris kosong; pisahkan di sini.
        val cur = txtLog.text
        if (cur.isNotEmpty() && !cur.endsWith("\n\n")) logBlank()
        log(LogLevel.HEAD, "$title · ${Fmt.clock(at)}")
    }

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
            if (level != LogLevel.DETAIL && level != LogLevel.OUT && it.endsWith(".") && !it.endsWith("..")) it.dropLast(1) else it
        }
        val lines = body.lines().map { it.trimEnd() }.map { if (level == LogLevel.DETAIL) Fmt.clip(it) else it }
        val text = lines.mapIndexed { i, l -> (if (i == 0) level.marker else indent) + l }.joinToString("\n") + "\n"
        val sp = SpannableString(text)
        sp.setSpan(ForegroundColorSpan(levelColor(level)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (level.bold) sp.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Hanging indent: baris lanjutan akibat teks panjang sejajar dengan isi pesan, bukan dengan penanda.
        val hang = (txtLog.paint.measureText("M") * level.marker.length).toInt()
        if (hang > 0) sp.setSpan(LeadingMarginSpan.Standard(0, hang), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        txtLog.append(sp)
        trimLog()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Batasi panjang log agar tidak membengkak: buang baris paling lama (dipotong di batas baris). */
    private fun trimLog() {
        val cur = txtLog.text
        if (cur.length <= LOG_MAX_CHARS) return
        val sb = android.text.SpannableStringBuilder(cur)
        val cut = sb.indexOf("\n", sb.length - LOG_KEEP_CHARS)
        sb.delete(0, if (cut < 0) sb.length - LOG_KEEP_CHARS else cut + 1)
        txtLog.text = sb
    }

    private fun levelColor(level: LogLevel): Int = when (level) {
        LogLevel.HEAD, LogLevel.SUMMARY -> Color.WHITE
        LogLevel.STEP -> Color.rgb(252, 211, 77)
        LogLevel.CMD -> Color.rgb(56, 189, 248)
        LogLevel.OK, LogLevel.END_OK -> Color.rgb(74, 222, 128)
        LogLevel.WARN -> Color.rgb(251, 191, 36)
        LogLevel.ERR, LogLevel.END_ERR -> Color.rgb(248, 113, 113)
        LogLevel.DETAIL -> Color.rgb(148, 163, 184)
        LogLevel.OUT -> Color.rgb(226, 232, 240)
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
            runOnUiThread {
                log(LogLevel.ERR, "Salin $name gagal: file tidak bisa dibaca atau disimpan. Pilih ulang file APK")
                log(LogLevel.DETAIL, Fmt.cause(e))
            }
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
