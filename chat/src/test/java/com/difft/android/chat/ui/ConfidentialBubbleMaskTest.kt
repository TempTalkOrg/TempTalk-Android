package com.difft.android.chat.ui

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Mode
import java.util.concurrent.atomic.AtomicBoolean






@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ConfidentialBubbleMaskTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var recyclerView: RecyclerView
    private val itemClicks = mutableListOf<ChatMessage>()
    private val quoteClicks = mutableListOf<Quote>()

    private val adapter: ChatMessageAdapter by lazy {
        object : ChatMessageAdapter(forWhat = For.Account(PEER), contactorCache = MessageContactsCacheUtil()) {
            override fun onItemClick(rootView: View, data: ChatMessage) {
                itemClicks += data
            }

            override fun onItemLongClick(rootView: View, data: ChatMessage) = Unit
            override fun onAvatarClicked(contactor: ContactorModel?) = Unit
            override fun onAvatarLongClicked(contactor: ContactorModel?) = Unit
            override fun onQuoteClicked(quote: Quote) {
                quoteClicks += quote
            }

            override fun onReactionClick(message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) = Unit
            override fun onReactionLongClick(message: ChatMessage, emoji: String) = Unit
            override fun onTranslateRetry(message: TextChatMessage) = Unit
            override fun onE2eeHeaderClick() = Unit
        }
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
        val activity = controller.get()
        recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@ConfidentialBubbleMaskTest.adapter
            layoutParams = FrameLayout.LayoutParams(WIDTH_PX, HEIGHT_PX)
        }
        activity.setContentView(recyclerView)
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        unmockkAll()
    }

    private fun message(confidential: Boolean) = TextChatMessage().apply {
        id = if (confidential) "c1" else "n1"
        authorId = PEER
        isMine = false
        timeStamp = TIMESTAMP
        systemShowTimestamp = TIMESTAMP
        message = "reply"
        quote = Quote(1L, PEER, "quoted text", null)
        mode = if (confidential) Mode.CONFIDENTIAL_VALUE else Mode.NORMAL_VALUE
    }

    private fun show(item: ChatMessage) {
        val committed = AtomicBoolean(false)
        adapter.submitList(listOf(item)) { committed.set(true) }
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

    private fun container(): ChatMessageContainerView {
        val holder = recyclerView.findViewHolderForAdapterPosition(0)
        assertNotNull("row 0 must be bound", holder)
        return holder!!.itemView.findViewById(R.id.contentContainer)
    }

    
    private fun tap(container: ChatMessageContainerView, target: View) {
        val at = IntArray(2).also(target::getLocationInWindow)
        val origin = IntArray(2).also(container::getLocationInWindow)
        val x = (at[0] - origin[0] + target.width / 2).toFloat()
        val y = (at[1] - origin[1] + target.height / 2).toFloat()
        val down = SystemClock.uptimeMillis()
        container.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        container.dispatchTouchEvent(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a tap on a masked quote opens the confidential message`() {
        show(message(confidential = true))
        val container = container()
        assertTrue(container.masked)
        assertNull("no ring: only the content is blurred", container.foreground)

        tap(container, container.findViewById(R.id.quoteZone))

        assertEquals(listOf("c1"), itemClicks.map { it.id })
        assertTrue("the masked quote must not act", quoteClicks.isEmpty())
    }

    @Test
    fun `a row rebound to a normal message is unmasked and its quote acts again`() {
        show(message(confidential = true))
        show(message(confidential = false))
        val container = container()
        assertFalse(container.masked)
        assertNull(container.foreground)

        tap(container, container.findViewById(R.id.quoteZone))

        assertEquals(1, quoteClicks.size)
    }

    private companion object {
        const val MY_ID = "+10000000001"
        const val PEER = "+10000000002"
        const val TIMESTAMP = 1_700_000_000_000L
        const val WIDTH_PX = 1080
        const val HEIGHT_PX = 2400
    }
}
