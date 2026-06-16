package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Arrays
import java.util.Objects

@WCDBTableCoding
class AttachmentModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBIndex
    @WCDBField
    var id: String? = null

    @WCDBIndex
    @WCDBField
    var messageId: String? = null

    // boxed Long -> Long?: lets KSP emit a NULL guard on read. #901 bug fix
    @WCDBField
    @WCDBIndex
    var forwardModelDatabaseId: Long? = null // this attachment belongs to which forwardModel

    // boxed Long -> Long?: lets KSP emit a NULL guard on read. #901 bug fix
    @WCDBField
    @WCDBIndex
    var quoteModelDatabaseId: Long? = null // this attachment belongs to which quoteModel

    // boxed Long -> Long?: lets KSP emit a NULL guard on read. #901 bug fix
    @WCDBField
    var authorityId: Long? = null // authorityId

    @WCDBField
    var contentType: String? = null

    @WCDBField
    var key: ByteArray? = ByteArray(0)

    @WCDBField
    var size: Int = 0

    @WCDBField
    var thumbnail: ByteArray? = ByteArray(0)

    @WCDBField
    var digest: ByteArray? = ByteArray(0)

    @WCDBField
    var fileName: String? = ""

    @WCDBField
    var flags: Int = 0

    @WCDBField
    var width: Int = 0

    @WCDBField
    var height: Int = 0

    @WCDBField
    var path: String? = null

    @WCDBField
    var status: Int = 0

    // boxed Long -> Long?: lets KSP emit a NULL guard on read. #901 bug fix
    @WCDBField
    var totalTime: Long? = null //总时长（毫秒单位），比如语音消息

    @WCDBField
    var amplitudes: String? = null //语音消息解码后的振幅数据

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as AttachmentModel
        return size == other.size && flags == other.flags && width == other.width &&
                height == other.height && status == other.status &&
                id == other.id && messageId == other.messageId &&
                forwardModelDatabaseId == other.forwardModelDatabaseId &&
                quoteModelDatabaseId == other.quoteModelDatabaseId &&
                authorityId == other.authorityId && contentType == other.contentType &&
                Objects.deepEquals(key, other.key) &&
                Objects.deepEquals(thumbnail, other.thumbnail) &&
                Objects.deepEquals(digest, other.digest) &&
                fileName == other.fileName && path == other.path
    }

    override fun hashCode(): Int =
        Objects.hash(
            id, messageId, forwardModelDatabaseId, quoteModelDatabaseId, authorityId,
            contentType, Arrays.hashCode(key), size, Arrays.hashCode(thumbnail),
            Arrays.hashCode(digest), fileName, flags, width, height, path, status
        )
}
