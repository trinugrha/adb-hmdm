package co.sat.hmdminstaller

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/*
 * Dialog pengaturan (Model Perangkat, Aplikasi dan Versi) yang dipisah dari MainActivity.
 * Dipakai sebagai fungsi ekstensi MainActivity, jadi tetap memakai state dan fungsi Activity yang sama.
 */

/**
 * Dialog Setting Model Perangkat: tambah/hapus model (ro.product.model) untuk POS / PDA / TABLET.
 * Model ini dipakai kartu Informasi Status untuk memilih jenis perangkat otomatis. Disimpan saat menekan Simpan.
 */
internal fun MainActivity.showModelSettings() {
    if (isFinishing) return
    val types = AdbController.DEVICE_MODELS.keys.toList()
    val draft = LinkedHashMap<String, MutableList<String>>()
    for (t in types) draft[t] = ModelStore.models(t).toMutableList()
    var current = if (deviceType in types) deviceType else types.first()

    val ui = SettingsUi(this)

    fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }
    /** Jenis yang sudah memuat model [m] (kecuali [except] pada jenis [exceptType]), atau null. */
    fun dupType(m: String, except: String? = null): String? =
        types.firstOrNull { t -> draft.getValue(t).any { it != except && norm(it) == norm(m) } }

    val scroll = ScrollView(this)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val btnAdd = ui.addButton("Tambah model")
    val btnExportM = ui.outlinedButton("Export", R.drawable.ic_upload_file)
    val btnImportM = ui.outlinedButton("Import", R.drawable.ic_download)

    fun buildList() {
        list.removeAllViews()
        val items = draft.getValue(current)
        list.addView(ui.sectionLabel("DAFTAR MODEL · ${items.size}"), ui.lpTop(24))
        for (m in items.toList()) {
            val del = ui.iconButton(R.drawable.ic_delete, ui.cDanger, "Hapus $m") {
                draft.getValue(current).remove(m)
                buildList()
            }
            list.addView(ui.itemCard(m, current, false, del), ui.lpTop(10))
        }
    }

    fun showAddDialog() {
        val inModel = ui.field("contoh: SM-T225", "", capitalize = true)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.label("Nama model", bold = true, color = ui.cText))
            addView(inModel, ui.lpField())
        }
        showPopup(
            "Tambah Model ke $current", icon = R.drawable.ic_add, content = form, hasInput = true,
            positive = "Tambah", negative = "Batal"
        ) {
            // Isian belum valid: popup tetap terbuka (return false).
            val m = inModel.text?.toString()?.trim().orEmpty()
            val d = dupType(m)
            when {
                norm(m).isEmpty() -> {
                    Toast.makeText(this, "Tambah model gagal: nama model belum diisi. Contoh: SM-T225", Toast.LENGTH_LONG).show()
                    false
                }
                d != null -> {
                    Toast.makeText(this, "Tambah model gagal: $m sudah ada di daftar $d", Toast.LENGTH_LONG).show()
                    false
                }
                else -> {
                    draft.getValue(current).add(m)
                    buildList()
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    true
                }
            }
        }
    }

    // Pilihan jenis perangkat: tombol segmen (POS / TABLET / PDA).
    val segment = ui.typeSegment(types, { current }) { t ->
        current = t
        buildList()
    }
    btnAdd.setOnClickListener { showAddDialog() }
    btnExportM.setOnClickListener {
        // Ekspor daftar yang sedang tampil di dialog (termasuk perubahan yang belum disimpan).
        onExportPicked = { uri ->
            try {
                val json = ModelStore.toJson(types, draft)
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: throw java.io.IOException("tidak bisa menulis file")
                Toast.makeText(this, "Model perangkat diekspor", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                val msg = "Ekspor model perangkat gagal: file tidak bisa ditulis. Pilih lokasi lain, lalu coba lagi"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                log(LogLevel.ERR, msg)
                log(LogLevel.DETAIL, Fmt.cause(e))
            }
        }
        exportVersionsLauncher.launch("model-perangkat.json")
    }
    btnImportM.setOnClickListener {
        onImportPicked = { uri ->
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("tidak bisa membaca file")
                if (bytes.size > 1_000_000) throw IllegalArgumentException("file terlalu besar")
                val parsed = ModelStore.parseJson(String(bytes, Charsets.UTF_8), types)
                for ((t, l) in parsed) draft[t] = l.toMutableList()
                buildList()
                Toast.makeText(this, "Model perangkat diimpor (${parsed.keys.joinToString()}). Ketuk \"Simpan\" untuk menerapkan", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                val reason = e.message?.takeIf {
                    e is IllegalArgumentException || (e is java.io.IOException && it.startsWith("tidak bisa"))
                } ?: "file tidak bisa dibaca"
                val msg = "Impor model perangkat gagal: $reason. Pilih file hasil ekspor model perangkat"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                log(LogLevel.ERR, msg)
                log(LogLevel.DETAIL, Fmt.cause(e))
            }
        }
        importVersionsLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }

    scroll.addView(ui.body(segment.view, btnAdd, btnExportM, btnImportM, list))
    buildList()

    showPopup(
        "Pengaturan Model Perangkat", "Model yang dikenali untuk memilih POS / PDA / TABLET otomatis.",
        icon = R.drawable.ic_settings, content = scroll, ownScroll = true, hasInput = true,
        positive = "Simpan", negative = "Batal"
    ) {
        for (t in types) ModelStore.save(t, draft.getValue(t))
        deviceModel = null
        // Daftar model berubah: hasil cek lama tidak berlaku, Status Instalasi dan Status Izin kembali "Belum diperiksa".
        appsInstalled = null; appsType = null
        hmdmPerms = null; permType = null
        refreshApps()
        refreshPerms()
        Toast.makeText(this, "Pengaturan model perangkat disimpan. Ketuk \"Cek Status\" untuk mengecek ulang", Toast.LENGTH_LONG).show()
        true
    }
}

