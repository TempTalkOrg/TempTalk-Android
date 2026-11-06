package com.difft.android.chat.group

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.base.utils.pane.detailKeyboard
import com.difft.android.base.utils.pane.hideDetailKeyboardBeforeLeaving
import com.difft.android.base.utils.pane.isInDetailStack
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsdetail.ContactDetailDestination
import com.difft.android.chat.databinding.ActivitySearchGroupMemberBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.difft.app.database.members
import org.difft.app.database.models.GroupMemberContactorModel
import javax.inject.Inject

/**
 * Search in a group's members. A page of the home detail stack or of its shell
 * [GroupMembersSearchActivity]. The query is view state: restored after a fold, it searches again.
 */
@AndroidEntryPoint
class GroupMembersSearchFragment : Fragment() {

    @Inject
    lateinit var groupUtil: GroupUtil

    private var _binding: ActivitySearchGroupMemberBinding? = null
    private val binding: ActivitySearchGroupMemberBinding get() = _binding!!

    private val groupId: String by lazy { arguments?.getString(ARG_GROUP_ID).orEmpty() }

    private val searchFlow = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val membersAdapter: SearchGroupMemberAdapter by lazy {
        object : SearchGroupMemberAdapter() {
            override fun onContactClicked(contact: GroupMemberContactorModel, position: Int) {
                val id = contact.id ?: return
                hideDetailKeyboardBeforeLeaving()
                detailNavigation().push(this@GroupMembersSearchFragment, ContactDetailDestination(id))
            }
        }.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivitySearchGroupMemberBinding.inflate(inflater, container, false)
        // Hosted, the keyboard opens through detailKeyboard (fresh open only), not on every composition.
        if (isInDetailStack()) binding.searchInput.autoFocus = false
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    @OptIn(FlowPreview::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (groupId.isEmpty()) {
            L.w { "[GroupMembersSearch] page without a group id" }
            detailNavigation().pop(this)
            return
        }
        setupBackButton(binding.ibBack)
        applyDetailPageInsets(binding.root, ime = true)
        detailKeyboard(showOnFirstOpen = true, focus = { binding.searchInput.focusAndShowKeyboard() }, savedInstanceState = savedInstanceState)

        binding.searchInput.onQueryChanged = { searchFlow.tryEmit(it.trim()) }
        binding.searchInput.onClear = { searchFlow.tryEmit("") }

        viewLifecycleOwner.lifecycleScope.launch {
            searchFlow
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect {
                    if (it.isNotEmpty()) search(it) else showNoResults(getString(R.string.search_messages_default_tips))
                }
        }

        binding.recyclerviewChatHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = null
            adapter = membersAdapter
        }
    }

    private fun search(key: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val group = groupUtil.getSingleGroupInfo(groupId)
                val results = group?.members?.filter { member ->
                    member.displayName?.contains(key, true) == true ||
                        member.remark?.contains(key, true) == true ||
                        member.id?.contains(key, true) == true
                } ?: emptyList()
                if (results.isEmpty()) {
                    showNoResults(getString(R.string.search_no_results_found))
                } else {
                    binding.recyclerviewChatHistory.visibility = View.VISIBLE
                    membersAdapter.submitList(results)
                    binding.tvTips.visibility = View.GONE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNoResults(getString(R.string.search_no_results_found))
                L.w { "[GroupMembersSearch] search error: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun showNoResults(tipContent: String) {
        val binding = _binding ?: return
        binding.recyclerviewChatHistory.visibility = View.GONE
        membersAdapter.submitList(emptyList())
        binding.tvTips.visibility = View.VISIBLE
        binding.tvTips.text = tipContent
    }

    companion object {
        private const val ARG_GROUP_ID = "groupId"
        private const val SEARCH_DEBOUNCE_MS = 300L

        fun arguments(groupId: String): Bundle = Bundle().apply { putString(ARG_GROUP_ID, groupId) }

        fun newInstance(groupId: String): GroupMembersSearchFragment =
            GroupMembersSearchFragment().apply { arguments = arguments(groupId) }
    }
}

/** A group's member search as a detail destination (home detail stack, or its shell Activity). */
class GroupMembersSearchDestination(private val groupId: String) : DetailDestination {
    override fun newFragment(): Fragment = GroupMembersSearchFragment.newInstance(groupId)
    override fun shellIntent(context: Context): Intent = GroupMembersSearchActivity.intent(context, groupId)
    override val subjectId: String get() = groupId
}
