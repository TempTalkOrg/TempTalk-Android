package com.difft.android.base.utils

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Edge-to-edge utilities for Android 15+ adaptation
 * Following Google's official recommendation for edge-to-edge implementation
 *
 * @see <a href="https://developer.android.com/develop/ui/views/layout/edge-to-edge">Edge-to-edge documentation</a>
 */
object EdgeToEdgeUtils {

    /**
     * Enable edge-to-edge display for the Activity.
     * Should be called before super.onCreate() in Activity.
     *
     * This will:
     * - Make status bar and navigation bar transparent
     * - Allow content to draw behind system bars
     * - Handle display cutouts appropriately
     */
    fun Activity.setupEdgeToEdge() {
        if (this is ComponentActivity) {
            enableEdgeToEdge()
        }
    }

    /**
     * Apply system bars padding to the root view.
     * Should be called after setContentView().
     *
     * @param rootView The root view to apply padding to
     * @param applyTop Whether to apply top (status bar) padding, default true
     * @param applyBottom Whether to apply bottom (navigation bar) padding, default true
     * @param applyHorizontal Whether to apply left/right padding for display cutouts, default false
     */
    fun applySystemBarsPadding(
        rootView: View,
        applyTop: Boolean = true,
        applyBottom: Boolean = true,
        applyHorizontal: Boolean = false
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = if (applyHorizontal) insets.left else 0,
                top = if (applyTop) insets.top else 0,
                right = if (applyHorizontal) insets.right else 0,
                bottom = if (applyBottom) insets.bottom else 0
            )
            // Return the insets so child views can also handle them if needed
            windowInsets
        }
        // Request insets to be applied
        rootView.requestApplyInsets()
    }

    /**
     * Apply system bars and IME (keyboard) padding to the root view.
     * Useful for screens with input fields that need to adjust for keyboard.
     *
     * @param rootView The root view to apply padding to
     * @param applyTop Whether to apply top (status bar) padding, default true
     */
    fun applySystemBarsAndImePadding(
        rootView: View,
        applyTop: Boolean = true
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                top = if (applyTop) systemBars.top else 0,
                bottom = maxOf(systemBars.bottom, ime.bottom)
            )
            windowInsets
        }
        rootView.requestApplyInsets()
    }

    /**
     * Get system bars insets (status bar + navigation bar heights)
     *
     * @param view Any view that's attached to the window
     * @return Pair of (statusBarHeight, navigationBarHeight), or (0, 0) if not available
     */
    fun getSystemBarsInsets(view: View): Pair<Int, Int> {
        val insets = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
        return if (insets != null) {
            Pair(insets.top, insets.bottom)
        } else {
            Pair(0, 0)
        }
    }

    /**
     * Clear any WindowInsets listener from the view.
     * Useful when a view needs custom insets handling.
     */
    fun clearWindowInsetsListener(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view, null)
    }
}