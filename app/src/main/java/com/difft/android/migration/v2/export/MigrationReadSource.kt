package com.difft.android.migration.v2.export

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.tencent.wcdb.core.Database
import com.tencent.wcdb.core.Table
import com.tencent.wcdb.orm.TableBinding
import org.difft.app.database.WCDB
import org.difft.app.database.WCDBKeyManager
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBForwardContextModel
import org.difft.app.database.models.DBForwardModel
import org.difft.app.database.models.DBGroupCryptoKeysModel
import org.difft.app.database.models.DBMentionModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBPendingMessageModelNew
import org.difft.app.database.models.DBQuoteModel
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.DBReadInfoModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.GroupCryptoKeysModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.DBSharedContactModel
import org.difft.app.database.models.DBSharedContactPhoneModel
import org.difft.app.database.models.PendingMessageModelNew
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel
import java.io.Closeable
import javax.inject.Inject










interface MigrationReadSource {
    val message: Table<MessageModel>
    val attachment: Table<AttachmentModel>
    val room: Table<RoomModel>
    val quote: Table<QuoteModel>
    val forward: Table<ForwardModel>
    val forwardContext: Table<ForwardContextModel>
    val mention: Table<MentionModel>
    val reaction: Table<ReactionModel>
    val sharedContact: Table<SharedContactModel>
    val sharedContactPhone: Table<SharedContactPhoneModel>
    val groupCryptoKeys: Table<GroupCryptoKeysModel>

    




    val readInfo: Table<ReadInfoModel>

    
    val pendingMessageNew: Table<PendingMessageModelNew>
}


class LiveReadSource @Inject constructor(
    private val wcdb: WCDB,
) : MigrationReadSource {
    override val message: Table<MessageModel> get() = wcdb.message
    override val attachment: Table<AttachmentModel> get() = wcdb.attachment
    override val room: Table<RoomModel> get() = wcdb.room
    override val quote: Table<QuoteModel> get() = wcdb.quote
    override val forward: Table<ForwardModel> get() = wcdb.forward
    override val forwardContext: Table<ForwardContextModel> get() = wcdb.forwardContext
    override val mention: Table<MentionModel> get() = wcdb.mention
    override val reaction: Table<ReactionModel> get() = wcdb.reaction
    override val sharedContact: Table<SharedContactModel> get() = wcdb.sharedContact
    override val sharedContactPhone: Table<SharedContactPhoneModel> get() = wcdb.sharedContactPhone
    override val groupCryptoKeys: Table<GroupCryptoKeysModel> get() = wcdb.groupCryptoKeys
    override val readInfo: Table<ReadInfoModel> get() = wcdb.readInfo
    override val pendingMessageNew: Table<PendingMessageModelNew> get() = wcdb.pendingMessageNew
}









class SnapshotReadSource(
    private val db: Database,
) : MigrationReadSource, Closeable {

    override val message: Table<MessageModel> by lazy { open("message", DBMessageModel) }
    override val attachment: Table<AttachmentModel> by lazy { open("attachment", DBAttachmentModel) }
    override val room: Table<RoomModel> by lazy { open("room", DBRoomModel) }
    override val quote: Table<QuoteModel> by lazy { open("quote", DBQuoteModel) }
    override val forward: Table<ForwardModel> by lazy { open("forward", DBForwardModel) }
    override val forwardContext: Table<ForwardContextModel> by lazy { open("forward_context", DBForwardContextModel) }
    override val mention: Table<MentionModel> by lazy { open("mention", DBMentionModel) }
    override val reaction: Table<ReactionModel> by lazy { open("reaction", DBReactionModel) }
    override val sharedContact: Table<SharedContactModel> by lazy { open("shared_contact", DBSharedContactModel) }
    override val sharedContactPhone: Table<SharedContactPhoneModel> by lazy { open("shared_contact_phone", DBSharedContactPhoneModel) }
    override val groupCryptoKeys: Table<GroupCryptoKeysModel> by lazy { open("group_crypto_keys", DBGroupCryptoKeysModel) }
    override val readInfo: Table<ReadInfoModel> by lazy { open("read_info", DBReadInfoModel) }
    override val pendingMessageNew: Table<PendingMessageModelNew> by lazy { open("pending_message_new", DBPendingMessageModelNew) }

    private fun <T> open(name: String, binding: TableBinding<T>): Table<T> {
        db.createTable(name, binding)
        return db.getTable(name, binding)
    }

    override fun close() {
        runCatching { db.close() }
            .onFailure { L.w { "[MigrationV2][Export] snapshot DB close failed: ${it.message}" } }
    }

    companion object {
        





        fun open(context: Context, snapshotPath: String): SnapshotReadSource {
            val db = Database(snapshotPath)
            WCDBKeyManager.cipherKeyForCurrentBuild(context)?.let { db.setCipherKey(it) }
            return SnapshotReadSource(db)
        }
    }
}
