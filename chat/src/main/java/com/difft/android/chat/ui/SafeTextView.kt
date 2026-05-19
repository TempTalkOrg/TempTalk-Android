package com.difft.android.chat.ui

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.textclassifier.TextClassifier
import androidx.appcompat.widget.AppCompatTextView

/**
 * A TextView that handles multiple Android framework bugs related to text selection and drawing.
 *
 * **Issues Addressed:**
 * 1. **AOSP #219831** (<https://issuetracker.google.com/issues/219831>): Layout becomes null
 *    during hardware-accelerated drawing due to a race condition between the main thread
 *    (modifying text) and the render thread (drawing).
 *    Manifests as: NullPointerException in `TextView.onDraw()`.
 *
 * 2. **SmartSelect / TextClassifier sprite crash**: Android O+ introduced SmartSelect,
 *    which can crash on certain sprite-based text with `IllegalArgumentException`
 *    in `SelectionActionModeHelper`.
 *    Manifests as: `IllegalArgumentException: Invalid offset` in `TextLinks`.
 *
 * **Mitigations:**
 *  - Disables `TextClassifier` (SmartSelect) on API 26+ to prevent sprite-detection crashes.
 *  - Catches both NPE and IAE in [onDraw] as a fail-safe for race conditions.
 *
 * **Usage:**
 * Use in place of a regular TextView in RecyclerView items or anywhere text content is
 * dynamically updated. No special lifecycle management required — standard ViewHolder
 * binding works correctly.
 *
 * **No reporting / no logging:** unlike [SafeFrameLayout], `onDraw` fires every frame, so
 * a log/report here would flood. This class is preventive hardening with no observed
 * crash signature on TempTalk currently; it is ported for parity with difft-android to
 * eliminate a known-theoretical race window.
 */
open class SafeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        // Disable TextClassifier (SmartSelect) to prevent crashes on sprite text.
        // Root-cause fix: NO_OP classifier bypasses the buggy sprite detection path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setTextClassifier(TextClassifier.NO_OP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        try {
            super.onDraw(canvas)
        } catch (e: NullPointerException) {
            // AOSP #219831: Layout becomes null during hardware-accelerated drawing.
            // Root cause: race condition in TextView's layout invalidation.
            // Safe to ignore — the next frame redraws correctly.
        } catch (e: IllegalArgumentException) {
            // SmartSelect bug: invalid offset in TextLinks when processing sprite text.
            // Root cause: TextClassifier.generateLinks() miscalculates sprite boundaries.
            // Safe to ignore — fail-safe if NO_OP classifier wasn't applied.
        }
    }
}
