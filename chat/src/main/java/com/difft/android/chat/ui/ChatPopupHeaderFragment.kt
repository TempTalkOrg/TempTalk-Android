package com.difft.android.chat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.utils.RxUtil
import com.difft.android.chat.databinding.ChatFragmentHeaderPopupBinding
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Simplified header fragment for ChatPopupActivity.
 * Shows contact name, archive time, back button, and maximize button.
 */
@AndroidEntryPoint
class ChatPopupHeaderFragment : Fragment() {

    private var _binding: ChatFragmentHeaderPopupBinding? = null
    private val binding get() = _binding!!

    private val chatViewModel: ChatMessageViewModel by activityViewModels()
    private val chatSettingViewModel: ChatSettingViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ChatFragmentHeaderPopupBinding.inflate(inflater, container, false)

        // Back button - close the popup
        binding.ibBack.setOnClickListener {
            activity?.finish()
        }

        // Maximize button - open full ChatActivity and close popup
        binding.ibMaximize.setOnClickListener {
            val popupActivity = activity as? ChatPopupActivity
            popupActivity?.maximizeToFullActivity()
        }

        // Observe contact name
        chatViewModel.chatUIData
            .onEach { chatUIData ->
                binding.textviewNickname.text = chatUIData.contact?.getDisplayNameForUI() ?: ""
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        // Observe archive time
        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach { conversationSet ->
                updateArchiveTimeUI(conversationSet.messageExpiry)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        return binding.root
    }

    /**
     * Update archive time UI display
     */
    private fun updateArchiveTimeUI(archiveTime: Long) {
        if (archiveTime > 0L) {
            binding.textviewTimer.visibility = View.VISIBLE
            val text = " [" + archiveTime.toArchiveTimeDisplayText() + "]"
            binding.textviewTimer.text = text
        } else {
            binding.textviewTimer.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}