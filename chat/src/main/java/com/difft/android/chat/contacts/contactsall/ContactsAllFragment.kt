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
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.sampleAfterFirst
import com.difft.android.base.widget.sideBar.SectionDecoration
import com.difft.android.base.widget.sideBar.SideBar
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getSortLetter
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.databinding.ChatFragmentContactsAllBinding
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlow
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import javax.inject.Inject

@AndroidEntryPoint
class ContactsAllFragment : Fragment() {

    @Inject
    lateinit var wcdb: WCDB

    val binding: ChatFragmentContactsAllBinding by viewbind()


    val mAdapter: ContactorsAdapter by lazy {
        object : ContactorsAdapter(globalServices.myId) {
            override fun onContactClicked(contact: ContactorModel, position: Int) {
                // Use ConversationNavigationCallback for dual-pane support
                val navigationCallback = activity as? ConversationNavigationCallback
                navigationCallback?.onContactDetailSelected(contact.id)
                    ?: ContactDetailActivity.startActivity(this@ContactsAllFragment.requireActivity(), contact.id)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        binding.sideBar.setOnTouchingLetterChangedListener(object : SideBar.OnTouchingLetterChangedListener {
            override fun onTouchingLetterChanged(s: String) {
                //该字母首次出现的位置
                val position: Int = mAdapter.getLetterPosition(s)
                if (position != -1) {
//                    binding.recyclerviewContacts.scrollToPosition(position)
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
            .asFlow()
            .onEach { binding.smartRefreshLayout.finishRefresh() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        ContactorUtil.contactsUpdate
            .asFlow()
            .sampleAfterFirst(2000)
            .onEach { initData() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        initData()

        TextSizeUtil.textSizeState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { mAdapter.notifyDataSetChanged() }
            .launchIn(viewLifecycleOwner.lifecycleScope)
        return binding.root
    }

    private fun initData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) {
                wcdb.contactor.allObjects
                    .sortedByPinyin()
            }
            if (!isAdded || view == null) return@launch
            mAdapter.submitList(contacts)
            addLettersDecoration(contacts)
        }
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