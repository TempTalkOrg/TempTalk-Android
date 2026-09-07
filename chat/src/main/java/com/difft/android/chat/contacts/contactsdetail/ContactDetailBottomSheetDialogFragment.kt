package com.difft.android.chat.contacts.contactsdetail

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.AndroidEntryPoint

/**
 * BottomSheet dialog for displaying contact details.
 * Uses [ContactDetailFragment] as content.
 */
@AndroidEntryPoint
class ContactDetailBottomSheetDialogFragment : BaseBottomSheetDialogFragment() {

    // Contact detail is also a full page and paints the page colour itself; keep the container on
    // that colour so its bg.elevated cards keep contrast and the drag handle shows no seam.
    override fun getContainerBackgroundRes(): Int = com.difft.android.base.R.drawable.base_bg_bottom_sheet_page

    companion object {
        private const val TAG = "ContactDetailBottomSheet"
        private const val ARG_CONTACT_ID = "ARG_CONTACT_ID"
        private const val ARG_CONTACT_NAME = "ARG_CONTACT_NAME"
        private const val ARG_SOURCE_TYPE = "ARG_SOURCE_TYPE"
        private const val ARG_SOURCE = "ARG_SOURCE"
        private const val ARG_AVATAR = "ARG_AVATAR"
        private const val ARG_JOINED_AT = "ARG_JOINED_AT"

        private fun newInstance(
            contactId: String,
            contactName: String? = null,
            sourceType: String? = null,
            source: String? = null,
            avatar: String? = null,
            joinedAt: String? = null
        ): ContactDetailBottomSheetDialogFragment {
            return ContactDetailBottomSheetDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONTACT_ID, contactId)
                    putString(ARG_CONTACT_NAME, contactName)
                    putString(ARG_SOURCE_TYPE, sourceType)
                    putString(ARG_SOURCE, source)
                    putString(ARG_AVATAR, avatar)
                    putString(ARG_JOINED_AT, joinedAt)
                }
            }
        }

        /**
         * Show contact detail bottom sheet from an Activity.
         */
        fun show(
            activity: FragmentActivity,
            contactId: String,
            contactName: String? = null,
            sourceType: String? = null,
            source: String? = null,
            avatar: String? = null,
            joinedAt: String? = null
        ) {
            newInstance(contactId, contactName, sourceType, source, avatar, joinedAt)
                .show(activity.supportFragmentManager, TAG)
        }

        /**
         * Show contact detail bottom sheet from a Fragment.
         */
        fun show(
            fragment: Fragment,
            contactId: String,
            contactName: String? = null,
            sourceType: String? = null,
            source: String? = null,
            avatar: String? = null,
            joinedAt: String? = null
        ) {
            newInstance(contactId, contactName, sourceType, source, avatar, joinedAt)
                .show(fragment.parentFragmentManager, TAG)
        }

        /**
         * Show contact detail bottom sheet with FragmentManager.
         */
        fun show(
            fragmentManager: FragmentManager,
            contactId: String,
            contactName: String? = null,
            sourceType: String? = null,
            source: String? = null,
            avatar: String? = null,
            joinedAt: String? = null
        ) {
            newInstance(contactId, contactName, sourceType, source, avatar, joinedAt)
                .show(fragmentManager, TAG)
        }
    }

    override fun getContentFragment(): Fragment {
        return ContactDetailFragment.newInstance(
            contactId = arguments?.getString(ARG_CONTACT_ID) ?: "",
            contactName = arguments?.getString(ARG_CONTACT_NAME),
            sourceType = arguments?.getString(ARG_SOURCE_TYPE),
            source = arguments?.getString(ARG_SOURCE),
            avatar = arguments?.getString(ARG_AVATAR),
            joinedAt = arguments?.getString(ARG_JOINED_AT)
        )
    }

    // Auto fit-to-contents: return 0 to enable fit-to-contents mode
    override fun getPeekHeightRatio(): Float = 0f

    // No need for expandable in fit-to-contents mode
    override fun isExpandable(): Boolean = false

    private var sheetView: View? = null
    private var sheetBehavior: BottomSheetBehavior<*>? = null
    private var expandedForEditing = false
    private var dismissGesturesEnabled = true

    override fun onBottomSheetReady(sheet: View, behavior: BottomSheetBehavior<*>) {
        sheetView = sheet
        sheetBehavior = behavior
        // Edit mode may have been requested before the dialog showed (e.g. recreation mid-edit),
        // in which case both requests were only recorded and are applied now.
        if (expandedForEditing) applyExpansion()
        if (!dismissGesturesEnabled) applyDismissGestures()
    }

    /**
     * Remark edit mode pushes the half-height card to full height (the app's usual full-screen sheet)
     * so the keyboard leaves room for the content; leaving edit mode restores auto height. Swiping
     * down still dismisses unless [setDismissGesturesEnabled] locked it for an unsaved change.
     *
     * Idempotent: the binder re-emits the current mode on every lifecycle restart. The request is
     * recorded even before the sheet exists and applied from [onBottomSheetReady].
     */
    fun setExpandedForEditing(expanded: Boolean) {
        if (expanded == expandedForEditing) return
        expandedForEditing = expanded
        applyExpansion()
    }

    private fun applyExpansion() {
        val sheet = sheetView ?: return
        val behavior = sheetBehavior ?: return
        val expanded = expandedForEditing
        sheet.layoutParams = sheet.layoutParams.apply {
            height = if (expanded) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        // Keep the base fit-to-contents mode so the only stops are EXPANDED and HIDDEN; fitToContents=false
        // would add a HALF_EXPANDED stop a slow drag settles on. skipCollapsed drops the 16:9 auto-peek
        // stop a full-height sheet would otherwise park at instead of dismissing.
        behavior.skipCollapsed = expanded
        behavior.peekHeight = BottomSheetBehavior.PEEK_HEIGHT_AUTO
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    /**
     * Swipe-to-dismiss and scrim taps cannot show the unsaved-changes prompt, so they are switched
     * off while a remark draft differs from the stored remark; close / back still prompt.
     *
     * Like [setExpandedForEditing] the request is recorded even before the sheet exists, so a
     * recreation mid-edit re-applies the lock from [onBottomSheetReady].
     */
    fun setDismissGesturesEnabled(enabled: Boolean) {
        if (enabled == dismissGesturesEnabled) return
        dismissGesturesEnabled = enabled
        applyDismissGestures()
    }

    private fun applyDismissGestures() {
        sheetBehavior?.isHideable = dismissGesturesEnabled
        dialog?.setCanceledOnTouchOutside(dismissGesturesEnabled)
    }

}