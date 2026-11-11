package com.difft.android.chat.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.R
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.messages.TestScopeApplication
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Quote
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.difft.app.database.models.ContactorModel
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.atomic.AtomicBoolean






@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ChatAvatarColumnTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var recyclerView: RecyclerView

    private fun adapterFor(forWhat: For?) = object : ChatMessageAdapter(forWhat = forWhat, contactorCache = MessageContactsCacheUtil()) {
        override fun onItemClick(rootView: View, data: ChatMessage) = Unit
        override fun onItemLongClick(rootView: View, data: ChatMessage) = Unit
        override fun onAvatarClicked(contactor: ContactorModel?) = Unit
        override fun onAvatarLongClicked(contactor: ContactorModel?) = Unit
        override fun onQuoteClicked(quote: Quote) = Unit
        override fun onReactionClick(message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) = Unit
        override fun onReactionLongClick(message: ChatMessage, emoji: String) = Unit
        override fun onTranslateRetry(message: TextChatMessage) = Unit
        override fun onE2eeHeaderClick() = Unit
    }

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        val globalServicesMock = mockk<GlobalHiltEntryPoint>(relaxed = true)
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns MY_ID
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        unmockkAll()
    }

    private fun message(id: String, author: String, showName: Boolean, index: Long) = TextChatMessage().apply {
        this.id = id
        authorId = author
        isMine = author == MY_ID
        timeStamp = TIMESTAMP + index
        systemShowTimestamp = TIMESTAMP + index
        message = "hello $id"
        this.showName = showName
    }

    private fun show(forWhat: For?, items: List<ChatMessage>) {
        val activity = controller.get()
        val adapter = adapterFor(forWhat)
        recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = adapter
            layoutParams = FrameLayout.LayoutParams(WIDTH_PX, HEIGHT_PX)
        }
        activity.setContentView(recyclerView)
        val committed = AtomicBoolean(false)
        adapter.submitList(items) { committed.set(true) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!committed.get() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, WIDTH_PX, HEIGHT_PX)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun row(position: Int): View {
        val holder = recyclerView.findViewHolderForAdapterPosition(position)
        assertNotNull("row $position must be bound", holder)
        return holder!!.itemView
    }

    private fun View.visibilityOf(id: Int): Int? = findViewById<View>(id)?.visibility

    private fun View.bubbleGoneStartDp(): Int {
        val params = findViewById<View>(R.id.contentContainer).layoutParams as ConstraintLayout.LayoutParams
        return Math.round(params.goneStartMargin / resources.displayMetrics.density)
    }

    @Test
    fun `a one-to-one chat shows no sender beside or above the bubbles`() {
        show(For.Account(PEER), listOf(message("a", PEER, showName = true, index = 0)))
        val row = row(0)
        assertEquals(View.GONE, row.visibilityOf(R.id.imageview_avatar))
        assertEquals(View.GONE, row.visibilityOf(R.id.cl_name))
        assertEquals(26, row.bubbleGoneStartDp())
        assertEquals(false, row.findViewById<ChatMessageContainerView>(R.id.contentContainer).avatarColumn)
    }

    @Test
    fun `a group shows the avatar and name on the first bubble of a run and keeps the column below it`() {
        show(
            For.Group(GROUP),
            listOf(message("a", PEER, showName = true, index = 0), message("b", PEER, showName = false, index = 1)),
        )
        assertEquals(View.VISIBLE, row(0).visibilityOf(R.id.imageview_avatar))
        assertEquals(View.VISIBLE, row(0).visibilityOf(R.id.cl_name))
        assertEquals(View.INVISIBLE, row(1).visibilityOf(R.id.imageview_avatar))
        assertEquals(View.GONE, row(1).visibilityOf(R.id.cl_name))
        assertEquals(46, row(0).bubbleGoneStartDp())
        assertEquals(46, row(1).bubbleGoneStartDp())
        
        val first = row(0)
        val nameTop = first.findViewById<View>(R.id.cl_name).top
        assertEquals(nameTop, first.findViewById<View>(R.id.imageview_avatar).top)
        assertTrue(first.findViewById<View>(R.id.contentContainer).top > nameTop)
        
        assertEquals(true, row(0).findViewById<ChatMessageContainerView>(R.id.contentContainer).avatarColumn)
    }

    @Test
    fun `a chat-history view without a scope keeps the sender column`() {
        show(null, listOf(message("a", PEER, showName = true, index = 0)))
        assertEquals(View.VISIBLE, row(0).visibilityOf(R.id.imageview_avatar))
    }

    @Test
    fun `the user's own bubbles never carry an avatar`() {
        show(For.Group(GROUP), listOf(message("a", MY_ID, showName = true, index = 0)))
        assertEquals(null, row(0).visibilityOf(R.id.imageview_avatar))
        assertEquals(true, (row(0) as ViewGroup).findViewById<View>(R.id.contentContainer) is ChatMessageContainerView)
    }

    private companion object {
        const val MY_ID = "+10000000001"
        const val PEER = "+10000000002"
        const val GROUP = "group-1"
        const val TIMESTAMP = 1_700_000_000_000L
        const val WIDTH_PX = 1080
        const val HEIGHT_PX = 2400
    }
}
