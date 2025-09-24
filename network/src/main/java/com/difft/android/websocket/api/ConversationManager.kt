package com.difft.android.websocket.api

import difft.android.messageserialization.For
import com.difft.android.websocket.api.messages.PublicKeyInfo

interface ConversationManager {
    fun hasPublicKeyInfoData(room: For): Boolean
    fun updatePublicKeyInfoData(room: For): Boolean
    fun updateConversationMemberData(room: For)

    /**
     * if room is For.Group, return all members' public key info
     * if room is For.Account, return only one public key info
     */
    fun getPublicKeyInfos(room: For): List<PublicKeyInfo>

    fun getPublicKeyInfos(ids: List<String>?): List<PublicKeyInfo>?
}