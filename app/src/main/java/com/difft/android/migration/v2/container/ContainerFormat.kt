package com.difft.android.migration.v2.container


































internal object ContainerFormat {

    
    const val TAG_MANIFEST: Byte = 0x01

    
    const val TAG_RECORD: Byte = 0x02

    
    const val TAG_ATTACHMENT: Byte = 0x03

    
    const val TAG_END: Byte = 0x7F

    
    const val FRAME_PREFIX_SIZE = 1 + 4

    
    const val BODY_FORMAT_VERSION = 1

    
    const val ENCODING_JSONL = "jsonl"

    









    const val FORMAT_VERSION = "1.0"

    
    const val SOURCE_PLATFORM_ANDROID = "android"

    




    val SOURCE_CAPABILITIES_ANDROID = listOf("keyReset", "voiceWaveform")

    




    const val CAPABILITY_CREDENTIAL_HANDOFF = "credentialHandoff"

    
    const val ATTACHMENT_COPY_BUFFER = 64 * 1024

    











    const val MAX_FRAME_PAYLOAD_BYTES = 64 * 1024 * 1024
}





















data class ContainerManifest(
    val bodyFormatVersion: Int,
    val encoding: String,
    val createdAt: Long,
    val recordCount: Int,
    val attachmentCount: Int,
    val attachmentBytes: Long = 0,
    val sourceAccount: String,
    val sourcePlatform: String,
    val sourceAppVersion: String,
    val formatVersion: String,
    val sourceCapabilities: List<String> = emptyList(),
)
