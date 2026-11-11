package com.difft.android.base.ui.sidechrome

import com.difft.android.base.utils.cutoutRectsInWindow
import android.app.Activity
import android.content.pm.PackageManager
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.R
import com.difft.android.base.ui.glass.GlassBackdrop
import com.difft.android.base.ui.glass.installGlassBackdrop
import com.difft.android.base.ui.theme.DifftTheme
import kotlin.math.roundToInt







class SideChromeController(private val activity: Activity) {

    val registry = SideChromeRegistry((activity as? LifecycleOwner)?.lifecycle)

    




    var active by mutableStateOf(false)
        private set

    



    var shortWindow by mutableStateOf(false)
        private set

    



    var lower: (@Composable () -> Unit)? by mutableStateOf(null)
    var lowerVisible by mutableStateOf(true)

    
    private var imeVisible by mutableStateOf(false)

    



    var dualPaneMinWidthDp: () -> Float = { 0f }

    private val showing: Boolean get() = active && (registry.current != null || lower != null)

    





    val reservedPx: Int get() = if (showing) dp(SideChromeEligibility.BAR_RESERVE_DP) else 0

    private val buttonBounds = mutableMapOf<String, Rect>()

    



    fun buttonBoundsInWindow(id: String): android.graphics.Rect? {
        if (!showing) return null
        val bounds = buttonBounds[id]?.takeIf { it.width > 0f } ?: return null
        return android.graphics.Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
    }

    private var lastReason: SideChromeEligibility.Reason? = null
    private var installedFor: View? = null
    private var rail: ComposeView? = null
    private var rightInsetPx = 0
    private var lastInsets: WindowInsetsCompat? = null

    





    private var railCutoutTopPx by mutableStateOf(0)

    private fun updateRailCutoutTop(frame: View) {
        val insets = lastInsets ?: return
        val width = frame.width
        val height = frame.height
        if (width <= 0 || height <= 0) return
        val bandLeft = width - rightInsetPx - dp(SideChromeEligibility.BAR_RESERVE_DP)
        railCutoutTopPx = frame.cutoutRectsInWindow(insets)
            .filter { !it.isEmpty && it.right > bandLeft && it.top < height / 2 }
            .maxOfOrNull { it.bottom } ?: 0
    }

    private var layoutListenerAdded = false
    private var railBackdrop: GlassBackdrop? = null
    private val backgroundLocation = IntArray(2)
    private val railLocation = IntArray(2)

    init {
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                railBackdrop?.close()
                railBackdrop = null
                installedFor = null
            }
        })
    }

    
    fun install() {
        val frame = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val page = (0 until frame.childCount).map(frame::getChildAt).firstOrNull { it !== rail } ?: return
        if (installedFor === page) return
        installedFor = page
        val bar = rail ?: ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                
                if (showing) {
                    DifftTheme(applyWindowBackground = false) {
                        SideRail(
                            model = registry.current,
                            lower = lower.takeIf { lowerVisible && !imeVisible },
                            topClearancePx = railCutoutTopPx,
                            onButtonBounds = { key, bounds -> buttonBounds[key] = bounds },
                        )
                    }
                }
            }
        }.also { rail = it }
        if (bar.parent == null) {
            frame.addView(bar, FrameLayout.LayoutParams(dp(SideChromeEligibility.BAR_RESERVE_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT))
        }
        installBackdrop(page, bar)
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets -> dispatch(frame, page, bar, insets) }
        if (!layoutListenerAdded) {
            layoutListenerAdded = true
            frame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft) evaluate(frame.width)
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) updateRailCutoutTop(frame)
            }
            (activity as? LifecycleOwner)?.let { owner ->
                snapshotFlow { showing }.distinctUntilChanged().drop(1)
                    
                    .onEach { applyLayout(); frame.requestApplyInsets() }
                    .launchIn(owner.lifecycleScope)
            }
        }
        frame.requestApplyInsets()
    }

    
    private fun installBackdrop(page: View, bar: ComposeView) {
        railBackdrop?.close()
        railBackdrop = bar.installGlassBackdrop(content = page) { canvas ->
            
            canvas.drawColor(ContextCompat.getColor(activity, R.color.bg1))
            val decor = activity.window.decorView
            val background = decor.background ?: return@installGlassBackdrop
            decor.getLocationInWindow(backgroundLocation)
            bar.getLocationInWindow(railLocation)
            val save = canvas.save()
            canvas.translate((backgroundLocation[0] - railLocation[0]).toFloat(), (backgroundLocation[1] - railLocation[1]).toFloat())
            background.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    
    private fun dispatch(frame: FrameLayout, page: View, bar: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val sides = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        rightInsetPx = sides.right
        lastInsets = insets
        updateRailCutoutTop(frame)
        imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        evaluate(frame.width, sides.left + sides.right)
        applyLayout()
        ViewCompat.dispatchApplyWindowInsets(page, insets)
        ViewCompat.dispatchApplyWindowInsets(bar, insets)
        return WindowInsetsCompat.CONSUMED
    }

    private var horizontalInsetPx = 0

    
    private fun applyLayout() {
        val bar = rail ?: return
        bar.visibility = if (showing) View.VISIBLE else View.GONE
        bar.updateLayoutParams<FrameLayout.LayoutParams> { rightMargin = rightInsetPx }
    }

    private fun evaluate(frameWidthPx: Int, horizontalInsets: Int = horizontalInsetPx) {
        horizontalInsetPx = horizontalInsets
        if (frameWidthPx <= 0) return
        val config = activity.resources.configuration
        val density = activity.resources.displayMetrics.density
        val reason = SideChromeEligibility.evaluate(
            SideChromeEligibility.Inputs(
                hasHinge = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE),
                onDefaultDisplay = ContextCompat.getDisplayOrDefault(activity).displayId == Display.DEFAULT_DISPLAY,
                inMultiWindow = activity.isInMultiWindowMode,
                screenWidthDp = config.screenWidthDp,
                screenHeightDp = config.screenHeightDp,
                contentWidthDp = (frameWidthPx - horizontalInsets) / density,
                dualPaneMinWidthDp = dualPaneMinWidthDp(),
            )
        )
        shortWindow = reason == SideChromeEligibility.Reason.ELIGIBLE
        val wanted = shortWindow && SideChromeSettings.placement(activity) == NavigationPlacement.RIGHT
        if (reason != lastReason || wanted != active) {
            L.i { "[SideChrome] cls=${activity.javaClass.simpleName} active=$wanted reason=${reason.name.lowercase()}" }
            lastReason = reason
        }
        if (wanted != active) {
            active = wanted
            applyLayout()
        }
    }

    
    fun refresh() {
        activity.findViewById<View>(android.R.id.content)?.requestApplyInsets()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()
}
