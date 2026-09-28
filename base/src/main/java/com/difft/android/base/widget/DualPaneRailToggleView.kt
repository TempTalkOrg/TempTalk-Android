package com.difft.android.base.widget

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.R
import com.difft.android.base.utils.DualPaneHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The list-pane header button that collapses / expands the dual-pane navigation rail.
 *
 * Self-wiring so every tab header (Chats, Contacts, Me) declares it in XML and nothing else:
 * on attach it finds the hosting [DualPaneHost] through the context chain, shows itself only
 * in dual-pane mode (phones never see it — declare it `gone` in XML so there is no first-frame
 * flash), routes taps to [DualPaneHost.toggleNavigationRail], and follows
 * [DualPaneHost.navigationRailCollapsed] to keep its accessibility label current. The icon
 * is the same in both states — the design's "tap the same button to come back".
 */
class DualPaneRailToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageButton(context, attrs) {

    private var host: DualPaneHost? = null
    private var stateJob: Job? = null

    init {
        setImageResource(R.drawable.ic_dual_pane_rail_toggle)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.t_primary))
        background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            .let { attributes -> attributes.getDrawable(0).also { attributes.recycle() } }
        setOnClickListener { host?.toggleNavigationRail() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val dualPaneHost = context.findDualPaneHost()?.takeIf { it.isDualPaneMode }
        host = dualPaneHost
        if (dualPaneHost == null) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        val owner = findViewTreeLifecycleOwner() ?: return
        stateJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dualPaneHost.navigationRailCollapsed.collect { collapsed ->
                    contentDescription = context.getString(
                        if (collapsed) R.string.dual_pane_rail_expand_hint else R.string.dual_pane_rail_collapse_hint
                    )
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        stateJob?.cancel()
        stateJob = null
        host = null
        super.onDetachedFromWindow()
    }

    private fun Context.findDualPaneHost(): DualPaneHost? {
        var current: Context? = this
        while (current != null) {
            if (current is DualPaneHost) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }
}
