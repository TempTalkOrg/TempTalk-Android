package org.difft.app.database.models;

import com.tencent.wcdb.WCDBDefault;
import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

/**
 * Per-uid public key info cache. Replaces the Gson-serialized
 * {@code Room.publicKeyInfoJson} blob (issue #675).
 *
 * <p>Primary-key pattern B: {@code uid} is the direct PK with
 * {@code isPrimary=true, isNotNull=true}. No {@code databaseId},
 * no {@code isAutoIncrement}. See analysis §"Primary-Key Anti-Pattern".
 *
 * <p>Writes flow exclusively through
 * {@code PublicKeyInfoStore.upsert(...)} which is backed by
 * {@code insertOrReplaceObjects} — single-statement atomic upsert,
 * no {@code updateValue(... WHERE uid.eq(...))} anti-pattern.
 */
@WCDBTableCoding
public class PublicKeyInfoModel {

    /** Identity uid. Sole primary key. */
    @WCDBField(isPrimary = true, isNotNull = true)
    public String uid;

    /**
     * Peer's public identity key as returned by {@code /keys}.
     * Empty string means server returned no key; runtime filter at
     * {@code NewSignalServiceMessageSender.kt:368-374} skips these.
     */
    @WCDBField(isNotNull = true)
    public String identityKey;

    /** Registration id used when building the Signal envelope. */
    @WCDBField(isNotNull = true)
    @WCDBDefault(intValue = 0)
    public int registrationId;

    /**
     * Timestamp of the peer's most recent client-initiated identity-key reset.
     * Pass-through from the {@code PublicKeyInfo} wire type returned by {@code /keys}.
     *
     * <p>Key rotation is driven entirely by the peer manually resetting their identity
     * key on their own client — there is no server-side rotation. The same timestamp
     * value is also recorded in {@code ResetIdentityKeyModel} when the reset event
     * is broadcast to this device. Stored here to mirror the server wire shape and
     * keep this table a complete per-uid key snapshot.
     */
    @WCDBField(isNotNull = true)
    @WCDBDefault(intValue = 0)
    public long resetIdentityKeyTime;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PublicKeyInfoModel that)) return false;
        return registrationId == that.registrationId
                && resetIdentityKeyTime == that.resetIdentityKeyTime
                && Objects.equals(uid, that.uid)
                && Objects.equals(identityKey, that.identityKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uid, identityKey, registrationId, resetIdentityKeyTime);
    }

    @Override
    public String toString() {
        // identityKey intentionally omitted (defensive — do not print key material in logs).
        return "PublicKeyInfoModel{" +
                "uid='" + uid + '\'' +
                ", registrationId=" + registrationId +
                ", resetIdentityKeyTime=" + resetIdentityKeyTime +
                '}';
    }
}
