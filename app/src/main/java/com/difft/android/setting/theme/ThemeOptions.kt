package com.difft.android.setting.theme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.theme.DifftRadius
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.createDarkExtendedColors
import com.difft.android.base.ui.theme.createLightExtendedColors
import com.difft.android.base.ui.theme.tokens.Palette
import com.difft.android.chat.ui.ChatWallpaperPainter
import com.difft.android.chat.ui.theme.ChatTheme


internal val AppearanceThumbnailHeight = 92.dp
internal val ChatThemeThumbnailHeight = 120.dp


internal val AppearanceCardShape = RoundedCornerShape(DifftRadius.floating)
private val ThumbnailRadius = 12.dp
private val ThumbnailShape = RoundedCornerShape(ThumbnailRadius)
private val CardPadding = 16.dp
private val CardBottomPadding = 14.dp
private val OptionSpacing = 12.dp
private val LabelTopGap = 10.dp
private val UnselectedBorder = 1.dp
private val RingWidth = 2.dp
private val RingGap = 2.dp
private val RadioSize = 18.dp
private val RadioDot = 8.dp
private val RadioBorder = 1.5.dp
private val BarHeight = 12.dp
private val BarSpacing = 10.dp
internal val ReceivedShadowOffset = 1.dp
private const val BAR_INSET_RATIO = 0.14f
private const val BAR_WIDTH_RATIO = 0.6f


private const val THUMBNAIL_TILE_SCALE = 0.45f
internal const val PREVIEW_TILE_SCALE = 0.65f


private const val LABEL_BREAK = "\u200B"


internal class ThemeOption(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val thumbnail: @Composable (Modifier) -> Unit,
)





@Composable
internal fun ThemeOptionSection(title: String, note: String, thumbnailHeight: Dp, options: List<ThemeOption>) {
    Column {
        DifftSettingsCaption(
            text = title,
            color = DifftTheme.colors.textSecondary,
            style = DifftSettingsDefaults.TitleTextStyle,
            modifier = Modifier
                .padding(horizontal = DifftSettingsDefaults.CaptionInset)
                .padding(bottom = DifftSettingsDefaults.TitleBottomGap),
        )
        val labelStyle = DifftTheme.typography.p3
        val measurer = rememberTextMeasurer()
        val radioAndGap = with(LocalDensity.current) { (RadioSize + DifftTheme.spacing.inlineSmall).roundToPx() }
        val spacing = with(LocalDensity.current) { OptionSpacing.roundToPx() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppearanceCardShape)
                .background(DifftSettingsDefaults.RowSurfaceColor)
                .padding(start = CardPadding, end = CardPadding, top = CardPadding, bottom = CardBottomPadding),
        ) {
            val columnWidth = (constraints.maxWidth - spacing * (options.size - 1)) / options.size.coerceAtLeast(1)
            val labelWidths = remember(options.map { it.label }, labelStyle) {
                options.map { measurer.measure(it.label.withoutBreak(), labelStyle, softWrap = false, maxLines = 1).size.width }
            }
            
            val stacked = labelWidths.any { radioAndGap + it > columnWidth }
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(OptionSpacing),
            ) {
                options.forEachIndexed { index, option ->
                    ThemeOptionItem(
                        option = option,
                        thumbnailHeight = thumbnailHeight,
                        stacked = stacked,
                        labelFitsOneLine = labelWidths[index] <= columnWidth,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        DifftSettingsCaption(
            text = note,
            modifier = Modifier
                .padding(horizontal = DifftSettingsDefaults.CaptionInset)
                .padding(top = DifftSettingsDefaults.FooterTopGap),
        )
    }
}

@Composable
private fun ThemeOptionItem(
    option: ThemeOption,
    thumbnailHeight: Dp,
    stacked: Boolean,
    labelFitsOneLine: Boolean,
    modifier: Modifier,
) {
    val ring = DifftTheme.colors.primary
    val border = DifftTheme.colors.line
    
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.selectable(
            selected = option.selected,
            interactionSource = interaction,
            indication = null,
            role = Role.RadioButton,
            onClick = option.onClick,
        ),
    ) {
        option.thumbnail(
            Modifier
                .fillMaxWidth()
                .height(thumbnailHeight)
                .drawBehind { if (option.selected) drawRing(ring) }
                .clip(ThumbnailShape)
                .then(if (option.selected) Modifier else Modifier.border(UnselectedBorder, border, ThumbnailShape)),
        )
        OptionCaption(option.label, option.selected, stacked, labelFitsOneLine, Modifier.padding(top = LabelTopGap))
    }
}

private fun String.withoutBreak(): String = replace(LABEL_BREAK, "")






@Composable
private fun OptionCaption(label: String, selected: Boolean, stacked: Boolean, labelFitsOneLine: Boolean, modifier: Modifier) {
    val style = DifftTheme.typography.p3
    val gap = DifftTheme.spacing.inlineSmall
    if (!stacked) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
            ThemeRadio(selected)
            Text(text = label.withoutBreak(), style = style, color = DifftTheme.colors.textPrimary)
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
            ThemeRadio(selected)
            Text(
                text = if (labelFitsOneLine) label.withoutBreak() else label.replace(LABEL_BREAK, "\n"),
                style = style,
                color = DifftTheme.colors.textPrimary,
            )
        }
    }
}


