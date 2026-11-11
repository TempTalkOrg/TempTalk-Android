package com.difft.android.p2p.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json










@Serializable
data class DeviceInfo(                                    
    @SerialName("alias") val alias: String,
    @SerialName("version") val version: String = "2.1",
    @SerialName("deviceModel") val deviceModel: String? = null,
    @SerialName("deviceType") val deviceType: String = "mobile", 
    @SerialName("fingerprint") val fingerprint: String,          
    @SerialName("port") val port: Int = 53317,
    @SerialName("protocol") val protocol: String = "https",      
    @SerialName("download") val download: Boolean = false,
    @SerialName("announce") val announce: Boolean = true,
    





    @Transient val lastSeenIp: String? = null,
)

@Serializable
data class FileMetadata(                                  
    @SerialName("id") val id: String,
    @SerialName("fileName") val fileName: String,
    @SerialName("size") val size: Long,
    @SerialName("fileType") val fileType: String,                
    @SerialName("sha256") val sha256: String? = null,            
    @SerialName("preview") val preview: String? = null,
    @SerialName("metadata") val metadata: FileTimes? = null,
)

@Serializable
data class FileTimes(
    @SerialName("modified") val modified: String? = null,        
    @SerialName("accessed") val accessed: String? = null,
)

@Serializable
data class PrepareUploadRequest(                          
    @SerialName("info") val info: DeviceInfo,
    @SerialName("files") val files: Map<String, FileMetadata>,   
    






    @SerialName("migration") val migration: MigrationHandshake? = null,
    




    @SerialName("pairToken") val pairToken: String? = null,
)
















@Serializable
data class MigrationHandshake(
    @SerialName("env") val sourceEnvironment: String,
    @SerialName("account") val sourceAccount: String,
    @SerialName("appVersion") val sourceAppVersion: String,
    @SerialName("formatVersion") val sourceFormatVersion: String,
    





    @SerialName("credentialHandoff") val credentialHandoff: Boolean? = null,
)

@Serializable
data class PrepareUploadResponse(                         
    @SerialName("sessionId") val sessionId: String,
    @SerialName("files") val files: Map<String, String>,         
    





    @SerialName("sessionToken") val sessionToken: String? = null,
    
    @SerialName("caps") val caps: List<String>? = null,
)






object ReceiverCaps {
    
    const val STATUS_V1 = "status-v1"

    
    const val BEAT_V1 = "beat-v1"

    




    const val PENDING_ENVELOPE_V1 = "pending-envelope-v1"

    





    const val VERDICT_RETAIN_V1 = "verdict-retain-v1"

    
    val BASE: List<String> = listOf(STATUS_V1, BEAT_V1)
}


object TransferTerminal {
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}







object CancelState {
    const val STOPPED = "stopped"
    const val ROLLED_BACK = "rolled_back"
    const val TOO_LATE = "too_late"
}






object TransferStages {
    const val PREPARED = "prepared"
    const val RECEIVING = "receiving"
    const val VERIFYING = "verifying"
    const val IMPORTING = "importing"
    const val PROMOTING = "promoting"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}








@Serializable
data class StatusResponse(
    @SerialName("stage") val stage: String,
    
    @SerialName("records") val records: Long? = null,
    @SerialName("attachmentBytes") val attachmentBytes: Long,
    @SerialName("progressRevision") val progressRevision: Long,
    
    @SerialName("terminal") val terminal: String? = null,
    @SerialName("updatedAt") val updatedAt: Long,
)


@Serializable
data class CancelResponse(
    @SerialName("state") val state: String,
)











@Serializable
data class QrPayload(
    @SerialName("v") val v: Int = 1,
    
    @SerialName("ip") val ip: String,
    @SerialName("port") val port: Int = 53317,
    @SerialName("fp") val fp: String,                            
    @SerialName("pin") val pin: String,                          
    @SerialName("alias") val alias: String,
    @SerialName("transferKey") val transferKey: String? = null,  
    





    @SerialName("env") val environment: String? = null,          
    @SerialName("account") val account: String? = null,          
    @SerialName("appVersion") val appVersion: String? = null,    
    @SerialName("formatVersion") val formatVersion: String? = null, 
    




    @SerialName("dir") val dir: String? = null,
    




    @Transient val ips: List<String> = emptyList(),
) {
    
    val candidates: List<String> get() = ips.ifEmpty { listOf(ip) }
}






@Serializable
data class LinkIntentRequest(
    @SerialName("info") val info: DeviceInfo,
    @SerialName("migration") val migration: MigrationHandshake,
    @SerialName("sync") val sync: Boolean,
)





@Serializable
data class LinkPrecheckRequest(
    @SerialName("info") val info: DeviceInfo,
    @SerialName("migration") val migration: MigrationHandshake,
)


@Serializable
data class LinkPeerStateResponse(@SerialName("state") val state: String)


@Serializable
data class ClaimRequest(@SerialName("info") val info: DeviceInfo)





enum class LinkPeerState(val wire: String) {
    
    CLEAN("clean"),

    
    SAME_ACCOUNT("same-account"),

    
    DIFFERENT_ACCOUNT("different-account");

    companion object {
        fun of(wire: String?): LinkPeerState? = entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }
    }
}








val P2pJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}
