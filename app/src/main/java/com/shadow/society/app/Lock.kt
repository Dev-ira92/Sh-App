package com.shadow.society.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object LockType {
    const val PASS = "pass"
    const val PIN = "pin"
    const val BIO = "bio"
    const val PATTERN = "pattern"   // old locks keep working, but it can no longer be chosen

    /** The three ways to log in. */
    val CHOICES = listOf(PIN, PASS, BIO)

    fun label(t: String): String = when (t) {
        PIN -> tr("Numeric PIN", "پین عددی")
        BIO -> tr("Fingerprint", "اثر انگشت")
        PATTERN -> tr("Pattern (old)", "الگو (قدیمی)")
        else -> tr("Password", "رمز عبور")
    }

    /** Shown when you SET a lock. */
    fun hint(t: String): String = when (t) {
        PIN -> tr("Choose a PIN (at least 4 digits, 6+ is better)", "یک پین انتخاب کن (حداقل ۴ رقم، ۶ رقم یا بیشتر بهتر است)")
        PATTERN -> tr("Draw a pattern (at least 4 dots)", "یک الگو بکش (حداقل ۴ نقطه)")
        else -> tr("Choose a password (at least 8 characters)", "یک رمز انتخاب کن (حداقل ۸ کاراکتر)")
    }

    /** Shown when you UNLOCK. */
    fun loginHint(t: String): String = when (t) {
        PIN -> tr("Enter your PIN", "پین را وارد کن")
        PATTERN -> tr("Draw your pattern", "الگو را بکش")
        else -> tr("Enter your password", "رمز را وارد کن")
    }

    fun check(t: String, s: String): String? = when (t) {
        PASS -> if (s.length < 8) tr("min 8 characters", "حداقل ۸ کاراکتر") else null
        PIN -> if (s.length < 4) tr("min 4 digits", "حداقل ۴ رقم") else null
        PATTERN -> if (s.split("-").size < 4) tr("connect at least 4 dots", "حداقل ۴ نقطه را وصل کن") else null
        else -> null
    }
}

/** Old pattern grid (only for people who already have a pattern lock). */
class PatternView(c: Context, private val onDone: (List<Int>) -> Unit) : View(c) {
    private val sel = ArrayList<Int>()
    private var fx = -1f
    private var fy = -1f
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Neo.dp(c, 5).toFloat()
        strokeCap = Paint.Cap.ROUND
        color = Neo.ACCENT
    }
    private val rad: Float get() = minOf(width, height) / 3f * 0.15f

    private fun px(i: Int): Float = width * ((i % 3) + 0.5f) / 3f
    private fun py(i: Int): Float = height * ((i / 3) + 0.5f) / 3f

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val s = minOf(MeasureSpec.getSize(wSpec), Neo.dp(context, 280))
        setMeasuredDimension(s, s)
    }

    private fun hit(x: Float, y: Float): Int {
        for (i in 0 until 9) {
            val dx = x - px(i)
            val dy = y - py(i)
            if (dx * dx + dy * dy < rad * rad * 5f) return i
        }
        return -1
    }

    private fun add(i: Int) { if (i >= 0 && !sel.contains(i)) sel.add(i) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sel.clear()
                parent?.requestDisallowInterceptTouchEvent(true)
                fx = e.x
                fy = e.y
                add(hit(e.x, e.y))
            }
            MotionEvent.ACTION_MOVE -> {
                fx = e.x
                fy = e.y
                add(hit(e.x, e.y))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fx = -1f
                fy = -1f
                val result = ArrayList(sel)
                postDelayed({ sel.clear(); invalidate() }, 300)
                if (result.isNotEmpty()) onDone(result)
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        for (k in 0 until sel.size - 1) {
            canvas.drawLine(px(sel[k]), py(sel[k]), px(sel[k + 1]), py(sel[k + 1]), lineP)
        }
        if (sel.isNotEmpty() && fx >= 0f) canvas.drawLine(px(sel.last()), py(sel.last()), fx, fy, lineP)
        for (i in 0 until 9) {
            val on = sel.contains(i)
            if (on) {
                dotP.color = Neo.withAlpha(Neo.ACCENT, 80)
                canvas.drawCircle(px(i), py(i), rad * 2.6f, dotP)
            }
            dotP.color = if (on) Neo.ACCENT else Neo.withAlpha(Neo.TEXT, 225)
            canvas.drawCircle(px(i), py(i), if (on) rad * 1.25f else rad * 1.05f, dotP)
        }
    }
}

