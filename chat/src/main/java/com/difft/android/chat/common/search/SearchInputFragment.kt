package com.difft.android.chat.common.search

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.databinding.ChatFragmentSearchInputBinding

class SearchInputFragment : Fragment() {
    private lateinit var binding: ChatFragmentSearchInputBinding
    private val viewModel by viewModels<SearchInputViewModel>(ownerProducer = { requireActivity() })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding = ChatFragmentSearchInputBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

//        refreshClearButton(binding.edittextSearchInput.length())
//        binding.edittextSearchInput.addTextChangedListener {
//            refreshClearButton(it?.length ?: 0)
//
//            viewModel.setInput(it?.toString())
//        }
//        viewModel.setInput(binding.edittextSearchInput.text.toString())
//        binding.buttonClear.setOnClickListener {
//            binding.edittextSearchInput.text = null
//        }

        binding.edittextSearchInput.setOnClickListener {
            startActivity(Intent(activity, globalServices.activityProvider.getActivityClass(ActivityType.SEARCH)))
        }

    }

//    private fun refreshClearButton(inputLength: Int) {
//        val isShowClearButton = inputLength != 0
//
//        binding.buttonClear.animate().apply {
//            cancel()
//
//            val toAlpha = if (isShowClearButton) 1.0f else 0f
//            alpha(toAlpha)
//        }
//    }

}