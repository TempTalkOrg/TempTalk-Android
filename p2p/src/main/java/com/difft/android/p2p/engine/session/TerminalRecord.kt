package com.difft.android.p2p.engine.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable










@Serializable
data class TerminalRecord(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("tokenSha256") val tokenSha256: String,
    








    @SerialName("terminal") val terminal: String? = null,
    @SerialName("stage") val stage: String,
    @SerialName("records") val records: Long,
    @SerialName("attachmentBytes") val attachmentBytes: Long,
    @SerialName("progressRevision") val progressRevision: Long,
    
    @SerialName("at") val at: Long,
)







interface TerminalRecordStore {
    fun load(): List<TerminalRecord>
    fun save(records: List<TerminalRecord>)
}
