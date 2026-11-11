



package com.difft.android.home

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.difft.android.base.ui.compose.floatingSurfaceShadow
import com.difft.android.base.ui.glass.GlassLensStyle
import com.difft.android.base.ui.glass.GlassTuning
import com.difft.android.base.ui.glass.liquidGlassSurface
import com.difft.android.base.ui.theme.DifftTheme


internal class HomeTabLens(private val row: GraphicsLayer, private val avatar: GraphicsLayer) {
    private var rowExtent = 0f
    private var width = 0f
    private var height = 0f
    private var slotExtent = 0f
    private var vertical = false
    private var rtl = false
    private var rowInset = 0f
    private val frame = HomeTabLensFrame()
    private val rowTransform = LensRowTransform(1f, 1f, 0f, 0f)

    fun measure(rowExtent: Float, width: Float, height: Float, slotExtent: Float, vertical: Boolean, rtl: Boolean, rowInset: Float) {
        this.rowExtent = rowExtent
        this.width = width
        this.height = height
        this.slotExtent = slotExtent
        this.vertical = vertical
        this.rtl = rtl
        this.rowInset = rowInset
    }

    fun applyFrame(index: Float, scaleX: Float, scaleY: Float) = frame.apply(index, scaleX, scaleY)

    fun Modifier.recordTabs(): Modifier = drawWithContent {
        row.record { this@drawWithContent.drawContent() }
    }

    fun Modifier.recordAvatar(): Modifier = drawWithContent {
        avatar.record { this@drawWithContent.drawContent() }
        drawLayer(avatar)
    }

    fun drawAvatar(scope: DrawScope) = with(scope) {
        drawLayer(avatar)
    }

    fun drawTabs(scope: DrawScope, index: Float, scaleX: Float, scaleY: Float) = with(scope) {
        val applied = frame.resolve(index, scaleX, scaleY)
        val offset = HomeTabPillMath.offset(applied.index, slotExtent, HomeTab.COUNT)
        val position = rowInset + HomeTabLensMath.axisPosition(offset, if (vertical) height else width,
            rowExtent, vertical, rtl)
        val x = HomeTabLensMath.physicalX(position, vertical)
        val y = HomeTabLensMath.physicalY(position, vertical)
        val transform = HomeTabLensMath.rowTransform(x, y, width, height, applied.scaleX, applied.scaleY, rowTransform)
        withTransform({
            translate(transform.translateX, transform.translateY)
            scale(transform.scaleX, transform.scaleY, pivot = Offset.Zero)
        }) {
            drawLayer(row)
        }
    }
}

@Composable
internal fun rememberHomeTabLens(): HomeTabLens {
    val row = rememberGraphicsLayer()
    val avatar = rememberGraphicsLayer()
    return remember(row, avatar) { HomeTabLens(row, avatar) }
}

@Composable
internal fun Modifier.homeTabLens(
    source: HomeTabLens,
    vertical: Boolean,
    scale: () -> PillScale,
    progress: () -> Float,
    index: () -> Float,
): Modifier {
    val colors = DifftTheme.colors
    val restDark = colors.isDark
    return liquidGlassSurface(
        shape = CircleShape,
        style = GlassLensStyle.Selection,
        progress = progress,
        content = {
            val deformation = scale()
            source.drawTabs(this, index(), HomeTabLensMath.physicalScaleX(deformation, vertical),
                HomeTabLensMath.physicalScaleY(deformation, vertical))
        },
        surface = {
            drawRect(homeTabRestTint(restDark, progress()))
            drawRect(Color.Black.copy(alpha = HomeTabLensMath.pressedTintAlpha(progress())))
        },
        highlight = progress,
        shadow = progress,
        innerShadow = progress,
    )
}

internal fun homeTabRestTint(dark: Boolean, progress: Float): Color =
    if (dark) Color.White.copy(alpha = HomeTabLensMath.restTintAlpha(progress))
    else Color.Black.copy(alpha = HomeTabLensMath.restTintAlphaLight(progress))

@Composable
internal fun Modifier.homeTabPanel(progress: () -> Float = { 1f }, highlight: () -> Float = { 1f }): Modifier {
    val colors = DifftTheme.colors
    val container = if (colors.isDark) colors.backgroundTertiary.copy(alpha = 0.4f) else colors.background.copy(alpha = GlassTuning.TINT_ALPHA)
    
    return floatingSurfaceShadow(CircleShape).liquidGlassSurface(
        shape = CircleShape,
        style = GlassLensStyle.Panel,
        progress = progress,
        surface = { drawRect(container) },
        highlight = highlight,
        shadow = { 0f },
    )
}
