package com.shadow.society.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Lock screen. First run: choose a lock type (password / PIN / pattern / phone fingerprint-face-lock),
 * set it, then the "My key" page opens. Next runs: unlock with the chosen type.
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
        if (SessionVault.isUnlocked()) { openList(false); return }
        first = !Vault.exists(this)
        buildShell()
        if (first) {
            showChooser()
        } else {
            type = Vault.lockType(this)
            startLogin()
        }
    }

    private fun buildShell() {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        val wrap = LinearLayout(this)
        wrap.gravity = Gravity.CENTER
        wrap.orientation = LinearLayout.VERTICAL
        val pad0 = Neo.dp(this, 24)
        wrap.setPadding(pad0, pad0, pad0, pad0)

        val card = Neo.vertical(this)
        card.background = Neo.box(this, Neo.withAlpha(Neo.BG, 0xDD), Neo.DIM, 10)
        card.setPadding(pad0, pad0, pad0, pad0)

        val lg = ImageView(this)
        lg.setImageDrawable(Icons.drawable(this, "shield", Neo.ACCENT, 56, 1f))
        val a = ObjectAnimator.ofFloat(lg, "alpha", 0.45f, 1f)
        a.duration = 1400
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.start()
        card.addView(lg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val title = Neo.glow(Neo.text(this, "Sh-So", 36, Neo.ACCENT))
        title.typeface = Neo.BOLD
        title.gravity = Gravity.CENTER
        card.addView(title)

        val slogan = Neo.text(this, "", 13, Neo.DIM)
        slogan.gravity = Gravity.CENTER
        card.addView(slogan)
        typeText(slogan, "> " + tr("The dragon that breathes in the dark.", "اژدهایی که در تاریکی نفس می‌کشد.") + "_", 0)

        info = Neo.text(this, "", 14, Neo.TEXT)
        info.gravity = Gravity.CENTER
        Neo.add(card, info, 20)

        holder = Neo.vertical(this)
        Neo.add(card, holder, 10)

        status = Neo.text(this, "", 12, Neo.DIM)
        status.gravity = Gravity.CENTER
        Neo.add(card, status, 10)

        wrap.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sv.addView(wrap, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(Neo.backdrop(this, sv, 0.8f))
    }

    private fun typeText(t: TextView, s: String, i: Int) {
        if (i > s.length) return
        t.text = s.substring(0, i)
        ui.postDelayed({ typeText(t, s, i + 1) }, 55)
    }

    private fun setPad(p: SecretPad) {
        pad = p
        holder.removeAllViews()
        holder.addView(p.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---------- first run ----------
    private fun showChooser() {
        info.text = tr("How do you want to unlock the app? (you can change it later in Settings)",
            "چطور می‌خواهی وارد برنامه شوی؟ (بعداً از تنظیمات قابل تغییر است)")
        holder.removeAllViews()
        val types = arrayListOf(LockType.PASS, LockType.PIN, LockType.PATTERN)
        if (AppLock.available(this)) types.add(LockType.BIO)
        for (t in types) {
            val b = Neo.button(this, LockType.label(t))
            Neo.add(holder, b, 8)
            b.setOnClickListener { choose(t) }
        }
        if (!AppLock.available(this)) {
            Neo.add(holder, Neo.text(this, tr("Fingerprint / face needs a screen lock on the phone.",
                "اثر انگشت / چهره نیاز به قفل صفحه در گوشی دارد."), 11, Neo.DIM), 10)
        }
    }

    private fun choose(t: String) {
        type = t
        if (t == LockType.BIO) {
            AppLock.prompt(this, { finishCreate("") }, { m -> err(m) })
        } else {
            firstSecret = null
            showEnter(false)
        }
    }

    private fun showEnter(confirmStep: Boolean) {
        info.text = if (confirmStep) tr("Repeat to confirm", "برای تأیید دوباره وارد کنید") else LockType.hint(type)
        val label = if (confirmStep) tr("Enter the app", "ورود به برنامه") else tr("Continue", "ادامه")
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
                finishCreate(s)
            } else {
                firstSecret = null
                err(tr("Did not match. Start again.", "یکسان نبود. از نو."))
                showEnter(false)
            }
        }
    }

    private fun finishCreate(secret: String) {
        if (busy) return
        busy = true
        status.setTextColor(Neo.DIM)
        status.text = "> " + tr("deriving keys...", "در حال ساخت کلیدها...")
        Thread {
            val r = try { create(type, secret); OK } catch (e: Exception) { ERROR }
            ui.post { busy = false; done(r) }
        }.start()
    }

    private fun create(t: String, secret: String) {
        deleteDatabase(Db.NAME)
        Vault.storeSalt(this, Vault.newSalt())
        Prefs.putStr(this, "lock_type", t)
        SessionVault.unlock(Vault.deriveDbKey(this, secret))
        Db.get(this).db()
        Vault.myKey(this)
    }

    // ---------- login ----------
    private fun startLogin() {
        holder.removeAllViews()
        if (type == LockType.BIO) {
            info.text = tr("Verify it's you", "هویت خود را تأیید کنید")
            val b = Neo.button(this, tr("Unlock", "باز کردن"))
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
        status.text = "> " + tr("deriving keys...", "در حال ساخت کلیدها...")
        Thread {
            val r = try {
                if (type != LockType.BIO && Duress.matches(this, secret)) {
                    // Duress secret: wipe everything, open a normal-looking EMPTY vault.
                    Duress.wipeAll(this)
                    create(type, secret)
                    OK
                } else if (Db.open(this, Vault.deriveDbKey(this, secret))) {
                    Prefs.putInt(this, "fails", 0)
                    Vault.myKey(this)
                    OK
                } else WRONG
            } catch (e: Exception) { ERROR }
            ui.post { busy = false; done(r) }
        }.start()
    }

    private fun done(r: Int) {
        if (r == OK) { openList(first); return }
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

    private fun openList(showKey: Boolean) {
        val i = Intent(this, if (showKey) KeyActivity::class.java else ChatListActivity::class.java)
        if (showKey) i.putExtra("first", true)
        startActivity(i)
        finish()
    }
}
