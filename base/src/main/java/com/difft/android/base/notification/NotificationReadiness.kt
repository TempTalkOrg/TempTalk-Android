package com.difft.android.base.notification

import android.app.Notification
import android.app.NotificationManager

/** Tri-state result of one readiness check: unreadable state is never reported as [OK]. */
enum class ReadinessStatus { OK, PROBLEM, UNKNOWN }

/** The checks of the readiness model. N = notifications, C = incoming calls. */
enum class ReadinessItemId {
    /** App-level notification switch and POST_NOTIFICATIONS. */
    N1,

    /** MESSAGE category: blocked, silent or without banner. */
    N2,

    /** CALL category importance. */
    C1,

    /** Full-screen intent permission. */
    C2,

    /** OEM "background pop-up / show on lock screen" switch; it cannot be read, so it is always "need check". */
    C3,

    /** CRITICAL_ALERT_V2 category importance: critical alerts post through it, not through CALL. */
    A1,
}

/** Why an item is not OK. One issue maps to one hint text and one fix action. */
enum class ReadinessIssue {
    APP_NOTIFICATIONS_OFF,
    MESSAGE_CATEGORY_OFF,
    MESSAGE_CATEGORY_SILENT,
    MESSAGE_CATEGORY_NO_BANNER,
    MESSAGE_CATEGORY_LOCK_HIDDEN,
    CALL_CATEGORY_OFF,
    CALL_CATEGORY_NO_BANNER,
    CALL_CATEGORY_LOCK_HIDDEN,
    FULL_SCREEN_DENIED,
    OEM_POPUP_CHECK,
    CRITICAL_CATEGORY_OFF,
    CRITICAL_CATEGORY_LOW_IMPORTANCE,
}

data class ReadinessItem(
    val id: ReadinessItemId,
    val status: ReadinessStatus,
    val issue: ReadinessIssue? = null,
)

/** What a message or call row shows: one shared vocabulary, one label per row. */
enum class CategoryLevel { ON, OFF, NO_BANNER, LOCK_HIDDEN, SILENT, UNKNOWN }

/** A notification category as read from the system. */
data class ChannelReading(
    val importance: Int,
    val hasSound: Boolean,
    /** The category's own lock-screen override; [VISIBILITY_NO_OVERRIDE] when none. */
    val lockscreenVisibility: Int = VISIBILITY_NO_OVERRIDE,
) {
    companion object {
        /** `NotificationManager.VISIBILITY_NO_OVERRIDE`, which the SDK stubs do not expose. */
        const val VISIBILITY_NO_OVERRIDE = -1000
    }
}

/** Everything the evaluator needs, already read from the system; no Android calls happen after this. */
data class ReadinessInput(
    val sdkInt: Int,
    val postNotificationsGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val messageGroupBlocked: Boolean,
    /** Null when the MESSAGE category does not exist on an API 26+ device. */
    val messageChannel: ChannelReading?,
    /** Null when the CALL category does not exist on an API 26+ device. */
    val callChannel: ChannelReading?,
    /** Null when the CRITICAL_ALERT_V2 category does not exist on an API 26+ device. */
    val criticalChannel: ChannelReading?,
    /** Null where the permission does not exist as a runtime setting (below API 34). */
    val canUseFullScreenIntent: Boolean?,
    val oemBrand: OemBrand,
)

