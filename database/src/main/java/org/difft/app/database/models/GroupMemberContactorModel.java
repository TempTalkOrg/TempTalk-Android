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

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        GroupMemberContactorModel that = (GroupMemberContactorModel) o;
        return databaseId == that.databaseId && Objects.equals(id, that.id) && Objects.equals(gid, that.gid) && Objects.equals(displayName, that.displayName) && Objects.equals(notification, that.notification) && Objects.equals(rapidRole, that.rapidRole) && Objects.equals(remark, that.remark) && Objects.equals(useGlobal, that.useGlobal) && Objects.equals(extId, that.extId) && Objects.equals(groupRole, that.groupRole);
    }

    @Override
    public int hashCode() {
        return Objects.hash(databaseId, id, gid, displayName, notification, rapidRole, remark, useGlobal, extId, groupRole);
    }
}