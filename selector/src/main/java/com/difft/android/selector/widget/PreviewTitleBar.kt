package com.difft.android.selector.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.RelativeLayout
import com.difft.android.selector.R
import com.difft.android.selector.utils.StyleUtils

class PreviewTitleBar : TitleBar {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    override fun setTitleBarStyle() {
        super.setTitleBarStyle()
        val titleBarStyle = config.selectorStyle.titleBarStyle!!
        if (StyleUtils.checkStyleValidity(titleBarStyle.previewTitleBackgroundColor)) {
            setBackgroundColor(titleBarStyle.previewTitleBackgroundColor)
        } else if (StyleUtils.checkSizeValidity(titleBarStyle.titleBackgroundColor)) {
            setBackgroundColor(titleBarStyle.titleBackgroundColor)
        }
        if (StyleUtils.checkStyleValidity(titleBarStyle.previewTitleLeftBackResource)) {
            ivLeftBack.setImageResource(titleBarStyle.previewTitleLeftBackResource)
        }
        rlAlbumBg.setOnClickListener(null)
        viewAlbumClickArea.setOnClickListener(null)
        val layoutParams = rlAlbumBg.layoutParams as RelativeLayout.LayoutParams
        layoutParams.removeRule(RelativeLayout.END_OF)
        layoutParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
        rlAlbumBg.setBackgroundResource(R.drawable.ps_ic_trans_1px)
        tvCancel.visibility = View.GONE
        ivArrow.visibility = View.GONE
        viewAlbumClickArea.visibility = View.GONE
    }
}
