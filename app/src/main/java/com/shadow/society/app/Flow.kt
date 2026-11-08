package com.shadow.society.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
private val HEX32 = Regex("[0-9a-f]{32}")

/**
 * Builds the session key from my key + the friend's key + the shared passphrase + the shared salt
 * (Argon2id + HKDF, takes a few seconds) and stores it. The keys are never sent anywhere.
 */
fun deriveAndStore(a: AppCompatActivity, id: Long, friendRaw: String, passphrase: String, saltRaw: String,
                   cb: (String?, String?) -> Unit) {
    Thread {
        var fp: String? = null
        var err: String? = null
        try {
            val f = friendRaw.trim()
            val fk = (Files.parseKey(f) ?: f).trim()
            require(HEX64.matches(fk)) { tr("The friend's key must be 64 hex characters", "کلید دوستم باید ۶۴ نویسه‌ی hex باشد") }
            val salt = saltRaw.trim().lowercase()
            require(HEX32.matches(salt)) { tr("The salt must be 32 hex characters", "salt باید ۳۲ نویسه‌ی hex باشد") }
            require(SessionCrypto.normalize(passphrase).split(" ").size >= 8) {
                tr("The passphrase needs at least 8 words", "عبارت حداقل ۸ کلمه لازم دارد")
            }
            val mine = Vault.myKeyBytes(a) ?: throw IllegalArgumentException(
                tr("Create your own key first (My key page)", "اول کلید خودت را بساز (صفحه‌ی کلید من)"))
            val blob = SessionCrypto.derive(mine, Crypto.unhex(fk.lowercase()), passphrase, Crypto.unhex(salt))
            fp = SessionCrypto.fingerprint(blob)
            SessionVault.putKey(a, id, blob)
        } catch (e: Exception) { err = e.message ?: "error" }
        val f2 = fp
        val e2 = err
        a.runOnUiThread { cb(f2, e2) }
    }.start()
}

/**
 * New / edit contact. The phone number is also the online ID. Encrypted by default: needs the friend's key,
 * the shared passphrase and the shared salt. Optional: the friend's server address.
 */
