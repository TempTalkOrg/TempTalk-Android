package com.difft.android.selector.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import com.difft.android.selector.R
import com.difft.android.selector.utils.StyleUtils

class PreviewBottomNavBar : BottomNavBar {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    override fun handleLayoutUI() {
        tvPreview.visibility = View.GONE
        tvImageEditor.setOnClickListener(this)
        tvImageEditor.visibility = View.GONE
    }

    fun isDisplayEditor(isHasVideo: Boolean) {
        tvImageEditor.visibility = View.GONE
    }

    fun getEditor(): TextView = tvImageEditor

    override fun setBottomNavBarStyle() {
        super.setBottomNavBarStyle()
        val bottomBarStyle = config.selectorStyle.bottomBarStyle!!
        if (StyleUtils.checkStyleValidity(bottomBarStyle.bottomPreviewNarBarBackgroundColor)) {
            setBackgroundColor(bottomBarStyle.bottomPreviewNarBarBackgroundColor)
        } else if (StyleUtils.checkSizeValidity(bottomBarStyle.bottomNarBarBackgroundColor)) {
            setBackgroundColor(bottomBarStyle.bottomNarBarBackgroundColor)
        }
    }

    override fun onClick(view: View) {
        super.onClick(view)
        if (view.id == R.id.ps_tv_editor) {
            bottomNavBarListener?.onEditImage()
        }
    }
}
