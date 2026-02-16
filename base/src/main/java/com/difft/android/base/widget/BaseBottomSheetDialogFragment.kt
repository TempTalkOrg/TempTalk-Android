package com.difft.android.base.widget

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.R
import com.difft.android.base.utils.SecureModeUtil
import com.difft.android.base.utils.WindowSizeClassUtil
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Base class for BottomSheetDialogFragment with unified styling and wide-screen support.
 *
 * Features:
 * - Rounded corners at top (16dp) when using default container
 * - Drag handle (optional)
 * - Auto fit-to-content by default
 * - Optional fixed peek height with expandable behavior
 * - Optional full-screen mode
 * - Max width constraint for wide screens (640dp)
 * - Custom layout support (bypass default container)
 *
 * Usage Mode 1 - Default Container (with rounded corners and drag handle):
 * 1. Override [getContentLayoutResId] to provide your content layout
 * 2. Override [onContentViewCreated] to setup your views
 * 3. Default: auto fit-to-content height
 * 4. Optional: override [getPeekHeightRatio] (e.g., 0.75f) for fixed initial height
 *
 * Usage Mode 2 - Custom Layout (full control):
 * 1. Override [useDefaultContainer] to return false
 * 2. Override [getCustomLayoutResId] to provide your custom layout
 * 3. Override [onViewCreated] to setup your views
 * 4. Override [isFullScreen], [isCancelableByUser], [isDraggable] as needed
 *
 * Usage Mode 3 - Fragment-based content:
 * 1. Override [getContentFragment] instead of [getContentLayoutResId]
 * 2. The fragment will be loaded into the container automatically
 */
abstract class BaseBottomSheetDialogFragment : BottomSheetDialogFragment() {

    // ========== Container Configuration ==========

    /**
     * Whether to use the default container layout (with rounded corners and drag handle).
     * Return false to use a custom layout via [getCustomLayoutResId].
     * Default is true.
     */
    protected open fun useDefaultContainer(): Boolean = true

    /**
     * Provide a custom layout resource ID when [useDefaultContainer] returns false.
     * This layout will be used directly without the default container wrapper.
     */
    @LayoutRes
    protected open fun getCustomLayoutResId(): Int = 0

    // ========== Content Configuration (for default container) ==========

    /**
     * Provide the layout resource ID for the content.
     * Only used when [useDefaultContainer] returns true.
     * Return 0 if using [getContentFragment] instead.
     */
    @LayoutRes
    protected open fun getContentLayoutResId(): Int = 0

    /**
     * Provide a Fragment to be loaded as content.
     * Only used when [useDefaultContainer] returns true.
     * Return null if using [getContentLayoutResId] instead.
     */
    protected open fun getContentFragment(): Fragment? = null

    // ========== Behavior Configuration ==========

    /**
     * Peek height ratio (0.0 - 1.0) of screen height.
     * Return 0 or negative to use auto fit-to-content behavior (default).
     * Return positive value (e.g., 0.75f) for fixed initial height with expandable behavior.
     */
    protected open fun getPeekHeightRatio(): Float = 0f

    /**
     * Whether the bottom sheet should use full screen height.
     * Default is false.
     */
    protected open fun isFullScreen(): Boolean = false

    /**
     * Whether the bottom sheet can be expanded to full screen.
     * Only applies when using fixed peek height (getPeekHeightRatio > 0).
     * Default is true.
     */
    protected open fun isExpandable(): Boolean = true

    /**
     * Whether the user can cancel the dialog by clicking outside or pressing back.
     * Default is true.
     */
    protected open fun isCancelableByUser(): Boolean = true

    /**
     * Whether the bottom sheet can be dragged by the user.
     * Default is true.
     */
    protected open fun isDraggable(): Boolean = true

    /**
     * Whether to show the drag handle (only for default container).
     * Default is true.
     */
    protected open fun showDragHandle(): Boolean = true

    /**
     * Whether to use the default rounded background.
     * Set to false if you want to use a custom background.
     * Default is true.
     */
    protected open fun useDefaultBackground(): Boolean = true

    // ========== Max Width Configuration ==========

    /**
     * Maximum width for the bottom sheet in pixels.
     * Return 0 or negative to use full width.
     * Default uses R.dimen.bottom_sheet_max_width (640dp).
     */
    protected open fun getMaxWidth(): Int {
        return resources.getDimensionPixelSize(R.dimen.bottom_sheet_max_width)
    }

    // ========== Internal State ==========

    private var contentView: View? = null

    // ========== Lifecycle Methods ==========

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return if (useDefaultContainer()) {
            inflater.inflate(R.layout.base_bottom_sheet_container, container, false)
        } else {
            val customLayoutId = getCustomLayoutResId()
            if (customLayoutId != 0) {
                inflater.inflate(customLayoutId, container, false)
            } else {
                null
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (useDefaultContainer()) {
            // Setup drag handle visibility
            val dragHandle = view.findViewById<View>(R.id.drag_handle)
            dragHandle?.visibility = if (showDragHandle()) View.VISIBLE else View.GONE

            // Load content
            val contentContainer = view.findViewById<FrameLayout>(R.id.content_container)

            val contentFragment = getContentFragment()
            if (contentFragment != null) {
                // Load Fragment into container
                childFragmentManager.beginTransaction()
                    .replace(R.id.content_container, contentFragment)
                    .commit()
            } else {
                // Inflate layout into container
                val layoutResId = getContentLayoutResId()
                if (layoutResId != 0) {
                    contentView = layoutInflater.inflate(layoutResId, contentContainer, false)
                    contentContainer.addView(contentView)
                    onContentViewCreated(contentView!!, savedInstanceState)
                }
            }
        }
        // When not using default container, subclasses should handle their own view setup
    }

    /**
     * Called after content view is created (when using [getContentLayoutResId] with default container).
     * Override this to setup your views.
     */
    protected open fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
        // Override in subclass
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        // Set cancelable based on configuration
        isCancelable = isCancelableByUser()
        dialog.setCanceledOnTouchOutside(isCancelableByUser())

        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )

            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                val peekHeightRatio = getPeekHeightRatio()

