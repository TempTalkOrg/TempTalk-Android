package com.difft.android.base.widget

import android.content.res.Resources
import com.difft.android.base.R
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * Caps the sheet at [maxWidthPx]; a non-positive value means "no cap" (full width).
 *
 * The cap lives on the behavior, never on the sheet view's `android:maxWidth`: the behavior clamps
 * in `onMeasureChild` so the first frame is already capped, while a view-level cap (e.g. a
 * ConstraintLayout's own maxWidth) would also freeze any later width change such as the popup
 * chat's maximize ramp. Horizontal centering comes from the container's `layout_gravity`.
 */
fun BottomSheetBehavior<*>.applyMaxWidth(maxWidthPx: Int) {
    if (maxWidthPx > 0) {
        maxWidth = maxWidthPx
    }
}

/** Applies the app-wide bottom-sheet width cap (`R.dimen.bottom_sheet_max_width`). */
fun BottomSheetBehavior<*>.applySharedMaxWidth(resources: Resources) {
    applyMaxWidth(resources.getDimensionPixelSize(R.dimen.bottom_sheet_max_width))
}
