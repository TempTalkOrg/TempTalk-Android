package com.difft.android.chat.common

import android.annotation.SuppressLint
import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import com.difft.android.chat.group.GroupUtil
import difft.android.messageserialization.For
import difft.android.messageserialization.RoomStore
import com.difft.android.messageserialization.db.store.getOrNull
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBRoomModel
import com.difft.android.websocket.api.ConversationManager
import com.difft.android.websocket.api.messages.GetPublicKeysReq
import com.difft.android.websocket.api.messages.PublicKeyInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationManagerImpl @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val roomStore: RoomStore,
    @param:ChativeHttpClientModule.Chat
    private val chatHttpClient: ChativeHttpClient,
    private val gson: Gson,
    private val wcdb: WCDB,
) : ConversationManager {

    override fun hasPublicKeyInfoData(room: For): Boolean {
        return wcdb.room.getFirstObject(DBRoomModel.roomId.eq(room.id))?.publicKeyInfoJson.isNullOrBlank().not()
    }

    override fun updatePublicKeyInfoData(room: For): Boolean {
        val uids = when (room) {
            is For.Group -> {
                GroupUtil.getSingleGroupInfo(context, room.id).blockingFirst()
                    .get().members.map {
                        it.id
                    }
            }

            is For.Account -> {
                listOf(room.id, globalServices.myId)
            }
        }
        val publicKeys = try {
            chatHttpClient.httpService.getPublicKeys(SecureSharedPrefsUtil.getToken(), GetPublicKeysReq(uids)).blockingFirst().data?.keys
        } catch (e: Exception) {
            L.e { "Obtains new publicKeys error forWho ${room.id} ${e.message}" }
            null
        }
        if (publicKeys.isNullOrEmpty()) {
            L.w { "Obtains new publicKeys isNullOrEmpty forWho ${room.id}" }
            return false
        } else {
            roomStore.updatePublicKeyInfo(room, gson.toJson(publicKeys)).blockingAwait()
            return true
        }
    }

    @SuppressLint("CheckResult")
    override fun updateConversationMemberData(room: For) {
        if (room is For.Group) {
            GroupUtil.fetchAndSaveSingleGroupInfo(context, room.id, true).blockingFirst()
        }
    }

    override fun getPublicKeyInfos(room: For): List<PublicKeyInfo> {
        val publicKeys = roomStore.getPublicKeyInfo(room).blockingGet().getOrNull()
        return if (publicKeys.isNullOrEmpty()) {
            emptyList()
        } else {
            gson.fromJson(publicKeys, object : com.google.gson.reflect.TypeToken<List<PublicKeyInfo>>() {}.type)
        }
    }

    override fun getPublicKeyInfos(ids: List<String>?): List<PublicKeyInfo>? {
        if (ids.isNullOrEmpty()) return null
        val publicKeys = try {
            chatHttpClient.httpService.getPublicKeys(SecureSharedPrefsUtil.getToken(), GetPublicKeysReq(ids)).blockingFirst().data?.keys
        } catch (e: Exception) {
            L.e { "Obtains new publicKeys error: $ids ${e.message}" }
            null
        }
        return publicKeys
    }
}