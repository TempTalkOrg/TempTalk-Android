package com.difft.android.login.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText
import com.difft.android.login.R

/**
 * Verification code input field with individual box style.
 *
 * Replaces the MNPasswordEditText library to fix the security issue of using reflection
 * to access private field `mMax` for getting maxLength.
 * Only implements features currently used: individual box style + original text display.
 *
 * Usage in XML:
 * ```xml
 * <com.difft.android.login.widget.VerificationCodeEditText
 *     android:layout_width="304dp"
 *     android:layout_height="44dp"
 *     android:inputType="number"
 *     android:maxLength="6"
 *     app:psw_show_cursor="true"
 *     app:psw_text_color="@color/t.primary"
 *     app:psw_background_color="@android:color/transparent"
 *     app:psw_border_color="@color/line"
 *     app:psw_border_selected_color="@color/t.info"
 *     app:psw_border_width="1dp"
 *     app:psw_border_radius="8dp"
 *     app:psw_item_margin="8dp" />
 * ```
 */
class VerificationCodeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    // Number of boxes (verification code length)
    private var boxCount: Int = 6

    // Text color
    private var codeTextColor: Int = Color.BLACK

    // Box background color
    private var boxBackgroundColor: Int = Color.WHITE

    // Border color
    private var borderColor: Int = Color.GRAY

    // Border color when selected
    private var borderSelectedColor: Int = Color.BLUE

    // Border corner radius
    private var borderRadius: Float = dpToPx(6f)

    // Border stroke width
    private var borderWidth: Float = dpToPx(1f)

    // Margin between boxes
    private var itemMargin: Float = dpToPx(10f)

    // Whether to show cursor
    private var showCursor: Boolean = false

    // Cursor color
    private var cursorColor: Int = Color.BLUE

    // Cursor width
    private var cursorWidth: Float = dpToPx(2f)

    // Cursor height (0 means auto-calculate)
    private var cursorHeight: Float = 0f

    // Paint for text
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Paint for drawing bitmaps
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Paint for cursor
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // GradientDrawable for box background with border
    private val boxDrawable = GradientDrawable()

    // Cursor blink related
    private var cursorVisible = true
    private var blinkRunnable: Runnable? = null
    private var isBlinkCancelled = false

    // Text change listener
    private var onTextChangeListener: OnTextChangeListener? = null

    init {
        initAttrs(attrs, defStyleAttr)
        initView()
    }

    private fun initAttrs(attrs: AttributeSet?, defStyleAttr: Int) {
        val typedArray = context.obtainStyledAttributes(
            attrs,
            R.styleable.VerificationCodeEditText,
            defStyleAttr,
            0
        )

        try {
            boxBackgroundColor = typedArray.getColor(
                R.styleable.VerificationCodeEditText_psw_background_color,
                Color.WHITE
            )
            borderColor = typedArray.getColor(
                R.styleable.VerificationCodeEditText_psw_border_color,
                Color.GRAY
            )
            borderSelectedColor = typedArray.getColor(
                R.styleable.VerificationCodeEditText_psw_border_selected_color,
                borderColor
            )
            codeTextColor = typedArray.getColor(
                R.styleable.VerificationCodeEditText_psw_text_color,
                Color.BLACK
            )
            borderRadius = typedArray.getDimension(
                R.styleable.VerificationCodeEditText_psw_border_radius,
                dpToPx(6f)
            )
            borderWidth = typedArray.getDimension(
                R.styleable.VerificationCodeEditText_psw_border_width,
                dpToPx(1f)
            )
            itemMargin = typedArray.getDimension(
                R.styleable.VerificationCodeEditText_psw_item_margin,
                dpToPx(10f)
            )
            showCursor = typedArray.getBoolean(
                R.styleable.VerificationCodeEditText_psw_show_cursor,
                false
            )
            cursorColor = typedArray.getColor(
                R.styleable.VerificationCodeEditText_psw_cursor_color,
                borderSelectedColor
            )
            cursorWidth = typedArray.getDimension(
                R.styleable.VerificationCodeEditText_psw_cursor_width,
                dpToPx(2f)
            )
            cursorHeight = typedArray.getDimension(
                R.styleable.VerificationCodeEditText_psw_cursor_height,
                0f
            )
            // Prefer custom attribute for box count
            boxCount = typedArray.getInt(
                R.styleable.VerificationCodeEditText_psw_box_count,
                0
            )
        } finally {
            typedArray.recycle()
        }

        // If custom attribute not set, get from maxLength (safe way)
        if (boxCount <= 0) {
            boxCount = getMaxLengthSafely()
        }

        // Ensure at least 1 box
        if (boxCount <= 0) {
            boxCount = 6 // Default 6-digit verification code
        }
    }

    /**
     * Safely get maxLength without using reflection.
     * Iterates through InputFilters and checks type directly.
     */
    private fun getMaxLengthSafely(): Int {
        val filters = filters ?: return 0
        for (filter in filters) {
            if (filter is InputFilter.LengthFilter) {
                return filter.max
            }
        }
        return 0
    }

    private fun initView() {
        // Hide native cursor
        isCursorVisible = false
        // Set text color to transparent (we draw text ourselves)
        setTextColor(Color.TRANSPARENT)
        // Enable focus on touch
        isFocusableInTouchMode = true
        // Disable long press (avoid clipboard menu)
        setOnLongClickListener { true }
        // Set background to transparent
        background = null

        // Initialize paints
        textPaint.color = codeTextColor
        textPaint.textSize = textSize

        cursorPaint.color = cursorColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Get view dimensions
        val viewWidth = measuredWidth
        val viewHeight = measuredHeight

        // Get the canvas clip bounds to know the actual visible area
        val clipBounds = canvas.clipBounds
        val visibleTop = clipBounds.top.toFloat()
        val visibleHeight = (clipBounds.bottom - clipBounds.top).toFloat()

        // Calculate box dimensions to fit within visible area
        val boxWidth = (viewWidth - itemMargin * (boxCount - 1)) / boxCount
        val boxHeight = visibleHeight - borderWidth // Leave space for border

        val currentTextLength = text?.length ?: 0
        val currentText = text?.toString() ?: ""

        // Create box bitmaps using GradientDrawable
        boxDrawable.setStroke(borderWidth.toInt(), borderColor)
        boxDrawable.cornerRadius = borderRadius
        boxDrawable.setColor(boxBackgroundColor)
        val normalBitmap = drawableToBitmap(boxDrawable, boxWidth.toInt(), boxHeight.toInt())

        var selectedBitmap: Bitmap? = null
        if (borderSelectedColor != borderColor) {
            boxDrawable.setStroke(borderWidth.toInt(), borderSelectedColor)
            selectedBitmap = drawableToBitmap(boxDrawable, boxWidth.toInt(), boxHeight.toInt())
        }

        // Draw each box - use visibleTop + offset to stay within clip bounds
        val drawTop = visibleTop + borderWidth / 2

        for (i in 0 until boxCount) {
            val left = boxWidth * i + itemMargin * i

            // Use selected bitmap for current input position
            val isSelected = (currentTextLength == i)
            val bitmap = if (isSelected && selectedBitmap != null) selectedBitmap else normalBitmap
            canvas.drawBitmap(bitmap, left, drawTop, bitmapPaint)

            // Draw text (centered vertically in the visible area)
            if (i < currentTextLength) {
                val char = currentText[i].toString()
                val charWidth = getTextWidth(textPaint, char)
                val charHeight = getTextHeight(textPaint, char)
                val textX = left + (boxWidth - charWidth) / 2
                val textY = visibleTop + (visibleHeight + charHeight) / 2f
                textPaint.color = codeTextColor
                canvas.drawText(char, textX, textY, textPaint)
            }
        }

        // Draw cursor
        if (showCursor && cursorVisible && hasFocus() && currentTextLength < boxCount) {
            val actualCursorHeight = if (cursorHeight > 0 && cursorHeight <= visibleHeight) {
                cursorHeight
            } else {
                visibleHeight * 0.5f
            }
            val cursorLeft = (boxWidth + itemMargin) * currentTextLength + boxWidth / 2 - cursorWidth / 2
            val cursorTop = visibleTop + (visibleHeight - actualCursorHeight) / 2f

            canvas.drawRoundRect(
                cursorLeft,
                cursorTop,
                cursorLeft + cursorWidth,
                cursorTop + actualCursorHeight,
                cursorWidth / 2,
                cursorWidth / 2,
                cursorPaint
            )
        }
    }

    private fun drawableToBitmap(drawable: GradientDrawable, width: Int, height: Int): Bitmap {
        val config = if (drawable.opacity != PixelFormat.OPAQUE) {
            Bitmap.Config.ARGB_8888
        } else {
            Bitmap.Config.RGB_565
        }
        val bitmap = Bitmap.createBitmap(width, height, config)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        invalidate()

        val currentText = text?.toString() ?: ""
        val isComplete = currentText.length == boxCount
        onTextChangeListener?.onTextChange(currentText, isComplete)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startBlink()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopBlink()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            startBlink()
        } else {
            stopBlink()
        }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (focused) {
            startBlink()
        } else {
            stopBlink()
        }
    }

    private fun startBlink() {
        if (!showCursor) return

        isBlinkCancelled = false
        blinkRunnable?.let { removeCallbacks(it) }

        blinkRunnable = object : Runnable {
            override fun run() {
                if (isBlinkCancelled) return
                cursorVisible = !cursorVisible
                invalidate()
                postDelayed(this, BLINK_INTERVAL)
            }
        }
        postDelayed(blinkRunnable!!, BLINK_INTERVAL)
    }

    private fun stopBlink() {
        isBlinkCancelled = true
        blinkRunnable?.let { removeCallbacks(it) }
        cursorVisible = true
    }

    /**
     * Set text change listener.
     */
    fun setOnTextChangeListener(listener: OnTextChangeListener?) {
        this.onTextChangeListener = listener
    }

    /**
     * Set text change listener (lambda version).
     */
    fun setOnTextChangeListener(listener: (text: String, isComplete: Boolean) -> Unit) {
        this.onTextChangeListener = object : OnTextChangeListener {
            override fun onTextChange(text: String, isComplete: Boolean) {
                listener(text, isComplete)
            }
        }
    }

    /**
     * Get current box count.
     */
    fun getBoxCount(): Int = boxCount

    // Utility methods
    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }

    private fun getTextWidth(paint: Paint, text: String): Float {
        val rect = Rect()
        paint.getTextBounds(text, 0, text.length, rect)
        return rect.width().toFloat()
    }

    private fun getTextHeight(paint: Paint, text: String): Float {
        val rect = Rect()
        paint.getTextBounds(text, 0, text.length, rect)
        return rect.height().toFloat()
    }

    /**
     * Text change listener interface.
     */
    interface OnTextChangeListener {
        /**
         * Called when input text changes.
         * @param text Current input text
         * @param isComplete Whether input is complete (reached max length)
         */
        fun onTextChange(text: String, isComplete: Boolean)
    }

    companion object {
        private const val BLINK_INTERVAL = 500L // Cursor blink interval (milliseconds)
    }
}
