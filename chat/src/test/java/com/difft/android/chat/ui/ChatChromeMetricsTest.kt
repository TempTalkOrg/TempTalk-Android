package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.widget.FloatingSurfaceDrawable
import com.difft.android.chat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config







@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatChromeMetricsTest {

    private val density = 2f

    @Test
    fun `a phone input sits 8dp above the navigation bar or keyboard`() {
        assertEquals(16, ChatInputGap.bottomPx(sideBar = false, keyboardUp = false, navBottomPx = 48, density = density))
        assertEquals(16, ChatInputGap.bottomPx(sideBar = false, keyboardUp = true, navBottomPx = 48, density = density))
    }

    @Test
    fun `a side-bar input rests 26dp from the screen bottom and 10dp above the keyboard`() {
        
        assertEquals(52, ChatInputGap.bottomPx(sideBar = true, keyboardUp = false, navBottomPx = 0, density = density))
        
        assertEquals(20, ChatInputGap.bottomPx(sideBar = true, keyboardUp = false, navBottomPx = 32, density = density))
        assertEquals(20, ChatInputGap.bottomPx(sideBar = true, keyboardUp = true, navBottomPx = 32, density = density))
    }

    @Test
    fun `a tall navigation bar still leaves the phone gap`() {
        assertEquals(16, ChatInputGap.bottomPx(sideBar = true, keyboardUp = false, navBottomPx = 96, density = density))
    }

    @Test
    fun `the phone title is an avatar beside two plain rows and the side-bar title one floating pill`() {
        val header = LayoutInflater.from(ApplicationProvider.getApplicationContext())
            .inflate(R.layout.chat_fragment_header, null)
        val title = header.findViewById<LinearLayout>(R.id.title_pill)
        val avatar = header.findViewById<View>(R.id.imageview_title_avatar)
        val titleText = header.findViewById<LinearLayout>(R.id.title_text)
        val nameRow = header.findViewById<View>(R.id.name_row)
        val timerRow = header.findViewById<View>(R.id.timer_row)
        val dp = header.resources.displayMetrics.density

        ChatHeaderChrome.styleTitle(title, avatar, titleText, nameRow, timerRow, pill = true)
        assertEquals(LinearLayout.HORIZONTAL, titleText.orientation)
        assertTrue(title.background is FloatingSurfaceDrawable)
        assertEquals((6 * dp).toInt(), title.paddingStart)
        assertEquals((20 * dp).toInt(), title.paddingEnd)
        assertEquals((32 * dp).toInt(), avatar.layoutParams.width)
        assertEquals((8 * dp).toInt(), (timerRow.layoutParams as ViewGroup.MarginLayoutParams).marginStart)
        assertEquals(1f, (nameRow.layoutParams as LinearLayout.LayoutParams).weight)

        ChatHeaderChrome.styleTitle(title, avatar, titleText, nameRow, timerRow, pill = false)
        assertEquals(LinearLayout.VERTICAL, titleText.orientation)
        assertEquals(LinearLayout.HORIZONTAL, title.orientation)
        assertNull(title.background)
        assertEquals(0f, title.elevation)
        assertEquals(0, title.paddingStart)
        assertEquals((36 * dp).toInt(), avatar.layoutParams.width)
        assertEquals(0, (timerRow.layoutParams as ViewGroup.MarginLayoutParams).marginStart)
        assertEquals(0f, (nameRow.layoutParams as LinearLayout.LayoutParams).weight)
    }

    @Test
    fun `a short-window pill sits centred between the in-place controls or across the header on the bar`() {
        val header = LayoutInflater.from(ApplicationProvider.getApplicationContext())
            .inflate(R.layout.chat_fragment_header, null)
        val title = header.findViewById<View>(R.id.title_pill)
        val params = { title.layoutParams as ConstraintLayout.LayoutParams }

        ChatHeaderChrome.placeTitle(title, R.id.ib_back, R.id.actions_capsule, centred = false, bias = 0.5f)
        assertEquals(R.id.ib_back, params().startToEnd)
        assertEquals(R.id.actions_capsule, params().endToStart)
        assertEquals(0.5f, params().horizontalBias)

        ChatHeaderChrome.placeTitle(title, R.id.ib_back, R.id.actions_capsule, centred = true, bias = 0.5f)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params().startToStart)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params().endToEnd)

        ChatHeaderChrome.placeTitle(title, R.id.ib_back, R.id.actions_capsule, centred = false)
        assertEquals(R.id.ib_back, params().startToEnd)
        assertEquals(0f, params().horizontalBias)
    }

    @Test
    fun `the input row keeps 16dp side insets and 12dp gaps`() {
        val input = LayoutInflater.from(ApplicationProvider.getApplicationContext())
            .inflate(R.layout.chat_fragment_input, null)
        val dp = input.resources.displayMetrics.density
        fun margins(id: Int) = input.findViewById<View>(id).layoutParams as ViewGroup.MarginLayoutParams

        assertEquals((16 * dp).toInt(), margins(R.id.cl_left_actions).marginStart)
        assertEquals((12 * dp).toInt(), margins(R.id.cl_input_content).marginStart)
        assertEquals((12 * dp).toInt(), margins(R.id.cl_input_content).marginEnd)
        assertEquals((16 * dp).toInt(), margins(R.id.cl_right_actions).marginEnd)
    }
}
