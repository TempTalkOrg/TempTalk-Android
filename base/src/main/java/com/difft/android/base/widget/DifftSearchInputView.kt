package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.input.ImeAction
import com.difft.android.base.ui.compose.input.ClearMode
import com.difft.android.base.ui.compose.input.DifftInputDefaults

/**
 * Search variant of [DifftClearableInputView]: magnifier icon, 36dp pill, `WhenNotEmpty`
 * clear semantics, `ImeAction.Search`. Zero logic — only default values differ, passed as
 * base-class constructor parameters (an `open val` override would read as null inside the
 * base `init`).
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
    defaultHeight = DifftInputDefaults.Height,
    defaultContentPadding = PaddingValues(start = DifftInputDefaults.ContentPaddingStart),
    defaultClearIconEndInset = DifftInputDefaults.ClearIconEndInset,
)
