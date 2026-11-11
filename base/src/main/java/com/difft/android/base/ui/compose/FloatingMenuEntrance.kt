package com.difft.android.base.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import com.difft.android.base.widget.FloatingMenuMotion






@Composable
fun Modifier.floatingMenuEntrance(origin: TransformOrigin): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(FloatingMenuMotion.SHOW_MS.toInt(), easing = CubicBezierEasing(0.3f, 1.3f, 0.5f, 1f)))
    }
    return graphicsLayer {
        val p = progress.value
        val scale = lerp(FloatingMenuMotion.COLLAPSED_SCALE, 1f, p)
        scaleX = scale
        scaleY = scale
        alpha = p.coerceIn(0f, 1f)
        transformOrigin = origin
    }
}
