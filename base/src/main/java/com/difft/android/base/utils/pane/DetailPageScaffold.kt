package com.difft.android.base.utils.pane

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment

/**
 * True when this page owns its window: a shell or popup Activity. A page in the home screen's
 * detail stack shares IndexActivity's window, whose background the pane chrome owns, so it passes
 * `DifftTheme(applyWindowBackground = ownsWindow())`.
 */
fun Fragment.ownsWindow(): Boolean = !isInDetailStack()

/**
 * Whether the page's container pads the bottom system bar: a shell does (BaseActivity pads its
 * XML content); the home screen's panes and layers, one-pane or two-pane, reach the window
 * bottom (the background runs under the navigation bar) and leave it to the page.
 */
fun Fragment.containerPadsBottom(): Boolean = !isInDetailStack()

/**
 * Bottom padding a detail page adds itself, in pixels, given the window's navigation-bar and IME
 * bottoms: nothing for the bar where the container already pads it, and with [ime] the part of the
 * keyboard the container's padding does not cover.
 */
fun detailPageBottomInset(containerPadsBottom: Boolean, navigationBarBottom: Int, imeBottom: Int, ime: Boolean): Int {
    val needed = if (ime) maxOf(navigationBarBottom, imeBottom) else navigationBarBottom
    val padded = if (containerPadsBottom) navigationBarBottom else 0
    return (needed - padded).coerceAtLeast(0)
}

/**
 * XML page root: pads its bottom per [detailPageBottomInset] from the window insets, on top of the
 * padding the layout declares. Never pads the top or sides (the container does).
 */
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
}

/**
 * A page that sits on the settings background (bg) rather than the plain detail background
 * (bg1), like its shell Activity's window. The pane paints the matching colour behind it.
 */
interface SettingsIdiomPage
