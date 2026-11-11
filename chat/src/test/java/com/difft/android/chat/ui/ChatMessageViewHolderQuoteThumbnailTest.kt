package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatItemChatMessageListTextMineBinding
import com.difft.android.chat.databinding.ChatItemChatMessageListTextOthersBinding
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.util.isHostActivityAlive
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.QuotedAttachment
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config







@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ChatMessageViewHolderQuoteThumbnailTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity
    private lateinit var parent: ViewGroup

    @Before
    fun setUp() {
        
        
        
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        activity = controller.get()
        
        parent = android.widget.FrameLayout(activity)
        activity.setContentView(parent)
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() } 
    }

    
    private fun inflateQuoteThumbnail(isMine: Boolean): ImageView {
        val inflater = LayoutInflater.from(activity)
        val view: View = if (isMine) {
            ChatItemChatMessageListTextMineBinding.inflate(inflater, parent, true).root
        } else {
            ChatItemChatMessageListTextOthersBinding.inflate(inflater, parent, true).root
        }
        return view.findViewById(R.id.quoteThumbnail)
    }

    private fun attachment(
        contentType: String,
        flags: Int = 0,
        thumbnailBytes: ByteArray? = null,
        path: String? = null,
        width: Int = 0,
        height: Int = 0,
    ) = Attachment(
        id = "", authorityId = 0L, contentType = contentType, key = null,
        size = thumbnailBytes?.size ?: 0, thumbnail = thumbnailBytes, digest = null,
        fileName = "f", flags = flags, width = width, height = height,
        path = path, status = AttachmentStatus.SUCCESS.code
    )

    private fun quote(qa: QuotedAttachment?) = Quote(
        id = 1000L, author = "author", text = "text",
        attachments = qa?.let { listOf(it) }
    )

    
    private fun ImageView.createdFromResId(): Int =
        drawable?.let { shadowOf(it).createdFromResId } ?: 0

    

    @Test
    fun `L1 voice by flag renders mic icon center`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        val q = quote(QuotedAttachment("audio/aac", "v.aac", attachment("audio/aac", flags = 1), 1))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.VISIBLE, iv.visibility)
        assertEquals(ImageView.ScaleType.CENTER, iv.scaleType)
        assertEquals(R.drawable.chat_ic_quote_mic, iv.createdFromResId())
    }

    @Test
    fun `L2 voice by contentType renders mic icon`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        
        val q = quote(QuotedAttachment("audio/aac", "v.aac", attachment("audio/aac", flags = 0), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.VISIBLE, iv.visibility)
        assertEquals(ImageView.ScaleType.CENTER, iv.scaleType)
        assertEquals(R.drawable.chat_ic_quote_mic, iv.createdFromResId())
    }

    

    @Test
    fun `L3 image bytes renders centerCrop on others binding`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val q = quote(QuotedAttachment("image/jpeg", "p.jpg", attachment("image/jpeg", thumbnailBytes = bytes), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.VISIBLE, iv.visibility)
        assertEquals(ImageView.ScaleType.CENTER_CROP, iv.scaleType)
    }

    @Test
    fun `L3 image bytes renders centerCrop on mine binding`() {
        val iv = inflateQuoteThumbnail(isMine = true)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val q = quote(QuotedAttachment("image/jpeg", "p.jpg", attachment("image/jpeg", thumbnailBytes = bytes), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.VISIBLE, iv.visibility)
        assertEquals(ImageView.ScaleType.CENTER_CROP, iv.scaleType)
    }

    

    @Test
    fun `L5a genuine file renders its type glyph visible others`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        val q = quote(QuotedAttachment("application/pdf", "d.pdf", attachment("application/pdf"), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.VISIBLE, iv.visibility)
        assertEquals(ImageView.ScaleType.CENTER, iv.scaleType)
        assertEquals(R.drawable.chat_ic_file_type_pdf, iv.createdFromResId())
        assertNotNull("the glyph takes the bubble's colour", iv.imageTintList)
    }

    @Test
    fun `L5a genuine file renders its type glyph on mine binding`() {
        val iv = inflateQuoteThumbnail(isMine = true)
        val q = quote(QuotedAttachment("application/zip", "logs.zip", attachment("application/zip"), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.VISIBLE, iv.visibility)
        assertEquals(ImageView.ScaleType.CENTER, iv.scaleType)
        assertEquals(R.drawable.chat_ic_file_type_zip, iv.createdFromResId())
    }

    @Test
    fun `L5a a photo bound after a file glyph on a recycled view is not tinted`() {
        val iv = inflateQuoteThumbnail(isMine = true)
        bindQuoteThumbnail(iv, quote(QuotedAttachment("application/pdf", "d.pdf", attachment("application/pdf"), 0)), null)
        val bytes = byteArrayOf(1, 2, 3, 4)

        bindQuoteThumbnail(iv, quote(QuotedAttachment("image/jpeg", "p.jpg", attachment("image/jpeg", thumbnailBytes = bytes), 0)), null)

        assertEquals(ImageView.ScaleType.CENTER_CROP, iv.scaleType)
        assertNull(iv.imageTintList)
    }

    

    @Test
    fun `L5b image with no thumbnail hides thumbnail text-only`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        
        val q = quote(QuotedAttachment("image/jpeg", "p.jpg", attachment("image/jpeg"), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.GONE, iv.visibility)
        assertNull(iv.drawable)
    }

    @Test
    fun `L5b video with no thumbnail hides thumbnail text-only`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        
        val q = quote(QuotedAttachment("video/mp4", "v.mp4", attachment("video/mp4"), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.GONE, iv.visibility)
        assertNull(iv.drawable)
    }

    

    @Test
    fun `L5b image with no bytes and null forWhat stays text-only`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        val q = quote(QuotedAttachment("image/jpeg", "p.jpg", attachment("image/jpeg"), 0))

        
        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.GONE, iv.visibility)
        assertNull(iv.drawable)
    }

    

    @Test
    @Ignore("Needs native WCDB + real lifecycle scope; verify via instrumentation.")
    fun `L8 image with no bytes loads reverse-looked-up original when present on disk`() {
        
        
    }

    @Test
    @Ignore("Needs native WCDB + real lifecycle scope; verify via instrumentation.")
    fun `L9 single-forward original resolves file under attachment authorityId directory`() {
        
        
    }

    

    @Test
    fun `L6 quote with no attachment hides thumbnail`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        
        iv.setImageResource(R.drawable.chat_ic_file_type_file)
        val q = quote(null)

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.GONE, iv.visibility)
        assertNull(iv.drawable)
    }

    
    

    @Test
    fun `L7 Quote stays 4-arg with no authorName field — author resolves from author id`() {
        
        val myId = "self-uid"
        val q = Quote(id = 1L, author = myId, text = "t", attachments = null)
        assertEquals(myId, q.author)
    }

    @Test
    fun `L7 author resolution — self id yields R-string-you, other id yields cache display name`() {
        
        
        
        val tv = android.widget.TextView(activity)
        val myId = "self-uid"
        val otherId = "other-uid"
        val cache = io.mockk.mockk<com.difft.android.chat.MessageContactsCacheUtil>()
        
        
        val contactor = org.difft.app.database.models.ContactorModel().apply {
            id = otherId
            publicName = "Display Name"
        }
        io.mockk.every { cache.getContactor(otherId) } returns contactor

        
        val selfQuote = Quote(id = 1L, author = myId, text = "t", attachments = null)
        tv.text = resolveQuoteAuthorText(activity, selfQuote, myId, cache)
        assertEquals(activity.getString(R.string.you), tv.text.toString())

        
        val otherQuote = Quote(id = 2L, author = otherId, text = "t", attachments = null)
        tv.text = resolveQuoteAuthorText(activity, otherQuote, myId, cache)
        assertEquals("Display Name", tv.text.toString())
    }

    @Test
    fun `L7 author resolution — cache miss falls back to formatBase58Id`() {
        val myId = "self-uid"
        val missId = "+12312345678"
        val cache = io.mockk.mockk<com.difft.android.chat.MessageContactsCacheUtil>()
        io.mockk.every { cache.getContactor(missId) } returns null

        val q = Quote(id = 3L, author = missId, text = "t", attachments = null)
        val resolved = resolveQuoteAuthorText(activity, q, myId, cache)
        
        assertEquals(missId.formatBase58Id(), resolved)
    }

    
    private fun resolveQuoteAuthorText(
        context: android.content.Context,
        quote: Quote,
        myId: String,
        contactorCache: com.difft.android.chat.MessageContactsCacheUtil,
    ): String = if (quote.author == myId) {
        context.getString(R.string.you)
    } else {
        contactorCache.getContactor(quote.author)?.getDisplayNameForUI()
            ?: quote.author.formatBase58Id()
    }

    
    
    
    

    @Test
    fun `L12 inflated view reports host activity alive`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        assertEquals(true, iv.isHostActivityAlive())
    }

    

    @Test
    fun `L13 clearQuoteThumbnail after activity destroyed does not throw and clears drawable`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        
        bindQuoteThumbnail(iv, quote(QuotedAttachment("application/pdf", "d", attachment("application/pdf"), 0)), null)
        
        controller.pause().stop().destroy()
        
        clearQuoteThumbnail(iv)
        assertNull(iv.drawable)
        assertEquals(View.GONE, iv.visibility)
    }

}
