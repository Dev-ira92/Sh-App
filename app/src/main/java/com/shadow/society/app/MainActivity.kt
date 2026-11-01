package com.shadow.society.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Login / vault creation.
 * The LOGIN PASSPHRASE only opens the vault (it derives the database key) and is never shared.
 * MY KEY is a different secret: you give it to your friends in person; it is part of every AES key.
 */
class MainActivity : AppCompatActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var pass: EditText
    private lateinit var confirm: EditText
    private lateinit var go: Button
    private lateinit var status: TextView
    private var first = false
    private var chosenKey = Crypto.randomKey(64)
    private val keyLens = intArrayOf(10, 50, 100, 500, 1000)
    private lateinit var keyView: TextView

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
        if (SessionVault.isUnlocked()) { openList(); return }
        first = !Vault.exists(this)

        val sv = ScrollView(this)
        sv.isFillViewport = true
        val wrap = LinearLayout(this)
        wrap.gravity = Gravity.CENTER
        wrap.orientation = LinearLayout.VERTICAL
        val pad = Neo.dp(this, 24)
        wrap.setPadding(pad, pad, pad, pad)

        val card = Neo.vertical(this)
        card.background = Neo.box(this, Neo.withAlpha(Neo.BG, 0xDD), Neo.DIM, 10)
        card.setPadding(pad, pad, pad, pad)

        val title = Neo.text(this, "Sh-So", 36, Neo.ACCENT)
        title.typeface = Neo.BOLD
        title.gravity = Gravity.CENTER
        card.addView(title)

        val slogan = Neo.text(this, "", 13, Neo.DIM)
        slogan.gravity = Gravity.CENTER
        card.addView(slogan)
        typeText(slogan, "> " + tr("The dragon that breathes in the dark.", "اژدهایی که در تاریکی نفس می‌کشد.") + "_", 0)

        pass = Neo.input(this, tr("login passphrase", "رمز ورود"))
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        Neo.add(card, pass, 22)
        confirm = Neo.input(this, tr("repeat passphrase", "تکرار رمز"))
        confirm.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (first) {
            Neo.add(card, confirm, 8)
            Neo.add(card, Neo.text(this, tr("MY KEY — give it to your friends in person (this is NOT your login password)",
                "کلید من — حضوری به دوستانت بده (این رمز ورود نیست)"), 12, Neo.DIM), 18)
            keyView = Neo.text(this, chosenKey, 12, Neo.TEXT)
            keyView.setTextIsSelectable(true)
            Neo.add(card, keyView, 4)
            val gen = Neo.button(this, tr("🎲 generate new key", "🎲 ساخت کلید جدید"))
            Neo.add(card, gen, 8)
            gen.setOnClickListener {
                Dialogs.choose(this, tr("Key length", "طول کلید"),
                    Array(keyLens.size) { keyLens[it].toString() + " " + tr("characters", "کاراکتر") }, -1) { w ->
                    chosenKey = Crypto.randomKey(keyLens[w])
                    keyView.text = chosenKey
                }
            }
        }

        go = Neo.button(this, if (first) tr("create vault", "ساخت گاوصندوق") else tr("enter", "ورود"))
        Neo.add(card, go, 14)
        status = Neo.text(this,
            if (first) tr("min 8 characters. use a long phrase.", "حداقل ۸ کاراکتر. یک جمله‌ی بلند بهتر است.") else "", 12, Neo.DIM)
        Neo.add(card, status, 10)

        wrap.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sv.addView(wrap, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(Neo.backdrop(this, sv, 0.8f))

        go.setOnClickListener { submit() }
    }

    private fun typeText(t: TextView, s: String, i: Int) {
        if (i > s.length) return
        t.text = s.substring(0, i)
        ui.postDelayed({ typeText(t, s, i + 1) }, 55)
    }

    private fun submit() {
        val p = pass.text.toString()
        if (first) {
            if (p.length < 8) { err(tr("too short", "کوتاه است")); return }
            if (p != confirm.text.toString()) { err(tr("passphrases differ", "رمزها یکسان نیستند")); return }
        }
        val wait = Prefs.long(this, "lock_until", 0) - System.currentTimeMillis()
        if (wait > 0) { err(tr("locked, wait ", "قفل شده، صبر کنید ") + (wait / 1000 + 1) + " s"); return }

        go.isEnabled = false
        status.setTextColor(Neo.DIM)
        status.text = "> " + tr("deriving keys...", "در حال ساخت کلیدها...")
        Thread {
            val r = attempt(p)
            ui.post { done(r) }
        }.start()
    }

    private fun attempt(p: String): Int = try {
        when {
            first -> { deleteDatabase(Db.NAME); create(p); OK }
            // Duress code: wipe everything, open a normal-looking EMPTY vault.
            Duress.matches(this, p) -> { Duress.wipeAll(this); create(p); OK }
            else -> {
                val key = Vault.deriveDbKey(this, p)
                if (Db.open(this, key)) {
                    Prefs.putInt(this, "fails", 0)
                    Vault.myKey(this)
                    OK
                } else WRONG
            }
        }
    } catch (e: Exception) { ERROR }

    private fun create(p: String) {
        Vault.createSalt(this)
        SessionVault.unlock(Vault.deriveDbKey(this, p))
        Db.get(this).db()
        if (first) Db.get(this).setMeta("my_key", chosenKey) else Vault.myKey(this)
    }

    private fun done(r: Int) {
        go.isEnabled = true
        if (r == OK) { openList(); return }
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

    private fun openList() {
        startActivity(Intent(this, ChatListActivity::class.java))
        finish()
    }
}
