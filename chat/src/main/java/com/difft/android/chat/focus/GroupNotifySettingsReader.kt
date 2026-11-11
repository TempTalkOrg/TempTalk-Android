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





@Singleton
class GroupNotifySettingsReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userManager: UserManager,
    private val messageNotificationUtil: MessageNotificationUtil,
) {

    
    fun globalLevel(): GlobalNotificationType = typeOf(userManager.getUserData()?.globalNotification)

    
    suspend fun level(gid: String): GlobalNotificationType = withContext(Dispatchers.IO) {
        try {
            val self = wcdb.groupMemberContactor.getFirstObject(
                DBGroupMemberContactorModel.gid.eq(gid).and(DBGroupMemberContactorModel.id.eq(globalServices.myId))
            )
            
            if (self == null || self.useGlobal == true) globalLevel() else typeOf(self.notification)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[FocusGroups] level read failed error=${e.javaClass.simpleName}" }
            globalLevel()
        }
    }

    
    fun soundSupported(): Boolean = messageNotificationUtil.supportConversationNotification()

    
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

    
    fun openSoundSettings(activity: Activity, gid: String, groupName: String) {
        messageNotificationUtil.createChannelForConversation(gid, groupName.ifEmpty { gid })
        messageNotificationUtil.openMessageNotificationChannelSettings(activity, gid)
    }

    private fun typeOf(value: Int?): GlobalNotificationType =
        GlobalNotificationType.entries.firstOrNull { it.value == value } ?: GlobalNotificationType.ALL
}
