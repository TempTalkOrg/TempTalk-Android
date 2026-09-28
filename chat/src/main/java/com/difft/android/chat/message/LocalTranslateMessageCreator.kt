package com.difft.android.chat.message

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.common.SendType
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.translate.translateValueDescRes
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.NotifyMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local system message announcing a conversation auto-translate change, mirroring iOS and Desktop.
 *
 * Deliberately separate from [LocalMessageCreator]: that file is already at the size limit, and this
 * notice shares none of its name-resolution machinery.
 *
 * The message is never sent to the server — it is persisted with `putWhenNonExist` and nothing
 * enqueues a send job. It is also excluded from the conversation-list preview (see
 * `WCDBUpdateService`), so it neither reorders the list nor replaces the preview text, matching iOS
 * where the equivalent `TSInfoMessage` leaves `shouldAffectThreadSorting = NO`. Being a
 * `NotifyMessage` (`type = TYPE_NOTIFY`) it is excluded from the unread query for free.
 *
 * Idempotency is the caller's: this is only invoked when the stored value actually changed.
 */
@Singleton
class LocalTranslateMessageCreator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messageStore: MessageStore,
    private val messageArchiveManager: MessageArchiveManager,
    private val gson: Gson,
) {

    /** @param translateSetting the newly stored target: 0 off, 1 Chinese, 2 English. */
    suspend fun createTranslateSettingChangedMessage(forWhat: For, translateSetting: Int) {
        withContext(Dispatchers.IO) {
            runCatching {
                val myId = globalServices.myId
                val timeStamp = System.currentTimeMillis()
                val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).toInt()
                val messageId = LocalMessageCreator.generateMessageId(timeStamp, myId)

                val ttNotify = TTNotifyMessage(
                    data = null,
                    notifyTime = timeStamp,
                    notifyType = TTNotifyMessage.NOTIFY_ACTION_TYPE_TRANSLATE_SETTING_CHANGED,
                    // Same sentence the settings screen shows under the options, as on iOS.
                    showContent = context.getString(translateValueDescRes(translateSetting)),
                    display = 1,
                )

                val message = NotifyMessage(
                    messageId,
                    For.Account(myId),
                    forWhat,
                    timeStamp,
                    timeStamp,
                    System.currentTimeMillis(),
                    SendType.Sent.rawValue,
                    expiresInSeconds,
                    0,
                    0,
                    0,
                    gson.toJson(ttNotify),
                )

                messageStore.putWhenNonExist(message)
                L.i { "[Translate] local notice inserted room=${forWhat.id} value=$translateSetting" }
            }.onFailure {
                L.e { "[Translate] local notice failed room=${forWhat.id} value=$translateSetting: ${it.stackTraceToString()}" }
            }
        }
    }
}
