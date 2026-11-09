package com.shadow.society.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Fingerprint / face / phone lock, asked by the phone itself. */
object AppLock {
    private fun pick(c: Context): Int? {
        val bm = BiometricManager.from(c)
        val opts = intArrayOf(
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
            Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL,
            Authenticators.BIOMETRIC_WEAK,
            Authenticators.DEVICE_CREDENTIAL)
        for (a in opts) {
            if (bm.canAuthenticate(a) == BiometricManager.BIOMETRIC_SUCCESS) return a
        }
        return null
    }

    fun available(c: Context): Boolean = pick(c) != null
    fun enabled(c: Context): Boolean = Prefs.int(c, "applock", 1) == 1

    @Volatile private var lastOk = 0L
    fun recentlyOk(): Boolean = lastOk != 0L && SystemClock.elapsedRealtime() - lastOk < 20000L

    fun prompt(a: FragmentActivity, onOk: () -> Unit, onFail: (String) -> Unit) {
        val auth = pick(a)
        if (auth == null) { onFail(tr("No screen lock / fingerprint on this phone", "این گوشی قفل صفحه یا اثر انگشت ندارد")); return }
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                lastOk = SystemClock.elapsedRealtime()
                onOk()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFail(errString.toString())
            }
        }
        val b = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Shadow")
            .setSubtitle(tr("Verify it's you", "هویت خود را تأیید کنید"))
            .setAllowedAuthenticators(auth)
        if ((auth and Authenticators.DEVICE_CREDENTIAL) == 0) b.setNegativeButtonText(tr("Cancel", "انصراف"))
        BiometricPrompt(a, ContextCompat.getMainExecutor(a), cb).authenticate(b.build())
    }
}

/** Every screen after login extends this: locked vault => login; theme change => rebuild; QR / file helpers. */
abstract class BaseActivity : AppCompatActivity() {
    private var built = -1
    private var scanCb: ((String) -> Unit)? = null
    private var keyCb: ((String) -> Unit)? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { r ->
        val s = r.contents
        if (s != null) scanCb?.invoke(s)
    }

