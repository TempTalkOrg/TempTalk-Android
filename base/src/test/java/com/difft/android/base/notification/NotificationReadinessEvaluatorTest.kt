package com.difft.android.base.notification

import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.NotificationManager.IMPORTANCE_MIN
import android.app.NotificationManager.IMPORTANCE_NONE
import android.app.NotificationManager.IMPORTANCE_UNSPECIFIED
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #1350: the readiness model's per-item rules (design T1 to T5). */
class NotificationReadinessEvaluatorTest {

    private val loud = ChannelReading(IMPORTANCE_HIGH, hasSound = true)

    private fun input(
        sdk: Int = 34,
        postGranted: Boolean = true,
        appEnabled: Boolean = true,
        groupBlocked: Boolean = false,
        message: ChannelReading? = loud,
        call: ChannelReading? = loud,
        critical: ChannelReading? = loud,
        fullScreen: Boolean? = true,
        brand: OemBrand = OemBrand.OTHER,
    ) = ReadinessInput(
        sdkInt = sdk,
        postNotificationsGranted = postGranted,
        appNotificationsEnabled = appEnabled,
        messageGroupBlocked = groupBlocked,
        messageChannel = message,
        callChannel = call,
        criticalChannel = critical,
        canUseFullScreenIntent = fullScreen,
        oemBrand = brand,
    )

    private fun evaluate(input: ReadinessInput) = NotificationReadinessEvaluator.evaluate(input)

    private fun NotificationReadiness.status(id: ReadinessItemId) = item(id)?.status

    private val secret = android.app.Notification.VISIBILITY_SECRET
    private val private = android.app.Notification.VISIBILITY_PRIVATE

    private fun messageLevel(channel: ChannelReading) = evaluate(input(message = channel)).messageLevel
    private fun callLevel(channel: ChannelReading?) = evaluate(input(call = channel)).callLevel

    // T1: one vocabulary for the message row
    @Test
    fun `message row maps importance, lock screen and sound with the documented precedence`() {
        assertEquals(CategoryLevel.OFF, messageLevel(ChannelReading(IMPORTANCE_NONE, hasSound = true)))
        // Anything below HIGH does not pop up, whatever the sound says (HyperOS lowers a category to LOW
        // when its floating-notification switch is off, while it still plays a sound).
        listOf(IMPORTANCE_MIN, IMPORTANCE_LOW, IMPORTANCE_DEFAULT).forEach {
            assertEquals(CategoryLevel.NO_BANNER, messageLevel(ChannelReading(it, hasSound = true)), "importance=$it")
            assertEquals(CategoryLevel.NO_BANNER, messageLevel(ChannelReading(it, hasSound = false)), "importance=$it")
        }
        assertEquals(CategoryLevel.ON, messageLevel(ChannelReading(IMPORTANCE_HIGH, hasSound = true)))
        assertEquals(CategoryLevel.SILENT, messageLevel(ChannelReading(IMPORTANCE_HIGH, hasSound = false)))
        assertEquals(CategoryLevel.LOCK_HIDDEN, messageLevel(ChannelReading(IMPORTANCE_HIGH, hasSound = true, lockscreenVisibility = secret)))
        // Lock-screen hiding outranks the sound check; PRIVATE (hide content) and no override are fine.
        assertEquals(CategoryLevel.LOCK_HIDDEN, messageLevel(ChannelReading(IMPORTANCE_HIGH, hasSound = false, lockscreenVisibility = secret)))
        assertEquals(CategoryLevel.ON, messageLevel(ChannelReading(IMPORTANCE_HIGH, hasSound = true, lockscreenVisibility = private)))
        assertEquals(CategoryLevel.NO_BANNER, messageLevel(ChannelReading(IMPORTANCE_LOW, hasSound = true, lockscreenVisibility = secret)))
        assertEquals(CategoryLevel.UNKNOWN, messageLevel(ChannelReading(IMPORTANCE_UNSPECIFIED, hasSound = true)))
    }

