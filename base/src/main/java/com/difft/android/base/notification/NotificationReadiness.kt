package com.difft.android.base.notification

import android.app.Notification
import android.app.NotificationManager


enum class ReadinessStatus { OK, PROBLEM, UNKNOWN }


enum class ReadinessItemId {
    
    N1,

    
    N2,

    
    C1,

    
    C2,

    



    C3,

    
    A1,
}


enum class ReadinessIssue {
    APP_NOTIFICATIONS_OFF,
    MESSAGE_CATEGORY_OFF,
    MESSAGE_CATEGORY_SILENT,
    MESSAGE_CATEGORY_NO_BANNER,
    MESSAGE_CATEGORY_LOCK_HIDDEN,

    



    MESSAGE_CATEGORY_NEVER_SET,
    CALL_CATEGORY_OFF,
    CALL_CATEGORY_NO_BANNER,
    CALL_CATEGORY_LOCK_HIDDEN,
    CALL_CATEGORY_NEVER_SET,
    FULL_SCREEN_DENIED,
    OEM_POPUP_CHECK,
    CRITICAL_CATEGORY_OFF,
    CRITICAL_CATEGORY_LOW_IMPORTANCE,
    CRITICAL_CATEGORY_LOCK_HIDDEN,
    CRITICAL_CATEGORY_NEVER_SET,
}

data class ReadinessItem(
    val id: ReadinessItemId,
    val status: ReadinessStatus,
    val issue: ReadinessIssue? = null,
)






enum class CategoryLevel { ON, OFF, NO_BANNER, LOCK_HIDDEN, SILENT, NEEDS_CHECK, UNKNOWN, CONFIRMED, TESTED_OK }


enum class CallPopupLevel { TESTED_OK, NOT_SHOWN, UNTESTED }


data class ChannelReading(
    val importance: Int,
    val hasSound: Boolean,
    
    val lockscreenVisibility: Int = VISIBILITY_NO_OVERRIDE,
    



    val userSetImportance: Boolean = true,
) {
    companion object {
        
        const val VISIBILITY_NO_OVERRIDE = -1000
    }
}


data class ReadinessInput(
    val sdkInt: Int,
    val postNotificationsGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val messageGroupBlocked: Boolean,
    
    val messageChannel: ChannelReading?,
    
    val callChannel: ChannelReading?,
    
    val criticalChannel: ChannelReading?,
    
    val canUseFullScreenIntent: Boolean?,
    val oemBrand: OemBrand,
    
    val testResults: NotificationTestResults = NotificationTestResults.NONE,
    




    val seenCategories: Set<String> = emptySet(),
)

