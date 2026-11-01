package com.shadow.society.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

object Lang {
    @Volatile var code = "fa"
}

/** Persian by default; English if chosen in Settings. */
fun tr(en: String, fa: String): String = if (Lang.code == "en") en else fa

private fun h(v: Long): Int = v.toInt()

class Palette(
    val id: String, val fa: String, val en: String,
    val bg: Int, val panel: Int, val barText: Int, val text: Int, val dim: Int,
    val accent: Int, val onAccent: Int,
    val sent: Int, val sentText: Int, val recv: Int, val recvText: Int, val red: Int,
    val mono: Boolean, val rain: Boolean, val dialog: Int
)

val THEMES: List<Palette> = listOf(
    Palette(id = "matrix", fa = "ماتریکس", en = "Matrix",
        bg = h(0xFF000000), panel = h(0xCC0B0F0B), barText = h(0xFF00FF41), text = h(0xFF00FF41), dim = h(0xFF008F11),
        accent = h(0xFF00FF41), onAccent = h(0xFF000000),
        sent = h(0xFF00FF41), sentText = h(0xFF000000), recv = h(0xE6031A06), recvText = h(0xFF00FF41),
        red = h(0xFFFF3B3B), mono = true, rain = true, dialog = R.style.Alert_Matrix),
    Palette(id = "telegram", fa = "تلگرام", en = "Telegram",
        bg = h(0xFF0E1621), panel = h(0xFF17212B), barText = h(0xFFFFFFFF), text = h(0xFFFFFFFF), dim = h(0xFF7F91A4),
        accent = h(0xFF5EB5F7), onAccent = h(0xFFFFFFFF),
        sent = h(0xFF2B5278), sentText = h(0xFFFFFFFF), recv = h(0xFF182533), recvText = h(0xFFFFFFFF),
        red = h(0xFFEC3942), mono = false, rain = false, dialog = R.style.Alert_Telegram),
    Palette(id = "whatsapp", fa = "واتساپ", en = "WhatsApp",
        bg = h(0xFFECE5DD), panel = h(0xFF075E54), barText = h(0xFFFFFFFF), text = h(0xFF111B21), dim = h(0xFF667781),
        accent = h(0xFF128C7E), onAccent = h(0xFFFFFFFF),
        sent = h(0xFFDCF8C6), sentText = h(0xFF111B21), recv = h(0xFFFFFFFF), recvText = h(0xFF111B21),
        red = h(0xFFD32F2F), mono = false, rain = false, dialog = R.style.Alert_WhatsApp),
    Palette(id = "sms", fa = "پیامک موبایل", en = "Mobile SMS",
        bg = h(0xFFFFFFFF), panel = h(0xFFF1F3F4), barText = h(0xFF202124), text = h(0xFF202124), dim = h(0xFF5F6368),
        accent = h(0xFF1A73E8), onAccent = h(0xFFFFFFFF),
        sent = h(0xFF1A73E8), sentText = h(0xFFFFFFFF), recv = h(0xFFE8EAED), recvText = h(0xFF202124),
        red = h(0xFFD93025), mono = false, rain = false, dialog = R.style.Alert_Sms)
)

/** Theme + sizes (changeable in Settings) + small view factories. */
object Neo {
    var P: Palette = THEMES[0]
    var fs = 1.5f        // font scale
    var isz = 1.5f       // icon scale
    var rain = true
    var version = 0      // bumped when settings change, screens rebuild themselves

    val BG: Int get() = P.bg
    val PANEL: Int get() = P.panel
    val BARTEXT: Int get() = P.barText
    val TEXT: Int get() = P.text
    val DIM: Int get() = P.dim
    val ACCENT: Int get() = P.accent
    val RED: Int get() = P.red
    val FONT: Typeface get() = if (P.mono) Typeface.MONOSPACE else Typeface.DEFAULT
    val BOLD: Typeface get() = Typeface.create(FONT, Typeface.BOLD)

    fun load(c: Context) {
        Lang.code = Prefs.str(c, "lang") ?: "fa"
        val id = Prefs.str(c, "theme")
        P = THEMES.firstOrNull { it.id == id } ?: THEMES[0]
        fs = Prefs.int(c, "font_pct", 150) / 100f
        isz = Prefs.int(c, "icon_pct", 150) / 100f
        rain = Prefs.int(c, "rain", 1) == 1
    }

    fun changed(c: Context) { load(c); version++ }

    fun themeName(p: Palette): String = tr(p.en, p.fa)

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    fun text(c: Context, s: String, sp: Int, color: Int): TextView = TextView(c).apply {
        text = s
        textSize = sp * fs
        setTextColor(color)
        typeface = FONT
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    fun box(c: Context, fill: Int, stroke: Int, r: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(c, 1), stroke)
        cornerRadius = dp(c, r).toFloat()
    }

    /** Telegram-style bubble: one small "tail" corner on the speaker's side. */
    fun bubble(c: Context, fill: Int, sent: Boolean): GradientDrawable {
        val big = dp(c, 14).toFloat()
        val small = dp(c, 3).toFloat()
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadii = if (sent) floatArrayOf(big, big, big, big, small, small, big, big)
                        else floatArrayOf(big, big, big, big, big, big, small, small)
        return g
    }

