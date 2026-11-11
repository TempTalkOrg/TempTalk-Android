package com.difft.android.base.ui.compose

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.difft.android.base.utils.safeTopInset
import com.difft.android.base.utils.cornerCutoutExtra
import kotlin.math.roundToInt






@Composable
fun Modifier.systemBarsWithCutoutPadding(): Modifier =
    windowInsetsPadding(
        WindowInsets.systemBars
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            .union(safeTopInsets()),
    )


@Composable
fun Modifier.statusBarWithCutoutPadding(): Modifier = windowInsetsPadding(safeTopInsets())





@Composable
fun safeTopInsets(): WindowInsets {
    val view = LocalView.current
    val density = LocalDensity.current
    
    WindowInsets.statusBars.getTop(density)
    WindowInsets.displayCutout.getTop(density)
    LocalConfiguration.current
    return WindowInsets(top = view.safeTopInset())
}





fun Modifier.cornerCutoutPadding(): Modifier = composed {
    val view = LocalView.current
    val density = LocalDensity.current
    
    WindowInsets.statusBars.getTop(density)
    WindowInsets.displayCutout.getTop(density)
    LocalConfiguration.current
    var extra by remember { mutableStateOf(0 to 0) }
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        extra = view.cornerCutoutExtra(
            bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt(),
        )
    }.absolutePadding(
        left = with(density) { extra.first.toDp() },
        right = with(density) { extra.second.toDp() },
    )
}