data class NotificationReadiness(
    val items: List<ReadinessItem>,
    
    val oemBrand: OemBrand = OemBrand.OTHER,
    val testResults: NotificationTestResults = NotificationTestResults.NONE,
) {
    fun item(id: ReadinessItemId): ReadinessItem? = items.firstOrNull { it.id == id }

    



    val messageLevel: CategoryLevel
        get() = if (item(ReadinessItemId.N1)?.status == ReadinessStatus.PROBLEM) {
            CategoryLevel.OFF
        } else {
            val confirmed = testResults.banner?.outcome == NotificationTestOutcome.OK &&
                testResults.lockscreen?.outcome == NotificationTestOutcome.OK
            levelOf(item(ReadinessItemId.N2), if (confirmed) CategoryLevel.CONFIRMED else null)
        }

    
    val callLevel: CategoryLevel
        get() = if (item(ReadinessItemId.N1)?.status == ReadinessStatus.PROBLEM) {
            CategoryLevel.OFF
        } else {
            val tested = testResults.call?.outcome == NotificationTestOutcome.OK
            levelOf(item(ReadinessItemId.C1), if (tested) CategoryLevel.TESTED_OK else null)
        }

    
    val callPopupLevel: CallPopupLevel
        get() = when (testResults.call?.outcome) {
            NotificationTestOutcome.OK -> CallPopupLevel.TESTED_OK
            NotificationTestOutcome.FAIL -> CallPopupLevel.NOT_SHOWN
            null -> CallPopupLevel.UNTESTED
        }

    




    private fun levelOf(item: ReadinessItem?, testedLevel: CategoryLevel?): CategoryLevel = when {
        item == null || item.status == ReadinessStatus.UNKNOWN -> CategoryLevel.UNKNOWN
        item.status == ReadinessStatus.OK ->
            if (oemBrand.vendorAlertSwitchesReadable) CategoryLevel.ON else testedLevel ?: CategoryLevel.UNKNOWN
        else -> when (item.issue) {
            ReadinessIssue.MESSAGE_CATEGORY_OFF, ReadinessIssue.CALL_CATEGORY_OFF -> CategoryLevel.OFF
            ReadinessIssue.MESSAGE_CATEGORY_NO_BANNER, ReadinessIssue.CALL_CATEGORY_NO_BANNER -> CategoryLevel.NO_BANNER
            ReadinessIssue.MESSAGE_CATEGORY_LOCK_HIDDEN, ReadinessIssue.CALL_CATEGORY_LOCK_HIDDEN -> CategoryLevel.LOCK_HIDDEN
            ReadinessIssue.MESSAGE_CATEGORY_SILENT -> CategoryLevel.SILENT
            ReadinessIssue.MESSAGE_CATEGORY_NEVER_SET, ReadinessIssue.CALL_CATEGORY_NEVER_SET -> CategoryLevel.NEEDS_CHECK
            else -> CategoryLevel.UNKNOWN
        }
    }

    




    fun callPromptIssue(): ReadinessIssue? =
        item(ReadinessItemId.C2)?.takeIf { it.status == ReadinessStatus.PROBLEM }?.issue
}


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
        return NotificationReadiness(items, input.oemBrand, input.testResults)
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
        neverSet = ReadinessIssue.MESSAGE_CATEGORY_NEVER_SET.takeIf { input.neverSetApplies(NotificationChannelIds.MESSAGE) },
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
        neverSet = ReadinessIssue.CALL_CATEGORY_NEVER_SET.takeIf { input.neverSetApplies(NotificationChannelIds.CALL) },
    )

    







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
        neverSet: ReadinessIssue?,
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
            !channel.userSetImportance -> neverSet
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
            channel.lockscreenVisibility == Notification.VISIBILITY_SECRET ->
                ReadinessItem(ReadinessItemId.A1, ReadinessStatus.PROBLEM, ReadinessIssue.CRITICAL_CATEGORY_LOCK_HIDDEN)
            input.neverSetApplies(NotificationChannelIds.CRITICAL_ALERT) && !channel.userSetImportance ->
                ReadinessItem(ReadinessItemId.A1, ReadinessStatus.PROBLEM, ReadinessIssue.CRITICAL_CATEGORY_NEVER_SET)
            else -> ReadinessItem(ReadinessItemId.A1, ReadinessStatus.OK)
        }
    }

    
    private fun ReadinessInput.neverSetApplies(channelId: String): Boolean =
        oemBrand == OemBrand.XIAOMI && channelId !in seenCategories

    private fun evaluateC2(input: ReadinessInput): ReadinessItem =
        if (input.sdkInt >= API_U && input.canUseFullScreenIntent == false) {
            ReadinessItem(ReadinessItemId.C2, ReadinessStatus.PROBLEM, ReadinessIssue.FULL_SCREEN_DENIED)
        } else {
            ReadinessItem(ReadinessItemId.C2, ReadinessStatus.OK)
        }

    



    private fun evaluateC3(input: ReadinessInput): ReadinessItem? {
        if (!input.oemBrand.isChinaOem) return null
        val status = when (input.testResults.call?.outcome) {
            NotificationTestOutcome.OK -> ReadinessStatus.OK
            NotificationTestOutcome.FAIL -> ReadinessStatus.PROBLEM
            null -> ReadinessStatus.UNKNOWN
        }
        return ReadinessItem(ReadinessItemId.C3, status, ReadinessIssue.OEM_POPUP_CHECK.takeIf { status != ReadinessStatus.OK })
    }
}
