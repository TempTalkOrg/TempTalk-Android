package com.difft.android.base.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.difft.android.base.R
import com.difft.android.base.widget.ToastUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * [Context.openExternalBrowser] must not crash on a device with no browser app, must toast
 * [R.string.base_link_open_failed] on failure only, and must not regress the success path (still
 * fires the same ACTION_VIEW intent, no toast).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenExternalBrowserTest {

    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(ToastUtil)
        every { ToastUtil.show(any<Int>()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(ToastUtil)
    }

    @Test
    fun `P0-1 startActivity throws ActivityNotFoundException does not propagate and shows toast`() {
        every { context.startActivity(any()) } throws ActivityNotFoundException()

        // No exception should propagate out of the extension.
        context.openExternalBrowser("https://yelling.pro/security")

        verify(exactly = 1) { ToastUtil.show(R.string.base_link_open_failed) }
    }

    @Test
    fun `P0-2 startActivity succeeds fires ACTION_VIEW intent with given url and shows no toast`() {
        val intentSlot = slot<Intent>()
        every { context.startActivity(capture(intentSlot)) } returns Unit

        context.openExternalBrowser("https://yelling.pro/security")

        assertEquals(Intent.ACTION_VIEW, intentSlot.captured.action)
        assertEquals("https://yelling.pro/security", intentSlot.captured.data.toString())
        verify(exactly = 0) { ToastUtil.show(any<Int>()) }
    }
}
