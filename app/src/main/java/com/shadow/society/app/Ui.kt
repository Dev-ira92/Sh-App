package com.shadow.society.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.text.format.DateFormat
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.Random
import java.util.TimeZone

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

private fun dayKey(ts: Long): Long = (ts + TimeZone.getDefault().getOffset(ts)) / 86400000L

private fun dayLabel(ts: Long): String {
    val today = dayKey(System.currentTimeMillis())
    val k = dayKey(ts)
    return when (k) {
        today -> tr("Today", "امروز")
        today - 1 -> tr("Yesterday", "دیروز")
        else -> DateFormat.format("dd MMM yyyy", ts).toString()
    }
}

/**
 * Bubbles: mine on the right, friend's on the left, each with a tail. Footer shows time, import/export
 * marks and SMS status icons. Fire messages are stored as ciphertext: if the session key in RAM matches
 * (same keys, verbal code, method and formula on both sides) the text is decrypted; otherwise ONLY the
 * raw text is shown. Long-press a bubble to copy its text.
 */
class MessageAdapter(private val ctx: Context) : BaseAdapter() {
    private var items: List<Msg> = emptyList()

    fun set(l: List<Msg>) { items = l; notifyDataSetChanged() }
    override fun getCount(): Int = items.size
    override fun getItem(i: Int): Msg = items[i]
    override fun getItemId(i: Int): Long = items[i].id

    private fun icon(name: String, tint: Int): ImageView {
        val iv = Icons.view(ctx, name, tint, 12, Neo.fs)
        iv.setPadding(Neo.dp(ctx, 2), 0, Neo.dp(ctx, 2), 0)
        return iv
    }

    override fun getView(pos: Int, v: View?, parent: ViewGroup?): View {
        val m = items[pos]
        val pal = Neo.P
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Neo.dp(ctx, 10), Neo.dp(ctx, 2), Neo.dp(ctx, 10), Neo.dp(ctx, 2))
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        if (pos == 0 || dayKey(items[pos - 1].ts) != dayKey(m.ts)) {
            val sep = Neo.text(ctx, dayLabel(m.ts), 11, pal.recvText).apply {
                background = Neo.box(ctx, Neo.withAlpha(pal.recv, 235), Neo.withAlpha(Neo.DIM, 90), 12)
                setPadding(Neo.dp(ctx, 12), Neo.dp(ctx, 3), Neo.dp(ctx, 12), Neo.dp(ctx, 3))
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.topMargin = Neo.dp(ctx, 8)
            lp.bottomMargin = Neo.dp(ctx, 6)
            col.addView(sep, lp)
        }

        var text = m.body
        var ok = true
        if (m.cipher) {
            ok = false
            val key = SessionVault.key(m.contactId)
            if (key != null) {
                try {
                    text = String(Crypto.decrypt(key, Base64.decode(m.body, Base64.NO_WRAP)))
                    ok = true
                } catch (e: Exception) { }
            }
        }
        val shown = text
        val fg = if (m.sent) pal.sentText else pal.recvText
        val soft = Neo.withAlpha(fg, 170)
        val tail = Neo.dp(ctx, 7)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Neo.bubble(ctx, m.sent)
            val hp = Neo.dp(ctx, 12)
            setPadding(hp + (if (m.sent) 0 else tail), Neo.dp(ctx, 8), hp + (if (m.sent) tail else 0), Neo.dp(ctx, 6))
        }
        val tv = Neo.text(ctx, shown, 15, if (ok) fg else Neo.withAlpha(fg, 150)).apply {
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.72f).toInt()
        }
        box.addView(tv)

        val foot = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        if (!ok) foot.addView(icon("lock", soft))
        if ((m.tag and 1) != 0) {
            foot.addView(icon("download", soft))
            foot.addView(Neo.text(ctx, tr("imported", "وارد شده") + "  ", 10, soft))
        }
        if ((m.tag and 2) != 0) {
            foot.addView(icon("upload", soft))
            foot.addView(Neo.text(ctx, tr("exported", "خروجی") + "  ", 10, soft))
        }
        foot.addView(Neo.text(ctx, DateFormat.format("HH:mm", m.ts).toString(), 10, soft))
        if (m.sent) {
            when (m.status) {
                0 -> foot.addView(icon("clock", soft))
                2 -> {
                    foot.addView(icon("cross", pal.red))
                    foot.addView(Neo.text(ctx, tr("failed", "ناموفق"), 10, pal.red))
                }
                3 -> foot.addView(icon("check2", fg))
                else -> foot.addView(icon("check", soft))
            }
        }
        box.addView(foot)

        val copy = View.OnLongClickListener { Neo.copy(ctx, shown); true }
        box.setOnLongClickListener(copy)
        tv.setOnLongClickListener(copy)

        val lpB = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpB.gravity = if (m.sent) Gravity.RIGHT else Gravity.LEFT
        col.addView(box, lpB)
        return col
    }
}

