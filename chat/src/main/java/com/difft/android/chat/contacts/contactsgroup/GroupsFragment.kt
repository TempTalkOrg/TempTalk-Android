package com.difft.android.chat.contacts.contactsgroup

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.chat.databinding.ChatFragmentGroupBinding
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUtil
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
            .startWithItem(true to emptyList())
            .flatMapSingle { Single.fromCallable { wcdb.group.getAllObjects(DBGroupModel.status.eq(0)) } }
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ groups ->
                binding.smartRefreshLayout.finishRefresh()
                mAdapter.submitList(groups)
            }, {
                it.printStackTrace()
            })

        GroupUtil.singleGroupsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ group ->
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


                mAdapter.submitList(list)
            }, {
                it.printStackTrace()
            })

        // Collect text size changes at Fragment level and notify adapter
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TextSizeUtil.textSizeState.collect {
                    mAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private val mAdapter: GroupsAdapter by lazy {
        object : GroupsAdapter() {
            override fun onItemClick(group: GroupModel) {
                GroupChatContentActivity.startActivity(
                    this@GroupsFragment.requireActivity(),
                    group.gid
                )
            }
        }
    }

    private fun syncGroupInfo() {
        lifecycleScope.launch(Dispatchers.IO) {
            GroupUtil.syncAllGroupAndAllGroupMembers(requireContext(), forceFetch = true, syncMembers = true)
        }
    }
}