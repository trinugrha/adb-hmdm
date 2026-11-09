package co.sat.hmdminstaller

import android.content.Context
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Penyimpanan PIN: yang disimpan hanya hash PBKDF2-HMAC-SHA256 + salt acak (bukan PIN asli).
 * Salah PIN 5x berturut-turut → terkunci sementara; makin sering makin lama (30 dtk, 1 mnt, 2 mnt ... maks 15 mnt).
 */
class PinStore(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("pin_store", Context.MODE_PRIVATE)

    val isSet: Boolean get() = sp.contains(K_HASH) && sp.contains(K_SALT)

    fun set(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString(K_SALT, salt.toHex())
            .putString(K_HASH, hash(pin, salt).toHex())
            .putInt(K_FAILS, 0)
            .remove(K_LOCK_UNTIL)
            .apply()
    }

    /** Sisa waktu terkunci (ms); 0 bila tidak sedang terkunci. */
    fun lockRemainingMs(): Long {
        val until = sp.getLong(K_LOCK_UNTIL, 0L)
        if (until == 0L) return 0L
        val now = System.currentTimeMillis()
        // Jam diubah mundur oleh pengguna → batasi sisa lock agar tidak melebihi durasi maksimum.
        return (until - now).coerceIn(0L, MAX_LOCK_MS)
    }

    /** true bila PIN benar. Selama masih terkunci selalu false (tanpa menghitung percobaan). */
    fun verify(pin: String): Boolean {
        if (lockRemainingMs() > 0) return false
        val saltHex = sp.getString(K_SALT, null) ?: return false
        val hashHex = sp.getString(K_HASH, null) ?: return false
        val ok = MessageDigest.isEqual(hash(pin, saltHex.fromHex()), hashHex.fromHex())
        if (ok) {
            sp.edit().putInt(K_FAILS, 0).remove(K_LOCK_UNTIL).apply()
        } else {
            val fails = sp.getInt(K_FAILS, 0) + 1
            val e = sp.edit().putInt(K_FAILS, fails)
            if (fails % MAX_TRIES == 0) {
                val step = fails / MAX_TRIES                      // 1,2,3...
                val ms = (BASE_LOCK_MS shl (step - 1).coerceAtMost(10)).coerceAtMost(MAX_LOCK_MS)
                e.putLong(K_LOCK_UNTIL, System.currentTimeMillis() + ms)
            }
            e.apply()
        }
        return ok
    }

    /** Sisa percobaan sebelum terkunci. */
    fun triesLeft(): Int = MAX_TRIES - (sp.getInt(K_FAILS, 0) % MAX_TRIES)

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    companion object {
        const val PIN_LENGTH = 4
        /** PIN awal saat aplikasi pertama kali dibuka (bisa diganti lewat menu Ubah PIN). */
        const val DEFAULT_PIN = "4321"
        private const val MAX_TRIES = 5
        private const val BASE_LOCK_MS = 30_000L
        private const val MAX_LOCK_MS = 15 * 60_000L
        private const val ITERATIONS = 120_000
        private const val K_HASH = "hash"
        private const val K_SALT = "salt"
        private const val K_FAILS = "fails"
        private const val K_LOCK_UNTIL = "lock_until"

        /** Hanya menolak angka yang sama semua (mis. 0000). Urutan seperti 4321 diperbolehkan. */
        fun isWeak(pin: String): Boolean = pin.all { it == pin[0] }
    }
}