data class NotificationReadiness(
    val items: List<ReadinessItem>,
    /** The skin the vendor-specific wording and C3 apply to. */
    val oemBrand: OemBrand = OemBrand.OTHER,
) {
    fun item(id: ReadinessItemId): ReadinessItem? = items.firstOrNull { it.id == id }

    /** The message row: app notifications off wins, otherwise the MESSAGE category's own level. */
    val messageLevel: CategoryLevel
        get() = if (item(ReadinessItemId.N1)?.status == ReadinessStatus.PROBLEM) CategoryLevel.OFF else levelOf(item(ReadinessItemId.N2))

    /** The call row, with the same rules and words as the message row. */
    val callLevel: CategoryLevel
        get() = if (item(ReadinessItemId.N1)?.status == ReadinessStatus.PROBLEM) CategoryLevel.OFF else levelOf(item(ReadinessItemId.C1))

    /**
     * A standard "on" reading becomes [CategoryLevel.UNKNOWN] when the brand's vendor banner and
     * lock-screen switches are not readable; negative levels are always shown as read.
     */
    private fun levelOf(item: ReadinessItem?): CategoryLevel = when {
        item == null || item.status == ReadinessStatus.UNKNOWN -> CategoryLevel.UNKNOWN
        item.status == ReadinessStatus.OK ->
            if (oemBrand.vendorAlertSwitchesReadable) CategoryLevel.ON else CategoryLevel.UNKNOWN
        else -> when (item.issue) {
            ReadinessIssue.MESSAGE_CATEGORY_OFF, ReadinessIssue.CALL_CATEGORY_OFF -> CategoryLevel.OFF
            ReadinessIssue.MESSAGE_CATEGORY_NO_BANNER, ReadinessIssue.CALL_CATEGORY_NO_BANNER -> CategoryLevel.NO_BANNER
            ReadinessIssue.MESSAGE_CATEGORY_LOCK_HIDDEN, ReadinessIssue.CALL_CATEGORY_LOCK_HIDDEN -> CategoryLevel.LOCK_HIDDEN
            ReadinessIssue.MESSAGE_CATEGORY_SILENT -> CategoryLevel.SILENT
            else -> CategoryLevel.UNKNOWN
        }
    }

    /**
     * The issue to raise as a startup call prompt, or null. Only a denied full-screen permission (C2)
     * qualifies, so the prompt reaches no more users than before the readiness model; a low CALL
     * category (C1) is shown on the settings page only.
     */
    fun callPromptIssue(): ReadinessIssue? =
        item(ReadinessItemId.C2)?.takeIf { it.status == ReadinessStatus.PROBLEM }?.issue
}

/** Pure evaluation of the readiness model; see the design in issue #1350. */
object NotificationReadinessEvaluator {

    private const val API_O = 26
    private const val API_U = 34

    fun evaluate(input: ReadinessInput): NotificationReadiness {
        val items = buildList {
            add(evaluateN1(input))
            add(evaluateN2(input))
            add(evaluateC1(input))
            add(evaluateC2(input))
            evaluateC3(input)?.let { add(it) }
            add(evaluateA1(input))
        }
        return NotificationReadiness(items, input.oemBrand)
    }

    private fun evaluateN1(input: ReadinessInput): ReadinessItem =
        if (!input.postNotificationsGranted || !input.appNotificationsEnabled) {
            ReadinessItem(ReadinessItemId.N1, ReadinessStatus.PROBLEM, ReadinessIssue.APP_NOTIFICATIONS_OFF)
        } else {
            ReadinessItem(ReadinessItemId.N1, ReadinessStatus.OK)
        }

    private fun evaluateN2(input: ReadinessInput): ReadinessItem = evaluateCategory(
        id = ReadinessItemId.N2,
        sdkInt = input.sdkInt,
        channel = input.messageChannel,
        blocked = input.messageGroupBlocked,
        checkSound = true,
        off = ReadinessIssue.MESSAGE_CATEGORY_OFF,
        noBanner = ReadinessIssue.MESSAGE_CATEGORY_NO_BANNER,
        lockHidden = ReadinessIssue.MESSAGE_CATEGORY_LOCK_HIDDEN,
        silent = ReadinessIssue.MESSAGE_CATEGORY_SILENT,
    )