fun showAddContact(a: OnlineBase, ex: Contact?, done: () -> Unit) {
    val sv = ScrollView(a)
    val col = Neo.vertical(a)
    val p = Neo.dp(a, 20)
    col.setPadding(p, p / 2, p, 0)
    sv.addView(col)

    val name = Neo.input(a, tr("name", "نام"))
    val phone = Neo.input(a, tr("phone number (also the online ID)", "شماره‌ی موبایل (شناسه‌ی آنلاین هم همین است)"))
        .apply { inputType = InputType.TYPE_CLASS_PHONE }
    val fire = Switch(a).apply {
        text = tr("🔐 Encrypted (recommended)", "🔐 رمزنگاری‌شده (پیشنهادی)")
        setTextColor(Neo.TEXT)
        isChecked = true
    }
    val plainNote = Neo.text(a, tr("Plain text: anyone on the way can read it.",
        "متن ساده: هر کسی در مسیر می‌تواند بخواند."), 12, Neo.RED)

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

    val pass = Neo.input(a, tr("shared passphrase (words)", "عبارت مشترک (کلمات)")).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 2
    }
    val salt = Neo.input(a, tr("shared salt (32 hex characters)", "salt مشترک (۳۲ نویسه‌ی hex)"))
    val importShared = Neo.button(a, tr("Import passphrase + salt (QR / file / paste)", "وارد کردن عبارت و salt (QR / فایل / paste)"))
    importShared.setOnClickListener {
        a.importRaw(tr("Passphrase + salt", "عبارت و salt")) { raw ->
            val pr = SharedSecret.parse(raw)
            if (pr == null) {
                Toast.makeText(a, tr("This is not a passphrase + salt", "این عبارت و salt نیست"), Toast.LENGTH_SHORT).show()
            } else {
                pass.setText(pr.first)
                salt.setText(pr.second)
            }
        }
    }

    val fireBox = Neo.vertical(a)
    Neo.add(fireBox, Neo.text(a, tr("AES key = HKDF( my key + friend's key + passphrase, salt ). Nothing here is sent anywhere.",
        "کلید AES = HKDF( کلید من + کلید دوستم + عبارت، salt ). هیچ‌کدام از این‌ها فرستاده نمی‌شود."), 11, Neo.DIM))
    Neo.add(fireBox, frow, 8)
    Neo.add(fireBox, importShared, 8)
    Neo.add(fireBox, pass, 8)
    Neo.add(fireBox, salt, 8)
    if (ex != null) {
        Neo.add(fireBox, Neo.text(a, tr("Leave these empty to keep the current session key.",
            "برای نگه‌داشتن کلید جلسه‌ی فعلی خالی بگذارید."), 11, Neo.DIM), 6)
    }

    // friend's server address (optional)
    val curSrv = if (ex != null) Online.contactServer(a, ex.id) else null
    val srv = Neo.input(a, if (curSrv != null) tr("current: ", "فعلی: ") + Online.host(curSrv.u)
                           else tr("friend's server address (optional)", "آدرس سرور دوستم (اختیاری)"))
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

    Neo.add(col, name)
    Neo.add(col, phone, 8)
    Neo.add(col, fire, 14)
    Neo.add(col, plainNote, 6)
    Neo.add(col, fireBox, 6)
    Neo.add(col, Neo.text(a, tr("Online: leave empty to use my server.", "آنلاین: خالی بگذار تا از سرور من استفاده شود."), 11, Neo.DIM), 14)
    Neo.add(col, srow, 4)

    fun refresh() {
        fireBox.visibility = if (fire.isChecked) View.VISIBLE else View.GONE
        plainNote.visibility = if (fire.isChecked) View.GONE else View.VISIBLE
    }
    fire.setOnCheckedChangeListener { _, _ -> refresh() }
    if (ex != null) { name.setText(ex.name); phone.setText(ex.phone); fire.isChecked = ex.level == 1 }
    refresh()

    val d = Neo.dialog(a)
        .setTitle(if (ex == null) tr("New contact", "مخاطب جدید") else tr("Edit contact", "ویرایش مخاطب"))
        .setView(sv)
        .setPositiveButton(tr("Save", "ذخیره"), null)
        .setNegativeButton(tr("Cancel", "انصراف"), null)
        .create()
    d.show()

    fun t(s: String) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show() }

    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn ->
        val n = name.text.toString().trim()
        val ph = phone.text.toString().trim()
        val isFire = fire.isChecked
        val f = friend.text.toString().trim()
        val ps = pass.text.toString().trim()
        val sl = salt.text.toString().trim()
        val st = srv.text.toString().trim()
        val srvInfo = if (st.isEmpty()) null else OnlineParse.server(st)
        val any = f.isNotEmpty() || ps.isNotEmpty() || sl.isNotEmpty()
        val all = f.isNotEmpty() && ps.isNotEmpty() && sl.isNotEmpty()

        if (n.isEmpty() || ph.isEmpty()) { t(tr("name and number required", "نام و شماره لازم است")); return@setOnClickListener }
        if (st.isNotEmpty() && srvInfo == null) {
            t(tr("This is not a server address", "این آدرس سرور نیست"))
            return@setOnClickListener
        }
        if (isFire && ((ex == null && !all) || (any && !all))) {
            t(tr("Fill in the friend's key, the passphrase and the salt", "کلید دوستم، عبارت و salt را کامل وارد کن"))
            return@setOnClickListener
        }

        val level = if (isFire) 1 else 0
        val id: Long
        val ok: Boolean
        if (ex == null) {
            id = Contacts.insert(a, Contact(0, n, ph, level, 0))
            ok = id > 0
        } else {
            id = ex.id
            ok = Contacts.update(a, Contact(ex.id, n, ph, level, ex.createdAt))
        }
        if (!ok) { t(tr("number already exists", "این شماره قبلاً ثبت شده")); return@setOnClickListener }
        if (srvInfo != null) Online.setContactServer(a, id, srvInfo)

        if (!(isFire && all)) { d.dismiss(); done(); return@setOnClickListener }

        btn.isEnabled = false
        t(tr("Deriving the key (a few seconds)…", "در حال ساخت کلید (چند ثانیه)…"))
        deriveAndStore(a, id, f, ps, sl) { fp, err ->
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
