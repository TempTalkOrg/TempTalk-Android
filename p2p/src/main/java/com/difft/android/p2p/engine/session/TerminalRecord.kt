package com.difft.android.p2p.engine.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a session ended, kept after the fact so a sender whose final response was lost can still ask
 * `GET /status` and get a definite answer instead of "unknown" — which, given what an answer decides
 * (whether the old device may start over, or has in fact been superseded), is the difference between
 * a safe retry and two devices holding the same account.
 *
 * Carries the SHA-256 of the session token, never the token: a record that outlives the session by
 * a day must not be a day-long credential for whoever can read the file.
 */
@Serializable
data class TerminalRecord(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("tokenSha256") val tokenSha256: String,
    /** One of [TransferTerminal]. */
    @SerialName("terminal") val terminal: String,
    @SerialName("stage") val stage: String,
    @SerialName("records") val records: Long,
    @SerialName("attachmentBytes") val attachmentBytes: Long,
    @SerialName("progressRevision") val progressRevision: Long,
    /** Epoch millis the session reached its terminal. */
    @SerialName("at") val at: Long,
)

/**
 * Where terminal records outlive the process. The session manager decides what to keep (count cap,
 * age cut-off) and hands the whole list down; the store only persists and reloads it. A store that
 * fails to load returns an empty list — losing the memory of a past session degrades a definite
 * answer to "unknown", which the sender handles; it never blocks a new session.
 */
interface TerminalRecordStore {
    fun load(): List<TerminalRecord>
    fun save(records: List<TerminalRecord>)
}
