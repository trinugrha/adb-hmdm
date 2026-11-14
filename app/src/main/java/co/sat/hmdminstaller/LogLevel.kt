package co.sat.hmdminstaller

/**
 * Tingkat/jenis satu entri log. Warna dan penanda ditentukan dari sini,
 * bukan ditebak dari isi teks, sehingga tampilan log konsisten.
 *
 * Contoh tampilan:
 * ```
 * ── Install APK · 14:02:11
 * [1/2] hmdm-launcher.apk (12.3 MB)
 * > adb install -r hmdm-launcher.apk
 * ✓ Terpasang · 4.2 dtk
 * [2/2] agent.apk (3.1 MB)
 * > adb install -r agent.apk
 * ✗ Gagal: versi lebih rendah dari yang terpasang (INSTALL_FAILED_VERSION_DOWNGRADE)
 * Ringkasan: 1 berhasil, 1 gagal dari 2 APK
 * ✗ agent.apk: versi lebih rendah dari yang terpasang
 * ✗ Gagal: Install APK · 6.0 dtk
 * ```
 *
 * Aturan penulisan pesan:
 * - Tanpa titik di akhir (dibuang otomatis oleh `MainActivity.log`, kecuali baris DETAIL).
 * - HP yang sedang dipasang disebut "HP target"; "device owner" hanya untuk istilah Android.
 * - Ukuran dan durasi lewat [Fmt] (titik desimal tetap, mis. 12.3 MB, 4.2 dtk).
 * - Percobaan ulang: "Percobaan 1/2 gagal (alasan), mencoba lagi..."; satuan waktu selalu "dtk".
 *   Alasan di sini singkat (tanpa saran perbaikan); saran lengkap hanya di baris ✗ akhir.
 * - Baris ✗ akhir berbentuk "<Aksi> gagal: <alasan singkat>. <Saran diawali huruf besar>" (satu bentuk untuk connect, sambung ulang, install,
 *   pairing, buka USB, salin APK, baca status, alamat tidak valid, dst; jangan menulis alasan tanpa awalan aksi). Awalan "Gagal ..." hanya dipakai di install, karena aksinya sudah jelas dari baris [n/m] di atasnya.
 *   Saran tidak ditaruh di dalam kurung dan tidak diawali huruf kecil.
 * - Status "belum ada / belum diset" memakai WARN (sama dengan warna kotak status di layar), bukan OK.
 * - Sambung ulang otomatis (saat install) tidak mencetak "> adb connect" per percobaan, cukup hasil akhirnya.
 * - Istilah Android ditulis huruf kecil di tengah kalimat: "device owner", "profile owner".
 * - Rantai exception teknis (mis. "IOException: ... <- ...") hanya di baris DETAIL; baris ✗ memuat alasan singkat + saran.
 * - DETAIL hanya untuk output mentah yang menambah informasi (bukan pengulangan alasan di baris ✗).
 *   Baris DETAIL yang terlalu panjang dipotong otomatis dan diakhiri "…" supaya jelas bahwa teksnya tidak utuh.
 * - Penyebab teknis (alasan putus koneksi, galat tak terduga, jejak crash) selalu di baris DETAIL di bawah baris ✗ / !, tidak digabung ke dalamnya.
 * - Saran per kode install (mis. INSTALL_FAILED_UPDATE_INCOMPATIBLE) ditulis sebagai kalimat sendiri setelah alasan, tidak di dalam kurung.
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
