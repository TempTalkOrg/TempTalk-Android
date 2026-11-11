package com.difft.android.base.ui.compose.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.floatingSurfaceShadow
import com.difft.android.base.ui.theme.DifftTheme

private val TrackPadding = 2.dp
private val SegmentGap = 2.dp
private val MinPillHeight = 28.dp
private val PillVerticalPadding = 5.dp
private val MinSegmentWidth = 64.dp
private val SegmentPadding = 14.dp
private val MinSegmentPadding = 4.dp
private val MinTouchHeight = 48.dp
private val LeadingGap = 4.dp
private val Pill = RoundedCornerShape(50)














@Composable
fun DifftSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    groupLabel: String,
    modifier: Modifier = Modifier,
    leading: (@Composable (index: Int, selected: Boolean) -> Unit)? = null,
) {
    val textStyle = DifftTheme.typography.footnote
    val track = DifftTheme.colors.fill
    val pillHeight = with(LocalDensity.current) { maxOf(MinPillHeight, textStyle.lineHeight.toDp() + PillVerticalPadding * 2) }
    Layout(
        modifier = modifier
            .selectableGroup()
            .semantics { contentDescription = groupLabel }
            .drawBehind {
                val bandHeight = (pillHeight + TrackPadding * 2).toPx()
                drawRoundRect(
                    color = track,
                    topLeft = Offset(0f, (size.height - bandHeight) / 2),
                    size = Size(size.width, bandHeight),
                    cornerRadius = CornerRadius(bandHeight / 2),
                )
            },
        content = {
            options.forEachIndexed { index, option ->
                Segment(
                    label = option,
                    selected = index == selectedIndex,
                    pillHeight = pillHeight,
                    onClick = { if (index != selectedIndex) onSelect(index) },
                    leading = leading?.let { { it(index, index == selectedIndex) } },
                )
            }
        },
    ) { measurables, constraints ->
        val count = measurables.size
        val fixed = (TrackPadding * 2 + SegmentGap * (count - 1).coerceAtLeast(0)).roundToPx()
        val height = maxOf(MinTouchHeight, pillHeight + TrackPadding * 2).roundToPx().coerceAtLeast(constraints.minHeight)
        
        val widest = measurables.maxOfOrNull { it.maxIntrinsicWidth(height) } ?: 0
        val natural = maxOf(MinSegmentWidth.roundToPx(), widest + (SegmentPadding - MinSegmentPadding).roundToPx() * 2)
        val offered = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val segment = if (count == 0) 0 else minOf(natural, ((offered - fixed) / count).coerceAtLeast(0))
        val width = if (count == 0) 0 else fixed + segment * count
        val placeables = measurables.map { it.measure(Constraints.fixed(segment, height)) }
        layout(width.coerceAtLeast(constraints.minWidth), height) {
            var x = TrackPadding.roundToPx()
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += segment + SegmentGap.roundToPx()
            }
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    pillHeight: Dp,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)?,
) {
    
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = null,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(pillHeight)
                .then(
                    if (selected) {
                        Modifier.floatingSurfaceShadow(Pill).clip(Pill).background(DifftTheme.colors.selected)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = MinSegmentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LeadingGap),
            ) {
                if (leading != null) Box(Modifier.clearAndSetSemantics { }) { leading() }
                Text(
                    text = label,
                    style = DifftTheme.typography.footnote,
                    color = if (selected) DifftTheme.colors.textPrimary else DifftTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
