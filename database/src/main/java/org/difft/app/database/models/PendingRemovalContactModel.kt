package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

/**
 * Placeholder for a weak (pending-removal) contact. Stored in its own table, isolated from the
 * friend store (contactor), so a full friend-sync wipe does not clobber it.
 *
 * The snapshot reuses [ContactorModel]: the server name/avatar are mapped in and the whole row is
 * gson-serialized into [snapshotJson]. expireAt/reason/deleteTime are weak-state metadata stored as
 * separate columns.
 *
 * uid is the primary key (see [PublicKeyInfoModel]); the upsert relies on its REPLACE semantics.
 *
 * Numeric columns use non-null types with defaults (expireAt/deleteTime: Long 0L, reason: Int 0) to
 * avoid the #901 WCDB-KSP NULL-read regression on boxed numeric/Boolean columns. snapshotJson is a
 * String column, which KSP guards for NULL, so it is nullable-safe.
 */
@WCDBTableCoding
class PendingRemovalContactModel {

    /** Target uid. Primary key (upsert REPLACE dedupe key). */
    @WCDBField(isPrimary = true, isNotNull = true)
    var uid: String = ""

    /** Absolute expiry, ms UTC. Indexed for reconcile/cleanup ordering by expiry. */
    @WCDBField
    @WCDBIndex
    var expireAt: Long = 0L

    /** 0=deleted / 1=deregistered (client does not distinguish). Non-null default avoids #901 KSP NULL-read. */
    @WCDBField
    var reason: Int = 0

    /** Entered-weak time, ms UTC. Non-null default avoids #901 KSP NULL-read. */
    @WCDBField
    var deleteTime: Long = 0L

    /** Whole [ContactorModel] row gson-serialized. String column, KSP NULL-safe. */
    @WCDBField
    var snapshotJson: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as PendingRemovalContactModel
        return uid == other.uid &&
                expireAt == other.expireAt &&
                reason == other.reason &&
                deleteTime == other.deleteTime &&
                snapshotJson == other.snapshotJson
    }

    override fun hashCode(): Int =
        Objects.hash(uid, expireAt, reason, deleteTime, snapshotJson)
}
