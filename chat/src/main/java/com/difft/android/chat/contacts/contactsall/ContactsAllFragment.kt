package com.difft.android.chat.contacts.contactsall

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.sampleAfterFirst
import com.difft.android.base.widget.sideBar.SectionDecoration
import com.difft.android.base.widget.sideBar.SideBar
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getSortLetter
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.recent.DualPaneSelectionListener
import com.difft.android.chat.databinding.ChatFragmentContactsAllBinding
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import javax.inject.Inject

@AndroidEntryPoint
class ContactsAllFragment : Fragment(), DualPaneSelectionListener {

    @Inject
    lateinit var wcdb: WCDB

    val binding: ChatFragmentContactsAllBinding by viewbind()


    val mAdapter: ContactorsAdapter by lazy {
        object : ContactorsAdapter(globalServices.myId) {
            override fun onContactClicked(contact: ContactorModel, position: Int) {
                // Use ConversationNavigationCallback for dual-pane support
                val navigationCallback = activity as? ConversationNavigationCallback
                if (navigationCallback != null) {
                    navigationCallback.onContactDetailSelected(contact.id)
                    if (navigationCallback.isDualPaneMode) {
                        selectedId = contact.id
                    }
                } else {
                    ContactDetailActivity.startActivity(this@ContactsAllFragment.requireActivity(), contact.id)
                }
            }
        }
    }

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

        binding.sideBar.setOnTouchingLetterChangedListener(object : SideBar.OnTouchingLetterChangedListener {
            override fun onTouchingLetterChanged(s: String) {
                val position: Int = mAdapter.getLetterPosition(s)
                if (position != -1) {
                    (binding.recyclerviewContacts.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
                }
            }
        })

        binding.smartRefreshLayout.setEnableRefresh(true)

        binding.smartRefreshLayout.setOnRefreshListener {
            ContactorUtil.fetchAndSaveContactors(true)
        }

        binding.recyclerviewContacts.apply {
            this.adapter = mAdapter
            this.layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = null
        }

        ContactorUtil.getContactsStatusUpdate
            .onEach { binding.smartRefreshLayout.finishRefresh() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        // Load contacts on every STARTED lifecycle (handles config changes / split-screen)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loadContacts()
            }
        }

        // Reload on incremental contact changes (friend added/removed/updated)
        ContactorUtil.contactsUpdate
            .sampleAfterFirst(2000)
            .onEach { loadContacts() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        TextSizeUtil.textSizeState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { mAdapter.notifyDataSetChanged() }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        val navigationCallback = activity as? ConversationNavigationCallback
        if (navigationCallback?.isDualPaneMode == true) {
            mAdapter.selectedId = navigationCallback.currentSelectedConversationId
        }
    }

    override fun updateDualPaneSelection(selectedId: String?) {
        mAdapter.selectedId = selectedId
    }

    private suspend fun loadContacts() {
        val contacts = withContext(Dispatchers.IO) {
            wcdb.contactor.allObjects
                .sortedByPinyin()
        }
        mAdapter.submitList(contacts)
        addLettersDecoration(contacts)
    }

    private var decoration: SectionDecoration? = null
    private fun addLettersDecoration(it: List<ContactorModel>) {
        decoration?.let {
            binding.recyclerviewContacts.removeItemDecoration(it)
        }

        decoration = SectionDecoration(requireContext(), object : SectionDecoration.DecorationCallback {
            override fun getGroupId(position: Int): Long {
                return if (position >= 0 && position < it.size) it[position].getDisplayNameForUI().getSortLetter().hashCode().toLong() else -1
            }

            override fun getGroupFirstLine(position: Int): String {
                return if (position >= 0 && position < it.size) it[position].getDisplayNameForUI().getSortLetter() else "#"
            }
        })

        decoration?.let {
            binding.recyclerviewContacts.addItemDecoration(it)
        }
    }
}