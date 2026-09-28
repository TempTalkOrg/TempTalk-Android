package com.difft.android.base

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.Display
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.EdgeToEdgeUtils.applySystemBarsPadding
import com.difft.android.base.utils.EdgeToEdgeUtils.setupEdgeToEdge
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.OrientationDisplayWatcher
import com.difft.android.base.utils.OrientationPolicy
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

    // The orientation value THIS policy last wrote, or null when it never applied. Later
    // re-applies happen only while requestedOrientation still equals it — a screen that set
    // its own orientation (media picker unlock, screen-share landscape lock) is never
    // clobbered by the fold/unfold re-apply.
    private var policyAppliedOrientation: Int? = null

    // Display-level replay trigger for the orientation policy: the one callback that still
    // fires when the OS letterboxes this activity instead of relaunching it or delivering
    // onConfigurationChanged. Filtering (alive, not PiP, host display only, display on) lives
    // in the watcher so the call activity, which is not a BaseActivity, applies the same rules.
    private val orientationDisplayWatcher = OrientationDisplayWatcher(this) { display ->
        reapplyOrientationPolicyIfOwned(display)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Pre-super: setRequestedOrientation post-super on API >= 26 can throw for
        // translucent activities. Safe here — OrientationPolicy reads only display metrics.
        if (shouldApplyOrientationPolicy()) {
            policyAppliedOrientation = OrientationPolicy.applyTo(this)
        }
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

    override fun onStart() {
        super.onStart()
        if (shouldApplyOrientationPolicy()) {
            orientationDisplayWatcher.start()
        }
    }

    override fun onStop() {
        orientationDisplayWatcher.stop()
        super.onStop()
    }

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
     * Re-apply the orientation policy when the configuration changes.
     *
     * Most activities declare enough `configChanges` keys that a fold/unfold does NOT recreate
     * them, so without this they keep whatever the FOLDED posture decided — on a book foldable
     * whose folded display is < 600dp that leaves every already-open screen portrait-locked
     * after unfolding.
     *
     * [OrientationPolicy.applyTo] is idempotent (it early-returns when the target already
     * matches) and reads the DISPLAY, not `newConfig`: a configuration change that only reshapes
     * our window (letterbox, split-screen, freeform resize) leaves the target unchanged, so this
     * is silent for everything but a real display change. [orientationDisplayWatcher] covers the
     * display changes the window manager never reports to this activity at all.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A pinned (PiP) window reports phone-bucket sizes; requesting portrait there would
        // only queue churn for PiP exit, which delivers its own mode-change callback below.
        if (isInPictureInPictureMode) return
        reapplyOrientationPolicyIfOwned()
    }

    /**
     * Backstop for the PiP skip above: the exit-PiP configuration change can race the
     * pinned-mode flag, so re-apply once the mode change itself reports un-pinned.
     */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode) {
            reapplyOrientationPolicyIfOwned()
        }
    }

    /**
     * Re-apply the orientation policy ONLY while it still owns the value: if any screen set
     * its own requestedOrientation since (the picture selector's one-time unlock, a
     * screen-share landscape lock), the policy backs off until that screen restores a
     * policy-written value.
     */
    private fun reapplyOrientationPolicyIfOwned(display: Display? = null) {
        if (!shouldApplyOrientationPolicy()) return
        val owned = policyAppliedOrientation?.let { requestedOrientation == it } == true
        if (owned) {
            policyAppliedOrientation = OrientationPolicy.applyTo(this, display)
        }
    }

    /**
     * Override to false to opt out — for Activities that must stay portrait
     * on all sizes, or manage orientation themselves (camera, PiP, etc.).
     * When false, BaseActivity leaves `requestedOrientation` untouched.
     *
     * Note: invoked before `super.onCreate()` (and again from [onStart], [onConfigurationChanged],
     * [onPictureInPictureModeChanged] and the display watcher's replay) — overrides must return a
     * constant. Hilt-injected fields and `savedInstanceState` are not yet available at the
     * pre-super call.
     */
    protected open fun shouldApplyOrientationPolicy(): Boolean = true
}
