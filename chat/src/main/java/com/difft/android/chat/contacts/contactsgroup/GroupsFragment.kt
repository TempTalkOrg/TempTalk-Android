package com.difft.android.chat.contacts.contactsgroup

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.chat.databinding.ChatFragmentGroupBinding
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlow
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.GroupModel
import javax.inject.Inject

@AndroidEntryPoint
class GroupsFragment : Fragment() {
    val binding: ChatFragmentGroupBinding by viewbind()

    @Inject
    lateinit var wcdb: WCDB
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return binding.root
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.smartRefreshLayout.setEnableRefresh(true)

        binding.smartRefreshLayout.setOnRefreshListener {
            syncGroupInfo()
        }

        binding.recyclerviewGroup.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = mAdapter
            itemAnimator = null
        }


        GroupUtil.getGroupsStatusUpdate
            .asFlow()
            .onEach {
                val groups = withContext(Dispatchers.IO) {
                    wcdb.group.getAllObjects(DBGroupModel.status.eq(0))
                }
                if (!isAdded || view == null) return@onEach
                binding.smartRefreshLayout.finishRefresh()
                submitSortedList(groups)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        // Initial load
        viewLifecycleOwner.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                wcdb.group.getAllObjects(DBGroupModel.status.eq(0))
            }
            if (!isAdded || view == null) return@launch
            submitSortedList(groups)
        }

        GroupUtil.singleGroupsUpdate
            .asFlow()
            .onEach { group ->
                val list = mAdapter.currentList.toMutableList()
                if (group.status == 0) {
                    val pos = list.indexOfFirst { group.gid == it.gid }
                    if (pos >= 0) {
                        list[pos] = group
                    } else {
                        list.add(group)
                    }
                } else {
                    list.removeIf { it.gid == group.gid }
                }
                submitSortedList(list)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        TextSizeUtil.textSizeState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { mAdapter.notifyDataSetChanged() }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private val mAdapter: GroupsAdapter by lazy {
        object : GroupsAdapter() {
            override fun onItemClick(group: GroupModel) {
                // Use ConversationNavigationCallback for dual-pane support
                val navigationCallback = activity as? ConversationNavigationCallback
                if (navigationCallback != null) {
                    navigationCallback.onGroupConversationSelected(group.gid)
                } else {
                    GroupChatContentActivity.startActivity(
                        this@GroupsFragment.requireActivity(),
                        group.gid
                    )
                }
            }
        }
    }

    private fun syncGroupInfo() {
        lifecycleScope.launch(Dispatchers.IO) {
            GroupUtil.syncAllGroupAndAllGroupMembers(requireContext(), forceFetch = true, syncMembers = true)
        }
    }

    private fun submitSortedList(list: List<GroupModel>) {
        mAdapter.submitList(list.sortedBy { it.name })
    }
}