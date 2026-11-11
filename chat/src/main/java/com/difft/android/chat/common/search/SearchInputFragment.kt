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
import com.difft.android.chat.recent.ListPaneLayerOpener

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

        binding.edittextSearchInput.setOnClickListener {
            
            if ((activity as? ListPaneLayerOpener)?.openSearchLayer() != true) {
                startActivity(Intent(activity, globalServices.activityProvider.getActivityClass(ActivityType.SEARCH)))
            }
        }

    }

}