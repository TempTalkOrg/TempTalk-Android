package com.difft.android.base

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.EdgeToEdgeUtils.applySystemBarsPadding
import com.difft.android.base.utils.EdgeToEdgeUtils.setupEdgeToEdge
import com.difft.android.base.utils.LanguageUtils
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
abstract class BaseActivity : AppCompatActivity() {
    private val activityStartTimestamp: Long = System.currentTimeMillis()

    // Time when this window last lost focus (0 if currently focused).
    // Used by screenshot detector to skip screenshots taken in notification panel.
    var windowFocusLostAt: Long = 0L
        private set

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocusLostAt = if (hasFocus) 0L else System.currentTimeMillis()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyOrientationPolicy()
        // Enable edge-to-edge before super.onCreate()
        if (shouldEnableEdgeToEdge()) {
            setupEdgeToEdge()
        }
        AppStartup.onCriticalRenderEventStart()
        super.onCreate(savedInstanceState)
        AppStartup.onCriticalRenderEventEnd()
        L.i { "[BaseActivity]${javaClass.name} Activity onCreate cost: ${System.currentTimeMillis() - activityStartTimestamp}" }
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyEdgeToEdgePaddingIfNeeded()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        applyEdgeToEdgePaddingIfNeeded()
    }

    private fun applyEdgeToEdgePaddingIfNeeded() {
        if (!shouldEnableEdgeToEdge() || !shouldApplySystemBarsPadding()) {
            return
        }
        val rootView = findViewById<View>(android.R.id.content)?.let {
            (it as? android.view.ViewGroup)?.getChildAt(0)
        } ?: return

        // Skip auto padding for Compose views - Compose handles insets via Modifier.systemBarsPadding()
        // Check for both ComposeView and any compose-related view classes
        val viewClassName = rootView.javaClass.name
        if (viewClassName.contains("Compose") || viewClassName.contains("compose")) {
            L.d { "[BaseActivity] Skipping auto padding for Compose view: $viewClassName" }
            return
        }

        applySystemBarsPadding(
            rootView = rootView,
            applyTop = shouldApplyStatusBarPadding(),
            applyBottom = shouldApplyNavigationBarPadding(),
            applyHorizontal = shouldApplyHorizontalPadding()
        )
    }

    /**
     * Whether to enable edge-to-edge for this Activity.
     * Override to return false for Activities that handle edge-to-edge themselves.
     * Default: true
     */
    protected open fun shouldEnableEdgeToEdge(): Boolean = true

    /**
     * Whether to automatically apply system bars padding to the root view.
     * Override to return false for Activities that handle insets themselves.
     * Default: true
     */
    protected open fun shouldApplySystemBarsPadding(): Boolean = true

    /**
     * Whether to apply status bar (top) padding.
     * Override to return false for fullscreen or immersive Activities.
     * Default: true
     */
    protected open fun shouldApplyStatusBarPadding(): Boolean = true

    /**
     * Whether to apply navigation bar (bottom) padding.
     * Override to return false for Activities with custom bottom handling (e.g., BottomSheet).
     * Default: true
     */
    protected open fun shouldApplyNavigationBarPadding(): Boolean = true

    /**
     * Whether to apply horizontal (left/right) padding for system bars and display cutouts.
     * In landscape mode, navigation bar is on the side so horizontal padding is needed.
     * Default: true
     */
    protected open fun shouldApplyHorizontalPadding(): Boolean = true

    override fun onResume() {
        super.onResume()
        L.i { "[BaseActivity]${javaClass.name} Activity onResume cost: ${System.currentTimeMillis() - activityStartTimestamp}" }
    }

    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(LanguageUtils.createConfiguredContext(context))
    }

    override fun onDestroy() {
        super.onDestroy()
        L.i { "[BaseActivity]${javaClass.name} Activity onDestroy" }
    }

    /**
     * Single source of truth for screen orientation across all activities:
     *   - Phones (sw < 600dp): lock SCREEN_ORIENTATION_PORTRAIT.
     *   - Tablets / unfolded foldables (sw ≥ 600dp): SCREEN_ORIENTATION_UNSPECIFIED
     *     so the user can rotate freely (drives the dual-pane layout on rotation).
     *
     * Done at runtime instead of `android:screenOrientation` in the manifest so
     * that large-screen devices which don't honor AOSP 16+'s sw≥600dp exemption
     * (HarmonyOS NEXT on Huawei foldables, older AOSP, some OEM ROMs) still rotate.
     * Tradeoff: with no manifest orientation the splash window follows the device,
     * so a phone launched while held landscape briefly rotates to portrait — rare
     * (auto-rotate is off by default and launchers stay portrait), and worth it to
     * keep foldables flicker-free in their common unfolded-landscape posture.
     */
    private fun applyOrientationPolicy() {
        if (!shouldApplyOrientationPolicy()) return
        val target = if (resources.getBoolean(R.bool.force_portrait_orientation)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation == target) return
        try {
            requestedOrientation = target
            L.i { "[BaseActivity] orient set cls=${javaClass.simpleName} swDp=${resources.configuration.smallestScreenWidthDp} target=$target" }
        } catch (e: IllegalStateException) {
            // API 26 (Android 8.0) throws "Only fullscreen activities can request
            // orientation" for translucent activities. Harmless — they inherit the
            // underlying activity's orientation. Fixed by AOSP in API 27.
            L.w { "[BaseActivity] orient set skipped cls=${javaClass.simpleName}: ${e.message}" }
        }
    }

    /**
     * Override to false to opt out — for Activities that must stay portrait
     * on all sizes, or manage orientation themselves (camera, PiP, etc.).
     * When false, BaseActivity leaves `requestedOrientation` untouched.
     *
     * Note: invoked before `super.onCreate()` — overrides must return a constant.
     * Hilt-injected fields and `savedInstanceState` are not yet available here.
     */
    protected open fun shouldApplyOrientationPolicy(): Boolean = true
}
