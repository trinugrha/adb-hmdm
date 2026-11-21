package co.sat.hmdminstaller

import android.os.SystemClock

/**
 * Hitung mundur sesi sebelum aplikasi terkunci otomatis. Hitungan dimulai saat login dan TIDAK direset oleh sentuhan
 * layar. Saat operasi (install dll) berjalan, hitungan ditahan (pause) di sisa waktu terakhir. Dipakai dari UI thread.
 */
internal class SessionTimer(private val timeoutMs: Long) {

    private var startedAt = SystemClock.elapsedRealtime()

    /** Waktu detak terakhir; dipakai untuk menahan hitungan saat operasi berjalan. */
    private var tickAt = startedAt

    /** Sisa waktu sesi (ms); nol atau negatif berarti sudah waktunya mengunci. */
    fun remainingMs(): Long = timeoutMs - (SystemClock.elapsedRealtime() - startedAt)

    /**
     * Satu detak (tiap detik dari pemantau). Bila [busy], titik awal digeser sebesar waktu yang berlalu sehingga sisa
     * waktu tetap sama, lalu lanjut menghitung setelah operasi selesai.
     */
    fun tick(busy: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (busy) startedAt += now - tickAt
        tickAt = now
    }

    /** Lewati jeda tanpa detak (mis. aplikasi di latar belakang) agar tidak dihitung sebagai waktu sibuk. */
    fun skipGap() {
        tickAt = SystemClock.elapsedRealtime()
    }

    /** Mulai sesi baru, mis. setelah PIN benar. */
    fun restart() {
        startedAt = SystemClock.elapsedRealtime()
        tickAt = startedAt
    }

    companion object {
        /** Sisa waktu <= ini (detik) ditampilkan sebagai peringatan. */
        const val WARN_SECONDS = 60

        /** Pembulatan ke atas ke detik penuh. */
        fun seconds(leftMs: Long): Long = (leftMs + 999) / 1000

        /** "Sesi 29:59" atau "Sesi 1:02:03". */
        fun label(sec: Long): String =
            if (sec >= 3600) "Sesi %d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
            else "Sesi %02d:%02d".format(sec / 60, sec % 60)
    }
}
