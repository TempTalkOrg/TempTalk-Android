package com.difft.android.chat.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.R


/**
 * Vertical bars same UI with telegram chat pinned messages preview on the top, that show the current pinned message sequence.
 */
@Composable
fun BarIndicator(
    barCount: Int,
    currentBarIndex: Int,
    activeColor: Color = colorResource(id = R.color.t_info),
    inactiveColor: Color = colorResource(id = R.color.t_disable),
    barWidth: Dp = 2.dp,
    barSpaceRatio: Float = 0.2f, // ratio of bar height
    minBarHeight: Dp = 8.dp // minimum bar height
) {
    val scrollState = rememberLazyListState()
    val density = LocalDensity.current

    // Calculate the height of each bar with spacing based on the minimum bar height
    val minHeightWithSpacingPx = with(density) { minBarHeight.toPx() } * (1 + barSpaceRatio)

    // Use BoxWithConstraints to get the maximum available height
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxHeight() // Fill the parent's height
            .width(barWidth)
    ) {
        val maxHeightPx = constraints.maxHeight.toFloat()
        val totalMinHeightPx = minHeightWithSpacingPx * barCount
        val isScrollable = totalMinHeightPx > maxHeightPx

        // Calculate the actual height of each bar based on the available space
        val barHeightPx = if (isScrollable) {
            // If scrollable, use the minimum bar height
            with(density) { minBarHeight.toPx() }
        } else {
            // If not scrollable, divide the available space by the number of bars
            (maxHeightPx - (barCount - 1) * with(density) { minBarHeight.toPx() } * barSpaceRatio) / barCount
        }

        LaunchedEffect(currentBarIndex) {
            if (isScrollable) {
                // Calculate the center position of the viewport
                val viewportCenter = maxHeightPx / 2

                // Animate scrolling to bring the selected bar to the center
                scrollState.animateScrollToItem(currentBarIndex, scrollOffset = -viewportCenter.toInt())
            }
        }

        if (isScrollable) {
            LazyColumn(state = scrollState) {
                items(barCount) { index ->
                    Bar(
                        index = index,
                        currentBarIndex = currentBarIndex,
                        activeColor = activeColor,
                        inactiveColor = inactiveColor,
                        barWidth = barWidth,
                        barHeightPx = barHeightPx,
                        barSpacePx = with(density) { minBarHeight.toPx() } * barSpaceRatio
                    )
                }
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val barWidthPx = with(density) { barWidth.toPx() }
                val barSpacePx = barHeightPx * barSpaceRatio
                for (i in 0 until barCount) {
                    val color = if (i == currentBarIndex) activeColor else inactiveColor
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(
                            x = (size.width - barWidthPx) / 2,
                            y = i * (barHeightPx + barSpacePx)
                        ),
                        size = Size(barWidthPx, barHeightPx),
                        cornerRadius = CornerRadius(
                            with(density) { 1.dp.toPx() },
                            with(density) { 1.dp.toPx() }
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun Bar(
    index: Int,
    currentBarIndex: Int,
    activeColor: Color,
    inactiveColor: Color,
    barWidth: Dp,
    barHeightPx: Float,
    barSpacePx: Float
) {
    val color = if (index == currentBarIndex) activeColor else inactiveColor
    Column {
        Spacer(modifier = Modifier.height(with(LocalDensity.current) { barSpacePx.toDp() / 2 }))
        Box(
            modifier = Modifier
                .height(with(LocalDensity.current) { barHeightPx.toDp() })
                .width(barWidth)
                .background(color)
        )
        Spacer(modifier = Modifier.height(with(LocalDensity.current) { barSpacePx.toDp() / 2 }))
    }
}
@Composable
@Preview
fun BarIndicatorPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .height(50.dp)
    ) {
        BarIndicator(10, 3)
    }
}

//Preview Bar
@Composable
@Preview
fun BarPreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .height(50.dp)
            .background(Color.Gray)
    ) {
        Bar(3, 3, Color.Red, Color.Gray, 2.dp, 20f, 10f)
        Bar(3, 3, Color.Red, Color.Gray, 2.dp, 20f, 10f)
    }
}