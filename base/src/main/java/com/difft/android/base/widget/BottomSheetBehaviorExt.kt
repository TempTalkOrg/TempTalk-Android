package com.difft.android.base.widget

import android.content.res.Resources
import com.difft.android.base.R
import com.google.android.material.bottomsheet.BottomSheetBehavior









fun BottomSheetBehavior<*>.applyMaxWidth(maxWidthPx: Int) {
    if (maxWidthPx > 0) {
        maxWidth = maxWidthPx
    }
}


fun BottomSheetBehavior<*>.applySharedMaxWidth(resources: Resources) {
    applyMaxWidth(resources.getDimensionPixelSize(R.dimen.bottom_sheet_max_width))
}
