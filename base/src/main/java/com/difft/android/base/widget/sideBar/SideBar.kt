package com.difft.android.base.widget.sideBar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.difft.android.base.R

class SideBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {
    // 触摸事件
    private var onTouchingLetterChangedListener: OnTouchingLetterChangedListener? = null
    private var choose = -1 // 选中
    private val paint = Paint()
    private var mTextDialog: TextView? = null
    private var mTextSizePressed = 0
    private var mWidth = 0
    private var mHeight = 0
    private var mItemHeight = 0
    fun setTextView(mTextDialog: TextView?) {
        this.mTextDialog = mTextDialog
    }

    init {
        //初始化默认属性
        val resources = context.resources
        mTextSizePressed = resources.getDimensionPixelSize(R.dimen.chat_sidebar_text_size_normal_default)
    }

    private fun init(context: Context) {
        //初始化默认属性
        val resources = context.resources
        mTextSizePressed = resources.getDimensionPixelSize(R.dimen.chat_sidebar_text_size_normal_default)
    }

    /**
     * 重写这个方法
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 获取焦点改变背景颜色.
        val height = height // 获取对应高度
        val width = width // 获取对应宽度
        val singleHeight = height / b.size // 获取每一个字母的高度
        for (i in b.indices) {
            paint.color = ContextCompat.getColor(context, R.color.t_info)
            // paint.setColor(Color.WHITE);
            paint.typeface = Typeface.DEFAULT
            paint.isAntiAlias = true
            paint.textSize = mTextSizePressed.toFloat()
            // 选中的状态
            if (i == choose) {
                paint.color = ContextCompat.getColor(context, R.color.blue_200)
                paint.isFakeBoldText = true
            }
            // x坐标等于中间-字符串宽度的一半.
            val xPos = width / 2 - paint.measureText(b[i]) / 2
            val yPos = (singleHeight * i + singleHeight).toFloat()
            canvas.drawText(b[i], xPos, yPos, paint)
            paint.reset() // 重置画笔
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        mWidth = measuredWidth
        mHeight = measuredHeight
        if (b.isNotEmpty()) mItemHeight = (mHeight - paddingTop - paddingBottom) / b.size
        //如果没有指定具体的宽度，修改宽度为Item高度+paddingLeft+paddingRight
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) mWidth = mItemHeight + paddingLeft + paddingRight
        setMeasuredDimension(mWidth, mHeight)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.action
        val y = event.y // 点击y坐标
        val oldChoose = choose
        val listener = onTouchingLetterChangedListener
        val c = (y / height * b.size).toInt() // 点击y坐标所占总高度的比例*b数组的长度就等于点击b中的个数.
        when (action) {
            MotionEvent.ACTION_UP -> {
//                setBackgroundDrawable(ColorDrawable(0x00000000))
                choose = -1 //
                invalidate()
                if (mTextDialog != null) {
                    mTextDialog?.visibility = INVISIBLE
                }
            }

            else -> {
//                setBackgroundResource(R.drawable.chat_contact_sidebar_background)
                if (oldChoose != c) {
                    if (c >= 0 && c < b.size) {
                        listener?.onTouchingLetterChanged(b[c])
                        if (mTextDialog != null) {
                            mTextDialog?.text = b[c]
                            mTextDialog?.y = calPositionY(b[c], c)
                            mTextDialog?.visibility = VISIBLE
                        }
                        choose = c
                        invalidate()
                    }
                }
            }
        }
        return true
    }

    private fun calPositionY(str: String, index: Int): Float {
        val rect = Rect()
        paint.getTextBounds(str, 0, str.length, rect)
        return (mItemHeight * index + (mItemHeight + rect.height()) / 2 + paddingTop).toFloat()
    }

    /**
     * 向外公开的方法
     *
     * @param onTouchingLetterChangedListener
     */
    fun setOnTouchingLetterChangedListener(
        onTouchingLetterChangedListener: OnTouchingLetterChangedListener?
    ) {
        this.onTouchingLetterChangedListener = onTouchingLetterChangedListener
    }

    /**
     * 接口
     *
     * @author coder
     */
    interface OnTouchingLetterChangedListener {
        fun onTouchingLetterChanged(s: String)
    }

    fun reset(letters: Array<String>) {
        b = letters
        invalidate()
    }

    // 26个字母
    var b: Array<String> = arrayOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I",
        "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V",
        "W", "X", "Y", "Z", "#"
    )
}