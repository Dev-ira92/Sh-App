package com.shadow.society.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Every screen after login extends this: locked vault => login; theme change => rebuild; QR scan helper. */
abstract class BaseActivity : AppCompatActivity() {
    private var built = -1
    private var scanCb: ((String) -> Unit)? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { r ->
        val s = r.contents
        if (s != null) scanCb?.invoke(s)
    }

    fun scanKey(cb: (String) -> Unit) {
        scanCb = cb
        val o = ScanOptions()
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        o.setPrompt(tr("Scan your friend's key QR", "QR کلید دوستت را اسکن کن"))
        o.setBeepEnabled(false)
        o.setOrientationLocked(false)
        scanLauncher.launch(o)
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
        SessionVault.checkPulse()
        if (!SessionVault.isUnlocked()) { toLogin(); return }
        onReady()
    }

    protected abstract fun onReady()

    private fun toLogin() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/** Builds the AES session key (in RAM only) from my key + friend key + verbal code + method. */
fun deriveAndStore(a: AppCompatActivity, id: Long, friend: String, verbal: String, method: MixMethod,
                   formula: String, cb: (String?, String?) -> Unit) {
    Thread {
        var fp: String? = null
        var err: String? = null
        try {
            val mixed = KeyMixer.mix(method, Vault.myKey(a), friend, verbal, formula)
            val key = KeyMixer.deriveKey(mixed)
            fp = KeyMixer.fingerprint(key)
            SessionVault.putKey(a, id, key)
        } catch (e: Exception) { err = e.message ?: "error" }
        val f = fp
        val e2 = err
        a.runOnUiThread { cb(f, e2) }
    }.start()
}

/** New / edit contact. Fire level (default) asks for friend key, verbal code and mixing method. */
fun showAddContact(a: BaseActivity, ex: Contact?, done: () -> Unit) {
    val sv = ScrollView(a)
    val col = Neo.vertical(a)
    val p = Neo.dp(a, 20)
    col.setPadding(p, p / 2, p, 0)
    sv.addView(col)

    val name = Neo.input(a, tr("name", "نام"))
    val phone = Neo.input(a, tr("phone number", "شماره تلفن")).apply { inputType = InputType.TYPE_CLASS_PHONE }
    val fire = Switch(a).apply {
        text = tr("🔴 Fire level (AES with your keys)", "🔴 سطح آتش (رمزنگاری AES با کلیدهای شما)")
        setTextColor(Neo.P.recvText)
        isChecked = true
    }
    val plainNote = Neo.text(a, tr("Plain text over SMS — anyone can read it.",
        "متن ساده روی پیامک — هر کسی می‌تواند بخواند."), 12, Neo.RED)

    val friend = Neo.input(a, tr("friend's key", "کلید دوستم")).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 2
    }
    val frow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    frow.addView(friend, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    frow.addView(Icons.button(a, "scan", Neo.ACCENT) { a.scanKey { friend.setText(it) } })
    val verbal = Neo.input(a, tr("verbal code (2-3 words)", "کد شفاهی (۲-۳ کلمه)"))
    val formula = Neo.input(a, tr("mixer formula", "فرمول اختلاط")).apply { setText(KeyMixer.DEFAULT_FORMULA) }
    val descView = Neo.text(a, "", 11, Neo.DIM)
    var method = MixMethod.CONCAT
    val mBtn = Neo.button(a, "")

    val fireBox = Neo.vertical(a)
    Neo.add(fireBox, Neo.text(a, tr("AES key = my key (automatic) + friend's key + verbal code + method.",
        "کلید AES = کلید من (خودکار) + کلید دوستم + کد شفاهی + روش ترکیب."), 11, Neo.DIM))
    Neo.add(fireBox, frow, 8)
    Neo.add(fireBox, verbal, 8)
    Neo.add(fireBox, mBtn, 8)
    Neo.add(fireBox, descView, 2)
    Neo.add(fireBox, formula, 8)
    if (ex != null) {
        Neo.add(fireBox, Neo.text(a, tr("Leave empty to keep the current session key.",
            "برای نگه‌داشتن کلید جلسه‌ی فعلی خالی بگذارید."), 11, Neo.DIM), 6)
    }

    Neo.add(col, name)
    Neo.add(col, phone, 8)
    Neo.add(col, fire, 14)
    Neo.add(col, plainNote, 6)
    Neo.add(col, fireBox, 6)

    fun refresh() {
        mBtn.text = "⚙ " + method.label()
        descView.text = method.desc
        formula.visibility = if (method == MixMethod.FORMULA) View.VISIBLE else View.GONE
        fireBox.visibility = if (fire.isChecked) View.VISIBLE else View.GONE
        plainNote.visibility = if (fire.isChecked) View.GONE else View.VISIBLE
    }
    mBtn.setOnClickListener {
        val all = MixMethod.values()
        Dialogs.choose(a, tr("Mixing method", "روش ترکیب کلید"),
            Array(all.size) { all[it].label() }, method.ordinal) { method = all[it]; refresh() }
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
        val vb = verbal.text.toString().trim()
        val fm = formula.text.toString()
        val mt = method
        val hasKeys = isFire && f.isNotEmpty() && vb.isNotEmpty()

        if (n.isEmpty() || ph.isEmpty()) { t(tr("name and number required", "نام و شماره لازم است")); return@setOnClickListener }
        if (isFire && ex == null && !hasKeys) {
            t(tr("Fire needs friend's key + verbal code", "سطح آتش کلید دوستم و کد شفاهی لازم دارد"))
            return@setOnClickListener
        }
        if (hasKeys) {
            try { KeyMixer.mix(mt, Vault.myKey(a), f, vb, fm) }
            catch (e: Exception) { t(tr("invalid input: ", "ورودی نامعتبر: ") + (e.message ?: "")); return@setOnClickListener }
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

        if (!hasKeys) { d.dismiss(); done(); return@setOnClickListener }

        btn.isEnabled = false
        deriveAndStore(a, id, f, vb, mt, fm) { fp, err ->
            d.dismiss()
            done()
            if (err != null) {
                t(tr("invalid input: ", "ورودی نامعتبر: ") + err)
            } else {
                Dialogs.info(a, tr("Fingerprint", "اثر انگشت کلید"),
                    fp + "\n\n" + tr("Read this aloud to your contact. If it differs, messages will show raw text only.",
                        "این کد را شفاهی با مخاطب مقایسه کنید. اگر فرق داشت، پیام‌ها فقط به شکل متن رمزشده دیده می‌شوند."))
            }
        }
    }
}
