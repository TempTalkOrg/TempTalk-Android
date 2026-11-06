package com.difft.android.base.utils

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.utils.pane.detailNavigation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Utility object for dual-pane layout support
 * Provides extension functions that can be used by any Fragment without inheritance constraints
 */
object DualPaneUtils {

    /**
     * Check if the fragment's parent activity is in dual-pane mode
     */
    fun Fragment.isInDualPaneMode(): Boolean {
        return (activity as? DualPaneHost)?.isDualPaneMode == true
    }

    /**
     * Whether a page shows its back button: always in one-pane (shell Activity or the full-window
     * detail layer), and in two-pane only where the detail stack has something to pop.
     */
    fun Fragment.showsDetailBackButton(): Boolean =
        !isInDualPaneMode() || detailNavigation().canPop(this)

    /**
     * Back button for a detail page: shown per [showsDetailBackButton] (title uses
     * layout_goneMarginStart in XML when hidden); pops the page through [detailNavigation].
     *
     * @param backButton The back button view
     * @param onBackClick Custom back action, defaults to popping the page
     */
    fun Fragment.setupBackButton(
        backButton: View,
        onBackClick: (() -> Unit)? = null
    ) {
        if (!showsDetailBackButton()) {
            backButton.visibility = View.GONE
        } else {
            backButton.visibility = View.VISIBLE
            backButton.setOnClickListener {
                onBackClick?.invoke() ?: detailNavigation().pop(this)
            }
        }
    }

    /**
     * Back button with dynamic state (e.g., edit mode toggle)
     * Used by ChatHeaderFragment, GroupChatHeaderFragment where back button
     * can switch between back arrow and close icon based on edit mode
     *
     * @param backButton The back button ImageView
     * @param isEditMode Whether currently in edit/selection mode
     * @param backIconRes Resource ID for normal back icon
     * @param closeIconRes Resource ID for close/cancel icon in edit mode
     * @param onExitEditMode Callback when close button clicked in edit mode
     * @param onBack Callback when back button clicked in normal mode, defaults to popping the page
     */
    fun Fragment.setupDynamicBackButton(
        backButton: ImageView,
        isEditMode: Boolean,
        backIconRes: Int,
        closeIconRes: Int,
        onExitEditMode: () -> Unit,
        onBack: () -> Unit = { detailNavigation().pop(this) }
    ) {
        when {
            !isEditMode && !showsDetailBackButton() -> {
                backButton.visibility = View.GONE
            }
            isEditMode -> {
                // In edit mode, show close icon
                backButton.visibility = View.VISIBLE
                backButton.setImageResource(closeIconRes)
                backButton.setOnClickListener { onExitEditMode() }
            }
            else -> {
                // Normal mode, show back icon
                backButton.visibility = View.VISIBLE
                backButton.setImageResource(backIconRes)
                backButton.setOnClickListener { onBack() }
            }
        }
    }
}

/**
 * Interface for activities that support dual-pane layout
 * Activities implementing this interface can host fragments in a detail pane
 */
interface DualPaneHost {
    /**
     * Whether the activity is currently in dual-pane mode (large screen)
     */
    val isDualPaneMode: Boolean

    /**
     * Show a fragment in the detail pane (for dual-pane mode)
     * In single-pane mode, this should navigate to the appropriate Activity
     *
     * @param fragment The fragment to display
     * @param tag Optional tag for the fragment transaction
     */
    fun showDetailFragment(fragment: Fragment, tag: String? = null)

    /**
     * Height in px of the floating home tab capsule's host over the bottom of the home pages
     * (capsule, gaps and, on phones, the navigation-bar inset); 0 while it is not shown. The
     * Chats / Contacts / Me pages pad their scrolling content by it so the last row can
     * scroll clear of the capsule.
     */
    val homeTabBarObstructionPx: StateFlow<Int>
}

/**
 * Lets a home page's scrolling view run underneath the floating home tab capsule: content
 * draws through the bottom padding (`clipToPadding = false`) while the padding itself tracks
 * [DualPaneHost.homeTabBarObstructionPx] so the last row can always scroll clear. The view's
 * own bottom padding is kept underneath, plus [extraPx] (a collapsible title's range, see
 * HomeHeaderCollapser). No-op outside a [DualPaneHost].
 */
fun View.padBottomForHomeTabBar(owner: LifecycleOwner, host: DualPaneHost?, extraPx: Int = 0) {
    host ?: return
    val basePaddingBottom = paddingBottom
    (this as? ViewGroup)?.clipToPadding = false
    host.homeTabBarObstructionPx
        .onEach { obstruction -> updatePadding(bottom = basePaddingBottom + obstruction + extraPx) }
        .launchIn(owner.lifecycleScope)
}

/**
 * Keeps [this] view's bottom margin clear of the floating home tab capsule (plus [extraPx]) on
 * top of its own declared margin — for overlays such as a letter index that are pinned to the
 * page bottom rather than scrolled. No-op outside a [DualPaneHost].
 */
fun View.marginBottomForHomeTabBar(owner: LifecycleOwner, host: DualPaneHost?, extraPx: Int = 0) {
    host ?: return
    val baseMarginBottom = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: return
    host.homeTabBarObstructionPx
        .onEach { obstruction ->
            val params = layoutParams as ViewGroup.MarginLayoutParams
            val target = baseMarginBottom + obstruction + extraPx
            if (params.bottomMargin != target) {
                params.bottomMargin = target
                layoutParams = params
            }
        }
        .launchIn(owner.lifecycleScope)
}

