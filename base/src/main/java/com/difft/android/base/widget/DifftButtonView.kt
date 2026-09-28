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

/**
 * XML-hostable shell around [DifftButton]. Drop-in for `Button` / `AppCompatButton`: the inherited
 * `setOnClickListener` / `isEnabled` / `id` surface is unchanged, plus [text] and `android:text`.
 *
 * ```xml
 * <com.difft.android.base.widget.DifftButtonView
 *     android:id="@+id/btn_done"
 *     android:layout_width="match_parent"
 *     android:layout_height="48dp"
 *     android:enabled="false"
 *     android:text="@string/me_delete" />
 * ```
 *
 * `app:difft_buttonSize` picks `xl` (default, 48dp) / `large` (40dp) / `medium` (32dp) /
 * `small` (24dp); `app:difft_buttonVariant` picks `primary` (default, filled) / `secondary`
 * (outlined, for the Cancel or Ignore next to a filled CTA of the same size) / `danger` /
 * `success` / `text`. `android:textSize`,
 * `android:textColor` and `android:background` are deliberately not read: the size owns the type
 * scale and the theme owns the colours, so a layout cannot fork the button's look.
 * `android:clickable` / `android:focusable` are read, defaulting to true the way a `Button` does.
 *
 * There is **no loading state**. A button that starts an asynchronous operation shows the global
 * wait dialog instead — see [withWait] / [WaitDialogHandle].
 *
 * The View owns the click and the accessibility node (class `Button` plus the label as the
 * content description), and the composition is a pure visual, so a tap has exactly one path. The
 * press feedback survives that split: the View's pressed state reaches the composition through
 * [hostInteractionSource], so the button draws the same press as a Compose-hosted one.
 *
 * When the page moves to Compose, replace the tag with a direct [DifftButton] call.
 */
class DifftButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftComposeView(context, attrs, defStyleAttr) {

    private var textState by mutableStateOf("")
    private var sizeState by mutableStateOf(DifftButtonSize.XL)
    private var variantState by mutableStateOf(DifftButtonVariant.Primary)
    private val clickGate = DifftButtonDefaults.ClickGate()

    /**
     * Button label. It also becomes the accessibility name, since the View reports the node rather
     * than Compose — but only ever overwrites a description this setter installed, so an
     * `android:contentDescription` set for an icon-only button would survive a label change.
     */
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
        // Button defaults: clickable + focusable — but only when the layout did not say otherwise
        // (a row that owns the click sets android:clickable="false" on the button).
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

    /**
     * `View.performClick` runs the listener regardless of `isEnabled`, so a disabled button has to
     * swallow the tap here; and clicks closer together than
     * [DifftButtonDefaults.ClickDebounceMillis] are dropped, closing the one frame between the tap
     * and the wait dialog's window becoming modal.
     */
    override fun performClick(): Boolean {
        if (!isEnabled) return false
        if (!clickGate.tryAccept()) return false
        return super.performClick()
    }

    @Composable
    override fun ThemedContent() = DifftButton(
        text = textState,
        onClick = null, // the View owns the click
        enabled = enabledState,
        size = sizeState,
        variant = variantState,
        interactionSource = hostInteractionSource,
        // The View already reports class Button + the label to TalkBack; silencing the subtree
        // keeps it from being announced twice.
        modifier = Modifier.clearAndSetSemantics { },
    )

    private companion object {
        /** Index order of the `difft_buttonSize` enum in `attrs.xml`. */
        val SIZES = arrayOf(DifftButtonSize.S, DifftButtonSize.M, DifftButtonSize.L, DifftButtonSize.XL)

        /** `xl` — the 48dp page CTA every migrated layout hosts. */
        const val DEFAULT_SIZE = 3

        /** Index order of the `difft_buttonVariant` enum in `attrs.xml`. */
        val VARIANTS = arrayOf(
            DifftButtonVariant.Primary,
            DifftButtonVariant.Secondary,
            DifftButtonVariant.Danger,
            DifftButtonVariant.Success,
            DifftButtonVariant.Text,
        )
    }
}