    @Test
    fun `message row treats a blocked group or a missing channel as off`() {
        assertEquals(CategoryLevel.OFF, evaluate(input(groupBlocked = true)).messageLevel)
        assertEquals(CategoryLevel.OFF, evaluate(input(message = null)).messageLevel)
        val noBanner = evaluate(input(message = ChannelReading(IMPORTANCE_DEFAULT, hasSound = true)))
        assertEquals(ReadinessStatus.PROBLEM, noBanner.status(ReadinessItemId.N2))
        assertEquals(ReadinessIssue.MESSAGE_CATEGORY_NO_BANNER, noBanner.item(ReadinessItemId.N2)?.issue)
        assertEquals(NotificationFix.MESSAGE_CATEGORY, NotificationFixJump.fixFor(ReadinessIssue.MESSAGE_CATEGORY_LOCK_HIDDEN))
    }

    @Test
    fun `N1 reports a problem when the app switch or the runtime permission is off`() {
        assertEquals(ReadinessStatus.PROBLEM, evaluate(input(appEnabled = false)).status(ReadinessItemId.N1))
        assertEquals(ReadinessStatus.PROBLEM, evaluate(input(postGranted = false)).status(ReadinessItemId.N1))
        assertEquals(ReadinessStatus.OK, evaluate(input()).status(ReadinessItemId.N1))
        assertEquals(CategoryLevel.OFF, evaluate(input(appEnabled = false)).messageLevel)
        assertEquals(CategoryLevel.OFF, evaluate(input(appEnabled = false)).callLevel)
    }

    // T2: the call row uses the same words and precedence, minus the sound rule
    @Test
    fun `call row follows the same rules as the message row except sound`() {
        assertEquals(CategoryLevel.ON, callLevel(ChannelReading(IMPORTANCE_HIGH, hasSound = false)))
        assertEquals(CategoryLevel.ON, callLevel(loud))
        assertEquals(CategoryLevel.OFF, callLevel(ChannelReading(IMPORTANCE_NONE, hasSound = true)))
        listOf(IMPORTANCE_MIN, IMPORTANCE_LOW, IMPORTANCE_DEFAULT).forEach {
            assertEquals(CategoryLevel.NO_BANNER, callLevel(ChannelReading(it, hasSound = true)), "importance=$it")
        }
        assertEquals(CategoryLevel.LOCK_HIDDEN, callLevel(ChannelReading(IMPORTANCE_HIGH, hasSound = true, lockscreenVisibility = secret)))
        assertEquals(CategoryLevel.ON, callLevel(ChannelReading(IMPORTANCE_HIGH, hasSound = true, lockscreenVisibility = private)))
        assertEquals(CategoryLevel.UNKNOWN, callLevel(ChannelReading(IMPORTANCE_UNSPECIFIED, hasSound = true)))
        val noBanner = evaluate(input(call = ChannelReading(IMPORTANCE_DEFAULT, hasSound = true)))
        assertEquals(ReadinessIssue.CALL_CATEGORY_NO_BANNER, noBanner.item(ReadinessItemId.C1)?.issue)
        assertEquals(NotificationFix.CALL_CATEGORY, NotificationFixJump.fixFor(ReadinessIssue.CALL_CATEGORY_LOCK_HIDDEN))
    }

    @Test
    fun `a missing CALL category is off like a missing MESSAGE category`() {
        assertEquals(CategoryLevel.OFF, callLevel(null))
        assertEquals(ReadinessStatus.PROBLEM, evaluate(input(call = null)).status(ReadinessItemId.C1))
        assertEquals(CategoryLevel.OFF, evaluate(input(message = null)).messageLevel)
    }

    @Test
    fun `the same status rules apply on every brand`() {
        OemBrand.entries.forEach { brand ->
            assertEquals(ReadinessStatus.OK, evaluate(input(brand = brand)).status(ReadinessItemId.C1), "brand=$brand")
            assertEquals(
                CategoryLevel.NO_BANNER,
                evaluate(input(brand = brand, call = ChannelReading(IMPORTANCE_LOW, hasSound = false))).callLevel,
            )
        }
    }

    // T3
    @Test
    fun `C2 is always OK on API 33 and below`() {
        assertEquals(ReadinessStatus.OK, evaluate(input(sdk = 33, fullScreen = null)).status(ReadinessItemId.C2))
        assertEquals(ReadinessStatus.OK, evaluate(input(sdk = 29, fullScreen = null)).status(ReadinessItemId.C2))
    }

