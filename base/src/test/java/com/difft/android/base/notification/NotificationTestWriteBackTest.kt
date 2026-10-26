package com.difft.android.base.notification

import android.app.Notification.VISIBILITY_SECRET
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #1350: how still-valid notification test results reach the message, call and call pop-up rows (design T1 to T3, T7 to T10). */
class NotificationTestWriteBackTest {

    private val loud = ChannelReading(IMPORTANCE_HIGH, hasSound = true)
    private val ok = NotificationTestResult(NotificationTestOutcome.OK, 1L, "fp")
    private val fail = NotificationTestResult(NotificationTestOutcome.FAIL, 1L, "fp")

    private fun evaluate(
        tests: NotificationTestResults = NotificationTestResults.NONE,
        brand: OemBrand = OemBrand.XIAOMI,
        message: ChannelReading? = loud,
        call: ChannelReading? = loud,
        appEnabled: Boolean = true,
        fullScreen: Boolean? = true,
    ) = NotificationReadinessEvaluator.evaluate(
        ReadinessInput(
            sdkInt = 34,
            postNotificationsGranted = true,
            appNotificationsEnabled = appEnabled,
            messageGroupBlocked = false,
            messageChannel = message,
            callChannel = call,
            criticalChannel = loud,
            canUseFullScreenIntent = fullScreen,
            oemBrand = brand,
            testResults = tests,
        )
    )

    // T1
    @Test
    fun `without results a China skin keeps every row at need-to-check`() {
        val readiness = evaluate()
        assertEquals(CategoryLevel.UNKNOWN, readiness.messageLevel)
        assertEquals(CategoryLevel.UNKNOWN, readiness.callLevel)
        assertEquals(CallPopupLevel.UNTESTED, readiness.callPopupLevel)
        assertEquals(ReadinessStatus.UNKNOWN, readiness.item(ReadinessItemId.C3)?.status)
    }

    // T2
    @Test
    fun `banner and lock-screen confirmations together confirm the message row`() {
        OemBrand.entries.filter { it.isChinaOem }.forEach { brand ->
            val readiness = evaluate(NotificationTestResults(banner = ok, lockscreen = ok), brand = brand)
            assertEquals(CategoryLevel.CONFIRMED, readiness.messageLevel, "brand=$brand")
            assertEquals(CategoryLevel.UNKNOWN, readiness.callLevel, "brand=$brand")
        }
    }

    // T3
    @Test
    fun `one message test alone or a failed one does not confirm the message row`() {
        listOf(
            NotificationTestResults(banner = ok),
            NotificationTestResults(lockscreen = ok),
            NotificationTestResults(banner = ok, lockscreen = fail),
            NotificationTestResults(banner = fail, lockscreen = ok),
        ).forEach { assertEquals(CategoryLevel.UNKNOWN, evaluate(it).messageLevel, "tests=$it") }
    }

    // T7
    @Test
    fun `a definite standard problem is shown as read whatever the tests say`() {
        val all = NotificationTestResults(banner = ok, lockscreen = ok, call = ok)
        val hidden = ChannelReading(IMPORTANCE_HIGH, hasSound = true, lockscreenVisibility = VISIBILITY_SECRET)
        assertEquals(CategoryLevel.LOCK_HIDDEN, evaluate(all, message = hidden).messageLevel)
        assertEquals(CategoryLevel.NO_BANNER, evaluate(all, message = ChannelReading(IMPORTANCE_DEFAULT, hasSound = true)).messageLevel)
        assertEquals(CategoryLevel.SILENT, evaluate(all, message = ChannelReading(IMPORTANCE_HIGH, hasSound = false)).messageLevel)
        assertEquals(CategoryLevel.NO_BANNER, evaluate(all, call = ChannelReading(IMPORTANCE_DEFAULT, hasSound = true)).callLevel)
        val appOff = evaluate(all, appEnabled = false)
        assertEquals(CategoryLevel.OFF, appOff.messageLevel)
        assertEquals(CategoryLevel.OFF, appOff.callLevel)
    }

