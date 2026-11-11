package com.difft.android.setting.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.compose.settings.DifftSegmentedControl
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSwitchRow
import com.difft.android.base.ui.sidechrome.NavigationPlacement
import com.difft.android.base.ui.theme.DifftRadius
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.density.DisplaySize
import com.difft.android.chat.ui.theme.ChatTheme
import com.difft.android.chat.R as ChatR


private val RowMinHeight = 56.dp
private val RowSidePadding = 16.dp
private val RowLabelControlGap = 12.dp
private val StackedRowTopPadding = 12.dp
private val StackedRowBottomPadding = 4.dp

private val PreviewOuterPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
private val PreviewInnerPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)
private val PreviewSpacing = 8.dp
private val PreviewShape = RoundedCornerShape(DifftRadius.card)
private val NoticePadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
private val NoticeIconGap = 4.dp
private val BubblePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
private const val BUBBLE_MAX_WIDTH_FRACTION = 0.92f
private const val NOTICE_SURFACE_ALPHA = 0.72f
private val Pill = RoundedCornerShape(50)


private val ReceivedBubbleShape = RoundedCornerShape(
    topStart = DifftRadius.card, topEnd = DifftRadius.card, bottomEnd = DifftRadius.card, bottomStart = DifftRadius.medium,
)
private val SentBubbleShape = RoundedCornerShape(
    topStart = DifftRadius.card, topEnd = DifftRadius.card, bottomEnd = DifftRadius.medium, bottomStart = DifftRadius.card,
)


private const val FONT_GLYPH = "A"


@Composable
internal fun AppearanceSection(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        DifftSettingsCaption(
            text = title,
            color = DifftTheme.colors.textSecondary,
            style = DifftSettingsDefaults.TitleTextStyle,
            modifier = Modifier
                .padding(horizontal = DifftSettingsDefaults.CaptionInset)
                .padding(bottom = DifftSettingsDefaults.TitleBottomGap),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppearanceCardShape)
                .background(DifftSettingsDefaults.RowSurfaceColor),
            content = content,
        )
        if (note != null) {
            DifftSettingsCaption(
                text = note,
                modifier = Modifier
                    .padding(horizontal = DifftSettingsDefaults.CaptionInset)
                    .padding(top = DifftSettingsDefaults.FooterTopGap),
            )
        }
    }
}









@Composable
internal fun AppearanceSettingRow(label: String, control: @Composable () -> Unit) {
    Layout(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RowSidePadding),
        content = {
            Text(
                text = label,
                style = DifftSettingsDefaults.LabelTextStyle,
                color = DifftTheme.colors.textPrimary,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
            control()
        },
    ) { measurables, constraints ->
        val gap = RowLabelControlGap.roundToPx()
        val labelOneLine = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val controlPlaceable = measurables[1].measure(Constraints(maxWidth = constraints.maxWidth))
        
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else labelOneLine + gap + controlPlaceable.width
        val loose = Constraints(maxWidth = width)
        val oneLine = labelOneLine + gap + controlPlaceable.width <= width
        if (oneLine) {
            val labelPlaceable = measurables[0].measure(Constraints(maxWidth = width - gap - controlPlaceable.width))
            val height = maxOf(RowMinHeight.roundToPx(), labelPlaceable.height, controlPlaceable.height)
            layout(width, height) {
                labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
                controlPlaceable.placeRelative(width - controlPlaceable.width, (height - controlPlaceable.height) / 2)
            }
        } else {
            val labelPlaceable = measurables[0].measure(loose)
            val top = StackedRowTopPadding.roundToPx()
            val height = top + labelPlaceable.height + controlPlaceable.height + StackedRowBottomPadding.roundToPx()
            layout(width, height) {
                labelPlaceable.placeRelative(0, top)
                controlPlaceable.placeRelative(width - controlPlaceable.width, top + labelPlaceable.height)
            }
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = RowSidePadding),
        thickness = DifftTheme.hairline,
        color = DifftTheme.colors.divider,
    )
}


@Composable
internal fun TextSection(chatTheme: ChatTheme, largeText: Boolean, onLargeTextChange: (Boolean) -> Unit) {
    AppearanceSection(title = stringResource(ChatR.string.me_text)) {
        TextPreview(chatTheme)
        val label = stringResource(R.string.me_text_size)
        AppearanceSettingRow(label) {
            DifftSegmentedControl(
                options = listOf(stringResource(R.string.me_text_size_default), stringResource(R.string.me_text_size_lage)),
                selectedIndex = if (largeText) 1 else 0,
                onSelect = { onLargeTextChange(it == 1) },
                groupLabel = label,
                leading = { index, selected ->
                    Text(
                        text = FONT_GLYPH,
                        style = if (index == 0) DifftTheme.typography.footnote else DifftTheme.typography.p2,
                        color = if (selected) DifftTheme.colors.textPrimary else DifftTheme.colors.textSecondary,
                    )
                },
            )
        }
    }
}






