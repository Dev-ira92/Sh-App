package com.shadow.society.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class SettingsActivity : BaseActivity() {
    private val keyLengths = intArrayOf(10, 50, 100, 500, 1000)
    private val lockSecs = intArrayOf(0, 15, 30, 60, 300)
    private val sizes = intArrayOf(100, 120, 150, 180, 220)

    private lateinit var keyView: TextView
    private lateinit var qr: ImageView
    private lateinit var pulseBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var lockdownBtn: Button
    private lateinit var appLockBtn: Button
    private lateinit var duress: EditText

    private fun lockLabel(s: Int): String =
        if (s == 0) tr("immediately", "فوراً") else if (s < 60) "$s s" else "${s / 60} min"

    private fun applyAndRebuild() { Neo.changed(this); recreate() }

    private fun pick(title: String, labels: Array<String>, cb: (Int) -> Unit) = Dialogs.choose(this, title, labels, -1, cb)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return

        val outer = Neo.vertical(this)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Neo.PANEL)
        val bp = Neo.dp(this, 4)
        bar.setPadding(bp, bp, bp, bp)
        bar.addView(Icons.button(this, "back", Neo.BARTEXT) { finish() })
        val title = Neo.glow(Neo.text(this, tr("Settings", "تنظیمات"), 18, Neo.BARTEXT))
        title.typeface = Neo.BOLD
        bar.addView(title)
        outer.addView(bar)

        val sv = ScrollView(this)
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 16)
        col.setPadding(p, p / 2, p, p * 2)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- Appearance ----
        col.addView(Neo.section(this, tr("APPEARANCE", "ظاهر")))
        val langBtn = Neo.button(this, tr("Language: ", "زبان: ") + (if (Lang.code == "en") "English" else "فارسی"))
        Neo.add(col, langBtn, 4)
        langBtn.setOnClickListener {
            Dialogs.choose(this, tr("Language", "زبان"), arrayOf("فارسی", "English"), if (Lang.code == "en") 1 else 0) { w ->
                Prefs.putStr(this, "lang", if (w == 1) "en" else "fa")
                applyAndRebuild()
            }
        }
        val themeBtn = Neo.button(this, tr("Theme: ", "تم: ") + Neo.themeName(Neo.P))
        Neo.add(col, themeBtn, 8)
        themeBtn.setOnClickListener {
            val labels = Array(THEMES.size) { Neo.themeName(THEMES[it]) }
            Dialogs.choose(this, tr("Theme", "تم"), labels, THEMES.indexOf(Neo.P)) { w ->
                Prefs.putStr(this, "theme", THEMES[w].id)
                applyAndRebuild()
            }
        }
        val fontBtn = Neo.button(this, tr("Font size: ", "اندازه فونت: ") + Prefs.int(this, "font_pct", 150) + "%")
        Neo.add(col, fontBtn, 8)
        fontBtn.setOnClickListener {
            pick(tr("Font size", "اندازه فونت"), Array(sizes.size) { sizes[it].toString() + "%" }) { w ->
                Prefs.putInt(this, "font_pct", sizes[w])
                applyAndRebuild()
            }
        }
        val iconBtn = Neo.button(this, tr("Icon size: ", "اندازه آیکن: ") + Prefs.int(this, "icon_pct", 150) + "%")
        Neo.add(col, iconBtn, 8)
        iconBtn.setOnClickListener {
            pick(tr("Icon size", "اندازه آیکن"), Array(sizes.size) { sizes[it].toString() + "%" }) { w ->
                Prefs.putInt(this, "icon_pct", sizes[w])
                applyAndRebuild()
            }
        }
        val rainBtn = Neo.button(this, tr("Matrix rain: ", "بارش ماتریکس: ") +
                (if (Prefs.int(this, "rain", 1) == 1) tr("ON", "روشن") else tr("OFF", "خاموش")))
        Neo.add(col, rainBtn, 8)
        rainBtn.setOnClickListener {
            Prefs.putInt(this, "rain", if (Prefs.int(this, "rain", 1) == 1) 0 else 1)
            applyAndRebuild()
        }

        // ---- App lock ----
        col.addView(Neo.section(this, tr("APP LOCK", "قفل برنامه")))
        col.addView(Neo.text(this, tr("Fingerprint, face, pattern, PIN or password of your phone, asked before the login screen. The login passphrase is still required.",
            "اثر انگشت، چهره، الگو، پین یا رمز گوشی، پیش از صفحه‌ی ورود پرسیده می‌شود. رمز ورود برنامه هم همچنان لازم است."), 12, Neo.DIM))
        appLockBtn = Neo.button(this, "")
        Neo.add(col, appLockBtn, 8)
        appLockBtn.setOnClickListener {
            if (!AppLock.available(this)) {
                Dialogs.info(this, tr("Not available", "در دسترس نیست"),
                    tr("Set a screen lock (PIN, pattern, fingerprint or face) in your phone settings first.",
                        "اول در تنظیمات گوشی، قفل صفحه (پین، الگو، اثر انگشت یا چهره) را فعال کنید."))
            } else {
                AppLock.prompt(this, {
                    Prefs.putInt(this, "applock", if (AppLock.enabled(this)) 0 else 1)
                    refresh()
                }, { })
            }
        }

        // ---- My key ----
        col.addView(Neo.section(this, tr("MY KEY (give it to your friend)", "کلید من (به دوستت بده)")))
        keyView = Neo.text(this, "", 12, Neo.TEXT)
        keyView.setTextIsSelectable(true)
        col.addView(keyView)
        qr = ImageView(this)
        Neo.add(col, qr, 10)

        val shareBtn = Neo.button(this, tr("Send my key…", "ارسال کلید من…"))
        Neo.add(col, shareBtn, 10)
        shareBtn.setOnClickListener {
            val labels = arrayOf(tr("QR image (PNG)", "تصویر QR (PNG)"), tr("JSON file", "فایل JSON"), tr("TXT file", "فایل TXT"))
            Dialogs.choose(this, tr("Send my key as", "ارسال کلید به صورت"), labels, -1) { w ->
                Dialogs.confirm(this, tr("This file contains your KEY. Send it only to the person you will talk with, then agree on the verbal code in person.",
                    "این فایل «کلید من» را دارد. فقط به همان مخاطب بفرست و کد شفاهی را حضوری با او هماهنگ کن.")) {
                    val k = Vault.myKey(this)
                    val f: File? = when (w) {
                        0 -> Files.exportKeyPng(this, k)
                        1 -> Files.exportKeyJson(this, k)
                        else -> Files.exportKeyTxt(this, k)
                    }
                    if (f == null) {
                        Toast.makeText(this, tr("Could not create the file", "ساخت فایل ناموفق بود"), Toast.LENGTH_SHORT).show()
                    } else {
                        Files.share(this, f, if (w == 0) "image/png" else if (w == 1) "application/json" else "text/plain")
                    }
                }
            }
        }

        val regen = Neo.button(this, tr("🎲 generate new key", "🎲 ساخت کلید جدید"))
        Neo.add(col, regen, 8)
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
        val setDuress = Neo.button(this, tr("save duress code", "ذخیره رمز اضطراری"))
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
        val wipe = Neo.button(this, tr("hold to destroy everything", "نگه دارید تا همه‌چیز نابود شود"))
        wipe.setTextColor(Neo.RED)
        wipe.background = Neo.box(this, 0, Neo.RED, 8)
        Neo.add(col, wipe, 8)
        wipe.setOnLongClickListener {
            Duress.wipeAll(this)
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            true
        }
        setContentView(Neo.backdrop(this, outer))
    }

    override fun onReady() { refresh() }

    private fun refresh() {
        val k = Vault.myKey(this)
        keyView.text = k
        qr.setImageBitmap(Qr.render(k, Neo.dp(this, 260)))
        pulseBtn.text = tr("pulse: ", "نبض: ") + Pulse.label(Pulse.hours(this))
        lockBtn.text = tr("lock: ", "قفل: ") + lockLabel(Prefs.int(this, "autolock", 60))
        lockdownBtn.text = if (Lockdown.isOn(this)) tr("lockdown: ON — tap to disable", "حالت قفل: روشن")
                           else tr("lockdown: OFF — tap to enable", "حالت قفل: خاموش")
        val state = if (!AppLock.available(this)) tr("not set up on phone", "روی گوشی فعال نیست")
                    else if (AppLock.enabled(this)) tr("ON", "روشن") else tr("OFF", "خاموش")
        appLockBtn.text = tr("App lock: ", "قفل برنامه: ") + state
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
