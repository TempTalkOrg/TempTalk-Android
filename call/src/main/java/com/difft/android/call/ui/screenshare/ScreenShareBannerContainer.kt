package com.difft.android.call.ui.screenshare

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.sqrt

@Composable
internal fun BannerContainer(
    modifier: Modifier,
    onClick: () -> Unit,
    dragDeltaX: Float,
    dragDeltaY: Float,
    parentMaxWPx: Float,
    parentMaxHPx: Float,
    marginEndPx: Float,
    marginTopPx: Float,
    bannerWidthPx: Float,
    onDragDeltaChange: (Float, Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val latestDragDX by rememberUpdatedState(dragDeltaX)
    val latestDragDY by rememberUpdatedState(dragDeltaY)
    val latestParentMaxWPx by rememberUpdatedState(parentMaxWPx)
    val latestParentMaxHPx by rememberUpdatedState(parentMaxHPx)
    val latestMarginEndPx by rememberUpdatedState(marginEndPx)
    val latestMarginTopPx by rememberUpdatedState(marginTopPx)
    val latestBannerWidthPx by rememberUpdatedState(bannerWidthPx)
    val latestOnDragDeltaChange by rememberUpdatedState(onDragDeltaChange)
    val latestOnClick by rememberUpdatedState(onClick)

    var bannerHeightPx by remember { mutableIntStateOf(0) }

    fun clampDragDelta(rawDX: Float, rawDY: Float): Pair<Float, Float> {
        val baseX = latestParentMaxWPx - latestBannerWidthPx
        val minDX = -(baseX - latestMarginEndPx).coerceAtLeast(0f)
        val maxDX = latestMarginEndPx
        val minDY = -latestMarginTopPx
        val maxDY = (latestParentMaxHPx - bannerHeightPx - latestMarginTopPx)
            .coerceAtLeast(0f)
        return rawDX.coerceIn(minDX, maxDX) to rawDY.coerceIn(minDY, maxDY)
    }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                bannerHeightPx = size.height
                val (clampedDX, clampedDY) = clampDragDelta(latestDragDX, latestDragDY)
                if (clampedDX != latestDragDX || clampedDY != latestDragDY) {
                    latestOnDragDeltaChange(clampedDX, clampedDY)
                }
            }
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis

                awaitPointerEventScope {
                    while (true) {
                        val downEvent = awaitPointerEvent(PointerEventPass.Initial)
                        val down = downEvent.changes.firstOrNull() ?: continue
                        if (!down.pressed) continue
                        down.consume()

                        var dragMode = false
                        var cumulativeX = 0f
                        var cumulativeY = 0f
                        val downTime = down.uptimeMillis

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull() ?: break

                            if (!change.pressed) {
                                if (!dragMode) {
                                    val elapsed = change.uptimeMillis - downTime
                                    if (elapsed < longPressTimeout) {
                                        latestOnClick()
                                    }
                                }
                                change.consume()
                                break
                            }

                            val delta = change.position - change.previousPosition
                            cumulativeX += delta.x
                            cumulativeY += delta.y

                            if (!dragMode) {
                                val dist = sqrt(
                                    cumulativeX * cumulativeX + cumulativeY * cumulativeY,
                                )
                                if (dist > touchSlop) {
                                    dragMode = true
                                    val (cx, cy) = clampDragDelta(
                                        latestDragDX + cumulativeX,
                                        latestDragDY + cumulativeY,
                                    )
                                    latestOnDragDeltaChange(cx, cy)
                                    cumulativeX = 0f
                                    cumulativeY = 0f
                                }
                            } else {
                                change.consume()
                                val (cx, cy) = clampDragDelta(
                                    latestDragDX + delta.x,
                                    latestDragDY + delta.y,
                                )
                                latestOnDragDeltaChange(cx, cy)
                            }
                        }
                    }
                }
            },
    ) {
        content()
    }
}
