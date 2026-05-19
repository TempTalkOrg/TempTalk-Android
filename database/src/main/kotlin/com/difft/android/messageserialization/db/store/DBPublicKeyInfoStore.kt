package com.difft.android.messageserialization.db.store

import com.difft.android.base.log.lumberjack.L
import difft.android.messageserialization.PublicKeyInfoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBPublicKeyInfoModel
import org.difft.app.database.models.PublicKeyInfoModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WCDB-backed store. All methods internally switch to [Dispatchers.IO] so
 * callers can invoke them from any coroutine context without worrying about
 * blocking a CPU thread on the default pool.
 */
@Singleton
class DBPublicKeyInfoStore @Inject constructor(
    private val wcdb: WCDB
) : PublicKeyInfoStore {

    override suspend fun upsert(models: List<PublicKeyInfoModel>) {
        if (models.isEmpty()) return
        withContext(Dispatchers.IO) {
            wcdb.publicKeyInfo.insertOrReplaceObjects(models)
        }
        L.i { "[PublicKeyInfoStore] upsert size=${models.size}" }
    }

    override suspend fun getForUids(
        uids: Collection<String>
    ): Map<String, PublicKeyInfoModel> {
        if (uids.isEmpty()) return emptyMap()
        val distinctUids = uids.toSet()
        return withContext(Dispatchers.IO) {
            wcdb.publicKeyInfo.getAllObjects(
                DBPublicKeyInfoModel.uid.`in`(*distinctUids.toTypedArray())
            ).associateBy { it.uid }
        }
    }

    override suspend fun hasAllUids(uids: Collection<String>): Boolean {
        if (uids.isEmpty()) return true
        val distinctUids = uids.toSet()
        val count = withContext(Dispatchers.IO) {
            wcdb.publicKeyInfo.getValue(
                DBPublicKeyInfoModel.uid.count(),
                DBPublicKeyInfoModel.uid.`in`(*distinctUids.toTypedArray())
            )?.int ?: 0
        }
        return count == distinctUids.size
    }

    override suspend fun deleteForUids(uids: Collection<String>) {
        if (uids.isEmpty()) return
        val distinctUids = uids.toSet()
        withContext(Dispatchers.IO) {
            wcdb.publicKeyInfo.deleteObjects(
                DBPublicKeyInfoModel.uid.`in`(*distinctUids.toTypedArray())
            )
        }
        L.i { "[PublicKeyInfoStore] delete size=${distinctUids.size}" }
    }
}
