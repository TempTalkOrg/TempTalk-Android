package com.difft.android.chat.focus

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.group.getDisplayAvatarData
import com.difft.android.network.group.GroupAvatarData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton


data class FocusGroupItem(
    val gid: String,
    val name: String,
    val memberCount: Int,
    val avatar: GroupAvatarData?,
)


@Singleton
class FocusGroupDirectory @Inject constructor() {

    
    suspend fun name(gid: String): String? = withContext(Dispatchers.IO) {
        try {
            wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid))?.name
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[FocusGroups] name read failed error=${e.javaClass.simpleName}" }
            null
        }
    }

    
    suspend fun activeGroups(): List<FocusGroupItem> = withContext(Dispatchers.IO) {
        try {
            wcdb.group.getAllObjects(DBGroupModel.status.eq(0))
                .mapNotNull { group ->
                    val gid = group.gid ?: return@mapNotNull null
                    FocusGroupItem(
                        gid = gid,
                        name = group.name.orEmpty(),
                        memberCount = wcdb.groupMemberContactor
                            .getValue(DBGroupMemberContactorModel.databaseId.count(), DBGroupMemberContactorModel.gid.eq(gid))
                            ?.int ?: 0,
                        avatar = group.getDisplayAvatarData(),
                    )
                }
                .sortedBy { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[FocusGroups] list read failed error=${e.javaClass.simpleName}" }
            emptyList()
        }
    }
}
