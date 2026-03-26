package com.difft.android.websocket.api

import difft.android.messageserialization.For
import com.difft.android.websocket.api.messages.PublicKeyInfo

interface ConversationManager {
    fun hasPublicKeyInfoData(room: For): Boolean
    suspend fun updatePublicKeyInfoData(room: For): Boolean
    suspend fun updateConversationMemberData(room: For)

    /**
     * if room is For.Group, return all members' public key info
     * if room is For.Account, return only one public key info
     */
    suspend fun getPublicKeyInfos(room: For): List<PublicKeyInfo>

    suspend fun getPublicKeyInfos(ids: List<String>?): List<PublicKeyInfo>?
}