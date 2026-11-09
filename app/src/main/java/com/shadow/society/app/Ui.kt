package com.shadow.society.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Random

object Dialogs {
    fun choose(c: Context, title: String, labels: Array<String>, sel: Int, cb: (Int) -> Unit) {
        Neo.dialog(c).setTitle(title)
            .setSingleChoiceItems(labels, sel) { d, w -> d.dismiss(); cb(w) }
            .setNegativeButton(tr("Cancel", "انصراف"), null).show()
    }

    fun confirm(c: Context, msg: String, yes: () -> Unit) {
        Neo.dialog(c).setMessage(msg)
            .setPositiveButton(tr("Yes", "بله")) { _, _ -> yes() }
            .setNegativeButton(tr("Cancel", "انصراف"), null).show()
    }

    fun info(c: Context, title: String, msg: String) {
        Neo.dialog(c).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }
}

/** Falling green code (Matrix theme). */
class MatrixRainView(c: Context) : View(c) {
    private val g = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉ0123456789:=*+<>|"
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fade = Paint().apply { color = 0x24000000 }
    private val r = Random()
    private var buf: Bitmap? = null
    private var bc: Canvas? = null
    private var size = 1
    private var cols = 0
    private var y = IntArray(0)

    private fun build(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        size = Neo.dp(context, 14)
        glyph.textSize = size.toFloat()
        cols = w / size + 1
        y = IntArray(cols) { -r.nextInt(h) }
        buf?.recycle()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf = b
        val cv = Canvas(b)
        cv.drawColor(Color.BLACK)
        bc = cv
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { build(w, h) }

    override fun onDraw(canvas: Canvas) {
        if (buf == null) build(width, height)
        val b = buf ?: return
        val cv = bc ?: return
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fade)
        for (i in 0 until cols) {
            glyph.color = if (r.nextInt(12) == 0) 0xFFCCFFD6.toInt() else 0xFF00FF41.toInt()
            cv.drawText(g[r.nextInt(g.length)].toString(), (i * size).toFloat(), y[i].toFloat(), glyph)
            y[i] += size
            if (y[i] > height && r.nextFloat() > 0.975f) y[i] = 0
        }
        canvas.drawBitmap(b, 0f, 0f, null)
        postInvalidateDelayed(70)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        buf?.recycle()
        buf = null
    }
}

class ContactAdapter(private val ctx: Context) : BaseAdapter() {
    private var items: List<Contact> = emptyList()
    private var unread: Map<Long, Int> = emptyMap()

    fun set(l: List<Contact>, u: Map<Long, Int>) { items = l; unread = u; notifyDataSetChanged() }
    override fun getCount(): Int = items.size
    override fun getItem(i: Int): Contact = items[i]
    override fun getItemId(i: Int): Long = items[i].id

    override fun getView(pos: Int, v: View?, parent: ViewGroup?): View {
        val c = items[pos]
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Neo.dp(ctx, 12)
            setPadding(p, p, p, p)
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val s = Neo.dp(ctx, 52)
        row.addView(Neo.avatar(ctx, c.name), LinearLayout.LayoutParams(s, s))

        val col = Neo.vertical(ctx).apply { setPadding(Neo.dp(ctx, 14), 0, Neo.dp(ctx, 8), 0) }
        col.addView(Neo.text(ctx, c.name, 17, Neo.TEXT).apply { typeface = Neo.BOLD })
        col.addView(Neo.text(ctx, c.phone, 12, Neo.DIM))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val u = unread[c.id] ?: 0
        if (u > 0) {
            val pill = Neo.text(ctx, "$u", 12, Neo.P.onAccent).apply {
                background = Neo.box(ctx, Neo.ACCENT, Neo.ACCENT, 12)
                setPadding(Neo.dp(ctx, 8), Neo.dp(ctx, 2), Neo.dp(ctx, 8), Neo.dp(ctx, 2))
            }
            row.addView(pill)
        }
        val unlocked = c.level == 1 && SessionVault.key(c.id) != null
        val name = if (c.level == 1) (if (unlocked) "unlock" else "lock") else "eye"
        val tint = if (c.level == 1) (if (unlocked) Neo.ACCENT else Neo.RED) else Neo.DIM
        val iv = Icons.view(ctx, name, tint, 22, Neo.fs)
        iv.setPadding(Neo.dp(ctx, 10), 0, 0, 0)
        row.addView(iv)
        return row
    }
}

/** Keys of one chat: just the friend's key (the session key is built from both keys and kept in the vault). */
fun showUnlock(a: Activity, c: Contact, scan: ((String) -> Unit) -> Unit, done: () -> Unit) {
    val col = Neo.vertical(a)
    val p = Neo.dp(a, 20)
    col.setPadding(p, p / 2, p, 0)
    val friend = Neo.input(a, tr("friend's key (64 hex characters)", "کلید دوستم (۶۴ نویسه‌ی hex)")).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 2
    }
    val frow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    frow.addView(friend, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    frow.addView(Icons.button(a, "scan", Neo.ACCENT) { scan { friend.setText(it) } })
    frow.addView(Icons.button(a, "file", Neo.ACCENT) { (a as? BaseActivity)?.pickKeyFile { friend.setText(it) } })
    Neo.add(col, frow)

    val d = Neo.dialog(a)
        .setTitle(c.name)
        .setView(col)
        .setPositiveButton(tr("Build key", "ساخت کلید"), null)
        .setNegativeButton(tr("Cancel", "انصراف"), null)
        .create()
    d.show()
    d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn ->
        val fk = parseFriendKey(friend.text.toString())
        if (fk == null) {
            Toast.makeText(a, tr("The friend's key must be 64 hex characters", "کلید دوستم باید ۶۴ نویسه‌ی hex باشد"), Toast.LENGTH_LONG).show()
        } else {
            btn.isEnabled = false
            deriveAndStore(a as AppCompatActivity, c.id, fk) { fp, err ->
                if (err != null) {
                    btn.isEnabled = true
                    Toast.makeText(a, err, Toast.LENGTH_LONG).show()
                } else {
                    d.dismiss()
                    Dialogs.info(a, tr("Fingerprint", "اثر انگشت کلید"),
                        fp + "\n\n" + tr("Read this aloud to your contact. If it differs, messages will not decrypt.",
                            "این کد را شفاهی با مخاطب مقایسه کنید. اگر فرق داشت، پیام‌ها رمزگشایی نمی‌شوند."))
                    done()
                }
            }
        }
    }
}

/** Paste raw "TXT|..." lines, a SHSO1 export, or a Base64 ciphertext copied from a bubble. */
fun showReceive(a: Activity, c: Contact, done: () -> Unit) {
    val col = Neo.vertical(a)
    val p = Neo.dp(a, 20)
    col.setPadding(p, p / 2, p, 0)
    val e = Neo.input(a, "TXT|a1b2c3d4|0|2|...   /   SHSO1|...   /   base64").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 5
        gravity = Gravity.TOP
    }
    Neo.add(col, e)
    val d = Neo.dialog(a)
        .setTitle(tr("Import pasted text", "ایمپورت متن paste‌شده"))
        .setView(col)
        .setPositiveButton(tr("Import", "وارد کن"), null)
        .setNegativeButton(tr("Close", "بستن"), null)
        .create()
    d.show()
    d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val txt = e.text.toString()
        d.dismiss()
        Thread {
            val r = Files.importText(a, c, txt, true)
            a.runOnUiThread {
                Toast.makeText(a, tr("Imported: ", "وارد شد: ") + r.imported, Toast.LENGTH_LONG).show()
                done()
            }
        }.start()
    }
}
