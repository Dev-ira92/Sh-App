package com.shadow.society.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "My key": press "Create key" → 32 random bytes shown as 64 hex characters in a 3-line scrolling box,
 * with Copy, the QR code and Send (text / JSON file).
 */
class KeyActivity : BaseActivity() {
    private var firstRun = false

    private lateinit var genBtn: Button
    private lateinit var keyArea: LinearLayout
    private lateinit var keyText: TextView
    private lateinit var keyQr: ImageView

    /** A box that shows exactly 3 lines and scrolls for the rest. */
    private fun scrollBox(tv: TextView): FrameLayout {
        tv.setTextIsSelectable(true)
        tv.typeface = Typeface.MONOSPACE
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

        val c1 = Neo.card(this)
        c1.addView(Neo.text(this, tr("A random 256-bit key (32 bytes, shown as 64 hex characters). Give it only to the person you will talk with, in person or over a channel you trust. This is NOT your login password.",
            "یک کلید تصادفی ۲۵۶ بیتی (۳۲ بایت، به صورت ۶۴ نویسه‌ی hex). فقط به کسی بده که قرار است با او گفتگو کنی، حضوری یا از کانال مطمئن. این رمز ورود نیست."), 12, Neo.DIM))

        genBtn = Neo.primary(this, tr("Create key", "ساخت کلید"))
        Neo.add(c1, genBtn, 12)
        genBtn.setOnClickListener {
            Vault.createKey(this)
            refresh()
        }

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

        if (firstRun) {
            val go = Neo.primary(this, tr("Continue", "ادامه"))
            Neo.add(col, go, 18)
            go.setOnClickListener { goList() }
        }
        setContentView(Neo.backdrop(this, outer))
    }

    override fun onReady() { refresh() }

    private fun refresh() {
        val k = Vault.myKeyHex(this)
        genBtn.visibility = if (k == null) View.VISIBLE else View.GONE
        keyArea.visibility = if (k == null) View.GONE else View.VISIBLE
        if (k != null) {
            keyText.text = k
            keyQr.setImageBitmap(QrTools.render(k, Neo.dp(this, 240), true))
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

    private fun goList() {
        startActivity(Intent(this, ChatListActivity::class.java))
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (firstRun) goList() else super.onBackPressed()
    }
}
