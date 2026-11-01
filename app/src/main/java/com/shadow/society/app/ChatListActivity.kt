package com.shadow.society.app

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast

class ChatListActivity : BaseActivity() {
    private lateinit var adapter: ContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return

        val root = Neo.vertical(this)
        val p = Neo.dp(this, 12)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Neo.PANEL)
        bar.setPadding(p, p / 3, p / 2, p / 3)
        val title = Neo.text(this, "Sh-So", 22, Neo.BARTEXT)
        title.typeface = Neo.BOLD
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Icons.button(this, "tune", Neo.BARTEXT) { startActivity(Intent(this, SettingsActivity::class.java)) })
        val panicBtn = Icons.button(this, "alert", Neo.BARTEXT) {
            Toast.makeText(this, tr("hold to destroy everything", "برای نابودی همه‌چیز نگه دارید"), Toast.LENGTH_SHORT).show()
        }
        panicBtn.setOnLongClickListener { panic(); true }
        bar.addView(panicBtn)
        bar.addView(Icons.button(this, "exit", Neo.BARTEXT) { SessionVault.lock(); finishAffinity() })
        root.addView(bar)

        val holder = FrameLayout(this)
        val lv = ListView(this)
        lv.setBackgroundColor(0)
        lv.cacheColorHint = 0
        lv.divider = ColorDrawable(Neo.withAlpha(Neo.DIM, 60))
        lv.dividerHeight = Neo.dp(this, 1)
        lv.selector = ColorDrawable(Neo.withAlpha(Neo.ACCENT, 40))
        adapter = ContactAdapter(this)
        lv.adapter = adapter
        val empty = Neo.text(this, tr("> no contacts yet_", "> هنوز مخاطبی نیست_"), 14, Neo.DIM)
        empty.gravity = Gravity.CENTER
        holder.addView(lv)
        holder.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        lv.emptyView = empty

        val fab = Icons.button(this, "add", Neo.P.onAccent) { showAddContact(this, null) { reload() } }
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(Neo.ACCENT)
        fab.background = oval
        val q = Neo.dp(this, 16)
        fab.setPadding(q, q, q, q)
        val flp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.RIGHT)
        flp.setMargins(0, 0, Neo.dp(this, 18), Neo.dp(this, 18))
        holder.addView(fab, flp)

        root.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(Neo.backdrop(this, root))

        lv.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, ChatActivity::class.java).putExtra("id", adapter.getItem(pos).id))
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            val c = adapter.getItem(pos)
            Dialogs.choose(this, c.name, arrayOf(tr("Edit", "ویرایش"), tr("Delete", "حذف")), -1) { which ->
                if (which == 0) {
                    showAddContact(this, c) { reload() }
                } else {
                    Dialogs.confirm(this, tr("Delete contact and all messages?", "مخاطب و همه پیام‌ها حذف شود؟")) {
                        Contacts.delete(this, c.id)
                        reload()
                    }
                }
            }
            true
        }
        Perms.request(this)
    }

    override fun onReady() {
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
        if (!SessionVault.isUnlocked()) return
        val l = Contacts.all(this)
        adapter.set(l, l.associate { it.id to Messages.unread(this, it.id) })
    }

    /** Long-press the alert icon: keys, database, spool, settings — gone. */
    private fun panic() {
        Duress.wipeAll(this)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
