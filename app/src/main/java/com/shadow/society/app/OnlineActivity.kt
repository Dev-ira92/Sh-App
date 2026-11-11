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

/** Online settings: my number (country code + number), the server (address + password), test, share, guide. */
class OnlineActivity : OnlineBase() {
    private lateinit var status: TextView
    private lateinit var code: FloatField
    private lateinit var phone: FloatField
    private lateinit var countryCap: TextView
    private lateinit var srvUrl: FloatField
    private lateinit var srvKey: FloatField

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun btn(parent: LinearLayout, label: String, top: Int, primary: Boolean = false, f: () -> Unit): Button {
        val b = if (primary) Neo.primary(this, label) else Neo.button(this, label)
        Neo.add(parent, b, top)
        b.setOnClickListener { f() }
        return b
    }

    private fun title(card: LinearLayout, s: String) {
        card.addView(Neo.text(this, s, 12, Neo.ACCENT).apply { typeface = Neo.BOLD })
    }

    private fun setCountry(c: Countries.Country) {
        code.edit.setText("+" + c.code)
        countryCap.text = c.flag + "  " + c.name
    }

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
        bar.addView(Neo.text(this, tr("Online", "آنلاین"), 12, Neo.BARTEXT).apply { typeface = Neo.BOLD })
        outer.addView(bar)

