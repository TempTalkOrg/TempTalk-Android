package com.difft.android.chat.contacts

import android.view.View
import android.widget.FrameLayout
import com.difft.android.base.utils.weakcontact.WeakContactClock
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsall.ContactItemViewHolder
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import android.app.Activity
import com.difft.android.base.utils.weakcontact.WeakContactCountdown
import kotlin.test.assertEquals

/**
 * Tests for the weak-pending contact list subtitle.
 *
 * **Screenshot tier downgraded to logic verification (documented)**: `:chat` has NO View/XML
 * screenshot harness — `ViewScreenshotHelper` / `ScreenshotTestBase` do not exist in the project,
 * and the only screenshot infra is Compose-based (Roborazzi +
 * `createComposeRule`, used by `ContactDetailScreenScreenshotTest`). The list row
 * (`chat_item_contact.xml`) is a View/XML layout whose subtitle is set by [ContactItemViewHolder]
 * + the adapter's bind decision. Bootstrapping a View screenshot harness is out of scope per the
 * write-tests skill's "downgrade when harness cost is high" guidance; instead this test invokes the
 * **real** production units directly (per "Tests Invoke Production, Don't Replicate It"):
 * - the real [WeakContactCountdown.daysLeftFromClock] (countdown calc),
 * - the real [ContactItemViewHolder.content] setter (visibility + text logic on `textViewContent`).
 *
 * It does NOT replicate the adapter's branch; it drives the same two production functions the
 * adapter chains, and asserts on the resulting `textViewContent` view state. A future View
 * screenshot harness can add the pixel baseline on top of this.
 *
 * `TextSizeUtil`/Glide avatar loading is avoided — only the content slot is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContactItemSubtitleTest {

    private lateinit var controller: ActivityController<Activity>
    private lateinit var parent: FrameLayout
    private val dayMs = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        parent = FrameLayout(activity)
        // Deterministic countdown: anchor server clock = now, so daysLeftFromClock is pure arithmetic
        // off expireAt regardless of wall time.
        WeakContactClock.update(serverNow = NOW, elapsedRealtime = android.os.SystemClock.elapsedRealtime())
    }

    @After
    fun tearDown() {
        clearClockAnchor()
        controller.destroy()
    }

    private fun clearClockAnchor() {
        val field = WeakContactClock::class.java.getDeclaredField("anchor")
        field.isAccessible = true
        field.set(WeakContactClock, null)
    }

    /** Build a real ViewHolder by inflating the real list-item layout under Robolectric. */
    private fun holder(): ContactItemViewHolder = ContactItemViewHolder(parent)

    // ---- weak-pending row shows "Removes in N days" subtitle ----------------------------

    @Test
    fun `T10 weak pending contact shows countdown subtitle visible with correct text`() {
        val activity = controller.get()
        // expireAt = 3 days from the anchored server-now → daysLeftFromClock = 3.
        val expireAt = NOW + (dayMs * 3) - 1
        val days = WeakContactCountdown.daysLeftFromClock(expireAt) // REAL countdown
        assertEquals(3, days)

        val subtitle = activity.getString(R.string.weak_contact_remove_in_days, days)
        val vh = holder()
        vh.content = subtitle // REAL ViewHolder content setter (visibility + text)

        val tv = vh.itemView.findViewById<View>(
            com.difft.android.chat.R.id.textViewContent
        )
        assertEquals(View.VISIBLE, tv.visibility, "subtitle must be visible for weak-pending row")
        assertEquals(subtitle, vh.content)
        // Sanity: the rendered text reflects the day count.
        assertEquals(true, subtitle.contains("3"))
    }

    // ---- non-weak (friend) row has no subtitle → content GONE ---------------------------

    @Test
    fun `T10 friend contact has no subtitle so content slot is GONE`() {
        val vh = holder()
        vh.content = null // adapter sets null when the item's expireAt is null (friend, not weak-pending)

        val tv = vh.itemView.findViewById<View>(
            com.difft.android.chat.R.id.textViewContent
        )
        assertEquals(View.GONE, tv.visibility, "friend row must hide the subtitle slot")
    }

    companion object {
        private const val NOW = 1_900_000_000_000L
    }
}
