package com.difft.android.base

import android.content.Context
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

    override fun onCreate(savedInstanceState: Bundle?) {
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
        super.attachBaseContext(LanguageUtils.updateBaseContextLocale(context))
    }

    override fun onDestroy() {
        super.onDestroy()
        L.i { "[BaseActivity]${javaClass.name} Activity onDestroy" }
    }
}