        val sv = ScrollView(this)
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 14)
        col.setPadding(p, p, p, p * 2)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(Neo.text(this, tr(
            "Messages go straight to your friend's phone number. Both of you use the same server, or each of you has his own server and you save your friend's server in his contact.",
            "پیام‌ها مستقیم به شماره‌ی موبایل دوستت می‌روند. یا هر دو از یک سرور استفاده می‌کنید، یا هر کس سرور خودش را دارد و سرور دوستت را در مخاطبش ذخیره می‌کنی."), 10, Neo.DIM))
        status = Neo.text(this, "", 11, Neo.TEXT)
        Neo.add(col, status, 10)

        // ---- my number ----
        val c1 = Neo.card(this)
        title(c1, tr("My phone number (my online ID)", "شماره‌ی موبایل من (شناسه‌ی آنلاین)"))
        code = FloatField(this, tr("Country code", "کد کشور")) { Regex("\\+\\d{1,4}").matches(it.trim()) }
        phone = FloatField(this, tr("Phone number", "شماره تماس")) { Phones.digits(it).length >= 6 }
        phone.edit.inputType = InputType.TYPE_CLASS_PHONE
        countryCap = Neo.text(this, "", 10, Neo.DIM)
        code.readOnly { pickCountry(this) { c -> setCountry(c) } }
        Neo.add(c1, formRow(this, code to 0.42f, phone to 0.58f), 12)
        Neo.add(c1, countryCap, 4)
        btn(c1, tr("Save my number", "ذخیره‌ی شماره"), 12, true) { savePhone() }
        Neo.add(col, c1, 14)

        // ---- server ----
        val c2 = Neo.card(this)
        title(c2, tr("Server", "سرور"))
        srvUrl = FloatField(this, tr("Server address", "آدرس سرور")) { OnlineParse.cleanUrl(it) != null }
        srvUrl.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        srvKey = FloatField(this, tr("Server password", "رمز سرور")) { s ->
            val t = s.trim()
            t.length >= 8 && t.none { it.isWhitespace() || it == '|' }
        }.password()
        Neo.add(c2, srvUrl, 12)
        Neo.add(c2, srvKey, 10)
        btn(c2, tr("Save and register", "ذخیره و ثبت"), 12, true) { saveServer() }
        btn(c2, tr("Test connection", "تست اتصال"), 8) { testServer() }
        btn(c2, tr("Import a server address (QR / file / text)", "وارد کردن آدرس سرور (QR / فایل / متن)"), 8) { joinServer() }
        btn(c2, tr("Server guide", "راهنمای سرور"), 8) { startActivity(Intent(this, ServerGuideActivity::class.java)) }
        Neo.add(col, c2, 14)

        // ---- share ----
        val c3 = Neo.card(this)
        title(c3, tr("Send my server address to a friend", "ارسال آدرس سرور من به دوست"))
        c3.addView(Neo.text(this, tr("It contains the server password: send it only to people you trust.",
            "رمز سرور هم داخلش است: فقط به کسانی بفرست که به آن‌ها اعتماد داری."), 9, Neo.DIM))
        btn(c3, tr("As text", "به صورت متن"), 10) { share(0) }
        btn(c3, tr("As TXT file", "به صورت فایل TXT"), 8) { share(1) }
        btn(c3, tr("As JSON file", "به صورت فایل JSON"), 8) { share(2) }
        btn(c3, tr("As QR image", "به صورت تصویر QR"), 8) { share(3) }
        Neo.add(col, c3, 14)

        // ---- forget ----
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
        if (ph != null) {
            val (cc, num) = Countries.splitPhone(ph)
            code.edit.setText(cc)
            if (phone.text().isEmpty()) phone.edit.setText(num)
            val country = Countries.all().firstOrNull { "+" + it.code == cc }
            countryCap.text = if (country != null) country.flag + "  " + country.name else ""
        } else if (code.text().isEmpty()) {
            setCountry(Countries.find("IR")!!)
        }
        if (s != null) {
            if (srvUrl.text().isEmpty()) srvUrl.edit.setText(s.u)
            if (srvKey.text().isEmpty()) srvKey.edit.setText(s.k)
        }
    }

    /** After saving: register (and ask if the number was registered before). */
    private fun registerNow() {
        if (Online.myInbox(this) == null || Online.myServer(this) == null) { refresh(); return }
        toast(tr("Registering…", "در حال ثبت…"))
        registerOnline(this) { e ->
            toast(e ?: tr("Online is ready ✓", "آنلاین آماده شد ✓"))
            refresh()
        }
    }

    private fun savePhone() {
        val c = code.text().trim()
        val num = Phones.digits(phone.text())
        if (!Regex("\\+\\d{1,4}").matches(c) || num.length < 6) {
            phone.bad(num.length < 6)
            toast(tr("Choose a country code and enter a valid number", "کد کشور را انتخاب کن و یک شماره‌ی معتبر وارد کن"))
            return
        }
        phone.bad(false)
        Online.setMyPhone(this, c + num.trimStart('0'))
        toast(tr("Saved", "ذخیره شد"))
        registerNow()
    }

    private fun saveServer() {
        val url = OnlineParse.cleanUrl(srvUrl.text())
        val key = srvKey.text().trim()
        val keyOk = key.length >= 8 && key.none { it.isWhitespace() || it == '|' }
        srvUrl.bad(url == null)
        srvKey.bad(!keyOk)
        if (url == null) { toast(tr("The address must start with https://", "آدرس باید با https:// شروع شود")); return }
        if (!keyOk) { toast(tr("Server password: at least 8 characters, no spaces", "رمز سرور: حداقل ۸ کاراکتر، بدون فاصله")); return }
        Online.saveServer(this, ServerInfo(url, key))
        registerNow()
    }

    private fun joinServer() {
        importRaw(tr("Server address", "آدرس سرور")) { raw ->
            val s = OnlineParse.server(raw)
            if (s == null) {
                toast(tr("This is not a server address", "این آدرس سرور نیست"))
            } else {
                srvUrl.edit.setText(s.u)
                srvKey.edit.setText(s.k)
                Online.saveServer(this, s)
                registerNow()
            }
        }
    }

    private fun testServer() {
        val s = Online.myServer(this)
        if (s == null) { toast(tr("Save the server first", "اول سرور را ذخیره کن")); return }
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
        if (s == null) { toast(tr("Save the server first", "اول سرور را ذخیره کن")); return }
        Dialogs.confirm(this, tr("This contains the SERVER PASSWORD. Send it only to people you trust. Continue?",
            "این «رمز سرور» را دارد. فقط به کسانی بفرست که به آن‌ها اعتماد داری. ادامه؟")) {
            when (kind) {
                0 -> SendFlow.shareText(this, s.toLine())
                1 -> Files.share(this, Online.exportText(this, "Shadow-server.txt", "# Shadow\n" + s.toLine() + "\n"), "text/plain")
                2 -> Files.share(this, Online.exportText(this, "Shadow-server.json", s.toJson()), "application/json")
                else -> {
                    val f = Files.exportKeyPng(this, s.toLine())
                    if (f == null) toast(tr("Could not create the image", "ساخت تصویر ناموفق بود"))
                    else Files.share(this, f, "image/png")
                }
            }
        }
    }
}
