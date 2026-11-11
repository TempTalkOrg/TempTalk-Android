package com.difft.android.chat.contacts.groupcard

import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.Fragment
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardViewModel
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class GroupCardBottomSheetDialogFragment : BaseBottomSheetDialogFragment() {
    override fun getContentFragment(): Fragment = GroupCardFragment.newInstance(
        arguments?.getString(GroupCardViewModel.ARG_GROUP_ID).orEmpty(),
        arguments?.getString(GroupCardViewModel.ARG_PRELOAD_KEY),
    )

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? GroupCardActivity)?.takeUnless { it.isChangingConfigurations }?.finish()
    }

    companion object {
        const val TAG = "GroupCardBottomSheet"

        fun newInstance(gid: String, preloadKey: String? = null): GroupCardBottomSheetDialogFragment =
            GroupCardBottomSheetDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(GroupCardViewModel.ARG_GROUP_ID, gid)
                    putString(GroupCardViewModel.ARG_PRELOAD_KEY, preloadKey)
                }
            }
    }
}
