package com.difft.android.chat.contacts.contactsdetail

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint

/**
 * BottomSheet dialog for displaying contact details.
 * Uses [ContactDetailFragment] as content.
 */
@AndroidEntryPoint
class ContactDetailBottomSheetDialogFragment : BaseBottomSheetDialogFragment() {

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
}