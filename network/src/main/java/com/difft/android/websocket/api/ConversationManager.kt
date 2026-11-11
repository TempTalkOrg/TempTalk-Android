package com.difft.android.websocket.api

import difft.android.messageserialization.For
import com.difft.android.websocket.api.messages.PublicKeyInfo








sealed interface PublicKeyUpdateResult {
    
    data object Updated : PublicKeyUpdateResult

    




    data object ServerEmpty : PublicKeyUpdateResult

    
    data object FetchFailed : PublicKeyUpdateResult

    
    data object EntityInvalid : PublicKeyUpdateResult

    
    data object Unresolved : PublicKeyUpdateResult
}

interface ConversationManager {

    

    











    suspend fun hasPublicKeyInfoData(room: For): Boolean

    




    suspend fun updatePublicKeyInfoData(room: For): Boolean

    





    suspend fun updatePublicKeyInfoDataResult(room: For): PublicKeyUpdateResult

    










    suspend fun classifyEmptyKeys(room: For): PublicKeyUpdateResult

    

    






    suspend fun updatePublicKeyInfoData(uids: List<String>): Boolean

    

    




    suspend fun getPublicKeyInfos(room: For): List<PublicKeyInfo>


    
    suspend fun updateConversationMemberData(room: For)

    
    suspend fun getPublicKeyInfos(ids: List<String>?): List<PublicKeyInfo>?
}
