package com.difft.android.setting

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.difft.android.R
import com.difft.android.base.widget.ToastUtil
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StoreUpdateDeliveryTest {

    private lateinit var app: Application
    private val offer = UpdateOffer(url = "https://example.invalid/a.apk", apkHash = "h")

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        mockkObject(ToastUtil)
        every { ToastUtil.show(any<Int>()) } just runs
    }

    @After
    fun tearDown() {
        unmockkObject(ToastUtil)
    }

    @Test
    fun deliver_opensStoreAppListing() {
        var handOffs = 0
        val delivery = StoreUpdateDelivery(AppStore.GooglePlay)

        delivery.deliver(app, offer, isForce = false) { handOffs++ }

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("market://details?id=${app.packageName}", started.dataString)
        assertEquals("com.android.vending", started.`package`)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, handOffs, "a store delivery never dismisses the force prompt")
        assertFalse(delivery.isInProgress)
    }

    @Test
    fun deliver_fallsBackToWebListing_whenStoreAppMissing() {
        shadowOf(app).checkActivities(true)
        val webUri = "https://f-droid.org/packages/${app.packageName}".toUri()
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, webUri), browserResolveInfo())

        StoreUpdateDelivery(AppStore.FDroid).deliver(app, offer, isForce = true) {}

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(webUri.toString(), started.dataString)
        assertNull(started.`package`)
        verify(exactly = 0) { ToastUtil.show(any<Int>()) }
    }

    @Test
    fun deliver_toastsInsteadOfCrashing_whenNothingResolves() {
        shadowOf(app).checkActivities(true)

        StoreUpdateDelivery(AppStore.GooglePlay).deliver(app, offer, isForce = false) {}

        assertNull(shadowOf(app).nextStartedActivity)
        verify(exactly = 1) { ToastUtil.show(R.string.status_upgrade_install_failed) }
    }

    private fun browserResolveInfo() = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = "com.example.browser"
            name = "com.example.browser.Main"
        }
    }
}
