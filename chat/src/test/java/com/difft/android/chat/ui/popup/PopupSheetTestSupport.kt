package com.difft.android.chat.ui.popup

import android.view.View
import com.difft.android.base.R as BaseR





internal fun View.bottomClearance(): Int =
    paddingBottom - resources.getDimensionPixelSize(BaseR.dimen.bottom_sheet_inset)