    @Test
    fun `C2 is a problem on API 34 when full-screen intents are not allowed`() {
        val denied = evaluate(input(sdk = 34, fullScreen = false))
        assertEquals(ReadinessStatus.PROBLEM, denied.status(ReadinessItemId.C2))
        assertEquals(ReadinessIssue.FULL_SCREEN_DENIED, denied.item(ReadinessItemId.C2)?.issue)
        assertEquals(ReadinessStatus.OK, evaluate(input(sdk = 34, fullScreen = true)).status(ReadinessItemId.C2))
    }

    // T4: the vendor row carries no readable signal, so it is "check" on China skins until a call test (#1350)
    @Test
    fun `C3 is need-to-check on a China skin without a call test`() {
        OemBrand.entries.filter { it.isChinaOem }.forEach { brand ->
            val item = evaluate(input(brand = brand)).item(ReadinessItemId.C3)
            assertEquals(ReadinessStatus.UNKNOWN, item?.status, "brand=$brand")
            assertEquals(ReadinessIssue.OEM_POPUP_CHECK, item?.issue)
        }
    }

    @Test
    fun `message and call categories follow the standard APIs on every brand`() {
        OemBrand.entries.forEach { brand ->
            val readiness = evaluate(input(brand = brand))
            assertEquals(ReadinessStatus.OK, readiness.status(ReadinessItemId.N2), "brand=$brand")
            assertEquals(ReadinessStatus.OK, readiness.status(ReadinessItemId.C1), "brand=$brand")
            assertEquals(readiness.oemBrand.vendorAlertSwitchesReadable, readiness.messageLevel == CategoryLevel.ON)
            assertNull(readiness.callPromptIssue())
        }
    }

    @Test
    fun `a China skin leaves only the vendor row uncertain`() {
        val readiness = evaluate(input(brand = OemBrand.XIAOMI))
        assertEquals(
            listOf(ReadinessItemId.C3),
            readiness.items.filter { it.status == ReadinessStatus.UNKNOWN }.map { it.id },
        )
    }

    @Test
    fun `definite standard problems stay problems on a China skin`() {
        val readiness = evaluate(
            input(
                brand = OemBrand.XIAOMI,
                appEnabled = false,
                message = ChannelReading(IMPORTANCE_LOW, hasSound = true),
                call = ChannelReading(IMPORTANCE_DEFAULT, hasSound = true),
                fullScreen = false,
            )
        )
        assertEquals(ReadinessStatus.PROBLEM, readiness.status(ReadinessItemId.N1))
        assertEquals(ReadinessIssue.MESSAGE_CATEGORY_NO_BANNER, readiness.item(ReadinessItemId.N2)?.issue)
        assertEquals(ReadinessStatus.PROBLEM, readiness.status(ReadinessItemId.N2))
        assertEquals(ReadinessIssue.CALL_CATEGORY_NO_BANNER, readiness.item(ReadinessItemId.C1)?.issue)
        assertEquals(ReadinessStatus.PROBLEM, readiness.status(ReadinessItemId.C2))
        val blocked = evaluate(input(brand = OemBrand.XIAOMI, groupBlocked = true))
        assertEquals(ReadinessStatus.PROBLEM, blocked.status(ReadinessItemId.N2))
        assertEquals(CategoryLevel.OFF, blocked.messageLevel)
    }

    @Test
    fun `China skins show a standard on reading as need-to-check on both rows`() {
        listOf(OemBrand.XIAOMI, OemBrand.OTHER_VENDOR).forEach { brand ->
            val readiness = evaluate(input(brand = brand))
            assertEquals(CategoryLevel.UNKNOWN, readiness.messageLevel, "brand=$brand")
            assertEquals(CategoryLevel.UNKNOWN, readiness.callLevel, "brand=$brand")
            assertNull(readiness.callPromptIssue())
        }
    }

    @Test
    fun `stock Android shows a standard on reading as on`() {
        val readiness = evaluate(input(brand = OemBrand.OTHER))
        assertEquals(CategoryLevel.ON, readiness.messageLevel)
        assertEquals(CategoryLevel.ON, readiness.callLevel)
    }

