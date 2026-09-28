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
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel
import java.io.Closeable
import javax.inject.Inject

/**
 * The read surface the [NeutralExporter] needs: exactly the WCDB [Table] accessors the export
 * projection touches, each over the same model and binding the live [WCDB] singleton exposes, so
 * projection code is identical whether it reads the live DB or a snapshot.
 *
 * [LiveReadSource] delegates to the live singleton; [SnapshotReadSource] opens a second encrypted
 * handle over a byte-copy of it. The snapshot lifecycle (take, open, export, close, delete) belongs
 * to the caller, not to this file.
 */
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

    /**
     * Others' per-participant read positions (the `read_info` table), which drive the outgoing-message
     * "read by" indicator on the destination. Queried per room and folded into
     * [com.difft.android.migration.v2.model.NeutralConversationLocal.participantReadPositions].
     */
    val readInfo: Table<ReadInfoModel>
}

/** [MigrationReadSource] backed by the live [WCDB] singleton; the Hilt default binding. */
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
}

/**
 * [MigrationReadSource] over a consistent on-disk snapshot of the live WCDB — a byte-copy produced by
 * [com.difft.android.migration.snapshot.BackupSnapshotter], opened with the same cipher key. Reading
 * it rather than the live DB gives the export a point-in-time view while the user keeps chatting.
 *
 * The caller must [close] this (closing the second [Database] handle) and then delete the snapshot
 * file. [close] is idempotent.
 */
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

    private fun <T> open(name: String, binding: TableBinding<T>): Table<T> {
        db.createTable(name, binding)
        return db.getTable(name, binding)
    }

    override fun close() {
        runCatching { db.close() }
            .onFailure { L.w { "[MigrationV2][Export] snapshot DB close failed: ${it.message}" } }
    }

    companion object {
        /**
         * Open a [SnapshotReadSource] over [snapshotPath], mirroring the live WCDB's encryption
         * policy for this build: keyed in release, plaintext in debug. Setting a cipher key on a
         * plaintext snapshot throws `WCDBCorruptOrIOException` Code 26 at the first table access, so
         * the key is set only when one exists. The caller owns the returned source's lifecycle.
         */
        fun open(context: Context, snapshotPath: String): SnapshotReadSource {
            val db = Database(snapshotPath)
            WCDBKeyManager.cipherKeyForCurrentBuild(context)?.let { db.setCipherKey(it) }
            return SnapshotReadSource(db)
        }
    }
}
