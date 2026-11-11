package com.difft.android.chat.common

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.group.GroupUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.websocket.api.ConversationManager
import com.difft.android.websocket.api.PublicKeyUpdateResult
import com.difft.android.websocket.api.messages.GetPublicKeysReq
import com.difft.android.websocket.api.messages.PublicKeyInfo
import difft.android.messageserialization.For
import difft.android.messageserialization.PublicKeyInfoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.members
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.PublicKeyInfoModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationManagerImpl @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val chatHttpClient: ChativeHttpClient,
    private val publicKeyInfoStore: PublicKeyInfoStore,
    private val groupUtil: GroupUtil,
    private val wcdb: WCDB,
) : ConversationManager {

    








    private suspend fun resolveUids(room: For): List<String> = when (room) {
        is For.Account -> listOf(room.id, globalServices.myId)
        is For.Group -> groupUtil.getSingleGroupInfo(room.id)
            ?.members
            ?.mapNotNull { it.id }
            ?: emptyList()
    }

    
















    private fun resolveUidsLocalOnly(room: For): List<String> = when (room) {
        is For.Account -> listOf(room.id, globalServices.myId)
        is For.Group -> wcdb.group
            .getFirstObject(DBGroupModel.gid.eq(room.id))
            ?.members
            ?.mapNotNull { it.id }
            ?: emptyList()
    }

    override suspend fun hasPublicKeyInfoData(room: For): Boolean = withContext(Dispatchers.IO) {
        
        
        
        val uids = resolveUidsLocalOnly(room)  
        if (uids.isEmpty()) {
            L.i { "[ConversationManager] hasPkinfo room=${room.id} uids=0 result=false reason=empty_local_resolution" }
            return@withContext false
        }
        val cached = publicKeyInfoStore.hasAllUids(uids)
        L.i { "[ConversationManager] hasPkinfo room=${room.id} uids=${uids.size} result=$cached" }
        cached
    }

    override suspend fun updatePublicKeyInfoData(room: For): Boolean {
        
        val uids = resolveUids(room)
        return updatePublicKeyInfoData(uids)
    }

    override suspend fun updatePublicKeyInfoData(uids: List<String>): Boolean {
        if (uids.isEmpty()) return true  

        val serverKeys: List<PublicKeyInfo>? = try {
            chatHttpClient.httpService
                .getPublicKeys(GetPublicKeysReq(uids))
                .data
                ?.keys
        } catch (e: Exception) {
            L.e { "[ConversationManager] getPublicKeys failed uids=${uids.size} err=${e.stackTraceToString()}" }
            null
        }

        if (serverKeys.isNullOrEmpty()) {
            L.w { "[ConversationManager] getPublicKeys empty uids=${uids.size}" }
            return false
        }

        val models = serverKeys.map { wire -> wire.toModel() }
        publicKeyInfoStore.upsert(models)
        L.i { "[ConversationManager] update uids=${uids.size} persisted=${models.size}" }
        return true
    }

    












    private sealed interface UidResolution {
        data class Resolved(val uids: List<String>) : UidResolution
        data object Invalid : UidResolution
        data object Unresolved : UidResolution
    }

    private suspend fun resolveUidsWithStatus(room: For): UidResolution = when (room) {
        is For.Account -> UidResolution.Resolved(listOf(room.id, globalServices.myId))
        is For.Group -> {
            val group = groupUtil.getSingleGroupInfo(room.id)  
            when {
                group == null -> UidResolution.Unresolved          
                (group.status ?: 0) != 0 -> UidResolution.Invalid  
                else -> withContext(Dispatchers.IO) {              
                    UidResolution.Resolved(group.members.mapNotNull { it.id })
                }
            }
        }
    }

    override suspend fun updatePublicKeyInfoDataResult(room: For): PublicKeyUpdateResult {
        
        when (val resolution = resolveUidsWithStatus(room)) {
            is UidResolution.Invalid -> {
                L.w { "[ConversationManager] updateResult room=${room.id} EntityInvalid (group status!=0)" }
                return PublicKeyUpdateResult.EntityInvalid
            }

            is UidResolution.Unresolved -> {
                L.w { "[ConversationManager] updateResult room=${room.id} Unresolved (group==null, transient)" }
                return PublicKeyUpdateResult.Unresolved
            }

            is UidResolution.Resolved -> {
                val uids = resolution.uids
                if (uids.isEmpty()) {
                    
                    L.w { "[ConversationManager] updateResult room=${room.id} ServerEmpty (resolved 0 uids)" }
                    return PublicKeyUpdateResult.ServerEmpty
                }

                
                val serverKeys: List<PublicKeyInfo>? = try {
                    chatHttpClient.httpService
                        .getPublicKeys(GetPublicKeysReq(uids))
                        .data
                        ?.keys
                } catch (e: Exception) {
                    L.e { "[ConversationManager] updateResult getPublicKeys failed uids=${uids.size} err=${e.stackTraceToString()}" }
                    return PublicKeyUpdateResult.FetchFailed  
                }

                return when {
                    serverKeys == null -> {
                        
                        L.w { "[ConversationManager] updateResult room=${room.id} FetchFailed (server body null)" }
                        PublicKeyUpdateResult.FetchFailed
                    }

                    serverKeys.isEmpty() -> {
                        
                        L.w { "[ConversationManager] updateResult room=${room.id} ServerEmpty uids=${uids.size}" }
                        PublicKeyUpdateResult.ServerEmpty
                    }

                    else -> {
                        publicKeyInfoStore.upsert(serverKeys.map { it.toModel() })
                        L.i { "[ConversationManager] updateResult room=${room.id} Updated uids=${uids.size} persisted=${serverKeys.size}" }
                        PublicKeyUpdateResult.Updated
                    }
                }
            }
        }
    }

    override suspend fun classifyEmptyKeys(room: For): PublicKeyUpdateResult =
        
        
        
        
        
        updatePublicKeyInfoDataResult(room)

    override suspend fun getPublicKeyInfos(room: For): List<PublicKeyInfo> {
        val uids = resolveUids(room)
        if (uids.isEmpty()) {
            L.w { "[ConversationManager] getPkinfos room=${room.id} uids=0 (empty resolution)" }
            return emptyList()
        }

        val byUid = publicKeyInfoStore.getForUids(uids)
        
        val result = uids.mapNotNull { uid -> byUid[uid]?.toWire() }
        L.i { "[ConversationManager] getPkinfos room=${room.id} requested=${uids.size} found=${result.size}" }
        return result
    }

    override suspend fun updateConversationMemberData(room: For) {
        
        if (room is For.Group) {
            groupUtil.fetchAndSaveSingleGroupInfo(room.id, true)
        }
    }

    override suspend fun getPublicKeyInfos(ids: List<String>?): List<PublicKeyInfo>? {
        
        if (ids.isNullOrEmpty()) return null
        return try {
            chatHttpClient.httpService
                .getPublicKeys(GetPublicKeysReq(ids))
                .data
                ?.keys
        } catch (e: Exception) {
            L.e { "[ConversationManager] getPublicKeys direct failed ids=${ids.size} err=${e.stackTraceToString()}" }
            null
        }
    }

    

    private fun PublicKeyInfo.toModel(): PublicKeyInfoModel =
        PublicKeyInfoModel().also {
            it.uid = this.uid
            it.identityKey = this.identityKey
            it.registrationId = this.registrationId
            it.resetIdentityKeyTime = this.resetIdentityKeyTime
        }

    private fun PublicKeyInfoModel.toWire(): PublicKeyInfo =
        PublicKeyInfo(
            uid = this.uid,
            identityKey = this.identityKey,
            registrationId = this.registrationId,
            resetIdentityKeyTime = this.resetIdentityKeyTime,
        )
}