/**
 * Dialog Setting: ubah daftar aplikasi (tambah/hapus) dan versi yang diharapkan per jenis perangkat
 * (POS / TABLET / PDA). Kolom versi dikosongkan = aplikasi hanya dicek terpasang, versinya tidak dibandingkan.
 * Perubahan ditahan sebagai draf (juga saat pindah jenis) dan baru disimpan saat menekan Simpan.
 */
internal fun MainActivity.showVersionSettings() {
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
    val fields = LinkedHashMap<String, EditText>()

    val ui = SettingsUi(this)

    val scroll = ScrollView(this)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val btnAdd = ui.addButton("Tambah aplikasi")
    val btnExport = ui.outlinedButton("Export", R.drawable.ic_upload_file)
    val btnImport = ui.outlinedButton("Import", R.drawable.ic_download)

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
        list.addView(ui.sectionLabel("DAFTAR APLIKASI · ${pkgs.size}"), ui.lpTop(24))
        for (pkg in pkgs.toList()) {
            // EditText hanya penampung nilai versi (tidak ditampilkan); versi tampil sebagai lencana.
            val et = ui.field("tanpa cek versi", vers[pkg].orEmpty()).apply {
                isFocusable = false
                isCursorVisible = false
            }
            val edit = ui.iconButton(R.drawable.ic_edit, ui.cEdit, "Edit versi $pkg") { onEditVersion(pkg) }
            val del = ui.iconButton(R.drawable.ic_delete, ui.cDanger, "Hapus $pkg") {
                commitFields()
                draftPkgs.getValue(current).remove(pkg)
                draftVers.getValue(current).remove(pkg)
                buildFields()
            }
            val verText = vers[pkg].orEmpty()
            val badge = if (verText.isEmpty()) "tanpa cek versi" else "v$verText"
            list.addView(ui.itemCard(pkg, badge, verText.isEmpty(), edit, del), ui.lpTop(10))
            fields[pkg] = et
        }
    }

    fun showEditVersionDialog(pkg: String) {
        commitFields()
        val inVer = ui.field("kosong = tanpa cek versi", draftVers.getValue(current)[pkg].orEmpty())
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.label(pkg, bold = true, size = 13f, color = ui.cText))
            addView(ui.label("Versi (versionName)", bold = true, color = ui.cText), ui.lpTop(16))
            addView(inVer, ui.lpField())
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
        val inPkg = ui.field("com.contoh.app", "")
        val inVer = ui.field("contoh: 1.0.0", "")
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.label("Nama paket", bold = true, color = ui.cText))
            addView(inPkg, ui.lpField())
            addView(ui.label("Versi (wajib diisi)", bold = true, color = ui.cText), ui.lpTop(16))
            addView(inVer, ui.lpField())
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
    val segment = ui.typeSegment(types, { current }) { t ->
        commitFields()
        current = t
        buildFields()
    }
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
                val msg = "Impor versi aplikasi gagal: $reason. Pilih file hasil ekspor versi aplikasi"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                log(LogLevel.ERR, msg)
                log(LogLevel.DETAIL, Fmt.cause(e))
            }
        }
        importVersionsLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }

    scroll.addView(ui.body(segment.view, btnAdd, btnExport, btnImport, list))
    // Isi kolom SEBELUM dialog tampil. Android memeriksa ada-tidaknya kolom ketik saat dialog dibuka;
    // bila kosong, keyboard diblokir (FLAG_ALT_FOCUSABLE_IM) dan versi tidak bisa diketik.
    buildFields()

    showPopup(
        "Pengaturan Aplikasi dan Versi", "Daftar aplikasi dan versi yang dicek per jenis perangkat.",
        icon = R.drawable.ic_settings, content = scroll, ownScroll = true, hasInput = true,
        positive = "Simpan", negative = "Batal"
    ) {
        commitFields()
        for (t in types) VersionStore.save(t, draftPkgs.getValue(t), draftVers.getValue(t))
        // Hasil cek lama dibuat tidak berlaku karena daftar/versi berubah; status kembali "Belum diperiksa".
        appsInstalled = null
        appsType = null
        // Model perangkat dan Status Izin juga kembali "Belum diperiksa".
        deviceModel = null
        hmdmPerms = null; permType = null
        refreshApps()
        refreshPerms()
        Toast.makeText(this, "Pengaturan aplikasi dan versi disimpan. Ketuk \"Cek Status\" untuk mengecek ulang", Toast.LENGTH_LONG).show()
        true
    }
}