                // Apply max width constraint for wide screens
                applyMaxWidth(sheet)

                // Configure draggable
                behavior.isDraggable = isDraggable()
                behavior.isHideable = isCancelableByUser()

                val screenHeight = WindowSizeClassUtil.getWindowHeightPx(requireActivity())

                if (peekHeightRatio > 0 && !isExpandable()) {
                    // Fixed height mode (non-expandable) - similar to ChatPopupActivity
                    val fixedHeight = (screenHeight * peekHeightRatio).toInt()

                    // Set view height to fixed height
                    val layoutParams = sheet.layoutParams
                    layoutParams.height = fixedHeight
                    sheet.layoutParams = layoutParams

                    // Configure behavior for fixed height mode
                    behavior.isFitToContents = true
                    behavior.skipCollapsed = true
                    behavior.peekHeight = fixedHeight
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                } else if (isFullScreen() || peekHeightRatio > 0) {
                    // Expandable mode (full screen or expandable peek height)
                    behavior.isFitToContents = false
                    behavior.skipCollapsed = isFullScreen()

                    if (isFullScreen()) {
                        behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    } else {
                        behavior.peekHeight = (screenHeight * peekHeightRatio).toInt()
                        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    }

                    // Set the BottomSheet height to match screen
                    val layoutParams = sheet.layoutParams
                    layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                    sheet.layoutParams = layoutParams
                } else {
                    // Auto height based on content (default behavior)
                    behavior.isFitToContents = true
                    behavior.skipCollapsed = false
                    behavior.peekHeight = BottomSheetBehavior.PEEK_HEIGHT_AUTO
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                }

                // Apply background
                if (useDefaultBackground()) {
                    sheet.setBackgroundResource(android.R.color.transparent)
                }

                // Add callback for state changes
                behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                            dismiss()
                        }
                        onBottomSheetStateChanged(newState)
                    }

                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        onBottomSheetSlide(slideOffset)
                    }
                })

                // Allow subclasses to do additional customization
                onBottomSheetReady(sheet, behavior)
            }
        }

        // 根据屏幕锁状态设置安全模式（Dialog 有独立 Window，需单独设置）
        SecureModeUtil.applySecureFlagToDialog(dialog)

        return dialog
    }

    /**
     * Apply max width constraint to the bottom sheet.
     */
    private fun applyMaxWidth(sheet: View) {
        val maxWidth = getMaxWidth()
        if (maxWidth <= 0) return

        val screenWidth = WindowSizeClassUtil.getWindowWidthPx(requireActivity())
        if (screenWidth > maxWidth) {
            val layoutParams = sheet.layoutParams
            when (layoutParams) {
                is CoordinatorLayout.LayoutParams -> {
                    layoutParams.width = maxWidth
                    layoutParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                }
                is FrameLayout.LayoutParams -> {
                    layoutParams.width = maxWidth
                    layoutParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                }
                else -> {
                    layoutParams.width = maxWidth
                }
            }
            sheet.layoutParams = layoutParams
        }
    }

    /**
     * Called when the bottom sheet is ready and configured.
     * Override to do additional customization.
     *
     * @param sheet The bottom sheet view
     * @param behavior The bottom sheet behavior
     */
    protected open fun onBottomSheetReady(sheet: View, behavior: BottomSheetBehavior<*>) {
        // Override in subclass if needed
    }

    /**
     * Called when bottom sheet state changes.
     * Override to handle state changes.
     */
    protected open fun onBottomSheetStateChanged(newState: Int) {
        // Override in subclass if needed
    }

    /**
     * Called when bottom sheet slides.
     * @param slideOffset -1.0 (hidden) to 1.0 (expanded), 0.0 is collapsed
     */
    protected open fun onBottomSheetSlide(slideOffset: Float) {
        // Override in subclass if needed
    }

    override fun onDestroyView() {
        super.onDestroyView()
        contentView = null
    }

    companion object {
        /**
         * Helper to show a BottomSheetDialogFragment from an Activity.
         */
        fun <T : BaseBottomSheetDialogFragment> T.show(activity: FragmentActivity, tag: String? = null) {
            show(activity.supportFragmentManager, tag ?: this::class.java.simpleName)
        }

        /**
         * Helper to show a BottomSheetDialogFragment from a Fragment.
         */
        fun <T : BaseBottomSheetDialogFragment> T.show(fragment: Fragment, tag: String? = null) {
            show(fragment.parentFragmentManager, tag ?: this::class.java.simpleName)
        }
    }
}