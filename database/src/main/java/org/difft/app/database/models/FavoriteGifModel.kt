package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

/**
 * Local cache of the decrypted favorites list (so the favorites tab opens instantly and is
 * visible offline; recalibrated on panel-open / app-start). See android-impl-design.md §B2.
 *
 * Primary key = [fileHash] (content is identity — no separate favoriteId). Kotlin WCDB model,
 * so no #901 KSP NULL-read concern for boxed types (all numeric columns are non-null with
 * sane defaults).
 */
@WCDBTableCoding
class FavoriteGifModel {
    /** Primary key = content hash (content is identirty). */
    @WCDBField(isPrimary = true)
    var fileHash: String = ""

    @WCDBField
    var attachmentId: String? = null

    @WCDBField
    var authorizeId: Long = 0L

    /** Base64 NO_WRAP of the SHA-512 attachment key (needed to download + decrypt). */
    @WCDBField
    var encKey: String? = null

    /** Base64 NO_WRAP of the cipherHash (digest). */
    @WCDBField
    var digest: String? = null

    @WCDBField
    var contentType: String = "image/gif"

    @WCDBField
    var width: Int = 0

    @WCDBField
    var height: Int = 0

    /** Plaintext asset size in bytes (0 = unknown/legacy). Round-trips into the blob for peer validation. */
    @WCDBField
    var size: Int = 0

    /** Descending sort key = the listVersion at which this item was added. */
    @WCDBField
    var addedListVersion: Long = 0L

    /** Optimistic-enqueue flag: shown in UI but not yet confirmed by a successful CAS PUT. */
    @WCDBField
    var pending: Boolean = false

    /**
     * Preview URL of an optimistic favorite whose asset is not yet uploaded: the cell displays it and
     * the send path uses it (GifSendInput.FromUrl) before the background transStore completes. Null
     * once the row is confirmed (real attachment available).
     */
    @WCDBField
    var sourceUrl: String? = null

    /**
     * Epoch millis when this row first became pending (0 for confirmed rows). Set once at creation and
     * never reset on retry, so [flushPendingFavorites] can give up on a pending row that has failed to
     * sync for longer than the TTL — bounding a transient failure so it can't linger as a zombie that
     * shows locally forever but never reaches the server.
     */
    @WCDBField
    var pendingSince: Long = 0L

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as FavoriteGifModel
        return fileHash == other.fileHash
    }

    override fun hashCode(): Int = Objects.hash(fileHash)
}
