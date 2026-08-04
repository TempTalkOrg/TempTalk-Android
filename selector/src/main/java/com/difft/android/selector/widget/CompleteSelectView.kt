package com.difft.android.selector.widget

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.difft.android.selector.R
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.style.PictureSelectorStyle
import com.difft.android.selector.style.SelectMainStyle
import com.difft.android.selector.utils.StyleUtils
import com.difft.android.selector.utils.ValueOf

class CompleteSelectView : LinearLayout {
    private lateinit var tvSelectNum: TextView
    private lateinit var tvComplete: TextView
    private lateinit var numberChangeAnimation: Animation
    private lateinit var config: SelectorConfig

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        inflateLayout()
        orientation = HORIZONTAL
        tvSelectNum = findViewById(R.id.ps_tv_select_num)
        tvComplete = findViewById(R.id.ps_tv_complete)
        gravity = Gravity.CENTER_VERTICAL
        numberChangeAnimation = AnimationUtils.loadAnimation(context, R.anim.ps_anim_modal_in)
        config = SelectorProviders.getInstance().selectorConfig
    }

    protected fun inflateLayout() {
        LayoutInflater.from(context).inflate(R.layout.ps_complete_selected_layout, this)
    }

    fun setCompleteSelectViewStyle() {
        val selectorStyle: PictureSelectorStyle = config.selectorStyle
        val selectMainStyle: SelectMainStyle = selectorStyle.selectMainStyle!!
        if (StyleUtils.checkStyleValidity(selectMainStyle.selectNormalBackgroundResources)) {
            setBackgroundResource(selectMainStyle.selectNormalBackgroundResources)
        }
        val selectNormalText = if (StyleUtils.checkStyleValidity(selectMainStyle.selectNormalTextResId))
            context.getString(selectMainStyle.selectNormalTextResId) else selectMainStyle.selectNormalText
        if (StyleUtils.checkTextValidity(selectNormalText)) {
            when (StyleUtils.getFormatCount(selectNormalText!!)) {
                1 -> tvComplete.text = String.format(selectNormalText, config.selectCount)
                2 -> tvComplete.text = String.format(selectNormalText, config.selectCount, config.maxSelectNum)
                else -> tvComplete.text = selectNormalText
            }
        }

        val selectNormalTextSize = selectMainStyle.selectNormalTextSize
        if (StyleUtils.checkSizeValidity(selectNormalTextSize)) {
            tvComplete.textSize = selectNormalTextSize.toFloat()
        }

        val selectNormalTextColor = selectMainStyle.selectNormalTextColor
        if (StyleUtils.checkStyleValidity(selectNormalTextColor)) {
            tvComplete.setTextColor(selectNormalTextColor)
        }

        val bottomBarStyle = selectorStyle.bottomBarStyle!!

        if (bottomBarStyle.isCompleteCountTips) {
            val selectNumRes = bottomBarStyle.bottomSelectNumResources
            if (StyleUtils.checkStyleValidity(selectNumRes)) {
                tvSelectNum.setBackgroundResource(selectNumRes)
            }
            val selectNumTextSize = bottomBarStyle.bottomSelectNumTextSize
            if (StyleUtils.checkSizeValidity(selectNumTextSize)) {
                tvSelectNum.textSize = selectNumTextSize.toFloat()
            }

            val selectNumTextColor = bottomBarStyle.bottomSelectNumTextColor
            if (StyleUtils.checkStyleValidity(selectNumTextColor)) {
                tvSelectNum.setTextColor(selectNumTextColor)
            }
        }
    }

    fun setSelectedChange(isPreview: Boolean) {
        val selectorStyle: PictureSelectorStyle = config.selectorStyle
        val selectMainStyle: SelectMainStyle = selectorStyle.selectMainStyle!!
        if (config.selectCount > 0) {
            isEnabled = true
            val selectBackground = selectMainStyle.selectBackgroundResources
            if (StyleUtils.checkStyleValidity(selectBackground)) {
                setBackgroundResource(selectBackground)
            } else {
                setBackgroundResource(R.drawable.ps_ic_trans_1px)
            }
            val selectText = if (StyleUtils.checkStyleValidity(selectMainStyle.selectTextResId))
                context.getString(selectMainStyle.selectTextResId) else selectMainStyle.selectText
            if (StyleUtils.checkTextValidity(selectText)) {
                when (StyleUtils.getFormatCount(selectText!!)) {
                    1 -> tvComplete.text = String.format(selectText, config.selectCount)
                    2 -> tvComplete.text = String.format(selectText, config.selectCount, config.maxSelectNum)
                    else -> tvComplete.text = selectText
                }
            } else {
                tvComplete.text = context.getString(R.string.ps_completed)
            }
            val selectTextSize = selectMainStyle.selectTextSize
            if (StyleUtils.checkSizeValidity(selectTextSize)) {
                tvComplete.textSize = selectTextSize.toFloat()
            }
            val selectTextColor = selectMainStyle.selectTextColor
            if (StyleUtils.checkStyleValidity(selectTextColor)) {
                tvComplete.setTextColor(selectTextColor)
            } else {
                tvComplete.setTextColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_info))
            }
            if (selectorStyle.bottomBarStyle!!.isCompleteCountTips) {
                if (tvSelectNum.visibility == View.GONE || tvSelectNum.visibility == View.INVISIBLE) {
                    tvSelectNum.visibility = View.VISIBLE
                }
                if (!TextUtils.equals(ValueOf.toString(config.selectCount), tvSelectNum.text)) {
                    tvSelectNum.text = ValueOf.toString(config.selectCount)
                    val animListener = config.onSelectAnimListener
                    if (animListener != null) {
                        animListener.onSelectAnim(tvSelectNum)
                    } else {
                        tvSelectNum.startAnimation(numberChangeAnimation)
                    }
                }
            } else {
                tvSelectNum.visibility = View.GONE
            }
        } else {
            if (isPreview && selectMainStyle.isCompleteSelectRelativeTop) {
                isEnabled = true
                val selectBackground = selectMainStyle.selectBackgroundResources
                if (StyleUtils.checkStyleValidity(selectBackground)) {
                    setBackgroundResource(selectBackground)
                } else {
                    setBackgroundResource(R.drawable.ps_ic_trans_1px)
                }
                val selectTextColor = selectMainStyle.selectTextColor
                if (StyleUtils.checkStyleValidity(selectTextColor)) {
                    tvComplete.setTextColor(selectTextColor)
                } else {
                    tvComplete.setTextColor(ContextCompat.getColor(context, R.color.ps_color_9b))
                }
            } else {
                isEnabled = config.isEmptyResultReturn
                val normalBackground = selectMainStyle.selectNormalBackgroundResources
                if (StyleUtils.checkStyleValidity(normalBackground)) {
                    setBackgroundResource(normalBackground)
                } else {
                    setBackgroundResource(R.drawable.ps_ic_trans_1px)
                }
                val normalTextColor = selectMainStyle.selectNormalTextColor
                if (StyleUtils.checkStyleValidity(normalTextColor)) {
                    tvComplete.setTextColor(normalTextColor)
                } else {
                    tvComplete.setTextColor(ContextCompat.getColor(context, R.color.ps_color_9b))
                }
            }

            tvSelectNum.visibility = View.GONE
            val selectNormalText = if (StyleUtils.checkStyleValidity(selectMainStyle.selectNormalTextResId))
                context.getString(selectMainStyle.selectNormalTextResId) else selectMainStyle.selectNormalText
            if (StyleUtils.checkTextValidity(selectNormalText)) {
                when (StyleUtils.getFormatCount(selectNormalText!!)) {
                    1 -> tvComplete.text = String.format(selectNormalText, config.selectCount)
                    2 -> tvComplete.text = String.format(selectNormalText, config.selectCount, config.maxSelectNum)
                    else -> tvComplete.text = selectNormalText
                }
            } else {
                tvComplete.text = context.getString(R.string.ps_please_select)
            }
            val normalTextSize = selectMainStyle.selectNormalTextSize
            if (StyleUtils.checkSizeValidity(normalTextSize)) {
                tvComplete.textSize = normalTextSize.toFloat()
            }
        }
    }
}
