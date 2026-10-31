package com.shadow.society.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged

class ChatActivity : BaseActivity() {
    private var contact: Contact? = null
    private var contactId = -1L
    private lateinit var adapter: MessageAdapter
    private lateinit var input: EditText
    private lateinit var header: TextView
    private lateinit var counter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        contactId = intent.getLongExtra("id", -1L)

        val root = Neo.vertical(this)
        root.setBackgroundColor(Neo.BG)
        val p = Neo.dp(this, 12)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Neo.PANEL)
        bar.setPadding(p, p / 2, p / 2, p / 2)
        header = Neo.text(this, "", 15, Neo.GREEN)
        header.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        bar.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Neo.iconButton(this, "🔑") { unlock() })
        bar.addView(Neo.iconButton(this, "📥") {
            contact?.let { showReceive(this, it) { reload() } }
        })
        root.addView(bar)

        val lv = ListView(this)
        lv.divider = null
        lv.isStackFromBottom = true
        lv.transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        adapter = MessageAdapter(this)
        lv.adapter = adapter
        root.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        counter = Neo.text(this, "", 10, Neo.DIM)
        counter.setPadding(p, 0, p, 0)
        root.addView(counter)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(p / 2, p / 2, p / 2, p / 2)
        input = Neo.input(this, "> _")
        input.maxLines = 4
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Neo.iconButton(this, "📤") { send() })
        root.addView(row)
        setContentView(root)

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

    private fun reload() {
        val c = contact ?: return
        if (!SessionVault.isUnlocked()) return
        Messages.markRead(this, c.id)
        adapter.set(Messages.list(this, c.id))
    }

    private fun updateCounter(t: String) {
        val c = contact ?: return
        val bytes = t.toByteArray().size + 1 + (if (c.level == 1) 28 else 0)
        counter.text = if (t.isEmpty()) "" else "~" + Codec.estimateParts(bytes) + " SMS"
    }

    private fun unlock() {
        val c = contact ?: return
        showUnlock(this, c) { reload() }
    }

    private fun send() {
        val c = contact ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (text.length > 500) {
            Toast.makeText(this, tr("max 500 characters", "حداکثر ۵۰۰ کاراکتر"), Toast.LENGTH_SHORT).show()
            return
        }
        if (!Perms.hasSend(this)) { Perms.request(this); return }

        val payload: ByteArray
        if (c.level == 1) {
            val key = SessionVault.key(c.id)
            if (key == null) { unlock(); return }
            payload = Codec.secure(key, text)
        } else {
            payload = Codec.plain(text)
        }

        try {
            Sender.send(c.phone, payload)
        } catch (e: Exception) {
            Toast.makeText(this, tr("send failed", "ارسال ناموفق"), Toast.LENGTH_SHORT).show()
            return
        }
        Messages.insert(this, Msg(0, c.id, text, true, System.currentTimeMillis(), true, false))
        input.setText("")
        reload()
    }
}
