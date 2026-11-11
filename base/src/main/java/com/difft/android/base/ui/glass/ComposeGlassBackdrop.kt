package com.difft.android.base.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme


val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }






@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier.fillMaxSize(),
    background: Color = DifftTheme.colors.background,
    content: @Composable () -> Unit,
    chrome: @Composable BoxScope.() -> Unit,
) {
    var backdrop by remember { mutableStateOf<GlassBackdrop?>(null) }
    Box(modifier) {
        ComposeGlassSource(
            modifier = Modifier.fillMaxSize(),
            background = background,
            onBackdrop = { backdrop = it },
            content = content,
        )
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            chrome()
        }
    }
}


@Composable
internal fun ComposeGlassSource(
    modifier: Modifier,
    background: Color,
    onBackdrop: (GlassBackdrop) -> Unit,
    content: @Composable () -> Unit,
) {
    val parentComposition = rememberCompositionContext()
    val saveableState = rememberSaveableStateHolder()
    val currentContent = rememberUpdatedState(content)
    val currentOnBackdrop = rememberUpdatedState(onBackdrop)
    
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ComposeView(context).apply {
                setParentCompositionContext(parentComposition)
                setBackgroundColor(background.toArgb())
                setContent {
                    
                    saveableState.SaveableStateProvider("content") { currentContent.value() }
                }
                currentOnBackdrop.value(installGlassBackdrop())
            }
        },
        update = { view -> view.setBackgroundColor(background.toArgb()) },
        onRelease = { view ->
            (view.getTag(R.id.glass_backdrop) as? GlassBackdrop)?.close()
            view.disposeComposition()
        },
    )
}
