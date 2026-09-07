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
import com.difft.android.base.ui.compose.DifftSwitchRow

/**
 * XML-hostable shell around [DifftSwitch]. Drop-in for `SwitchCompat`: same `isChecked` /
 * `isEnabled` / `setOnCheckedChangeListener` / `setOnClickListener` surface, plus
 * [DifftToggleView.setOnToggleRequestListener] for the controlled mode (see [DifftToggleView] for
 * the callback rule).
 *
 * Two forms, chosen by `android:text`:
 * - no text  → the bare 51×31 control (wrap_content footprint = the design box)
 * - text     → a [DifftSwitchRow]: label start, switch end. `SwitchCompat` is a TextView, so the
 *   migrated label rows keep their layout untouched; `android:background` and
 *   `android:paddingStart/End` stay on the View, which `AbstractComposeView` honours (it subtracts
 *   padding in onMeasure and offsets the composition in onLayout).
 *
 * ```xml
 * <com.difft.android.base.widget.DifftSwitchView
 *     android:layout_width="match_parent"
 *     android:layout_height="52dp"
 *     android:paddingStart="16dp"
 *     android:paddingEnd="8dp"
 *     android:background="@color/bg.elevated"
 *     android:text="@string/chat_stick" />
 * ```
 *
 * `android:textSize` / `android:textColor` are deliberately not read: every migrated row is the
 * theme's 16sp `textPrimary`, and two fewer attrs is two fewer ways to bypass the theme.
 *
 * When the page moves to Compose, replace the tag with a direct [DifftSwitch] / [DifftSwitchRow].
 */
class DifftSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftToggleView(context, attrs, defStyleAttr) {

    override val accessibilityClassName: String = Switch::class.java.name

    private var labelState by mutableStateOf<String?>(null)

    /**
     * Row label. Null / blank renders the bare control. A label also becomes the accessibility
     * name, but only a label ever writes it: clearing the label drops the description only when it
     * is still the one this setter installed, so an `android:contentDescription` on the bare form
     * survives.
     */
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
                onCheckedChange = null, // the View owns the click
                enabled = enabled,
                // The View reports the whole row to TalkBack (Switch class + contentDescription);
                // silencing the subtree keeps TalkBack from announcing the label twice.
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
                // The View's layout_height is the row height; a second min here would push a
                // wrap_content row past its parent's AT_MOST height.
                minHeight = 0.dp,
            )
        }
    }
}
