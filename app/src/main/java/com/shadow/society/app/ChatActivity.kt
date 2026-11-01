package com.shadow.society.app

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import java.io.File

class ChatActivity : BaseActivity() {
    private var contact: Contact? = null
    private var contactId = -1L
    private lateinit var adapter: MessageAdapter
    private lateinit var input: EditText
    private lateinit var header: TextView
    private lateinit var counter: TextView

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        contactId = intent.getLongExtra("id", -1L)

        val root = Neo.vertical(this)
        val p = Neo.dp(this, 12)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Neo.PANEL)
        bar.setPadding(p / 3, p / 3, p / 3, p / 3)
        bar.addView(Icons.button(this, "back", Neo.BARTEXT) { finish() })
        header = Neo.text(this, "", 15, Neo.BARTEXT)
        header.typeface = Neo.BOLD
        bar.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Icons.button(this, "key", Neo.BARTEXT) { unlock() })
        bar.addView(Icons.button(this, "download", Neo.BARTEXT) { importMenu() })
        bar.addView(Icons.button(this, "upload", Neo.BARTEXT) { exportMenu() })
        root.addView(bar)

        val lv = ListView(this)
        lv.setBackgroundColor(0)
        lv.cacheColorHint = 0
        lv.divider = null
        lv.selector = ColorDrawable(0)
        lv.isStackFromBottom = true
        lv.transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        adapter = MessageAdapter(this)
        lv.adapter = adapter
        root.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        counter = Neo.text(this, "", 11, Neo.DIM)
        counter.setPadding(p, 2, p, 2)
        counter.setBackgroundColor(Neo.withAlpha(Neo.PANEL, 200))
        root.addView(counter)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setBackgroundColor(Neo.PANEL)
        row.setPadding(p / 2, p / 2, p / 2, p / 2)
        input = Neo.input(this, tr("Message", "پیام"))
        input.maxLines = 4
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sendBtn = Icons.button(this, "send", Neo.P.onAccent) { send() }
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(Neo.ACCENT)
        sendBtn.background = oval
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(p / 2, 0, 0, 0)
        row.addView(sendBtn, lp)
        root.addView(row)
        setContentView(Neo.backdrop(this, root))

        input.doAfterTextChanged { updateCounter(it.toString()) }
    }

    override fun onReady() {
        val c = Contacts.byId(this, contactId)
        if (c == null) { finish(); return }
        contact = c
        header.text = c.name + "\n" + c.phone + "  " + (if (c.level == 1) "🔴" else "🟢")
        updateCounter(input.text.toString())
        reload()
        Inbox.listener = Runnable { reload() }
        Thread {
            if (Inbox.drain(this) > 0) runOnUiThread { reload() }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        Inbox.listener = null
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun reload() {
        val c = contact ?: return
        if (!SessionVault.isUnlocked()) return
        Messages.markRead(this, c.id)
        adapter.set(Messages.list(this, c.id))
    }

    private fun updateCounter(t: String) {
        val c = contact ?: return
        val bytes = t.toByteArray().size + 1 + (if (c.level == 1) 28 else 0)
        val parts = if (t.isEmpty()) "" else "   ~" + Codec.estimateParts(bytes) + " SMS"
        counter.text = if (c.level == 1) "🔴 AES-256-GCM$parts"
                       else "🟢 " + tr("plain text — NOT secret", "متن ساده — محرمانه نیست") + parts
    }

    // ---------- keys ----------
    private fun unlock() {
        val c = contact ?: return
        showUnlock(this, c, this::scanKey) { reload() }
    }

    // ---------- send ----------
    private fun send() {
        val c = contact ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (text.length > 500) { toast(tr("max 500 characters", "حداکثر ۵۰۰ کاراکتر")); return }
        if (!Perms.hasSend(this)) { Perms.request(this); return }

        val payload: ByteArray
        if (c.level == 1) {
            val key = SessionVault.key(c.id)
            if (key == null) { unlock(); return }
            payload = Codec.secure(key, text)
        } else {
            payload = Codec.plain(text)
        }

        val id = Messages.insert(this, Msg(0, c.id, text, true, System.currentTimeMillis(), true, false, 0, 0))
        try {
            Sender.send(this, c.phone, payload, id)
        } catch (e: Exception) {
            Messages.setStatus(this, id, 2)
        }
        input.setText("")
        reload()
    }

    // ---------- export ----------
    private fun exportMenu() {
        val labels = arrayOf(
            tr("Chat → raw file (encrypted)", "گفتگو ← فایل خام (رمزشده)"),
            tr("Chat → readable text (NOT encrypted)", "گفتگو ← متن خوانا (بدون رمز)"),
            tr("Typed message → file", "پیام نوشته‌شده ← فایل"))
        Dialogs.choose(this, tr("Export", "خروجی"), labels, -1) { w ->
            when (w) {
                0 -> doExport(false)
                1 -> Dialogs.confirm(this, tr("This file will NOT be encrypted. Continue?", "این فایل رمزنگاری نیست. ادامه؟")) { doExport(true) }
                else -> exportTyped()
            }
        }
    }

    private fun doExport(readable: Boolean) {
        val c = contact ?: return
        val r = if (readable) Files.exportReadable(this, c) else Files.exportRaw(this, c)
        reload()
        offerShare(r.file, "${r.count} " + tr("messages exported", "پیام خروجی گرفته شد") +
                (if (r.skipped > 0) "\n${r.skipped} " + tr("skipped (no key)", "رد شد (کلید نیست)") else ""))
    }

    private fun exportTyped() {
        val c = contact ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) { toast(tr("type a message first", "اول پیام را بنویس")); return }
        val payload: ByteArray
        if (c.level == 1) {
            val key = SessionVault.key(c.id)
            if (key == null) { unlock(); return }
            payload = Codec.secure(key, text)
        } else {
            payload = Codec.plain(text)
        }
        val now = System.currentTimeMillis()
        val f = Files.exportMessage(this, c, payload, now)
        Messages.insert(this, Msg(0, c.id, text, true, now, true, false, 1, 2))
        input.setText("")
        reload()
        offerShare(f, tr("1 message exported as a file", "۱ پیام به صورت فایل خروجی گرفته شد"))
    }

    private fun offerShare(f: File, msg: String) {
        Neo.dialog(this).setTitle(tr("Export ready", "خروجی آماده شد")).setMessage(msg)
            .setPositiveButton(tr("Send with…", "ارسال با…")) { _, _ -> Files.share(this, f) }
            .setNegativeButton(tr("Close", "بستن"), null).show()
    }

    // ---------- import ----------
    private fun importMenu() {
        val c = contact ?: return
        val labels = arrayOf(tr("From file (.txt)", "از فایل (.txt)"), tr("Paste text", "paste متن"))
        Dialogs.choose(this, tr("Import", "ایمپورت"), labels, -1) { w ->
            if (w == 0) pickLauncher.launch(arrayOf("*/*")) else showReceive(this, c) { reload() }
        }
    }

    private fun onFilePicked(uri: Uri) {
        val c = contact ?: return
        val text = Files.readUri(this, uri)
        if (text == null) { toast(tr("cannot read file", "فایل خوانده نشد")); return }
        val labels = arrayOf(tr("from my contact", "از طرف مخاطب من"), tr("my own backup", "پشتیبان خودم"))
        Dialogs.choose(this, tr("This file is…", "این فایل…"), labels, 0) { w ->
            Thread {
                val r = Files.importText(this, c, text, w == 0)
                runOnUiThread {
                    reload()
                    Dialogs.info(this, tr("Import finished", "ایمپورت انجام شد"),
                        "${r.imported} " + tr("messages imported", "پیام وارد شد") +
                                (if (r.ignored > 0) "\n${r.ignored} " + tr("ignored (duplicate/invalid)", "نادیده (تکراری/نامعتبر)") else ""))
                }
            }.start()
        }
    }
}
