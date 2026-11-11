package com.difft.android.chat.ui.popup

import com.difft.android.base.widget.applyFloatingCardShadow
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import com.difft.android.base.R as BaseR








internal object PopupSheetCard {

    fun apply(sheet: View) {
        val radius = sheet.resources.getDimension(BaseR.dimen.radius_floating)
        sheet.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val inset = view.resources.getDimensionPixelSize(BaseR.dimen.bottom_sheet_inset)
                outline.setRoundRect(inset, 0, view.width - inset, view.height - view.paddingBottom, radius)
            }
        }
        sheet.clipToOutline = true
        sheet.elevation = sheet.resources.getDimension(BaseR.dimen.bottom_sheet_elevation)
        sheet.applyFloatingCardShadow()
    }

    
    fun setBottomClearance(sheet: View, clearancePx: Int) {
        val inset = sheet.resources.getDimensionPixelSize(BaseR.dimen.bottom_sheet_inset)
        sheet.setPadding(inset, 0, inset, clearancePx + inset)
        sheet.invalidateOutline()
    }
}
