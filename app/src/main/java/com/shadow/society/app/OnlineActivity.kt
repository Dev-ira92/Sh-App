package com.shadow.society.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Online: my phone number (= my ID) and the server (my own, or my friend's / a shared one). */
class OnlineActivity : OnlineBase() {
    private lateinit var status: TextView

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun btn(parent: LinearLayout, label: String, top: Int, primary: Boolean = false, f: () -> Unit): Button {
        val b = if (primary) Neo.primary(this, label) else Neo.button(this, label)
        Neo.add(parent, b, top)
        b.setOnClickListener { f() }
        return b
    }

    private fun title(card: LinearLayout, s: String) {
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
        val ttl = Neo.glow(Neo.text(this, tr("Online", "آنلاین"), 18, Neo.BARTEXT))
        ttl.typeface = Neo.BOLD
        bar.addView(ttl)
        outer.addView(bar)

        val sv = ScrollView(this)
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 14)
        col.setPadding(p, p, p, p * 2)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(Neo.text(this, tr(
            "Messages go straight to your friend's phone number. Both of you register your number on the same server (shared), or each person has their own server and you save your friend's server address in his contact.",
            "پیام‌ها مستقیم به شماره‌ی موبایل دوستت می‌روند. یا هر دو روی یک سرور ثبت می‌شوید (مشترک)، یا هر کس سرور خودش را دارد و آدرس سرور دوستت را در مخاطبش ذخیره می‌کنی."), 12, Neo.DIM))

        status = Neo.text(this, "", 13, Neo.TEXT)
        Neo.add(col, status, 10)

        val c1 = Neo.card(this)
        title(c1, "1 · " + tr("My phone number", "شماره‌ی موبایل من"))
        c1.addView(Neo.text(this, tr("This is your online ID. Your friend saves you with this same number.",
            "این شناسه‌ی آنلاین توست. دوستت هم تو را با همین شماره ذخیره می‌کند."), 12, Neo.DIM))
        btn(c1, tr("Set my number…", "تنظیم شماره‌ی من…"), 10, true) { setPhone() }
        Neo.add(col, c1, 14)

        val c2 = Neo.card(this)
        title(c2, "2 · " + tr("Server", "سرور"))
        c2.addView(Neo.text(this, tr("Your own server: you deploy it on Cloudflare. A friend's / shared server: import the address he gave you.",
            "سرور خودم: خودت روی Cloudflare می‌سازی. سرور دوستم / مشترک: آدرسی که به تو داده را وارد کن."), 12, Neo.DIM))
        btn(c2, tr("My own server…", "سرور خودم…"), 10, true) { ownServer() }
        btn(c2, tr("Use a friend's / shared server…", "سرور دوستم / مشترک…"), 8) { joinServer() }
        btn(c2, tr("Test connection", "تست اتصال"), 8) { testServer() }
        Neo.add(col, c2, 14)

        val c3 = Neo.card(this)
        title(c3, "3 · " + tr("Share my server address", "ارسال آدرس سرور من"))
        c3.addView(Neo.text(this, tr("Contains the SERVER KEY: give it only to people who may chat on your server.",
            "«کلید سرور» داخلش است: فقط به کسانی بده که اجازه‌ی چت روی سرورت را دارند."), 12, Neo.DIM))
        btn(c3, tr("As text…", "به صورت متن…"), 10) { share(0) }
        btn(c3, tr("As TXT file…", "به صورت فایل TXT…"), 8) { share(1) }
        btn(c3, tr("As JSON file…", "به صورت فایل JSON…"), 8) { share(2) }
        btn(c3, tr("As QR image…", "به صورت تصویر QR…"), 8) { share(3) }
        Neo.add(col, c3, 14)

        val c4 = Neo.card(this)
        val forget = Neo.danger(this, tr("Forget the server", "فراموش کردن سرور"))
        Neo.add(c4, forget, 0)
        forget.setOnClickListener {
            Dialogs.confirm(this, tr("Forget the server? Your number stays.", "سرور فراموش شود؟ شماره‌ات می‌ماند.")) {
                Online.forgetServer(this)
                refresh()
            }
        }
        Neo.add(col, c4, 14)

        setContentView(Neo.backdrop(this, outer))
    }

    override fun onReady() { refresh() }

    private fun refresh() {
        val ph = Online.myPhone(this)
        val s = Online.myServer(this)
        status.text = tr("Number: ", "شماره: ") + (ph ?: tr("not set", "تنظیم نشده")) + "\n" +
                tr("Server: ", "سرور: ") + (if (s != null) Online.host(s.u) else tr("not set", "تنظیم نشده")) + "\n" +
                tr("Status: ", "وضعیت: ") + (if (Online.ready(this)) tr("ready ✓", "آماده ✓") else tr("not ready", "آماده نیست"))
    }

    private fun registerNow() {
        if (Online.myInbox(this) == null || Online.myServer(this) == null) { refresh(); return }
        toast(tr("Registering…", "در حال ثبت…"))
        Thread {
            val err = Online.register(this)
            runOnUiThread {
                toast(err ?: tr("Online is ready ✓", "آنلاین آماده شد ✓"))
                refresh()
            }
        }.start()
    }

    private fun setPhone() {
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 20)
        col.setPadding(p, p / 2, p, 0)
        val cur = Online.myPhone(this) ?: ""
        val e = Neo.input(this, tr("my phone number", "شماره‌ی موبایل من")).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            setText(cur)
        }
        Neo.add(col, e)
        val d = Neo.dialog(this).setTitle(tr("My number", "شماره‌ی من")).setView(col)
            .setPositiveButton(tr("Save", "ذخیره"), null)
            .setNegativeButton(tr("Cancel", "انصراف"), null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val s = e.text.toString().trim()
            if (Phones.tail(s).length < 6) {
                toast(tr("Enter a valid number", "یک شماره‌ی معتبر وارد کن"))
            } else {
                d.dismiss()
                Online.setMyPhone(this, s)
                registerNow()
            }
        }
    }

    private fun ownServer() {
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 20)
        col.setPadding(p, p / 2, p, 0)
        val cur = Online.myServer(this)
        val u = Neo.input(this, "https://sh-so.xxx.workers.dev").apply { setText(cur?.u ?: "") }
        val k = Neo.input(this, tr("server key (SERVER_KEY)", "کلید سرور (SERVER_KEY)")).apply { setText(cur?.k ?: "") }
        Neo.add(col, u)
        Neo.add(col, k, 8)
        val d = Neo.dialog(this).setTitle(tr("My own server", "سرور خودم")).setView(col)
            .setPositiveButton(tr("Save", "ذخیره"), null)
            .setNegativeButton(tr("Cancel", "انصراف"), null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = OnlineParse.cleanUrl(u.text.toString())
            val key = k.text.toString().trim()
            if (url == null) {
                toast(tr("The address must start with https://", "آدرس باید با https:// شروع شود"))
            } else if (key.length < 8 || key.any { it.isWhitespace() || it == '|' }) {
                toast(tr("Server key: at least 8 characters, no spaces or |", "کلید سرور: حداقل ۸ کاراکتر، بدون فاصله و |"))
            } else {
                d.dismiss()
                Online.saveServer(this, ServerInfo(url, key))
                registerNow()
            }
        }
    }

    private fun joinServer() {
        importRaw(tr("Server address", "آدرس سرور")) { raw ->
            val s = OnlineParse.server(raw)
            if (s == null) {
                toast(tr("This is not a server address", "این آدرس سرور نیست"))
            } else {
                Online.saveServer(this, s)
                registerNow()
            }
        }
    }

    private fun testServer() {
        val s = Online.myServer(this)
        if (s == null) { toast(tr("Set the server first", "اول سرور را تنظیم کن")); return }
        toast(tr("Testing…", "در حال تست…"))
        Thread {
            val r = Net.request("GET", s.u + "/ping", emptyMap(), null)
            runOnUiThread {
                if (r.code == 200 && r.body.contains("sh-so")) toast(tr("Connected ✓", "اتصال برقرار است ✓")) else toast(Online.err(r))
            }
        }.start()
    }

    private fun share(kind: Int) {
        val s = Online.myServer(this)
        if (s == null) { toast(tr("Set the server first", "اول سرور را تنظیم کن")); return }
        Dialogs.confirm(this, tr("This contains the SERVER KEY. Send it only to people who may chat on your server. Continue?",
            "این «کلید سرور» را دارد. فقط به کسانی بفرست که اجازه‌ی چت روی سرورت را دارند. ادامه؟")) {
            when (kind) {
                0 -> SendFlow.shareText(this, s.toLine())
                1 -> Files.share(this, Online.exportText(this, "ShSo-server.txt", "# Sh-So\n" + s.toLine() + "\n"), "text/plain")
                2 -> Files.share(this, Online.exportText(this, "ShSo-server.json", s.toJson()), "application/json")
                else -> {
                    val f = Files.exportKeyPng(this, s.toLine())
                    if (f == null) toast(tr("Could not create the image", "ساخت تصویر ناموفق بود"))
                    else Files.share(this, f, "image/png")
                }
            }
        }
    }
}
