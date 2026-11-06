package com.velmorath.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

internal object UiStyle {
    const val CARD_RADIUS = 14
    const val CARD_PADDING = 14
    const val CARD_VERTICAL = 12
    const val CARD_GAP = 8
    const val CARD_TITLE = 17f
    const val SECONDARY = 14f
}

internal class UiKit(val context: Context) {
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    fun color(id: Int) = ContextCompat.getColor(context, id)
    fun text(value: String, size: Float = 16f, color: Int = R.color.ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(this@UiKit.color(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    fun shape(color: Int, radius: Int = 12) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    fun action(value: String, selected: Boolean = false, click: () -> Unit) = text(value, 14f, if (selected) R.color.accent else R.color.ink).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(12), 0, dp(12), 0)
        isClickable = true; isFocusable = true
        val attr = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, attr, true)
        setBackgroundResource(attr.resourceId)
        setOnClickListener { click() }
    }
    fun icon(drawable: Int, description: String, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(drawable); imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
        contentDescription = description
        setPadding(dp(10), dp(10), dp(10), dp(10))
        val attr = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, attr, true)
        setBackgroundResource(attr.resourceId)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); setOnClickListener { click() }
    }
    fun menu(value: String, options: List<String>, selected: Int = -1, chosen: (Int) -> Unit): View {
        val button = action(value) {}
        button.maxLines = 1
        button.ellipsize = android.text.TextUtils.TruncateAt.END
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_dropdown, 0)
        button.compoundDrawablePadding = dp(4)
        button.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color(R.color.muted))
        button.setOnClickListener {
            android.widget.PopupMenu(context, button).apply {
                options.forEachIndexed { index, name -> menu.add(0, index, index, name).apply { isCheckable = selected >= 0; isChecked = index == selected } }
                setOnMenuItemClickListener { chosen(it.itemId); true }
                show()
            }
        }
        return button
    }
    fun primary(value: String, click: () -> Unit) = com.google.android.material.button.MaterialButton(context).apply {
        text = value; minHeight = dp(48); setOnClickListener { click() }
    }
    fun segmented(labels: List<String>, selected: Int, changed: (Int) -> Unit): View = LinearLayout(context).apply {
        setPadding(dp(3), dp(3), dp(3), dp(3)); background = shape(color(R.color.line), 11)
        labels.forEachIndexed { index, name ->
            addView(action(name, index == selected) { changed(index) }.apply {
                minHeight = dp(48)
                if (index == selected) background = shape(color(R.color.card), 9)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
}

internal object CoursePalette {
    fun colors(context: Context, title: String): Pair<Int, Int> {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return Color.parseColor(if (dark) "#202028" else "#FFFFFF") to StableColors.get(context, "course", title)
    }
}
