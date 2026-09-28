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
 * - **Enabled state** — [enabledState] mirrors `isEnabled` into the composition, and
 *   `android:enabled` is applied here because the framework ignores it on a plain View.
 * - **Lazy composition** — composes on first measure, not on attach, so a GONE shell costs nothing.
 * - **Press indication** — [hostInteractionSource] carries the View's pressed state into the
 *   composition, so a click the View owns still shows a ripple.
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

    /**
     * Compose-side mirror of [isEnabled], written only by [setEnabled], so a shell's composable
     * reads one value for its disabled treatment and can never disagree with the View flag.
     */
    protected var enabledState by mutableStateOf(true)
        private set

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
                if (hasValue(R.styleable.DifftComposeView_android_enabled)) {
                    // The framework does not apply android:enabled to a plain View; route it
                    // through the setter so the View flag and the Compose state agree.
                    isEnabled = getBoolean(R.styleable.DifftComposeView_android_enabled, true)
                }
            }
        }
    }

    /**
     * Compose lazily on first measure instead of on attach: a GONE shell (a hidden submit button,
     * the message-list checkboxes) then costs no composition at all, and every View-facing state
     * lives outside the composition so nothing is lost. Composing on attach also inflated the heap
     * of Robolectric tests that build screens full of shells.
     */
    override val shouldCreateCompositionOnAttachedToWindow: Boolean
        get() = false

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        enabledState = enabled
    }

    /**
     * Press interactions forwarded from this View, for the shell's composable to draw its press
     * indication from. The View owns the click — it is the accessibility node and the single click
     * path — so the composition never sees a touch, and without this an XML-hosted shell had no
     * ripple. Fed by [setPressed], which the framework drives from touch and keyboard exactly as it
     * drives a `Button`'s pressed drawable state (TalkBack's click goes straight to `performClick`
     * and shows no press, on a `Button` too). Only a shell that owns its own click forwards the
     * press: `ViewGroup.dispatchSetPressed` cascades a row's pressed state onto every non-clickable
     * child, so a shell inside a row that owns the click is pressed together with the row, and
     * forwarding that would draw a second ripple inside the row's.
     */
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
            // A press cascaded from a clickable parent is the parent's to indicate.
            if (!isClickable && !isLongClickable) return
            // The framework sets the hotspot right before pressing on a touch; a keyboard or D-pad
            // confirm never does, so start the ripple from the centre the way Compose's own
            // clickable does for key presses. The bounded ripple reads the position directly, and
            // an unspecified Offset would throw there.
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

    /** The shell's composable, already inside `DifftTheme`. */
    @Composable
    protected abstract fun ThemedContent()

    /** Reads a dimension attribute as Compose [Dp], or null when the attribute is absent. */
    protected fun TypedArray.dpOrNull(@StyleableRes index: Int): Dp? =
        if (hasValue(index)) (getDimension(index, 0f) / resources.displayMetrics.density).dp else null
}
