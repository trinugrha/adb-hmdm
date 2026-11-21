package co.sat.hmdminstaller

/**
 * Tingkat/jenis satu entri log. Warna dan penanda ditentukan dari sini,
 * bukan ditebak dari isi teks, sehingga tampilan log konsisten.
 *
 * Contoh tampilan:
 * ```
 * ── Pasang Aplikasi · 14:02:11
 * [1/2] hmdm-launcher.apk (12.3 MB)
 * > adb install -r hmdm-launcher.apk
 * ✓ Terpasang · 4.2 dtk
 * [2/2] agent.apk (3.1 MB)
 * > adb install -r agent.apk
 * ✗ Gagal: versi lebih rendah dari yang terpasang (INSTALL_FAILED_VERSION_DOWNGRADE)
 * Ringkasan: 1 berhasil, 1 gagal dari 2 APK
 * ✗ agent.apk: versi lebih rendah dari yang terpasang
 * ✗ Gagal: Pasang Aplikasi · 6.0 dtk
 * ```
 *
 * Aturan penulisan pesan:
 * - Tanpa titik di akhir (dibuang otomatis oleh `MainActivity.log`, kecuali baris DETAIL).
 * - HP yang sedang dipasang disebut "HP target". Fitur yang dijadikan HMDM disebut "MDM Mode", sama dengan tombol
 *   dan kartu di layar; "device owner" hanya untuk istilah teknis Android (alasan penolakan sistem, perintah dpm).
 * - Ukuran dan durasi lewat [Fmt] (titik desimal tetap, mis. 12.3 MB, 4.2 dtk).
 * - Percobaan ulang: "Percobaan 1/2 gagal (alasan), mencoba lagi..."; satuan waktu selalu "dtk".
 *   Alasan di sini singkat (tanpa saran perbaikan); saran lengkap hanya di baris ✗ akhir.
 * - Baris ✗ akhir berbentuk "<Aksi> gagal: <alasan singkat>. <Saran diawali huruf besar>" (satu bentuk untuk connect, sambung ulang, install,
 *   pairing, buka USB, salin APK, baca status, alamat tidak valid, dst; jangan menulis alasan tanpa awalan aksi). Awalan "Gagal ..." hanya dipakai di install, karena aksinya sudah jelas dari baris [n/m] di atasnya.
 *   Saran tidak ditaruh di dalam kurung dan tidak diawali huruf kecil.
 * - Status "belum ada / belum diset" memakai WARN (sama dengan warna kotak status di layar), bukan OK.
 * - Sambung ulang otomatis (saat install) tidak mencetak "> adb connect" per percobaan, cukup hasil akhirnya.
 * - Koneksi USB selalu disebut dengan label "USB · <nama perangkat>", tidak pernah dengan alamat loopback internal (127.0.0.1:<port>).
 * - Judul proses (HEAD/END) memakai nama yang sama dengan tombol atau kartu di layar.
 * - Istilah Android ditulis huruf kecil di tengah kalimat: "device owner", "profile owner".
 * - Rantai exception teknis (mis. "IOException: ... <- ...") dan nama kelas exception hanya di baris DETAIL; baris ✗ memuat alasan singkat + saran.
 *   Jejak crash dari sesi sebelumnya juga baris DETAIL (maks. 14 baris, sisanya diringkas "… N baris lain tidak ditampilkan").
 * - DETAIL hanya untuk output mentah yang menambah informasi (bukan pengulangan alasan di baris ✗).
 *   Baris DETAIL yang terlalu panjang dipotong otomatis dan diakhiri "…" supaya jelas bahwa teksnya tidak utuh.
 * - Penyebab teknis (alasan putus koneksi, galat tak terduga, jejak crash) selalu di baris DETAIL di bawah baris ✗ / !, tidak digabung ke dalamnya.
 * - Saran per kode install (mis. INSTALL_FAILED_UPDATE_INCOMPATIBLE) ditulis sebagai kalimat sendiri setelah alasan, tidak di dalam kurung.
 * - Petunjuk langkah berikutnya setelah proses berhasil (bukan output mentah) memakai STEP diawali "Berikutnya:", bukan DETAIL.
 * - Pemisah "·" hanya untuk durasi, jam, dan label perangkat (mis. "Terpasang · 4.2 dtk", "USB · Pixel 6"); antar klausa biasa pakai koma.
 * - Gagal connect yang dicetak pemanggil (USB, sambung ulang) tetap diikuti baris DETAIL penyebab teknis, sama seperti connect biasa.
 * - Pesan Toast memakai bentuk yang sama ("<Aksi> gagal: <alasan singkat>. <Saran>"). Teks exception mentah (`e.message`) tidak pernah
 *   masuk ke baris ✗ atau Toast; ia hanya ditulis sebagai baris DETAIL lewat [Fmt.cause].
 * - Popup gagal memuat baris ✗ proses itu sebagai butir "Penyebab" tanpa awalan "<Aksi> gagal: " (judul popup sudah memuat aksinya),
 *   lewat `MainActivity.errReason`.
 * - Teks status di layar (header, progres) memakai "·" hanya untuk label dan durasi; petunjuk dan angka dipisah koma atau "dari".
 * - Kutipan nama tombol, dialog, atau perintah memakai tanda kutip ganda ("..."), di log, popup, maupun Toast; tidak pakai kutip tunggal.
 *   Petunjuk ke tombol memakai kata "ketuk" (bukan "klik") dan nama tombol persis seperti tulisan di layar, mis. ketuk "Cek Status".
 * - Petunjuk ke tombol memakai label yang benar-benar ada di layar: koneksi kabel ditunjuk dengan "Pilih Perangkat" di tab "USB"
 *   (bukan "Connect USB", yang hanya nama proses di log dan judul popup). Satuan waktu di popup juga "dtk", bukan "detik".
 * - Jalur menu Android ditulis dengan "›" (Pengaturan › Keamanan), bukan ">".
 * - Penyebab putus koneksi (`AdbController.lastDropReason`) ditulis dalam bahasa sehari-hari karena tampil di popup; nama exception
 *   dan jawaban mentah ada di `lastDropDetail`, hanya untuk baris DETAIL.
 * - Judul popup memakai huruf kapital di tiap kata ("Pilih Perangkat USB", "Keluar dari Aplikasi?"), sama dengan judul proses dan nama tombol.
 *   Istilah "izin" dipakai untuk permission Android di log, popup, dan Toast; "permission" hanya untuk nama paket atau perintah teknis.
 * - Pesan exception di baris DETAIL ditulis berbahasa Indonesia tanpa panah ("USB ke soket", bukan "USB->soket") dan tanpa kata "Timeout" ("Waktu habis ...").
 * - Popup hasil: ikon dan warna ditentukan parameter `ok` di `showConnProblem`, bukan dari teks judul.
 *   Judul popup hasil juga berhuruf kapital di tiap kata: "<Proses> Gagal" / "<Proses> Berhasil" (mis. "Connect USB Gagal", "Koneksi Terputus").
 * - Mode TCP/IP disebut "mode nirkabel (TCP/IP)" di log dan popup, sama dengan kartu di layar; tanda "->" tidak dipakai di pesan log.
 * - HP yang menjalankan aplikasi ini disebut "HP ini" (bukan "HP teknisi" atau "perangkat ini"), pasangan dari "HP target".
 * - Urutan koneksi nirkabel ditulis "Pair" lalu "Connect" (bukan "dan"). Petunjuk tombol di teks status juga memakai label layar,
 *   mis. "Pilih Perangkat" di tab USB, bukan nama proses ("Connect USB", "Pilih Perangkat USB").
 * - Butir "Penyebab" di popup selalu diawali huruf kapital, juga untuk penyebab putus koneksi (`lastDropReason`, yang di log ditulis huruf kecil).
 * - Contoh isian di Toast ditulis sebagai kalimat sendiri: "Isi nama model. Contoh: SM-T225".
 * - Judul proses cek status memakai nama tombol "Cek Status" (bukan "Cek Informasi Status").
 * - Klausa berurutan dipisah koma sebelum "lalu" ("Colok ulang kabel, lalu ketuk ..."); urutan dua nama tombol tetap tanpa koma ("Pair" lalu "Connect").
 * - Petunjuk ke HP ini ditulis "layar HP ini", bukan "layar aplikasi ini".
 * - Popup gagal tanpa baris ✗ memakai baris ! proses itu (maks. 2, kecuali pesan "mencoba lagi") sebagai butir "Penyebab".
 *   Butir "Penyebab" selalu diawali huruf kapital, tanpa titik di akhir. Satuan detik di pesan batas waktu selalu bilangan bulat ("20 dtk").
 * - Popup berbahasa sehari-hari: kode teknis install ("(INSTALL_FAILED_...)") hanya di log; popup membuangnya lewat `MainActivity.plainReason`.
 * - Exception dari perintah shell masuk ke output mentah lewat `AdbController.shellError` ("Error: <Kelas>: <pesan>"), selalu sebagai baris DETAIL.
 * - Toast simpan pengaturan: "Pengaturan <nama> disimpan. Ketuk \"Cek Status\" untuk mengecek ulang"; nama sama dengan judul popup pengaturannya.
 * - Progres untuk satu APK tidak memakai "1 dari 1" (\"Memasang APK: nama.apk\").
 * - Label kartu status di layar berbahasa Indonesia ("STATUS MDM MODE", "STATUS INSTALASI", "STATUS IZIN"); judul dialog pengaturan
 *   memakai "dan", bukan "&" ("Pengaturan Aplikasi dan Versi"), sama dengan Toast simpan.
 * - Daftar "Periksa" yang sama dipakai satu sumber teks (mis. `QR_PAIR_CHECKLIST`), supaya popup tidak berbeda kalimat untuk kegagalan yang sama.
 * - Setiap proses: HEAD → isi → END_OK / END_ERR, lalu satu baris kosong.
 */
enum class LogLevel(val marker: String, val bold: Boolean = false) {
    /** Pembuka satu proses. */
    HEAD("── ", bold = true),
    /** Langkah / item yang sedang dikerjakan, mis. "[1/3] nama.apk". */
    STEP(""),
    /** Perintah ADB yang dijalankan. */
    CMD("> "),
    /** Berhasil. */
    OK("✓ "),
    /** Peringatan atau input yang belum lengkap; proses tidak berhenti karenanya. */
    WARN("! "),
    /** Gagal. */
    ERR("✗ "),
    /** Output mentah dari device, hanya untuk diagnosis. */
    DETAIL("    "),
    /** Ringkasan hasil. */
    SUMMARY("", bold = true),
    /** Penutup proses: berhasil. */
    END_OK("✓ ", bold = true),
    /** Penutup proses: gagal / berhenti. */
    END_ERR("✗ ", bold = true)
}
