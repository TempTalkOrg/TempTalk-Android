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
import androidx.core.graphics.createBitmap
import com.difft.android.login.R






















class VerificationCodeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private var boxCount: Int = 6

    private var codeTextColor: Int = Color.BLACK

    private var boxBackgroundColor: Int = Color.WHITE

    private var borderColor: Int = Color.GRAY

    private var borderSelectedColor: Int = Color.BLUE

    private var borderRadius: Float = dpToPx(6f)

    private var borderWidth: Float = dpToPx(1f)

    private var itemMargin: Float = dpToPx(10f)

    private var showCursor: Boolean = false

    private var cursorColor: Int = Color.BLUE

    private var cursorWidth: Float = dpToPx(2f)

    
    private var cursorHeight: Float = 0f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val boxDrawable = GradientDrawable()

    
    private val reusableClipBounds = Rect()

    private var cursorVisible = true
    private var blinkRunnable: Runnable? = null
    private var isBlinkCancelled = false

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
            
            boxCount = typedArray.getInt(
                R.styleable.VerificationCodeEditText_psw_box_count,
                0
            )
        } finally {
            typedArray.recycle()
        }

        
        if (boxCount <= 0) {
            boxCount = getMaxLengthSafely()
        }

        if (boxCount <= 0) {
            boxCount = 6
        }
    }

    



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
        isCursorVisible = false
        
        setTextColor(Color.TRANSPARENT)
        isFocusableInTouchMode = true
        
        setOnLongClickListener { true }
        background = null

        textPaint.color = codeTextColor
        textPaint.textSize = textSize

        cursorPaint.color = cursorColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = measuredWidth
        val viewHeight = measuredHeight

        
        if (!canvas.getClipBounds(reusableClipBounds)) return
        val visibleTop = reusableClipBounds.top.toFloat()
        val visibleHeight = (reusableClipBounds.bottom - reusableClipBounds.top).toFloat()

        val boxWidth = (viewWidth - itemMargin * (boxCount - 1)) / boxCount
        val boxHeight = visibleHeight - borderWidth

        
        
        
        
        if (boxWidth <= 0f || boxHeight <= 0f) return

        val currentTextLength = text?.length ?: 0
        val currentText = text?.toString() ?: ""

        boxDrawable.setStroke(borderWidth.toInt(), borderColor)
        boxDrawable.cornerRadius = borderRadius
        boxDrawable.setColor(boxBackgroundColor)
        val normalBitmap = drawableToBitmap(boxDrawable, boxWidth.toInt(), boxHeight.toInt())

        var selectedBitmap: Bitmap? = null
        if (borderSelectedColor != borderColor) {
            boxDrawable.setStroke(borderWidth.toInt(), borderSelectedColor)
            selectedBitmap = drawableToBitmap(boxDrawable, boxWidth.toInt(), boxHeight.toInt())
        }

        
        val drawTop = visibleTop + borderWidth / 2

        for (i in 0 until boxCount) {
            val left = boxWidth * i + itemMargin * i

            val isSelected = (currentTextLength == i)
            val bitmap = if (isSelected && selectedBitmap != null) selectedBitmap else normalBitmap
            canvas.drawBitmap(bitmap, left, drawTop, bitmapPaint)

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
        val bitmap = createBitmap(width, height, config)
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

    fun setOnTextChangeListener(listener: OnTextChangeListener?) {
        this.onTextChangeListener = listener
    }

    fun setOnTextChangeListener(listener: (text: String, isComplete: Boolean) -> Unit) {
        this.onTextChangeListener = object : OnTextChangeListener {
            override fun onTextChange(text: String, isComplete: Boolean) {
                listener(text, isComplete)
            }
        }
    }

    fun getBoxCount(): Int = boxCount

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

    interface OnTextChangeListener {
        




        fun onTextChange(text: String, isComplete: Boolean)
    }

    companion object {
        private const val BLINK_INTERVAL = 500L 
    }
}