    fun input(c: Context, hint: String): EditText = EditText(c).apply {
        this.hint = hint
        setHintTextColor(DIM)
        setTextColor(P.recvText)
        typeface = FONT
        textSize = 15 * fs
        background = box(c, P.recv, DIM, 8)
        val p = dp(c, 10)
        setPadding(p, p, p, p)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING   // keyboard must not learn
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    fun button(c: Context, label: String): Button = Button(c).apply {
        text = label
        isAllCaps = false
        textSize = 14 * fs
        setTextColor(ACCENT)
        typeface = FONT
        background = box(c, 0, ACCENT, 8)
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

    fun dialog(c: Context): AlertDialog.Builder = AlertDialog.Builder(c, P.dialog)

    /** Page background; Matrix theme gets the falling code behind everything. */
    fun backdrop(c: Context, content: View, rainAlpha: Float = 0.6f): View {
        val f = FrameLayout(c)
        f.setBackgroundColor(BG)
        if (P.rain && rain) {
            val r = MatrixRainView(c)
            r.alpha = rainAlpha
            f.addView(r, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        f.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return f
    }

    fun styleWindow(a: Activity) {
        val w = a.window
        w.setBackgroundDrawable(ColorDrawable(BG))
        w.statusBarColor = PANEL or 0xFF000000.toInt()
        w.navigationBarColor = BG
        val ctl = WindowCompat.getInsetsController(w, w.decorView)
        ctl.isAppearanceLightStatusBars = ColorUtils.calculateLuminance(PANEL) > 0.5
        ctl.isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(BG) > 0.5
    }
}

/** A line icon drawn from a tiny path (24x24 grid), tinted and sized from Settings. */
class IconDrawable(private val path: Path, tint: Int, private val px: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(s, s)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = px
    override fun getIntrinsicHeight(): Int = px
}

object Icons {
    // Commands: M L H V Q Z (absolute) and  O cx cy r (circle)
    private val D = mapOf(
        "send" to "M3 11 L21 3 L14 21 L11 13 Z M21 3 L11 13",
        "add" to "M12 5 V19 M5 12 H19",
        "tune" to "M4 6 H20 M4 12 H20 M4 18 H20 O9 6 2.2 O15 12 2.2 O8 18 2.2",
        "key" to "O8 15 4 M11 12 L20 4 M17 7 L19 9 M14 10 L16 12",
        "lock" to "M5 11 H19 V21 H5 Z M8 11 V8 Q8 3 12 3 Q16 3 16 8 V11",
        "unlock" to "M5 11 H19 V21 H5 Z M8 11 V8 Q8 3 12 3 Q15 3 16 6",
        "download" to "M12 4 V15 M7 10 L12 15 L17 10 M4 20 H20",
        "upload" to "M12 16 V5 M7 9 L12 4 L17 9 M4 20 H20",
        "scan" to "M4 9 V4 H9 M15 4 H20 V9 M20 15 V20 H15 M9 20 H4 V15 M4 12 H20",
        "exit" to "M10 4 H5 V20 H10 M15 8 L19 12 L15 16 M19 12 H9",
        "alert" to "M12 3 L22 20 H2 Z M12 10 V14 M12 17 V17.5",
        "back" to "M15 5 L8 12 L15 19",
        "paste" to "M9 4 H15 V7 H9 Z M7 5 H5 V21 H19 V5 H17"
    )

    private fun parse(d: String): Path {
        val p = Path()
        val t = Regex("[MLHVQOZ]|-?[0-9.]+").findAll(d).map { it.value }.toList()
        var i = 0
        var cx = 0f
        var cy = 0f
        fun n(): Float = t[i++].toFloat()
        while (i < t.size) {
            when (t[i++]) {
                "M" -> { cx = n(); cy = n(); p.moveTo(cx, cy) }
                "L" -> { cx = n(); cy = n(); p.lineTo(cx, cy) }
                "H" -> { cx = n(); p.lineTo(cx, cy) }
                "V" -> { cy = n(); p.lineTo(cx, cy) }
                "Q" -> { val x1 = n(); val y1 = n(); cx = n(); cy = n(); p.quadTo(x1, y1, cx, cy) }
                "O" -> { val x = n(); val y = n(); val r = n(); p.addCircle(x, y, r, Path.Direction.CW) }
                "Z" -> p.close()
            }
        }
        return p
    }

    fun drawable(c: Context, name: String, tint: Int): Drawable =
        IconDrawable(parse(D[name] ?: "M0 0"), tint, (Neo.dp(c, 24) * Neo.isz).toInt())

    fun button(c: Context, name: String, tint: Int, onClick: (() -> Unit)? = null): ImageButton {
        val b = ImageButton(c)
        b.setImageDrawable(drawable(c, name, tint))
        b.setBackgroundColor(0)
        val p = Neo.dp(c, 8)
        b.setPadding(p, p, p, p)
        b.contentDescription = name
        if (onClick != null) b.setOnClickListener { onClick() }
        return b
    }
}