/** The input for a lock: password box, or the PIN keypad (digits light up when touched). */
class SecretPad(private val ctx: Context, val type: String, private val label: String,
                private val onSubmit: (String) -> Unit) {
    val view: LinearLayout = Neo.vertical(ctx)
    private var field: EditText? = null
    private var dots: TextView? = null
    private val buf = StringBuilder()

    init {
        view.gravity = Gravity.CENTER_HORIZONTAL
        when (type) {
            LockType.PATTERN -> {
                val pv = PatternView(ctx) { pts -> onSubmit("P" + pts.joinToString("-")) }
                view.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            LockType.PIN -> buildPin()
            else -> buildPass()
        }
    }

    private fun buildPass() {
        val e = Neo.input(ctx, tr("password", "رمز عبور"))
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        e.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        field = e
        Neo.add(view, e, 4)
        val b = Neo.primary(ctx, label)
        Neo.add(view, b, 12)
        fun go() {
            val s = e.text.toString()
            if (s.isNotEmpty()) {
                e.setText("")
                onSubmit(s)
            }
        }
        b.setOnClickListener { go() }
        e.setOnEditorActionListener { _, _, _ -> go(); true }
    }

    /** Bright look while a key is pressed. */
    private fun lit(b: Button, k: String, on: Boolean) {
        if (on) {
            b.background = Neo.raised(ctx, 14)
            b.setTextColor(Neo.P.onAccent)
            b.animate().scaleX(0.93f).scaleY(0.93f).setDuration(60).start()
        } else {
            b.background = if (k == "OK") Neo.raised(ctx, 14) else Neo.glass(ctx, 14)
            b.setTextColor(if (k == "OK") Neo.P.onAccent else Neo.TEXT)
            b.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
        }
    }

    private fun buildPin() {
        val d = Neo.glow(Neo.text(ctx, "–", 30, Neo.TEXT))
        d.gravity = Gravity.CENTER
        dots = d
        Neo.add(view, d, 4)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        val m = Neo.dp(ctx, 4)
        for (r in 0 until 4) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            for (cI in 0 until 3) {
                val k = keys[r * 3 + cI]
                val b = if (k == "OK") Neo.primary(ctx, k) else Neo.button(ctx, k)
                b.textSize = 24f
                b.setTextColor(if (k == "OK") Neo.P.onAccent else Neo.TEXT)
                b.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> lit(b, k, true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.postDelayed({ lit(b, k, false) }, 140)
                    }
                    false
                }
                b.setOnClickListener { press(k) }
                val lp = LinearLayout.LayoutParams(0, Neo.dp(ctx, 60), 1f)
                lp.setMargins(m, m, m, m)
                row.addView(b, lp)
            }
            Neo.add(view, row, 4)
        }
    }

    private fun press(k: String) {
        when (k) {
            "⌫" -> { if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1) }
            "OK" -> {
                if (buf.isNotEmpty()) {
                    val s = buf.toString()
                    buf.clear()
                    render()
                    onSubmit(s)
                    return
                }
            }
            else -> { if (buf.length < 12) buf.append(k) }
        }
        render()
    }

    private fun render() {
        dots?.text = if (buf.isEmpty()) "–" else "● ".repeat(buf.length).trim()
    }

    fun clear() {
        buf.clear()
        field?.setText("")
        render()
    }
}

/** Dialog that asks for a new secret (optionally twice, to confirm) of the given lock type. */
fun askSecret(a: Activity, type: String, title: String, confirm: Boolean, cb: (String) -> Unit) {
    val sv = ScrollView(a)
    val col = Neo.vertical(a)
    val p = Neo.dp(a, 16)
    col.setPadding(p, p / 2, p, 0)
    col.gravity = Gravity.CENTER_HORIZONTAL
    sv.addView(col)
    val info = Neo.text(a, LockType.hint(type), 13, Neo.DIM)
    info.gravity = Gravity.CENTER
    col.addView(info)

    var first: String? = null
    lateinit var dlg: AlertDialog
    lateinit var pad: SecretPad
    pad = SecretPad(a, type, tr("OK", "تأیید")) { s ->
        val err = LockType.check(type, s)
        if (err != null) {
            info.text = err
        } else if (!confirm) {
            dlg.dismiss()
            cb(s)
        } else if (first == null) {
            first = s
            info.text = tr("Repeat it to confirm", "برای تأیید دوباره وارد کن")
            pad.clear()
        } else if (first == s) {
            dlg.dismiss()
            cb(s)
        } else {
            first = null
            info.text = tr("Did not match. Start again.", "یکسان نبود. از نو.")
        }
    }
    Neo.add(col, pad.view, 12)
    dlg = Neo.dialog(a).setTitle(title).setView(sv)
        .setNegativeButton(tr("Cancel", "انصراف"), null).create()
    dlg.show()
}
