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
import com.difft.android.chat.contacts.data.ContactorUtil.getEntryPoint
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
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBGroupMemberContactorModel
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
        // Merge friend source (contactor) + weak-pending source, de-dup by id.
        // Weak uids come from the weak table; their DISPLAY info resolves through the value chain
        // (groupMember-first, snapshot last) instead of reading the snapshot directly, so a stale
        // snapshot avatar never shadows the live (avatar-less) value the detail screen shows. This
        // keeps the list and the detail screen consistent.
        // Capture the context OUTSIDE the IO block. Calling requireContext() inside withContext(IO)
        // throws IllegalStateException if the fragment detaches while the IO work is in flight.
        val ctx = requireContext()
        val pendingRepo = ctx.getEntryPoint().getPendingRemovalContactRepository()
        val contacts = withContext(Dispatchers.IO) {
            val friends = wcdb.contactor.allObjects
            val weakExpire = pendingRepo.getAllExpireAt()       // uid -> expireAt (countdown subtitle)
            val weakUids = weakExpire.keys.toList()

            // Proactively pull the live value for weak uids that have NO groupMember row yet (only a
            // snapshot). One network round-trip lands an avatar-less stub into groupMember (active
            // account) so the snapshot avatar stops shadowing the live value the detail screen shows.
            //
            // Convergence — NOT via any contactsUpdate emission (ContactorUtil.fetchContactors never
            // emits contactsUpdate); convergence is via groupMember persistence, not re-trigger:
            //   - Active account: fetchContactors writes a groupMember stub, so the uid lands in
            //     knownInGroupMember next time → it drops out of missing → no further fetch.
            //   - Account gone: fetchContactors returns empty and writes no stub, so the uid stays in
            //     missing. The list page may re-issue the fetch on a later contactsUpdate (throttled
            //     2s). This is an accepted boundary — the set is small and the user does not notice.
            //     We deliberately do NOT record a "gone" set: that would let a transient network
            //     blip mark a still-active uid as gone, freezing its stale snapshot avatar for the
            //     rest of the Fragment lifecycle with no self-heal. Leaving it in missing means a
            //     recovered network simply resolves the live value on the next pass.
            if (weakUids.isNotEmpty()) {
                val friendIds = friends.map { it.id }.toSet()
                val knownInGroupMember = wcdb.groupMemberContactor
                    .getAllObjects(DBGroupMemberContactorModel.id.`in`(weakUids))
                    .map { it.id }.toSet()
                val missing = weakUids.filter { it !in knownInGroupMember && it !in friendIds }
                if (missing.isNotEmpty()) {
                    ContactorUtil.fetchContactors(missing, ctx)
                }
            }

            val weakContacts = if (weakUids.isEmpty()) emptyList()
            else wcdb.getContactorsFromAllTable(weakUids)        // groupMember-first, snapshot last
            val merged = (friends + weakContacts).distinctBy { it.id }.sortedByPinyin()
            // Carry each contactor's expireAt on the list item so a weak-state change (e.g. friend
            // restored: expireAt has -> null) is part of DiffUtil contents and rebinds the row.
            merged.map { ContactListItem(it, weakExpire[it.id]) }
        }
        mAdapter.submitList(contacts)
        addLettersDecoration(contacts)
    }

    private var decoration: SectionDecoration? = null
    private fun addLettersDecoration(it: List<ContactListItem>) {
        decoration?.let {
            binding.recyclerviewContacts.removeItemDecoration(it)
        }

        decoration = SectionDecoration(requireContext(), object : SectionDecoration.DecorationCallback {
            override fun getGroupId(position: Int): Long {
                return if (position >= 0 && position < it.size) it[position].contactor.getDisplayNameForUI().getSortLetter().hashCode().toLong() else -1
            }

            override fun getGroupFirstLine(position: Int): String {
                return if (position >= 0 && position < it.size) it[position].contactor.getDisplayNameForUI().getSortLetter() else "#"
            }
        })

        decoration?.let {
            binding.recyclerviewContacts.addItemDecoration(it)
        }
    }
}