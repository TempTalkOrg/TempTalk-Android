package org.difft.app.database.models

import com.tencent.wcdb.MultiUnique
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding(multiUnique = [MultiUnique(columns = ["gid", "id"])])
class GroupMemberContactorModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBIndex
    @WCDBField
    var id: String? = null

    @WCDBField(isNotNull = true)
    @WCDBIndex
    var gid: String = ""

    @WCDBField
    var displayName: String? = null //群详情接口返回用于显示的成员名称

    @WCDBField
    var notification: Int? = null

    @WCDBField
    var rapidRole: Int? = null

    @WCDBField
    var remark: String? = null //备注名

    @WCDBField
    var remarkAvatar: String? = null

    @WCDBField
    var useGlobal: Boolean? = false

    @WCDBField
    var extId: Int? = null

    /**
     * const val GROUP_ROLE_OWNER = 0
     * const val GROUP_ROLE_ADMIN = 1
     * const val GROUP_ROLE_MEMBER = 2
     */
    @WCDBField
    var groupRole: Int? = null

    @WCDBField
    var uidSignature: String? = null

    /**
     * Tri-state uidSignature verification status for encrypted-group members.
     * **Never simplify to a primitive boolean defaulting to false.**
     *
     * - `null`  = unverified / failed — retried on next fullVerify
     * - `true`  = verified
     * - `false` = ❌ NEVER set this — bypasses the Phase 3 SQL filter
     *   (`signatureVerify IS NULL`), creating a permanently-skipped
     *   "pseudo-verified" state that keeps signature-invalid members on the
     *   client forever.
     *
     * Plain-group members have `uidSignature = null`; the Phase 3 verify loop
     * skips them in-memory (`uid?.signature ?: return@forEach`) — by design
     * assumption, encrypted-group members always carry `uidSignature`, so the
     * SQL query does not filter on it. Field lifetime follows the row: deleted on
     * kick/leave; re-inserted as `null` on re-join.
     *
     * ⚠️ Anti-precedent: `useGlobal` in this file is a boxed Boolean with a
     * non-null default (`= false`). **signatureVerify must NOT follow that
     * pattern.** Keep the field default of `null` (consistent with `groupRole`
     * and `uidSignature`). Any PR changing this to `= false` or a primitive
     * boolean introduces a security bug and must be rejected in review.
     *
     * Kotlin migration (#901): declared `Boolean?` (boxed → nullable per the
     * isNotNull-driven rule) so WCDB-KSP emits a `ColumnType.Null` read guard —
     * a SQLite NULL now correctly reads back as Kotlin `null`, not `false`. This
     * root-fixes the verify bug the Java `getBool` path (NULL → false) caused.
     */
    @WCDBField
    var signatureVerify: Boolean? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as GroupMemberContactorModel
        // exclude databaseId: rowid must not affect content equality, #901
        return id == other.id &&
                gid == other.gid &&
                displayName == other.displayName &&
                notification == other.notification &&
                rapidRole == other.rapidRole &&
                remark == other.remark &&
                remarkAvatar == other.remarkAvatar &&
                useGlobal == other.useGlobal &&
                extId == other.extId &&
                groupRole == other.groupRole &&
                uidSignature == other.uidSignature &&
                signatureVerify == other.signatureVerify
    }

    // exclude databaseId: rowid must not affect content equality, #901
    override fun hashCode(): Int = Objects.hash(
        id, gid, displayName, notification, rapidRole, remark, remarkAvatar,
        useGlobal, extId, groupRole, uidSignature, signatureVerify
    )
}
