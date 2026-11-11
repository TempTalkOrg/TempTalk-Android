package com.difft.android.chat.crypto








object GroupCryptoConstants {
    
    
    const val GCM_AAD_GROUP_NAME = "tt-grp-v1|gcm|name"
    const val GCM_AAD_GROUP_AVATAR = "tt-grp-v1|gcm|avatar"

    
    const val BLOB_VERSION_V1: UByte = 1u

    
    const val R_GROUP_SIZE = 32
    const val K_GROUP_SIZE = 32
    const val GCM_NONCE_SIZE = 12
    const val GCM_TAG_SIZE = 16
    const val ED25519_SIGNATURE_SIZE = 64

    
    
    val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2A,                                    
        0x30, 0x05,                                    
        0x06, 0x03, 0x2B, 0x65, 0x70,                  
        0x03, 0x21, 0x00                               
    )
}