    // T8
    @Test
    fun `brands with readable switches ignore test results on the settings rows`() {
        val all = NotificationTestResults(banner = ok, lockscreen = ok, call = fail)
        val withTests = evaluate(all, brand = OemBrand.OTHER)
        val without = evaluate(brand = OemBrand.OTHER)
        assertEquals(CategoryLevel.ON, withTests.messageLevel)
        assertEquals(CategoryLevel.ON, withTests.callLevel)
        assertEquals(without.items, withTests.items)
        assertNull(withTests.item(ReadinessItemId.C3))
    }

    @Test
    fun `Samsung rows take test results but get no vendor pop-up row`() {
        val readiness = evaluate(NotificationTestResults(banner = ok, lockscreen = ok, call = ok), brand = OemBrand.SAMSUNG)
        assertEquals(CategoryLevel.CONFIRMED, readiness.messageLevel)
        assertEquals(CategoryLevel.TESTED_OK, readiness.callLevel)
        assertNull(readiness.item(ReadinessItemId.C3))
    }

    // T9
    @Test
    fun `a passed call test marks the call and call pop-up rows tested`() {
        val readiness = evaluate(NotificationTestResults(call = ok))
        assertEquals(CategoryLevel.TESTED_OK, readiness.callLevel)
        assertEquals(CallPopupLevel.TESTED_OK, readiness.callPopupLevel)
        assertEquals(ReadinessStatus.OK, readiness.item(ReadinessItemId.C3)?.status)
        assertEquals(CategoryLevel.UNKNOWN, readiness.messageLevel)
    }

    // T10
    @Test
    fun `a failed call test shows did-not-pop-up and leaves the call row at need-to-check`() {
        val readiness = evaluate(NotificationTestResults(call = fail))
        assertEquals(CallPopupLevel.NOT_SHOWN, readiness.callPopupLevel)
        assertEquals(CategoryLevel.UNKNOWN, readiness.callLevel)
        assertEquals(ReadinessStatus.PROBLEM, readiness.item(ReadinessItemId.C3)?.status)
        assertEquals(ReadinessIssue.OEM_POPUP_CHECK, readiness.item(ReadinessItemId.C3)?.issue)
    }

    @Test
    fun `call test results never raise the startup prompt`() {
        assertNull(evaluate(NotificationTestResults(call = fail)).callPromptIssue())
        assertEquals(ReadinessIssue.FULL_SCREEN_DENIED, evaluate(NotificationTestResults(call = ok), fullScreen = false).callPromptIssue())
    }

    // T4 through the whole chain: a record taken before a category change no longer confirms the row.
    @Test
    fun `a stale record stops confirming the message row`() {
        val build = "build"
        fun input(message: ChannelReading) = ReadinessInput(34, true, true, false, message, loud, loud, true, OemBrand.VIVO)
        val before = input(loud)
        val stored = listOf(NotificationTestKind.BANNER, NotificationTestKind.LOCKSCREEN).associateWith {
            NotificationTestResult(NotificationTestOutcome.OK, 10L, NotificationTestFingerprint.of(it, before, build))
        }
        val confirmed = NotificationReadinessEvaluator.evaluate(before.copy(testResults = NotificationTestResults.validOf(stored, before, build, 20L)))
        assertEquals(CategoryLevel.CONFIRMED, confirmed.messageLevel)
        // A changed lock-screen override still reads as OK, but the record taken before it is void.
        val after = input(ChannelReading(IMPORTANCE_HIGH, hasSound = true, lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE))
        val stale = NotificationReadinessEvaluator.evaluate(after.copy(testResults = NotificationTestResults.validOf(stored, after, build, 20L)))
        assertEquals(CategoryLevel.UNKNOWN, stale.messageLevel)
    }
}
