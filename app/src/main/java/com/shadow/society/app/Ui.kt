package com.shadow.society.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Random

/** Matrix theme + tiny view factories (the UI is built in code). */
object Neo {
    val BG = 0xFF000000.toInt()
    val GREEN = 0xFF00FF41.toInt()
    val DIM = 0xFF008F11.toInt()
    val DARK = 0xFF031A06.toInt()
    val PANEL = 0xFF0B0F0B.toInt()
    val RED = 0xFFFF3B3B.toInt()
    val MONO: Typeface = Typeface.MONOSPACE

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun text(c: Context, s: String, sp: Int, color: Int): TextView = TextView(c).apply {
        text = s
        textSize = sp.toFloat()
        setTextColor(color)
        typeface = MONO
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    fun box(c: Context, fill: Int, stroke: Int, r: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(c, 1), stroke)
        cornerRadius = dp(c, r).toFloat()
    }

    fun input(c: Context, hint: String): EditText = EditText(c).apply {
        this.hint = hint
        setHintTextColor(DIM)
        setTextColor(GREEN)
        typeface = MONO
        textSize = 15f
        background = box(c, DARK, DIM, 4)
        val p = dp(c, 10)
        setPadding(p, p, p, p)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        highlightColor = 0x5500FF41
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING   // keyboard must not learn
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    fun button(c: Context, label: String): Button = Button(c).apply {
        text = label
        isAllCaps = false
        setTextColor(GREEN)
        typeface = MONO
        background = box(c, BG, GREEN, 4)
    }

    fun iconButton(c: Context, glyph: String, onClick: (() -> Unit)? = null): Button = Button(c).apply {
        text = glyph
        isAllCaps = false
        textSize = 18f
        setTextColor(GREEN)
        setBackgroundColor(0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        val p = dp(c, 8)
        setPadding(p, p, p, p)
        if (onClick != null) setOnClickListener { onClick() }
    }

    fun vertical(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun add(parent: LinearLayout, v: View, top: Int = 0) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(parent.context, top)
        parent.addView(v, lp)
    }

    fun section(c: Context, s: String): TextView = text(c, "// $s", 13, DIM).apply {
        setPadding(0, dp(c, 22), 0, dp(c, 4))
    }

    fun copy(c: Context, s: String) {
        (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Sh-So", s))
        Toast.makeText(c, tr("Copied", "کپی شد"), Toast.LENGTH_SHORT).show()
    }
}

object Dialogs {
    fun choose(c: Context, title: String, labels: Array<String>, sel: Int, cb: (Int) -> Unit) {
        AlertDialog.Builder(c).setTitle(title)
            .setSingleChoiceItems(labels, sel) { d, w -> d.dismiss(); cb(w) }
            .setNegativeButton(tr("Cancel", "انصراف"), null).show()
    }

    fun confirm(c: Context, msg: String, yes: () -> Unit) {
        AlertDialog.Builder(c).setMessage(msg)
            .setPositiveButton(tr("Yes", "بله")) { _, _ -> yes() }
            .setNegativeButton(tr("Cancel", "انصراف"), null).show()
    }

    fun info(c: Context, title: String, msg: String) {
        AlertDialog.Builder(c).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }
}

/** Every screen after login extends this: locked vault => back to login. */
abstract class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionVault.isUnlocked()) toLogin()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        SessionVault.checkPulse()
        if (!SessionVault.isUnlocked()) { toLogin(); return }
        onReady()
    }

    protected abstract fun onReady()

    private fun toLogin() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/** Falling green code. */
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
        val oval = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Neo.DARK)
            setStroke(Neo.dp(ctx, 1), Neo.GREEN)
        }
        val avatar = Neo.text(ctx, c.name.take(1).uppercase(), 20, Neo.GREEN).apply {
            gravity = Gravity.CENTER
            background = oval
        }
        val s = Neo.dp(ctx, 46)
        row.addView(avatar, LinearLayout.LayoutParams(s, s))
        val col = Neo.vertical(ctx).apply { setPadding(Neo.dp(ctx, 12), 0, Neo.dp(ctx, 8), 0) }
        col.addView(Neo.text(ctx, c.name, 16, Neo.GREEN))
        col.addView(Neo.text(ctx, c.phone, 12, Neo.DIM))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val u = unread[c.id] ?: 0
        val lvl = if (c.level == 1) (if (SessionVault.key(c.id) != null) "🔓 " else "🔒 ") + "🔴" else "🟢"
        row.addView(Neo.text(ctx, (if (u > 0) "[$u] " else "") + lvl, 14, Neo.GREEN))
        return row
    }
}

/**
 * Fire messages are stored as ciphertext. If the key in RAM matches (same keys, verbal code,
 * method and formula on both sides) the text is decrypted. Otherwise ONLY the raw text is shown.
 * Long-press a bubble to copy its text.
 */
class MessageAdapter(private val ctx: Context) : BaseAdapter() {
    private var items: List<Msg> = emptyList()

    fun set(l: List<Msg>) { items = l; notifyDataSetChanged() }
    override fun getCount(): Int = items.size
    override fun getItem(i: Int): Msg = items[i]
    override fun getItemId(i: Int): Long = items[i].id

