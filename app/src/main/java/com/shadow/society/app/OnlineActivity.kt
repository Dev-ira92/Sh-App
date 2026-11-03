package com.shadow.society.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Internal server (Cloudflare Worker): server, my inbox, my address, invite for a shared server. */
class OnlineActivity : OnlineBase() {
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var addrBox: LinearLayout

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun btn(col: LinearLayout, label: String, top: Int, f: () -> Unit): Button {
        val b = Neo.button(this, label)
        Neo.add(col, b, top)
        b.setOnClickListener { f() }
        return b
    }

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
        val title = Neo.glow(Neo.text(this, tr("Online (internal server)", "آنلاین (سرور داخلی)"), 18, Neo.BARTEXT))
        title.typeface = Neo.BOLD
        bar.addView(title)
        outer.addView(bar)

        val sv = ScrollView(this)
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 16)
        col.setPadding(p, p / 2, p, p * 2)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(Neo.text(this, tr(
            "The internal server is a free Cloudflare Worker that only holds encrypted packets. Shared server: one person deploys it, the other joins with an invite. Separate servers: each person deploys their own and you only exchange addresses.",
            "سرور داخلی یک Worker رایگان در Cloudflare است که فقط بسته‌های رمزشده را نگه می‌دارد. سرور مشترک: یک نفر می‌سازد و نفر دوم با دعوت‌نامه می‌پیوندد. سرور جدا: هر کس سرور خودش را می‌سازد و فقط آدرس‌ها را به هم می‌دهید."), 12, Neo.DIM))
        status = Neo.text(this, "", 13, Neo.TEXT)
        Neo.add(col, status, 12)

        col.addView(Neo.section(this, tr("STEP 1 · SERVER", "گام ۱ · سرور")))
        btn(col, tr("Use my own server…", "سرور خودم…"), 4) { ownServer() }
        btn(col, tr("Join a shared server (invite)…", "پیوستن به سرور مشترک (دعوت‌نامه)…"), 8) { joinServer() }
        btn(col, tr("Test connection", "تست اتصال"), 8) { testServer() }

        col.addView(Neo.section(this, tr("STEP 2 · MY INBOX", "گام ۲ · صندوق من")))
        btn(col, tr("Create my inbox", "ساخت صندوق من"), 4) { createInbox() }

        addrBox = Neo.vertical(this)
        addrBox.addView(Neo.section(this, tr("MY ONLINE ADDRESS (give it to your contact)", "آدرس آنلاین من (به مخاطبت بده)")))
        addrBox.addView(Neo.text(this, tr("With this address your contact can drop messages into your inbox. They cannot read them.",
            "با این آدرس مخاطبت می‌تواند برایت پیام بگذارد. نمی‌تواند چیزی بخواند."), 12, Neo.DIM))
        qr = ImageView(this)
        Neo.add(addrBox, qr, 10)
        btn(addrBox, tr("Copy", "کپی"), 10) { Online.myAddr(this)?.let { Neo.copy(this, it.toLine()) } }
        btn(addrBox, tr("Send as text…", "ارسال به صورت متن…"), 8) { shareAddr(0) }
        btn(addrBox, tr("Send as TXT file…", "ارسال به صورت فایل TXT…"), 8) { shareAddr(1) }
        btn(addrBox, tr("Send as JSON file…", "ارسال به صورت فایل JSON…"), 8) { shareAddr(2) }
        btn(addrBox, tr("Send as QR image…", "ارسال به صورت تصویر QR…"), 8) { shareAddr(3) }
        col.addView(addrBox)

        col.addView(Neo.section(this, tr("INVITE (shared server)", "دعوت‌نامه (سرور مشترک)")))
        col.addView(Neo.text(this, tr("Contains the SERVER KEY: whoever has it can create inboxes on your server. Give it only to your contact.",
            "«کلید سرور» داخلش است: هر کس دارد می‌تواند روی سرور تو صندوق بسازد. فقط به مخاطبت بده."), 12, Neo.DIM))
        btn(col, tr("Send invite as text…", "ارسال دعوت‌نامه به صورت متن…"), 8) { shareInvite(0) }
        btn(col, tr("Send invite as TXT…", "ارسال دعوت‌نامه به صورت TXT…"), 8) { shareInvite(1) }
        btn(col, tr("Send invite as JSON…", "ارسال دعوت‌نامه به صورت JSON…"), 8) { shareInvite(2) }
        btn(col, tr("Send invite as QR image…", "ارسال دعوت‌نامه به صورت تصویر QR…"), 8) { shareInvite(3) }

        col.addView(Neo.section(this, tr("RESET", "پاک‌سازی")))
        val reset = btn(col, tr("Forget the server and my inbox", "فراموش کردن سرور و صندوق من"), 4) {
            Dialogs.confirm(this, tr("Forget the server and your inbox? Contacts' addresses are kept.",
                "سرور و صندوقت فراموش شود؟ آدرس مخاطب‌ها می‌ماند.")) {
                Online.reset(this)
                refresh()
            }
        }
        reset.setTextColor(Neo.RED)
        reset.background = Neo.box(this, 0, Neo.RED, 8)

        setContentView(Neo.backdrop(this, outer))
    }

    override fun onReady() { refresh() }

    private fun refresh() {
        val url = Online.meta(this, "ol_url")
        val mine = Online.mine(this)
        status.text = tr("Server: ", "سرور: ") + (if (url != null) Online.host(url) else tr("not set", "تنظیم نشده")) + "\n" +
                tr("My inbox: ", "صندوق من: ") + (if (mine != null) tr("ready", "آماده") else tr("not created", "ساخته نشده"))
        addrBox.visibility = if (mine != null) View.VISIBLE else View.GONE
        if (mine != null) {
            qr.setImageBitmap(QrTools.render(Addr(mine.url, mine.inbox, mine.drop).toLine(), Neo.dp(this, 260), true))
        }
    }

    // ---------- step 1 ----------
    private fun ownServer() {
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 20)
        col.setPadding(p, p / 2, p, 0)
        val cur = Online.meta(this, "ol_url") ?: ""
        val curKey = Online.meta(this, "ol_skey") ?: ""
        val u = Neo.input(this, "https://sh-so.xxx.workers.dev").apply { setText(cur) }
        val k = Neo.input(this, tr("server key (SERVER_KEY)", "کلید سرور (SERVER_KEY)")).apply { setText(curKey) }
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
                Online.saveServer(this, url, key)
                refresh()
                createInbox()
            }
        }
    }

    private fun joinServer() {
        importRaw(tr("Server invite", "دعوت‌نامه‌ی سرور")) { raw ->
            val s = OnlineParse.server(raw)
            if (s == null) {
                toast(tr("This is not a server invite", "این دعوت‌نامه‌ی سرور نیست"))
            } else {
                Online.saveServer(this, s.u, s.k)
                refresh()
                createInbox()
            }
        }
    }

    private fun testServer() {
        val u = Online.meta(this, "ol_url")
        if (u == null) { toast(tr("Set the server first", "اول سرور را تنظیم کن")); return }
        toast(tr("Testing…", "در حال تست…"))
        Thread {
            val r = Net.request("GET", "$u/ping", emptyMap(), null)
            runOnUiThread {
                if (r.code == 200 && r.body.contains("sh-so")) toast(tr("Connected ✓", "اتصال برقرار است ✓")) else toast(Online.err(r))
            }
        }.start()
    }

    // ---------- step 2 ----------
    private fun createInbox() {
        if (Online.meta(this, "ol_url") == null) { toast(tr("Set the server first", "اول سرور را تنظیم کن")); return }
        val go = {
            toast(tr("Creating your inbox…", "در حال ساخت صندوق…"))
            Thread {
                val err = Online.createInbox(this)
                runOnUiThread {
                    toast(err ?: tr("My inbox is ready", "صندوق من آماده شد"))
                    refresh()
                }
            }.start()
        }
        if (Online.mine(this) != null) {
            Dialogs.confirm(this, tr("A new inbox changes your address; give it to your contacts again. Continue?",
                "صندوق جدید آدرست را عوض می‌کند؛ باید دوباره به مخاطب‌ها بدهی. ادامه؟")) { go() }
        } else {
            go()
        }
    }

    // ---------- sharing ----------
    private fun shareValue(line: String, json: String, base: String, kind: Int) {
        when (kind) {
            0 -> SendFlow.shareText(this, line)
            1 -> Files.share(this, Online.exportText(this, "$base.txt", "# Sh-So\n$line\n"), "text/plain")
            2 -> Files.share(this, Online.exportText(this, "$base.json", json), "application/json")
            else -> {
                val f = Files.exportKeyPng(this, line)
                if (f == null) toast(tr("Could not create the image", "ساخت تصویر ناموفق بود"))
                else Files.share(this, f, "image/png")
            }
        }
    }

    private fun shareAddr(kind: Int) {
        val a = Online.myAddr(this) ?: return
        shareValue(a.toLine(), a.toJson(), "ShSo-address", kind)
    }

    private fun shareInvite(kind: Int) {
        val u = Online.meta(this, "ol_url")
        val k = Online.meta(this, "ol_skey")
        if (u == null || k == null) { toast(tr("Set the server first", "اول سرور را تنظیم کن")); return }
        Dialogs.confirm(this, tr("This invite contains the SERVER KEY. Send it only to your contact. Continue?",
            "این دعوت‌نامه «کلید سرور» را دارد. فقط به مخاطبت بفرست. ادامه؟")) {
            val s = ServerInfo(u, k)
            shareValue(s.toLine(), s.toJson(), "ShSo-invite", kind)
        }
    }
}
