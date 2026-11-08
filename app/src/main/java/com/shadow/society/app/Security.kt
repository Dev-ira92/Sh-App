package com.shadow.society.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.ln

const val GENERIC_DECRYPT_ERROR = "رمزگشایی ناموفق"

/** Every decryption failure (wrong key, tampering, bad format) looks exactly the same. */
class DecryptException : GeneralSecurityException(GENERIC_DECRYPT_ERROR)

/**
 * AES-256-GCM, random 12-byte IV per message.
 * A "session key" blob is 32 bytes (key) or 48 bytes (key + 16-byte salt; the salt is bound into the
 * authentication data, exactly like the Python reference).
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
 * Session key = HKDF-SHA256( sorted([keyA, keyB]) + Argon2id(passphrase, salt), salt, info = "v1" ).
 * Same standard as the Python reference (t=3, 64 MB, 4 lanes). Result: 48 bytes = key(32) + salt(16).
 */
object SessionCrypto {
    const val ITER = 3
    const val MEM_KB = 64 * 1024
    const val LANES = 4
    private val INFO = "v1".toByteArray()

    fun normalize(t: String): String {
        var s = Normalizer.normalize(t, Normalizer.Form.NFKC)
        s = s.replace('\u064a', '\u06cc').replace('\u0643', '\u06a9').replace("\u200c", "").replace("\u200d", "")
        return s.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun stretch(pass: String, salt: ByteArray, iter: Int = ITER, memKb: Int = MEM_KB, lanes: Int = LANES): ByteArray {
        val p = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt).withIterations(iter).withMemoryAsKB(memKb).withParallelism(lanes).build()
        val g = Argon2BytesGenerator()
        g.init(p)
        val out = ByteArray(32)
        g.generateBytes(normalize(pass).toByteArray(Charsets.UTF_8), out)
        return out
    }

    private fun cmp(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x - y
        }
        return 0
    }

    fun derive(keyA: ByteArray, keyB: ByteArray, pass: String, salt: ByteArray,
               iter: Int = ITER, memKb: Int = MEM_KB, lanes: Int = LANES): ByteArray {
        require(keyA.size == 32 && keyB.size == 32 && salt.size == 16)
        val first = if (cmp(keyA, keyB) <= 0) keyA else keyB
        val second = if (cmp(keyA, keyB) <= 0) keyB else keyA
        val ikm = first + second + stretch(pass, salt, iter, memKb, lanes)
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, INFO))
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
        val salt = ByteArray(16) { it.toByte() }
        val pw = "alpha  Beta   gamma delta epsilon zeta eta theta"
        val blob = derive(a, b, pw, salt)
        if (Crypto.hex(blob.copyOfRange(0, 32)) != "cd03fbb61dd60f4f1e98a6aaa02e2beb856156f436214635f6467c1fd8698df4") return false
        if (Crypto.hex(derive(b, a, pw, salt)) != Crypto.hex(blob)) return false
        val fromPython = Crypto.unhex("6465666768696a6b6c6d6e6fea37ed639397784b1fe65a287afa92ef4bad8c3b6a")
        val plain = try { String(Crypto.decrypt(blob, fromPython)) } catch (e: Exception) { return false }
        if (plain != "hello") return false
        return normalize("  \u064a\u200c\u0643   \u06a9\u0644\u0645\u0647   HELLO ") == "\u06cc\u06a9 \u06a9\u0644\u0645\u0647 hello"
    }
}

/** Diceware-style passphrase. Uses assets/wordlist_en.txt + wordlist_fa.txt if you add them, plus a built-in list. */
object Diceware {
    private val rng = SecureRandom()
    private const val MIN_BITS = 100.0

