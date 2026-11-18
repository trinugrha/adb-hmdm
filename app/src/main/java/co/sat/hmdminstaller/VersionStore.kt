package co.sat.hmdminstaller

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

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

    private val PKG_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Ubah daftar aplikasi dan versi semua jenis menjadi teks JSON untuk diekspor. */
    fun toJson(types: List<String>, pkgs: Map<String, List<String>>, vers: Map<String, Map<String, String>>): String {
        val root = JSONObject()
        root.put("format", "hmdm-installer-versions")
        root.put("version", 1)
        val tObj = JSONObject()
        for (t in types) {
            val list = (pkgs[t] ?: emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val vObj = JSONObject()
            for (pkg in list) {
                val v = (vers[t]?.get(pkg) ?: "").trim()
                if (v.isNotEmpty()) vObj.put(pkg, v)
            }
            tObj.put(t, JSONObject().put("packages", JSONArray(list)).put("versions", vObj))
        }
        root.put("types", tObj)
        return root.toString(2)
    }

    /**
     * Baca teks JSON hasil [toJson]. Hanya jenis perangkat di [types] yang diambil; jenis lain diabaikan.
     * Hasil: jenis -> (daftar paket, versi). Melempar [IllegalArgumentException] bila format tidak sesuai.
     */
    fun parseJson(text: String, types: List<String>): Map<String, Pair<List<String>, Map<String, String>>> {
        val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("bukan file JSON yang valid") }
        val tObj = root.optJSONObject("types") ?: throw IllegalArgumentException("data \"types\" tidak ditemukan")
        val res = LinkedHashMap<String, Pair<List<String>, Map<String, String>>>()
        for (t in types) {
            val o = tObj.optJSONObject(t) ?: continue
            val arr = o.optJSONArray("packages") ?: throw IllegalArgumentException("daftar paket $t tidak ada")
            val list = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i, "").trim()
                if (!PKG_REGEX.matches(pkg)) throw IllegalArgumentException("nama paket tidak valid di $t: $pkg")
                if (pkg !in list) list.add(pkg)
            }
            val vObj = o.optJSONObject("versions")
            val vers = LinkedHashMap<String, String>()
            if (vObj != null) {
                for (pkg in list) {
                    val v = vObj.optString(pkg, "").trim()
                    if (v.isNotEmpty()) vers[pkg] = v
                }
            }
            res[t] = list to vers
        }
        if (res.isEmpty()) throw IllegalArgumentException("tidak ada jenis perangkat yang dikenal")
        return res
    }
}
