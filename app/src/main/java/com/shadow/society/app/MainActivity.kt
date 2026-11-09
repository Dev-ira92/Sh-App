package com.shadow.society.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * First run: choose how to log in (PIN / password / fingerprint), set it, enter your phone number
 * (it becomes your online ID), then the "My key" page opens. Next runs: unlock with the chosen type.
 */
class MainActivity : AppCompatActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var holder: LinearLayout
    private var pad: SecretPad? = null
    private var first = false
    private var type = LockType.PASS
    private var firstSecret: String? = null
    private var pendingSecret = ""
    private var busy = false

    private companion object {
        const val OK = 0
        const val WRONG = 1
        const val ERROR = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Neo.styleWindow(this)
        if (Root.isRooted(this)) {
            val t = Neo.text(this, tr("> rooted device detected. access denied_", "> دستگاه روت شده شناسایی شد. دسترسی ممنوع_"), 16, Neo.RED)
            t.gravity = Gravity.CENTER
            setContentView(t)
            return
        }
        if (SessionVault.isUnlocked()) { openNext(false); return }
        first = !Vault.exists(this)
        buildShell()
        if (first) {
            showChooser()
        } else {
            type = Vault.lockType(this)
            startLogin()
        }
    }

    private fun langRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        for ((code, name) in listOf("fa" to "فارسی", "en" to "English")) {
            val b = if (Lang.code == code) Neo.primary(this, name) else Neo.button(this, name)
            b.textSize = 13f
            b.setOnClickListener {
                Prefs.putStr(this, "lang", code)
                Prefs.putInt(this, "lang_sys", 0)
                Neo.changed(this)
                recreate()
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(Neo.dp(this, 4), 0, Neo.dp(this, 4), 0)
            row.addView(b, lp)
        }
        return row
    }

    private fun buildShell() {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        val wrap = LinearLayout(this)
        wrap.gravity = Gravity.CENTER
        wrap.orientation = LinearLayout.VERTICAL
        val pad0 = Neo.dp(this, 24)
        wrap.setPadding(pad0, pad0, pad0, pad0)

        val card = Neo.card(this)

        val lg = ImageView(this)
        lg.setImageDrawable(Icons.drawable(this, "shield", Neo.ACCENT, 56, 1f))
        val a = ObjectAnimator.ofFloat(lg, "alpha", 0.55f, 1f)
        a.duration = 1400
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.start()
        card.addView(lg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val title = Neo.glow(Neo.text(this, "Shadow", 34, Neo.ACCENT))
        title.typeface = Neo.BOLD
        title.gravity = Gravity.CENTER
        card.addView(title)

        if (first) Neo.add(card, langRow(), 14)

        info = Neo.text(this, "", 14, Neo.TEXT)
        info.gravity = Gravity.CENTER
        Neo.add(card, info, 18)

        holder = Neo.vertical(this)
        Neo.add(card, holder, 10)

        status = Neo.text(this, "", 12, Neo.DIM)
        status.gravity = Gravity.CENTER
        Neo.add(card, status, 10)

        wrap.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sv.addView(wrap, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(Neo.backdrop(this, sv, 0.8f))
    }

    private fun setPad(p: SecretPad) {
        pad = p
        holder.removeAllViews()
        holder.addView(p.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---------- first run ----------
    private fun showChooser() {
        info.text = tr("How do you want to log in? (you can change it later in Settings)",
            "چطور می‌خواهی وارد برنامه شوی؟ (بعداً از تنظیمات قابل تغییر است)")
        holder.removeAllViews()
        for (t in LockType.CHOICES) {
            val b = Neo.primary(this, LockType.label(t))
            Neo.add(holder, b, 8)
            b.setOnClickListener { choose(t) }
        }
    }

    private fun choose(t: String) {
        type = t
        if (t == LockType.BIO) {
            if (!AppLock.available(this)) {
                err(tr("Set a screen lock / fingerprint on your phone first", "اول در گوشی قفل صفحه یا اثر انگشت را فعال کن"))
                return
            }
            AppLock.prompt(this, { showPhone("") }, { m -> err(m) })
        } else {
            firstSecret = null
            showEnter(false)
        }
    }

    private fun showEnter(confirmStep: Boolean) {
        info.text = if (confirmStep) tr("Repeat to confirm", "برای تأیید دوباره وارد کنید") else LockType.hint(type)
        val label = if (confirmStep) tr("Continue", "ادامه") else tr("Continue", "ادامه")
        setPad(SecretPad(this, type, label) { s -> onNew(s, confirmStep) })
    }

    private fun onNew(s: String, confirmStep: Boolean) {
        if (!confirmStep) {
            val e = LockType.check(type, s)
            if (e != null) { err(e); pad?.clear(); return }
            firstSecret = s
            status.text = ""
            showEnter(true)
        } else {
            if (s == firstSecret) {
                status.text = ""
                showPhone(s)
            } else {
                firstSecret = null
                err(tr("Did not match. Start again.", "یکسان نبود. از نو."))
                showEnter(false)
            }
        }
    }

    /** Step 2: my phone number. It becomes my online ID and is saved inside the encrypted vault. */
    private fun showPhone(secret: String) {
        pendingSecret = secret
        status.text = ""
        info.text = tr("Your phone number. It is registered as your online ID.", "شماره‌ی موبایل تو. به‌عنوان شناسه‌ی آنلاین ثبت می‌شود.")
        holder.removeAllViews()
        val e = Neo.input(this, tr("phone number (0912… or +98912…)", "شماره‌ی موبایل (۰۹۱۲… یا ۹۸+۹۱۲…)"))
            .apply { inputType = InputType.TYPE_CLASS_PHONE }
        Neo.add(holder, e, 4)
        val b = Neo.primary(this, tr("Enter the app", "ورود به برنامه"))
        Neo.add(holder, b, 12)
        b.setOnClickListener {
            val s = e.text.toString().trim()
            if (Phones.tail(s).length < 6) err(tr("Enter a valid number", "یک شماره‌ی معتبر وارد کن")) else finishCreate(pendingSecret, s)
        }
    }

    private fun finishCreate(secret: String, phone: String) {
        if (busy) return
        busy = true
        status.setTextColor(Neo.DIM)
        status.text = "> " + tr("preparing…", "در حال آماده‌سازی…")
        Thread {
            val r = try { create(type, secret); Online.setMyPhone(this, phone); OK } catch (e: Exception) { ERROR }
            ui.post { busy = false; done(r) }
        }.start()
    }

    private fun create(t: String, secret: String) {
        deleteDatabase(Db.NAME)
        Vault.storeSalt(this, Vault.newSalt())
        Prefs.putStr(this, "lock_type", t)
        SessionVault.unlock(Vault.deriveDbKey(this, secret))
        Db.get(this).db()
    }

    // ---------- login ----------
    private fun startLogin() {
        holder.removeAllViews()
        if (type == LockType.BIO) {
            info.text = tr("Verify it's you", "هویت خود را تأیید کنید")
            val b = Neo.primary(this, tr("Unlock", "باز کردن"))
            Neo.add(holder, b, 8)
            b.setOnClickListener { askBio() }
            askBio()
        } else {
            info.text = LockType.loginHint(type)
            setPad(SecretPad(this, type, tr("Enter the app", "ورود به برنامه")) { s -> attempt(s) })
        }
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
            err(tr("locked, wait ", "قفل شده، صبر کنید ") + (wait / 1000 + 1) + " s")
            pad?.clear()
            return
        }
        busy = true
        status.setTextColor(Neo.DIM)
        status.text = "> " + tr("checking…", "در حال بررسی…")
        Thread {
            val r = try {
                if (type != LockType.BIO && Duress.matches(this, secret)) {
                    // Duress secret: wipe everything, open a normal-looking EMPTY vault.
                    Duress.wipeAll(this)
                    create(type, secret)
                    OK
                } else if (Db.open(this, Vault.deriveDbKey(this, secret))) {
                    Prefs.putInt(this, "fails", 0)
                    OK
                } else WRONG
            } catch (e: Exception) { ERROR }
            ui.post { busy = false; done(r) }
        }.start()
    }

    private fun done(r: Int) {
        if (r == OK) { openNext(first); return }
        pad?.clear()
        if (r == WRONG) {
            val fails = Prefs.int(this, "fails", 0) + 1
            Prefs.putInt(this, "fails", fails)
            if (fails >= 3) {
                val delay = minOf(5000L shl minOf(fails - 3, 8), 15 * 60 * 1000L)
                Prefs.putLong(this, "lock_until", System.currentTimeMillis() + delay)
            }
            err(tr("access denied", "دسترسی رد شد"))
        } else err(tr("error", "خطا"))
    }

    private fun err(m: String) {
        status.setTextColor(Neo.RED)
        status.text = "> $m"
    }

    private fun openNext(showKey: Boolean) {
        val i = Intent(this, if (showKey) KeyActivity::class.java else ChatListActivity::class.java)
        if (showKey) i.putExtra("first", true)
        startActivity(i)
        finish()
    }
}
