package com.shadow.society.app

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import org.bouncycastle.crypto.generators.SCrypt
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM + scrypt + helpers. */
object Crypto {
    private val rng = SecureRandom()
    private val AAD = "ShSo|v1".toByteArray()
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    fun random(n: Int): ByteArray { val b = ByteArray(n); rng.nextBytes(b); return b }

    fun randomKey(len: Int): String {
        val sb = StringBuilder(len)
        repeat(len) { sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    fun kdf(secret: String, salt: ByteArray, logN: Int): ByteArray {
        val p = secret.toByteArray()
        try { return SCrypt.generate(p, salt, 1 shl logN, 8, 1, 32) } finally { p.fill(0) }
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun hmac(key: ByteArray, msg: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(msg)
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun constEq(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(AAD)
        return iv + c.doFinal(plain)
    }

    /** Throws if the key is wrong or data was altered. */
    @Throws(GeneralSecurityException::class)
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < 28) throw GeneralSecurityException("short")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        c.updateAAD(AAD)
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/** Ways to combine My Key + Friend Key + Verbal code. Both people must pick the SAME one. */
enum class MixMethod(val fa: String, val en: String, val desc: String) {
    CONCAT("الحاق ساده", "Simple concat", "K1 + K2 + V"),
    FORMULA("فرمول دلخواه", "Custom formula", "K1,K2,V  ~ reverse  - drop 1st char"),
    INTERLEAVE("درهم‌آمیزی", "Interleave", "K1/K2 char by char + V"),
    XOR_HASH("XOR هش‌ها", "XOR of hashes", "hex(SHA256(K1) xor SHA256(K2)) + V"),
    HMAC_CHAIN("زنجیره HMAC", "HMAC chain", "HMAC(HMAC(V,K1),K2)"),
    SANDWICH("ساندویچی", "Sandwich", "K2 half + V + K1 + K2 half");

    fun label(): String = tr(en, fa)
}

object KeyMixer {
    const val DEFAULT_FORMULA = "K1,K2,V"

    fun normalizeVerbal(v: String): String = v.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    /** K1/K2 are the two long keys in sorted order, so both phones compute the same thing. */
    fun mix(method: MixMethod, myKey: String, friendKey: String, verbal: String, formula: String): String {
        val a = myKey.trim()
        val raw = friendKey.trim()
        val b = Files.parseKey(raw) ?: raw          // accepts a pasted key, JSON or "SHSOKEY1|..." line
        val v = normalizeVerbal(verbal)
        require(b.isNotEmpty() && v.isNotEmpty()) { "empty" }
        val k1 = if (a <= b) a else b
        val k2 = if (a <= b) b else a
        return when (method) {
            MixMethod.CONCAT -> k1 + k2 + v
            MixMethod.FORMULA -> applyFormula(k1, k2, v, formula)
            MixMethod.INTERLEAVE -> interleave(k1, k2) + v
            MixMethod.XOR_HASH -> {
                val x = Crypto.sha256(k1.toByteArray())
                val y = Crypto.sha256(k2.toByteArray())
                Crypto.hex(ByteArray(32) { (x[it].toInt() xor y[it].toInt()).toByte() }) + v
            }
            MixMethod.HMAC_CHAIN ->
                Crypto.hex(Crypto.hmac(Crypto.hmac(v.toByteArray(), k1.toByteArray()), k2.toByteArray()))
            MixMethod.SANDWICH -> k2.take(k2.length / 2) + v + k1 + k2.drop(k2.length / 2)
        }
    }

    private fun interleave(a: String, b: String): String {
        val sb = StringBuilder()
        for (i in 0 until maxOf(a.length, b.length)) {
            if (i < a.length) sb.append(a[i])
            if (i < b.length) sb.append(b[i])
        }
        return sb.toString()
    }

    private fun applyFormula(k1: String, k2: String, v: String, f: String): String {
        var u1 = false
        var u2 = false
        var uv = false
        val out = StringBuilder()
        for (raw in f.split(",")) {
            var t = raw.trim()
            var rev = false
            var drop = 0
            while (t.isNotEmpty() && (t[0] == '~' || t[0] == '-')) {
                if (t[0] == '~') rev = !rev else drop++
                t = t.substring(1)
            }
            var s: String
            when (t.uppercase(Locale.ROOT)) {
                "K1" -> { s = k1; u1 = true }
                "K2" -> { s = k2; u2 = true }
                "V" -> { s = v; uv = true }
                else -> throw IllegalArgumentException("bad token: $raw")
            }
            s = s.drop(drop)
            if (rev) s = s.reversed()
            out.append(s)
        }
        require(u1 && u2 && uv) { "formula must use K1, K2, V" }
        return out.toString()
    }

    fun deriveKey(mixed: String): ByteArray = Crypto.kdf(mixed, "ShSo|session|v1".toByteArray(), 14)

    /** Read this aloud with your friend: same code = same key. */
    fun fingerprint(key: ByteArray): String {
        val h = Crypto.hex(Crypto.sha256(key)).substring(0, 8).uppercase(Locale.ROOT)
        return h.substring(0, 4) + " " + h.substring(4)
    }
}

/** RAM-only keys. Nothing here touches the disk. */
object SessionVault {
    private class Entry(val key: ByteArray, val deadline: Long)

    private var k: String? = null
    private val keys = HashMap<Long, Entry>()

    @Synchronized fun unlock(key: String) { k = key }
    @Synchronized fun isUnlocked(): Boolean = k != null
    @Synchronized fun dbKey(): String = k ?: throw IllegalStateException("locked")

    @Synchronized fun putKey(c: Context, id: Long, key: ByteArray) {
        keys[id]?.key?.fill(0)
        val h = Pulse.hours(c)
        keys[id] = Entry(key, if (h > 0) SystemClock.elapsedRealtime() + h * 3600000L else Long.MAX_VALUE)
    }

    @Synchronized fun key(id: Long): ByteArray? {
        val e = keys[id] ?: return null
        if (SystemClock.elapsedRealtime() > e.deadline) { e.key.fill(0); keys.remove(id); return null }
        return e.key
    }

    @Synchronized fun checkPulse() {
        val now = SystemClock.elapsedRealtime()
        val it = keys.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (now > e.deadline) { e.key.fill(0); it.remove() }
        }
    }

    @Synchronized fun wipeKeys() { keys.values.forEach { it.key.fill(0) }; keys.clear() }

    /** Locks the database only. Session keys stay in RAM (see KeyPolicy). */
    @Synchronized fun lock() { k = null; Db.close() }

    /** Locks the database AND destroys every session key. */
    @Synchronized fun lockAll() { wipeKeys(); lock() }
}

/** Dragon's Pulse: session keys self-destruct after N hours. */
object Pulse {
    val HOURS = intArrayOf(0, 1, 4, 8, 12, 24)
    fun hours(c: Context): Int = Prefs.int(c, "pulse_hours", 8)
    fun set(c: Context, h: Int) { Prefs.putInt(c, "pulse_hours", h) }
    fun label(h: Int): String = if (h == 0) tr("Off", "خاموش") else "$h h"
}

/** When are session keys cleared after you leave the app? 0 = at once, 10 / 30 minutes, -1 = never. */
object KeyPolicy {
    val OPTIONS = intArrayOf(0, 10, 30, -1)
    fun minutes(c: Context): Int = Prefs.int(c, "key_clear", 10)
    fun set(c: Context, m: Int) { Prefs.putInt(c, "key_clear", m) }
    fun label(m: Int): String = when (m) {
        0 -> tr("when I leave the app", "هنگام خروج از برنامه")
        -1 -> tr("never (until the app is closed)", "هرگز پاک نشود")
        else -> "$m " + tr("minutes", "دقیقه")
    }
}

/** A random 256-bit secret locked inside the phone's Android Keystore. It never leaves the device. */
object DevKey {
    private const val ALIAS = "shso_master_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val ex = ks.getKey(ALIAS, null)
        if (ex is SecretKey) return ex
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return kg.generateKey()
    }

    @Synchronized fun get(c: Context): ByteArray {
        val blob = Prefs.str(c, "dev_blob")
        if (blob != null) {
            val b = Base64.decode(blob, Base64.NO_WRAP)
            val ci = Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            return ci.doFinal(b, 12, b.size - 12)
        }
        val raw = Crypto.random(32)
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.ENCRYPT_MODE, key())
        val ct = ci.doFinal(raw)
        Prefs.putStr(c, "dev_blob", Base64.encodeToString(ci.iv + ct, Base64.NO_WRAP))
        return raw
    }

    fun destroy() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry(ALIAS)
        } catch (e: Exception) { }
    }
}

