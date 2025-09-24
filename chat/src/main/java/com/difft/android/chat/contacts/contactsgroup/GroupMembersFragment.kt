//package com.difft.android.chat.contacts.contactsgroup
//
//import android.os.Bundle
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import androidx.fragment.app.viewModels
//import com.difft.android.base.fragment.DisposableManageFragment
//import com.difft.android.chat.contacts.contactsall.ContactorsViewModel
//import com.difft.android.chat.databinding.ChatFragmentGroupMembersBinding
//import com.difft.android.chat.common.search.SearchInputViewModel
//import com.difft.android.chat.group.GroupChatViewModel
//
//class GroupMembersFragment : DisposableManageFragment() {
//    private lateinit var binding: ChatFragmentGroupMembersBinding
//    private val searchInputViewModel: SearchInputViewModel by viewModels(ownerProducer = {
//        requireActivity()
//    })
//    private val groupChatViewModel: GroupChatViewModel by viewModels(ownerProducer = {
//        requireActivity()
//    })
//    private val contactorsViewModel: ContactorsViewModel by viewModels(ownerProducer = {
//        requireActivity()
//    })
//
//    override fun onCreateView(
//        inflater: LayoutInflater,
//        container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View? {
//        binding = ChatFragmentGroupMembersBinding.inflate(inflater, container, false)
//        return binding.root
//    }
//
//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//
//
//    }
//}