package com.difft.android.chat.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.chat.databinding.ActivityEmojiReactionBinding
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Reaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.reactions
import org.difft.app.database.wcdb

/**
 * Who reacted to a message, one tab per emoji. A page of the home detail stack or of its shell
 * [EmojiReactionActivity]; the tabs are this page's children, so ViewPager2 restores them with it.
 */
@AndroidEntryPoint
class EmojiReactionFragment : Fragment() {

    companion object {
        private const val ARG_GROUP_ID = "groupId"
        private const val ARG_CONTACT_ID = "contactID"
        private const val ARG_MESSAGE_ID = "messageId"

        fun arguments(forWhat: For, messageId: String): Bundle = Bundle().apply {
            putString(if (forWhat is For.Group) ARG_GROUP_ID else ARG_CONTACT_ID, forWhat.id)
            putString(ARG_MESSAGE_ID, messageId)
        }

        fun newInstance(arguments: Bundle?): EmojiReactionFragment = EmojiReactionFragment().apply { this.arguments = arguments }
    }

    private var _binding: ActivityEmojiReactionBinding? = null
    private val binding: ActivityEmojiReactionBinding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityEmojiReactionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments
        val forWhat: For? = args?.getString(ARG_CONTACT_ID)?.let { For.Account(it) }
            ?: args?.getString(ARG_GROUP_ID)?.let { For.Group(it) }
        val messageId = args?.getString(ARG_MESSAGE_ID)
        if (forWhat == null || messageId == null) {
            L.w { "[EmojiReaction] page without a conversation or message" }
            detailNavigation().pop(this)
            return
        }
        setupBackButton(binding.ibBack)
        applyDetailPageInsets(binding.root)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val reactions = withContext(Dispatchers.IO) {
                    wcdb.message.getFirstObject(DBMessageModel.id.eq(messageId))?.reactions()?.groupBy { it.emoji }
                }
                if (!reactions.isNullOrEmpty() && _binding != null) initViewPager(messageId, reactions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[EmojiReaction] error: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun initViewPager(messageId: String, emojis: Map<String, List<Reaction>>) {
        val titles = emojis.map { it.key + "(" + it.value.size + ")" }
        binding.viewpager.adapter = ReactionTabsAdapter(this, messageId, emojis.keys.toList())

        TabLayoutMediator(binding.tabLayout, binding.viewpager) { tab, position ->
            tab.text = titles[position]
        }.attach()

        binding.tabLayout.addOnTabSelectedListener(object : OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val selected = tab ?: return
                val binding = _binding ?: return
                // Scroll the selected tab to the middle of the strip.
                val scrollX = selected.view.left - (binding.scrollTab.width - selected.view.width) / 2
                binding.scrollTab.smoothScrollTo(scrollX, 0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
    }

    /**
     * One tab per emoji; each tab loads its reactors by message id (no id lists in arguments).
     * Item ids come from the emoji, not the position: after a recreation with a different emoji
     * set (reactions removed meanwhile) a restored tab is reused only for the same emoji.
     */
    internal class ReactionTabsAdapter(
        page: Fragment,
        private val messageId: String,
        private val emojis: List<String>,
    ) : FragmentStateAdapter(page) {
        override fun getItemCount(): Int = emojis.size

        override fun getItemId(position: Int): Long = idOf(emojis[position])

        override fun containsItem(itemId: Long): Boolean = emojis.any { idOf(it) == itemId }

        override fun createFragment(position: Int): Fragment = EmojiReactionTabFragment.newInstance(messageId, emojis[position])

        private fun idOf(emoji: String): Long = emoji.hashCode().toLong()
    }
}

/** Reactions of a message as a detail destination (home detail stack, or its shell Activity). */
class EmojiReactionDestination(private val forWhat: For, private val messageId: String) : DetailDestination {
    override fun newFragment(): Fragment = EmojiReactionFragment.newInstance(EmojiReactionFragment.arguments(forWhat, messageId))
    override fun shellIntent(context: Context): Intent = EmojiReactionActivity.intent(context, forWhat, messageId)
    override val subjectId: String get() = forWhat.id
}
