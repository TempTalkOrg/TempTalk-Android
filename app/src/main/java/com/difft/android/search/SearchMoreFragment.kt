package com.difft.android.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.R
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.databinding.ActivitySearchMoreBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.difft.app.database.models.ContactorModel
import javax.inject.Inject






@AndroidEntryPoint
class SearchMoreFragment : Fragment(), SearchSelectionPage {

    @Inject
    lateinit var source: GlobalSearchSource

    private var _binding: ActivitySearchMoreBinding? = null
    private val binding: ActivitySearchMoreBinding get() = _binding!!

    private val type: Int by lazy { requireArguments().getInt(ARG_TYPE, -1) }
    private val key: String by lazy { requireArguments().getString(ARG_KEY).orEmpty() }

    private val contactsAdapter: SearchContactAdapter by lazy {
        object : SearchContactAdapter() {
            override fun onContactClicked(contact: ContactorModel, position: Int) {
                ChatActivity.startActivity(requireActivity(), contact.id)
            }
        }.apply { setOrUpdateSearchKey(key) }
    }

    private val groupsAdapter: SearchGroupsAdapter by lazy {
        object : SearchGroupsAdapter() {
            override fun onItemClick(group: GroupUIData) {
                GroupChatContentActivity.startActivity(requireActivity(), group.gid)
            }
        }.apply { setOrUpdateSearchKey(key) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivitySearchMoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackButton(binding.ibBack)
        applyDetailPageInsets(binding.root)
        binding.recyclerviewResult.apply {
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = null
        }
        when (type) {
            SearchMoreActivity.SEARCH_TYPE_CONTACT -> load(R.string.search_more_contacts, contactsAdapter) { contactsAdapter.submitList(source.contacts(key)) }
            SearchMoreActivity.SEARCH_TYPE_GROUP -> load(R.string.search_more_groups, groupsAdapter) { groupsAdapter.submitList(source.groups(key)) }
        }
    }

    override fun showSelection(selectedId: String?) {
        contactsAdapter.selectedId = selectedId
        groupsAdapter.selectedId = selectedId
    }

    
    private fun load(titleRes: Int, adapter: RecyclerView.Adapter<*>, fetch: suspend () -> Unit) {
        binding.tvTitle.text = getString(titleRes)
        binding.recyclerviewResult.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch { fetch() }
    }

    companion object {
        private const val ARG_TYPE = "type"
        private const val ARG_KEY = "key"

        fun arguments(type: Int, key: String): Bundle = bundleOf(ARG_TYPE to type, ARG_KEY to key)

        fun newInstance(arguments: Bundle?): SearchMoreFragment = SearchMoreFragment().apply { this.arguments = arguments }
    }
}


class SearchMoreDestination(private val type: Int, private val key: String) : DetailDestination {
    override fun newFragment(): Fragment = SearchMoreFragment.newInstance(SearchMoreFragment.arguments(type, key))
    override fun shellIntent(context: Context): Intent = SearchMoreActivity.intent(context, type, key)
    override val pageKey: String get() = javaClass.name + ":" + type + ":" + key
}
