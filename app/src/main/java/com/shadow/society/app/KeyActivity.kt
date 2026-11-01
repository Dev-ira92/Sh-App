package com.shadow.society.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * "My key" page: the secret you give to a friend. Copy it, or send it as text, a TXT file, a JSON file
 * or a QR image. Opens after the first login, and any time from the key icon / Settings.
 */
class KeyActivity : BaseActivity() {
    private val lengths = intArrayOf(10, 50, 100, 500, 1000)
    private var firstRun = false
    private lateinit var keyView: TextView
    private lateinit var qr: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        firstRun = intent.getBooleanExtra("first", false)

        val outer = Neo.vertical(this)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Neo.PANEL)
        val bp = Neo.dp(this, 4)
        bar.setPadding(bp * 3, bp, bp, bp)
        if (!firstRun) bar.addView(Icons.button(this, "back", Neo.BARTEXT) { finish() })
        val title = Neo.glow(Neo.text(this, tr("My key", "کلید من"), 18, Neo.BARTEXT))
        title.typeface = Neo.BOLD
        bar.addView(title)
        outer.addView(bar)

        val sv = ScrollView(this)
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 16)
        col.setPadding(p, p, p, p * 2)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(Neo.text(this, tr("This key was created for you. Together with your friend's key, a verbal code and a mixing method it builds the AES key of each chat. Give it only to the person you will talk with. This is NOT your lock password.",
            "این کلید برای تو ساخته شد. همراه با کلید دوستت، کد شفاهی و روش ترکیب، کلید AES هر چت را می‌سازد. فقط به کسی بده که قرار است با او گفتگو کنی. این رمز قفل برنامه نیست."), 13, Neo.DIM))

        keyView = Neo.text(this, "", 13, Neo.TEXT)
        keyView.setTextIsSelectable(true)
        Neo.add(col, keyView, 14)

        qr = ImageView(this)
        Neo.add(col, qr, 12)

        action(col, tr("Copy key", "کپی کلید"), 14) { Neo.copy(this, Vault.myKey(this)) }
        action(col, tr("Send as text…", "ارسال به صورت متن…"), 8) {
            guard { shareText(Vault.myKey(this)) }
        }
        action(col, tr("Send as TXT file…", "ارسال به صورت فایل TXT…"), 8) {
            guard { Files.share(this, Files.exportKeyTxt(this, Vault.myKey(this)), "text/plain") }
        }
        action(col, tr("Send as JSON file…", "ارسال به صورت فایل JSON…"), 8) {
            guard { Files.share(this, Files.exportKeyJson(this, Vault.myKey(this)), "application/json") }
        }
        action(col, tr("Send as QR image…", "ارسال به صورت تصویر QR…"), 8) {
            guard {
                val f = Files.exportKeyPng(this, Vault.myKey(this))
                if (f == null) toast(tr("Could not create the image", "ساخت تصویر ناموفق بود"))
                else Files.share(this, f, "image/png")
            }
        }
        action(col, tr("Generate a new key", "ساخت کلید جدید"), 18) { regenerate() }

        if (firstRun) {
            val go = action(col, tr("Continue", "ادامه"), 22) { goList() }
            go.setTextColor(Neo.P.onAccent)
            go.background = Neo.box(this, Neo.ACCENT, Neo.ACCENT, 8)
        }
        setContentView(Neo.backdrop(this, outer))
    }

    private fun action(col: LinearLayout, label: String, top: Int, f: () -> Unit): android.widget.Button {
        val b = Neo.button(this, label)
        Neo.add(col, b, top)
        b.setOnClickListener { f() }
        return b
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun guard(f: () -> Unit) {
        Dialogs.confirm(this, tr("This contains your KEY. Send it only to the person you will talk with, then agree on the verbal code with them.",
            "این شامل «کلید من» است. فقط به همان مخاطب بفرست و کد شفاهی را با او هماهنگ کن.")) { f() }
    }

    private fun shareText(k: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, k)
        startActivity(Intent.createChooser(i, tr("Send with...", "ارسال با...")))
    }

    private fun regenerate() {
        val labels = Array(lengths.size) {
            lengths[it].toString() + " " + tr("characters", "کاراکتر") + (if (lengths[it] < 50) "  ⚠" else "")
        }
        Dialogs.choose(this, tr("Key length", "طول کلید"), labels, -1) { w ->
            Dialogs.confirm(this, tr("Your friends must receive the new key again. Continue?",
                "دوستانت باید کلید جدید را دوباره بگیرند. ادامه؟")) {
                Vault.regenerate(this, lengths[w])
                SessionVault.wipeKeys()
                refresh()
            }
        }
    }

    override fun onReady() { refresh() }

    private fun refresh() {
        val k = Vault.myKey(this)
        keyView.text = k
        qr.setImageBitmap(QrTools.render(k, Neo.dp(this, 260), true))
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
