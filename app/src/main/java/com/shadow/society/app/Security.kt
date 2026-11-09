package com.shadow.society.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.params.HKDFParameters
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

const val GENERIC_DECRYPT_ERROR = "رمزگشایی ناموفق"

/** Every decryption failure (wrong key, tampering, bad format) looks exactly the same. */
class DecryptException : GeneralSecurityException(GENERIC_DECRYPT_ERROR)

/**
 * AES-256-GCM, random 12-byte IV per message.
 * A "session key" blob is 48 bytes: key (32) + salt (16). The salt is bound into the authentication data.
 */
object Crypto {
    private val rng = SecureRandom()
    private val AAD = "shadow/v1".toByteArray()
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    fun random(n: Int): ByteArray { val b = ByteArray(n); rng.nextBytes(b); return b }

    fun randomKey(len: Int): String {
        val sb = StringBuilder(len)
        repeat(len) { sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /** scrypt: used only for the local vault lock (password / PIN). */
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

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun constEq(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun aadOf(key: ByteArray): ByteArray = if (key.size == 48) AAD + key.copyOfRange(32, 48) else AAD
    private fun spec(key: ByteArray) = SecretKeySpec(key, 0, 32, "AES")

    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, spec(key), GCMParameterSpec(128, iv))
        c.updateAAD(aadOf(key))
        return iv + c.doFinal(plain)
    }

    /** Always throws the same generic exception on any failure. */
    @Throws(GeneralSecurityException::class)
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        try {
            if (blob.size < 28) throw GeneralSecurityException()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, spec(key), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            c.updateAAD(aadOf(key))
            return c.doFinal(blob, 12, blob.size - 12)
        } catch (e: Exception) {
            throw DecryptException()
        }
    }
}

/**
 * Session key from the two personal keys only (same as shadow_crypto.derive_pair_key in the Python reference):
 *   salt = SHA-256("shadow/pair-salt" + sorted keys)[:16]
 *   key  = HKDF-SHA256(sorted keys, salt, info = "v1")
 * Result: 48 bytes = key (32) + salt (16). Order of the two keys does not matter.
 */
object SessionCrypto {
    private val INFO = "v1".toByteArray()

    private fun cmp(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x - y
        }
        return 0
    }

    fun derive(keyA: ByteArray, keyB: ByteArray): ByteArray {
        require(keyA.size == 32 && keyB.size == 32)
        val c = cmp(keyA, keyB)
        val first = if (c <= 0) keyA else keyB
        val second = if (c <= 0) keyB else keyA
        val salt = Crypto.sha256("shadow/pair-salt".toByteArray() + first + second).copyOfRange(0, 16)
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(first + second, salt, INFO))
        val out = ByteArray(32)
        gen.generateBytes(out, 0, 32)
        return out + salt
    }

    /** Both people read this aloud: same code = same key. */
    fun fingerprint(blob: ByteArray): String {
        val h = Crypto.hex(Crypto.sha256(blob.copyOfRange(0, 32))).substring(0, 8).uppercase(Locale.ROOT)
        return h.substring(0, 4) + " " + h.substring(4)
    }

    /** Compares this phone's results with vectors produced by the Python reference. */
    fun selfTest(): Boolean {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 32).toByte() }
        val blob = derive(a, b)
        if (Crypto.hex(blob.copyOfRange(0, 32)) != "e04debdc4806d5ad9931221bb66807421fc7f06e7af1ae6eee35dd84b77fcc29") return false
        if (Crypto.hex(blob.copyOfRange(32, 48)) != "c94894cae1445756b0a9071052e68664") return false
        if (Crypto.hex(derive(b, a)) != Crypto.hex(blob)) return false
        val fromPython = Crypto.unhex("6465666768696a6b6c6d6e6f320e302dc99961881c59f827a1b73e518f3cbc45bc")
        val plain = try { String(Crypto.decrypt(blob, fromPython)) } catch (e: Exception) { return false }
        return plain == "hello"
    }
}

/** How long session keys are kept (inside the encrypted vault). -1 = never delete. */
object KeyPolicy {
    fun minutes(c: Context): Int = Prefs.int(c, "key_keep", 10080)   // default: 7 days

    fun set(c: Context, m: Int) {
        Prefs.putInt(c, "key_keep", m)
        SessionVault.restamp(c)
    }

    fun label(m: Int): String = when {
        m < 0 -> tr("Never delete", "هرگز حذف نشود")
        m % 1440 == 0 -> "${m / 1440} " + tr("days", "روز")
        m % 60 == 0 -> "${m / 60} " + tr("hours", "ساعت")
        else -> "$m " + tr("minutes", "دقیقه")
    }
}

/**
 * Keys live in RAM and also inside the encrypted database (with an expiry), so they survive the phone
 * closing the app. Locking the app wipes the RAM copy; logging in again restores the stored ones.
 */
object SessionVault {
    private class Entry(val key: ByteArray, val expiry: Long)

    private var k: String? = null
    private val keys = HashMap<Long, Entry>()
    private var appCtx: Context? = null
    @Volatile private var needRestore = true

    fun init(c: Context) { appCtx = c.applicationContext }

    @Synchronized fun unlock(key: String) { k = key; needRestore = true }
    @Synchronized fun isUnlocked(): Boolean = k != null
    @Synchronized fun dbKey(): String = k ?: throw IllegalStateException("locked")

