package com.difft.android.base.utils.pane

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.difft.android.base.ui.sidechrome.padForSideChrome






fun Fragment.ownsWindow(): Boolean = !isInDetailStack()






fun Fragment.containerPadsBottom(): Boolean = !isInDetailStack()






fun detailPageBottomInset(containerPadsBottom: Boolean, navigationBarBottom: Int, imeBottom: Int, ime: Boolean): Int {
    val needed = if (ime) maxOf(navigationBarBottom, imeBottom) else navigationBarBottom
    val padded = if (containerPadsBottom) navigationBarBottom else 0
    return (needed - padded).coerceAtLeast(0)
}






fun Fragment.applyDetailPageInsets(root: View, ime: Boolean = false) {
    val basePadding = root.paddingBottom
    val padsBottom = containerPadsBottom()
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bottom = detailPageBottomInset(
            containerPadsBottom = padsBottom,
            navigationBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            ime = ime,
        )
        view.updatePadding(bottom = basePadding + bottom)
        insets
    }
    ViewCompat.requestApplyInsets(root)
    padForSideChrome(root)
}





interface SettingsIdiomPage
