package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.CheckBox
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.difft.android.base.ui.compose.DifftCheckBox
import com.difft.android.base.ui.compose.DifftCheckBoxDefaults

/**
 * XML-hostable shell around [DifftCheckBox]. Drop-in for `AppCompatCheckBox` in list rows and
 * forms: same `isChecked` / `isEnabled` / `setOnCheckedChangeListener` / `setOnClickListener`
 * surface (see [DifftToggleView] for the callback rule), and the same 32dp wrap size, so existing
 * layouts keep their spacing while the glyph itself is the design's 16dp box.
 *
 * ```xml
 * <com.difft.android.base.widget.DifftCheckBoxView
 *     android:layout_width="wrap_content"
 *     android:layout_height="wrap_content"
 *     android:checked="true" />
 * ```
 *
 * Inside a forced-dark View layout add `app:difft_forceDark="true"`. When the page moves to
 * Compose, replace the tag with a direct [DifftCheckBox] call.
 */
class DifftCheckBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftToggleView(context, attrs, defStyleAttr) {

    override val accessibilityClassName: String = CheckBox::class.java.name

    @Composable
    override fun ToggleContent(checked: Boolean, enabled: Boolean) {
        DifftCheckBox(
            checked = checked,
            onCheckedChange = null,
            // wrap_content → 32dp AppCompat footprint (min only applies when the incoming min is 0);
            // an explicit layout size arrives as EXACTLY constraints and wins. Never fillMaxSize here:
            // wrap_content children receive AT_MOST constraints, which fillMaxSize would expand to.
            // AbstractComposeView.onMeasure ignores View-level minimumWidth/Height, hence Compose-side.
            modifier = Modifier.defaultMinSize(DifftCheckBoxDefaults.TouchTarget, DifftCheckBoxDefaults.TouchTarget),
            enabled = enabled,
        )
    }
}
