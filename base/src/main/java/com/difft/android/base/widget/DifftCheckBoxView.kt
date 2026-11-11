package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.CheckBox
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.difft.android.base.ui.compose.DifftCheckBox
import com.difft.android.base.ui.compose.DifftCheckBoxDefaults
















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
            
            
            
            
            modifier = Modifier.defaultMinSize(DifftCheckBoxDefaults.TouchTarget, DifftCheckBoxDefaults.TouchTarget),
            enabled = enabled,
        )
    }
}
