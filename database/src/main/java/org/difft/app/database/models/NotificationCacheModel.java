package org.difft.app.database.models;

import com.tencent.wcdb.MultiPrimary;
import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

/**
 * 通知缓存数据模型
 * 用于持久化存储通知消息，支持app重启后恢复通知
 * 使用 conversationId + timestamp 作为联合主键，自动去重
 */
@WCDBTableCoding(multiPrimaries = @MultiPrimary(columns = {"conversationId", "timestamp"}))
public class NotificationCacheModel {

    /**
     * 会话ID (群组ID或用户ID) - 联合主键之一
     */
    @WCDBField(isNotNull = true)
    public String conversationId;

    /**
     * 消息时间戳 - 联合主键之一
     */
    @WCDBField(isNotNull = true)
    public long timestamp;

    /**
     * 消息内容
     */
    @WCDBField(isNotNull = true)
    public String content;

    /**
     * 发送人唯一标识 (fromId)
     */
    @WCDBField(isNotNull = true)
    public String personKey;

    /**
     * 发送人显示名称 (消息发送时刻的快照)
     */
    @WCDBField(isNotNull = true)
    public String personName;

    /**
     * 创建时间 (用于清理过期数据)
     */
    @WCDBField(isNotNull = true)
    @WCDBIndex
    public long createdAt;
}
