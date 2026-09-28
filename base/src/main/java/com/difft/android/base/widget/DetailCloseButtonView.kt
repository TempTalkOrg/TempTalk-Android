package com.difft.android.base.widget

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.core.content.withStyledAttributes
import com.difft.android.base.R
import com.difft.android.base.ui.compose.DetailCloseButton

/**
 * XML-hostable shell around [DetailCloseButton] for View-based detail pages.
 *
 * ```xml
 * <com.difft.android.base.widget.DetailCloseButtonView
 *     android:id="@+id/btn_close"
 *     android:layout_width="@dimen/detail_close_button_size"
 *     android:layout_height="@dimen/detail_close_button_size"
 *     android:layout_gravity="bottom|center_horizontal"
 *     android:layout_marginBottom="@dimen/detail_close_button_margin" />
 * ```
 *
 * The View owns the click and the accessibility node; the composition is visual only and draws its
 * ripple from the press this View forwards. The shadow is View elevation with a circular outline
 * rather than a Compose shadow, which the composition bounds would clip.
 */
class DetailCloseButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftComposeView(context, attrs, defStyleAttr) {

    init {
        // Button defaults: clickable + focusable — but only when the layout did not say otherwise.
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
        elevation = resources.getDimension(R.dimen.detail_close_button_elevation)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    @Composable
    override fun ThemedContent() {
        DetailCloseButton(
            onClick = null,
            elevation = 0.dp,
            interactionSource = hostInteractionSource,
        )
    }
}
