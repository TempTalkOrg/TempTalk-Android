package difft.android.messageserialization

import org.difft.app.database.models.PublicKeyInfoModel







interface PublicKeyInfoStore {

    



    suspend fun upsert(models: List<PublicKeyInfoModel>)

    




    suspend fun getForUids(uids: Collection<String>): Map<String, PublicKeyInfoModel>

    




    suspend fun hasAllUids(uids: Collection<String>): Boolean

    



    suspend fun deleteForUids(uids: Collection<String>)
}
