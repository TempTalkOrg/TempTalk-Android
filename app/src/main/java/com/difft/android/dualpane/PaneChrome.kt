package com.difft.android.dualpane

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.R







internal class PaneChrome(
    private val activity: FragmentActivity,
    private val isDualPane: Boolean,
    private val pages: PaneNavigator.Pages,
) {
    private val root: View
        get() = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)

    private val detailPane: View? get() = activity.findViewById(R.id.detail_pane)

    private var detailBackgroundKey: Any? = null
    private var rootBackgroundKey: Any? = null

    
    var layerClosing = false
        private set

    
    private var layerOffscreen = false

    
    private var lastTab = PaneNavigator.TAB_CHATS
    private var lastTop: Fragment? = null
    private var lastDepth = 0
    private var lastTopIsConversation = false
    private var applied = false

    fun apply(
        tab: Int,
        top: Fragment?,
        root: Fragment?,
        depth: Int,
        topIsConversation: Boolean,
        rootIsConversation: Boolean,
        animate: Boolean,
    ) {
        lastTab = tab
        lastTop = top
        lastDepth = depth
        lastTopIsConversation = topIsConversation
        applied = true
        val pane = detailPane ?: return
        
        
        applyDetailBackground(pane, tab, root, rootIsConversation)
        if (isDualPane) {
            val empty = depth == 0
            activity.findViewById<View>(R.id.empty_detail_view)?.visibility = if (empty) View.VISIBLE else View.GONE
            activity.findViewById<View>(R.id.fragment_container_detail)?.visibility = if (empty) View.GONE else View.VISIBLE
            applyTwoPaneRootBackground(tab, top, pane)
        } else {
            applyLayer(pane, covers = depth > 0, animate = animate)
            applyOnePaneRootBackground(tab, top, depth, topIsConversation)
        }
    }

    





    private fun applyOnePaneRootBackground(tab: Int, top: Fragment?, depth: Int, topIsConversation: Boolean) {
        val rootColor = when {
            depth == 0 -> listColorRes(tab)
            topIsConversation -> com.difft.android.base.R.color.bg1
            else -> detailColorRes(tab, top)
        }
        if (rootBackgroundKey == rootColor) return
        rootBackgroundKey = rootColor
        root.setBackgroundResource(rootColor)
    }

    



    fun closeLayer(onClosed: () -> Unit) {
        val pane = detailPane
        if (pane == null || pane.visibility != View.VISIBLE) {
            onClosed()
            return
        }
        if (layerClosing) return
        layerClosing = true
        runDetailLayerAnimation(PaneMotion.uncover(pane, underDetailLayer())) { cancelled ->
            layerClosing = false
            if (cancelled) return@runDetailLayerAnimation
            layerOffscreen = true
            onClosed()
        }
    }

    




    fun cancelClose() {
        if (!layerClosing && !layerOffscreen) return
        layerClosing = false
        layerOffscreen = false
        detailLayerAnimator?.cancel()
        detailPane?.translationX = 0f
    }

    private fun applyLayer(pane: View, covers: Boolean, animate: Boolean) {
        if (covers) {
            if (pane.visibility != View.VISIBLE) {
                pane.visibility = View.VISIBLE
                detailLayerAnimator?.cancel()
                if (animate) {
                    runDetailLayerAnimation(PaneMotion.cover(pane, underDetailLayer())) {}
                } else {
                    pane.translationX = 0f
                }
            }
        } else if (!layerClosing) {
            layerOffscreen = false
            detailLayerAnimator?.cancel()
            pane.translationX = 0f
            pane.visibility = View.GONE
        }
        detailLayerCovers = covers
        applyCoverage()
    }

    
    private fun underDetailLayer(): List<View> = listOfNotNull(
        activity.findViewById(R.id.list_content),
        activity.findViewById<View>(R.id.list_layer)?.takeIf { it.visibility == View.VISIBLE },
    )

    
    
    
    

    private var detailLayerAnimator: Animator? = null
    private var listLayerAnimator: Animator? = null

    private fun runDetailLayerAnimation(animator: Animator, onFinished: (cancelled: Boolean) -> Unit) {
        listLayerAnimator?.cancel()
        detailLayerAnimator?.cancel()
        detailLayerAnimator = start(animator) { cancelled ->
            if (detailLayerAnimator === animator) detailLayerAnimator = null
            onFinished(cancelled)
        }
    }

    



    fun runListLayerAnimation(animator: Animator, onFinished: (cancelled: Boolean) -> Unit) {
        listLayerAnimator?.cancel()
        if (detailLayerAnimator != null) {
            onFinished(false)
            return
        }
        listLayerAnimator = start(animator) { cancelled ->
            if (listLayerAnimator === animator) listLayerAnimator = null
            onFinished(cancelled)
        }
    }

    
    fun cancelListLayerAnimation() {
        listLayerAnimator?.cancel()
    }

    private fun start(animator: Animator, onFinished: (cancelled: Boolean) -> Unit): Animator {
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) = onFinished(cancelled)
        })
        animator.start()
        return animator
    }

    
    private var detailLayerCovers = false
    private var listLayerCovers = false

    
    fun setLayerCovers(covers: Boolean) {
        if (listLayerCovers == covers) return
        listLayerCovers = covers
        applyCoverage()
        
        
        val pane = detailPane
        if (applied && pane != null) {
            if (isDualPane) {
                applyTwoPaneRootBackground(lastTab, lastTop, pane)
            } else {
                applyOnePaneRootBackground(lastTab, lastTop, lastDepth, lastTopIsConversation)
            }
        }
    }

    



    private fun applyCoverage() {
        LIST_VIEW_IDS.forEach { setCovered(it, detailLayerCovers || listLayerCovers) }
        
        setCovered(R.id.list_layer, detailLayerCovers, uncovered = View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    }

    private fun setCovered(id: Int, covered: Boolean, uncovered: Int = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
        val view = activity.findViewById<View>(id) ?: return
        view.importantForAccessibility = if (covered) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else uncovered
        (view as? ViewGroup)?.descendantFocusability = if (covered) {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    
    private fun applyDetailBackground(pane: View, tab: Int, top: Fragment?, topIsConversation: Boolean) {
        val key: Any = if (topIsConversation) CONVERSATION_BACKGROUND else detailColorRes(tab, top)
        if (key == detailBackgroundKey) return
        detailBackgroundKey = key
        if (topIsConversation) {
            
            
            pane.background = pages.conversationLayerBackground(activity, pane)
        } else {
            pane.setBackgroundResource(detailColorRes(tab, top))
        }
    }

    
    private fun applyTwoPaneRootBackground(tab: Int, top: Fragment?, pane: View) {
        val listColor = listColorRes(tab)
        val detailColor = detailColorRes(tab, top)
        val key = listColor to detailColor
        if (rootBackgroundKey == key) return
        rootBackgroundKey = key
        root.background = PaneSplitBackgroundDrawable(
            detailPane = pane,
            listColor = ContextCompat.getColor(activity, listColor),
            detailColor = ContextCompat.getColor(activity, detailColor),
        )
    }

    
    
    private fun listColorRes(tab: Int): Int =
        if (tab == PaneNavigator.TAB_ME && !listLayerCovers) com.difft.android.base.R.color.bg else com.difft.android.base.R.color.bg1

    
    
    
    fun pageBackground(tab: Int, page: Fragment): android.graphics.drawable.Drawable =
        android.graphics.drawable.ColorDrawable(ContextCompat.getColor(activity, detailColorRes(tab, page)))

    private fun detailColorRes(tab: Int, top: Fragment?): Int =
        if (tab == PaneNavigator.TAB_ME || (top != null && pages.usesSettingsBackground(top))) {
            com.difft.android.base.R.color.bg
        } else {
            com.difft.android.base.R.color.bg1
        }

    private companion object {
        const val CONVERSATION_BACKGROUND = "conversation"
        val LIST_VIEW_IDS = intArrayOf(R.id.index_viewpager, R.id.home_tab_bar)
    }
}