    @Test
    fun `Samsung keeps a standard on reading at need-to-check`() {
        val readiness = evaluate(input(brand = OemBrand.SAMSUNG))
        assertEquals(CategoryLevel.UNKNOWN, readiness.messageLevel)
        assertEquals(CategoryLevel.UNKNOWN, readiness.callLevel)
    }

    @Test
    fun `China skins keep negative levels as read`() {
        val low = evaluate(
            input(
                brand = OemBrand.XIAOMI,
                message = ChannelReading(IMPORTANCE_LOW, hasSound = true),
                call = ChannelReading(IMPORTANCE_LOW, hasSound = true),
            )
        )
        assertEquals(CategoryLevel.NO_BANNER, low.messageLevel)
        assertEquals(CategoryLevel.NO_BANNER, low.callLevel)
    }

    @Test
    fun `non-China brands report HIGH as OK`() {
        listOf(OemBrand.OTHER, OemBrand.SAMSUNG).forEach { brand ->
            val readiness = evaluate(input(brand = brand))
            assertEquals(ReadinessStatus.OK, readiness.status(ReadinessItemId.N2), "brand=$brand")
            assertEquals(ReadinessStatus.OK, readiness.status(ReadinessItemId.C1))
        }
    }

    @Test
    fun `C3 does not exist on brands without vendor settings`() {
        assertNull(evaluate(input(brand = OemBrand.OTHER)).item(ReadinessItemId.C3))
        assertNull(evaluate(input(brand = OemBrand.SAMSUNG)).item(ReadinessItemId.C3))
    }

    // T5
    @Test
    fun `before API 26 there are no channels to fail`() {
        val readiness = evaluate(input(sdk = 24, message = null, call = null, fullScreen = null))
        assertEquals(CategoryLevel.ON, readiness.messageLevel)
        assertEquals(ReadinessStatus.OK, readiness.status(ReadinessItemId.C1))
    }

    @Test
    fun `startup prompt keys off a denied full-screen permission only`() {
        val fullScreenDenied = evaluate(input(fullScreen = false))
        assertEquals(ReadinessIssue.FULL_SCREEN_DENIED, fullScreenDenied.callPromptIssue())
        assertNull(evaluate(input()).callPromptIssue())
    }

    @Test
    fun `a low CALL category shows on the settings page but does not prompt at startup`() {
        val lowCall = evaluate(input(call = ChannelReading(IMPORTANCE_LOW, hasSound = false)))
        assertEquals(ReadinessStatus.PROBLEM, lowCall.status(ReadinessItemId.C1))
        assertNull(lowCall.callPromptIssue())
    }

    @Test
    fun `startup prompt ignores the vendor row and every other item`() {
        val xiaomi = evaluate(input(brand = OemBrand.XIAOMI))
        assertNull(xiaomi.callPromptIssue())
        assertNull(evaluate(input(call = null)).callPromptIssue())
    }

    // Critical alerts post through CRITICAL_ALERT_V2, so A1 is independent of the CALL category.
    @Test
    fun `A1 follows the critical-alert category and not the CALL category`() {
        val lowCall = evaluate(input(call = ChannelReading(IMPORTANCE_LOW, hasSound = false)))
        assertEquals(ReadinessStatus.PROBLEM, lowCall.status(ReadinessItemId.C1))
        assertEquals(ReadinessStatus.OK, lowCall.status(ReadinessItemId.A1))

        val low = evaluate(input(critical = ChannelReading(IMPORTANCE_LOW, hasSound = true)))
        assertEquals(ReadinessIssue.CRITICAL_CATEGORY_LOW_IMPORTANCE, low.item(ReadinessItemId.A1)?.issue)
        val off = evaluate(input(critical = ChannelReading(IMPORTANCE_NONE, hasSound = true)))
        assertEquals(ReadinessIssue.CRITICAL_CATEGORY_OFF, off.item(ReadinessItemId.A1)?.issue)
        assertEquals(ReadinessStatus.UNKNOWN, evaluate(input(critical = null)).status(ReadinessItemId.A1))
        assertEquals(ReadinessStatus.OK, evaluate(input()).status(ReadinessItemId.A1))
    }
}
