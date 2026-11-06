package com.difft.android.chat.recent

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.R
import com.difft.android.chat.focus.FocusTimeFormat
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** The folded-groups row on the real chat-list row layout: content, grey badge and its single "End Focus" action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FoldedGroupsViewHolderTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
    }

    private fun bound(summary: FoldedGroupsSummary, onEnd: () -> Unit = {}): View {
        val parent = FrameLayout(activity)
        val holder = FoldedGroupsViewHolder(parent)
        holder.bind(summary, swipeCallback = null, onClick = {}, onLongClick = {}, onEndFocus = onEnd)
        return holder.itemView
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `shows the count, the newest group's preview and the strongest mention`() {
        val view = bound(
            FoldedGroupsSummary(
                count = 4, unreadCount = 46, hasManualUnread = false, mentionType = MENTIONS_TYPE_ME,
                latestGroupName = "Design Review", latestContent = "Vanessa: done", lastActiveTimeText = "15:01", sortTime = 1L,
            )
        )
        assertEquals("Folded Groups(4)", view.text(R.id.textview_label))
        assertEquals("Design Review: Vanessa: done", view.text(R.id.textview_detail))
        assertEquals("[@You]", view.text(R.id.textview_at))
        assertEquals("46", view.text(R.id.textview_missed_number))
        assertEquals("15:01", view.text(R.id.textview_date))
        // No end time: no resume label after the name, and never the focus-row icon.
        assertEquals(View.GONE, view.findViewById<View>(R.id.textview_fold_resume).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.imageview_fold_resume).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.imageview_focus).visibility)
    }

    @Test
    fun `an end time shows the resume label after the name, with Tomorrow when it is not today`() {
        val today = bound(FoldedGroupsSummary(count = 2, unreadCount = 0, hasManualUnread = false, resumeLabel = FocusTimeFormat.EndLabel("18:00", tomorrow = false)))
        assertEquals(View.VISIBLE, today.findViewById<View>(R.id.imageview_fold_resume).visibility)
        assertEquals("Until 18:00", today.text(R.id.textview_fold_resume))

        val tomorrow = bound(FoldedGroupsSummary(count = 2, unreadCount = 0, hasManualUnread = false, resumeLabel = FocusTimeFormat.EndLabel("09:00", tomorrow = true)))
        assertEquals("Until Tomorrow 09:00", tomorrow.text(R.id.textview_fold_resume))
    }

    @Test
    fun `no unread hides the badge and no mention hides the tag`() {
        val view = bound(FoldedGroupsSummary(count = 1, unreadCount = 0, hasManualUnread = false, latestGroupName = "G", latestContent = ""))
        assertEquals("G", view.text(R.id.textview_detail))
        assertEquals(View.GONE, view.findViewById<View>(R.id.textview_missed_number).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.textview_at).visibility)
    }

    @Test
    fun `the only swipe action is End Focus`() {
        var ended = 0
        val view = bound(FoldedGroupsSummary(count = 2, unreadCount = 0, hasManualUnread = false), onEnd = { ended++ })
        assertEquals(View.GONE, view.findViewById<View>(R.id.swipe_start_actions).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.action_pin).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.action_delete).visibility)
        val focus = view.findViewById<View>(R.id.action_focus)
        assertEquals(View.VISIBLE, focus.visibility)
        val label = view.text(R.id.action_focus_label)
        assertTrue(label == "End Focus" || label == "End")
        focus.performClick()
        assertEquals(1, ended)
    }
}
