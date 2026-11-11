package com.difft.android.base.ui.glass

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.R
import com.difft.android.base.ui.topchrome.TopChromeGlassDrawable
import com.difft.android.base.ui.topchrome.TopChromeStyle
import com.difft.android.base.ui.topchrome.topChromeInset
import com.google.android.material.appbar.AppBarLayout
import kotlin.math.ceil
import kotlinx.coroutines.flow.StateFlow














fun installAppBarOverlayChrome(
    owner: LifecycleOwner,
    viewport: CoordinatorLayout,
    appBar: AppBarLayout,
    body: View,
    rows: ViewGroup?,
    covered: StateFlow<Boolean>? = null,
    windowLifecycle: Lifecycle? = null,
    installSource: Boolean = rows != null,
    onContentTop: ((Int) -> Unit)? = null,
    style: TopChromeStyle = TopChromeStyle.Band,
    @ColorRes tintRes: Int = R.color.bg1,
) {
    if (owner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
        AppBarOverlayChrome(viewport, appBar, body, rows, covered, windowLifecycle, installSource, onContentTop, style, tintRes).start(owner)
    }
}

private class AppBarOverlayChrome(
    private val viewport: CoordinatorLayout,
    private val appBar: AppBarLayout,
    private val body: View,
    private val rows: ViewGroup?,
    private val covered: StateFlow<Boolean>?,
    private val windowLifecycle: Lifecycle?,
    private val installSource: Boolean,
    private val onContentTop: ((Int) -> Unit)?,
    private val style: TopChromeStyle,
    @ColorRes tintRes: Int,
) {
    private val params = body.layoutParams as CoordinatorLayout.LayoutParams
    private val previousBehavior = params.behavior
    private val previousBottom = params.bottomMargin
    private val previousTranslation = body.translationY
    private val previousTopPadding = rows?.paddingTop ?: 0
    private val previousClip = rows?.clipToPadding ?: true
    private val previousAppBarIndex = viewport.indexOfChild(appBar)
    private val previousBarClip = appBar.clipBounds
    private val previousBarPaddingTop = appBar.paddingTop
    private var visibility: AutoCloseable? = null
    private var bandVisibility: AutoCloseable? = null
    private val barPosition = IntArray(2)
    private val clip = Rect()
    private val currentClip = Rect()
    private var bottomUnderlap = 0
    private var offset = (appBar.layoutParams as? CoordinatorLayout.LayoutParams)
        ?.let { it.behavior as? AppBarLayout.Behavior }?.topAndBottomOffset ?: 0
    private var inset = 0
    private var contentTop = -1
    private var bandBottom = Float.NaN
    private var backdrop: GlassBackdrop? = null
    private val bandView = View(viewport.context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }
    private val band = TopChromeGlassDrawable(bandView, style, tintRes, ::visibleBottom)

    
    private fun visibleBottom(): Float = maxOf(inset.toFloat(), appBar.bottom + appBar.translationY - bandView.top)
    private val offsetListener = AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
        offset = verticalOffset
        moveChrome()
    }

    fun start(owner: LifecycleOwner) {
        body.updateLayoutParams<CoordinatorLayout.LayoutParams> { behavior = null }
        rows?.clipToPadding = false
        if (installSource && rows != null) {
            backdrop = viewport.installGlassBackdrop(rows) { canvas ->
                canvas.drawColor(ContextCompat.getColor(viewport.context, R.color.bg1))
            }
        }
        bandView.background = band
        viewport.addView(bandView, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        
        appBar.bringToFront()
        visibility = bindPageChromeVisibility(owner, windowLifecycle, covered, appBar)
        
        
        bandVisibility = bindPageChromeVisibility(owner, windowLifecycle, null, bandView)
        appBar.addOnOffsetChangedListener(offsetListener)
        trackSystemBarUnderlap(owner, viewport) { insets ->
            bottomUnderlap = insets.bottom
            updateGeometry()
        }
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) close()
        })
    }

    private fun moveChrome() {
        body.translationY = previousTranslation + offset
        appBar.getLocationInWindow(barPosition)
        
        
        clip.set(0, (inset - barPosition[1]).coerceAtLeast(0), appBar.width, appBar.height)
        if (clip.top >= clip.bottom) clip.setEmpty()
        previousBarClip?.let { if (!clip.intersect(it)) clip.setEmpty() }
        if (!appBar.getClipBounds(currentClip) || currentClip != clip) appBar.clipBounds = clip
        
        
        val bottom = visibleBottom()
        if (bottom != bandBottom) {
            bandBottom = bottom
            bandView.invalidate()
        }
    }

    private fun updateGeometry(): Boolean {
        var settled = true
        inset = viewport.topChromeInset()
        val barPadding = previousBarPaddingTop + inset
        if (appBar.paddingTop != barPadding) {
            appBar.updatePadding(top = barPadding)
            settled = false
        }
        val expandedBottom = appBar.bottom - offset + appBar.translationY
        val top = previousTopPadding + ceil(expandedBottom.coerceAtLeast(0f)).toInt()
        if (!applyOverlayBodyUnderlap(body, 0, previousBottom, previousTranslation,
                collapseRange = appBar.totalScrollRange, offset = offset)
        ) settled = false
        if (rows != null && rows.paddingTop != top) {
            (rows as? RecyclerView)?.offsetChildrenVertical(top - rows.paddingTop)
            rows.updatePadding(top = top)
            settled = false
        }
        if (top != contentTop) {
            contentTop = top
            onContentTop?.invoke(top)
        }
        
        val fade = if (style == TopChromeStyle.Feather) TopChromeGlassDrawable.FEATHER_FADE_DP * viewport.resources.displayMetrics.density else 0f
        val bandHeight = ceil(expandedBottom.coerceAtLeast(inset.toFloat()) + fade).toInt()
        if (bandView.layoutParams.height != bandHeight) {
            bandView.updateLayoutParams<CoordinatorLayout.LayoutParams> { height = bandHeight }
            settled = false
        }
        moveChrome()
        return settled
    }

    private fun close() {
        visibility?.close()
        bandVisibility?.close()
        appBar.removeOnOffsetChangedListener(offsetListener)
        band.close()
        viewport.removeView(bandView)
        backdrop?.close()
        body.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            behavior = previousBehavior
            bottomMargin = previousBottom
        }
        body.translationY = previousTranslation
        appBar.updatePadding(top = previousBarPaddingTop)
        appBar.clipBounds = previousBarClip
        rows?.updatePadding(top = previousTopPadding)
        rows?.clipToPadding = previousClip
        if (viewport.indexOfChild(appBar) != previousAppBarIndex) {
            viewport.removeView(appBar)
            viewport.addView(appBar, previousAppBarIndex)
        }
    }
}


internal fun applyOverlayBodyUnderlap(
    body: View,
    top: Int,
    baseBottomMargin: Int = 0,
    baseTranslation: Float = 0f,
    collapseRange: Int = 0,
    offset: Int = 0,
): Boolean {
    val bottom = baseBottomMargin - top - collapseRange
    val needsLayout = (body.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin != bottom
    if (needsLayout) body.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bottom }
    body.translationY = baseTranslation - top + offset
    return !needsLayout
}
