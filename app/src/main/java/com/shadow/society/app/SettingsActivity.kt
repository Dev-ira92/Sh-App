package com.shadow.society.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : BaseActivity() {
    private val keyLengths = intArrayOf(10, 50, 100, 500, 1000)
    private val lockSecs = intArrayOf(0, 15, 30, 60, 300)

    private lateinit var keyView: TextView
    private lateinit var qr: ImageView
    private lateinit var pulseBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var lockdownBtn: Button
    private lateinit var duress: EditText

    private fun lockLabel(s: Int): String =
        if (s == 0) tr("immediately", "فوراً") else if (s < 60) "$s s" else "${s / 60} min"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return

        val sv = ScrollView(this)
        sv.setBackgroundColor(Neo.BG)
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 16)
        col.setPadding(p, p, p, p * 2)
        sv.addView(col)

        col.addView(Neo.text(this, "> settings_", 20, Neo.GREEN))

        // ---- My key ----
        col.addView(Neo.section(this, tr("MY KEY (give it to your friend in person)", "کلید من (حضوری به دوستت بده)")))
        keyView = Neo.text(this, "", 12, Neo.GREEN)
        keyView.setTextIsSelectable(true)
        col.addView(keyView)
        qr = ImageView(this)
        Neo.add(col, qr, 10)
        val regen = Neo.button(this, tr("[ 🎲 generate new key ]", "[ 🎲 ساخت کلید جدید ]"))
        Neo.add(col, regen, 10)
        regen.setOnClickListener {
            val labels = Array(keyLengths.size) {
                keyLengths[it].toString() + " " + tr("characters", "کاراکتر") + (if (keyLengths[it] < 50) "  ⚠" else "")
            }
            Dialogs.choose(this, tr("Key length", "طول کلید"), labels, -1) { which ->
                Dialogs.confirm(this, tr("Your friends must receive the new key again. Continue?",
                    "دوستانت باید کلید جدید را دوباره بگیرند. ادامه؟")) {
                    Vault.regenerate(this, keyLengths[which])
                    SessionVault.wipeKeys()
                    refresh()
                }
            }
        }

        // ---- Duress ----
        col.addView(Neo.section(this, tr("DURESS CODE", "کد اضطراری")))
        col.addView(Neo.text(this, tr("Typing this at login silently wipes everything and shows an empty, normal-looking app.",
            "وارد کردن این رمز در صفحه ورود، بی‌صدا همه‌چیز را پاک می‌کند و یک برنامه‌ی خالی و عادی نشان می‌دهد."), 12, Neo.DIM))
        duress = Neo.input(this,
            if (Duress.isSet(this)) tr("(set) new duress code", "(تنظیم شده) رمز جدید") else tr("duress code", "رمز اضطراری"))
        Neo.add(col, duress, 8)
        val setDuress = Neo.button(this, tr("[ save duress code ]", "[ ذخیره رمز اضطراری ]"))
        Neo.add(col, setDuress, 8)
        setDuress.setOnClickListener { saveDuress() }

        // ---- Dragon's pulse ----
        col.addView(Neo.section(this, tr("DRAGON'S PULSE", "نبض اژدها")))
        col.addView(Neo.text(this, tr("Session keys self-destruct after this time. Messages stay but become unreadable.",
            "کلیدهای جلسه بعد از این مدت نابود می‌شوند. پیام‌ها می‌مانند اما خوانا نیستند."), 12, Neo.DIM))
        pulseBtn = Neo.button(this, "")
        Neo.add(col, pulseBtn, 8)
        pulseBtn.setOnClickListener {
            val labels = Array(Pulse.HOURS.size) { Pulse.label(Pulse.HOURS[it]) }
            Dialogs.choose(this, tr("Dragon's Pulse", "نبض اژدها"), labels, -1) { w ->
                Pulse.set(this, Pulse.HOURS[w])
                refresh()
            }
        }

        // ---- Auto-lock ----
        col.addView(Neo.section(this, tr("AUTO-LOCK", "قفل خودکار")))
        lockBtn = Neo.button(this, "")
        Neo.add(col, lockBtn, 4)
        lockBtn.setOnClickListener {
            val labels = Array(lockSecs.size) { lockLabel(lockSecs[it]) }
            Dialogs.choose(this, tr("Lock after leaving the app", "قفل پس از خروج از برنامه"), labels, -1) { w ->
                Prefs.putInt(this, "autolock", lockSecs[w])
                refresh()
            }
        }

        // ---- Lockdown ----
        col.addView(Neo.section(this, tr("LOCKDOWN", "حالت قفل‌شده")))
        col.addView(Neo.text(this, tr("Disguises the app as \"Calculator\" and hides it from Recents.",
            "برنامه را به شکل «ماشین‌حساب» درمی‌آورد و از لیست اخیر پنهان می‌کند."), 12, Neo.DIM))
        lockdownBtn = Neo.button(this, "")
        Neo.add(col, lockdownBtn, 8)
        lockdownBtn.setOnClickListener {
            Lockdown.set(this, !Lockdown.isOn(this))
            Toast.makeText(this, tr("Launcher icon will change in a few seconds", "آیکن چند ثانیه‌ی دیگر عوض می‌شود"), Toast.LENGTH_LONG).show()
            refresh()
        }

        // ---- Destroy ----
        col.addView(Neo.section(this, tr("DESTROY", "نابودی")))
        val wipe = Neo.button(this, tr("[ ☠ hold to destroy everything ]", "[ ☠ نگه دارید تا همه‌چیز نابود شود ]"))
        wipe.setTextColor(Neo.RED)
        wipe.background = Neo.box(this, Neo.BG, Neo.RED, 4)
        Neo.add(col, wipe, 8)
        wipe.setOnLongClickListener {
            Duress.wipeAll(this)
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            true
        }
        setContentView(sv)
    }

    override fun onReady() { refresh() }

    private fun refresh() {
        val k = Vault.myKey(this)
        keyView.text = k
        qr.setImageBitmap(Qr.render(k, Neo.dp(this, 260)))
        pulseBtn.text = tr("pulse: ", "نبض: ") + Pulse.label(Pulse.hours(this))
        lockBtn.text = tr("lock: ", "قفل: ") + lockLabel(Prefs.int(this, "autolock", 60))
        lockdownBtn.text = if (Lockdown.isOn(this)) tr("[ lockdown: ON — tap to disable ]", "[ حالت قفل: روشن ]")
                           else tr("[ lockdown: OFF — tap to enable ]", "[ حالت قفل: خاموش ]")
    }

    private fun saveDuress() {
        val code = duress.text.toString()
        if (code.length < 8) {
            Toast.makeText(this, tr("min 8 characters", "حداقل ۸ کاراکتر"), Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val same = Vault.deriveDbKey(this, code) == SessionVault.dbKey()
            if (!same) Duress.set(this, code)
            runOnUiThread {
                Toast.makeText(this,
                    if (same) tr("must differ from your passphrase", "باید با رمز ورود فرق داشته باشد") else tr("saved", "ذخیره شد"),
                    Toast.LENGTH_SHORT).show()
                if (!same) duress.setText("")
            }
        }.start()
    }
}
