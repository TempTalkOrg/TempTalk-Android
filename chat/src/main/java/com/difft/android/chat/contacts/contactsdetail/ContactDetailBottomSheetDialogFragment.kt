package com.difft.android.chat.contacts.contactsdetail

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.difft.android.chat.contacts.cardPreloadEntryPoint
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Input
import com.difft.android.chat.contacts.showCardAfterLocalRead
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.AndroidEntryPoint





@AndroidEntryPoint
class ContactDetailBottomSheetDialogFragment : BaseBottomSheetDialogFragment() {

    companion object {
        internal const val TAG = "ContactDetailBottomSheet"
        private const val ARG_CUSTOM_ID = "ARG_CUSTOM_ID"
        private const val ARG_CONTACT_ID = "ARG_CONTACT_ID"
        private const val ARG_CONTACT_NAME = "ARG_CONTACT_NAME"
        private const val ARG_SOURCE_TYPE = "ARG_SOURCE_TYPE"
        private const val ARG_SOURCE = "ARG_SOURCE"
        private const val ARG_AVATAR = "ARG_AVATAR"
        private const val ARG_JOINED_AT = "ARG_JOINED_AT"
        private const val ARG_OVER_CONVERSATION = "ARG_OVER_CONVERSATION"
        private const val ARG_FULL_CONVERSATION = "ARG_FULL_CONVERSATION"
        private const val ARG_PRELOAD_KEY = "ARG_PRELOAD_KEY"

        private fun newInstance(
            contactId: String,
            contactName: String? = null,
            sourceType: String? = null,
            source: String? = null,
            avatar: String? = null,
            joinedAt: String? = null,
            overConversation: String? = null,
            customId: String? = null,
            fullConversation: Boolean = false,
            preloadKey: String? = null,
        ): ContactDetailBottomSheetDialogFragment {
            return ContactDetailBottomSheetDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONTACT_ID, contactId)
                    putString(ARG_CUSTOM_ID, customId)
                    putString(ARG_CONTACT_NAME, contactName)
                    putString(ARG_SOURCE_TYPE, sourceType)
                    putString(ARG_SOURCE, source)
                    putString(ARG_AVATAR, avatar)
                    putString(ARG_JOINED_AT, joinedAt)
                    putString(ARG_OVER_CONVERSATION, overConversation)
                    putBoolean(ARG_FULL_CONVERSATION, fullConversation)
                    putString(ARG_PRELOAD_KEY, preloadKey)
                }
            }
        }

        




        fun show(
            activity: FragmentActivity,
            contactId: String,
            contactName: String? = null,
            sourceType: String? = null,
            source: String? = null,
            avatar: String? = null,
            joinedAt: String? = null,
            overConversation: String? = null,
            customId: String? = null,
            fullConversation: Boolean = false,
        ) {
            val manager = activity.supportFragmentManager
            if (manager.isStateSaved) return
            val input = Input(contactId, customId, contactName, avatar, joinedAt, sourceType, source)
            present(activity, activity, manager, input) { key ->
                newInstance(contactId, contactName, sourceType, source, avatar, joinedAt, overConversation, customId,
                    fullConversation, key)
            }
        }

        




        fun show(
            fragment: Fragment,
            contactId: String,
            contactName: String? = null,
            sourceType: String? = null,
            source: String? = null,
            avatar: String? = null,
            joinedAt: String? = null
        ) {
            val manager = fragment.childFragmentManager
            if (manager.isStateSaved) return
            val input = Input(contactId, contactName = contactName, avatar = avatar, joinedAt = joinedAt,
                sourceType = sourceType, source = source)
            
            
            present(fragment.requireContext(), fragment, manager, input) { key ->
                newInstance(contactId, contactName, sourceType, source, avatar, joinedAt, fullConversation = true,
                    preloadKey = key)
            }
        }

        
        private fun present(
            context: Context,
            owner: LifecycleOwner,
            manager: FragmentManager,
            input: Input,
            sheet: (preloadKey: String?) -> ContactDetailBottomSheetDialogFragment,
        ) {
            val entry = context.cardPreloadEntryPoint()
            showCardAfterLocalRead(owner, manager, TAG, entry.cardPreloads(),
                read = { entry.cardPreloader().contact(input) }) { key -> sheet(key).show(manager, TAG) }
        }
    }

    
    val overConversation: String? get() = arguments?.getString(ARG_OVER_CONVERSATION)

    val opensFullConversation: Boolean get() = arguments?.getBoolean(ARG_FULL_CONVERSATION) == true

    override fun getContentFragment(): Fragment {
        return ContactDetailFragment.newInstance(
            contactId = arguments?.getString(ARG_CONTACT_ID) ?: "",
            customId = arguments?.getString(ARG_CUSTOM_ID),
            contactName = arguments?.getString(ARG_CONTACT_NAME),
            sourceType = arguments?.getString(ARG_SOURCE_TYPE),
            source = arguments?.getString(ARG_SOURCE),
            avatar = arguments?.getString(ARG_AVATAR),
            joinedAt = arguments?.getString(ARG_JOINED_AT),
            preloadKey = arguments?.getString(ARG_PRELOAD_KEY),
        )
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ContactDetailActivity)?.takeUnless { it.isChangingConfigurations }?.finish()
    }

    
    override fun getPeekHeightRatio(): Float = 0f

    
    override fun isExpandable(): Boolean = false

    private var sheetView: View? = null
    private var sheetBehavior: BottomSheetBehavior<*>? = null
    private var expandedForEditing = false
    private var dismissGesturesEnabled = true

    override fun onBottomSheetReady(sheet: View, behavior: BottomSheetBehavior<*>) {
        sheetView = sheet
        sheetBehavior = behavior
        
        
        if (expandedForEditing) applyExpansion()
        if (!dismissGesturesEnabled) applyDismissGestures()
    }

    







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
        
        
        
        behavior.skipCollapsed = expanded
        behavior.peekHeight = BottomSheetBehavior.PEEK_HEIGHT_AUTO
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    






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
