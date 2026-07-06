package com.difft.android.websocket.api

import difft.android.messageserialization.For
import com.difft.android.websocket.api.messages.PublicKeyInfo

/**
 * Result of a /keys fetch. The send path uses it to distinguish **permanent** failures
 * (stop retrying) from **transient** ones (keep retrying, don't kill weak-network sends).
 * See issue #970 ②.
 *
 * Permanent (→ NoValidRecipientKeysException): **only** [EntityInvalid] (group status != 0,
 * reliable and unambiguous).
 * Transient (→ IOException): [ServerEmpty], [FetchFailed], [Unresolved].
 * Success: [Updated].
 *
 * Note: [ServerEmpty] is **transient**, not permanent — an empty array is ambiguous (the entity
 * may be gone, or a valid recipient's keys may not have propagated yet / a transient server
 * blank), so retry conservatively to avoid dropping messages/group keys (PR #973 code-review).
 */
sealed interface PublicKeyUpdateResult {
    /** Server returned a non-empty key set and it was upserted. */
    data object Updated : PublicKeyUpdateResult

    /**
     * Fetch succeeded but the server returned an **empty array** (entity missing / valid group
     * with 0 members) → **transient** (retryable). Ambiguous: the keys may simply not have
     * propagated yet, so retry conservatively rather than dropping permanently.
     */
    data object ServerEmpty : PublicKeyUpdateResult

    /** The fetch itself failed (network/timeout/body-parse error, no definite answer) → transient, retryable. */
    data object FetchFailed : PublicKeyUpdateResult

    /** The target group is confirmed invalid (`GroupModel.status != 0`) → permanent. */
    data object EntityInvalid : PublicKeyUpdateResult

    /** Group info not yet resolved (fetch threw / groupsInProgress concurrency guard skipped → group==null) → transient, retryable. */
    data object Unresolved : PublicKeyUpdateResult
}

interface ConversationManager {

    // ---- For-keyed: pre-flight + default reads. Facade resolves For → uids internally. ----

    /**
     * True iff cached public key info exists for EVERY uid resolved from [room].
     * - For.Account(peer) → checks [peer, selfUid] both cached.
     * - For.Group(gid)    → checks all current group members cached
     *   (resolved LOCAL-ONLY — never hits the network; see
     *   `ConversationManagerImpl.resolveUidsLocalOnly`). A cold group
     *   cache returns false, forcing the caller to run
     *   `updatePublicKeyInfoData(room)` which runs the full suspend
     *   resolver with network fallback.
     * - Empty resolution (group row absent locally) → returns false
     *   (treated as cache miss, NOT vacuous true).
     */
    suspend fun hasPublicKeyInfoData(room: For): Boolean

    /**
     * Fetch /keys for uids resolved from [room] and persist results. Returns
     * true on non-empty server response, false otherwise.
     * Internally delegates to [updatePublicKeyInfoData] after resolution.
     */
    suspend fun updatePublicKeyInfoData(room: For): Boolean

    /**
     * The split-signal (null/empty) variant of [updatePublicKeyInfoData]. Used **only** by the
     * send path ([NewSignalServiceMessageSender.createNewOutgoingPushMessage], its sole
     * return-value read site) to separate "server confirms no key / group invalid" (permanent)
     * from "network failure / not yet synced" (transient), stopping the infinite retry of orphan
     * receipts to invalid groups (issue #970 ②).
     *
     * Implemented **independently** of the old Boolean overload (no mutual delegation): the
     * Boolean overload keeps its original lightweight semantics, so the ~7 call sites that ignore
     * its return value don't pay for an extra group resolution.
     */
    suspend fun updatePublicKeyInfoDataResult(room: For): PublicKeyUpdateResult

    /**
     * Makes one confirming decision for the "public keys all empty after filtering" ambiguity
     * (called by [NewSignalServiceMessageSender] when `hasPublicKeyInfoData=true` but filtering
     * leaves nothing).
     *
     * Reuses [updatePublicKeyInfoDataResult] for one confirming fetch (same path for Group and
     * Account). The caller maps the result: only `EntityInvalid` → permanent; `ServerEmpty`
     * (ambiguous empty array), `FetchFailed`, `Unresolved`, and `Updated` (re-fetched a key) →
     * transient (let the job retry / self-heal). The fetch runs once on the exception branch only,
     * off the hot path, so it does not cause churn.
     */
    suspend fun classifyEmptyKeys(room: For): PublicKeyUpdateResult

    // ---- uid-keyed: ONLY for retry-branch narrowing where server response gives uid list ----

    /**
     * Fetch /keys for explicit [uids] and persist results. Returns true on
     * non-empty server response. Used EXCLUSIVELY by the 3 retry branches in
     * [NewSignalServiceMessageSender] (WS/HTTP/Rust) where the server response
     * enumerates stale/missing uids — no `For` context is available.
     * - Empty [uids] → true (vacuous success, no server call, no DB write).
     */
    suspend fun updatePublicKeyInfoData(uids: List<String>): Boolean

    // ---- For-keyed reads ----

    /**
     * Read cached public key info for uids resolved from [room].
     * Missing uids absent from result (no null placeholders). Preserves
     * request-uid order.
     */
    suspend fun getPublicKeyInfos(room: For): List<PublicKeyInfo>

    // ---- Unchanged from pre-refactor ----

    /**
     * Refresh group metadata. UNCHANGED from pre-refactor. No-op for Account.
     */
    suspend fun updateConversationMemberData(room: For)

    /**
     * Bypass-cache direct server fetch. UNCHANGED.
     * Does NOT write to public_key_info table.
     */
    suspend fun getPublicKeyInfos(ids: List<String>?): List<PublicKeyInfo>?
}
