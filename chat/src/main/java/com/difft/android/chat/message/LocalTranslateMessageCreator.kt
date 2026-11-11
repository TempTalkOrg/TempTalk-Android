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















@Singleton
class LocalTranslateMessageCreator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messageStore: MessageStore,
    private val messageArchiveManager: MessageArchiveManager,
    private val gson: Gson,
) {

    
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
