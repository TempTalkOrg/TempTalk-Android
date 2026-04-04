package com.difft.android.setting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.chat.R
import com.difft.android.databinding.ActivityChatSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Fragment for chat settings
 * Can be displayed in both Activity (single-pane) and dual-pane mode
 */
@AndroidEntryPoint
class ChatSettingsFragment : Fragment() {

    companion object {
        fun newInstance() = ChatSettingsFragment()
    }

    private var _binding: ActivityChatSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var userManager: UserManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityChatSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupBackButton(binding.ibBack)
        initView()
    }

    override fun onResume() {
        super.onResume()
        // Update voice playback speed display when returning from settings
        updateVoicePlaybackSpeedDisplay()
    }

    private fun initView() {
        // Save to photos setting - local only, no server sync
        binding.switchSaveToPhotos.isChecked = (userManager.getUserData()?.saveToPhotos == true)
        binding.switchSaveToPhotos.setOnClickListener {
            val newValue = binding.switchSaveToPhotos.isChecked
            userManager.update { saveToPhotos = newValue }
        }

        // Voice playback speed setting
        updateVoicePlaybackSpeedDisplay()
        binding.clVoicePlaybackSpeed.setOnClickListener {
            VoicePlaybackSpeedSettingsActivity.startActivity(requireActivity())
        }
    }

    private fun updateVoicePlaybackSpeedDisplay() {
        val speed = userManager.getUserData()?.voicePlaybackSpeed ?: 1.0f
        binding.tvVoicePlaybackSpeedValue.text = when (speed) {
            1.5f -> getString(R.string.voice_speed_1_5x)
            2.0f -> getString(R.string.voice_speed_2x)
            else -> getString(R.string.voice_speed_1x)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

