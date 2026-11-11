package com.difft.android.base

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.window.layout.FoldingFeature
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.EdgeToEdgeUtils.applySystemBarsPadding
import com.difft.android.base.utils.EdgeToEdgeUtils.setupEdgeToEdge
import com.difft.android.base.utils.FoldDiagnostics
import com.difft.android.base.utils.FoldPostureWatcher
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.OrientationDisplayWatcher
import com.difft.android.base.utils.OrientationPolicy
import com.difft.android.base.utils.StatusBarSetting
import com.difft.android.base.utils.density.DisplayDensityNormalizer
import com.difft.android.base.utils.density.UiDensity
import com.difft.android.base.ui.sidechrome.SideChrome
import com.difft.android.base.ui.sidechrome.SideChromeController
import com.difft.android.base.ui.sidechrome.SideChromeHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
abstract class BaseActivity : AppCompatActivity(), FoldDiagnostics.Host, SideChromeHost {
    private val activityStartTimestamp: Long = System.currentTimeMillis()

    
    
    
    
    private var policyAppliedOrientation: Int? = null

    
    
    
    
    private val orientationDisplayWatcher = OrientationDisplayWatcher(this) { display ->
        reapplyOrientationPolicyIfOwned(display)
        onHostDisplayChanged(display)
        reconcileDensity(display)
    }

    
    
    
    private var densityManaged = false
    private val createdAtElapsed = SystemClock.elapsedRealtime()
    private var densityRecreateRequested = false
    private val mainHandler = Handler(Looper.getMainLooper())

    




    protected open fun participatesInSideChrome(): Boolean = false

    




    protected open fun padsRootForSideChrome(): Boolean = false

    final override val sideChrome: SideChromeController? by lazy {
        if (participatesInSideChrome()) SideChromeController(this) else null
    }

    
    var platformDensityDpi: Int = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        
        
        
        val applyPolicy = shouldApplyOrientationPolicy()
        if (applyPolicy) {
            policyAppliedOrientation = OrientationPolicy.applyTo(this)
        }
        
        
        val heldForUnfold = applyPolicy && OrientationPolicy.isUnfoldPending()
        
        if (shouldEnableEdgeToEdge()) {
            setupEdgeToEdge()
        }
        AppStartup.onCriticalRenderEventStart()
        super.onCreate(savedInstanceState)
        StatusBarSetting.attach(this, window)
        AppStartup.onCriticalRenderEventEnd()
        L.i { "[BaseActivity]${javaClass.name} Activity onCreate cost: ${System.currentTimeMillis() - activityStartTimestamp}" }
        if (densityManaged) DisplayDensityNormalizer.registerManaged(this)
        if (sideChrome != null) SideChrome.register(this)
        
