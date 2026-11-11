package com.difft.android.base.widget

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.annotation.StyleableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.withStyledAttributes
import com.difft.android.base.R





















abstract class DifftComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private var forceDarkState by mutableStateOf(false)

    



    protected var enabledState by mutableStateOf(true)
        private set

    
    var forceDark: Boolean
        get() = forceDarkState
        set(value) {
            forceDarkState = value
        }

    init {
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.DifftComposeView) {
                forceDarkState = getBoolean(R.styleable.DifftComposeView_difft_forceDark, false)
                if (hasValue(R.styleable.DifftComposeView_android_enabled)) {
                    
                    
                    isEnabled = getBoolean(R.styleable.DifftComposeView_android_enabled, true)
                }
            }
        }
    }

    



    override val shouldCreateCompositionOnAttachedToWindow: Boolean
        get() = false

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        enabledState = enabled
    }

    










    protected val hostInteractionSource = MutableInteractionSource()
    private var hostPress: PressInteraction.Press? = null
    private var pressOrigin = Offset.Unspecified

    override fun drawableHotspotChanged(x: Float, y: Float) {
        super.drawableHotspotChanged(x, y)
        pressOrigin = Offset(x, y)
    }

    override fun setPressed(pressed: Boolean) {
        val changed = pressed != isPressed
        super.setPressed(pressed)
        if (!changed) return
        if (pressed) {
            
            if (!isClickable && !isLongClickable) return
            
            
            
            
            val origin = if (pressOrigin.isSpecified) pressOrigin else Offset(width / 2f, height / 2f)
            val press = PressInteraction.Press(origin)
            hostPress = press
            hostInteractionSource.tryEmit(press)
        } else {
            hostPress?.let { hostInteractionSource.tryEmit(PressInteraction.Release(it)) }
            hostPress = null
            pressOrigin = Offset.Unspecified
        }
    }

    @Composable
    final override fun Content() {
        com.difft.android.base.ui.theme.DifftTheme(
            darkTheme = forceDarkState || isSystemInDarkTheme(),
            applyWindowBackground = false,
        ) {
            ThemedContent()
        }
    }

    
    @Composable
    protected abstract fun ThemedContent()

    
    protected fun TypedArray.dpOrNull(@StyleableRes index: Int): Dp? =
        if (hasValue(index)) (getDimension(index, 0f) / resources.displayMetrics.density).dp else null
}
