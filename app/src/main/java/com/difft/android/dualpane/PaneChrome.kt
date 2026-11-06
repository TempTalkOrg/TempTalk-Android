package com.difft.android.dualpane

import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.R

/**
 * The views [PaneNavigator] drives from the current tab's stack: the detail pane's background, the
 * two-pane empty state, the one-pane detail layer (visibility, slide, list accessibility) and the
 * root background, whose colour shows through the edge-to-edge system-bar strips. This is the
 * single owner of the root background, so a tab change cannot repaint over the detail layer.
 */
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

    /** True while the one-pane layer slides out ahead of its stack being cleared. */
    var layerClosing = false
        private set

    // The close slide finished: the layer sits off screen until its stack is cleared.
    private var layerOffscreen = false

    // The last apply's inputs, so a list-layer change can repaint the root without the navigator.
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
        if (isDualPane) {
            applyDetailBackground(pane, tab, top, topIsConversation)
        } else {
            // One-pane pushed pages paint their own background, so the layer keeps the root's:
            // a conversation stays visible behind the push slide instead of turning plain.
            applyDetailBackground(pane, tab, root, rootIsConversation)
        }
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

    /**
     * One-pane: the root shows through the status-bar strip above the layer. A conversation on top
     * gets the solid bg1 strip ChatActivity's window paints there (the layer paints the rest);
     * another page uses its own colour; with no layer, the tab's list colour. Always a solid
     * colour: the list sits on the root while the layer slides in or out, and a patterned root
     * would show through its transparent title bar.
     */
    private fun applyOnePaneRootBackground(tab: Int, top: Fragment?, depth: Int, topIsConversation: Boolean) {
        val rootColor = when {
            depth == 0 -> listColorRes(tab)
            topIsConversation -> com.difft.android.base.R.color.bg1
            else -> detailColorRes(tab, top)
        }
        if (rootBackgroundKey != rootColor) {
            rootBackgroundKey = rootColor
            root.setBackgroundResource(rootColor)
        }
    }

    /** One-pane: slides the layer out, then [onClosed] clears the stack it shows. */
    fun closeLayer(onClosed: () -> Unit) {
        val pane = detailPane
        if (pane == null || pane.visibility != View.VISIBLE) {
            onClosed()
            return
        }
        if (layerClosing) return
        layerClosing = true
        pane.animate().cancel()
        pane.animate()
            .translationX(slideDistance(pane))
            .setDuration(LAYER_SLIDE_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                layerClosing = false
                layerOffscreen = true
                onClosed()
            }
            .start()
    }

    /**
     * A new page arrived while the layer was closing (or closed off screen ahead of its clear):
     * stop the slide and bring the layer back. Only the navigator calls this, for a real new page;
     * a routine re-derive while a close is pending must leave the layer where the slide put it.
     */
    fun cancelClose() {
        if (!layerClosing && !layerOffscreen) return
        layerClosing = false
        layerOffscreen = false
        detailPane?.let {
            it.animate().cancel()
            it.translationX = 0f
        }
    }

    private fun applyLayer(pane: View, covers: Boolean, animate: Boolean) {
        if (covers) {
            if (pane.visibility != View.VISIBLE) {
                pane.visibility = View.VISIBLE
                pane.animate().cancel()
                if (animate) {
                    pane.translationX = slideDistance(pane)
                    pane.animate()
                        .translationX(0f)
                        .setDuration(LAYER_SLIDE_MS)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    pane.translationX = 0f
                }
            }
        } else if (!layerClosing) {
            layerOffscreen = false
            pane.animate().cancel()
            pane.translationX = 0f
            pane.visibility = View.GONE
        }
        detailLayerCovers = covers
        applyCoverage()
    }

    // One-pane detail layer up / list-pane layer up: the single owner of what the covered views hide.
    private var detailLayerCovers = false
    private var listLayerCovers = false

    /** The list-pane layer covers the tab content (both layouts); the list views hide from TalkBack and focus. */
    fun setLayerCovers(covers: Boolean) {
        if (listLayerCovers == covers) return
        listLayerCovers = covers
        applyCoverage()
        // The layer's pages (search, folded groups) sit on bg1, also over the Me tab's bg list:
        // the status strip above the list column follows the layer.
        val pane = detailPane
        if (applied && pane != null) {
            if (isDualPane) {
                applyTwoPaneRootBackground(lastTab, lastTop, pane)
            } else {
                applyOnePaneRootBackground(lastTab, lastTop, lastDepth, lastTopIsConversation)
            }
        }
    }

    /**
     * Covered views are kept out of TalkBack and keyboard focus: the list while the detail layer
     * or the list-pane layer is up, and the list-pane layer itself while the one-pane detail layer is up.
     */
    private fun applyCoverage() {
        LIST_VIEW_IDS.forEach { setCovered(it, detailLayerCovers || listLayerCovers) }
        // The container itself is never a node (its pages are): back to the layout's "no".
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

    // Set on detail_pane, not in the fragment, so it stays fixed while the IME resizes the page.
    private fun applyDetailBackground(pane: View, tab: Int, top: Fragment?, topIsConversation: Boolean) {
        val key: Any = if (topIsConversation) CONVERSATION_BACKGROUND else detailColorRes(tab, top)
        if (key == detailBackgroundKey) return
        detailBackgroundKey = key
        if (topIsConversation) {
            // Both layouts' detail column reaches the window bottom: paint the navigation-bar
            // strip under the input solid, like ChatActivity.
            pane.background = pages.conversationLayerBackground(activity, pane)
        } else {
            pane.setBackgroundResource(detailColorRes(tab, top))
        }
    }

    // Two-pane: the status/navigation bar strips keep the colour of the column below them.
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

    // Recent and Contacts are flat immersive lists (bg1); Me is the settings idiom (bg). The
    // list-pane layer (search, folded groups) covers any tab's list with bg1 pages.
    private fun listColorRes(tab: Int): Int =
        if (tab == PaneNavigator.TAB_ME && !listLayerCovers) com.difft.android.base.R.color.bg else com.difft.android.base.R.color.bg1

    // Settings pages (Me tab) and the contact card use the settings background; chats and the
    // empty state sit on bg1.
    /** Opaque background for a pushed page: the colour the pane would paint behind it. */
    fun pageBackground(tab: Int, page: Fragment): android.graphics.drawable.Drawable =
        android.graphics.drawable.ColorDrawable(ContextCompat.getColor(activity, detailColorRes(tab, page)))

    private fun detailColorRes(tab: Int, top: Fragment?): Int =
        if (tab == PaneNavigator.TAB_ME || (top != null && pages.usesSettingsBackground(top))) {
            com.difft.android.base.R.color.bg
        } else {
            com.difft.android.base.R.color.bg1
        }

    private fun slideDistance(pane: View): Float {
        val width = (if (pane.width > 0) pane.width else root.width).toFloat()
        return if (pane.layoutDirection == View.LAYOUT_DIRECTION_RTL) -width else width
    }

    private companion object {
        const val LAYER_SLIDE_MS = 250L
        const val CONVERSATION_BACKGROUND = "conversation"
        val LIST_VIEW_IDS = intArrayOf(R.id.index_viewpager, R.id.home_tab_bar)
    }
}
