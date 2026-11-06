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
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.chat.contacts.contactsdetail.ContactDetailDestination
import com.difft.android.chat.databinding.FragmentEmojiReactionBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.reactions
import javax.inject.Inject

/**
 * Who reacted to a message with one emoji: a tab of [EmojiReactionFragment]. The arguments carry
 * only the message id and the emoji (they are saved with the home screen's state); the reactors
 * are loaded from the message.
 */
@AndroidEntryPoint
class EmojiReactionTabFragment : Fragment() {

    @Inject
    lateinit var wcdb: WCDB

    val binding: FragmentEmojiReactionBinding by viewbind()

    private val mContactsAdapter: EmojiReactionContactorsAdapter by lazy {
        object : EmojiReactionContactorsAdapter() {
            override fun onContactClicked(contact: ContactorModel, position: Int) {
                detailNavigation().push(this@EmojiReactionTabFragment, ContactDetailDestination(contact.id))
            }
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

        val messageId = arguments?.getString(ARG_MESSAGE_ID)
        val emoji = arguments?.getString(ARG_EMOJI)
        if (messageId == null || emoji == null) {
            mContactsAdapter.submitList(emptyList())
            return binding.root
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val contacts = withContext(Dispatchers.IO) {
                    val userIds = wcdb.message.getFirstObject(DBMessageModel.id.eq(messageId))
                        ?.reactions()?.filter { it.emoji == emoji }?.map { it.uid }?.distinct().orEmpty()
                    if (userIds.isEmpty()) emptyList() else wcdb.getContactorsFromAllTable(userIds).distinctBy { contact -> contact.id }
                }
                if (!isAdded || view == null) return@launch
                mContactsAdapter.submitList(contacts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[EmojiReactionTabFragment] error: ${e.stackTraceToString()}" }
            }
        }

        return binding.root
    }

    companion object {
        private const val ARG_MESSAGE_ID = "ARG_MESSAGE_ID"
        private const val ARG_EMOJI = "ARG_EMOJI"

        fun newInstance(messageId: String, emoji: String): EmojiReactionTabFragment =
            EmojiReactionTabFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MESSAGE_ID, messageId)
                    putString(ARG_EMOJI, emoji)
                }
            }
    }
}
