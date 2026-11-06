package com.velmorath.app

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs

internal class NoticeCard(context: Context, item: CampusItem, saved: Boolean,
    open: () -> Unit, private val onFavoriteToggle: () -> Unit) : MaterialCardView(context) {
    private val ui = UiKit(context)
    private var downX = 0f
    private var downY = 0f
    private var horizontal = false
    private var moved = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    init {
        val source = StableColors.get(context, "source", item.sourceId.ifBlank { item.source })
        val foreground = if (ColorUtils.calculateContrast(Color.BLACK, source) >= ColorUtils.calculateContrast(Color.WHITE, source)) Color.BLACK else Color.WHITE
        val inverse = Color.rgb(255 - Color.red(source), 255 - Color.green(source), 255 - Color.blue(source))
        radius = ui.dp(UiStyle.CARD_RADIUS).toFloat(); strokeWidth = 0; cardElevation = 0f
        setCardBackgroundColor(if (saved) source else ui.color(R.color.card))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(UiStyle.CARD_GAP) }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(UiStyle.CARD_PADDING), ui.dp(UiStyle.CARD_VERTICAL), ui.dp(UiStyle.CARD_PADDING), ui.dp(UiStyle.CARD_VERTICAL))
            addView(ui.text(item.title, UiStyle.CARD_TITLE, bold = true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                if (saved) setTextColor(foreground)
                val dot = ui.shape(if (saved) inverse else source, 8).apply { setBounds(0, 0, ui.dp(10), ui.dp(10)) }
                setCompoundDrawablesRelative(dot, null, null, null); compoundDrawablePadding = ui.dp(8)
            })
            addView(ui.text(listOf(item.source, item.publishedAt.ifBlank { "日期未知" }).filter { it.isNotBlank() }.joinToString("  ·  "), 14f, R.color.muted).apply {
                setPadding(0, ui.dp(8), 0, 0); if (saved) setTextColor(foreground)
            })
        }
        addView(body); isClickable = true; isFocusable = true; setOnClickListener { open() }
        ViewCompat.setStateDescription(this, if (saved) "已收藏" else "未收藏")
        ViewCompat.addAccessibilityAction(this, if (saved) "取消收藏" else "收藏通知") { _, _ -> onFavoriteToggle(); true }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel(); translationX = 0f
                downX = event.rawX; downY = event.rawY; horizontal = false; moved = false
                isPressed = true; return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Raw coordinates are independent of the card's visual translation.
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                    moved = true; horizontal = dx > slop && dx > abs(dy) * 1.5f
                }
                if (horizontal) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    translationX = dx.coerceIn(0f, ui.dp(80).toFloat()); isPressed = false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val shouldToggle = horizontal && event.rawX - downX >= ui.dp(56)
                isPressed = false; parent?.requestDisallowInterceptTouchEvent(false)
                animate().translationX(0f).setDuration(140).start()
                if (shouldToggle) onFavoriteToggle() else if (!moved) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false; parent?.requestDisallowInterceptTouchEvent(false)
                animate().translationX(0f).setDuration(140).start(); return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean = super.performClick()
}
