package com.alfamart.hmdmadb.adb

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** RSA 2048 untuk AUTH ADB. Kunci privat disimpan di SharedPreferences. */
object AdbCrypto {
    private const val PREF = "adb_keys"
    private const val K_PRIV = "rsa_priv"
    private const val K_PUB = "rsa_pub"

    data class Keys(val private: java.security.PrivateKey, val public: java.security.PublicKey)

    fun getOrCreate(ctx: Context): Keys {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val privB64 = p.getString(K_PRIV, null)
        val pubB64 = p.getString(K_PUB, null)
        if (privB64 != null && pubB64 != null) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.DEFAULT)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.DEFAULT)))
                return Keys(priv, pub)
            } catch (_: Exception) { /* regenerate */ }
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        p.edit()
            .putString(K_PRIV, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString(K_PUB, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        return Keys(kp.private, kp.public)
    }

    /** Tanda tangani token AUTH ADB (SHA1withRSA, sesuai implementasi adb desktop). */
    fun sign(priv: java.security.PrivateKey, token: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA1withRSA")
        s.initSign(priv)
        s.update(token)
        return s.sign()
    }

    /** Payload ADB_AUTH_RSAPUBLICKEY: "guid\0 rsa-ssh pubkey\0" (format yang dipakai adb). */
    fun publicKeyPayload(ctx: Context): ByteArray {
        val keys = getOrCreate(ctx)
        val raw = keys.public.encoded
        val b64 = Base64.encodeToString(raw, Base64.NO_WRAP)
        return ("android-host\x00ssh-rsa $b64 android-host@hmdm\x00".toByteArray(Charsets.UTF_8))
    }
}
