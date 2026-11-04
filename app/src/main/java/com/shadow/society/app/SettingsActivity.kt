package com.shadow.society.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : BaseActivity() {
    private val lockSecs = intArrayOf(0, 15, 30, 60, 300)
    private val sizes = intArrayOf(100, 120, 150, 180, 220)

    private lateinit var lockTypeBtn: Button
    private lateinit var autoLockBtn: Button
    private lateinit var keysBtn: Button
    private lateinit var onlineInfo: TextView

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun lockLabel(s: Int): String =
        if (s == 0) tr("immediately", "فوراً") else if (s < 60) "$s s" else "${s / 60} min"

    private fun applyAndRebuild() { Neo.changed(this); recreate() }

    private fun btn(card: LinearLayout, label: String, top: Int, f: () -> Unit): Button {
        val b = Neo.button(this, label)
        Neo.add(card, b, top)
        b.setOnClickListener { f() }
        return b
    }

    private fun cardTitle(card: LinearLayout, s: String) {
        card.addView(Neo.text(this, s, 15, Neo.TEXT).apply { typeface = Neo.BOLD })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return

        val outer = Neo.vertical(this)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Neo.PANEL)
        bar.elevation = Neo.dp(this, 6).toFloat()
        val bp = Neo.dp(this, 4)
        bar.setPadding(bp, bp, bp, bp)
        bar.addView(Icons.button(this, "back", Neo.BARTEXT) { finish() })
        val title = Neo.glow(Neo.text(this, tr("Settings", "تنظیمات"), 18, Neo.BARTEXT))
        title.typeface = Neo.BOLD
        bar.addView(title)
        outer.addView(bar)

        val sv = ScrollView(this)
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 14)
        col.setPadding(p, p, p, p * 2)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- Look ----
        val look = Neo.card(this)
        cardTitle(look, tr("Look", "ظاهر"))
        btn(look, tr("Theme: ", "تم: ") + Neo.themeName(Neo.P), 10) {
            val labels = Array(THEMES.size) { Neo.themeName(THEMES[it]) }
            Dialogs.choose(this, tr("Theme", "تم"), labels, THEMES.indexOf(Neo.P)) { w ->
                Prefs.putStr(this, "theme", THEMES[w].id)
                applyAndRebuild()
            }
        }
        btn(look, tr("Language: ", "زبان: ") + (if (Lang.code == "en") "English" else "فارسی"), 8) {
            Dialogs.choose(this, tr("Language", "زبان"), arrayOf("فارسی", "English"), if (Lang.code == "en") 1 else 0) { w ->
                Prefs.putStr(this, "lang", if (w == 1) "en" else "fa")
                applyAndRebuild()
            }
        }
        btn(look, tr("Text & icon size: ", "اندازه‌ی نوشته و آیکن: ") + Prefs.int(this, "font_pct", 150) + "%", 8) {
            Dialogs.choose(this, tr("Size", "اندازه"), Array(sizes.size) { sizes[it].toString() + "%" }, -1) { w ->
                Prefs.putInt(this, "font_pct", sizes[w])
                Prefs.putInt(this, "icon_pct", sizes[w])
                applyAndRebuild()
            }
        }
        btn(look, tr("Matrix rain: ", "بارش ماتریکس: ") +
                (if (Prefs.int(this, "rain", 1) == 1) tr("ON", "روشن") else tr("OFF", "خاموش")), 8) {
            Prefs.putInt(this, "rain", if (Prefs.int(this, "rain", 1) == 1) 0 else 1)
            applyAndRebuild()
        }
        Neo.add(col, look, 0)

        // ---- Security ----
        val sec = Neo.card(this)
        cardTitle(sec, tr("Security", "امنیت"))
        lockTypeBtn = btn(sec, "", 10) { changeLock() }
        autoLockBtn = btn(sec, "", 8) {
            val labels = Array(lockSecs.size) { lockLabel(lockSecs[it]) }
            Dialogs.choose(this, tr("Lock the app after leaving it", "قفل برنامه پس از خروج"), labels, -1) { w ->
                Prefs.putInt(this, "autolock", lockSecs[w])
                refresh()
            }
        }
        keysBtn = btn(sec, "", 8) {
            val labels = Array(KeyPolicy.OPTIONS.size) { KeyPolicy.label(KeyPolicy.OPTIONS[it]) }
            Dialogs.choose(this, tr("Clear session keys", "پاک شدن کلیدهای جلسه"), labels, -1) { w ->
                KeyPolicy.set(this, KeyPolicy.OPTIONS[w])
                refresh()
            }
        }
        btn(sec, tr("My key (copy / send / QR)", "کلید من (کپی / ارسال / QR)"), 8) { startActivity(Intent(this, KeyActivity::class.java)) }
        btn(sec, tr("Advanced…", "پیشرفته…"), 8) { advanced() }
        Neo.add(col, sec, 14)

        // ---- Online ----
        val on = Neo.card(this)
        cardTitle(on, tr("Online", "آنلاین"))
        onlineInfo = Neo.text(this, "", 12, Neo.DIM)
        Neo.add(on, onlineInfo, 6)
        val open = Neo.primary(this, tr("Online settings", "تنظیمات آنلاین"))
        Neo.add(on, open, 10)
        open.setOnClickListener { startActivity(Intent(this, OnlineActivity::class.java)) }
        Neo.add(col, on, 14)

        // ---- Danger ----
        val dg = Neo.card(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(Icons.view(this, "trash", Neo.RED, 28, 1f))
        val wipe = Neo.danger(this, tr("Hold to delete ALL data", "نگه دارید تا همه‌ی داده‌ها حذف شود"))
        row.addView(wipe, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Neo.dp(this@SettingsActivity, 10) })
        dg.addView(row)
        wipe.setOnLongClickListener {
            Duress.wipeAll(this)
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            true
        }
        Neo.add(col, dg, 14)

        setContentView(Neo.backdrop(this, outer))
    }

    override fun onReady() { refresh() }

    private fun refresh() {
        lockTypeBtn.text = tr("Lock: ", "قفل: ") + LockType.label(Vault.lockType(this))
        autoLockBtn.text = tr("Lock app after leaving: ", "قفل برنامه پس از خروج: ") + lockLabel(Prefs.int(this, "autolock", 60))
        keysBtn.text = tr("Session keys: ", "کلیدهای جلسه: ") + KeyPolicy.label(KeyPolicy.minutes(this))
        val s = Online.myServer(this)
        onlineInfo.text = tr("Number: ", "شماره: ") + (Online.myPhone(this) ?: "—") + "\n" +
                tr("Server: ", "سرور: ") + (if (s != null) Online.host(s.u) else "—") + "\n" +
                (if (Online.ready(this)) tr("ready ✓", "آماده ✓") else tr("not ready", "آماده نیست"))
    }

    // ---------- lock ----------
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

    // ---------- advanced ----------
    private fun advanced() {
        val labels = arrayOf(
            tr("Duress code", "کد اضطراری"),
            (if (Lockdown.isOn(this)) tr("Calculator disguise: ON", "ماشین‌حساب: روشن") else tr("Calculator disguise: OFF", "ماشین‌حساب: خاموش")),
            tr("Default SIM: ", "سیم‌کارت پیش‌فرض: ") + Sims.label(this, Prefs.int(this, "sim", -1)))
        Dialogs.choose(this, tr("Advanced", "پیشرفته"), labels, -1) { w ->
            when (w) {
                0 -> setDuress()
                1 -> {
                    Lockdown.set(this, !Lockdown.isOn(this))
                    toast(tr("Launcher icon will change in a few seconds", "آیکن چند ثانیه‌ی دیگر عوض می‌شود"))
                }
                else -> Sims.choose(this, "sim", false) { refresh() }
            }
        }
    }

    private fun setDuress() {
        val t = Vault.lockType(this)
        if (t == LockType.BIO) {
            toast(tr("Needs a password, PIN or pattern lock", "نیاز به قفل رمز، پین یا الگو دارد"))
            return
        }
        askSecret(this, t, tr("Duress code (wipes everything when entered at login)", "کد اضطراری (با وارد کردنش در ورود همه‌چیز پاک می‌شود)"), true) { s ->
            Thread {
                val same = Vault.deriveDbKey(this, s) == SessionVault.dbKey()
                if (!same) Duress.set(this, s)
                runOnUiThread {
                    toast(if (same) tr("Must differ from your lock", "باید با قفل اصلی فرق داشته باشد") else tr("Saved", "ذخیره شد"))
                }
            }.start()
        }
    }
}
