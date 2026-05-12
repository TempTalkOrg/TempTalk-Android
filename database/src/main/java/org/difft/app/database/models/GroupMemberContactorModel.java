package org.difft.app.database.models;

import com.tencent.wcdb.MultiUnique;
import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding(multiUnique = @MultiUnique(columns = {"gid", "id"}))
public class GroupMemberContactorModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBIndex
    @WCDBField
    public String id;

    @WCDBField(isNotNull = true)
    @WCDBIndex
    public String gid;

    @WCDBField
    public String displayName; //群详情接口返回用于显示的成员名称

    @WCDBField
    public Integer notification;

    @WCDBField
    public Integer rapidRole;

    @WCDBField
    public String remark; //备注名

    @WCDBField
    public String remarkAvatar;

    @WCDBField
    public Boolean useGlobal = false;

    @WCDBField
    public Integer extId;

    /**
     * const val GROUP_ROLE_OWNER = 0
     * const val GROUP_ROLE_ADMIN = 1
     * const val GROUP_ROLE_MEMBER = 2
     */
    @WCDBField
    public Integer groupRole;

    @WCDBField
    public String uidSignature;

    /**
     * Tri-state uidSignature verification status for encrypted-group members.
     * <b>Never simplify to a primitive boolean defaulting to false.</b>
     *
     * <ul>
     *   <li>{@code null}  = unverified / failed — retried on next fullVerify</li>
     *   <li>{@code true}  = verified</li>
     *   <li>{@code false} = ❌ NEVER set this — bypasses the Phase 3 SQL filter
     *       ({@code signatureVerify IS NULL}), creating a permanently-skipped
     *       "pseudo-verified" state that keeps signature-invalid members on the
     *       client forever.</li>
     * </ul>
     *
     * Plain-group members have {@code uidSignature = null}; the Phase 3 verify loop
     * skips them in-memory ({@code uid?.signature ?: return@forEach}) — by design
     * assumption, encrypted-group members always carry {@code uidSignature}, so the
     * SQL query does not filter on it. Field lifetime follows the row: deleted on
     * kick/leave; re-inserted as {@code null} on re-join.
     *
     * <p>⚠️ Anti-precedent: {@code useGlobal} in this file is declared
     * {@code public Boolean useGlobal = false} — a boxed Boolean with a non-null
     * default. <b>signatureVerify must NOT follow that pattern.</b> Keep the Java
     * field default of {@code null} (consistent with {@code groupRole} and
     * {@code uidSignature}). Any PR changing this to {@code = false} or a primitive
     * boolean introduces a security bug and must be rejected in review.
     */
    @WCDBField
    public Boolean signatureVerify;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        GroupMemberContactorModel that = (GroupMemberContactorModel) o;
        return databaseId == that.databaseId && Objects.equals(id, that.id) && Objects.equals(gid, that.gid) && Objects.equals(displayName, that.displayName) && Objects.equals(notification, that.notification) && Objects.equals(rapidRole, that.rapidRole) && Objects.equals(remark, that.remark) && Objects.equals(remarkAvatar, that.remarkAvatar) && Objects.equals(useGlobal, that.useGlobal) && Objects.equals(extId, that.extId) && Objects.equals(groupRole, that.groupRole) && Objects.equals(uidSignature, that.uidSignature) && Objects.equals(signatureVerify, that.signatureVerify);
    }

    @Override
    public int hashCode() {
        return Objects.hash(databaseId, id, gid, displayName, notification, rapidRole, remark, remarkAvatar, useGlobal, extId, groupRole, uidSignature, signatureVerify);
    }
}