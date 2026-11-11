package com.shadow.society.app

import android.content.Intent
import android.graphics.Color
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * First run: the sign-up page (phone with country code, password + repeat with an eye, my key, optional server,
 * Register). Next runs: the login page (password / PIN / fingerprint, depending on Settings).
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
        val p = Neo.dp(this, 16)
        wrap.setPadding(p, p, p, p)
        val card = Neo.card(this)
        wrap.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sv.addView(wrap, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(Neo.backdrop(this, sv))
        return card
    }

    private fun body(s: String): TextView = Neo.text(this, s, 11, Neo.DIM)

    // ---------- sign up ----------
    private fun buildRegister() {
        val card = shell()
        card.addView(PulseTitle(this, "Shadow"))
        Neo.add(card, body(tr("Create your account. Your phone number is your online ID.",
            "ثبت‌نام کن. شماره‌ی موبایلت شناسه‌ی آنلاین توست.")), 12)

        // phone: country code (pick from the list) + number
        val code = FloatField(this, tr("Country code", "کد کشور")) { Regex("\\+\\d{1,4}").matches(it.trim()) }
        val phone = FloatField(this, tr("Phone number", "شماره تماس")) { Phones.digits(it).length >= 6 }
        phone.edit.inputType = InputType.TYPE_CLASS_PHONE
        val countryCap = Neo.text(this, "", 10, Neo.DIM)
        fun setCountry(c: Countries.Country) {
            code.edit.setText("+" + c.code)
            countryCap.text = c.flag + "  " + c.name
        }
        code.readOnly { pickCountry(this) { c -> setCountry(c) } }
        setCountry(Countries.find(Locale.getDefault().country) ?: Countries.find("IR")!!)
        Neo.add(card, formRow(this, code to 0.42f, phone to 0.58f), 14)
        Neo.add(card, countryCap, 4)

        // password + repeat (one eye shows / hides both)
        val pass = FloatField(this, tr("Password", "رمز عبور")) { it.length >= 8 }
        val pass2 = FloatField(this, tr("Repeat password", "تکرار رمز")) { it.length >= 8 && it == pass.text() }
        pass2.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        pass.password(pass2)
        pass.edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { pass2.refresh() }
        })
        Neo.add(card, formRow(this, pass to 1f, pass2 to 1f), 12)
        Neo.add(card, Neo.text(this, tr("At least 8 characters. Tap the eye to see them.", "حداقل ۸ کاراکتر. با زدن چشم رمز را ببین."), 9, Neo.DIM), 4)

        // my key: created here, one line shown
        var key = keepKey ?: Crypto.hex(Crypto.random(32))
        keepKey = key
        Neo.add(card, Neo.text(this, tr("My key", "کلید من"), 12, Neo.ACCENT).apply { typeface = Neo.BOLD }, 16)
        val keyView = TextView(this)
        keyView.typeface = Typeface.MONOSPACE
        keyView.textSize = 15f * (Neo.fs / 1.5f)
        keyView.setTextColor(Neo.TEXT)
        keyView.setSingleLine(true)
        keyView.ellipsize = TextUtils.TruncateAt.MIDDLE
        keyView.background = Neo.box(this, Color.WHITE, Neo.BORDER, 10)
        val kp = Neo.dp(this, 12)
        keyView.setPadding(kp, kp, kp, kp)
        keyView.text = key
        Neo.add(card, keyView, 6)

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

        // online (optional)
        Neo.add(card, Neo.text(this, tr("Online (optional)", "آنلاین (اختیاری)"), 12, Neo.ACCENT).apply { typeface = Neo.BOLD }, 20)
        Neo.add(card, body(tr("To chat online and use all the features, enter the server address and the server password.",
            "برای ارتباط آنلاین و استفاده از تمام قابلیت‌ها، آدرس سرور و رمز سرور را وارد کنید.")), 4)
        val srvUrl = FloatField(this, tr("Server address", "آدرس سرور")) { OnlineParse.cleanUrl(it) != null }
        srvUrl.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val srvKey = FloatField(this, tr("Server password", "رمز سرور")) { s ->
            val t = s.trim()
            t.length >= 8 && t.none { it.isWhitespace() || it == '|' }
        }.password()
        Neo.add(card, srvUrl, 10)
        Neo.add(card, srvKey, 10)
        val guide = Neo.button(this, tr("Server guide", "راهنمای سرور"))
        Neo.add(card, guide, 10)
        guide.setOnClickListener { startActivity(Intent(this, ServerGuideActivity::class.java)) }

        // register
        val reg = Neo.primary(this, tr("Register", "ثبت نام"))
        reg.textSize = 20f
        val rp = Neo.dp(this, 14)
        reg.setPadding(rp, rp, rp, rp)
        Neo.add(card, reg, 20)
        status = Neo.text(this, "", 10, Neo.DIM)
        Neo.add(card, status, 8)

        reg.setOnClickListener {
            val c = code.text().trim()
            val num = Phones.digits(phone.text())
            val pw = pass.text()
            val su = srvUrl.text().trim()
            val sk = srvKey.text().trim()
            val wantsServer = su.isNotEmpty() || sk.isNotEmpty()
            val url = OnlineParse.cleanUrl(su)
            val keyOk = sk.length >= 8 && sk.none { it.isWhitespace() || it == '|' }
            val e = when {
                !Regex("\\+\\d{1,4}").matches(c) -> tr("Choose a country code", "یک کد کشور انتخاب کن")
                num.length < 6 -> tr("Enter a valid phone number", "یک شماره‌ی معتبر وارد کن")
                pw.length < 8 -> tr("Password: at least 8 characters", "رمز عبور: حداقل ۸ کاراکتر")
                pw != pass2.text() -> tr("The two passwords differ", "دو رمز یکسان نیستند")
                wantsServer && url == null -> tr("The server address must start with https://", "آدرس سرور باید با https:// شروع شود")
                wantsServer && !keyOk -> tr("Server password: at least 8 characters, no spaces", "رمز سرور: حداقل ۸ کاراکتر، بدون فاصله")
                else -> null
            }
            phone.bad(num.length < 6)
            pass.bad(pw.length < 8)
            pass2.bad(pw != pass2.text())
            srvUrl.bad(wantsServer && url == null)
            srvKey.bad(wantsServer && !keyOk)
            if (e != null) err(e)
            else register(pw, c + num.trimStart('0'), key, if (wantsServer && url != null) ServerInfo(url, sk) else null)
        }

        // language links
        val langRow = LinearLayout(this)
        langRow.gravity = Gravity.CENTER
        for ((i, pair) in listOf("fa" to "فارسی", "en" to "English").withIndex()) {
            if (i > 0) langRow.addView(Neo.text(this, "  |  ", 10, Neo.DIM))
            val tv = Neo.text(this, pair.second, 10, Neo.ACCENT)
            if (Lang.code == pair.first) tv.paintFlags = tv.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            tv.setOnClickListener {
                Prefs.putStr(this, "lang", pair.first)
                Prefs.putInt(this, "lang_sys", 0)
                Neo.changed(this)
                recreate()
            }
            langRow.addView(tv)
        }
        Neo.add(card, langRow, 16)
    }

    private fun register(pw: String, phone: String, key: String, server: ServerInfo?) {
        if (busy) return
        busy = true
        status.setTextColor(Neo.DIM)
        status.text = tr("Creating your account…", "در حال ساخت حساب…")
        Thread {
            val ok = try {
                deleteDatabase(Db.NAME)
                Vault.storeSalt(this, Vault.newSalt())
                Prefs.putStr(this, "lock_type", LockType.PASS)
                SessionVault.unlock(Vault.deriveDbKey(this, pw))
                Db.get(this).db()
                Db.get(this).setMeta("my_key", key)
                Online.setMyPhone(this, phone)
                if (server != null) Online.saveServer(this, server)
                true
            } catch (e: Exception) { false }
            ui.post {
                if (!ok) {
                    busy = false
                    err(tr("Something went wrong", "مشکلی پیش آمد"))
                } else if (server == null) {
                    busy = false
                    keepKey = null
                    openNext()
                } else {
                    status.text = tr("Registering on the server…", "در حال ثبت روی سرور…")
                    registerOnline(this) { e ->
                        busy = false
                        keepKey = null
                        if (e != null) {
                            Toast.makeText(this, tr("Account created, but online is not ready: ", "حساب ساخته شد، ولی آنلاین آماده نیست: ") + e,
                                Toast.LENGTH_LONG).show()
                        }
                        openNext()
                    }
                }
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
        Neo.add(card, body(LockType.loginHint(type)), 12)
        val holder = Neo.vertical(this)
        Neo.add(card, holder, 14)
        status = Neo.text(this, "", 10, Neo.DIM)

        when (type) {
            LockType.PASS -> {
                val f = FloatField(this, tr("Password", "رمز عبور")) { it.isNotEmpty() }.password()
                f.edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                passField = f
                Neo.add(holder, f, 0)
                val b = Neo.primary(this, tr("Log in", "ورود"))
                b.textSize = 20f
                Neo.add(holder, b, 14)
                val go = { val s = f.text(); if (s.isNotEmpty()) attempt(s) }
                b.setOnClickListener { go() }
                f.edit.setOnEditorActionListener { _, _, _ -> go(); true }
            }
            LockType.BIO -> {
                val b = Neo.primary(this, tr("Unlock", "باز کردن"))
                b.textSize = 20f
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
