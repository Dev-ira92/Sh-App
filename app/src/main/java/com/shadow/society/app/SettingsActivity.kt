package com.shadow.society.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

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
        card.addView(Neo.text(this, s, 11, Neo.ACCENT).apply { typeface = Neo.BOLD })
    }

    private fun langName(): String =
        if (Prefs.int(this, "lang_sys", 0) == 1) tr("System default", "زبان سیستم")
        else if (Lang.code == "en") "English" else "فارسی"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return

        val outer = Neo.vertical(this)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Neo.PANEL)
        bar.elevation = Neo.dp(this, 3).toFloat()
        val bp = Neo.dp(this, 4)
        bar.setPadding(bp, bp, bp, bp)
        bar.addView(Icons.button(this, "back", Neo.BARTEXT) { finish() })
        val title = Neo.text(this, tr("Settings", "تنظیمات"), 12, Neo.BARTEXT)
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
        btn(look, tr("Language: ", "زبان: ") + langName(), 10) {
            val labels = arrayOf(tr("System default", "زبان سیستم"), "فارسی", "English")
            Dialogs.choose(this, tr("Language", "زبان"), labels, -1) { w ->
                if (w == 0) {
                    Prefs.putInt(this, "lang_sys", 1)
                    Prefs.putStr(this, "lang", if (Locale.getDefault().language == "fa") "fa" else "en")
                } else {
                    Prefs.putInt(this, "lang_sys", 0)
                    Prefs.putStr(this, "lang", if (w == 1) "fa" else "en")
                }
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
        keysBtn = btn(sec, "", 8) { keyPolicy() }
        btn(sec, tr("My key", "کلید من"), 8) { startActivity(Intent(this, KeyActivity::class.java)) }
        btn(sec, tr("Advanced…", "پیشرفته…"), 8) { advanced() }
        Neo.add(col, sec, 14)

        // ---- Online ----
        val on = Neo.card(this)
        cardTitle(on, tr("Online", "آنلاین"))
        onlineInfo = Neo.text(this, "", 9, Neo.DIM)
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
        lockTypeBtn.text = tr("Login: ", "ورود: ") + LockType.label(Vault.lockType(this))
        autoLockBtn.text = tr("Lock app after leaving: ", "قفل برنامه پس از خروج: ") + lockLabel(Prefs.int(this, "autolock", 60))
        keysBtn.text = tr("Session keys: ", "کلیدهای جلسه: ") +
                (if (KeyPolicy.minutes(this) < 0) KeyPolicy.label(-1) else tr("delete after ", "حذف پس از ") + KeyPolicy.label(KeyPolicy.minutes(this)))
        val s = Online.myServer(this)
        onlineInfo.text = tr("Number: ", "شماره: ") + (Online.myPhone(this) ?: "—") + "\n" +
                tr("Server: ", "سرور: ") + (if (s != null) Online.host(s.u) else "—") + "\n" +
                (if (Online.ready(this)) tr("ready ✓", "آماده ✓") else tr("not ready", "آماده نیست"))
    }

    // ---------- login type ----------
    private fun changeLock() {
        val types = LockType.CHOICES
        val labels = Array(types.size) { LockType.label(types[it]) }
        Dialogs.choose(this, tr("New login type", "نوع ورود جدید"), labels, types.indexOf(Vault.lockType(this))) { w ->
            val t = types[w]
            if (t == LockType.BIO) {
                if (!AppLock.available(this)) {
                    toast(tr("Set a screen lock / fingerprint on your phone first", "اول در گوشی قفل صفحه یا اثر انگشت را فعال کن"))
                } else {
                    AppLock.prompt(this, { applyLock(t, "") }, { m -> toast(m) })
                }
            } else {
                askSecret(this, t, tr("New login", "ورود جدید"), true) { s -> applyLock(t, s) }
            }
        }
    }

    private fun applyLock(t: String, secret: String) {
        Thread {
            val ok = Vault.changeLock(this, t, secret)
            if (ok) Duress.clear(this)
            runOnUiThread {
                toast(if (ok) tr("Changed (duress code reset)", "تغییر کرد (کد اضطراری پاک شد)") else tr("Failed", "ناموفق"))
                refresh()
            }
        }.start()
    }

    // ---------- session keys: never delete / delete after… ----------
    private fun keyPolicy() {
        val labels = arrayOf(tr("Never delete", "هرگز حذف نشود"), tr("Delete after…", "حذف شود پس از…"))
        Dialogs.choose(this, tr("Session keys", "کلیدهای جلسه"), labels, if (KeyPolicy.minutes(this) < 0) 0 else 1) { w ->
            if (w == 0) {
                KeyPolicy.set(this, -1)
                refresh()
            } else {
                deleteAfter()
            }
        }
    }

    private fun deleteAfter() {
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 20)
        col.setPadding(p, p / 2, p, 0)
        val e = Neo.input(this, tr("a number", "یک عدد")).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("7")
        }
        Neo.add(col, e)
        fun apply(mult: Int) {
            val n = e.text.toString().toLongOrNull()
            if (n == null || n <= 0 || n * mult > 5_256_000L) {
                toast(tr("Enter a number (up to 10 years)", "یک عدد وارد کن (حداکثر ۱۰ سال)"))
            } else {
                KeyPolicy.set(this, (n * mult).toInt())
                refresh()
            }
        }
        Neo.dialog(this).setTitle(tr("Delete session keys after…", "حذف کلیدهای جلسه پس از…")).setView(col)
            .setPositiveButton(tr("minutes", "دقیقه")) { _, _ -> apply(1) }
            .setNeutralButton(tr("hours", "ساعت")) { _, _ -> apply(60) }
            .setNegativeButton(tr("days", "روز")) { _, _ -> apply(1440) }
            .show()
    }

    // ---------- advanced ----------
    private fun advanced() {
        val labels = arrayOf(
            tr("Duress code", "کد اضطراری"),
            (if (Lockdown.isOn(this)) tr("Calculator disguise: ON", "ماشین‌حساب: روشن") else tr("Calculator disguise: OFF", "ماشین‌حساب: خاموش")),
            tr("Default SIM: ", "سیم‌کارت پیش‌فرض: ") + Sims.label(this, Prefs.int(this, "sim", -1)),
            tr("Crypto self-test", "خودآزمون رمزنگاری"))
        Dialogs.choose(this, tr("Advanced", "پیشرفته"), labels, -1) { w ->
            when (w) {
                0 -> setDuress()
                1 -> {
                    Lockdown.set(this, !Lockdown.isOn(this))
                    toast(tr("Launcher icon will change in a few seconds", "آیکن چند ثانیه‌ی دیگر عوض می‌شود"))
                }
                2 -> Sims.choose(this, "sim", false) { refresh() }
                else -> selfTest()
            }
        }
    }

    private fun selfTest() {
        Thread {
            val ok = try { SessionCrypto.selfTest() } catch (e: Throwable) { false }
            runOnUiThread {
                Dialogs.info(this, tr("Crypto self-test", "خودآزمون رمزنگاری"),
                    if (ok) tr("✓ This phone gives exactly the same results as the Python reference (HKDF + AES-GCM).",
                        "✓ این گوشی دقیقاً همان نتیجه‌ی نسخه‌ی پایتون را می‌دهد (HKDF + AES-GCM).")
                    else tr("✗ Results differ from the reference. Do not rely on encryption between this app and the Python version.",
                        "✗ نتیجه با مرجع فرق دارد. به رمزنگاری بین این برنامه و نسخه‌ی پایتون تکیه نکن."))
            }
        }.start()
    }

    private fun setDuress() {
        val t = Vault.lockType(this)
        if (t == LockType.BIO) {
            toast(tr("Needs a PIN or password login", "نیاز به ورود با پین یا رمز عبور دارد"))
            return
        }
        askSecret(this, t, tr("Duress code (wipes everything when entered at login)", "کد اضطراری (با وارد کردنش در ورود همه‌چیز پاک می‌شود)"), true) { s ->
            Thread {
                val same = Vault.deriveDbKey(this, s) == SessionVault.dbKey()
                if (!same) Duress.set(this, s)
                runOnUiThread {
                    toast(if (same) tr("Must differ from your login", "باید با ورود اصلی فرق داشته باشد") else tr("Saved", "ذخیره شد"))
                }
            }.start()
        }
    }
}
