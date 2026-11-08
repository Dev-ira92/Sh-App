package com.shadow.society.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * "My key": press "Create key" → 32 random bytes shown as 64 hex characters in a 3-line scrolling box,
 * with Copy, the QR code and Send (text / JSON file). Below: the shared passphrase + salt card.
 */
class KeyActivity : BaseActivity() {
    private var firstRun = false

    private lateinit var genBtn: Button
    private lateinit var keyArea: LinearLayout
    private lateinit var keyText: TextView
    private lateinit var keyQr: ImageView

    private lateinit var passArea: LinearLayout
    private lateinit var passText: TextView
    private lateinit var passQr: ImageView

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    /** A box that shows exactly 3 lines and scrolls for the rest. */
    private fun scrollBox(tv: TextView): FrameLayout {
        tv.setTextIsSelectable(true)
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setTextColor(Neo.P.recvText)
        val pad = Neo.dp(this, 8)
        val sv = ScrollView(this)
        tv.setPadding(pad, pad, pad, pad)
        sv.addView(tv)
        val box = FrameLayout(this)
        box.background = Neo.box(this, Neo.P.recv, Neo.DIM, 10)
        box.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tv.lineHeight * 3 + pad * 2))
        return box
    }

    private fun titleRow(title: String, onCopy: () -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(Neo.text(this, title, 16, Neo.TEXT).apply { typeface = Neo.BOLD },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val copy = Neo.button(this, tr("Copy", "کپی"))
        copy.textSize = 12f
        copy.setOnClickListener { onCopy() }
        row.addView(copy)
        return row
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        firstRun = intent.getBooleanExtra("first", false)

        val outer = Neo.vertical(this)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Neo.PANEL)
        bar.elevation = Neo.dp(this, 6).toFloat()
        val bp = Neo.dp(this, 4)
        bar.setPadding(bp * 3, bp, bp, bp)
        if (!firstRun) bar.addView(Icons.button(this, "back", Neo.BARTEXT) { finish() })
        val title = Neo.glow(Neo.text(this, tr("My key", "کلید من"), 18, Neo.BARTEXT))
        title.typeface = Neo.BOLD
        bar.addView(title)
        outer.addView(bar)

        val sv = ScrollView(this)
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 14)
        col.setPadding(p, p, p, p * 2)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---------- card 1: my key ----------
        val c1 = Neo.card(this)
        c1.addView(Neo.text(this, tr("A random 256-bit key (32 bytes, shown as 64 hex characters). Give it only to the person you will talk with. This is NOT your login password.",
            "یک کلید تصادفی ۲۵۶ بیتی (۳۲ بایت، به صورت ۶۴ نویسه‌ی hex). فقط به کسی بده که قرار است با او گفتگو کنی. این رمز ورود نیست."), 12, Neo.DIM))

        genBtn = Neo.primary(this, tr("Create key", "ساخت کلید"))
        Neo.add(c1, genBtn, 12)
        genBtn.setOnClickListener { createKey() }

        keyArea = Neo.vertical(this)
        Neo.add(keyArea, titleRow(tr("My key", "کلید من")) { Vault.myKeyHex(this)?.let { Neo.copy(this, it) } }, 0)
        keyText = Neo.text(this, "", 14, Neo.TEXT)
        Neo.add(keyArea, scrollBox(keyText), 8)
        keyQr = ImageView(this)
        Neo.add(keyArea, keyQr, 10)
        val send = Neo.primary(this, tr("Send", "ارسال"))
        Neo.add(keyArea, send, 10)
        send.setOnClickListener { sendKey() }
        val renew = Neo.danger(this, tr("Create a new key", "ساخت کلید جدید"))
        Neo.add(keyArea, renew, 8)
        renew.setOnClickListener {
            Dialogs.confirm(this, tr("A new key replaces the old one. Your contacts must get the new key and their session keys are deleted. Continue?",
                "کلید جدید جای قبلی را می‌گیرد. مخاطب‌ها باید کلید جدید را بگیرند و کلیدهای جلسه پاک می‌شوند. ادامه؟")) {
                Vault.createKey(this)
                SessionVault.wipeKeys()
                refresh()
            }
        }
        c1.addView(keyArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Neo.add(col, c1, 0)

        // ---------- card 2: shared passphrase + salt ----------
        val c2 = Neo.card(this)
        c2.addView(Neo.text(this, tr("Shared passphrase + salt", "عبارت و salt مشترک"), 16, Neo.TEXT).apply { typeface = Neo.BOLD })
        c2.addView(Neo.text(this, tr("ONE of you creates it and gives it to the other in person. Both enter the same passphrase + salt (and each other's key) in the contact.",
            "فقط یک نفر می‌سازد و حضوری به دیگری می‌دهد. هر دو نفر همین عبارت و salt (و کلید یکدیگر) را در مخاطب وارد می‌کنند."), 12, Neo.DIM))
        val gen2 = Neo.button(this, tr("Create passphrase + salt", "ساخت عبارت و salt"))
        Neo.add(c2, gen2, 10)
        gen2.setOnClickListener {
            val (ps, sl) = SharedSecret.generate(this)
            Db.get(this).setMeta("shared_pass", ps)
            Db.get(this).setMeta("shared_salt", sl)
            refresh()
        }
        passArea = Neo.vertical(this)
        Neo.add(passArea, titleRow(tr("Passphrase and salt", "عبارت و salt")) {
            val ps = Db.get(this).getMeta("shared_pass")
            val sl = Db.get(this).getMeta("shared_salt")
            if (!ps.isNullOrEmpty() && !sl.isNullOrEmpty()) Neo.copy(this, SharedSecret.toLine(ps, sl))
        }, 10)
        passText = Neo.text(this, "", 14, Neo.TEXT)
        Neo.add(passArea, scrollBox(passText), 8)
        passQr = ImageView(this)
        Neo.add(passArea, passQr, 10)
        val send2 = Neo.primary(this, tr("Send", "ارسال"))
        Neo.add(passArea, send2, 10)
        send2.setOnClickListener { sendShared() }
        c2.addView(passArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Neo.add(col, c2, 14)

        if (firstRun) {
            val go = Neo.primary(this, tr("Continue", "ادامه"))
            Neo.add(col, go, 18)
            go.setOnClickListener { goList() }
        }
        setContentView(Neo.backdrop(this, outer))
    }

    override fun onReady() { refresh() }

    private fun createKey() {
        Vault.createKey(this)
        refresh()
    }

    private fun refresh() {
        val k = Vault.myKeyHex(this)
        genBtn.visibility = if (k == null) android.view.View.VISIBLE else android.view.View.GONE
        keyArea.visibility = if (k == null) android.view.View.GONE else android.view.View.VISIBLE
        if (k != null) {
            keyText.text = k
            keyQr.setImageBitmap(QrTools.render(k, Neo.dp(this, 240), true))
        }
        val ps = Db.get(this).getMeta("shared_pass")
        val sl = Db.get(this).getMeta("shared_salt")
        val has = !ps.isNullOrEmpty() && !sl.isNullOrEmpty()
        passArea.visibility = if (has) android.view.View.VISIBLE else android.view.View.GONE
        if (has) {
            passText.text = ps + "\n\nsalt: " + sl
            passQr.setImageBitmap(QrTools.render(SharedSecret.toLine(ps!!, sl!!), Neo.dp(this, 240), true))
        }
    }

    private fun sendKey() {
        val k = Vault.myKeyHex(this) ?: return
        Dialogs.choose(this, tr("Send my key", "ارسال کلید من"),
            arrayOf(tr("As text", "به صورت متن"), tr("As JSON file", "به صورت فایل json")), -1) { w ->
            Dialogs.confirm(this, tr("This contains your KEY. Send it only to the person you will talk with. Continue?",
                "این «کلید من» را دارد. فقط به همان مخاطب بفرست. ادامه؟")) {
                if (w == 0) SendFlow.shareText(this, k)
                else Files.share(this, Files.exportKeyJson(this, k), "application/json")
            }
        }
    }

    private fun sendShared() {
        val ps = Db.get(this).getMeta("shared_pass")
        val sl = Db.get(this).getMeta("shared_salt")
        if (ps.isNullOrEmpty() || sl.isNullOrEmpty()) return
        Dialogs.choose(this, tr("Send passphrase + salt", "ارسال عبارت و salt"),
            arrayOf(tr("As text", "به صورت متن"), tr("As JSON file", "به صورت فایل json")), -1) { w ->
            Dialogs.confirm(this, tr("This is SECRET. Send it only to the person you will talk with. Continue?",
                "این محرمانه است. فقط به همان مخاطب بفرست. ادامه؟")) {
                if (w == 0) SendFlow.shareText(this, SharedSecret.toLine(ps, sl))
                else Files.share(this, Online.exportText(this, "Shadow-shared.json", SharedSecret.toJson(ps, sl)), "application/json")
            }
        }
    }

    private fun goList() {
        startActivity(Intent(this, ChatListActivity::class.java))
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (firstRun) goList() else super.onBackPressed()
    }
}