/**
 * Vault = the encrypted database.
 *   database key = SHA-256( scrypt(your secret) + device key )
 * The secret is the password / PIN / pattern. For "phone lock" type the secret is empty.
 * My Key lives inside the encrypted database.
 */
object Vault {
    const val LOGN = 14

    fun exists(c: Context): Boolean = Prefs.str(c, "vault_salt") != null
    fun lockType(c: Context): String = Prefs.str(c, "lock_type") ?: LockType.PASS

    fun newSalt(): ByteArray = Crypto.random(16)
    fun storeSalt(c: Context, salt: ByteArray) { Prefs.putStr(c, "vault_salt", Base64.encodeToString(salt, Base64.NO_WRAP)) }
    private fun saltOf(c: Context): ByteArray = Base64.decode(Prefs.str(c, "vault_salt"), Base64.NO_WRAP)

    private fun keyFor(c: Context, secret: String, salt: ByteArray): String {
        val ku = if (secret.isEmpty()) ByteArray(32) else Crypto.kdf(secret, salt, LOGN)
        return Crypto.hex(Crypto.sha256(ku + DevKey.get(c)))
    }

    fun deriveDbKey(c: Context, secret: String): String = keyFor(c, secret, saltOf(c))

    /** Re-encrypts the database under a new lock (type + secret). */
    fun changeLock(c: Context, type: String, secret: String): Boolean = try {
        val salt = newSalt()
        val nk = keyFor(c, secret, salt)
        Db.get(c).db().changePassword(nk)
        storeSalt(c, salt)
        Prefs.putStr(c, "lock_type", type)
        SessionVault.unlock(nk)
        true
    } catch (e: Exception) { false }

