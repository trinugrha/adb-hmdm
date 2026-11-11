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
 *     Failure [INSTALL_FAILED_VERSION_DOWNGRADE]
 * Ringkasan: 1 berhasil, 1 gagal dari 2 APK
 * ✗ agent.apk: versi lebih rendah dari yang terpasang
 * ✗ Gagal: Install APK · 6.0 dtk
 * ```
 *
 * Aturan penulisan pesan:
 * - Tanpa titik di akhir (dibuang otomatis oleh `MainActivity.log`, kecuali baris DETAIL).
 * - HP yang sedang dipasang disebut "HP target"; "device owner" hanya untuk istilah Android.
 * - Ukuran dan durasi lewat [Fmt] (titik desimal tetap, mis. 12.3 MB, 4.2 dtk).
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
