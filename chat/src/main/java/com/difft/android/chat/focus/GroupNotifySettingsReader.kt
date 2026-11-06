package com.difft.android.chat.focus

import android.app.Activity
import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.util.MessageSoundLabel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads what a group's settings page shows beside the focus rows: the effective message
 * notification level (own value, or the global one when the group follows it) and the sound row.
 */
@Singleton
class GroupNotifySettingsReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userManager: UserManager,
    private val messageNotificationUtil: MessageNotificationUtil,
) {

    /** The global group notification level, [GlobalNotificationType.ALL] when unset. */
    fun globalLevel(): GlobalNotificationType = typeOf(userManager.getUserData()?.globalNotification)

    /** The level this group applies: its own setting, or the global one when it follows it. */
    suspend fun level(gid: String): GlobalNotificationType = withContext(Dispatchers.IO) {
        try {
            val self = wcdb.groupMemberContactor.getFirstObject(
                DBGroupMemberContactorModel.gid.eq(gid).and(DBGroupMemberContactorModel.id.eq(globalServices.myId))
            )
            // No member row yet reads as "follows global", the same as GroupNotifyGate.readLevel.
            if (self == null || self.useGlobal == true) globalLevel() else typeOf(self.notification)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[FocusGroups] level read failed error=${e.javaClass.simpleName}" }
            globalLevel()
        }
    }

    /** Whether the per-conversation sound row exists (API 26+). */
    fun soundSupported(): Boolean = messageNotificationUtil.supportConversationNotification()

    /** The sound label the group plays, null when unavailable. */
    suspend fun soundLabel(gid: String): String? = withContext(Dispatchers.IO) {
        try {
            MessageSoundLabel.readForConversation(context, gid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            L.w { "[FocusGroups] sound read failed error=${e.javaClass.simpleName}" }
            null
        }
    }

    /** Opens the system sound settings of the group's own notification channel (created on first use). */
    fun openSoundSettings(activity: Activity, gid: String, groupName: String) {
        messageNotificationUtil.createChannelForConversation(gid, groupName.ifEmpty { gid })
        messageNotificationUtil.openMessageNotificationChannelSettings(activity, gid)
    }

    private fun typeOf(value: Int?): GlobalNotificationType =
        GlobalNotificationType.entries.firstOrNull { it.value == value } ?: GlobalNotificationType.ALL
}
