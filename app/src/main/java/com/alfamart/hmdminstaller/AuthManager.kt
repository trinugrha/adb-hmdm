package com.alfamart.hmdminstaller

import android.content.Context
import java.security.MessageDigest

/** PIN & sesi — port dari Web ADB v2.5 (localStorage -> SharedPreferences). */
object AuthManager {
    // sha256("4567") — PIN default sama seperti versi Web. Ganti untuk produksi:
    // python3 -c "import hashlib; print(hashlib.sha256(b'PINBARU').hexdigest())"
    private const val VALID_PASS_HASH = "db2e7f1bd5ab9968ae76199b7cc74795ca7404d5a08d78567715ce532f9d2669"
    const val SESSION_TIMEOUT_MS = 20 * 60 * 1000L

    private const val P = "auth"
    private fun prefs(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    fun checkPin(pin: String): Boolean =
        runCatching { sha256Hex(pin) == VALID_PASS_HASH }.getOrDefault(false)

    fun saveLogin(c: Context) {
        prefs(c).edit().putLong("login_time", System.currentTimeMillis()).apply()
    }

    fun logout(c: Context) {
        prefs(c).edit().remove("login_time").apply()
    }

    fun isLoggedIn(c: Context): Boolean {
        val t = prefs(c).getLong("login_time", 0L)
        if (t == 0L) return false
        return System.currentTimeMillis() - t < SESSION_TIMEOUT_MS
    }

    fun remainingMs(c: Context): Long {
        val t = prefs(c).getLong("login_time", 0L)
        return SESSION_TIMEOUT_MS - (System.currentTimeMillis() - t)
    }

    fun failed(c: Context) = prefs(c).getInt("failed", 0)
    fun lockUntil(c: Context) = prefs(c).getLong("lock_until", 0L)
    fun recordFail(c: Context): Long {
        val f = failed(c) + 1
        var until = 0L
        if (f >= 3) {
            val secs = minOf(60L, 1L shl (f - 2)) // 2,4,8… detik seperti versi web (2**(n-2))
            until = System.currentTimeMillis() + secs * 1000
        }
        prefs(c).edit().putInt("failed", f).putLong("lock_until", until).apply()
        return until
    }

    fun resetFail(c: Context) {
        prefs(c).edit().putInt("failed", 0).putLong("lock_until", 0L).apply()
    }
}
