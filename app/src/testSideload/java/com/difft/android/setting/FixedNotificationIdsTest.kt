package com.difft.android.setting

import com.difft.android.call.service.ForegroundService
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.migration.v2.transfer.MigrationTransferNotificationHelper
import org.junit.Test
import kotlin.test.assertEquals

/**
 * #1354: the merged channels carry notifications from several features, so every fixed
 * notification id must stay distinct; a collision would make one notification replace another.
 */
class FixedNotificationIdsTest {

    private fun privateConst(owner: Class<*>, name: String): Int =
        owner.getDeclaredField(name).apply { isAccessible = true }.getInt(null)

    @Test
    fun `fixed notification ids are pairwise distinct`() {
        val ids = mapOf(
            "ongoingCall" to ForegroundService.DEFAULT_NOTIFICATION_ID,
            "backgroundConnection" to MessageForegroundService.FOREGROUND_ID,
            "messageSummary" to privateConst(MessageNotificationUtil::class.java, "MESSAGE_SUMMARY_NOTIFICATION_ID"),
            "loggedOut" to privateConst(LoggedOutNotifier::class.java, "NOTIFICATION_ID"),
            "upgradeProgress" to AppUpgradeNotifier.NOTIFICATION_ID,
            "upgradeTerminal" to AppUpgradeNotifier.NOTIFICATION_ID_TERMINAL,
            "transferRunning" to MigrationTransferNotificationHelper.NOTIFICATION_ID_RUNNING,
            "transferResult" to MigrationTransferNotificationHelper.NOTIFICATION_ID_RESULT,
        )

        assertEquals(ids.size, ids.values.toSet().size, "ids=$ids")
    }
}
