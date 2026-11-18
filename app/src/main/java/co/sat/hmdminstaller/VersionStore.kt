package co.sat.hmdminstaller

import android.content.Context
import android.content.SharedPreferences

/**
 * Daftar aplikasi dan versi yang diharapkan per jenis perangkat, bisa diubah lewat tombol Setting
 * tanpa build ulang.
 *
 * Nilai bawaan ada di [AdbController.DEVICE_PACKAGES] dan [AdbController.EXPECTED_VERSIONS].
 * - Daftar aplikasi: bila pengguna mengubah (tambah/hapus), seluruh daftar jenis itu disimpan sebagai override.
 * - Versi: override per (jenis, paket). Override kosong ("") berarti paket itu hanya dicek terpasang.
 * Tanpa override, nilai bawaan dipakai.
 */
object VersionStore {

    @Volatile private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) sp = context.applicationContext.getSharedPreferences("version_store", Context.MODE_PRIVATE)
    }

    private fun vKey(type: String, pkg: String) = "v|$type|$pkg"
    private fun pKey(type: String) = "p|$type"

    /** Daftar aplikasi bawaan [type]. */
    fun defaultPackages(type: String): List<String> = AdbController.DEVICE_PACKAGES[type] ?: emptyList()

    /** Versi bawaan [type] (hanya paket yang punya versi). */
    fun defaultVersions(type: String): Map<String, String> = AdbController.EXPECTED_VERSIONS[type] ?: emptyMap()

    /** Daftar aplikasi yang berlaku untuk [type]. */
    fun packages(type: String): List<String> {
        val raw = sp?.getString(pKey(type), null) ?: return defaultPackages(type)
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Versi yang berlaku untuk [type]: paket tanpa versi (kosong) tidak ikut dalam hasil. */
    fun expected(type: String): Map<String, String> {
        val prefs = sp
        val def = defaultVersions(type)
        val res = LinkedHashMap<String, String>()
        for (pkg in packages(type)) {
            val k = vKey(type, pkg)
            val v = if (prefs != null && prefs.contains(k)) prefs.getString(k, "") ?: "" else def[pkg] ?: ""
            if (v.isNotBlank()) res[pkg] = v.trim()
        }
        return res
    }

    /**
     * Simpan daftar aplikasi dan versi untuk [type]. Hanya yang berbeda dari bawaan yang disimpan
     * sebagai override; yang sama dengan bawaan dihapus dari penyimpanan.
     */
    fun save(type: String, pkgs: List<String>, versions: Map<String, String>) {
        val prefs = sp ?: return
        val list = pkgs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val defPkgs = defaultPackages(type)
        val defVers = defaultVersions(type)
        val ed = prefs.edit()
        // Bersihkan override versi lama paket yang sudah tidak ada di daftar (bawaan maupun tambahan).
        for (p in (defPkgs + packages(type)).distinct()) ed.remove(vKey(type, p))
        if (list == defPkgs) ed.remove(pKey(type)) else ed.putString(pKey(type), list.joinToString("\n"))
        for (pkg in list) {
            val v = (versions[pkg] ?: "").trim()
            if (v != (defVers[pkg] ?: "")) ed.putString(vKey(type, pkg), v)
        }
        ed.apply()
    }
}
