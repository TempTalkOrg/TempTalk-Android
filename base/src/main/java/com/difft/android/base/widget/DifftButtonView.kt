package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.content.withStyledAttributes
import com.difft.android.base.R
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.compose.DifftButtonDefaults
import com.difft.android.base.ui.compose.DifftButtonSize
import com.difft.android.base.ui.compose.DifftButtonVariant































class DifftButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftComposeView(context, attrs, defStyleAttr) {

    private var textState by mutableStateOf("")
    private var sizeState by mutableStateOf(DifftButtonSize.XL)
    private var variantState by mutableStateOf(DifftButtonVariant.Primary)
    private val clickGate = DifftButtonDefaults.ClickGate()

    




    var text: CharSequence
        get() = textState
        set(value) {
            val previous = textState
            textState = value.toString()
            if (contentDescription == null || contentDescription == previous) {
                contentDescription = textState
            }
        }

    var size: DifftButtonSize
        get() = sizeState
        set(value) {
            sizeState = value
        }

    var variant: DifftButtonVariant
        get() = variantState
        set(value) {
            variantState = value
        }

    init {
        
        
        var clickable = true
        var focusable = true
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.DifftButtonView) {
                text = getText(R.styleable.DifftButtonView_android_text) ?: ""
                sizeState = SIZES[getInt(R.styleable.DifftButtonView_difft_buttonSize, DEFAULT_SIZE)]
                variantState = VARIANTS[getInt(R.styleable.DifftButtonView_difft_buttonVariant, 0)]
                clickable = getBoolean(R.styleable.DifftButtonView_android_clickable, true)
                focusable = getBoolean(R.styleable.DifftButtonView_android_focusable, true)
            }
        }
        isClickable = clickable
        isFocusable = focusable
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    





    override fun performClick(): Boolean {
        if (!isEnabled) return false
        if (!clickGate.tryAccept()) return false
        return super.performClick()
    }

    @Composable
    override fun ThemedContent() = DifftButton(
        text = textState,
        onClick = null, 
        enabled = enabledState,
        size = sizeState,
        variant = variantState,
        interactionSource = hostInteractionSource,
        
        
        modifier = Modifier.clearAndSetSemantics { },
    )

    private companion object {
        
        val SIZES = arrayOf(DifftButtonSize.S, DifftButtonSize.M, DifftButtonSize.L, DifftButtonSize.XL)

        
        const val DEFAULT_SIZE = 3

        
        val VARIANTS = arrayOf(
            DifftButtonVariant.Primary,
            DifftButtonVariant.Secondary,
            DifftButtonVariant.Danger,
            DifftButtonVariant.Success,
            DifftButtonVariant.Text,
        )
    }
}