    private fun evaluateC1(input: ReadinessInput): ReadinessItem = evaluateCategory(
        id = ReadinessItemId.C1,
        sdkInt = input.sdkInt,
        channel = input.callChannel,
        blocked = false,
        checkSound = false,
        off = ReadinessIssue.CALL_CATEGORY_OFF,
        noBanner = ReadinessIssue.CALL_CATEGORY_NO_BANNER,
        lockHidden = ReadinessIssue.CALL_CATEGORY_LOCK_HIDDEN,
        silent = null,
    )

    /**
     * The shared rule for the message and call categories, in precedence order: blocked, missing or
     * NONE is off; unspecified importance is unknown; anything below HIGH does not pop up; a SECRET
     * lock-screen override hides it on the lock screen; and, for messages only, HIGH without a sound
     * is silent. ROM-specific switches that only lower standard importance are covered by the
     * importance rule, never by brand.
     */
    @Suppress("LongParameterList")
    private fun evaluateCategory(
        id: ReadinessItemId,
        sdkInt: Int,
        channel: ChannelReading?,
        blocked: Boolean,
        checkSound: Boolean,
        off: ReadinessIssue,
        noBanner: ReadinessIssue,
        lockHidden: ReadinessIssue,
        silent: ReadinessIssue?,
    ): ReadinessItem {
        if (sdkInt < API_O) return ReadinessItem(id, ReadinessStatus.OK)
        val issue = when {
            blocked || channel == null -> off
            channel.importance == NotificationManager.IMPORTANCE_UNSPECIFIED ->
                return ReadinessItem(id, ReadinessStatus.UNKNOWN)
            channel.importance == NotificationManager.IMPORTANCE_NONE -> off
            channel.importance < NotificationManager.IMPORTANCE_HIGH -> noBanner
            channel.lockscreenVisibility == Notification.VISIBILITY_SECRET -> lockHidden
            checkSound && !channel.hasSound -> silent
            else -> null
        }
        return if (issue == null) ReadinessItem(id, ReadinessStatus.OK) else ReadinessItem(id, ReadinessStatus.PROBLEM, issue)
    }

    private fun evaluateA1(input: ReadinessInput): ReadinessItem {
        if (input.sdkInt < API_O) return ReadinessItem(ReadinessItemId.A1, ReadinessStatus.OK)
        val channel = input.criticalChannel
            ?: return ReadinessItem(ReadinessItemId.A1, ReadinessStatus.UNKNOWN)
        return when {
            channel.importance == NotificationManager.IMPORTANCE_UNSPECIFIED ->
                ReadinessItem(ReadinessItemId.A1, ReadinessStatus.UNKNOWN)
            channel.importance == NotificationManager.IMPORTANCE_NONE ->
                ReadinessItem(ReadinessItemId.A1, ReadinessStatus.PROBLEM, ReadinessIssue.CRITICAL_CATEGORY_OFF)
            channel.importance < NotificationManager.IMPORTANCE_HIGH ->
                ReadinessItem(ReadinessItemId.A1, ReadinessStatus.PROBLEM, ReadinessIssue.CRITICAL_CATEGORY_LOW_IMPORTANCE)
            else -> ReadinessItem(ReadinessItemId.A1, ReadinessStatus.OK)
        }
    }

    private fun evaluateC2(input: ReadinessInput): ReadinessItem =
        if (input.sdkInt >= API_U && input.canUseFullScreenIntent == false) {
            ReadinessItem(ReadinessItemId.C2, ReadinessStatus.PROBLEM, ReadinessIssue.FULL_SCREEN_DENIED)
        } else {
            ReadinessItem(ReadinessItemId.C2, ReadinessStatus.OK)
        }

    /**
     * C3 exists only on skins that keep their own pop-up and lock-screen switches. Nothing readable
     * backs it, so it is always "check" and the row leads to the place to confirm it.
     */
    private fun evaluateC3(input: ReadinessInput): ReadinessItem? =
        if (input.oemBrand.isChinaOem) {
            ReadinessItem(ReadinessItemId.C3, ReadinessStatus.UNKNOWN, ReadinessIssue.OEM_POPUP_CHECK)
        } else {
            null
        }
}
