package com.difft.android.base.notification

import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #1350: stored notification test results, their fingerprint and their validity (design T4 to T6, T14). */
class NotificationTestResultTest {

    private val loud = ChannelReading(IMPORTANCE_HIGH, hasSound = true)
    private val build = "vendor/device/1:15/AP3A/1:user/release-keys"
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun input(
        message: ChannelReading? = loud,
        call: ChannelReading? = loud,
        appEnabled: Boolean = true,
        groupBlocked: Boolean = false,
        fullScreen: Boolean? = true,
    ) = ReadinessInput(
        sdkInt = 34,
        postNotificationsGranted = true,
        appNotificationsEnabled = appEnabled,
        messageGroupBlocked = groupBlocked,
        messageChannel = message,
        callChannel = call,
        criticalChannel = loud,
        canUseFullScreenIntent = fullScreen,
        oemBrand = OemBrand.XIAOMI,
    )

    private fun stored(kind: NotificationTestKind, input: ReadinessInput, at: Long = now, outcome: NotificationTestOutcome = NotificationTestOutcome.OK) =
        NotificationTestResult(outcome, at, NotificationTestFingerprint.of(kind, input, build))

    private fun allOk(input: ReadinessInput, at: Long = now) =
        NotificationTestKind.entries.associateWith { stored(it, input, at) }

    @Test
    fun `a result survives an encode and decode round trip`() {
        listOf(NotificationTestOutcome.OK, NotificationTestOutcome.FAIL).forEach { outcome ->
            val result = NotificationTestResult(outcome, now, "0123456789abcdef")
            assertEquals(result, NotificationTestResult.decode(result.encode()))
        }
        assertEquals("ok|$now|0123456789abcdef", NotificationTestResult(NotificationTestOutcome.OK, now, "0123456789abcdef").encode())
    }

    // T14: a corrupt value reads as "not tested" and never throws
    @Test
    fun `malformed values decode to null`() {
        listOf(null, "", " ", "garbage", "ok|abc|fp", "maybe|1|fp", "ok|1", "ok|1|fp|extra", "ok|0|fp", "ok|-5|fp", "ok|1|", "|1|fp")
            .forEach { assertNull(NotificationTestResult.decode(it), "raw=$it") }
    }

    // T6
    @Test
    fun `a result is valid for 30 days and not before it was taken`() {
        val fp = "fp"
        val result = NotificationTestResult(NotificationTestOutcome.OK, now, fp)
        assertTrue(result.isValid(fp, now))
        assertTrue(result.isValid(fp, now + 30 * day))
        assertFalse(result.isValid(fp, now + 30 * day + 1))
        assertFalse(result.isValid(fp, now - 1))
        assertFalse(result.isValid("other", now))
    }

    // T4
    @Test
    fun `a MESSAGE importance change voids the message tests but not the call test`() {
        val before = input()
        val after = input(message = ChannelReading(IMPORTANCE_DEFAULT, hasSound = true))
        val valid = NotificationTestResults.validOf(allOk(before), after, build, now)
        assertNull(valid.banner)
        assertNull(valid.lockscreen)
        assertNotNull(valid.call)
    }

    @Test
    fun `a CALL category or full-screen change voids only the call test`() {
        val before = input()
        listOf(input(call = ChannelReading(IMPORTANCE_DEFAULT, hasSound = true)), input(fullScreen = false)).forEach { after ->
            val valid = NotificationTestResults.validOf(allOk(before), after, build, now)
            assertNotNull(valid.banner)
            assertNotNull(valid.lockscreen)
            assertNull(valid.call)
        }
    }

    @Test
    fun `sound, lock-screen override, app switch and group switch are part of the fingerprint`() {
        val base = input()
        listOf(
            input(message = ChannelReading(IMPORTANCE_HIGH, hasSound = false)),
            input(message = ChannelReading(IMPORTANCE_HIGH, hasSound = true, lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE)),
            input(message = null),
            input(appEnabled = false),
            input(groupBlocked = true),
        ).forEach { changed ->
            assertNotEquals(
                NotificationTestFingerprint.of(NotificationTestKind.BANNER, base, build),
                NotificationTestFingerprint.of(NotificationTestKind.BANNER, changed, build),
            )
        }
    }

    // T5
    @Test
    fun `a system update voids every result`() {
        val valid = NotificationTestResults.validOf(allOk(input()), input(), "vendor/device/1:16/BP1A/2:user/release-keys", now)
        assertEquals(NotificationTestResults.NONE, valid)
    }

    @Test
    fun `unchanged settings keep every result until it expires`() {
        val stored = allOk(input())
        assertEquals(NotificationTestResults(stored.getValue(NotificationTestKind.BANNER), stored.getValue(NotificationTestKind.LOCKSCREEN), stored.getValue(NotificationTestKind.CALL)),
            NotificationTestResults.validOf(stored, input(), build, now + day))
        assertEquals(NotificationTestResults.NONE, NotificationTestResults.validOf(stored, input(), build, now + 31 * day))
    }

    @Test
    fun `the fingerprint is stable and safe to store between separators`() {
        val fp = NotificationTestFingerprint.of(NotificationTestKind.CALL, input(), build)
        assertEquals(fp, NotificationTestFingerprint.of(NotificationTestKind.CALL, input(), build))
        assertEquals(16, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
