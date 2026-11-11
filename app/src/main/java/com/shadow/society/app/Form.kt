package com.shadow.society.app

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Input with a floating label (like ".form label .input + span"): the label sits on the field; when the field is
 * focused or filled it becomes small and bold under the text; it turns green when the value is valid.
 */
class FloatField(ctx: Context, private val label: String, private val validator: ((String) -> Boolean)? = null) : FrameLayout(ctx) {
    val edit: EditText = EditText(ctx)
    private val lab: TextView = TextView(ctx)
    private var up = false
    private val s: Float get() = Neo.fs / 1.5f
    private fun px(v: Float): Int = (v * s * context.resources.displayMetrics.density).toInt()

    init {
        edit.background = Neo.box(ctx, Color.WHITE, Neo.BORDER, 10)
        edit.setTextColor(Neo.TEXT)
        edit.textSize = 19f * s
        edit.typeface = Neo.FONT
        edit.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        edit.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.setPadding(Neo.dp(ctx, 10), px(10f), Neo.dp(ctx, 10), px(27f))
        addView(edit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        lab.text = label
        lab.textSize = 17f * s
        lab.setTextColor(Neo.LABEL)
        lab.typeface = Neo.FONT
        lab.pivotX = 0f
        lab.pivotY = 0f
        lab.setOnClickListener {
            edit.requestFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, 0)
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = Neo.dp(ctx, 11)
        lp.topMargin = px(11f)
        addView(lab, lp)

        edit.setOnFocusChangeListener { _, _ -> update(true) }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) { update(true) }
        })
        update(false)
    }

    fun text(): String = edit.text.toString()

    /** Re-check validity (when another field it depends on changed). */
    fun refresh() { update(true) }

    /** Red border for a wrong value. */
    fun bad(on: Boolean) {
        edit.background = Neo.box(context, Color.WHITE, if (on) Neo.RED else Neo.BORDER, 10)
    }

    /** Password field with an eye: shows / hides the text. The eye also controls the fields in [also]. */
    fun password(vararg also: FloatField): FloatField {
        val hidden = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val shown = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        edit.inputType = hidden
        edit.setPadding(edit.paddingLeft, edit.paddingTop, Neo.dp(context, 48), edit.paddingBottom)
        var visible = false
        val eye = ImageButton(context)
        eye.setBackgroundColor(0)
        eye.setImageDrawable(Icons.drawable(context, "eye", Neo.LABEL, 24, 1f))
        eye.contentDescription = "show password"
        eye.setOnClickListener {
            visible = !visible
            for (f in listOf(this) + also) {
                val e = f.edit
                val sel = e.selectionEnd
                e.inputType = if (visible) shown else hidden
                e.typeface = Neo.FONT
                e.setSelection(sel.coerceIn(0, e.text.length))
            }
            eye.setImageDrawable(Icons.drawable(context, "eye", if (visible) Neo.ACCENT else Neo.LABEL, 24, 1f))
        }
        val lp = FrameLayout.LayoutParams(Neo.dp(context, 44), Neo.dp(context, 44))
        lp.gravity = Gravity.END or Gravity.TOP
        lp.topMargin = px(2f)
        addView(eye, lp)
        return this
    }

    /** Not typed: tapping opens a picker (country code). Shows a small arrow. */
    fun readOnly(onTap: () -> Unit): FloatField {
        edit.isFocusable = false
        edit.isFocusableInTouchMode = false
        edit.isCursorVisible = false
        edit.setOnClickListener { onTap() }
        lab.setOnClickListener { onTap() }
        val caret = TextView(context)
        caret.text = "▾"
        caret.textSize = 20f * s
        caret.setTextColor(Neo.LABEL)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.END or Gravity.TOP
        lp.rightMargin = Neo.dp(context, 10)
        lp.topMargin = px(10f)
        addView(caret, lp)
        return this
    }

    private fun update(animate: Boolean) {
        val t = text()
        val wantUp = edit.hasFocus() || t.isNotEmpty()
        val valid = t.isNotEmpty() && (validator?.invoke(t) ?: false)
        lab.setTextColor(if (valid) Neo.GREEN else Neo.LABEL)
        lab.typeface = if (wantUp) Neo.BOLD else Neo.FONT
        if (wantUp != up || !animate) {
            up = wantUp
            val ty = if (wantUp) px(25f).toFloat() else 0f
            val sc = if (wantUp) 0.78f else 1f
            if (animate) lab.animate().translationY(ty).scaleX(sc).scaleY(sc).setDuration(200).start()
            else { lab.translationY = ty; lab.scaleX = sc; lab.scaleY = sc }
        }
    }
}

/** The title with the pulsing dot (".title::before / ::after"). */
class PulseTitle(ctx: Context, title: String) : LinearLayout(ctx) {
    private var anim: ObjectAnimator? = null
    private val ring: View

    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val sz = Neo.dp(ctx, 20)
        fun circle(): View = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Neo.ACCENT) }
        }
        val dot = FrameLayout(ctx)
        val base = circle()
        ring = circle()
        dot.addView(base, FrameLayout.LayoutParams(sz, sz))
        dot.addView(ring, FrameLayout.LayoutParams(sz, sz))
        addView(dot, LinearLayout.LayoutParams(sz, sz))
        val tv = TextView(ctx)
        tv.text = title
        tv.textSize = 34f * (Neo.fs / 1.5f)
        tv.setTextColor(Neo.ACCENT)
        tv.typeface = Neo.BOLD
        tv.letterSpacing = -0.04f
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = Neo.dp(ctx, 14)
        addView(tv, lp)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val a = ObjectAnimator.ofPropertyValuesHolder(ring,
            PropertyValuesHolder.ofFloat("scaleX", 0.9f, 1.8f),
            PropertyValuesHolder.ofFloat("scaleY", 0.9f, 1.8f),
            PropertyValuesHolder.ofFloat("alpha", 1f, 0f))
        a.duration = 1000
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.start()
        anim = a
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

/** A row of views side by side with a small gap (".flex"). Each item has a weight. */
fun formRow(c: Context, vararg items: Pair<View, Float>): LinearLayout {
    val row = LinearLayout(c)
    row.orientation = LinearLayout.HORIZONTAL
    val gap = Neo.dp(c, 6)
    for ((i, it) in items.withIndex()) {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, it.second)
        if (i > 0) lp.leftMargin = gap
        row.addView(it.first, lp)
    }
    return row
}