    override fun getView(pos: Int, v: View?, parent: ViewGroup?): View {
        val m = items[pos]
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (m.sent) Gravity.RIGHT else Gravity.LEFT
            val p = Neo.dp(ctx, 8)
            setPadding(p, Neo.dp(ctx, 4), p, Neo.dp(ctx, 4))
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        val color = if (m.sent) Neo.BG else if (ok) Neo.GREEN else Neo.DIM
        val bubble = Neo.text(ctx, shown, 15, color).apply {
            background = if (m.sent) Neo.box(ctx, Neo.GREEN, Neo.GREEN, 10) else Neo.box(ctx, Neo.DARK, Neo.DIM, 10)
            val p = Neo.dp(ctx, 10)
            setPadding(p, p, p, p)
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.8f).toInt()
            setOnLongClickListener { Neo.copy(ctx, shown); true }
        }
        row.addView(bubble)
        val stamp = (if (ok) "" else "🔒 ") + DateFormat.format("HH:mm  dd/MM", m.ts)
        row.addView(Neo.text(ctx, stamp, 10, Neo.DIM))
        return row
    }
}

fun showAddContact(a: Activity, ex: Contact?, done: () -> Unit) {
    val col = Neo.vertical(a)
    val p = Neo.dp(a, 20)
    col.setPadding(p, p / 2, p, 0)
    val name = Neo.input(a, tr("name", "نام"))
    val phone = Neo.input(a, tr("phone number", "شماره تلفن")).apply { inputType = InputType.TYPE_CLASS_PHONE }
    val fire = Switch(a).apply {
        text = tr("🔴 Fire level (encrypted)", "🔴 سطح آتش (رمزنگاری‌شده)")
        setTextColor(Neo.GREEN)
    }
    Neo.add(col, name)
    Neo.add(col, phone, 8)
    Neo.add(col, fire, 14)
    if (ex != null) { name.setText(ex.name); phone.setText(ex.phone); fire.isChecked = ex.level == 1 }

    val d = AlertDialog.Builder(a)
        .setTitle(if (ex == null) tr("New contact", "مخاطب جدید") else tr("Edit contact", "ویرایش مخاطب"))
        .setView(col)
        .setPositiveButton(tr("Save", "ذخیره"), null)
        .setNegativeButton(tr("Cancel", "انصراف"), null)
        .create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val n = name.text.toString().trim()
        val ph = phone.text.toString().trim()
        if (n.isEmpty() || ph.isEmpty()) {
            Toast.makeText(a, tr("name and number required", "نام و شماره لازم است"), Toast.LENGTH_SHORT).show()
        } else {
            val level = if (fire.isChecked) 1 else 0
            val ok = if (ex == null) Contacts.insert(a, Contact(0, n, ph, level, 0)) > 0
                     else Contacts.update(a, Contact(ex.id, n, ph, level, ex.createdAt))
            if (!ok) {
                Toast.makeText(a, tr("number already exists", "این شماره قبلاً ثبت شده"), Toast.LENGTH_SHORT).show()
            } else {
                d.dismiss()
                done()
            }
        }
    }
}

/** Build the session key: My Key (automatic) + Friend Key + Verbal code, combined with the chosen method. */
fun showUnlock(a: Activity, c: Contact, done: () -> Unit) {
    val sv = ScrollView(a)
    val col = Neo.vertical(a)
    val p = Neo.dp(a, 20)
    col.setPadding(p, p / 2, p, 0)
    val friend = Neo.input(a, tr("friend's key", "کلید دوستم")).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 2
    }
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

    Neo.add(col, friend)
    Neo.add(col, verbal, 8)
    Neo.add(col, mBtn, 8)
    Neo.add(col, descView, 2)
    Neo.add(col, formula, 8)
    sv.addView(col)

    val d = AlertDialog.Builder(a)
        .setTitle("🔑 " + c.name)
        .setView(sv)
        .setPositiveButton(tr("Derive", "ساخت کلید"), null)
        .setNegativeButton(tr("Cancel", "انصراف"), null)
        .create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn ->
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

/** Paste raw "TXT|..." lines from the SMS app, or a raw Base64 ciphertext copied from a bubble. */
fun showReceive(a: Activity, c: Contact, done: () -> Unit) {
    val col = Neo.vertical(a)
    val p = Neo.dp(a, 20)
    col.setPadding(p, p / 2, p, 0)
    val e = Neo.input(a, "TXT|a1b2c3d4|0|2|...   /   base64").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 5
        gravity = Gravity.TOP
    }
    Neo.add(col, e)
    val d = AlertDialog.Builder(a)
        .setTitle("📥 " + tr("Import received text", "دریافت دستی متن"))
        .setView(col)
        .setPositiveButton(tr("Import", "وارد کن"), null)
        .setNegativeButton(tr("Close", "بستن"), null)
        .create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        var n = 0
        val b64 = Regex("[A-Za-z0-9+/=]+")
        for (line in e.text.toString().lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (Codec.parse(t) != null) {
                Spool.add(a, c.phone, System.currentTimeMillis(), t)
                n++
            } else if (t.length >= 40 && b64.matches(t)) {
                try {
                    Codec.encode(byteArrayOf(1) + Base64.decode(t, Base64.NO_WRAP))
                        .forEach { Spool.add(a, c.phone, System.currentTimeMillis(), it) }
                    n++
                } catch (ex: Exception) { }
            }
        }
        d.dismiss()
        Thread {
            val stored = Inbox.drain(a)
            a.runOnUiThread {
                Toast.makeText(a, "$n → $stored", Toast.LENGTH_SHORT).show()
                done()
            }
        }.start()
    }
}
