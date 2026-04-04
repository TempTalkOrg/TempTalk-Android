package org.difft.app.database.models;

import androidx.annotation.Nullable;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class ContactorModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBField(isNotNull = true)
    @WCDBIndex(isUnique = true)
    public String id;

    @WCDBField
    public String name;

    @WCDBField
    public String email;

    @WCDBField
    public String avatar;

    @WCDBField
    public int meetingVersion = 1;

    @WCDBField
    public String publicName;

    @WCDBField
    public String timeZone;

    @WCDBField
    public String remark; //备注名

    @WCDBField
    public String joinedAt;

    @WCDBField
    public String sourceDescribe;

    @WCDBField
    public String findyouDescribe;

    @Nullable
    public GroupMemberContactorModel groupMemberContactor;

    @WCDBField
    public String customUid;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ContactorModel that = (ContactorModel) o;
        return meetingVersion == that.meetingVersion && Objects.equals(id, that.id) && Objects.equals(name, that.name) && Objects.equals(email, that.email) && Objects.equals(avatar, that.avatar) && Objects.equals(publicName, that.publicName) && Objects.equals(timeZone, that.timeZone) && Objects.equals(remark, that.remark) && Objects.equals(joinedAt, that.joinedAt) && Objects.equals(sourceDescribe, that.sourceDescribe) && Objects.equals(findyouDescribe, that.findyouDescribe) && Objects.equals(groupMemberContactor, that.groupMemberContactor) && Objects.equals(customUid, that.customUid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, email, avatar, meetingVersion, publicName, timeZone, remark, joinedAt, sourceDescribe, findyouDescribe, groupMemberContactor, customUid);
    }
}