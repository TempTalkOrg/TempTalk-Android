package com.difft.android.base.widget.sideBar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.difft.android.base.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vertical A-Z index rail. Shows the letters passed to [reset], sized to its content; on short
 * windows it scales the text down, then condenses with dots, then stops drawing and ignores
 * touches (#1374).
 */
class SideBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    interface OnTouchingLetterChangedListener {
        fun onTouchingLetterChanged(s: String)
    }

    private var onTouchingLetterChangedListener: OnTouchingLetterChangedListener? = null
    private var letters: List<String> = emptyList()
    private var slots: List<SideBarSlot> = emptyList()
    private var slotHeight = 0f
    private var contentTop = 0f

    /** Index into [letters] of the letter under the finger, or -1. */
    private var chosen = -1

    private val maxTextSize = resources.getDimension(R.dimen.chat_sidebar_text_size_normal_default)
    private val preferredItemHeight = PREFERRED_ITEM_HEIGHT_DP * resources.displayMetrics.density
    private val minItemHeight = MIN_ITEM_HEIGHT_DP * resources.displayMetrics.density
    private val normalColor = ContextCompat.getColor(context, R.color.t_info)
    private val selectedColor = ContextCompat.getColor(context, R.color.blue_200)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun setOnTouchingLetterChangedListener(listener: OnTouchingLetterChangedListener?) {
        onTouchingLetterChangedListener = listener
    }

    fun reset(letters: List<String>) {
        if (this.letters == letters) return
        this.letters = letters
        chosen = -1
        // Slots must match the new letters before the next touch, which can arrive ahead of the
        // relayout requested below.
        updateSlots()
        // Intrinsic height depends on the letter count.
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (letters.size * preferredItemHeight).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateSlots()
    }

    private fun updateSlots() {
        val available = (height - paddingTop - paddingBottom).toFloat()
        slots = SideBarLayout.slots(letters.size, available, minItemHeight)
        slotHeight = if (slots.isEmpty()) 0f else min(preferredItemHeight, available / slots.size)
        contentTop = paddingTop + (available - slotHeight * slots.size) / 2
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slots.isEmpty()) return
        paint.textSize = min(maxTextSize, slotHeight * TEXT_TO_SLOT_RATIO)
        val centerX = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val baselineOffset = -(paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        slots.forEachIndexed { i, slot ->
            val (text, selected) = when (slot) {
                is SideBarSlot.Letter -> letters[slot.index] to (slot.index == chosen)
                is SideBarSlot.Dot -> DOT to (chosen in slot.fromIndex..slot.toIndex)
            }
            paint.color = if (selected) selectedColor else normalColor
            paint.isFakeBoldText = selected
            canvas.drawText(text, centerX, contentTop + slotHeight * (i + 0.5f) + baselineOffset, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (slots.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                val index = SideBarLayout.letterIndexAt(slots, slotHeight, event.y - contentTop)
                if (index >= 0 && index != chosen) {
                    chosen = index
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onTouchingLetterChangedListener?.onTouchingLetterChanged(letters[index])
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                chosen = -1
                invalidate()
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                chosen = -1
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val PREFERRED_ITEM_HEIGHT_DP = 16f
        const val MIN_ITEM_HEIGHT_DP = 12f
        const val TEXT_TO_SLOT_RATIO = 0.8f
        const val DOT = "•"
    }
}