@Composable
private fun TextPreview(chatTheme: ChatTheme) {
    val bubbles = chatBubbleColors(chatTheme)
    val previewLabel = stringResource(ChatR.string.me_text_preview)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PreviewOuterPadding)
            .clip(PreviewShape)
            .chatWallpaper(chatTheme, PREVIEW_TILE_SCALE)
            .semantics { contentDescription = previewLabel }
            .padding(PreviewInnerPadding),
        verticalArrangement = Arrangement.spacedBy(PreviewSpacing),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(Pill)
                .background(DifftTheme.colors.background.copy(alpha = NOTICE_SURFACE_ALPHA))
                .padding(NoticePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NoticeIconGap),
        ) {
            val noticeStyle = DifftTheme.typography.p4
            Icon(
                painter = painterResource(ChatR.drawable.ic_lock),
                contentDescription = null,
                tint = DifftTheme.colors.textSecondary,
                modifier = Modifier.size(with(LocalDensity.current) { noticeStyle.fontSize.toDp() }),
            )
            Text(
                text = stringResource(ChatR.string.me_text_preview_notice),
                style = noticeStyle,
                color = DifftTheme.colors.textSecondary,
            )
        }
        PreviewBubble(
            text = stringResource(ChatR.string.me_text_preview_received),
            textColor = DifftTheme.colors.textPrimary,
            fill = bubbles.receivedFill,
            shadow = bubbles.receivedShadow,
            shape = ReceivedBubbleShape,
            modifier = Modifier.align(Alignment.Start),
        )
        PreviewBubble(
            text = stringResource(ChatR.string.me_text_preview_sent),
            textColor = DifftTheme.colors.bubbleSentText,
            fill = Brush.horizontalGradient(bubbles.sent),
            shadow = null,
            shape = SentBubbleShape,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
private fun PreviewBubble(text: String, textColor: Color, fill: Brush, shadow: Color?, shape: Shape, modifier: Modifier) {
    Box(
        modifier = modifier
            .fractionOfParentWidth(BUBBLE_MAX_WIDTH_FRACTION)
            .drawBehind {
                if (shadow != null) {
                    translate(top = ReceivedShadowOffset.toPx()) {
                        drawOutline(shape.createOutline(size, layoutDirection, this), shadow)
                    }
                }
            }
            .clip(shape)
            .background(fill)
            .padding(BubblePadding),
    ) {
        Text(text = text, style = DifftTheme.typography.p2, color = textColor)
    }
}


private fun Modifier.fractionOfParentWidth(fraction: Float): Modifier = layout { measurable, constraints ->
    val cap = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).toInt() else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = cap))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}


@Composable
internal fun ScreenLayoutSection(
    displaySize: DisplaySize,
    onDisplaySizeChange: (DisplaySize) -> Unit,
    navigationPlacement: NavigationPlacement?,
    onNavigationPlacementChange: (NavigationPlacement) -> Unit,
    statusBarHidden: Boolean,
    onStatusBarHiddenChange: (Boolean) -> Unit,
) {
    AppearanceSection(
        title = stringResource(R.string.me_screen_layout),
        note = stringResource(R.string.me_screen_layout_note),
    ) {
        val sizes = DisplaySize.entries
        val sizeLabel = stringResource(R.string.me_display_size)
        AppearanceSettingRow(sizeLabel) {
            DifftSegmentedControl(
                options = sizes.map { stringResource(displaySizeLabelRes(it)) },
                selectedIndex = sizes.indexOf(displaySize),
                onSelect = { onDisplaySizeChange(sizes[it]) },
                groupLabel = sizeLabel,
            )
        }
        RowDivider()
        if (navigationPlacement != null) {
            val placements = NavigationPlacement.entries
            val placementLabel = stringResource(R.string.me_navigation_placement)
            AppearanceSettingRow(placementLabel) {
                DifftSegmentedControl(
                    options = placements.map { stringResource(navigationPlacementLabelRes(it)) },
                    selectedIndex = placements.indexOf(navigationPlacement),
                    onSelect = { onNavigationPlacementChange(placements[it]) },
                    groupLabel = placementLabel,
                )
            }
            RowDivider()
        }
        DifftSwitchRow(
            label = stringResource(R.string.me_hide_status_bar),
            checked = statusBarHidden,
            onCheckedChange = onStatusBarHiddenChange,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = RowSidePadding),
            minHeight = RowMinHeight,
        )
    }
}

private fun displaySizeLabelRes(size: DisplaySize): Int = when (size) {
    DisplaySize.SMALL -> R.string.me_display_size_small
    DisplaySize.STANDARD -> R.string.me_display_size_standard
    DisplaySize.LARGE -> R.string.me_display_size_large
}

private fun navigationPlacementLabelRes(placement: NavigationPlacement): Int = when (placement) {
    NavigationPlacement.DEFAULT -> R.string.me_navigation_placement_default
    NavigationPlacement.RIGHT -> R.string.me_navigation_placement_right
}
