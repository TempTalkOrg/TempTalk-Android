package com.difft.android.chat.group

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton




@Singleton
class GroupMemberWriter @Inject constructor() {

    



    suspend fun replaceAllForGroup(
        gid: String,
        serverMembers: List<GroupMemberContactorModel>,
        commitAllowed: (() -> Boolean)? = null,
    ) {
        require(gid.isNotEmpty()) {
            "GroupMemberWriter.replaceAllForGroup: gid must not be empty (W5 stub-row contract guard)"
        }
        withContext(Dispatchers.IO) {
            try {
                requireGroupWriteSession(commitAllowed)
                
                
                
                
                
                
                
                
                val verifiedSigByUid: Map<String, String?> = wcdb.groupMemberContactor
                    .getAllObjects(DBGroupMemberContactorModel.gid.eq(gid))
                    .filter { it.signatureVerify == true }
                    .associate { (it.id ?: "") to it.uidSignature }

                serverMembers.forEach { m ->
                    val uid = m.id ?: return@forEach
                    m.signatureVerify =
                        if (verifiedSigByUid.containsKey(uid) && verifiedSigByUid[uid] == m.uidSignature) true else null
                }

                requireGroupWriteSession(commitAllowed)
                wcdb.groupMemberContactor.deleteObjects(DBGroupMemberContactorModel.gid.eq(gid))
                if (serverMembers.isNotEmpty()) {
                    wcdb.groupMemberContactor.insertObjects(serverMembers)
                }
                L.i { "[GroupMemberWriter] replaced gid=$gid count=${serverMembers.size}" }
            } catch (e: CancellationException) {
                
                throw e
            } catch (e: Exception) {
                
                
                
                
                
                L.e { "[GroupMemberWriter] replaceAllForGroup failed gid=$gid: ${e.stackTraceToString()}" }
                throw e
            }
        }
    }
}
