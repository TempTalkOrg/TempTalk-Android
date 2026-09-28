package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import androidx.compose.ui.text.input.ImeAction
import com.difft.android.base.ui.compose.input.ClearMode
import com.difft.android.base.ui.compose.input.DifftInputDefaults
import com.difft.android.base.ui.compose.input.DifftTextFieldSize
import com.difft.android.base.ui.compose.input.DifftTextFieldStyle

/**
 * Search variant of [DifftClearableInputView]: magnifier icon, the library search box at
 * [DifftTextFieldSize.Large], `WhenNotEmpty` clear semantics, `ImeAction.Search`. Zero logic —
 * only default values differ, passed as base-class constructor parameters (an `open val`
 * override would read as null inside the base `init`).
 */
class DifftSearchInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftClearableInputView(
    context, attrs, defStyleAttr,
    defaultLeadingIcon = LeadingIcon.Search,
    defaultClearMode = ClearMode.WhenNotEmpty,
    defaultImeAction = ImeAction.Search,
    defaultStyle = DifftTextFieldStyle.Search,
    defaultSize = DifftTextFieldSize.Large,
    defaultHeight = DifftTextFieldSize.Large.height,
    // Null: the component applies the library insets (12) instead of the shell's bare default.
    defaultContentPadding = null,
    defaultClearIconEndInset = DifftInputDefaults.ClearIconEndInset,
)
