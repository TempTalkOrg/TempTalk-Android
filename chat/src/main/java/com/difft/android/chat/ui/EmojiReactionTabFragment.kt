package com.difft.android.chat.ui

import android.os.Bundle
import com.difft.android.base.log.lumberjack.L
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import org.difft.app.database.getContactorsFromAllTable
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.databinding.FragmentEmojiReactionBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import javax.inject.Inject

@AndroidEntryPoint
class EmojiReactionTabFragment : Fragment() {

    @Inject
    lateinit var wcdb: WCDB

    private var userIds: ArrayList<String>? = null

    val binding: FragmentEmojiReactionBinding by viewbind()

    private val mContactsAdapter: EmojiReactionContactorsAdapter by lazy {
        object : EmojiReactionContactorsAdapter() {
            override fun onContactClicked(contact: ContactorModel, position: Int) {
                ContactDetailActivity.startActivity(requireActivity(), contact.id)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            userIds = it.getStringArrayList(ARG_TAB_USER_IDS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        binding.recyclerviewContacts.apply {
            this.adapter = mContactsAdapter
            this.layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = null
        }

        if (!userIds.isNullOrEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val contacts = withContext(Dispatchers.IO) {
                        wcdb.getContactorsFromAllTable(userIds!!).distinctBy { contact -> contact.id }
                    }
                    if (!isAdded || view == null) return@launch
                    mContactsAdapter.submitList(contacts)
                } catch (e: Exception) {
                    L.w { "[EmojiReactionTabFragment] error: ${e.stackTraceToString()}" }
                }
            }
        } else {
            mContactsAdapter.submitList(emptyList())
        }

        return binding.root
    }

    companion object {
        private const val ARG_TAB_USER_IDS = "ARG_TAB_USER_IDS"

        fun newInstance(ids: ArrayList<String>?): EmojiReactionTabFragment {
            val fragment = EmojiReactionTabFragment()
            val args = Bundle()
            args.putStringArrayList(ARG_TAB_USER_IDS, ids)
            fragment.arguments = args
            return fragment
        }
    }
}
