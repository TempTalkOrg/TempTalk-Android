package com.difft.android.call.ui.actionbar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens

/** Visual state of a round action control. */
enum class ActionButtonStyle {
    /** bg2 circle, primary-text glyph. */
    NORMAL,

    /** bgwhite (#FAFAFA) circle, bg2 glyph — "this route / feature is on". */
    SELECTED,

    /** Error-red circle, white glyph — the hang-up / leave control. */
    END,
}

/**
 * Drop shadow shared by every bar control. Its hue is the call page ground (bgpage), so it is
 * invisible on the plain call background and only reads over video or a shared screen.
 */
private val ACTION_SHADOW_COLOR = Color(0x330B0E11)

internal fun Modifier.actionButtonShadow(shape: androidx.compose.ui.graphics.Shape = CircleShape): Modifier =
    this
        .shadow(elevation = 4.dp, shape = shape, clip = false, ambientColor = ACTION_SHADOW_COLOR, spotColor = ACTION_SHADOW_COLOR)
        .shadow(elevation = 1.dp, shape = shape, clip = false, ambientColor = ACTION_SHADOW_COLOR, spotColor = ACTION_SHADOW_COLOR)

@Composable
internal fun actionButtonBackground(style: ActionButtonStyle): Color = when (style) {
    ActionButtonStyle.NORMAL -> DifftTheme.colors.backgroundSecondary
    ActionButtonStyle.SELECTED -> ColorTokens.Light.Bg
    ActionButtonStyle.END -> DifftTheme.colors.textError
}

@Composable
internal fun actionButtonGlyph(style: ActionButtonStyle): Color = when (style) {
    ActionButtonStyle.NORMAL -> DifftTheme.colors.textPrimary
    ActionButtonStyle.SELECTED -> DifftTheme.colors.backgroundSecondary
    ActionButtonStyle.END -> Color.White
}

/**
 * One round control of the call action bar.
 *
 * [iconSize] is the box the painter is fitted into: pass the button size for the
 * `call_ic_*` family (48-unit viewport with a centred 24-unit glyph) and the glyph size for
 * tight vectors such as the dots / smile / invite icons.
 *
 * [overlay] draws on top of the circle (permission badge, preset ring, people count).
 */
@Composable
fun CallActionButton(
    painter: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = CallActionBarPlanner.BUTTON_DP.dp,
    iconSize: Dp = size,
    style: ActionButtonStyle = ActionButtonStyle.NORMAL,
    iconAlpha: Float = 1f,
    /** False for two-tone glyphs (mic-off's red slash) that carry their own colours. */
    tintIcon: Boolean = true,
    testTag: String? = null,
    /** Overrides the disc colour, e.g. inside a bottom sheet whose ground equals the NORMAL fill. */
    containerColor: Color? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .size(size)
            .actionButtonShadow()
            .background(color = containerColor ?: actionButtonBackground(style), shape = CircleShape)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            alpha = iconAlpha,
            colorFilter = if (tintIcon) ColorFilter.tint(actionButtonGlyph(style)) else null,
            modifier = Modifier.size(iconSize),
        )
        overlay()
    }
}

/** Caption under a labelled control: 14sp / 20 line height, primary text. */
@Composable
fun ActionButtonLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        style = TextStyle(
            fontSize = 14.sp,
            lineHeight = CallActionBarPlanner.LABEL_LINE_HEIGHT_DP.sp,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight(400),
            color = DifftTheme.colors.textPrimary,
        ),
        modifier = modifier.height(CallActionBarPlanner.LABEL_LINE_HEIGHT_DP.dp),
    )
}

/** A control with its caption below (two-row bar, More sheet quick actions). */
@Composable
fun LabeledActionSlot(
    label: String,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CallActionBarPlanner.LABEL_GAP_DP.dp),
    ) {
        control()
        ActionButtonLabel(label)
    }
}

/**
 * Participant count on the People control: 16dp tall pill, error red, white 12sp, hugging the
 * circle's bottom-end corner.
 */
@Composable
fun BoxScope.ActionCountBadge(count: Int) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 4.dp, y = 2.dp)
            .defaultMinSize(minWidth = 20.dp)
            .height(16.dp)
            .background(color = DifftTheme.colors.textError, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            color = Color.White,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight(400),
            textAlign = TextAlign.Center,
        )
    }
}
