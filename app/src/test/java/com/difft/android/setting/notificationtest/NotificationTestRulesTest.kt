package com.difft.android.setting.notificationtest

import android.app.Notification.VISIBILITY_SECRET
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_UNSPECIFIED
import com.difft.android.base.notification.ChannelReading
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationReadinessEvaluator
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.notification.ReadinessInput
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #1350: when a notification test may start, and when a running one is voided (design T11 to T13). */
class NotificationTestRulesTest {

    private val loud = ChannelReading(IMPORTANCE_HIGH, hasSound = true)

    private fun readiness(
        sdk: Int = 34,
        appEnabled: Boolean = true,
        message: ChannelReading? = loud,
        call: ChannelReading? = loud,
        fullScreen: Boolean? = true,
    ) = NotificationReadinessEvaluator.evaluate(
        ReadinessInput(sdk, true, appEnabled, false, message, call, loud, fullScreen, OemBrand.XIAOMI)
    )

    private fun precheck(kind: NotificationTestKind, dnd: Boolean = false, sdk: Int = 34, appEnabled: Boolean = true,
        message: ChannelReading? = loud, call: ChannelReading? = loud, fullScreen: Boolean? = true) =
        NotificationTestRules.precheck(kind, readiness(sdk, appEnabled, message, call, fullScreen), dnd)

    @Test
    fun `every test starts when the readable settings are fine and Do Not Disturb is off`() {
        NotificationTestKind.entries.forEach { assertNull(precheck(it), "kind=$it") }
    }

    @Test
    fun `an unreadable category does not block the test meant for it`() {
        val unspecified = ChannelReading(IMPORTANCE_UNSPECIFIED, hasSound = true)
        assertNull(precheck(NotificationTestKind.BANNER, message = unspecified))
        assertNull(precheck(NotificationTestKind.CALL, call = unspecified))
    }

    @Test
    fun `app notifications off block every test and lead to the app notification page`() {
        assertEquals(Precheck(PrecheckBlock.MESSAGE_OFF, NotificationFix.APP_NOTIFICATIONS), precheck(NotificationTestKind.BANNER, appEnabled = false))
        assertEquals(Precheck(PrecheckBlock.MESSAGE_OFF, NotificationFix.APP_NOTIFICATIONS), precheck(NotificationTestKind.LOCKSCREEN, appEnabled = false))
        assertEquals(Precheck(PrecheckBlock.CALL_OFF, NotificationFix.APP_NOTIFICATIONS), precheck(NotificationTestKind.CALL, appEnabled = false))
    }

    @Test
    fun `a MESSAGE category problem blocks both message tests`() {
        listOf(ChannelReading(IMPORTANCE_DEFAULT, hasSound = true), ChannelReading(IMPORTANCE_HIGH, hasSound = false), null).forEach { channel ->
            listOf(NotificationTestKind.BANNER, NotificationTestKind.LOCKSCREEN).forEach { kind ->
                assertEquals(Precheck(PrecheckBlock.MESSAGE_OFF, NotificationFix.MESSAGE_CATEGORY), precheck(kind, message = channel), "kind=$kind")
            }
        }
        assertNull(precheck(NotificationTestKind.CALL, message = null))
    }

    @Test
    fun `a hidden lock-screen override is named as such by the lock-screen test`() {
        val hidden = ChannelReading(IMPORTANCE_HIGH, hasSound = true, lockscreenVisibility = VISIBILITY_SECRET)
        assertEquals(Precheck(PrecheckBlock.LOCK_HIDDEN, NotificationFix.MESSAGE_CATEGORY), precheck(NotificationTestKind.LOCKSCREEN, message = hidden))
        assertEquals(Precheck(PrecheckBlock.MESSAGE_OFF, NotificationFix.MESSAGE_CATEGORY), precheck(NotificationTestKind.BANNER, message = hidden))
    }

    @Test
    fun `a CALL category problem blocks the call test only`() {
        val low = ChannelReading(IMPORTANCE_DEFAULT, hasSound = true)
        assertEquals(Precheck(PrecheckBlock.CALL_OFF, NotificationFix.CALL_CATEGORY), precheck(NotificationTestKind.CALL, call = low))
        assertNull(precheck(NotificationTestKind.BANNER, call = low))
    }

