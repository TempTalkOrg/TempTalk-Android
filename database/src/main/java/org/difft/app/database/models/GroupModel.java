package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class GroupModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBIndex(isUnique = true)
    @WCDBField
    public String gid;

    @WCDBField
    public String name;

    @WCDBField
    public Integer messageExpiry;

    @WCDBField
    public String avatar;

    @WCDBField
    public Integer status;

    @WCDBField
    public Integer invitationRule;

    @WCDBField
    public Integer version;

    @WCDBField
    public String remindCycle;

    @WCDBField
    public Boolean anyoneRemove;

    @WCDBField
    public Boolean rejoin;

    @WCDBField
    public Integer publishRule;

    @WCDBField
    public Boolean linkInviteSwitch = false;

    @WCDBField
    public Boolean privateChat = false;

    @WCDBField
    public boolean criticalAlert = false;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GroupModel that = (GroupModel) o;
        return Objects.equals(gid, that.gid) && Objects.equals(name, that.name) && Objects.equals(messageExpiry, that.messageExpiry) && Objects.equals(avatar, that.avatar) && Objects.equals(status, that.status) && Objects.equals(invitationRule, that.invitationRule) && Objects.equals(version, that.version) && Objects.equals(remindCycle, that.remindCycle) && Objects.equals(anyoneRemove, that.anyoneRemove) && Objects.equals(rejoin, that.rejoin) && Objects.equals(publishRule, that.publishRule) && Objects.equals(linkInviteSwitch, that.linkInviteSwitch) && Objects.equals(privateChat, that.privateChat) && criticalAlert == that.criticalAlert;
    }

    @Override
    public int hashCode() {
        return Objects.hash(gid, name, messageExpiry, avatar, status, invitationRule, version, remindCycle, anyoneRemove, rejoin, publishRule, linkInviteSwitch, privateChat, criticalAlert);
    }
}