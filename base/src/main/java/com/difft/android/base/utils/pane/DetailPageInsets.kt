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
import com.difft.android.base.ui.sidechrome.sideChromePadding








fun Modifier.consumeContainerInsets(containerPadsBottom: Boolean): Modifier = composed {
    val padded = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    consumeWindowInsets(
        if (containerPadsBottom) padded else padded.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ).sideChromePadding()
}






fun Modifier.detailPageInsets(containerPadsBottom: Boolean, ime: Boolean = false): Modifier = composed {
    val bottom = if (ime) WindowInsets.navigationBars.union(WindowInsets.ime) else WindowInsets.navigationBars
    consumeContainerInsets(containerPadsBottom).windowInsetsPadding(bottom.only(WindowInsetsSides.Bottom))
}
