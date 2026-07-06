package org.difft.app.database.models

import com.tencent.wcdb.WCDBDefault
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

/**
 * Per-uid public key info cache. Replaces the Gson-serialized
 * `Room.publicKeyInfoJson` blob (issue #675).
 *
 * Primary-key pattern B: `uid` is the direct PK with
 * `isPrimary=true, isNotNull=true`. No `databaseId`,
 * no `isAutoIncrement`. See analysis §"Primary-Key Anti-Pattern".
 *
 * Writes flow exclusively through
 * `PublicKeyInfoStore.upsert(...)` which is backed by
 * `insertOrReplaceObjects` — single-statement atomic upsert,
 * no `updateValue(... WHERE uid.eq(...))` anti-pattern.
 */
@WCDBTableCoding
class PublicKeyInfoModel {

    /** Identity uid. Sole primary key. */
    @WCDBField(isPrimary = true, isNotNull = true)
    var uid: String = ""

    /**
     * Peer's public identity key as returned by `/keys`.
     * Empty string means server returned no key; runtime filter at
     * `NewSignalServiceMessageSender.kt:368-374` skips these.
     */
    @WCDBField(isNotNull = true)
    var identityKey: String = ""

    /** Registration id used when building the Signal envelope. */
    @WCDBField(isNotNull = true)
    @WCDBDefault(intValue = 0)
    var registrationId: Int = 0

    /**
     * Timestamp of the peer's most recent client-initiated identity-key reset.
     * Pass-through from the `PublicKeyInfo` wire type returned by `/keys`.
     *
     * Key rotation is driven entirely by the peer manually resetting their identity
     * key on their own client — there is no server-side rotation. The same timestamp
     * value is also recorded in `ResetIdentityKeyModel` when the reset event
     * is broadcast to this device. Stored here to mirror the server wire shape and
     * keep this table a complete per-uid key snapshot.
     */
    @WCDBField(isNotNull = true)
    @WCDBDefault(intValue = 0)
    var resetIdentityKeyTime: Long = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as PublicKeyInfoModel
        return registrationId == other.registrationId &&
                resetIdentityKeyTime == other.resetIdentityKeyTime &&
                uid == other.uid &&
                identityKey == other.identityKey
    }

    override fun hashCode(): Int =
        Objects.hash(uid, identityKey, registrationId, resetIdentityKeyTime)

    // identityKey intentionally omitted (defensive — do not print key material in logs).
    override fun toString(): String =
        "PublicKeyInfoModel{" +
                "uid='" + uid + '\'' +
                ", registrationId=" + registrationId +
                ", resetIdentityKeyTime=" + resetIdentityKeyTime +
                '}'
}
