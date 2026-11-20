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
import android.view.inputmethod.EditorInfo
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

    // Posisi tab bar = geser karena keyboard + geser karena disembunyikan saat gulir.
    private var tabKbOffset = 0f
    private var tabHideOffset = 0f
    private var tabHidden = false
    private var tabAnim: android.animation.ValueAnimator? = null

    private fun applyTabOffset() {
        findViewById<View>(R.id.tabBar).translationY = tabKbOffset + tabHideOffset
    }

    private fun setTabHidden(hide: Boolean, bar: View) {
        if (tabHidden == hide) return
        tabHidden = hide
        val d = resources.displayMetrics.density
        // Cukup jauh agar bar beserta bayangan dan jarak 16dp ke dasar layar ikut hilang.
        val target = if (hide) bar.height + 32 * d else 0f
        tabAnim?.cancel()
        tabAnim = android.animation.ValueAnimator.ofFloat(tabHideOffset, target).apply {
            duration = 180
            addUpdateListener { tabHideOffset = it.animatedValue as Float; applyTabOffset() }
            start()
        }
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
        if (!visible) return
        // Tanpa fokus (mis. kolom input sudah disembunyikan), tetap tutup lewat token jendela.
        val focus = currentFocus
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow((focus ?: window.decorView).windowToken, 0)
        focus?.clearFocus()
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
    private lateinit var permAdminDot: View
    private lateinit var txtPermAdmin: TextView
    private lateinit var progress: ProgressBar
    private lateinit var loading: LoadingOverlay
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
    /** Apakah adbd HP target sudah berjalan dalam mode TCP/IP (port aktif): true = tombol "Ubah ke Mode Nirkabel" dimatikan. */
    @Volatile private var tcpipActive: Boolean? = null
    /** Status tiap aplikasi wajib (AdbController.REQUIRED_PACKAGES) di HP target: null = belum diketahui. */
    @Volatile private var appsInstalled: Map<String, String?>? = null
    /** Jenis perangkat yang dicek untuk [appsInstalled]; hasil dipakai hanya bila sama dengan jenis yang sedang terpilih. */
    @Volatile private var appsType: String? = null
    /** True sesaat setelah Connect berhasil: Informasi Status (instalasi, launcher, permission) jangan dibaca otomatis; baru dibaca saat kartunya diketuk. */
    @Volatile private var skipInfoRefresh = false
    /** Jenis perangkat yang dipilih untuk ceklist instalasi: POS / TABLET / PDA (disimpan di preferensi). */
    private var deviceType = "POS"
    private lateinit var typeChips: Map<String, android.widget.CheckBox>
    /** Status tiga permission com.hmdm.launcher di HP target: null = belum diketahui. */
    @Volatile private var hmdmPerms: HmdmPermissions? = null
    /** Model HP target hasil baca di kartu Informasi Status (model, jenis terdeteksi atau null). */
    @Volatile private var deviceModel: Pair<String, String?>? = null
    private lateinit var txtModel: TextView
    /** Jenis perangkat yang dicek untuk [hmdmPerms]; hasil dipakai hanya bila sama dengan jenis yang sedang terceklis. */
    @Volatile private var permType: String? = null

    // Pemantau status koneksi (live): jalan hanya saat layar terlihat.
    private val poller = Executors.newSingleThreadScheduledExecutor()
    private var pollTask: ScheduledFuture<*>? = null
    private var lastProbe = 0L
    private var probeFails = 0

    // ---- Kunci PIN ----
    private lateinit var pinStore: PinStore
    private var lockView: PinLockView? = null
    private var lastActivityAt = SystemClock.elapsedRealtime()
    /** Waktu detak timer sesi terakhir; dipakai untuk menahan (pause) hitungan mundur saat operasi berjalan. */
    private var timerTickAt = SystemClock.elapsedRealtime()
    private lateinit var txtSession: TextView
    private val backWhileLocked = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { moveTaskToBack(true) }
    }

    /** Penerima hasil pilih file untuk Export/Import versi aplikasi; diisi oleh dialog Setting saat tombolnya diketuk. */
    private var onExportPicked: ((Uri) -> Unit)? = null
    private var onImportPicked: ((Uri) -> Unit)? = null
    private val exportVersionsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) onExportPicked?.invoke(uri)
    }
    private val importVersionsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportPicked?.invoke(uri)
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
        // Sembunyikan isi aplikasi dari recent apps / screenshot (layar PIN hanya overlay).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        // Tab melayang tidak ikut naik saat keyboard muncul: isi halaman tetap menyusut (adjustResize),
        // tetapi bar tab digeser turun sebesar penyusutan itu sehingga tetap di dasar layar, di balik keyboard.
        run {
            val root = findViewById<View>(android.R.id.content)
            val bar = findViewById<View>(R.id.tabBar)
            var fullH = 0
            var lastW = 0
            root.viewTreeObserver.addOnGlobalLayoutListener {
                val w = root.width
                val h = root.height
                if (w != lastW) { lastW = w; fullH = 0 }
                if (h > fullH) fullH = h
                // Hanya penyusutan akibat keyboard yang dikompensasi. Penyusutan lain (layar dikunci/dibuka,
                // bar sistem berubah, overlay PIN) dianggap tinggi layar baru, sehingga tab tidak tertinggal
                // turun rapat ke dasar layar.
                val shrink = (fullH - h).coerceAtLeast(0)
                val imeVisible = ViewCompat.getRootWindowInsets(root)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
                val keyboardShrink = imeVisible || shrink > fullH / 5
                if (!keyboardShrink) fullH = h
                tabKbOffset = if (keyboardShrink) shrink.toFloat() else 0f
                applyTabOffset()
            }

            // Tab melayang ikut sembunyi saat isi digulir ke bawah, dan muncul lagi saat digulir ke atas.
            val density = resources.displayMetrics.density
            val slop = 4 * density
            var acc = 0f
            findViewById<ScrollView>(R.id.pageScroll).setOnScrollChangeListener { _, _, y, _, oldY ->
                val dy = (y - oldY).toFloat()
                if (y <= 0) { acc = 0f; setTabHidden(false, bar); return@setOnScrollChangeListener }
                // Akumulasi searah agar getaran kecil tidak membuat tab kedip.
                acc = if (dy * acc < 0) dy else acc + dy
                if (acc > slop) setTabHidden(true, bar)
                else if (acc < -slop) setTabHidden(false, bar)
            }
        }

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
        // Jenis perangkat dipilih otomatis dari model HP target (tidak ada tombol pilih lagi).
        typeChips = emptyMap()
        val typePrefs = getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
        deviceType = typePrefs.getString("device_type", "POS")?.takeIf { it in AdbController.DEVICE_PACKAGES } ?: "POS"
        txtModel = findViewById(R.id.txtModel)
        appsDot = findViewById(R.id.appsDot)
        txtApps = findViewById(R.id.txtApps)
        txtAppsDetail = findViewById(R.id.txtAppsDetail)
        permAdminDot = findViewById(R.id.permAdminDot)
        txtPermAdmin = findViewById(R.id.txtPermAdmin)
        progress = findViewById(R.id.progress)
        loading = LoadingOverlay(this)

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
        buttons = listOf(btnPair, btnPairQr, btnConnect, btnConnectUsb, btnDisconnect,
            btnInstallOnly, btnOwner, btnAll, btnClearKaspersky, btnUninstallKaspersky, btnTcpip)
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
        // Hanya diminta sekali; bila ditolak tidak diminta lagi tiap aplikasi dibuka.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !typePrefs.getBoolean("notif_asked", false)) {
            typePrefs.edit().putBoolean("notif_asked", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        adb.onProgress = { idx, total, name, sent, size -> runOnUiThread { showInstallProgress(idx, total, name, sent, size) } }

        // Siapkan kunci ADB di thread latar (bukan UI thread).
        io.execute { adb.warmUp() }
        // Bila aplikasi sempat crash sebelumnya, tampilkan jejaknya di log (lalu hapus) agar penyebabnya bisa dibaca / disalin.
        io.execute {
            val f = java.io.File(filesDir, App.CRASH_FILE)
            val txt = try { if (f.exists()) f.readText() else null } catch (e: Throwable) { null }
            if (txt != null) {
                f.delete()
                val trace = txt.lines().filter { it.isNotBlank() }.map { it.replace("\t", "  ") }
                runOnUiThread {
                    log(LogLevel.ERR, "Aplikasi berhenti tiba-tiba pada sesi sebelumnya, jejaknya ada di bawah")
                    trace.take(14).forEach { log(LogLevel.DETAIL, it) }
                    if (trace.size > 14) log(LogLevel.DETAIL, "… ${trace.size - 14} baris lain tidak ditampilkan")
                }
            }
        }
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
            // Hasil cek manual sudah dipakai kartu; jangan dibaca ulang diam-diam (bisa menimpa dengan hasil terpotong).
            skipInfoRefresh = true
            run("Cek Informasi Status") {
                // Baca model HP target; bila ada di daftar POS / PDA / TABLET, jenis perangkat dipilih otomatis.
                val dm = adb.queryDeviceModel()
                if (dm != null) {
                    deviceModel = dm
                    if (dm.second != null) {
                        runOnUiThread { log(LogLevel.OK, "Model perangkat: ${dm.first} -> ${dm.second}") }
                        if (dm.second != deviceType) {
                            deviceType = dm.second!!
                            getSharedPreferences("ui_prefs", Context.MODE_PRIVATE).edit().putString("device_type", deviceType).apply()
                        }
                    } else {
                        runOnUiThread { log(LogLevel.WARN, "Model perangkat: ${dm.first} (tidak ada di daftar POS / PDA / TABLET, pilih jenis secara manual)") }
                    }
                } else {
                    runOnUiThread { log(LogLevel.WARN, "Model perangkat tidak terbaca") }
                }
                val type = deviceType
                val r = adb.checkRequiredPackages(type)
                if (r != null) { appsInstalled = r; appsType = type }
                // Permission hanya dicek bila com.hmdm.launcher termasuk dalam jenis perangkat yang terceklis.
                val pr = if (hmdmInType(type)) adb.checkHmdmPermissions() else null
                if (pr != null) { hmdmPerms = pr; permType = type }
                r != null || pr != null
            }
        }
        val btnMoreToggle: View = findViewById(R.id.btnMoreToggle)
        val moreContainer: View = findViewById(R.id.moreContainer)
        val txtMoreToggle: TextView = findViewById(R.id.txtMoreToggle)
        val icMoreToggle: View = findViewById(R.id.icMoreToggle)
        btnMoreToggle.setOnClickListener {
            val show = moreContainer.visibility != View.VISIBLE
            moreContainer.visibility = if (show) View.VISIBLE else View.GONE
            txtMoreToggle.text = if (show) "Sembunyikan" else "Lihat Lainnya"
            icMoreToggle.rotation = if (show) 180f else 0f
        }
        tabMode = findViewById(R.id.tabMode)
        val tabWireless: View = findViewById(R.id.tabWireless)
        val tabUsb: View = findViewById(R.id.tabUsb)
        val txtConnTitle: TextView = findViewById(R.id.txtConnTitle)
        val cardTcpip: View = findViewById(R.id.cardTcpip)
        tabMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                // Pindah tab (swipe atau ketuk): tutup keyboard dulu, sebelum kolom input di tab lama disembunyikan.
                hideKeyboard()
                val usb = tab.position == 1
                tabWireless.visibility = if (usb) View.GONE else View.VISIBLE
                tabUsb.visibility = if (usb) View.VISIBLE else View.GONE
                txtConnTitle.text = if (usb) "KONEKSI USB DEBUGGING" else "KONEKSI WIRELESS DEBUGGING"
                cardTcpip.visibility = if (usb) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        btnPair.setOnClickListener {
            if (connectedAddr != null && adb.isConnected) return@setOnClickListener
            val addr = addr(edtPairIp.text.toString(), edtPairPort.text.toString())
            val code = clean(edtPairCode.text.toString())
            run("Pair") { doPair(addr, code) }
        }
        // Tombol "Done" di keyboard pada kolom kode pairing = tekan tombol Pair.
        edtPairCode.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                btnPair.performClick()
                true
            } else false
        }
        // Tombol "Done" di keyboard pada kolom port Connect = tekan tombol Connect.
        edtConnPort.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                btnConnect.performClick()
                true
            } else false
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
                log(LogLevel.ERR, "Connect USB gagal: tidak ada HP target di port USB. Colok lewat kabel OTG, aktifkan USB debugging di Opsi Developer HP target, dan pilih mode USB \"Transfer file\"")
                logEnd("Connect USB", false, t0)
                showConnProblem(
                    "Connect USB gagal",
                    "Tidak ada HP target yang terdeteksi di port USB.\n\n" +
                        "Periksa:\n" +
                        "• Kabel (OTG) terpasang kuat ke HP target\n" +
                        "• USB debugging aktif di Opsi Developer HP target\n" +
                        "• Mode USB di HP target: \"Transfer file\"\n\n" +
                        "Rincian lengkap ada di log."
                )
                return@setOnClickListener
            }
            var usbDlg: AlertDialog? = null
            val usbList = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            for ((i, t) in targets.withIndex()) {
                usbList.addView(
                    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = t.label + "\n" + t.device.deviceName
                        setAllCaps(false)
                        textSize = 14f
                        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                        minHeight = dp(56)
                        setOnClickListener {
                            usbDlg?.dismiss()
                            showUsbDevice(t.label)
                            run("Connect USB") { doConnectUsb(t) }
                        }
                    },
                    android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(8) }
                )
            }
            usbDlg = showPopup(
                "Pilih Perangkat USB", "Ketuk HP target yang ingin disambungkan.",
                icon = R.drawable.ic_usb, content = usbList, positive = null, negative = "Batal"
            )
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
            run("Pasang Aplikasi") { skipInfoRefresh = true; adb.install(list) }
        }
        btnOwner.setOnClickListener {
            run("Jadikan MDM Mode") { skipInfoRefresh = true; doSetOwner() }
        }
        btnClearKaspersky.setOnClickListener {
            showConfirm(
                "Hapus Data Aplikasi Kaspersky?",
                "Semua data dan pengaturan aplikasi Kaspersky di HP target akan dihapus.\n\nSetelah ini lanjut ke Langkah 2: matikan Aplikasi Admin Kaspersky di HP target (manual).\n\nPerintah: adb shell pm clear ${AdbController.KASPERSKY_PACKAGE}",
                "Hapus"
            ) {
                // Selesai hapus data: Informasi Status (instalasi, default launcher, permission) tidak ikut dicek.
                run("Hapus Data Aplikasi Kaspersky") { skipInfoRefresh = true; adb.clearKasperskyData() }
            }
        }
        btnUninstallKaspersky.setOnClickListener {
            showConfirm(
                "Uninstall Aplikasi Kaspersky?",
                "Aplikasi Kaspersky akan di-uninstall dari HP target.\n\nPastikan Langkah 2 (mematikan Aplikasi Admin Kaspersky) sudah dilakukan, jika belum uninstall akan ditolak.\n\nPerintah: adb shell pm uninstall -k --user 0 ${AdbController.KASPERSKY_PACKAGE}",
                "Uninstall"
            ) {
                // Selesai uninstall: Informasi Status (instalasi, default launcher, permission) tidak ikut dicek.
                run("Uninstall Aplikasi Kaspersky") { skipInfoRefresh = true; adb.uninstallKaspersky() }
            }
        }
        btnTcpip.setOnClickListener {
            // Selesai ubah mode: Informasi Status (instalasi, default launcher, permission) tidak ikut dicek.
            run("Ubah ke Mode Nirkabel") { skipInfoRefresh = true; doEnableTcpip() }
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
        findViewById<View>(R.id.btnSettings).setOnClickListener { showVersionSettings() }
        showLock()

        restorePickedApks()
        refreshApkList()
        refreshHeader()
    }

    override fun onStart() {
        super.onStart()
        // Kunci ulang bila aplikasi ditinggalkan lebih lama dari batas waktu (koneksi ADB tetap berjalan).
        if (lockView == null && !busy && sessionRemainingMs() <= 0) showLock()
        // Pemantau berhenti saat aplikasi di latar belakang; jangan hitung jeda itu sebagai waktu sibuk.
        timerTickAt = SystemClock.elapsedRealtime()
        // Setelah layar HP dibuka kembali, kembalikan posisi tab ke tempat semula.
        tabKbOffset = 0f
        applyTabOffset()
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
        loading.release()
        connDialog?.dismiss()
        worker.shutdown()
        io.shutdown()
        poller.shutdownNow()
        // Koneksi ADB sengaja TIDAK ditutup di sini: ia hidup di App + ConnectionService.
        // Hanya lepas pendengar log milik Activity ini.
        (application as App).logListener = null
        adb.onProgress = null
    }

    /**
     * Dialog Setting: ubah daftar aplikasi (tambah/hapus) dan versi yang diharapkan per jenis perangkat
     * (POS / TABLET / PDA). Kolom versi dikosongkan = aplikasi hanya dicek terpasang, versinya tidak dibandingkan.
     * Perubahan ditahan sebagai draf (juga saat pindah jenis) dan baru disimpan saat menekan Simpan.
     */
    private fun showVersionSettings() {
        if (isFinishing) return
        val types = AdbController.DEVICE_PACKAGES.keys.toList()
        // Draf per jenis: daftar paket (berurutan) dan versinya, awalnya dari nilai yang berlaku sekarang.
        val draftPkgs = LinkedHashMap<String, MutableList<String>>()
        val draftVers = LinkedHashMap<String, MutableMap<String, String>>()
        for (t in types) {
            draftPkgs[t] = VersionStore.packages(t).toMutableList()
            draftVers[t] = VersionStore.expected(t).toMutableMap()
        }
        var current = if (deviceType in types) deviceType else types.first()
        val fields = LinkedHashMap<String, android.widget.EditText>()

        // ---- Gaya: warna dari tema aplikasi (ikut mode gelap), bingkai membulat, jarak seragam ----
        val cText = ContextCompat.getColor(this, R.color.accent_text)
        val cMuted = ContextCompat.getColor(this, R.color.muted)
        val cSurface = ContextCompat.getColor(this, R.color.surface)
        val cOutline = ContextCompat.getColor(this, R.color.outline)
        val cCard = ContextCompat.getColor(this, R.color.chip_bg)
        val cStroke = ContextCompat.getColor(this, R.color.stroke)
        val cAccent = ContextCompat.getColor(this, R.color.accent)
        val cAccentText = ContextCompat.getColor(this, R.color.accent_text)
        val cTab = ContextCompat.getColor(this, R.color.btn_main)
        val cOnTab = ContextCompat.getColor(this, R.color.on_tab_button)
        val matchP = ViewGroup.LayoutParams.MATCH_PARENT
        val wrapP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun rounded(fill: Int, stroke: Int?): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(fill)
                if (stroke != null) setStroke(dp(1), stroke)
            }

        /** Parameter tata letak lebar penuh dengan jarak atas [top] dp; tinggi [h] (default menyesuaikan isi). */
        fun lpTop(top: Int, h: Int = wrapP) = android.widget.LinearLayout.LayoutParams(matchP, h).apply { topMargin = dp(top) }

        fun label(s: String, bold: Boolean = false, size: Float = 12f, color: Int = cMuted) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            setLineSpacing(0f, 1.25f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        fun field(placeholder: String, value: String) = android.widget.EditText(this).apply {
            setText(value)
            hint = placeholder
            textSize = 14f
            setTextColor(cText)
            setHintTextColor(cMuted)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = rounded(cSurface, cOutline)
            setPadding(dp(12), 0, dp(12), 0)
        }

        val scroll = ScrollView(this)
        val list = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val btnAdd = MaterialButton(this).apply {
            text = "Tambah aplikasi"
            setIconResource(R.drawable.ic_add)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(8)
            backgroundTintList = ColorStateList.valueOf(cTab)
            val onTab = ContextCompat.getColor(this@MainActivity, R.color.on_tab_button)
            setTextColor(onTab)
            iconTint = ColorStateList.valueOf(onTab)
        }
        val btnExport = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Export"
            setIconResource(R.drawable.ic_upload_file)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(6)
            setTextColor(cAccentText)
            iconTint = ColorStateList.valueOf(cAccentText)
            strokeColor = ColorStateList.valueOf(cAccentText)
            rippleColor = ColorStateList.valueOf(cAccentText)
        }
        val btnImport = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Import"
            setIconResource(R.drawable.ic_download)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(6)
            setTextColor(cAccentText)
            iconTint = ColorStateList.valueOf(cAccentText)
            strokeColor = ColorStateList.valueOf(cAccentText)
            rippleColor = ColorStateList.valueOf(cAccentText)
        }
        val ioButtons = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(btnExport, android.widget.LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(btnImport, android.widget.LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        }
        val buttons = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(btnAdd, android.widget.LinearLayout.LayoutParams(matchP, dp(44)))
        }

        /** Salin isi kolom versi ke draf jenis yang sedang tampil. */
        fun commitFields() {
            val d = draftVers.getValue(current)
            for ((pkg, et) in fields) {
                val v = et.text?.toString()?.trim().orEmpty()
                if (v.isNotEmpty()) d[pkg] = v else d.remove(pkg)
            }
        }

        // Diisi setelah showEditVersionDialog dideklarasikan (fungsi lokal tidak bisa saling memanggil maju).
        var onEditVersion: (String) -> Unit = {}

        fun buildFields() {
            list.removeAllViews()
            fields.clear()
            val pkgs = draftPkgs.getValue(current)
            val vers = draftVers.getValue(current)
            list.addView(label("DAFTAR APLIKASI · ${pkgs.size}", bold = true, size = 11f, color = cAccentText).apply { letterSpacing = 0.08f }, lpTop(24))
            for (pkg in pkgs.toList()) {
                val et = field("tanpa cek versi", vers[pkg].orEmpty()).apply {
                    // Hanya tampilan; ubah lewat ikon pensil (kotak ini sendiri tidak bisa diketuk).
                    isFocusable = false
                    isCursorVisible = false
                }
                // Tombol ikon seragam: 44dp, bingkai membulat berwarna lembut, efek ketuk (ripple).
                fun iconButton(res: Int, tint: Int, desc: String, onClick: () -> Unit) = android.widget.ImageView(this).apply {
                    setImageResource(res)
                    imageTintList = ColorStateList.valueOf(tint)
                    contentDescription = desc
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(Color.argb(60, Color.red(tint), Color.green(tint), Color.blue(tint))),
                        rounded(Color.argb(32, Color.red(tint), Color.green(tint), Color.blue(tint)), null),
                        null
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                }
                val edit = iconButton(R.drawable.ic_edit, Color.rgb(245, 158, 11), "Edit versi $pkg") { onEditVersion(pkg) }
                val del = iconButton(R.drawable.ic_delete, Color.rgb(220, 38, 38), "Hapus $pkg") {
                    commitFields()
                    draftPkgs.getValue(current).remove(pkg)
                    draftVers.getValue(current).remove(pkg)
                    buildFields()
                }
                val name = label(pkg, bold = true, size = 13f, color = cText).apply {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }
                // Versi ditampilkan sebagai lencana; EditText tetap dipakai sebagai penampung nilai (tidak ditampilkan).
                val verText = vers[pkg].orEmpty()
                val badge = TextView(this).apply {
                    text = if (verText.isEmpty()) "tanpa cek versi" else "v$verText"
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (verText.isEmpty()) cMuted else cAccentText)
                    background = rounded(if (verText.isEmpty()) cCard else ContextCompat.getColor(this@MainActivity, R.color.accent_text_soft), null).apply { cornerRadius = dp(8).toFloat() }
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                }
                // Kiri: nama paket + lencana versi. Kanan: tombol edit & hapus berukuran kecil.
                val textCol = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(name)
                    addView(badge, android.widget.LinearLayout.LayoutParams(wrapP, wrapP).apply { topMargin = dp(8) })
                }
                val card = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = rounded(cSurface, cStroke).apply { cornerRadius = dp(14).toFloat() }
                    setPadding(dp(16), dp(12), dp(10), dp(12))
                    addView(textCol, android.widget.LinearLayout.LayoutParams(0, wrapP, 1f))
                    addView(edit, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
                    addView(del, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
                }
                list.addView(card, lpTop(10))
                fields[pkg] = et
            }
        }

        fun showEditVersionDialog(pkg: String) {
            commitFields()
            val inVer = field("kosong = tanpa cek versi", draftVers.getValue(current)[pkg].orEmpty())
            val form = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
                addView(label(pkg, bold = true, size = 13f, color = cText))
                addView(label("Versi (versionName)", bold = true, color = cText), lpTop(16))
                addView(inVer, lpTop(6, dp(44)))
            }
            showPopup(
                "Edit Versi", icon = R.drawable.ic_edit, content = form, hasInput = true,
                positive = "OK", negative = "Batal"
            ) {
                val v = inVer.text?.toString()?.trim().orEmpty()
                if (v.isNotEmpty()) draftVers.getValue(current)[pkg] = v else draftVers.getValue(current).remove(pkg)
                buildFields()
                true
            }
        }

        onEditVersion = { pkg -> showEditVersionDialog(pkg) }

        fun showAddDialog() {
            val inPkg = field("com.contoh.app", "")
            val inVer = field("contoh: 1.0.0", "")
            val form = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
                addView(label("Nama paket", bold = true, color = cText))
                addView(inPkg, lpTop(6, dp(44)))
                addView(label("Versi (wajib diisi)", bold = true, color = cText), lpTop(16))
                addView(inVer, lpTop(6, dp(44)))
            }
            showPopup(
                "Tambah Aplikasi ke $current", icon = R.drawable.ic_add, content = form, hasInput = true,
                positive = "Tambah", negative = "Batal"
            ) {
                // Isian belum valid: popup tetap terbuka (return false).
                val pkg = inPkg.text?.toString()?.trim().orEmpty()
                val ver = inVer.text?.toString()?.trim().orEmpty()
                when {
                    !Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(pkg) -> {
                        Toast.makeText(this, "Tambah aplikasi gagal: nama paket tidak valid. Contoh: com.contoh.app", Toast.LENGTH_LONG).show()
                        false
                    }
                    ver.isEmpty() -> {
                        Toast.makeText(this, "Tambah aplikasi gagal: versi tidak boleh kosong. Isi versionName yang diharapkan", Toast.LENGTH_LONG).show()
                        false
                    }
                    pkg in draftPkgs.getValue(current) -> {
                        Toast.makeText(this, "Tambah aplikasi gagal: aplikasi sudah ada di daftar $current. Ubah versinya lewat daftar yang ada", Toast.LENGTH_LONG).show()
                        false
                    }
                    else -> {
                        commitFields()
                        draftPkgs.getValue(current).add(pkg)
                        draftVers.getValue(current)[pkg] = ver
                        buildFields()
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        true
                    }
                }
            }
        }

        // Pilihan jenis perangkat: tombol segmen (POS / TABLET / PDA).
        val typeChips = LinkedHashMap<String, TextView>()
        fun refreshChips() {
            for ((t, tv) in typeChips) {
                val sel = t == current
                tv.background = if (sel) rounded(cTab, null) else null
                tv.setTextColor(if (sel) cOnTab else cMuted)
            }
        }
        val typeRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = rounded(cCard, null).apply { cornerRadius = dp(12).toFloat() }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            for (t in types) {
                val tv = TextView(this@MainActivity).apply {
                    text = t
                    gravity = android.view.Gravity.CENTER
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (t == current) return@setOnClickListener
                        commitFields()
                        current = t
                        refreshChips()
                        buildFields()
                    }
                }
                typeChips[t] = tv
                addView(tv, android.widget.LinearLayout.LayoutParams(0, dp(38), 1f))
            }
        }
        refreshChips()
        btnAdd.setOnClickListener { showAddDialog() }
        btnExport.setOnClickListener {
            // Ekspor data yang sudah tersimpan (perubahan di dialog yang belum disimpan tidak ikut).
            onExportPicked = { uri ->
                try {
                    val json = VersionStore.toJson(
                        types,
                        types.associateWith { VersionStore.packages(it) },
                        types.associateWith { VersionStore.expected(it) }
                    )
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: throw java.io.IOException("tidak bisa menulis file")
                    Toast.makeText(this, "Versi aplikasi diekspor", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    // Alasan singkat berbahasa Indonesia untuk teknisi; teks exception mentah hanya di log DETAIL.
                    val msg = "Ekspor versi aplikasi gagal: file tidak bisa ditulis. Pilih lokasi lain, lalu coba lagi"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    log(LogLevel.ERR, msg)
                    log(LogLevel.DETAIL, Fmt.cause(e))
                }
            }
            exportVersionsLauncher.launch("versi-aplikasi.json")
        }
        btnImport.setOnClickListener {
            onImportPicked = { uri ->
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("tidak bisa membaca file")
                    if (bytes.size > 1_000_000) throw IllegalArgumentException("file terlalu besar")
                    val parsed = VersionStore.parseJson(String(bytes, Charsets.UTF_8), types)
                    for ((t, pv) in parsed) {
                        draftPkgs[t] = pv.first.toMutableList()
                        draftVers[t] = pv.second.toMutableMap()
                    }
                    buildFields()
                    Toast.makeText(this, "Versi aplikasi diimpor (${parsed.keys.joinToString()}). Ketuk \"Simpan\" untuk menerapkan", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    // Pesan dari kode ini sendiri (IllegalArgumentException / "tidak bisa ...") sudah berbahasa Indonesia
                    // dan dipakai sebagai alasan; exception sistem diganti alasan umum. Teks mentah hanya di log DETAIL.
                    val reason = e.message?.takeIf {
                        e is IllegalArgumentException || (e is java.io.IOException && it.startsWith("tidak bisa"))
                    } ?: "file tidak bisa dibaca"
                    val msg = "Impor versi aplikasi gagal: $reason. Pilih file hasil ekspor aplikasi ini"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    log(LogLevel.ERR, msg)
                    log(LogLevel.DETAIL, Fmt.cause(e))
                }
            }
            importVersionsLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(label("JENIS PERANGKAT", bold = true, size = 11f, color = cAccentText).apply { letterSpacing = 0.08f })
            addView(typeRow, lpTop(10))
            addView(buttons, lpTop(12, dp(44)))
            addView(ioButtons, lpTop(8, dp(44)))
            addView(list)
        }
        scroll.addView(box)
        // Isi kolom SEBELUM dialog tampil. Android memeriksa ada-tidaknya kolom ketik saat dialog dibuka;
        // bila kosong, keyboard diblokir (FLAG_ALT_FOCUSABLE_IM) dan versi tidak bisa diketik.
        buildFields()

        showPopup(
            "Pengaturan Aplikasi & Versi", "Daftar aplikasi dan versi yang dicek per jenis perangkat",
            icon = R.drawable.ic_settings, content = scroll, ownScroll = true, hasInput = true,
            positive = "Simpan", negative = "Batal"
        ) {
            commitFields()
            for (t in types) VersionStore.save(t, draftPkgs.getValue(t), draftVers.getValue(t))
            // Hasil cek lama dibuat tidak berlaku karena daftar/versi berubah; status kembali "Belum diperiksa".
            appsInstalled = null
            appsType = null
            refreshApps()
            Toast.makeText(this, "Pengaturan versi disimpan. Ketuk \"Cek Status\" untuk mengecek ulang", Toast.LENGTH_LONG).show()
            true
        }
    }

    private var connDialog: AlertDialog? = null

    private enum class PopupTone { NAVY, DANGER }

    /**
     * Merapikan teks isi popup: label ("Penyebab:", "Periksa:", "Langkah berikutnya:", "Catatan:", "Perhatian:", "Perintah:")
     * ditebalkan, perintah adb memakai huruf monospace, dan baris "• " diberi indent gantung supaya baris lanjutan sejajar dengan isi.
     */
    private fun formatPopupMessage(text: String): CharSequence {
        val labelRe = Regex("^(Penyebab|Periksa|Langkah berikutnya|Catatan|Perhatian|Perintah):")
        val strong = ContextCompat.getColor(this, R.color.text)
        val sb = android.text.SpannableStringBuilder()
        val lines = text.split("\n")
        for ((i, line) in lines.withIndex()) {
            val start = sb.length
            sb.append(line)
            if (i < lines.lastIndex) sb.append('\n')
            val m = labelRe.find(line)
            if (m != null) {
                val labelEnd = start + m.value.length
                sb.setSpan(StyleSpan(Typeface.BOLD), start, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(strong), start, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (m.groupValues[1] == "Perintah") {
                    sb.setSpan(android.text.style.TypefaceSpan("monospace"), labelEnd, start + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else if (line.startsWith("• ")) {
                sb.setSpan(LeadingMarginSpan.Standard(0, dp(12)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    /**
     * Satu-satunya bentuk popup di aplikasi (layout dialog_popup): ikon bulat, judul di tengah, isi, lalu tombol.
     * Semua popup (hasil, konfirmasi, pilihan, form, QR, pengaturan) lewat sini supaya tampilannya seragam.
     * [tone] NAVY = netral/berhasil, DANGER = gagal/berbahaya. Tombol positif selalu navy.
     * [positive] null = tanpa tombol positif; [negative] null = tanpa tombol Batal.
     * [onPositive] mengembalikan true untuk menutup popup, false untuk tetap terbuka (mis. isian belum valid).
     * [ownScroll] true bila [content] sudah punya ScrollView sendiri (tidak dibungkus ScrollView popup lagi).
     * [hasInput] true bila [content] berisi kolom ketik (keyboard tidak boleh diblokir).
     */
    private fun showPopup(
        title: String,
        message: String? = null,
        icon: Int = R.drawable.ic_info,
        tone: PopupTone = PopupTone.NAVY,
        content: View? = null,
        ownScroll: Boolean = false,
        positive: String? = "OK",
        negative: String? = null,
        hasInput: Boolean = false,
        onNegative: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        onPositive: (() -> Boolean)? = null
    ): AlertDialog? {
        if (isFinishing || isDestroyed) return null
        val v = layoutInflater.inflate(R.layout.dialog_popup, null)
        val danger = tone == PopupTone.DANGER
        v.findViewById<View>(R.id.popupIconBg).setBackgroundResource(if (danger) R.drawable.bg_badge_danger else R.drawable.bg_badge_navy)
        v.findViewById<android.widget.ImageView>(R.id.popupIcon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, if (danger) R.color.danger else R.color.accent_text))
        }
        v.findViewById<TextView>(R.id.popupTitle).text = title
        val msg = v.findViewById<TextView>(R.id.popupMessage)
        if (message.isNullOrEmpty()) msg.visibility = View.GONE else {
            msg.text = formatPopupMessage(message)
            // Satu kalimat pendek: rata tengah di bawah judul; pesan berparagraf/berdaftar: rata kiri.
            msg.gravity = if (message.contains("\n")) android.view.Gravity.START else android.view.Gravity.CENTER_HORIZONTAL
        }
        if (content != null) {
            if (ownScroll) {
                val body = v.findViewById<android.widget.FrameLayout>(R.id.popupBody)
                body.removeAllViews()
                body.addView(content, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                v.findViewById<android.widget.FrameLayout>(R.id.popupContent).apply {
                    visibility = View.VISIBLE
                    // Tanpa teks isi, kolom/daftar langsung di bawah judul dengan jarak kecil.
                    if (message.isNullOrEmpty()) (layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(8)
                    addView(content, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
        }
        val dlg = AlertDialog.Builder(this).setView(v).create()
        val ok = v.findViewById<MaterialButton>(R.id.popupOk)
        val cancel = v.findViewById<MaterialButton>(R.id.popupCancel)
        if (positive == null) ok.visibility = View.GONE else {
            ok.text = positive
            ok.setOnClickListener { if (onPositive?.invoke() != false) dlg.dismiss() }
        }
        if (negative != null) {
            cancel.visibility = View.VISIBLE
            cancel.text = negative
            // Tanpa tombol positif, Batal memakai lebar penuh dan tidak perlu jarak kanan.
            if (positive == null) (cancel.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = 0
            cancel.setOnClickListener { onNegative?.invoke(); dlg.dismiss() }
        }
        if (onDismiss != null) dlg.setOnDismissListener { onDismiss() }
        dlg.show()
        if (hasInput) dlg.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        return dlg
    }

    /** Popup hasil proses (gagal, putus, atau berhasil). [ok] true = ikon centang navy; gagal = ikon merah. Popup lama diganti agar tidak bertumpuk. */
    private fun showConnProblem(title: String, message: String, ok: Boolean = false) {
        connDialog?.dismiss()
        connDialog = showPopup(
            title, message,
            icon = if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning,
            tone = if (ok) PopupTone.NAVY else PopupTone.DANGER
        )
    }

    /** Popup konfirmasi: judul, isi, Batal + tombol aksi. */
    private fun showConfirm(
        title: String, message: String, positive: String,
        icon: Int = R.drawable.ic_warning, tone: PopupTone = PopupTone.DANGER,
        onConfirm: () -> Unit
    ) {
        showPopup(title, message, icon, tone, positive = positive, negative = "Batal", onPositive = { onConfirm(); true })
    }

    /** Minta konfirmasi sebelum keluar; peringatkan bila masih ada proses berjalan. */
    private fun confirmExit() {
        if (isFinishing) return
        val warn = if (busy) "\n\nPerhatian: masih ada proses yang berjalan dan akan ikut terhenti." else ""
        showConfirm(
            "Keluar dari Aplikasi?",
            "Koneksi ADB akan diputus, layanan latar dihentikan, dan daftar APK dikosongkan.$warn",
            "Keluar", icon = R.drawable.ic_logout, tone = PopupTone.NAVY
        ) { exitApp() }
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

    /** Dipanggil tiap detik dari pemantau. Saat operasi (install dll) berjalan, timer DITAHAN (pause) di sisa waktu terakhir, tidak direset. */
    private fun updateSessionTimer() {
        if (lockView != null) return
        val now = SystemClock.elapsedRealtime()
        // Geser titik awal sebesar waktu yang berlalu selama sibuk, sehingga sisa waktu tetap sama lalu lanjut menghitung setelah selesai.
        if (busy) lastActivityAt += now - timerTickAt
        timerTickAt = now
        val left = sessionRemainingMs()
        if (left <= 0) { showLock(); return }
        val sec = (left + 999) / 1000
        txtSession.text = if (sec >= 3600) "Sesi %d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
                          else "Sesi %02d:%02d".format(sec / 60, sec % 60)
        txtSession.setTextColor(if (sec <= 60) Color.rgb(253, 224, 71) else ContextCompat.getColor(this, R.color.header_subtext))
    }

    private fun removeLock() {
        lockView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        lockView = null
        backWhileLocked.isEnabled = false
        lastActivityAt = SystemClock.elapsedRealtime()
        timerTickAt = lastActivityAt
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
            log(LogLevel.ERR, "Koneksi ke $addr terputus" + (if (cableGone) ", kabel USB dicabut" else "") + " · ${Fmt.clock(System.currentTimeMillis())}")
            adb.lastDropReason.takeIf { it.isNotEmpty() }?.let { log(LogLevel.DETAIL, "Penyebab: $it") }
            adb.lastDropDetail.takeIf { it.isNotEmpty() }?.let { log(LogLevel.DETAIL, it) }
            showConnProblem(
                "Koneksi terputus",
                "Koneksi ke $addr terputus" + (if (cableGone) " karena kabel USB dicabut" else "") + "." +
                    (adb.lastDropReason.takeIf { it.isNotEmpty() }?.let { "\n\nPenyebab:\n• $it" } ?: "") +
                    "\n\nLangkah berikutnya: sambungkan ulang lewat \"Pair\" dan \"Connect\", atau \"Connect USB\"."
            )
            showUsbDevice(null)
            lostAddr = addr
            adb.sessionLabel = null
            connectedAddr = null
            connectedSince = 0L
            ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; kasperskyInstalled = null; hmdmInstalled = null; tcpipActive = null; appsInstalled = null; hmdmPerms = null; permType = null; deviceModel = null
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
        val hint2 = TextView(this).apply {
            text = "HP teknisi dan HP target harus di Wi-Fi yang sama."
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(img)
            addView(hint2, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        val dialog = showPopup(
            "Scan QR untuk Pair",
            "HP target: Opsi Developer › Wireless debugging › Pair device with QR code, lalu arahkan kamera ke QR ini.",
            icon = R.drawable.ic_wifi, content = box, positive = null, negative = "Batal",
            onDismiss = { cancel.set(true) }
        )
        val shownAt = System.currentTimeMillis()
        io.execute {
            val found = AdbDiscovery.find(this, AdbDiscovery.TYPE_PAIRING, QR_PAIR_TIMEOUT_MS, cancel) { name, _, _ -> name == session.serviceName }
            if (found == null) {
                if (!cancel.get()) runOnUiThread {
                    dialog?.dismiss()
                    logHead("Pair dengan QR", shownAt)
                    log(LogLevel.ERR, "Pair dengan QR gagal: HP target tidak memindai QR dalam ${QR_PAIR_TIMEOUT_MS / 1000} dtk atau layanan pairing tidak ditemukan. Pastikan satu Wi-Fi, lalu coba lagi")
                    logEnd("Pair dengan QR", false, shownAt)
                    showConnProblem(
                        "Pair dengan QR gagal",
                        "HP target tidak memindai QR dalam ${QR_PAIR_TIMEOUT_MS / 1000} detik, atau layanan pairing tidak ditemukan.\n\n" +
                            "Periksa:\n• Layar \"Pair device with QR code\" di HP target terbuka dan kamera diarahkan ke QR\n• HP target dan perangkat ini satu jaringan Wi-Fi (client isolation mati)\n\n" +
                            "Rincian lengkap ada di log."
                    )
                }
                return@execute
            }
            runOnUiThread {
                dialog?.dismiss()
                if (busy) return@runOnUiThread
                edtPairIp.setText(found.host); edtPairPort.setText(found.port.toString()); edtPairCode.setText(session.password)
                run("Pair dengan QR") { doPairQr(found, session.password) }
            }
        }
    }

    private fun doPairQr(f: AdbDiscovery.Found, password: String): Boolean {
        if (!doPair(f.addr, password, showNext = false)) return false
        // Setelah pair, cari port connect HP yang sama lalu sambung otomatis.
        // Beri waktu adbd di HP target siap menerima koneksi TLS setelah pairing.
        try { Thread.sleep(2_000) } catch (e: InterruptedException) { }
        val ca = adb.findConnectAddr(f.host)
        if (ca == null) {
            log(LogLevel.WARN, "Port connect tidak ditemukan otomatis. Isi IP dan port connect (layar utama Wireless debugging) lalu ketuk \"Connect\"")
            return true
        }
        val (ip, port) = ca.substringBeforeLast(':') to ca.substringAfterLast(':')
        runOnUiThread { edtConnIp.setText(ip); edtConnPort.setText(port) }
        // Hasil connect menentukan penutup proses: jangan "✓ Selesai" tepat setelah "✗ Connect gagal".
        return doConnect(ca)
    }

    // ---------- Langkah (jalan di thread kerja) ----------

    /** [showNext] false untuk Pair dengan QR: connect dilanjutkan otomatis, jadi petunjuk "Berikutnya" tidak perlu. */
    private fun doPair(addr: String, code: String, showNext: Boolean = true): Boolean {
        val ok = adb.pair(addr, code)
        if (ok) {
            // Pairing berhasil: IP connect otomatis diisi sama dengan IP pairing (HP target sama).
            // Port connect berbeda dari port pairing, jadi hanya IP yang diisi; port diisi manual.
            val pairIp = addr.substringBeforeLast(':').trim()
            runOnUiThread {
                if (pairIp.isNotEmpty()) edtConnIp.setText(pairIp)
                if (showNext && pairIp.isNotEmpty()) {
                    log(LogLevel.STEP, "IP connect diisi otomatis: $pairIp")
                    log(LogLevel.STEP, "Berikutnya: isi port connect (layar utama Wireless debugging), lalu ketuk \"Connect\"")
                }
                refreshHeader()
            }
        }
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
            ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; kasperskyInstalled = null; hmdmInstalled = null; tcpipActive = null; appsInstalled = null; hmdmPerms = null; permType = null; deviceModel = null
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
            ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; kasperskyInstalled = null; hmdmInstalled = null; tcpipActive = null; appsInstalled = null; hmdmPerms = null; permType = null; deviceModel = null
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
        if (ok) { connectedAddr = null; connectedSince = 0L; lostAddr = null; ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; kasperskyInstalled = null; hmdmInstalled = null; tcpipActive = null; appsInstalled = null; hmdmPerms = null; permType = null; deviceModel = null; showUsbDevice(null) }
        runOnUiThread { refreshHeader() }
        return ok
    }

    private fun doDisconnect(): Boolean {
        val ok = adb.disconnect()
        if (ok) { connectedAddr = null; connectedSince = 0L; lostAddr = null; ownerState = OwnerState.Unknown; homeState = HomeState.Unknown; kasperskyInstalled = null; hmdmInstalled = null; tcpipActive = null; appsInstalled = null; hmdmPerms = null; permType = null; deviceModel = null; showUsbDevice(null) }
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
        runErrors.clear()
        adb.lastInstall = null
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
                if (adb.isConnected) {
                    val st = adb.queryDeviceOwner(); if (st != OwnerState.Unknown) ownerState = st
                    adb.queryKasperskyInstalled()?.let { kasperskyInstalled = it }
                    adb.queryHmdmInstalled()?.let { hmdmInstalled = it }
                    adb.queryTcpipActive()?.let { tcpipActive = it }
                    if (!skipInfo) {
                        val t = deviceType
                        adb.queryRequiredPackages(t)?.let { appsInstalled = it; appsType = t }
                        if (hmdmInType(t)) adb.queryHmdmPermissions()?.let { hmdmPerms = it; permType = t }
                    }
                }
            } catch (e: Throwable) { }
            runOnUiThread {
                logEnd(title, ok, startedAt)
                busy = false
                setBusy(false, if (ok) "Selesai: $title" else "Gagal: $title, lihat log")
                refreshHeader()
                val inst = adb.lastInstall
                val isInstall = title == "Pasang Aplikasi" || title == "Pasang Aplikasi + Jadikan MDM Mode"
                if (!ok && isInstall && inst != null && inst.total > 1 && inst.failed.isNotEmpty()) {
                    val shown = inst.failed.take(8).joinToString("\n") { (name, why) -> "• $name: $why" }
                    val more = if (inst.failed.size > 8) "\n• dan ${inst.failed.size - 8} APK lainnya (lihat log)" else ""
                    showConnProblem(
                        if (inst.ok == 0) "Semua APK gagal dipasang" else "${inst.failed.size} APK gagal dipasang",
                        "${inst.ok} dari ${inst.total} APK berhasil dipasang, ${inst.failed.size} gagal.\n\n" +
                            "Penyebab:\n$shown$more\n\n" +
                            "Langkah berikutnya: perbaiki penyebabnya, lalu pasang ulang APK yang gagal." +
                            (if (inst.ok > 0) " APK yang berhasil tidak perlu dipasang ulang." else "") +
                            (if (title.contains("MDM")) "\n\nCatatan: langkah \"Jadikan MDM Mode\" tetap dijalankan, cek hasilnya di log." else "")
                    )
                } else if (!ok) {
                    val isPair = title == "Pair" || title == "Pair dengan QR"
                    val isConn = title == "Connect" || title == "Connect USB"
                    val popupTitle = "$title gagal"
                    val hint = when {
                        title == "Pair dengan QR" -> "Periksa:\n• Layar \"Pair device with QR code\" di HP target masih terbuka dan QR terpindai\n• HP target dan perangkat ini satu jaringan Wi-Fi"
                        isPair -> "Periksa:\n• IP, port pairing, dan kode 6 digit sesuai layar \"Pair device with pairing code\" di HP target\n• Kode belum kedaluwarsa (muncul ulang bila layar ditutup)\n• HP target dan perangkat ini satu jaringan Wi-Fi"
                        title == "Connect USB" -> "Periksa:\n• Kabel USB terpasang kuat dan tidak terlepas\n• Izin akses USB di layar aplikasi ini dipilih \"Izinkan\"\n• Dialog \"Izinkan USB debugging?\" di HP target disetujui (centang \"Selalu izinkan\")\n• Mode USB di HP target: \"Transfer file\""
                        isConn -> "Periksa:\n• IP dan port sesuai layar Wireless debugging di HP target\n• HP target dan perangkat ini satu jaringan Wi-Fi\n• Pairing sudah dilakukan bila diminta"
                        title == "Pasang Aplikasi" || title == "Pasang Aplikasi + Jadikan MDM Mode" -> "Periksa:\n• Layar HP target menyala dan koneksi masih tersambung\n• Penyimpanan HP target cukup\n• Versi APK tidak lebih rendah dari yang sudah terpasang\n• Dialog di HP target (bila muncul) disetujui"
                        title == "Jadikan MDM Mode" -> "Periksa:\n• Semua akun (Google dan lainnya) di HP target sudah dihapus\n• HP target baru atau habis factory reset\n• Belum ada aplikasi lain yang menjadi device owner"
                        title == "Hapus Data Aplikasi Kaspersky" -> "Periksa:\n• Koneksi ke HP target masih tersambung\n• Aplikasi Kaspersky masih terpasang di HP target"
                        title == "Uninstall Aplikasi Kaspersky" -> "Periksa:\n• Aplikasi Admin Kaspersky sudah dimatikan di HP target (Langkah 2)\n• Aplikasi Kaspersky masih terpasang di HP target"
                        title == "Ubah ke Mode Nirkabel" -> "Periksa:\n• Kabel USB masih terpasang kuat\n• Layar HP target menyala dan USB debugging aktif"
                        title == "Disconnect" -> ""
                        else -> "Periksa:\n• Koneksi ke HP target masih tersambung"
                    }
                    val reasons = runErrors.distinct().takeLast(3).joinToString("\n") { "• ${errReason(it)}" }
                    val msg = buildString {
                        if (reasons.isNotEmpty()) append("Penyebab:\n").append(reasons)
                        else append("Proses tidak berhasil dan tidak ada penyebab yang tercatat.")
                        if (hint.isNotEmpty()) append("\n\n").append(hint)
                        append("\n\nRincian lengkap ada di log.")
                    }
                    showConnProblem(popupTitle, msg)
                } else {
                    val connNow = connectedAddr?.takeIf { adb.isConnected }
                    val okMsg = when (title) {
                        "Pair" -> "Pairing dengan HP target berhasil.\n\nLangkah berikutnya: isi port connect (tampil di layar utama Wireless debugging HP target), lalu ketuk \"Connect\"."
                        "Pair dengan QR" ->
                            if (connNow != null) "Pairing berhasil dan sudah terhubung ke $connNow.\n\nLangkah berikutnya: pasang aplikasi atau jadikan MDM Mode."
                            else "Pairing berhasil, tetapi port connect tidak ditemukan otomatis.\n\nLangkah berikutnya: isi IP dan port connect (layar utama Wireless debugging HP target), lalu ketuk \"Connect\"."
                        "Connect" -> "Berhasil terhubung ke ${connNow ?: "HP target"}.\n\nLangkah berikutnya: pasang aplikasi atau jadikan MDM Mode."
                        "Connect USB" -> "Berhasil terhubung ke HP target lewat kabel USB.\n\nLangkah berikutnya: pasang aplikasi atau jadikan MDM Mode."
                        "Disconnect" -> "Koneksi ke HP target sudah diputus.\n\nUntuk menyambung lagi, lakukan \"Pair\" dan \"Connect\", atau \"Connect USB\"."
                        "Pasang Aplikasi" -> if (inst != null && inst.total > 0) "${inst.total} APK berhasil dipasang ke HP target." else "Semua APK berhasil dipasang ke HP target."
                        "Jadikan MDM Mode" -> "HMDM berhasil dijadikan MDM Mode di HP target."
                        "Pasang Aplikasi + Jadikan MDM Mode" -> (if (inst != null && inst.total > 0) "${inst.total} APK berhasil dipasang" else "Semua APK berhasil dipasang") + " dan HMDM berhasil dijadikan MDM Mode di HP target."
                        "Hapus Data Aplikasi Kaspersky" -> "Data dan pengaturan aplikasi Kaspersky berhasil dihapus dari HP target."
                        "Uninstall Aplikasi Kaspersky" -> "Aplikasi Kaspersky berhasil di-uninstall dari HP target."
                        "Ubah ke Mode Nirkabel" -> "Mode nirkabel (TCP/IP) berhasil diaktifkan di HP target.\n\nLangkah berikutnya: cabut kabel, lalu sambungkan lewat tab \"Wireless\" (\"Pair\" lalu \"Connect\")."
                        else -> null
                    }
                    if (okMsg != null) showConnProblem("$title berhasil", okMsg, ok = true)
                }
            }
        }
    }

    // ---------- Progress install (inline di halaman, di atas log) ----------

    private var progShown = false
    private var progIdx = -1
    private var progStartMs = 0L

    private fun showInstallProgress(idx: Int, total: Int, name: String, sent: Long, size: Long) {
        if (isFinishing || !busy) return
        val box = findViewById<View>(R.id.progBox)
        val bar = findViewById<ProgressBar>(R.id.barInstall)
        val n = findViewById<TextView>(R.id.txtProgName)
        val info = findViewById<TextView>(R.id.txtProgInfo)
        val pctView = findViewById<TextView>(R.id.txtProgPct)
        val speedView = findViewById<TextView>(R.id.txtProgSpeed)
        val now = android.os.SystemClock.elapsedRealtime()
        if (idx != progIdx || sent == 0L) { progIdx = idx; progStartMs = now }
        if (!progShown) {
            progShown = true
            box.visibility = View.VISIBLE
            // Halaman tidak digulir otomatis; progres juga tampil di kartu loading.
        }
        n.text = "Memasang APK $idx dari $total: $name"
        loading.update("Memasang APK $idx dari $total")
        if (size > 0 && sent >= size) {
            bar.isIndeterminate = true
            pctView.text = "100%"
            speedView.text = ""
            info.text = "Terkirim ${Fmt.mb(size)}. Menunggu HP target memasang..."
        } else {
            bar.isIndeterminate = false
            val pct = if (size > 0) (sent * 100 / size).toInt() else 0
            bar.progress = if (size > 0) (sent * 1000 / size).toInt() else 0
            pctView.text = "$pct%"
            val el = now - progStartMs
            speedView.text = if (el >= 500 && sent > 0)
                String.format(java.util.Locale.US, "%.1f MB/dtk", sent / 1_048_576.0 / (el / 1000.0)) else ""
            info.text = "${Fmt.mb(sent)} dari ${Fmt.mb(size)}"
        }
    }

    private fun hideInstallProgress() {
        if (!progShown) return
        progShown = false
        progIdx = -1
        findViewById<View>(R.id.progBox)?.visibility = View.GONE
    }

    private fun setBusy(on: Boolean, status: String) {
        if (!on) hideInstallProgress()
        adb.holdLocks(on)
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyButtonState(on)
        applyTypeChipState(on)
        apkListBox.alpha = if (on) 0.5f else 1f
        progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) loading.show(status) else loading.hide()
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
                txtConn.text = "Terputus · $lost, Connect ulang"
                statusDot.backgroundTintList = ColorStateList.valueOf(Color.rgb(248, 113, 113))
            }
            else -> {
                txtConn.text = "Belum terhubung, Pair lalu Connect atau Connect USB"
                statusDot.backgroundTintList = ColorStateList.valueOf(Color.rgb(148, 163, 184))
            }
        }
        refreshOwner()
        refreshApps()
        refreshPerms()
        refreshTcpip()
        applyButtonState(busy)
    }

    /**
     * Atur status tombol: semua tombol mati saat ada proses berjalan; tombol yang butuh device
     * (Pasang APK, set-device-owner, Otomatis, Hapus data & Uninstall Kaspersky) juga mati selama belum terhubung.
     */
    /**
     * Satu-satunya aturan aktif/tidaknya checkbox jenis perangkat: hanya bisa ditekan bila sudah terhubung
     * DAN tidak ada proses berjalan (Connect, Connect USB, Pairing QR, Cek Status, Pasang, dll).
     */
    private fun applyTypeChipState(isBusy: Boolean) {
        if (!::typeChips.isInitialized) return
        val on = !isBusy && connectedAddr != null && adb.isConnected
        typeChips.forEach { (_, box) -> box.isEnabled = on; box.isClickable = on; box.alpha = if (on) 1f else 0.4f }
    }

    private fun applyButtonState(isBusy: Boolean) {
        applyTypeChipState(isBusy)
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
            // "Ubah ke Mode Nirkabel" (adb tcpip) hanya untuk sesi yang berjalan lewat kabel USB.
            val notUsb = b.id == R.id.btnTcpip && connected && !adb.isViaUsb
            // Mode TCP/IP sudah aktif di HP target: tombol tidak perlu ditekan lagi.
            val tcpipAlready = b.id == R.id.btnTcpip && connected && tcpipActive == true
            val enabled = !isBusy && !notUsb && !tcpipAlready && !needsApk && !noHmdmApk && !noKaspersky && !noHmdm && !ownerActive && (connected || b !in needsConnection) && !(connected && b in blockedWhenConnected)
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

    /** True bila com.hmdm.launcher termasuk dalam daftar aplikasi jenis perangkat [type], atau daftarnya kosong (tidak dibatasi). */
    @Suppress("UNUSED_PARAMETER")
    private fun hmdmInType(type: String): Boolean = true

    /** Status gabungan tiga permission com.hmdm.launcher (admin perangkat, overlay, riwayat penggunaan). */
    private fun refreshPerms() {
        if (!::txtPermAdmin.isInitialized) return
        val connected = connectedAddr != null && adb.isConnected
        // Hanya tampilkan hasil bila dicek untuk jenis perangkat yang sedang terceklis.
        val applicable = hmdmInType(deviceType)
        val p = if (connected && applicable && permType == deviceType) hmdmPerms else null
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

    private fun refreshApps() {
        if (!::txtApps.isInitialized) return
        val connected = connectedAddr != null && adb.isConnected
        val st = if (connected && appsType == deviceType) appsInstalled else null
        val pkgs = VersionStore.packages(deviceType)
        val total = pkgs.size
        val grey = Color.rgb(148, 163, 184)
        val green = Color.rgb(74, 222, 128)
        val amber = Color.rgb(251, 191, 36)
        val red = Color.rgb(248, 113, 113)
        // Checkbox jenis perangkat: hanya satu yang tercentang (yang dipilih), selalu ada satu terpilih.
        // Selama belum terhubung, checkbox dinonaktifkan (tidak bisa diubah) dan tampil redup.
        typeChips.forEach { (type, box) ->
            box.isChecked = type == deviceType
            // Aktif/tidaknya diatur terpusat di applyTypeChipState (belum terhubung / proses berjalan).
        }
        val expected = VersionStore.expected(deviceType)
        val missing = if (st == null) emptyList() else pkgs.filter { st[it] == null }
        val wrong = if (st == null) emptyList() else pkgs.filter {
            st[it] != null && expected[it] != null && !AdbController.versionMatches(expected.getValue(it), st[it])
        }
        val okCount = total - missing.size - wrong.size
        val (text, color) = when {
            !connected -> "Belum terhubung" to grey
            total == 0 -> "Daftar aplikasi kosong" to grey
            st == null -> "Belum diperiksa" to grey
            missing.isEmpty() && wrong.isEmpty() ->
                (if (expected.isEmpty()) "Semua terpasang ✓ ($total/$total)" else "Versi sesuai ✓ ($total/$total)") to green
            // Kuning: semua terpasang tapi ada versi yang berbeda. Merah: ada aplikasi yang belum terpasang.
            missing.isEmpty() -> "Versi belum sesuai ($okCount/$total)" to amber
            else -> "Belum sesuai ($okCount/$total)" to red
        }
        txtApps.text = text
        appsDot.backgroundTintList = ColorStateList.valueOf(color)
        val dm = if (connected) deviceModel else null
        txtModel.text = when {
            dm == null -> "Belum diperiksa"
            dm.second != null -> "${dm.first} · ${dm.second}"
            else -> "${dm.first} · tidak dikenal"
        }
        // Rincian aplikasi bermasalah tidak ditampilkan di kartu; hanya ada di log.
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
            OwnerState.None ->
                when (hmdmInstalled) {
                    false -> "Belum diset (HMDM belum terpasang)" to Color.rgb(248, 113, 113)
                    true -> "Belum diset (HMDM sudah terpasang)" to Color.rgb(251, 191, 36)
                    null -> "Belum diset" to Color.rgb(251, 191, 36)
                }
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
    private fun apkKey(f: File) = "${f.absolutePath}|${f.length()}|${f.lastModified()}"

    private fun apkPackage(item: ApkItem): String {
        val f = item.file
        val key = apkKey(f)
        return apkPkgCache.getOrPut(key) {
            try {
                packageManager.getPackageArchiveInfo(f.absolutePath, 0)?.packageName ?: ""
            } catch (e: Throwable) { "" }
        }
    }

    /** Apakah daftar APK yang dipilih berisi com.hmdm.launcher. */
    /** Hanya membaca cache (aman di UI thread); cache diisi di thread latar oleh [apkPackage]. */
    private fun hasHmdmApk(): Boolean = apks.any { apkPkgCache[apkKey(it.file)] == AdbController.HMDM_PACKAGE }

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
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_text))
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

    /** Gulir isi log saja ke bawah, tanpa memindahkan fokus/halaman utama. */
    private fun scrollLogToEnd() {
        logScroll.post { logScroll.scrollTo(0, (logScroll.getChildAt(0).height - logScroll.height).coerceAtLeast(0)) }
    }

    /** Satu baris kosong sebagai pemisah antar proses. */
    private fun logBlank() {
        txtLog.append("\n")
        scrollLogToEnd()
    }

    /** Baris ERR selama satu proses, dipakai sebagai isi popup bila proses gagal. */
    private val runErrors = mutableListOf<String>()

    /**
     * Isi satu butir "Penyebab" di popup: baris ✗ tanpa awalan "<Aksi> gagal: " / "Gagal: ", karena judul popup
     * sudah memuat aksinya ("Connect USB gagal"). Huruf pertama dibuat kapital.
     */
    private fun errReason(s: String): String {
        val stripped = s.replace(Regex("^(?:[^:]{1,60}? )?[Gg]agal: "), "")
        // Kapitalkan hanya bila awalan benar-benar dibuang; baris seperti "agent.apk: ..." dibiarkan.
        return if (stripped.length != s.length) stripped.replaceFirstChar { it.uppercase() } else s
    }

    /**
     * Satu entri log berwarna. Warna dan penanda ditentukan oleh [level];
     * pesan multi-baris dirapikan (baris lanjutan diberi indent selebar penanda), dan titik di
     * akhir pesan dibuang supaya semua baris seragam (elipsis "..." dibiarkan).
     */
    private fun log(level: LogLevel, s: String) {
        if (s.isBlank()) return
        if (level == LogLevel.ERR) runErrors.add(s.trim())
        val indent = " ".repeat(level.marker.length)
        val body = s.trimEnd().let {
            // Output mentah device (DETAIL) tidak diubah sama sekali.
            if (level != LogLevel.DETAIL && it.endsWith(".") && !it.endsWith("..")) it.dropLast(1) else it
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
        scrollLogToEnd()
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
        // Parse nama paket di thread latar (bukan UI thread), lalu segarkan status tombol.
        val snapshot = apks.toList()
        io.execute {
            snapshot.forEach { apkPackage(it) }
            runOnUiThread { if (!isFinishing) refreshHeader() }
        }
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
        /** Batas menunggu HP target memindai QR pairing. */
        private const val QR_PAIR_TIMEOUT_MS = 120_000L
    }
}