    private fun expiryFor(c: Context): Long {
        val m = KeyPolicy.minutes(c)
        return if (m < 0) 0L else System.currentTimeMillis() + m * 60000L
    }

    private fun store(c: Context, id: Long, key: ByteArray, exp: Long) {
        try { Db.get(c).setMeta("sk_$id", Crypto.hex(key) + "|" + exp) } catch (e: Exception) { }
    }

    fun putKey(c: Context, id: Long, key: ByteArray) {
        val exp = expiryFor(c)
        synchronized(this) {
            keys[id]?.key?.fill(0)
            keys[id] = Entry(key, exp)
        }
        if (isUnlocked()) store(c, id, key, exp)
    }

    @Synchronized fun key(id: Long): ByteArray? {
        val e = keys[id] ?: return null
        if (e.expiry != 0L && System.currentTimeMillis() > e.expiry) { e.key.fill(0); keys.remove(id); return null }
        return e.key
    }

    /** After the keep-time setting changed: give every key the new expiry. */
    fun restamp(c: Context) {
        val snap = synchronized(this) { keys.entries.map { Pair(it.key, it.value.key.copyOf()) } }
        for ((id, key) in snap) putKey(c, id, key)
    }

    private fun sweepStored(c: Context) {
        try {
            val now = System.currentTimeMillis()
            val dead = ArrayList<String>()
            Db.get(c).db().rawQuery("SELECT k, v FROM meta WHERE substr(k,1,3)='sk_'", null).use { cur ->
                while (cur.moveToNext()) {
                    val exp = (cur.getString(1) ?: "").split("|").getOrNull(1)?.toLongOrNull() ?: 0L
                    if (exp != 0L && exp <= now) dead.add(cur.getString(0))
                }
            }
            for (d in dead) Db.get(c).db().execSQL("DELETE FROM meta WHERE k=?", arrayOf<Any?>(d))
        } catch (e: Exception) { }
    }

    private fun restoreAll(c: Context) {
        val rows = ArrayList<Pair<String, String>>()
        Db.get(c).db().rawQuery("SELECT k, v FROM meta WHERE substr(k,1,3)='sk_'", null).use { cur ->
            while (cur.moveToNext()) rows.add(Pair(cur.getString(0), cur.getString(1) ?: ""))
        }
        val now = System.currentTimeMillis()
        for ((k0, v) in rows) {
            val id = k0.substring(3).toLongOrNull() ?: continue
            val p = v.split("|")
            val exp = p.getOrNull(1)?.toLongOrNull() ?: continue
            if (exp != 0L && exp <= now) {
                Db.get(c).db().execSQL("DELETE FROM meta WHERE k=?", arrayOf<Any?>(k0))
                continue
            }
            val bytes = try { Crypto.unhex(p[0]) } catch (e: Exception) { continue }
            if (bytes.size != 48) continue   // keys of the old method are ignored
            synchronized(this) { if (!keys.containsKey(id)) keys[id] = Entry(bytes, exp) }
        }
    }

    /** After logging in: bring the stored keys back into RAM (once per unlock). */
    fun restoreIfNeeded(c: Context) {
        if (!needRestore || !isUnlocked()) return
        needRestore = false
        try { restoreAll(c) } catch (e: Exception) { needRestore = true }
    }

    /** Called when a screen resumes: restore stored keys, drop the expired ones. */
    fun checkPulse() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val it = keys.entries.iterator()
            while (it.hasNext()) {
                val e = it.next().value
                if (e.expiry != 0L && now > e.expiry) { e.key.fill(0); it.remove() }
            }
        }
        val c = appCtx
        if (c != null && isUnlocked()) { restoreIfNeeded(c); sweepStored(c) }
    }

    /** Deletes ALL session keys, also the stored ones. */
    fun wipeKeys() {
        synchronized(this) {
            keys.values.forEach { it.key.fill(0) }
            keys.clear()
        }
        val c = appCtx
        if (c != null && isUnlocked()) {
            try { Db.get(c).db().execSQL("DELETE FROM meta WHERE substr(k,1,3)='sk_'") } catch (e: Exception) { }
        }
    }

    /** Locks the app: wipes the RAM copy and closes the database. Stored keys stay in the vault. */
    @Synchronized fun lock() {
        keys.values.forEach { it.key.fill(0) }
        keys.clear()
        k = null
        needRestore = true
        Db.close()
    }

    /** Locks AND destroys every key (used before wiping all data). */
    fun lockAll() { wipeKeys(); lock() }
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
 * Vault = the encrypted database.  database key = SHA-256( scrypt(your secret) + device key ).
 * The secret is the password / PIN. For the fingerprint type it is empty (the phone's lock protects it).
 * My key (32 random bytes, as hex) lives inside the encrypted database.
 */
object Vault {
    const val LOGN = 14
    private val HEX64 = Regex("[0-9a-f]{64}")

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

    /** My key as 64 hex characters, or null if it was not created yet (old-style keys count as "not created"). */
    fun myKeyHex(c: Context): String? {
        val v = Db.get(c).getMeta("my_key")
        return if (v != null && HEX64.matches(v)) v else null
    }

    fun myKeyBytes(c: Context): ByteArray? = myKeyHex(c)?.let { Crypto.unhex(it) }

    /** Creates (or replaces) my key: 32 random bytes. */
    fun createKey(c: Context): String {
        val k = Crypto.hex(Crypto.random(32))
        Db.get(c).setMeta("my_key", k)
        return k
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
