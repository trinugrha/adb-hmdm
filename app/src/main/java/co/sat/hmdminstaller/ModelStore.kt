package co.sat.hmdminstaller

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Daftar model perangkat per jenis (POS / PDA / TABLET) untuk deteksi otomatis di kartu Informasi Status.
 * Bawaan ada di [AdbController.DEVICE_MODELS]; perubahan pengguna (tambah/hapus) disimpan sebagai override per jenis.
 */
object ModelStore {
    @Volatile private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) sp = context.applicationContext.getSharedPreferences("model_store", Context.MODE_PRIVATE)
    }

    private fun key(type: String) = "m|$type"

    fun defaults(type: String): List<String> = AdbController.DEVICE_MODELS[type] ?: emptyList()

    /** Daftar model yang berlaku untuk [type]. */
    fun models(type: String): List<String> {
        val raw = sp?.getString(key(type), null) ?: return defaults(type)
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Simpan daftar model [type]; bila sama dengan bawaan, override dihapus. */
    fun save(type: String, list: List<String>) {
        val prefs = sp ?: return
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean == defaults(type)) prefs.edit().remove(key(type)).apply()
        else prefs.edit().putString(key(type), clean.joinToString("\n")).apply()
    }

    /** Ubah daftar model semua jenis menjadi teks JSON untuk diekspor. */
    fun toJson(types: List<String>, models: Map<String, List<String>>): String {
        val root = JSONObject()
        root.put("format", "hmdm-installer-models")
        root.put("version", 1)
        val tObj = JSONObject()
        for (t in types) {
            val list = (models[t] ?: emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            tObj.put(t, JSONArray(list))
        }
        root.put("types", tObj)
        return root.toString(2)
    }

    /**
     * Baca teks JSON hasil [toJson]. Hanya jenis di [types] yang diambil. Melempar [IllegalArgumentException]
     * bila format tidak sesuai atau satu model muncul di dua jenis.
     */
    fun parseJson(text: String, types: List<String>): Map<String, List<String>> {
        val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("bukan file JSON yang valid") }
        val tObj = root.optJSONObject("types") ?: throw IllegalArgumentException("data \"types\" tidak ditemukan")
        val res = LinkedHashMap<String, List<String>>()
        val seen = HashMap<String, String>()
        for (t in types) {
            val arr = tObj.optJSONArray(t) ?: continue
            val list = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val m = arr.optString(i, "").trim()
                val n = m.uppercase().filter { it.isLetterOrDigit() }
                if (n.isEmpty() || m.length > 40) throw IllegalArgumentException("nama model tidak valid di $t: $m")
                val other = seen[n]
                if (other != null && other != t) throw IllegalArgumentException("model $m ada di $other dan $t")
                seen[n] = t
                if (m !in list) list.add(m)
            }
            res[t] = list
        }
        if (res.isEmpty()) throw IllegalArgumentException("tidak ada jenis perangkat yang dikenal")
        return res
    }
}
