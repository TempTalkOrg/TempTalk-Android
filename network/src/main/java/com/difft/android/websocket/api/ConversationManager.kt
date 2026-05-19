package com.difft.android.websocket.api

import difft.android.messageserialization.For
import com.difft.android.websocket.api.messages.PublicKeyInfo

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
