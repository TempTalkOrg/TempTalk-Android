package com.difft.android.chat.ui

import android.os.Looper
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
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.difft.app.database.models.ContactorModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A translation arriving refreshes the row through `PAYLOAD_TRANSLATE`, which re-renders only the
 * translate block. The row's click and long-press must still deliver the item the row now shows,
 * otherwise the long-press menu decides "translate" vs "off" from the pre-translation instance.
 *
 * Runs the real [ChatMessageAdapter] / [ChatMessageViewHolder.Message] so the DiffUtil payload,
 * the partial bind and the listener wiring are all production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ChatMessageViewHolderTranslateRebindTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var recyclerView: RecyclerView
    private val longClicked = mutableListOf<ChatMessage>()
    private val clicked = mutableListOf<ChatMessage>()

    private val adapter: ChatMessageAdapter by lazy {
        object : ChatMessageAdapter(forWhat = null, contactorCache = MessageContactsCacheUtil()) {
            override fun onItemClick(rootView: View, data: ChatMessage) {
                clicked += data
            }

            override fun onItemLongClick(rootView: View, data: ChatMessage) {
                longClicked += data
            }

            override fun onAvatarClicked(contactor: ContactorModel?) = Unit
            override fun onAvatarLongClicked(contactor: ContactorModel?) = Unit
            override fun onQuoteClicked(quote: Quote) = Unit
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
            adapter = this@ChatMessageViewHolderTranslateRebindTest.adapter
            layoutParams = FrameLayout.LayoutParams(WIDTH_PX, HEIGHT_PX)
        }
        activity.setContentView(recyclerView)
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        unmockkAll()
    }

    private fun message(data: TranslateData?) = TextChatMessage().apply {
        id = "m1"
        authorId = MY_ID
        isMine = true
        timeStamp = TIMESTAMP
        systemShowTimestamp = TIMESTAMP
        message = "hello"
        translateData = data
    }

    private fun submitAndAwait(items: List<ChatMessage>) {
        val committed = AtomicBoolean(false)
        adapter.submitList(items) { committed.set(true) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!committed.get() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        layout()
    }

    private fun layout() {
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, WIDTH_PX, HEIGHT_PX)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun contentContainer(): View {
        val holder = recyclerView.findViewHolderForAdapterPosition(0)
        assertNotNull("row 0 must be bound", holder)
        return holder!!.itemView.findViewById(R.id.contentContainer)
    }

    @Test
    fun `a translate-only rebind hands the translated item to long-press and click`() {
        submitAndAwait(listOf(message(data = null)))
        val holderBefore = recyclerView.findViewHolderForAdapterPosition(0)

        val translated = message(TranslateData(TranslateStatus.ShowCN, "你好", null))
        submitAndAwait(listOf(translated))

        assertSame("the translate payload must reuse the row", holderBefore, recyclerView.findViewHolderForAdapterPosition(0))
        contentContainer().performLongClick()
        contentContainer().performClick()

        assertEquals(1, longClicked.size)
        assertSame(translated, longClicked.single())
        assertEquals(TranslateStatus.ShowCN, (longClicked.single() as TextChatMessage).translateData?.translateStatus)
        assertSame(translated, clicked.single())
    }

    private companion object {
        const val MY_ID = "+10000000"
        const val TIMESTAMP = 1_700_000_000_000L
        const val WIDTH_PX = 1080
        const val HEIGHT_PX = 1920
    }
}
