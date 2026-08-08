package com.difft.android.chat.ui

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.dp
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import com.difft.android.chat.databinding.ChatItemChatMessageListTextMineBinding
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * T1-1 … T1-12 — the out-of-bubble send-failed status row of an outgoing message.
 *
 * **Screenshot tier downgraded to view-state / geometry assertions** (documented, per the
 * [ChatMessageViewHolderQuoteThumbnailTest] precedent): `:chat` has NO View/XML Roborazzi harness —
 * the only screenshot infra is Compose-based. These tests invoke the REAL production top-level
 * function [bindSendFailStatusRow] against the REAL inflated `chat_item_chat_message_list_text_mine`
 * layout and assert on the resulting view state plus post-`measure`+`layout` geometry. Colour-value
 * parity with the design is verified on-device (no pixel baseline).
 *
 * T1-10 / T1-11 are framework-assumption tests: they pin ConstraintLayout's GONE-collapse behaviour
 * plus `layout_goneMarginTop`, which is the single structural risk of re-anchoring `cl_translate` /
 * `cl_speech_to_text` onto the (usually GONE) status row. If that assumption ever breaks, the
 * translate / speech-to-text panels silently shift by 5dp on every outgoing message.
 *
 * Verify: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class SendFailStatusRowTest {

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

    private fun inflateMine(): ChatItemChatMessageListTextMineBinding =
        ChatItemChatMessageListTextMineBinding.inflate(LayoutInflater.from(activity), parent, true)

    private fun statusRow(): View = inflateMine().llSendFailStatus

    /** Measures the item at a realistic phone width so ConstraintLayout resolves the chain. */
    private fun measureAndLayout(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun View.boundsInRoot(root: View): Rect {
        var x = 0
        var y = 0
        var v: View = this
        while (v !== root) {
            x += v.left
            y += v.top
            v = v.parent as View
        }
        return Rect(x, y, x + width, y + height)
    }

    // ---- T1-1 … T1-4: visibility per sendStatus -------------------------------------------

    @Test
    fun `T1-1 failed status shows the row as a clickable hit target`() {
        val row = statusRow()

        bindSendFailStatusRow(row, SendType.SentFailed.rawValue) { }

        assertEquals(View.VISIBLE, row.visibility)
        assertTrue(row.isClickable)
    }

    @Test
    fun `T1-2 sending status hides the row`() {
        val row = statusRow()

        bindSendFailStatusRow(row, SendType.Sending.rawValue) { }

        assertEquals(View.GONE, row.visibility)
    }

    @Test
    fun `T1-3 sent status hides the row`() {
        val row = statusRow()

        bindSendFailStatusRow(row, SendType.Sent.rawValue) { }

        assertEquals(View.GONE, row.visibility)
    }

    @Test
    fun `T1-4 null and unknown sendStatus hide the row`() {
        val nullRow = statusRow()
        bindSendFailStatusRow(nullRow, null) { }
        assertEquals(View.GONE, nullRow.visibility)

        val unknownRow = statusRow()
        bindSendFailStatusRow(unknownRow, 99) { }
        assertEquals(View.GONE, unknownRow.visibility)
    }

    // ---- T1-5: recycle leaves no retry affordance behind ----------------------------------

    @Test
    fun `T1-5 rebinding a recycled row clears visibility listener and clickable`() {
        val row = statusRow()
        var taps = 0

        bindSendFailStatusRow(row, SendType.SentFailed.rawValue) { taps++ }
        row.performClick()
        assertEquals(1, taps)

        // Same View, now bound to a successfully-sent message (RecyclerView reuse).
        bindSendFailStatusRow(row, SendType.Sent.rawValue) { taps++ }

        assertEquals(View.GONE, row.visibility)
        assertFalse(row.isClickable)
        row.performClick()
        assertEquals(1, taps) // listener was cleared — no extra tap delivered
    }

    // ---- T1-6: tap forwards exactly once -------------------------------------------------

    @Test
    fun `T1-6 tapping the failed row invokes the retry callback exactly once`() {
        val row = statusRow()
        var taps = 0

        bindSendFailStatusRow(row, SendType.SentFailed.rawValue) { taps++ }
        row.performClick()

        assertEquals(1, taps)
    }

    // ---- T1-7: visual contract (text / colour token / sizes) ------------------------------

    @Test
    fun `T1-7 row renders the retry label in the t_error token at 12sp with a 12dp icon`() {
        val binding = inflateMine()
        val label = binding.tvSendFailText
        val icon = binding.ivSendFailIcon

        assertEquals(
            activity.getString(R.string.chat_message_send_failed_retry),
            label.text.toString()
        )
        assertEquals(
            activity.getColor(com.difft.android.base.R.color.t_error),
            label.currentTextColor
        )
        assertEquals(12f * activity.resources.displayMetrics.scaledDensity, label.textSize, 0.5f)
        assertEquals(12.dp, icon.layoutParams.width)
        assertEquals(12.dp, icon.layoutParams.height)
    }

    // ---- T1-8: accessibility — one announcement, exact wording ---------------------------

    @Test
    fun `T1-8 row owns the contentDescription and its children are not accessibility nodes`() {
        val binding = inflateMine()

        assertEquals(
            activity.getString(R.string.chat_message_send_failed_retry),
            binding.llSendFailStatus.contentDescription.toString()
        )
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            binding.ivSendFailIcon.importantForAccessibility
        )
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            binding.tvSendFailText.importantForAccessibility
        )
    }

    // ---- T1-9: minimum touch target -----------------------------------------------------

    @Test
    fun `T1-9 visible row is at least 32dp tall`() {
        val binding = inflateMine()
        bindSendFailStatusRow(binding.llSendFailStatus, SendType.SentFailed.rawValue) { }

        measureAndLayout(binding.root)

        assertTrue(
            "row height ${binding.llSendFailStatus.height}px < ${32.dp}px",
            binding.llSendFailStatus.height >= 32.dp
        )
    }

    // ---- T1-10 (FA3): GONE-collapse + goneMarginTop restore today's geometry --------------

    @Test
    fun `T1-10 visible row never overlaps cl_translate and pushes it below`() {
        val binding = inflateMine()
        bindSendFailStatusRow(binding.llSendFailStatus, SendType.SentFailed.rawValue) { }
        binding.clTranslate.visibility = View.VISIBLE

        measureAndLayout(binding.root)

        val row = binding.llSendFailStatus.boundsInRoot(binding.root)
        val translate = binding.clTranslate.boundsInRoot(binding.root)
        assertFalse("row $row overlaps translate $translate", Rect.intersects(row, translate))
        assertTrue(translate.top >= row.bottom)
    }

    @Test
    fun `T1-10 gone row restores cl_translate to contentContainer bottom plus 5dp`() {
        val binding = inflateMine()
        bindSendFailStatusRow(binding.llSendFailStatus, SendType.Sent.rawValue) { }
        binding.clTranslate.visibility = View.VISIBLE

        measureAndLayout(binding.root)

        val container = binding.contentContainer.boundsInRoot(binding.root)
        val translate = binding.clTranslate.boundsInRoot(binding.root)
        assertEquals(container.bottom + 5.dp, translate.top)
    }

    @Test
    fun `T1-10 visible row never overlaps cl_speech_to_text and pushes it below`() {
        val binding = inflateMine()
        bindSendFailStatusRow(binding.llSendFailStatus, SendType.SentFailed.rawValue) { }
        binding.clSpeechToText.visibility = View.VISIBLE

        measureAndLayout(binding.root)

        val row = binding.llSendFailStatus.boundsInRoot(binding.root)
        val s2t = binding.clSpeechToText.boundsInRoot(binding.root)
        assertFalse("row $row overlaps speechToText $s2t", Rect.intersects(row, s2t))
        assertTrue(s2t.top >= row.bottom)
    }

    @Test
    fun `T1-10 gone row restores cl_speech_to_text to contentContainer bottom plus 5dp`() {
        val binding = inflateMine()
        bindSendFailStatusRow(binding.llSendFailStatus, SendType.Sent.rawValue) { }
        binding.clSpeechToText.visibility = View.VISIBLE

        measureAndLayout(binding.root)

        val container = binding.contentContainer.boundsInRoot(binding.root)
        val s2t = binding.clSpeechToText.boundsInRoot(binding.root)
        assertEquals(container.bottom + 5.dp, s2t.top)
    }

    // ---- T1-11: the anchors themselves must not be silently reverted ----------------------

    @Test
    fun `T1-11 translate and speech-to-text anchor the status row with a 5dp gone margin`() {
        val binding = inflateMine()

        listOf(binding.clTranslate, binding.clSpeechToText).forEach { panel ->
            val lp = panel.layoutParams as ConstraintLayout.LayoutParams
            assertEquals(R.id.ll_send_fail_status, lp.topToBottom)
            assertEquals(5.dp, lp.goneTopMargin)
        }
    }

    // ---- T1-12: every mine content type keeps the row below the bubble --------------------

    @Test
    fun `T1-12 row stays below the bubble for every mine content type`() {
        val contentLayouts = listOf(
            R.layout.chat_item_content_text,
            R.layout.chat_item_content_image, // covers IMAGE + VIDEO (same wrapper)
            R.layout.chat_item_content_audio,
            R.layout.chat_item_content_attach,
            R.layout.chat_item_content_contact,
            R.layout.chat_item_content_multi_forward
        )

        contentLayouts.forEach { contentLayout ->
            parent.removeAllViews()
            val binding = inflateMine()
            LayoutInflater.from(activity).inflate(contentLayout, binding.contentFrame, true)

            bindSendFailStatusRow(binding.llSendFailStatus, SendType.SentFailed.rawValue) { }
            measureAndLayout(binding.root)

            val row = binding.llSendFailStatus.boundsInRoot(binding.root)
            val container = binding.contentContainer.boundsInRoot(binding.root)
            assertEquals(View.VISIBLE, binding.llSendFailStatus.visibility)
            assertFalse(
                "content $contentLayout: row $row overlaps bubble $container",
                Rect.intersects(row, container)
            )
            assertTrue("content $contentLayout: row not below bubble", row.top >= container.bottom)
        }
    }
}
