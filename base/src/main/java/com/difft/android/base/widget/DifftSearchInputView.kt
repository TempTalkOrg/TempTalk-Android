package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import androidx.compose.ui.text.input.ImeAction
import com.difft.android.base.ui.compose.input.ClearMode
import com.difft.android.base.ui.compose.input.DifftInputDefaults
import com.difft.android.base.ui.compose.input.DifftTextFieldSize
import com.difft.android.base.ui.compose.input.DifftTextFieldStyle







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
    
    defaultContentPadding = null,
    defaultClearIconEndInset = DifftInputDefaults.ClearIconEndInset,
)
