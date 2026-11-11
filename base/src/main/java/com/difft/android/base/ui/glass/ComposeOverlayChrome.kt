package com.difft.android.base.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import com.difft.android.base.ui.theme.DifftTheme






@Composable
fun OverlayChrome(
    modifier: Modifier = Modifier.fillMaxSize(),
    insets: WindowInsets = WindowInsets.safeDrawing,
    glass: Boolean = false,
    top: @Composable BoxScope.() -> Unit = {},
    bottom: @Composable BoxScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    var backdrop by remember { mutableStateOf<GlassBackdrop?>(null) }
    val sourceBackground = DifftTheme.colors.background
    val inherited = LocalGlassBackdrop.current
    CompositionLocalProvider(LocalGlassBackdrop provides if (glass) backdrop else inherited) {
        SubcomposeLayout(modifier.windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal))) { constraints ->
            require(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
                "OverlayChrome needs a bounded page viewport"
            }
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val chromeConstraints = Constraints(minWidth = width, maxWidth = width, maxHeight = height)
            val topChrome = subcompose(ChromeSlot.Top) {
                Box(Modifier.fillMaxWidth().windowInsetsPadding(insets.only(WindowInsetsSides.Top)), content = top)
            }.single().measure(chromeConstraints)
            val bottomChrome = subcompose(ChromeSlot.Bottom) {
                Box(Modifier.fillMaxWidth().windowInsetsPadding(insets.only(WindowInsetsSides.Bottom)), content = bottom)
            }.single().measure(chromeConstraints)
            val padding = PaddingValues(top = topChrome.height.toDp(), bottom = bottomChrome.height.toDp())
            val page = subcompose(ChromeSlot.Content) {
                val contentModifier = Modifier.fillMaxSize().consumeWindowInsets(padding)
                if (glass) {
                    ComposeGlassSource(
                        modifier = contentModifier,
                        background = sourceBackground,
                        onBackdrop = { backdrop = it },
                    ) { content(padding) }
                } else {
                    Box(contentModifier) { content(padding) }
                }
            }.single().measure(Constraints.fixed(width, height))
            layout(width, height) {
                page.placeRelative(0, 0)
                topChrome.placeRelative(0, 0)
                bottomChrome.placeRelative(0, height - bottomChrome.height)
            }
        }
    }
}

private enum class ChromeSlot { Top, Bottom, Content }
