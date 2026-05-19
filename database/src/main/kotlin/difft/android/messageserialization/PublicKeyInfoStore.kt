package difft.android.messageserialization

import org.difft.app.database.models.PublicKeyInfoModel

/**
 * Per-uid public key info cache store (issue #675). Replaces the
 * Gson-serialized `Room.publicKeyInfoJson` blob with a dedicated uid-keyed
 * table. All writes flow through [upsert] — never via per-row
 * `updateValue(... WHERE uid.eq(...))`.
 */
interface PublicKeyInfoStore {

    /**
     * Insert-or-replace a batch in a single atomic statement.
     * Empty input is a no-op (skipped before the DB call).
     */
    suspend fun upsert(models: List<PublicKeyInfoModel>)

    /**
     * Read cached rows for [uids], returned as uid → model map for
     * O(1) access. Empty input returns emptyMap without DB hit.
     * Missing uids are simply absent from the returned map (no null placeholders).
     */
    suspend fun getForUids(uids: Collection<String>): Map<String, PublicKeyInfoModel>

    /**
     * True iff EVERY uid in [uids] has a corresponding row cached.
     * - Empty [uids] → true (vacuous). MUST NOT hit the DB.
     * - Non-empty [uids] → count(rows) == distinct-uids-size.
     */
    suspend fun hasAllUids(uids: Collection<String>): Boolean

    /**
     * Delete cached rows for [uids]. Rows not present are skipped silently.
     * Empty input is a no-op. Reserved for future proactive invalidation.
     */
    suspend fun deleteForUids(uids: Collection<String>)
}
