package com.difft.android.chat.ui

import android.os.Bundle
import com.difft.android.base.widget.floatAsTopBarButton
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.difft.android.chat.databinding.ChatFragmentHeaderPopupBinding
import com.difft.android.chat.setting.archive.bindCompactExpiry
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach





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

        binding.ibBack.floatAsTopBarButton(leading = true)
        binding.ibMaximize.floatAsTopBarButton(leading = false)

        
        binding.ibBack.setOnClickListener {
            activity?.finish()
        }

        
        binding.ibMaximize.setOnClickListener {
            val popupActivity = activity as? ChatPopupActivity
            popupActivity?.maximizeToFullActivity()
        }

        
        chatViewModel.chatUIData
            .onEach { chatUIData ->
                binding.textviewNickname.text = chatUIData.contact?.getDisplayNameForUI() ?: ""
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        
        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach { conversationSet ->
                updateArchiveTimeUI(conversationSet.messageExpiry)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        return binding.root
    }

    


    private fun updateArchiveTimeUI(archiveTime: Long) {
        bindCompactExpiry(binding.timerRow, binding.textviewTimer, archiveTime)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}