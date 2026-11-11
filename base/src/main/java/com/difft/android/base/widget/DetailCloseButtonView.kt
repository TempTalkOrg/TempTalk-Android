package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.Button
import androidx.compose.runtime.Composable
import androidx.core.content.withStyledAttributes
import com.difft.android.base.R
import com.difft.android.base.ui.compose.DetailCloseButton

















class DetailCloseButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftComposeView(context, attrs, defStyleAttr) {

    init {
        
        var clickable = true
        var focusable = true
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.DetailCloseButtonView) {
                clickable = getBoolean(R.styleable.DetailCloseButtonView_android_clickable, true)
                focusable = getBoolean(R.styleable.DetailCloseButtonView_android_focusable, true)
            }
        }
        isClickable = clickable
        isFocusable = focusable
        if (contentDescription == null) contentDescription = context.getString(R.string.base_close)
        clipChildren = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? ViewGroup)?.clipChildren = false
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    @Composable
    override fun ThemedContent() {
        DetailCloseButton(
            onClick = null,
            interactionSource = hostInteractionSource,
        )
    }
}
