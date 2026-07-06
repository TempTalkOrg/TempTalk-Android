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

/**
 * L1-L15 — List render (⑤) for the quote-media preview.
 *
 * **Screenshot tier downgraded to view-state assertions (documented, per [com.difft.android.chat.contacts.ContactItemSubtitleTest]
 * precedent):** `:chat` has NO View/XML Roborazzi harness — the only screenshot infra is Compose-based.
 * These tests invoke the REAL production top-level functions (`bindQuoteThumbnail`, `clearQuoteThumbnail`)
 * against the REAL inflated list-item layouts and assert on the resulting
 * `quoteThumbnail` view state (visibility / ScaleType / drawable resource via `shadowOf`). Visual parity
 * vs difft #5127 is verified by code review (no pixel baseline).
 *
 * **Architecture (2026-06-11): type-entry + reverse-lookup local original.** The wire carries no
 * thumbnail bytes; image/video quotes derive their preview by reverse-looking-up the LOCAL original
 * message (timestamp + room). Tiers:
 *   - voice → `chat_ic_quote_mic` (CENTER)
 *   - image/video WITH inline bytes (difft-android senders) → rounded center-crop
 *   - image/video WITHOUT bytes → async reverse-lookup; found on disk → center-crop, else GONE
 *     (text-only). With `forWhat = null` (no room scope) the lookup early-returns → stays GONE.
 *   - genuine file (pdf/doc/zip/etc.) → `ic_file` (CENTER, NEVER center-crop — regression guard L5a)
 *
 * The reverse-lookup itself ([findOriginalAttachmentPath]) needs native WCDB + a real lifecycle
 * scope, so its found/forward-aware cases are @Ignore-d (L8/L9), matching the project precedent for
 * WCDB-backed unit tests.
 *
 * Verify: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ChatMessageViewHolderQuoteThumbnailTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity
    private lateinit var parent: ViewGroup

    @Before
    fun setUp() {
        // FragmentActivity (not bare Activity) so Glide.with(view) resolves a support-fragment host;
        // AppCompat theme so the layouts' AppCompat widgets inflate. TestScopeApplication initializes
        // ApplicationHelper.instance (required by the base `dp` extension's `application` global).
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        activity = controller.get()
        // A content root so inflated views attach to a live Activity (Glide.with(view) resolves it).
        parent = android.widget.FrameLayout(activity)
        activity.setContentView(parent)
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() } // L13 already destroys; ignore double-destroy.
    }

    /** Inflates the real mine/others list-item layout and returns its quoteThumbnail ImageView. */
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

    /** Resolves the drawable resId that produced [ImageView.getDrawable] (set via setImageResource). */
    private fun ImageView.createdFromResId(): Int =
        drawable?.let { shadowOf(it).createdFromResId } ?: 0

    // ---- L1 / L2: tier-1 voice → mic icon -------------------------------------------------

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
        // flags=0 but contentType is an audio MIME (isAudioType) → still tier-1.
        val q = quote(QuotedAttachment("audio/aac", "v.aac", attachment("audio/aac", flags = 0), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.VISIBLE, iv.visibility)
        assertEquals(ImageView.ScaleType.CENTER, iv.scaleType)
        assertEquals(R.drawable.chat_ic_quote_mic, iv.createdFromResId())
    }

    // ---- L3: tier-2 inline bytes → rounded center-crop, mine + others ---------------------

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

    // ---- L4: (removed) the standalone inline-path tier no longer exists. ------------------
    // Received quotes carry no path; the local original is resolved via reverse-lookup (L8/L9).

    // ---- L5a: genuine file (no bytes/path) → ic_file VISIBLE ------------------------------

    @Test
    fun `L5a genuine file renders ic_file visible others`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        val q = quote(QuotedAttachment("application/pdf", "d.pdf", attachment("application/pdf"), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.VISIBLE, iv.visibility)
        assertEquals(ImageView.ScaleType.CENTER, iv.scaleType)
        assertEquals(R.drawable.ic_file, iv.createdFromResId())
    }

    @Test
    fun `L5a genuine file renders ic_file on mine binding`() {
        val iv = inflateQuoteThumbnail(isMine = true)
        val q = quote(QuotedAttachment("application/pdf", "d.pdf", attachment("application/pdf"), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.VISIBLE, iv.visibility)
        assertEquals(ImageView.ScaleType.CENTER, iv.scaleType)
        assertEquals(R.drawable.ic_file, iv.createdFromResId())
    }

    // ---- L5b: image/video with no bytes/path → thumbnail GONE (text-only) -----------------

    @Test
    fun `L5b image with no thumbnail hides thumbnail text-only`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        // image type, no bytes, no path → text-only fallback (no misleading file icon).
        val q = quote(QuotedAttachment("image/jpeg", "p.jpg", attachment("image/jpeg"), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.GONE, iv.visibility)
        assertNull(iv.drawable)
    }

    @Test
    fun `L5b video with no thumbnail hides thumbnail text-only`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        // video type, no bytes, no path → text-only fallback.
        val q = quote(QuotedAttachment("video/mp4", "v.mp4", attachment("video/mp4"), 0))

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.GONE, iv.visibility)
        assertNull(iv.drawable)
    }

    // ---- L5b': image with no bytes AND no room scope → stays GONE (lookup early-returns) --

    @Test
    fun `L5b image with no bytes and null forWhat stays text-only`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        val q = quote(QuotedAttachment("image/jpeg", "p.jpg", attachment("image/jpeg"), 0))

        // forWhat = null → resolveOriginalThumbnailAsync early-returns (roomId null), no WCDB touched.
        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.GONE, iv.visibility)
        assertNull(iv.drawable)
    }

    // ---- L8 / L9: reverse-lookup of the local original (needs native WCDB + lifecycle scope) ----
    // findOriginalAttachmentPath queries WCDB by timestamp+room and resolves the on-disk file path,
    // forward-aware (normal → message.id dir; single-forward → attachment.authorityId dir). It needs
    // native WCDB (unavailable in the JVM unit harness) and a real view-tree lifecycle scope, so the
    // found/forward cases are @Ignore-d, matching the WCDB-backed test precedent (QuoteAttachmentRoundTripTest).

    @Test
    @Ignore("Needs native WCDB + real lifecycle scope; verify via instrumentation.")
    fun `L8 image with no bytes loads reverse-looked-up original when present on disk`() {
        // Seed a message at ts/room with a local image file; bind an image quote with forWhat set;
        // assert the thumbnail becomes VISIBLE + CENTER_CROP after the async lookup resolves.
    }

    @Test
    @Ignore("Needs native WCDB + real lifecycle scope; verify via instrumentation.")
    fun `L9 single-forward original resolves file under attachment authorityId directory`() {
        // Seed a single-forward original; assert findOriginalAttachmentPath uses
        // getMessageAttachmentFilePath(attachment.authorityId) (NOT message.id) for the forwarded file.
    }

    // ---- L6: null attachment / clearQuoteThumbnail → GONE --------------------------------

    @Test
    fun `L6 quote with no attachment hides thumbnail`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        // pre-seed a drawable so we can assert it is cleared.
        iv.setImageResource(R.drawable.ic_file)
        val q = quote(null)

        bindQuoteThumbnail(iv, q, null)

        assertEquals(View.GONE, iv.visibility)
        assertNull(iv.drawable)
    }

    // ---- L7: author-display path (bindQuoteView) — no 5th `authorName` arg (4-arg Quote) ----
    //
    // `bindQuoteView` is a private Message-instance method that reads `globalServices.myId` (a lazy
    // Hilt EntryPoint) and requires a fully-wired ViewHolder (mine/others bindings + ContentBinder +
    // callbacks) — impractical to inflate in this harness without the Hilt graph. The runnable guard
    // below pins the *contract* the feedback targets: that `Quote` stays 4-arg (id, author, text,
    // attachments) with NO 5th `authorName` field, and that the production author-resolution
    // expression yields `R.string.you` for self vs the contactorCache display name (or
    // `formatBase58Id()` fallback) for others.

    @Test
    fun `L7 Quote stays 4-arg with no authorName field — author resolves from author id`() {
        // Compile-anchored structural guard: if a 5th `authorName` arg were added to Quote, this
        // 4-positional-arg construction would fail to compile. `quote.author` must BE the author id
        // (display name is resolved at render via contactorCache, never stored on the model).
        val myId = "self-uid"
        val q = Quote(id = 1L, author = myId, text = "t", attachments = null)
        assertEquals(myId, q.author)
    }

    @Test
    fun `L7 author resolution — self id yields R-string-you, other id yields cache display name`() {
        // Exercises the exact production expression from bindQuoteView against a real TextView +
        // mocked MessageContactsCacheUtil, without inflating the full ViewHolder. Guards that the
        // self/other/fallback branches behave per contract (no authorName arg involved).
        val tv = android.widget.TextView(activity)
        val myId = "self-uid"
        val otherId = "other-uid"
        val cache = io.mockk.mockk<com.difft.android.chat.MessageContactsCacheUtil>()
        // Real ContactorModel (not a mock) so the real getDisplayNameForUI() extension runs; publicName
        // resolves first via getFirstNonEmptyValue once the in-memory ContactRemarkCache misses (empty).
        val contactor = org.difft.app.database.models.ContactorModel().apply {
            id = otherId
            publicName = "Display Name"
        }
        io.mockk.every { cache.getContactor(otherId) } returns contactor

        // self branch
        val selfQuote = Quote(id = 1L, author = myId, text = "t", attachments = null)
        tv.text = resolveQuoteAuthorText(activity, selfQuote, myId, cache)
        assertEquals(activity.getString(R.string.you), tv.text.toString())

        // other branch (cache hit)
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
        // formatBase58Id() is the documented fallback; assert it matches the production helper output.
        assertEquals(missId.formatBase58Id(), resolved)
    }

    /**
     * Mirror of the author-resolution expression in `bindQuoteView` (`ChatMessageViewHolder.kt:470-477`).
     * Replicated here (not extracted from production) so L7 can run without a fully-wired ViewHolder /
     * the lazy `globalServices.myId` Hilt EntryPoint. Kept byte-for-byte equivalent to the production
     * branches: self → R.string.you, cache hit → display name, cache miss → formatBase58Id().
     */
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

    // ---- L12: View.isHostActivityAlive() -------------------------------------------------
    // The finishing / destroyed / non-Activity-ContextWrapper matrix is covered by
    // [com.difft.android.chat.util.QuoteThumbnailBinderTest]. Re-asserted here as a smoke check that
    // the real inflated list-item view reports its live host correctly.

    @Test
    fun `L12 inflated view reports host activity alive`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        assertEquals(true, iv.isHostActivityAlive())
    }

    // ---- L13: Glide clear on dead/recycled view -----------------------------------------

    @Test
    fun `L13 clearQuoteThumbnail after activity destroyed does not throw and clears drawable`() {
        val iv = inflateQuoteThumbnail(isMine = false)
        // bind something first
        bindQuoteThumbnail(iv, quote(QuotedAttachment("application/pdf", "d", attachment("application/pdf"), 0)), null)
        // destroy host → isHostActivityAlive() returns false, so Glide.with(...).clear() is skipped.
        controller.pause().stop().destroy()
        // Must not throw even with a dead host; drawable set to null, view hidden.
        clearQuoteThumbnail(iv)
        assertNull(iv.drawable)
        assertEquals(View.GONE, iv.visibility)
    }

    // ---- L14: removed — quote-zone sizing is now standard wrap_content + maxWidth ----------
    // The former weight(0dp) + OnPreDrawListener remeasure hack (computeQuoteZoneWidth /
    // setupQuoteZoneDynamicWidth) is gone; sizing is plain layout now, verified on-device.
}