/** Build the session key: My Key (automatic) + Friend Key (type, scan QR or import a file) + Verbal code + method. */
fun showUnlock(a: Activity, c: Contact, scan: ((String) -> Unit) -> Unit, done: () -> Unit) {
    val sv = ScrollView(a)
    val col = Neo.vertical(a)
    val p = Neo.dp(a, 20)
    col.setPadding(p, p / 2, p, 0)
    val friend = Neo.input(a, tr("friend's key", "کلید دوستم")).apply {
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

    val verbal = Neo.input(a, tr("verbal code (2-3 words)", "کد شفاهی (۲-۳ کلمه)"))
    val formula = Neo.input(a, tr("mixer formula", "فرمول اختلاط")).apply { setText(KeyMixer.DEFAULT_FORMULA) }
    val descView = Neo.text(a, "", 11, Neo.DIM)
    var method = MixMethod.CONCAT
    val mBtn = Neo.button(a, "")

    fun refresh() {
        mBtn.text = "⚙ " + method.label()
        descView.text = method.desc
        formula.visibility = if (method == MixMethod.FORMULA) View.VISIBLE else View.GONE
    }
    mBtn.setOnClickListener {
        val all = MixMethod.values()
        Dialogs.choose(a, tr("Mixing method", "روش ترکیب کلید"),
            Array(all.size) { all[it].label() }, method.ordinal) { method = all[it]; refresh() }
    }
    refresh()

    Neo.add(col, frow)
    Neo.add(col, verbal, 8)
    Neo.add(col, mBtn, 8)
    Neo.add(col, descView, 2)
    Neo.add(col, formula, 8)
    sv.addView(col)

    val d = Neo.dialog(a)
        .setTitle(c.name)
        .setView(sv)
        .setPositiveButton(tr("Derive", "ساخت کلید"), null)
        .setNegativeButton(tr("Cancel", "انصراف"), null)
        .create()
    d.show()
    d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn ->
        val f = friend.text.toString()
        val vb = verbal.text.toString()
        val fm = formula.text.toString()
        val mt = method
        btn.isEnabled = false
        Thread {
            var fp: String? = null
            var err: String? = null
            try {
                val mixed = KeyMixer.mix(mt, Vault.myKey(a), f, vb, fm)
                val key = KeyMixer.deriveKey(mixed)
                fp = KeyMixer.fingerprint(key)
                SessionVault.putKey(a, c.id, key)
            } catch (e: Exception) { err = e.message ?: "error" }
            val fpF = fp
            val errF = err
            a.runOnUiThread {
                if (errF != null) {
                    btn.isEnabled = true
                    Toast.makeText(a, tr("invalid input: ", "ورودی نامعتبر: ") + errF, Toast.LENGTH_LONG).show()
                } else {
                    d.dismiss()
                    Dialogs.info(a, tr("Fingerprint", "اثر انگشت کلید"),
                        fpF + "\n\n" + tr("Read this aloud to your contact. Different code = different inputs; messages will show raw text only.",
                            "این کد را شفاهی با مخاطب مقایسه کنید. اگر فرق داشت، ورودی‌ها یکسان نیست و پیام‌ها فقط به شکل متن رمزشده دیده می‌شوند."))
                    done()
                }
            }
        }.start()
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
