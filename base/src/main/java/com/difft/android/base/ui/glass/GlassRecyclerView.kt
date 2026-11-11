package com.difft.android.base.ui.glass

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView





class GlassRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.recyclerview.R.attr.recyclerViewStyle,
) : RecyclerView(context, attrs, defStyleAttr), GlassDamageSource {
    
    private var tracker: GlassRegionDirtyTracker? = null
    private var edges: GlassEdgeEffectFactory? = null
    override val glassDamage: GlassRegionDirtyTracker get() = checkNotNull(tracker)

    init {
        tracker = GlassRegionDirtyTracker(this)
        if (Build.VERSION.SDK_INT >= 31 && GlassMode.isLive(context)) {
            edges = GlassEdgeEffectFactory(glassDamage).also { edgeEffectFactory = it }
        }
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        if (Build.VERSION.SDK_INT >= 31) tracker?.descendantInvalidated(target)
        super.onDescendantInvalidated(child, target)
    }

    override fun invalidate() {
        tracker?.invalidateAll()
        super.invalidate()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun invalidate(dirty: Rect) {
        tracker?.invalidateAll()
        super.invalidate(dirty)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun invalidate(l: Int, t: Int, r: Int, b: Int) {
        tracker?.invalidateAll()
        super.invalidate(l, t, r, b)
    }

    override fun onScrolled(dx: Int, dy: Int) {
        if (dx != 0 || dy != 0) tracker?.invalidateAll()
        super.onScrolled(dx, dy)
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) edges?.finishAll()
        tracker?.reset()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (Build.VERSION.SDK_INT >= 31) edges?.reset()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun setClipToPadding(clipToPadding: Boolean) {
        if (Build.VERSION.SDK_INT >= 31 && clipToPadding != this.clipToPadding) edges?.reset()
        super.setClipToPadding(clipToPadding)
    }
}