    fun myKey(c: Context): String {
        val db = Db.get(c)
        val cur = db.getMeta("my_key")
        if (cur != null) return cur
        val nk = Crypto.randomKey(64)
        db.setMeta("my_key", nk)
        return nk
    }

    fun regenerate(c: Context, len: Int): String {
        val nk = Crypto.randomKey(len)
        Db.get(c).setMeta("my_key", nk)
        return nk
    }
}

/** Second secret (same type as your lock): entering it at login silently wipes everything and opens an EMPTY app. */
object Duress {
    fun set(c: Context, code: String) {
        val salt = Crypto.random(16)
        Prefs.putStr(c, "duress_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
        Prefs.putStr(c, "duress_hash", Crypto.hex(Crypto.kdf(code, salt, Vault.LOGN)))
    }

    fun clear(c: Context) {
        Prefs.putStr(c, "duress_hash", "")
        Prefs.putStr(c, "duress_salt", "")
    }

    fun isSet(c: Context): Boolean = !Prefs.str(c, "duress_hash").isNullOrEmpty()

    fun matches(c: Context, code: String): Boolean {
        val h = Prefs.str(c, "duress_hash")
        val s = Prefs.str(c, "duress_salt")
        if (h.isNullOrEmpty() || s.isNullOrEmpty()) return false
        return Crypto.constEq(h, Crypto.hex(Crypto.kdf(code, Base64.decode(s, Base64.NO_WRAP), Vault.LOGN)))
    }

    fun wipeAll(c: Context) {
        SessionVault.lockAll()
        c.deleteDatabase(Db.NAME)
        Spool.clear(c)
        Files.clear(c)
        Prefs.clear(c)
        DevKey.destroy()
        try { NotificationManagerCompat.from(c).cancelAll() } catch (e: Exception) { }
    }
}
