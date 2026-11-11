package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.Switch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.withStyledAttributes
import com.difft.android.base.R
import com.difft.android.base.ui.compose.DifftSwitch
import com.difft.android.base.ui.compose.settings.DifftSwitchRow




























class DifftSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftToggleView(context, attrs, defStyleAttr) {

    override val accessibilityClassName: String = Switch::class.java.name

    private var labelState by mutableStateOf<String?>(null)

    





    var label: String?
        get() = labelState
        set(value) {
            val previous = labelState
            labelState = value?.takeIf { it.isNotEmpty() }
            when {
                labelState != null -> contentDescription = labelState
                contentDescription == previous -> contentDescription = null
            }
        }

    init {
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.DifftSwitchView) {
                label = getString(R.styleable.DifftSwitchView_android_text)
            }
        }
    }

    @Composable
    override fun ToggleContent(checked: Boolean, enabled: Boolean) {
        val text = labelState
        if (text == null) {
            DifftSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
        } else {
            DifftSwitchRow(
                label = text,
                checked = checked,
                onCheckedChange = null, 
                enabled = enabled,
                
                
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
                
                
                minHeight = 0.dp,
            )
        }
    }
}
