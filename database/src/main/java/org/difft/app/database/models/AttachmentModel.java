package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Arrays;
import java.util.Objects;

@WCDBTableCoding
public class AttachmentModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBIndex
    @WCDBField
    public String id;
    @WCDBIndex
    @WCDBField
    public String messageId;
    @WCDBField
    @WCDBIndex
    public Long forwardModelDatabaseId; // this attachment belongs to which forwardModel
    @WCDBField
    @WCDBIndex
    public Long quoteModelDatabaseId; // this attachment belongs to which quoteModel
    @WCDBField
    public Long authorityId; // authorityId
    @WCDBField
    public String contentType;
    @WCDBField
    public byte[] key = new byte[0];
    @WCDBField
    public int size;
    @WCDBField
    public byte[] thumbnail = new byte[0];
    @WCDBField
    public byte[] digest = new byte[0];
    @WCDBField
    public String fileName = "";
    @WCDBField
    public int flags;
    @WCDBField
    public int width;
    @WCDBField
    public int height;
    @WCDBField
    public String path;
    @WCDBField
    public int status;

    @WCDBField
    public Long totalTime; //总时长（毫秒单位），比如语音消息
    @WCDBField
    public String amplitudes; //语音消息解码后的振幅数据

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AttachmentModel that = (AttachmentModel) o;
        return size == that.size && flags == that.flags && width == that.width && height == that.height && status == that.status && Objects.equals(id, that.id) && Objects.equals(messageId, that.messageId) && Objects.equals(forwardModelDatabaseId, that.forwardModelDatabaseId) && Objects.equals(quoteModelDatabaseId, that.quoteModelDatabaseId) && Objects.equals(authorityId, that.authorityId) && Objects.equals(contentType, that.contentType) && Objects.deepEquals(key, that.key) && Objects.deepEquals(thumbnail, that.thumbnail) && Objects.deepEquals(digest, that.digest) && Objects.equals(fileName, that.fileName) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, messageId, forwardModelDatabaseId, quoteModelDatabaseId, authorityId, contentType, Arrays.hashCode(key), size, Arrays.hashCode(thumbnail), Arrays.hashCode(digest), fileName, flags, width, height, path, status);
    }
}