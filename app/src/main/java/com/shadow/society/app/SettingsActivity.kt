package com.shadow.society.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

class SettingsActivity : BaseActivity() {
    private val lockSecs = intArrayOf(0, 15, 30, 60, 300)
    private val sizes = intArrayOf(100, 120, 150, 180, 220)

    private lateinit var lockTypeBtn: Button
    private lateinit var keyClearBtn: Button
    private lateinit var pulseBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var lockdownBtn: Button
    private lateinit var simBtn: Button
    private lateinit var duressBtn: Button

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

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

        // ---- Lock ----
        col.addView(Neo.section(this, tr("APP LOCK", "قفل برنامه")))
        lockTypeBtn = Neo.button(this, "")
        Neo.add(col, lockTypeBtn, 4)
        lockTypeBtn.setOnClickListener { changeLock() }
        lockBtn = Neo.button(this, "")
        Neo.add(col, lockBtn, 8)
        lockBtn.setOnClickListener {
            val labels = Array(lockSecs.size) { lockLabel(lockSecs[it]) }
            Dialogs.choose(this, tr("Lock the app after leaving it", "قفل برنامه پس از خروج"), labels, -1) { w ->
                Prefs.putInt(this, "autolock", lockSecs[w])
                refresh()
            }
        }

        // ---- My key ----
        col.addView(Neo.section(this, tr("MY KEY", "کلید من")))
        val keyBtn = Neo.button(this, tr("Open my key page (copy / send / QR)", "صفحه‌ی کلید من (کپی / ارسال / QR)"))
        Neo.add(col, keyBtn, 4)
        keyBtn.setOnClickListener { startActivity(Intent(this, KeyActivity::class.java)) }

        // ---- Session keys ----
        col.addView(Neo.section(this, tr("SESSION KEYS", "کلیدهای جلسه")))
        col.addView(Neo.text(this, tr("Friend/session keys live only in memory. Choose when they are cleared after you leave the app.",
            "کلیدهای مخاطب فقط در حافظه‌اند. انتخاب کن بعد از خروج از برنامه کی پاک شوند."), 12, Neo.DIM))
        keyClearBtn = Neo.button(this, "")
        Neo.add(col, keyClearBtn, 8)
        keyClearBtn.setOnClickListener {
            val labels = Array(KeyPolicy.OPTIONS.size) { KeyPolicy.label(KeyPolicy.OPTIONS[it]) }
            Dialogs.choose(this, tr("Clear keys", "پاک شدن کلیدها"), labels, -1) { w ->
                KeyPolicy.set(this, KeyPolicy.OPTIONS[w])
                refresh()
            }
        }
        pulseBtn = Neo.button(this, "")
        Neo.add(col, pulseBtn, 8)
        pulseBtn.setOnClickListener {
            val labels = Array(Pulse.HOURS.size) { Pulse.label(Pulse.HOURS[it]) }
            Dialogs.choose(this, tr("Dragon's Pulse (maximum life of a key)", "نبض اژدها (حداکثر عمر کلید)"), labels, -1) { w ->
                Pulse.set(this, Pulse.HOURS[w])
                refresh()
            }
        }

        // ---- SIM ----
        col.addView(Neo.section(this, tr("SIM CARD", "سیم‌کارت")))
        col.addView(Neo.text(this, tr("Default SIM for sending. You can also pick a SIM inside each chat (SIM button).",
            "سیم‌کارت پیش‌فرض برای ارسال. داخل هر چت هم با دکمه‌ی SIM می‌توانی انتخاب کنی."), 12, Neo.DIM))
        simBtn = Neo.button(this, "")
        Neo.add(col, simBtn, 8)
        simBtn.setOnClickListener { Sims.choose(this, "sim", false) { refresh() } }

        // ---- Duress ----
        col.addView(Neo.section(this, tr("DURESS CODE", "کد اضطراری")))
        col.addView(Neo.text(this, tr("Entering this at the lock screen silently wipes everything and shows an empty, normal-looking app. Works with password, PIN and pattern locks.",
            "وارد کردن این کد در صفحه‌ی قفل، بی‌صدا همه‌چیز را پاک می‌کند و یک برنامه‌ی خالی و عادی نشان می‌دهد. برای قفل رمز، پین و الگو کار می‌کند."), 12, Neo.DIM))
        duressBtn = Neo.button(this, "")
        Neo.add(col, duressBtn, 8)
        duressBtn.setOnClickListener { setDuress() }