    private val keyFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val k = Files.readKey(this, uri)
            if (k == null) {
                Toast.makeText(this, tr("No key found in this file", "کلیدی در این فایل پیدا نشد"), Toast.LENGTH_LONG).show()
            } else {
                keyCb?.invoke(k)
            }
        }
    }

    fun scanKey(cb: (String) -> Unit) {
        scanCb = cb
        val o = ScanOptions()
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        o.setPrompt(tr("Scan the QR code", "QR را اسکن کن"))
        o.setBeepEnabled(false)
        o.setOrientationLocked(false)
        scanLauncher.launch(o)
    }

    /** Pick a key file: JSON, TXT or a picture of the QR code. */
    fun pickKeyFile(cb: (String) -> Unit) {
        keyCb = cb
        keyFileLauncher.launch(arrayOf("text/*", "application/json", "image/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        built = Neo.version
        Neo.styleWindow(this)
        if (!SessionVault.isUnlocked()) toLogin()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (built != Neo.version) { recreate(); return }
        if (!SessionVault.isUnlocked()) { toLogin(); return }
        SessionVault.checkPulse()
        onReady()
    }

    protected abstract fun onReady()

    private fun toLogin() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

private val HEX64 = Regex("[0-9a-fA-F]{64}")

/** The friend's key as lowercase hex, or null. Accepts a pasted key, the JSON file or a "SHSOKEY1|…" line. */
fun parseFriendKey(raw: String): String? {
    val t = raw.trim()
    val k = (Files.parseKey(t) ?: t).trim()
    return if (HEX64.matches(k)) k.lowercase() else null
}

/** Builds the session key from my key + the friend's key (HKDF, instant) and stores it. Keys are never sent anywhere. */
fun deriveAndStore(a: AppCompatActivity, id: Long, friendHex: String, cb: (String?, String?) -> Unit) {
    Thread {
        var fp: String? = null
        var err: String? = null
        try {
            val mine = Vault.myKeyBytes(a) ?: throw IllegalArgumentException(
                tr("Create your own key first (My key page)", "اول کلید خودت را بساز (صفحه‌ی کلید من)"))
            if (Crypto.hex(mine) == friendHex) throw IllegalArgumentException(
                tr("That is your own key. Enter your friend's key.", "این کلید خودت است. کلید دوستت را وارد کن."))
            val blob = SessionCrypto.derive(mine, Crypto.unhex(friendHex))
            fp = SessionCrypto.fingerprint(blob)
            SessionVault.putKey(a, id, blob)
        } catch (e: Exception) { err = e.message ?: "error" }
        val f2 = fp
        val e2 = err
        a.runOnUiThread { cb(f2, e2) }
    }.start()
}

/**
 * New / edit contact: first name, last name, phone number (also the online ID), friend's key,
 * and the friend's server address (optional, for online). Everything is encrypted.
 */
fun showAddContact(a: OnlineBase, ex: Contact?, done: () -> Unit) {
    val sv = ScrollView(a)
    val col = Neo.vertical(a)
    val p = Neo.dp(a, 20)
    col.setPadding(p, p / 2, p, 0)
    sv.addView(col)

    val first = Neo.input(a, tr("first name", "نام"))
    val last = Neo.input(a, tr("last name", "نام خانوادگی"))
    val phone = Neo.input(a, tr("phone number (also the online ID)", "شماره‌ی موبایل (شناسه‌ی آنلاین هم همین است)"))
        .apply { inputType = InputType.TYPE_CLASS_PHONE }

    val friend = Neo.input(a, tr("friend's key (64 hex characters)", "کلید دوستم (۶۴ نویسه‌ی hex)")).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 2
    }
    val frow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    frow.addView(friend, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    frow.addView(Icons.button(a, "scan", Neo.ACCENT) { a.scanKey { friend.setText(it) } })
    frow.addView(Icons.button(a, "file", Neo.ACCENT) { a.pickKeyFile { friend.setText(it) } })

    val curSrv = if (ex != null) Online.contactServer(a, ex.id) else null
    val srv = Neo.input(a, if (curSrv != null) tr("current: ", "فعلی: ") + Online.host(curSrv.u)
                           else tr("friend's server address (optional, online)", "آدرس سرور دوستم (اختیاری، برای آنلاین)"))
    val srow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    srow.addView(srv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    srow.addView(Icons.button(a, "download", Neo.ACCENT) {
        a.importRaw(tr("Friend's server address", "آدرس سرور دوستم")) { raw ->
            val s = OnlineParse.server(raw)
            srv.setText(s?.toLine() ?: raw)
        }
    })

    Neo.add(col, first)
    Neo.add(col, last, 8)
    Neo.add(col, phone, 8)
    Neo.add(col, frow, 8)
    if (ex != null) {
        Neo.add(col, Neo.text(a, tr("Leave the key empty to keep the current one.", "برای نگه‌داشتن کلید فعلی، کلید را خالی بگذار."), 11, Neo.DIM), 4)
    }
    Neo.add(col, srow, 8)

    if (ex != null) {
        val i = ex.name.indexOf(' ')
        first.setText(if (i < 0) ex.name else ex.name.substring(0, i))
        last.setText(if (i < 0) "" else ex.name.substring(i + 1))
        phone.setText(ex.phone)
    }

    val d = Neo.dialog(a)
        .setTitle(if (ex == null) tr("New contact", "مخاطب جدید") else tr("Edit contact", "ویرایش مخاطب"))
        .setView(sv)
        .setPositiveButton(tr("Save", "ذخیره"), null)
        .setNegativeButton(tr("Cancel", "انصراف"), null)
        .create()
    d.show()

    fun t(s: String) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show() }

    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn ->
        val fn = first.text.toString().trim()
        val ln = last.text.toString().trim()
        val n = (fn + " " + ln).trim()
        val ph = phone.text.toString().trim()
        val fkRaw = friend.text.toString().trim()
        val st = srv.text.toString().trim()
        val srvInfo = if (st.isEmpty()) null else OnlineParse.server(st)
        val fk = if (fkRaw.isEmpty()) null else parseFriendKey(fkRaw)

        if (fn.isEmpty() || ph.isEmpty()) { t(tr("first name and phone number are required", "نام و شماره‌ی موبایل لازم است")); return@setOnClickListener }
        if (Phones.tail(ph).length < 6) { t(tr("Enter a valid phone number", "یک شماره‌ی معتبر وارد کن")); return@setOnClickListener }
        if (st.isNotEmpty() && srvInfo == null) { t(tr("This is not a server address", "این آدرس سرور نیست")); return@setOnClickListener }
        if (fkRaw.isNotEmpty() && fk == null) { t(tr("The friend's key must be 64 hex characters", "کلید دوستم باید ۶۴ نویسه‌ی hex باشد")); return@setOnClickListener }
        if (ex == null && fk == null) { t(tr("The friend's key is required", "کلید دوستم لازم است")); return@setOnClickListener }
        if (fk != null && Vault.myKeyHex(a) == null) {
            t(tr("Create your own key first (My key page)", "اول کلید خودت را بساز (صفحه‌ی کلید من)"))
            return@setOnClickListener
        }

        val id: Long
        val ok: Boolean
        if (ex == null) {
            id = Contacts.insert(a, Contact(0, n, ph, 1, 0))
            ok = id > 0
        } else {
            id = ex.id
            ok = Contacts.update(a, Contact(ex.id, n, ph, 1, ex.createdAt))
        }
        if (!ok) { t(tr("number already exists", "این شماره قبلاً ثبت شده")); return@setOnClickListener }
        if (srvInfo != null) Online.setContactServer(a, id, srvInfo)

        if (fk == null) { d.dismiss(); done(); return@setOnClickListener }

        btn.isEnabled = false
        deriveAndStore(a, id, fk) { fp, err ->
            d.dismiss()
            done()
            if (err != null) {
                t(err)
            } else {
                Dialogs.info(a, tr("Fingerprint", "اثر انگشت کلید"),
                    fp + "\n\n" + tr("Read this aloud to your contact. If it differs, messages will not decrypt.",
                        "این کد را شفاهی با مخاطب مقایسه کنید. اگر فرق داشت، پیام‌ها رمزگشایی نمی‌شوند."))
            }
        }
    }
}
