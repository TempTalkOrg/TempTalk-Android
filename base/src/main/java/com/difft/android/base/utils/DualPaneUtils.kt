package com.difft.android.base.utils

import android.view.View
import android.widget.ImageView
import androidx.fragment.app.Fragment

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
     * Setup back button behavior based on dual-pane mode
     * - Dual-pane mode: hide the back button (title uses layout_goneMarginStart in XML)
     * - Single-pane mode: show the back button
     *
     * @param backButton The back button view
     * @param onBackClick Custom back action, defaults to finishing the activity
     */
    fun Fragment.setupBackButton(
        backButton: View,
        onBackClick: (() -> Unit)? = null
    ) {
        if (isInDualPaneMode()) {
            backButton.visibility = View.GONE
        } else {
            backButton.visibility = View.VISIBLE
            backButton.setOnClickListener {
                onBackClick?.invoke() ?: activity?.finish()
            }
        }
    }

    /**
     * Setup back button with dynamic state (e.g., edit mode toggle)
     * Used by ChatHeaderFragment, GroupChatHeaderFragment where back button
     * can switch between back arrow and close icon based on edit mode
     *
     * @param backButton The back button ImageView
     * @param isEditMode Whether currently in edit/selection mode
     * @param backIconRes Resource ID for normal back icon
     * @param closeIconRes Resource ID for close/cancel icon in edit mode
     * @param onExitEditMode Callback when close button clicked in edit mode
     * @param onBack Callback when back button clicked in normal mode
     */
    fun Fragment.setupDynamicBackButton(
        backButton: ImageView,
        isEditMode: Boolean,
        backIconRes: Int,
        closeIconRes: Int,
        onExitEditMode: () -> Unit,
        onBack: () -> Unit = { activity?.finish() }
    ) {
        when {
            isInDualPaneMode() && !isEditMode -> {
                // In dual-pane mode, hide back button when not in edit mode
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
}