        // ---- Lockdown ----
        col.addView(Neo.section(this, tr("LOCKDOWN", "حالت قفل‌شده")))
        col.addView(Neo.text(this, tr("Disguises the app as \"Calculator\" and hides it from Recents.",
            "برنامه را به شکل «ماشین‌حساب» درمی‌آورد و از لیست اخیر پنهان می‌کند."), 12, Neo.DIM))
        lockdownBtn = Neo.button(this, "")
        Neo.add(col, lockdownBtn, 8)
        lockdownBtn.setOnClickListener {
            Lockdown.set(this, !Lockdown.isOn(this))
            toast(tr("Launcher icon will change in a few seconds", "آیکن چند ثانیه‌ی دیگر عوض می‌شود"))
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
        lockTypeBtn.text = tr("Lock type: ", "نوع قفل: ") + LockType.label(Vault.lockType(this)) + tr("  (change)", "  (تغییر)")
        lockBtn.text = tr("Lock app after leaving: ", "قفل برنامه پس از خروج: ") + lockLabel(Prefs.int(this, "autolock", 60))
        keyClearBtn.text = tr("Clear keys: ", "پاک شدن کلیدها: ") + KeyPolicy.label(KeyPolicy.minutes(this))
        pulseBtn.text = tr("Max key life: ", "حداکثر عمر کلید: ") + Pulse.label(Pulse.hours(this))
        simBtn.text = tr("Default SIM: ", "سیم‌کارت پیش‌فرض: ") + Sims.label(this, Prefs.int(this, "sim", -1))
        duressBtn.text = if (Duress.isSet(this)) tr("Duress code: set (tap to change)", "کد اضطراری: تنظیم شده (تغییر)")
                         else tr("Duress code: not set (tap to set)", "کد اضطراری: تنظیم نشده (تنظیم)")
        lockdownBtn.text = if (Lockdown.isOn(this)) tr("lockdown: ON — tap to disable", "حالت قفل: روشن")
                           else tr("lockdown: OFF — tap to enable", "حالت قفل: خاموش")
    }

    private fun changeLock() {
        val types = arrayListOf(LockType.PASS, LockType.PIN, LockType.PATTERN)
        if (AppLock.available(this)) types.add(LockType.BIO)
        val labels = Array(types.size) { LockType.label(types[it]) }
        Dialogs.choose(this, tr("New lock type", "نوع قفل جدید"), labels, types.indexOf(Vault.lockType(this))) { w ->
            val t = types[w]
            if (t == LockType.BIO) {
                AppLock.prompt(this, { applyLock(t, "") }, { m -> toast(m) })
            } else {
                askSecret(this, t, tr("New lock", "قفل جدید"), true) { s -> applyLock(t, s) }
            }
        }
    }

    private fun applyLock(t: String, secret: String) {
        Thread {
            val ok = Vault.changeLock(this, t, secret)
            if (ok) Duress.clear(this)
            runOnUiThread {
                toast(if (ok) tr("Lock changed (duress code reset)", "قفل تغییر کرد (کد اضطراری پاک شد)") else tr("Failed", "ناموفق"))
                refresh()
            }
        }.start()
    }

    private fun setDuress() {
        val t = Vault.lockType(this)
        if (t == LockType.BIO) {
            toast(tr("Needs a password, PIN or pattern lock", "نیاز به قفل رمز، پین یا الگو دارد"))
            return
        }
        askSecret(this, t, tr("Duress code", "کد اضطراری"), true) { s ->
            Thread {
                val same = Vault.deriveDbKey(this, s) == SessionVault.dbKey()
                if (!same) Duress.set(this, s)
                runOnUiThread {
                    toast(if (same) tr("Must differ from your lock", "باید با قفل اصلی فرق داشته باشد") else tr("Saved", "ذخیره شد"))
                    refresh()
                }
            }.start()
        }
    }
}
