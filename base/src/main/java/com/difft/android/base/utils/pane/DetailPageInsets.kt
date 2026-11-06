package com.difft.android.base.utils.pane

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Compose page root under a container that already padded the system bars (shell) or all but
 * the bottom (a home pane or layer, [containerPadsBottom] false): marks those insets consumed,
 * so descendant `navigationBarsPadding()` / `imePadding()` / Scaffold insets add only what the
 * container did not pad. Pages never pad `systemBars` / `statusBars` themselves.
 */
fun Modifier.consumeContainerInsets(containerPadsBottom: Boolean): Modifier = composed {
    val padded = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    consumeWindowInsets(
        if (containerPadsBottom) padded else padded.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    )
}

/**
 * [consumeContainerInsets], then pads the bottom with what is left of the navigation bar (and,
 * with [ime], the keyboard): nothing (or the keyboard beyond the bar) where the container pads the
 * bottom, the bar (or the larger of bar and keyboard) in the one-pane layer.
 */
fun Modifier.detailPageInsets(containerPadsBottom: Boolean, ime: Boolean = false): Modifier = composed {
    val bottom = if (ime) WindowInsets.navigationBars.union(WindowInsets.ime) else WindowInsets.navigationBars
    consumeContainerInsets(containerPadsBottom).windowInsetsPadding(bottom.only(WindowInsetsSides.Bottom))
}
