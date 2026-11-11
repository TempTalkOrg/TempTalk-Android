



package com.difft.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.glass.GlassMode
import com.difft.android.base.ui.theme.DifftTheme


@Composable
internal fun HomeTabSelectionPill(
    motion: PillMotion,
    vertical: Boolean,
    modifier: Modifier,
    pressProgress: () -> Float,
    content: @Composable (selectedCopy: Boolean, lens: HomeTabLens?) -> Unit,
) {
    val reverse = !vertical && LocalLayoutDirection.current == LayoutDirection.Rtl
    val lensEnabled = GlassMode.isLiquid(LocalContext.current)
    val lens = if (lensEnabled) rememberHomeTabLens() else null
    val deformation = remember { PillScale(1f, 1f) }
    PillLayer(
        modifier.then(pillGestures(motion, vertical, reverse)),
        vertical,
        motion,
        lens = lens,
        scale = { if (lensEnabled) HomeTabLensMath.scale(motion.velocity, motion.scaleAxis, motion.scaleCross, deformation)
            else HomeTabPillMath.scale(motion.velocity, pressProgress(), deformation) },
        progress = pressProgress,
        content = content,
    )
}

@Composable
internal fun rememberPillMotion(selected: Int, onSelect: (Int) -> Unit): PillMotion {
    val scope = rememberCoroutineScope()
    val currentSelected = rememberUpdatedState(selected)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val motion = remember(scope) { PillMotion(selected, scope, currentSelected, currentOnSelect) }
    LaunchedEffect(selected) {
        if (!motion.dragging) motion.animateSelection(selected)
    }
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    return motion
}


private fun pillGestures(motion: PillMotion, vertical: Boolean, reverse: Boolean): Modifier = Modifier
    .pointerInput(motion) {
        
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            motion.pointerDown()
            try {
                waitForUpOrCancellation()
            } finally {
                motion.pointerUp()
            }
        }
    }
    .pointerInput(motion, vertical, reverse) {
        var activeDrag = false
        fun axis(offset: Offset) = if (vertical) offset.y else offset.x
        try {
            detectDragGestures(
                orientationLock = if (vertical) Orientation.Vertical else Orientation.Horizontal,
                onDragStart = { _, _, _ ->
                    activeDrag = true
                    motion.startDrag()
                },
                onDrag = { change, amount ->
                    change.consume()
                    motion.drag(axis(amount), reverse)
                },
                onDragEnd = {
                    activeDrag = false
                    motion.release()
                },
                onDragCancel = {
                    activeDrag = false
                    motion.cancelDrag()
                },
            )
        } finally {
            if (activeDrag) motion.cancelDrag()
        }
    }

@Composable
private fun PillLayer(
    modifier: Modifier,
    vertical: Boolean,
    motion: PillMotion,
    lens: HomeTabLens?,
    scale: () -> PillScale,
    progress: () -> Float,
    content: @Composable (selectedCopy: Boolean, lens: HomeTabLens?) -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val colors = DifftTheme.colors
    val fill = homeTabRestTint(colors.isDark, 0f)
    Layout(
        modifier = modifier,
        content = {
            Box(if (lens == null) Modifier else Modifier.graphicsLayer {
                val panelScale = HomeTabPanelMath.scale(progress(), maxOf(size.width, size.height) + 8.dp.toPx(), 16.dp.toPx())
                scaleX = panelScale
                scaleY = panelScale
            }) { content(false, lens) }
            if (lens != null) {
                
                Box(with(lens) { Modifier.clearAndSetSemantics {}.recordTabs() }
                    .homeTabPanel(progress, highlight = progress)
                    .padding(if (vertical) androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
                        else androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp))) { content(true, lens) }
            }
            val surface = if (lens != null) Modifier.homeTabLens(lens, vertical, scale,
                progress = { HomeTabLensMath.progress(progress()) }, index = { motion.index })
                else Modifier.background(fill, CircleShape)
            Box(
                Modifier
                    .graphicsLayer {
                        val index = motion.index
                        val offset = HomeTabPillMath.offset(index, motion.slotExtent, HomeTab.COUNT)
                        val position = HomeTabLensMath.axisPosition(offset, if (vertical) size.height else size.width,
                            motion.stripExtent, vertical, rtl)
                        translationX = HomeTabLensMath.physicalX(position, vertical)
                        translationY = HomeTabLensMath.physicalY(position, vertical)
                        val deformation = scale()
                        scaleX = HomeTabLensMath.physicalScaleX(deformation, vertical)
                        scaleY = HomeTabLensMath.physicalScaleY(deformation, vertical)
                        lens?.applyFrame(index, scaleX, scaleY)
                    }
                    .then(surface)
                    .testTag(TAG_SELECTION_PILL),
            )
        },
    ) { measurables, constraints ->
        val strip = measurables[0].measure(constraints)
        val width = if (vertical) strip.width else strip.width / HomeTab.COUNT
        val height = if (vertical) strip.height / HomeTab.COUNT else strip.height
        motion.stripExtent = (if (vertical) strip.height else strip.width).toFloat()
        motion.slotExtent = motion.stripExtent / HomeTab.COUNT
        val inset = 4.dp.roundToPx()
        val accent = if (lens != null) measurables[1].measure(Constraints.fixed(
            strip.width + if (vertical) 0 else inset * 2,
            strip.height + if (vertical) inset * 2 else 0,
        )) else null
        val pill = measurables[if (lens != null) 2 else 1].measure(Constraints.fixed(width, height))
        lens?.measure(motion.stripExtent, width.toFloat(), height.toFloat(), motion.slotExtent, vertical,
            layoutDirection == LayoutDirection.Rtl, inset.toFloat())
        layout(strip.width, strip.height) {
            if (lens != null) strip.placeRelative(0, 0)
            accent?.placeRelative(if (vertical) 0 else -inset, if (vertical) -inset else 0)
            pill.place(0, 0)
            if (lens == null) strip.placeRelative(0, 0)
        }
    }
}

internal const val TAG_SELECTION_PILL = "home_tab_selection_pill"
