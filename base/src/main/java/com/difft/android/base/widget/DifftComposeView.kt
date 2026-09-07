package com.difft.android.base.widget

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.annotation.StyleableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.withStyledAttributes
import com.difft.android.base.R

/**
 * Base for every XML-hostable shell around a Difft composable (search input, checkbox, switch, …).
 *
 * The composable is the single source of truth for the visual; the shell exists only so View
 * layouts can host it, and a page that migrates to Compose drops the shell and calls the
 * composable directly. The base owns what every shell would otherwise re-invent:
 *
 * - **Theming** — wraps [ThemedContent] in `DifftTheme` with `applyWindowBackground = false`, since
 *   an embedded subtree must never paint the host Activity's window background.
 * - **Forced dark** — [forceDark] / `app:difft_forceDark`. View layouts that hardcode `.night`
 *   colours (the call sheets) cannot express "dark" through the resource configuration, and the
 *   Compose `darkTheme` flag is invisible to XML, so the shell needs an explicit switch.
 * - **Attr helpers** — [dpOrNull] for dimension attributes that map to Compose `Dp`.
 *
 * Subclasses implement [ThemedContent] and never call `DifftTheme` themselves.
 */
abstract class DifftComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private var forceDarkState by mutableStateOf(false)

    /** Renders the content with the dark palette regardless of the system theme. */
    var forceDark: Boolean
        get() = forceDarkState
        set(value) {
            forceDarkState = value
        }

    init {
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.DifftComposeView) {
                forceDarkState = getBoolean(R.styleable.DifftComposeView_difft_forceDark, false)
            }
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

    /** The shell's composable, already inside `DifftTheme`. */
    @Composable
    protected abstract fun ThemedContent()

    /** Reads a dimension attribute as Compose [Dp], or null when the attribute is absent. */
    protected fun TypedArray.dpOrNull(@StyleableRes index: Int): Dp? =
        if (hasValue(index)) (getDimension(index, 0f) / resources.displayMetrics.density).dp else null
}