        FoldDiagnostics.onConfigurationArrived(this, via = "create", restored = savedInstanceState != null)
        if (applyPolicy) {
            FoldPostureWatcher.attach(this, ::onFoldPosture)
            if (heldForUnfold) OrientationPolicy.scheduleUnfoldRelock(this) { reapplyOrientationPolicyIfOwned() }
        }
    }

    








    private fun onFoldPosture(state: FoldingFeature.State?) {
        val openedFromClosed = foldPostureSeen && lastFoldPosture == null && state != null
        foldPostureSeen = true
        lastFoldPosture = state
        if (!shouldApplyOrientationPolicy() || isInPictureInPictureMode) return
        val owned = policyAppliedOrientation?.let { requestedOrientation == it } == true
        if (!owned) return
        if (state == null) {
            OrientationPolicy.clearUnfoldPending()
            reapplyOrientationPolicyIfOwned()
        } else if (openedFromClosed) {
            OrientationPolicy.preReleaseForUnfold(this)?.let {
                policyAppliedOrientation = it
                OrientationPolicy.scheduleUnfoldRelock(this) { reapplyOrientationPolicyIfOwned() }
            }
        }
    }

    private var foldPostureSeen = false
    private var lastFoldPosture: FoldingFeature.State? = null

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

        
        
        val viewClassName = rootView.javaClass.name
        if (viewClassName.contains("Compose") || viewClassName.contains("compose")) {
            L.d { "[BaseActivity] Skipping auto padding for Compose view: $viewClassName" }
            return
        }

        applySystemBarsPadding(
            rootView = rootView,
            applyTop = shouldApplyStatusBarPadding(),
            applyBottom = shouldApplyNavigationBarPadding(),
            applyHorizontal = shouldApplyHorizontalPadding(),
            extraRight = { if (padsRootForSideChrome()) sideChrome?.reservedPx ?: 0 else 0 },
        )
        matchWindowBackgroundToRoot(rootView)
    }

    





    private fun matchWindowBackgroundToRoot(rootView: View) {
        val color = (rootView.background as? ColorDrawable)?.color ?: return
        if (Color.alpha(color) != OPAQUE_ALPHA || window.decorView.background !is ColorDrawable) return
        val translucent = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent)).run {
            try {
                getBoolean(0, false)
            } finally {
                recycle()
            }
        }
        if (!translucent) window.setBackgroundDrawable(ColorDrawable(color))
    }

    




    protected open fun shouldEnableEdgeToEdge(): Boolean = true

    




    protected open fun shouldApplySystemBarsPadding(): Boolean = true

    




    protected open fun shouldApplyStatusBarPadding(): Boolean = true

    




    protected open fun shouldApplyNavigationBarPadding(): Boolean = true

    




    protected open fun shouldApplyHorizontalPadding(): Boolean = true

    override fun onStart() {
        super.onStart()
        if (shouldApplyOrientationPolicy() || densityManaged) {
            orientationDisplayWatcher.start()
        }
    }

    override fun onStop() {
        orientationDisplayWatcher.stop()
        
        
        foldPostureSeen = false
        lastFoldPosture = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        StatusBarSetting.apply(this, window)
        
        
        if (ContextCompat.getDisplayOrDefault(this).displayId == Display.DEFAULT_DISPLAY) {
            UiDensity.update(resources.configuration.densityDpi)
        }
        L.i { "[BaseActivity]${javaClass.name} Activity onResume cost: ${System.currentTimeMillis() - activityStartTimestamp}" }
    }

    override fun attachBaseContext(context: Context) {
        val density = DisplayDensityNormalizer.attach(context, javaClass)
        densityManaged = density.managed
        platformDensityDpi = density.systemDensityDpi
        updateUiDensity(context, density)
        super.attachBaseContext(LanguageUtils.createConfiguredContext(context, density.overrideDpi))
    }

    
    private fun updateUiDensity(context: Context, density: DisplayDensityNormalizer.Attach) {
        if (ContextCompat.getDisplayOrDefault(context).displayId != Display.DEFAULT_DISPLAY) return
        val dpi = if (density.overrideDpi != Configuration.DENSITY_DPI_UNDEFINED) density.overrideDpi else density.systemDensityDpi
        UiDensity.update(dpi)
    }

    






    private fun reconcileDensity(display: Display) {
        if (!densityManaged) return
        mainHandler.post {
            
            
            if (isFinishing || isDestroyed || isChangingConfigurations || densityRecreateRequested) return@post
            if (DisplayDensityNormalizer.shouldRecreate(this, display.displayId, platformDensityDpi, createdAtElapsed)) {
                densityRecreateRequested = true
                recreate()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) StatusBarSetting.apply(this, window)
    }

    override fun onDestroy() {
        StatusBarSetting.detach(window)
        super.onDestroy()
        DisplayDensityNormalizer.unregisterManaged(this)
        SideChrome.unregister(this)
        L.i { "[BaseActivity]${javaClass.name} Activity onDestroy" }
    }

    













    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        FoldDiagnostics.onConfigurationArrived(this, via = "onConfigurationChanged")
        
        
        if (isInPictureInPictureMode) return
        reapplyOrientationPolicyIfOwned()
        reconcileDensity(ContextCompat.getDisplayOrDefault(this))
        sideChrome?.refresh()
    }

    override fun onContentChanged() {
        super.onContentChanged()
        sideChrome?.install()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        sideChrome?.refresh()
    }

    



    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode) {
            reapplyOrientationPolicyIfOwned()
        }
    }

    





    private fun reapplyOrientationPolicyIfOwned(display: Display? = null) {
        if (!shouldApplyOrientationPolicy()) return
        val owned = policyAppliedOrientation?.let { requestedOrientation == it } == true
        if (owned) {
            policyAppliedOrientation = OrientationPolicy.applyTo(this, display)
        }
    }

    










    protected open fun shouldApplyOrientationPolicy(): Boolean = true

    





    protected open fun onHostDisplayChanged(display: Display) = Unit

    
    override fun foldDiagExtras(): String {
        val owned = policyAppliedOrientation?.let { requestedOrientation == it } == true
        return "owned=$owned" + extraFoldDiag()
    }

    
    protected open fun extraFoldDiag(): String = ""
}

private const val OPAQUE_ALPHA = 0xFF