private fun DrawScope.drawRing(color: Color) {
    val stroke = RingWidth.toPx()
    val outset = RingGap.toPx() + stroke / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(-outset, -outset),
        size = Size(size.width + outset * 2, size.height + outset * 2),
        cornerRadius = CornerRadius(ThumbnailRadius.toPx() + outset),
        style = Stroke(stroke),
    )
}


@Composable
private fun ThemeRadio(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(RadioSize)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(DifftTheme.colors.primary)
                } else {
                    Modifier.border(RadioBorder, DifftTheme.colors.textDisabled, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(RadioDot).clip(CircleShape).background(DifftTheme.colors.textOnPrimary))
    }
}





@Composable
internal fun AppearanceThumbnail(light: Boolean, dark: Boolean, modifier: Modifier) {
    val panels = remember(light, dark) {
        listOfNotNull(createLightExtendedColors().takeIf { light }, createDarkExtendedColors().takeIf { dark })
    }
    Box(
        modifier.drawBehind {
            val panelWidth = size.width / panels.size
            panels.forEachIndexed { index, colors ->
                val left = panelWidth * index
                drawRect(colors.background, topLeft = Offset(left, 0f), size = Size(panelWidth, size.height))
                drawBars(left, panelWidth, ChatBubbleColors(listOf(colors.bubbleRecv), List(2) { colors.bubbleSent }))
            }
        },
    )
}


@Composable
internal fun ChatThemeThumbnail(theme: ChatTheme, modifier: Modifier) {
    val bubbles = chatBubbleColors(theme)
    Box(modifier.chatWallpaper(theme, THUMBNAIL_TILE_SCALE).drawBehind { drawBars(0f, size.width, bubbles) })
}






internal class ChatBubbleColors(
    val received: List<Color>,
    val sent: List<Color>,
    
    val receivedShadow: Color? = null,
) {
    
    val receivedFill: Brush
        get() = if (received.size == 1) SolidColor(received.single()) else Brush.linearGradient(colorStops = RECEIVED_STOPS.zip(received).toTypedArray())

    
    fun receivedBrush(topLeft: Offset, size: Size): Brush = when (received.size) {
        1 -> SolidColor(received.single())
        else -> Brush.linearGradient(
            colorStops = RECEIVED_STOPS.zip(received).toTypedArray(),
            start = topLeft,
            end = Offset(topLeft.x + size.width, topLeft.y + size.height),
        )
    }
}


private val RECEIVED_STOPS = listOf(0f, 0.45f, 1f)


@Composable
internal fun chatBubbleColors(theme: ChatTheme): ChatBubbleColors {
    if (theme != ChatTheme.GRADIENT) return ChatBubbleColors(listOf(DifftTheme.colors.bubbleRecv), List(2) { DifftTheme.colors.bubbleSent })
    
    val night = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val veil = if (night) Palette.ChatReceivedVeilDark else Palette.ChatReceivedVeil
    val inks = if (night) {
        listOf(Palette.ChatInkPurpleDark, Palette.ChatInkBlueDark, Palette.ChatInkTealDark)
    } else {
        listOf(Palette.ChatInkPurple, Palette.ChatInkBlue, Palette.ChatInkTeal)
    }
    return ChatBubbleColors(
        received = inks.map { veil.compositeOver(it) },
        sent = listOf(Palette.ChatGradientPurple, Palette.ChatGradientBlue),
        receivedShadow = if (night) null else Palette.ChatReceivedShadow,
    )
}


@Composable
internal fun Modifier.chatWallpaper(theme: ChatTheme, tileScale: Float): Modifier {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    
    val patternVersion = remember { mutableIntStateOf(0) }
    val painter = remember(theme, configuration) {
        ChatWallpaperPainter(context, theme).also { it.onPatternReady = { patternVersion.intValue++ } }
    }
    return drawBehind {
        patternVersion.intValue
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.scale(tileScale, tileScale)
            painter.draw(canvas.nativeCanvas, 0f, 0f, size.width / tileScale, size.height / tileScale)
            canvas.restore()
        }
    }
}


private fun DrawScope.drawBars(left: Float, width: Float, bubbles: ChatBubbleColors) {
    val barHeight = BarHeight.toPx()
    val inset = width * BAR_INSET_RATIO
    val barSize = Size((width - inset * 2) * BAR_WIDTH_RATIO, barHeight)
    val barWidth = barSize.width
    val top = (size.height - barHeight * 2 - BarSpacing.toPx()) / 2
    val radius = CornerRadius(barHeight / 2)
    val receivedTopLeft = Offset(left + inset, top)
    bubbles.receivedShadow?.let {
        drawRoundRect(it, topLeft = receivedTopLeft + Offset(0f, ReceivedShadowOffset.toPx()), size = barSize, cornerRadius = radius)
    }
    drawRoundRect(bubbles.receivedBrush(receivedTopLeft, barSize), topLeft = receivedTopLeft, size = barSize, cornerRadius = radius)
    val sentLeft = left + width - inset - barWidth
    val sentTop = top + barHeight + BarSpacing.toPx()
    
    val brush = Brush.horizontalGradient(bubbles.sent, startX = sentLeft, endX = sentLeft + barWidth)
    drawRoundRect(brush, topLeft = Offset(sentLeft, sentTop), size = Size(barWidth, barHeight), cornerRadius = radius)
}
