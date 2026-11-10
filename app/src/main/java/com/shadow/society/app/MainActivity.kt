package com.shadow.society.app

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * First run: the sign-up page (phone with country code, password + repeat, my key, Register).
 * Next runs: the login page (password / PIN / fingerprint, depending on what you chose in Settings).
 */
class MainActivity : AppCompatActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private var busy = false
    private var type = LockType.PASS
    private var pad: SecretPad? = null
    private var passField: FloatField? = null

    private companion object {
        const val OK = 0
        const val WRONG = 1
        const val ERROR = 2
        var keepKey: String? = null   // survives a language switch while signing up
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Neo.styleWindow(this)
        if (Root.isRooted(this)) {
            val t = Neo.text(this, tr("Rooted device detected. Access denied.", "دستگاه روت شده شناسایی شد. دسترسی ممنوع."), 16, Neo.RED)
            t.gravity = Gravity.CENTER
            setContentView(t)
            return
        }
        if (SessionVault.isUnlocked()) { openNext(); return }
        if (!Vault.exists(this)) {
            buildRegister()
        } else {
            type = Vault.lockType(this)
            buildLogin()
        }
    }

    /** The white card, centered on the page. */
    private fun shell(): LinearLayout {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        val wrap = LinearLayout(this)
        wrap.gravity = Gravity.CENTER
        wrap.orientation = LinearLayout.VERTICAL
        val p = Neo.dp(this, 20)
        wrap.setPadding(p, p, p, p)
        val card = Neo.card(this)
        wrap.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sv.addView(wrap, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(Neo.backdrop(this, sv))
        return card
    }

    private fun smallText(s: String, color: Int = Neo.DIM): TextView {
        val t = Neo.text(this, s, 10, color)
        return t
    }

    // ---------- sign up ----------
    private fun buildRegister() {
        val card = shell()
        card.addView(PulseTitle(this, "Shadow"))
        Neo.add(card, Neo.text(this, tr("Create your account. Your phone number is your online ID.",
            "ثبت‌نام کن. شماره‌ی موبایلت شناسه‌ی آنلاین توست."), 9, Neo.DIM), 10)

        // phone: country code + number
        val code = FloatField(this, tr("Country code", "کد کشور")) { Regex("\\+\\d{1,4}").matches(it.trim()) }
        code.edit.inputType = InputType.TYPE_CLASS_PHONE
        code.edit.setText("+98")
        val phone = FloatField(this, tr("Phone number", "شماره تماس")) { Phones.digits(it).length >= 6 }
        phone.edit.inputType = InputType.TYPE_CLASS_PHONE
        Neo.add(card, formRow(this, code to 0.4f, phone to 0.6f), 12)

        // password + repeat
        val pass = FloatField(this, tr("Password", "رمز عبور")) { it.length >= 8 }
        val pass2 = FloatField(this, tr("Repeat password", "تکرار رمز")) { it.length >= 8 && it == pass.text() }
        val pwType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        pass.edit.inputType = pwType
        pass2.edit.inputType = pwType
        pass.edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { pass2.refresh() }
        })
        Neo.add(card, formRow(this, pass to 1f, pass2 to 1f), 10)
        Neo.add(card, smallText(tr("At least 8 characters.", "حداقل ۸ کاراکتر.")), 2)

        // my key: created here, one line shown
        var key = keepKey ?: Crypto.hex(Crypto.random(32))
        keepKey = key
        Neo.add(card, Neo.text(this, tr("My key", "کلید من"), 10, Neo.ACCENT).apply { typeface = Neo.BOLD }, 12)
        val keyView = TextView(this)
        keyView.typeface = Typeface.MONOSPACE
        keyView.textSize = 13f * (Neo.fs / 1.5f)
        keyView.setTextColor(Neo.TEXT)
        keyView.setSingleLine(true)
        keyView.ellipsize = TextUtils.TruncateAt.MIDDLE
        keyView.background = Neo.box(this, android.graphics.Color.WHITE, Neo.BORDER, 10)
        val kp = Neo.dp(this, 10)
        keyView.setPadding(kp, kp, kp, kp)
        keyView.text = key
        Neo.add(card, keyView, 4)

        val create = Neo.button(this, tr("Create", "ایجاد"))
        val copy = Neo.button(this, tr("Copy", "کپی"))
        val send = Neo.button(this, tr("Send", "ارسال"))
        Neo.add(card, formRow(this, create to 1f, copy to 1f, send to 1f), 8)
        create.setOnClickListener {
            key = Crypto.hex(Crypto.random(32))
            keepKey = key
            keyView.text = key
        }
        copy.setOnClickListener { Neo.copy(this, key) }
        send.setOnClickListener { sendDialog(key) }

        // register
        val reg = Neo.primary(this, tr("Register", "ثبت نام"))
        Neo.add(card, reg, 16)
        status = Neo.text(this, "", 10, Neo.DIM)
        Neo.add(card, status, 8)

        reg.setOnClickListener {
            val c = code.text().trim()
            val num = Phones.digits(phone.text())
            val pw = pass.text()
            val e = when {
                !Regex("\\+\\d{1,4}").matches(c) -> tr("Enter a country code like +98", "کد کشور را مثل +98 وارد کن")
                num.length < 6 -> tr("Enter a valid phone number", "یک شماره‌ی معتبر وارد کن")
                pw.length < 8 -> tr("Password: at least 8 characters", "رمز عبور: حداقل ۸ کاراکتر")
                pw != pass2.text() -> tr("The two passwords differ", "دو رمز یکسان نیستند")
                else -> null
            }
            code.bad(!Regex("\\+\\d{1,4}").matches(c))
            phone.bad(num.length < 6)
            pass.bad(pw.length < 8)
            pass2.bad(pw != pass2.text())
            if (e != null) err(e) else register(pw, c + num.trimStart('0'), key)
        }

        // language links
        val langRow = LinearLayout(this)
        langRow.gravity = Gravity.CENTER
        for ((i, pair) in listOf("fa" to "فارسی", "en" to "English").withIndex()) {
            if (i > 0) langRow.addView(Neo.text(this, "  |  ", 9, Neo.DIM))
            val tv = Neo.text(this, pair.second, 9, Neo.ACCENT)
            if (Lang.code == pair.first) tv.paintFlags = tv.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            tv.setOnClickListener {
                Prefs.putStr(this, "lang", pair.first)
                Prefs.putInt(this, "lang_sys", 0)
                Neo.changed(this)
                recreate()
            }
            langRow.addView(tv)
        }
        Neo.add(card, langRow, 14)
    }

    private fun register(pw: String, phone: String, key: String) {
        if (busy) return
        busy = true
        status.setTextColor(Neo.DIM)
        status.text = tr("Creating your account…", "در حال ساخت حساب…")
        Thread {
            val r = try {
                deleteDatabase(Db.NAME)
                Vault.storeSalt(this, Vault.newSalt())
                Prefs.putStr(this, "lock_type", LockType.PASS)
                SessionVault.unlock(Vault.deriveDbKey(this, pw))
                Db.get(this).db()
                Db.get(this).setMeta("my_key", key)
                Online.setMyPhone(this, phone)
                OK
            } catch (e: Exception) { ERROR }
            ui.post {
                busy = false
                if (r == OK) { keepKey = null; openNext() } else err(tr("Something went wrong", "مشکلی پیش آمد"))
            }
        }.start()
    }

    private fun sendDialog(key: String) {
        Dialogs.choose(this, tr("Send my key", "ارسال کلید من"),
            arrayOf(tr("JSON file", "فایل json"), tr("QR code", "کد QR")), -1) { w ->
            if (w == 0) {
                Dialogs.confirm(this, tr("This contains your KEY. Send it only to the person you will talk with. Continue?",
                    "این «کلید من» را دارد. فقط به همان مخاطب بفرست. ادامه؟")) {
                    Files.share(this, Files.exportKeyJson(this, key), "application/json")
                }
            } else {
                showQr(key)
            }
        }
    }

    private fun showQr(key: String) {
        val iv = ImageView(this)
        iv.setImageBitmap(QrTools.render(key, Neo.dp(this, 260), true))
        val p = Neo.dp(this, 16)
        iv.setPadding(p, p, p, p)
        Neo.dialog(this).setTitle(tr("My key (QR)", "کلید من (QR)")).setView(iv)
            .setPositiveButton(tr("Send image", "ارسال تصویر")) { _, _ ->
                val f = Files.exportKeyPng(this, key)
                if (f != null) Files.share(this, f, "image/png")
            }
            .setNegativeButton(tr("Close", "بستن"), null).show()
    }

    // ---------- login ----------
    private fun buildLogin() {
        val card = shell()
        card.addView(PulseTitle(this, "Shadow"))
        Neo.add(card, Neo.text(this, LockType.loginHint(type), 9, Neo.DIM), 10)
        val holder = Neo.vertical(this)
        Neo.add(card, holder, 12)
        status = Neo.text(this, "", 10, Neo.DIM)

        when (type) {
            LockType.PASS -> {
                val f = FloatField(this, tr("Password", "رمز عبور")) { it.isNotEmpty() }
                f.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                f.edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                passField = f
                Neo.add(holder, f, 0)
                val b = Neo.primary(this, tr("Log in", "ورود"))
                Neo.add(holder, b, 12)
                val go = { val s = f.text(); if (s.isNotEmpty()) attempt(s) }
                b.setOnClickListener { go() }
                f.edit.setOnEditorActionListener { _, _, _ -> go(); true }
            }
            LockType.BIO -> {
                val b = Neo.primary(this, tr("Unlock", "باز کردن"))
                Neo.add(holder, b, 4)
                b.setOnClickListener { askBio() }
                askBio()
            }
            else -> {
                val p = SecretPad(this, type, tr("Log in", "ورود")) { s -> attempt(s) }
                pad = p
                holder.addView(p.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        Neo.add(card, status, 10)
    }

    private fun askBio() {
        if (!AppLock.available(this)) {
            err(tr("Set a screen lock on your phone first", "اول در گوشی قفل صفحه را فعال کنید"))
            return
        }
        AppLock.prompt(this, { attempt("") }, { m -> err(m) })
    }

    private fun attempt(secret: String) {
        if (busy) return
        val wait = Prefs.long(this, "lock_until", 0) - System.currentTimeMillis()
        if (wait > 0) {
            err(tr("Locked, wait ", "قفل شده، صبر کنید ") + (wait / 1000 + 1) + " s")
            clearInputs()
            return
        }
        busy = true
        status.setTextColor(Neo.DIM)
        status.text = tr("Checking…", "در حال بررسی…")
        Thread {
            val r = try {
                if (type != LockType.BIO && Duress.matches(this, secret)) {
                    // Duress secret: wipe everything, open a normal-looking EMPTY vault.
                    Duress.wipeAll(this)
                    deleteDatabase(Db.NAME)
                    Vault.storeSalt(this, Vault.newSalt())
                    Prefs.putStr(this, "lock_type", type)
                    SessionVault.unlock(Vault.deriveDbKey(this, secret))
                    Db.get(this).db()
                    OK
                } else if (Db.open(this, Vault.deriveDbKey(this, secret))) {
                    Prefs.putInt(this, "fails", 0)
                    OK
                } else WRONG
            } catch (e: Exception) { ERROR }
            ui.post { busy = false; done(r) }
        }.start()
    }

    private fun clearInputs() {
        pad?.clear()
        passField?.edit?.setText("")
    }

    private fun done(r: Int) {
        if (r == OK) { openNext(); return }
        clearInputs()
        if (r == WRONG) {
            val fails = Prefs.int(this, "fails", 0) + 1
            Prefs.putInt(this, "fails", fails)
            if (fails >= 3) {
                val delay = minOf(5000L shl minOf(fails - 3, 8), 15 * 60 * 1000L)
                Prefs.putLong(this, "lock_until", System.currentTimeMillis() + delay)
            }
            err(tr("Access denied", "دسترسی رد شد"))
        } else err(tr("Error", "خطا"))
    }

    private fun err(m: String) {
        status.setTextColor(Neo.RED)
        status.text = m
    }

    private fun openNext() {
        startActivity(Intent(this, ChatListActivity::class.java))
        finish()
    }
}