    // T11
    @Test
    fun `a denied full-screen permission on API 34 blocks the call test and leads to its page`() {
        assertEquals(
            Precheck(PrecheckBlock.FULL_SCREEN_OFF, NotificationFix.FULL_SCREEN_INTENT),
            precheck(NotificationTestKind.CALL, fullScreen = false),
        )
        assertNull(precheck(NotificationTestKind.CALL, sdk = 33, fullScreen = null))
        assertNull(precheck(NotificationTestKind.BANNER, fullScreen = false))
    }

    // T13
    @Test
    fun `Do Not Disturb blocks every test without a settings page, after any definite problem`() {
        NotificationTestKind.entries.forEach {
            assertEquals(Precheck(PrecheckBlock.DND_ON, null), precheck(it, dnd = true), "kind=$it")
        }
        assertEquals(PrecheckBlock.FULL_SCREEN_OFF, precheck(NotificationTestKind.CALL, dnd = true, fullScreen = false)?.block)
    }

    @Test
    fun `only filters that silence alerts count as Do Not Disturb`() {
        assertFalse(NotificationTestRules.isDndOn(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertFalse(NotificationTestRules.isDndOn(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
        assertTrue(NotificationTestRules.isDndOn(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(NotificationTestRules.isDndOn(NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertTrue(NotificationTestRules.isDndOn(NotificationManager.INTERRUPTION_FILTER_NONE))
    }

    // T12, T13
    @Test
    fun `a run counts while locked or with the screen still off, and Do Not Disturb stays off`() {
        assertEquals(VoidReason.UNLOCKED, NotificationTestRules.voidReason(NotificationTestKind.CALL, keyguardLocked = false, interactive = true, dndOn = false))
        assertEquals(VoidReason.UNLOCKED, NotificationTestRules.voidReason(NotificationTestKind.CALL, keyguardLocked = false, interactive = true, dndOn = true))
        assertEquals(VoidReason.DND, NotificationTestRules.voidReason(NotificationTestKind.CALL, keyguardLocked = true, interactive = false, dndOn = true))
        assertEquals(VoidReason.DND, NotificationTestRules.voidReason(NotificationTestKind.CALL, keyguardLocked = false, interactive = false, dndOn = true))
        assertNull(NotificationTestRules.voidReason(NotificationTestKind.CALL, keyguardLocked = true, interactive = false, dndOn = false))
        // Woken onto the lock screen still tests the lock-screen path.
        assertNull(NotificationTestRules.voidReason(NotificationTestKind.CALL, keyguardLocked = true, interactive = true, dndOn = false))
        // No secure lock, or a lock delay: the screen staying off is enough.
        assertNull(NotificationTestRules.voidReason(NotificationTestKind.CALL, keyguardLocked = false, interactive = false, dndOn = false))
        // The lock-screen test has nothing to judge without a locked keyguard.
        assertEquals(
            VoidReason.NOT_LOCKED,
            NotificationTestRules.voidReason(NotificationTestKind.LOCKSCREEN, keyguardLocked = false, interactive = false, dndOn = false),
        )
        assertNull(NotificationTestRules.voidReason(NotificationTestKind.LOCKSCREEN, keyguardLocked = true, interactive = false, dndOn = false))
    }

    @Test
    fun `the wait for a delayed keyguard ends once it locks, the screen is back on, or the settle time passes`() {
        val settle = NotificationTestRules.LOCK_SETTLE_MS
        assertTrue(NotificationTestRules.awaitingLock(keyguardLocked = false, interactive = false, sinceScreenOffMs = 2_000))
        assertTrue(NotificationTestRules.awaitingLock(keyguardLocked = false, interactive = false, sinceScreenOffMs = settle - 1))
        assertFalse(NotificationTestRules.awaitingLock(keyguardLocked = false, interactive = false, sinceScreenOffMs = settle))
        assertFalse(NotificationTestRules.awaitingLock(keyguardLocked = true, interactive = false, sinceScreenOffMs = 2_000))
        assertFalse(NotificationTestRules.awaitingLock(keyguardLocked = false, interactive = true, sinceScreenOffMs = 2_000))
    }
}
