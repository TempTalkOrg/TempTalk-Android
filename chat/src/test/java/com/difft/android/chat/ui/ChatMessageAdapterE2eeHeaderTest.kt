package com.difft.android.chat.ui

import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.R
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.EncryptionHeaderChatMessage
import com.difft.android.chat.message.NotifyChatMessage
import com.difft.android.chat.messages.TestScopeApplication
import org.difft.app.database.models.ContactorModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import difft.android.messageserialization.model.Quote
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T2-20..T2-24 — [ChatMessageAdapter.VIEW_TYPE_E2EE_HEADER] plumbing
 * (`getItemViewType`/`onCreateViewHolder`/click wiring) and [EncryptionHeaderChatMessage.equals]
 * against the REAL production [ChatMessageAdapter] / [ChatMessageViewHolder.Notify].
 *
 * Only [EncryptionHeaderChatMessage] / [NotifyChatMessage] rows are submitted (never
 * [com.difft.android.chat.message.TextChatMessage]) — both reuse the `Notify` ViewHolder shell,
 * which has no Hilt-global dependency, unlike `ChatMessageViewHolder.Message` (reads
 * `globalServices.myId`). This mirrors `RecentChatAdapterFooterTest`'s scoping rationale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [28])
class ChatMessageAdapterE2eeHeaderTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity
    private lateinit var recyclerView: RecyclerView
    private var headerClickCount = 0

    private val adapter: ChatMessageAdapter by lazy {
        object : ChatMessageAdapter(forWhat = null, contactorCache = MessageContactsCacheUtil()) {
            override fun onItemClick(rootView: View, data: ChatMessage) = Unit
            override fun onItemLongClick(rootView: View, data: ChatMessage) = Unit
            override fun onAvatarClicked(contactor: ContactorModel?) = Unit
            override fun onAvatarLongClicked(contactor: ContactorModel?) = Unit
            override fun onQuoteClicked(quote: Quote) = Unit
            override fun onReactionClick(message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) = Unit
            override fun onReactionLongClick(message: ChatMessage, emoji: String) = Unit
            override fun onE2eeHeaderClick() {
                headerClickCount++
            }
        }
    }

    private fun header(isNonFriendVariant: Boolean = false) = EncryptionHeaderChatMessage(isNonFriendVariant)

    private fun notify(id: String): NotifyChatMessage = NotifyChatMessage().apply {
        this.id = id
        authorId = ""
    }

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        activity = controller.get()
        recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@ChatMessageAdapterE2eeHeaderTest.adapter
            layoutParams = FrameLayout.LayoutParams(WIDTH_PX, HEIGHT_PX)
        }
        activity.setContentView(recyclerView)
    }

    private fun submitAndAwait(items: List<ChatMessage>) {
        val committed = AtomicBoolean(false)
        adapter.submitList(items) { committed.set(true) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!committed.get() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun layoutRecyclerView() {
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, WIDTH_PX, HEIGHT_PX)
        shadowOf(Looper.getMainLooper()).idle()
    }

    // T2-20 — getItemViewType returns VIEW_TYPE_E2EE_HEADER (203), fires before Confidential/Notify branches
    @Test
    fun `T2-20 getItemViewType returns header type for EncryptionHeaderChatMessage`() {
        submitAndAwait(listOf(header(), notify("n1")))

        assertEquals(ChatMessageAdapter.VIEW_TYPE_E2EE_HEADER, adapter.getItemViewType(0))
        assertEquals(ChatMessageAdapter.VIEW_TYPE_NOTIFY, adapter.getItemViewType(1))
    }

    // T2-21 — onCreateViewHolder(VIEW_TYPE_E2EE_HEADER) returns a Notify holder with tv_e2ee_header_content
    @Test
    fun `T2-21 header view type creates Notify holder with header content textview present`() {
        submitAndAwait(listOf(header()))
        layoutRecyclerView()

        val holder = recyclerView.findViewHolderForAdapterPosition(0)

        assertTrue(holder is ChatMessageViewHolder.Notify)
        assertNotNull(holder!!.itemView.findViewById<View>(R.id.tv_e2ee_header_content))
    }

    // T2-22 — tapping the bound header row invokes onE2eeHeaderClick exactly once
    @Test
    fun `T2-22 clicking the bound header row invokes onE2eeHeaderClick once`() {
        submitAndAwait(listOf(header()))
        layoutRecyclerView()

        val holder = recyclerView.findViewHolderForAdapterPosition(0)!!
        holder.itemView.performClick()

        assertEquals(1, headerClickCount)
    }

    // T2-23 — a plain NotifyChatMessage row stays non-clickable (no cross-viewType leakage)
    @Test
    fun `T2-23 plain notify row is not clickable and never invokes onE2eeHeaderClick`() {
        submitAndAwait(listOf(notify("n1")))
        layoutRecyclerView()

        val holder = recyclerView.findViewHolderForAdapterPosition(0)!!
        assertFalse(holder.itemView.isClickable)

        holder.itemView.performClick()
        assertEquals(0, headerClickCount)
    }

    // T2-24 — equals() distinguishes isNonFriendVariant so DiffUtil rebinds on a variant flip
    @Test
    fun `T2-24 EncryptionHeaderChatMessage equals is false across variant flip with same id`() {
        val friendVariant = header(isNonFriendVariant = false)
        val nonFriendVariant = header(isNonFriendVariant = true)

        assertEquals(friendVariant.id, nonFriendVariant.id)
        assertFalse(friendVariant == nonFriendVariant)
        assertFalse(friendVariant.hashCode() == nonFriendVariant.hashCode())
    }

    // T2-25 — friend-variant header content embeds the lock icon as a leading ImageSpan
    // (emoji-style), not a compound drawable — compound drawables vertical-center
    // across the whole (possibly multi-line) TextView instead of pinning to the first line.
    @Test
    fun `T2-25 friend variant header content embeds lock icon as leading ImageSpan not compound drawable`() {
        submitAndAwait(listOf(header()))
        layoutRecyclerView()

        val holder = recyclerView.findViewHolderForAdapterPosition(0)!!
        val textView = holder.itemView.findViewById<AppCompatTextView>(R.id.tv_e2ee_header_content)
        val spanned = textView.text as Spanned

        val imageSpans = spanned.getSpans(0, spanned.length, ImageSpan::class.java)
        assertTrue("expected a leading ImageSpan for the lock icon", imageSpans.isNotEmpty())

        val compoundDrawables = textView.compoundDrawablesRelative
        assertTrue("must not use compound drawables — ImageSpan replaces them", compoundDrawables.all { it == null })
    }

    // T2-26 — non-friend-variant header content also embeds the lock icon as a leading ImageSpan
    @Test
    fun `T2-26 non-friend variant header content embeds lock icon as leading ImageSpan`() {
        submitAndAwait(listOf(header(isNonFriendVariant = true)))
        layoutRecyclerView()

        val holder = recyclerView.findViewHolderForAdapterPosition(0)!!
        val textView = holder.itemView.findViewById<AppCompatTextView>(R.id.tv_e2ee_header_content)
        val spanned = textView.text as Spanned

        val imageSpans = spanned.getSpans(0, spanned.length, ImageSpan::class.java)
        assertTrue("expected a leading ImageSpan for the lock icon", imageSpans.isNotEmpty())
    }

    // T2-27 — both variants end with the blue+bold "Learn more" suffix; only the base copy differs
    @Test
    fun `T2-27 non-friend variant also appends blue bold learn-more suffix`() {
        submitAndAwait(listOf(header(isNonFriendVariant = true)))
        layoutRecyclerView()

        val holder = recyclerView.findViewHolderForAdapterPosition(0)!!
        val textView = holder.itemView.findViewById<AppCompatTextView>(R.id.tv_e2ee_header_content)
        val spanned = textView.text as Spanned
        val learnMore = textView.context.getString(com.difft.android.base.R.string.e2ee_learn_more)

        assertTrue("text must end with the learn-more label", spanned.toString().endsWith(learnMore))
        val suffixStart = spanned.length - learnMore.length
        assertTrue(
            "learn-more suffix must carry a ForegroundColorSpan",
            spanned.getSpans(suffixStart, spanned.length, ForegroundColorSpan::class.java).isNotEmpty(),
        )
        assertTrue(
            "learn-more suffix must carry a bold StyleSpan",
            spanned.getSpans(suffixStart, spanned.length, StyleSpan::class.java).any { it.style == android.graphics.Typeface.BOLD },
        )
    }

    private companion object {
        const val WIDTH_PX = 1000
        const val HEIGHT_PX = 2000
    }
}
