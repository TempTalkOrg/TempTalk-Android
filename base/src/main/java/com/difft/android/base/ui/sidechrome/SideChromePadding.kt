package com.difft.android.base.ui.sidechrome

import android.view.View
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach





fun Modifier.sideChromePadding(): Modifier = composed {
    val reserved = currentSideChrome()?.reservedPx ?: 0
    absolutePadding(right = with(LocalDensity.current) { reserved.toDp() })
}







fun Fragment.padForSideChrome(view: View) {
    if (keepsOwnChrome()) return
    val controller = activity?.let(SideChrome::of) ?: return
    val base = view.paddingRight
    snapshotFlow { controller.reservedPx }
        .onEach { reserved -> view.updatePadding(right = base + reserved) }
        .launchIn(viewLifecycleOwner.lifecycleScope)
}







fun Fragment.onSideChromeActive(listener: (active: Boolean) -> Unit) {
    val controller = activity?.takeUnless { keepsOwnChrome() }?.let(SideChrome::of)
    if (controller == null) {
        listener(false)
        return
    }
    snapshotFlow { controller.active }
        .onEach(listener)
        .launchIn(viewLifecycleOwner.lifecycleScope)
}