    private val BUILTIN = listOf(
        "amber", "anchor", "arrow", "atlas", "badge", "basil", "beacon", "birch", "blade", "bridge", "cabin", "candle",
        "canyon", "cedar", "cliff", "cobalt", "comet", "coral", "crater", "dagger", "delta", "ember", "falcon", "fjord",
        "flint", "forest", "garnet", "glacier", "harbor", "hazel", "indigo", "island", "jungle", "kernel", "lagoon",
        "lantern", "marble", "meadow", "nectar", "oasis", "orbit", "pebble", "quartz", "raven", "saddle", "thunder",
        "umber", "valley", "willow", "zephyr", "acorn", "bamboo", "breeze", "canvas", "cinder", "clover", "copper",
        "dune", "eagle", "ferry", "granite", "hammer", "iron", "jasper", "kettle", "lotus", "maple", "noble", "olive",
        "pearl", "quiver", "ridge", "silver", "tundra", "velvet", "walnut", "yarrow", "zenith", "anvil", "bronze",
        "cipher", "drift", "echo", "flame", "grove", "haven", "ivory", "jade", "knight", "ledger", "mirror", "north",
        "onyx", "prism", "quill", "river", "shadow", "timber", "unity", "vortex", "wander",
        "آب", "آسمان", "ابر", "اسب", "انار", "باد", "باران", "بلبل", "پرنده", "پنجره", "تابستان", "ترازو", "جنگل",
        "چراغ", "چشمه", "حوض", "خورشید", "دریا", "درخت", "دشت", "رود", "ستاره", "سنگ", "شمع", "شاهین", "صبح",
        "طوفان", "عقاب", "غروب", "فانوس", "قلعه", "کوه", "کتاب", "گل", "گندم", "ماه", "مهتاب", "نسیم", "نقره",
        "هلال", "یاقوت", "زمرد", "زمستان", "بهار", "پاییز", "آتش", "برف", "تندر", "دره", "سبد", "شب", "قایق",
        "کاروان", "گنج", "لاله", "نیلوفر", "آهو", "پروانه", "تاک", "جویبار", "چنار", "خرمن", "دیوار", "زنبق",
        "سیب", "شقایق", "صدف", "عسل", "فرش", "قطره", "گردو", "لیمو", "مروارید", "نان", "هسته", "یخ", "ابریشم",
        "بادام", "پله", "تپه", "جاده", "چتر", "حلزون", "خاک", "دف", "رعد", "زیتون", "سفال", "شیشه", "طلا", "کلید",
        "گلدان", "ماهی", "نعنا", "همای")

    private fun fromAssets(c: Context, name: String): List<String> = try {
        c.assets.open(name).bufferedReader().readLines().mapNotNull {
            val t = it.trim()
            if (t.isEmpty() || t.startsWith("#")) null else SessionCrypto.normalize(t.split(Regex("\\s+")).last())
        }
    } catch (e: Exception) { emptyList() }

    fun words(c: Context): List<String> =
        (fromAssets(c, "wordlist_en.txt") + fromAssets(c, "wordlist_fa.txt") + BUILTIN.map { SessionCrypto.normalize(it) }).distinct()

    /** At least 8 words; more if the list is small, so the entropy stays at 100 bits or more. */
    fun generate(c: Context): String {
        val w = words(c)
        val bits = ln(w.size.toDouble()) / ln(2.0)
        val count = maxOf(8, ceil(MIN_BITS / bits).toInt())
        return (0 until count).joinToString(" ") { w[rng.nextInt(w.size)] }
    }
}

/** The shared passphrase + salt that one of you creates and hands to the other. */
object SharedSecret {
    fun generate(c: Context): Pair<String, String> = Pair(Diceware.generate(c), Crypto.hex(Crypto.random(16)))

    fun toLine(pass: String, salt: String): String = "SHADOWPAIR1|" + SessionCrypto.normalize(pass) + "|" + salt

    fun toJson(pass: String, salt: String): String = JSONObject().put("app", "Shadow").put("type", "shared")
        .put("v", 1).put("pass", SessionCrypto.normalize(pass)).put("salt", salt).toString(2)

    /** Accepts the JSON file or the "SHADOWPAIR1|words|salt" line. Returns (passphrase, salt). */
    fun parse(text: String): Pair<String, String>? {
        val t = text.trim()
        var pass: String? = null
        var salt: String? = null
        if (t.startsWith("{")) {
            try {
                val j = JSONObject(t)
                if (j.optString("type") != "shared") return null
                pass = j.optString("pass")
                salt = j.optString("salt")
            } catch (e: Exception) { return null }
        } else {
            for (l in t.lines()) {
                val x = l.trim()
                if (x.startsWith("SHADOWPAIR1|")) {
                    val p = x.split("|")
                    if (p.size == 3) { pass = p[1]; salt = p[2] }
                    break
                }
            }
        }
        val s = salt?.lowercase() ?: return null
        if (pass.isNullOrBlank() || !Regex("[0-9a-f]{32}").matches(s)) return null
        return Pair(pass, s)
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
            if (bytes.size != 32 && bytes.size != 48) continue
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
