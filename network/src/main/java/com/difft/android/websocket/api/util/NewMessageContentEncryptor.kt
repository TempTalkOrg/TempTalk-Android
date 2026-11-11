package com.difft.android.websocket.api.util

import com.difft.android.base.log.lumberjack.L


interface INewMessageContentEncryptor {
    companion object {

        
        
        const val MESSAGE_MINIMUM_SUPPORTED_VERSION = 2
        const val MESSAGE_MAX_SUPPORTED_VERSION = 3
        const val MESSAGE_CURRENT_VERSION = 2

    }

    fun encryptOneToOneMessage(contentByteArray: ByteArray, hisPublicKey: String): EncryptResult
    fun encryptGroupMessage(
        contentByteArray: ByteArray,
        theirPubKeys: Map<String, String>
    ): EncryptResult

    fun generateKey(): ByteArray

    fun encryptCallKey(pubKeys: Map<String, String>, mKey: ByteArray?): EncryptCallKeyResult

    fun decryptCallKey(eKey: String, eMKey: String): ByteArray?

    







    fun encryptRtmMessage(message: ByteArray, localPrivateKey: ByteArray, aesKey: ByteArray, timestamp: Long): String

    






    fun decryptRtmMessage(cipherMessage: ByteArray, hisPublicKey: String, aesKey: ByteArray): ByteArray
}


data class RtmEncryptedMessage(
    val serverTimestamp: Long,
    val sendTimestamp: Long,
    val uuid: String,
    val signature: String,
    val payload: String,
)


data class EncryptResult(
    val cipherText: ByteArray,
    val signedEKey: ByteArray,
    val eKey: ByteArray,
    val identityKey: ByteArray,
    val ermKeys: Map<String, ByteArray>?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptResult

        if (!cipherText.contentEquals(other.cipherText)) return false
        if (!signedEKey.contentEquals(other.signedEKey)) return false
        if (!eKey.contentEquals(other.eKey)) return false
        if (!identityKey.contentEquals(other.identityKey)) return false
        if (ermKeys != other.ermKeys) return false

        return true
    }

    override fun hashCode(): Int {
        var result = cipherText.contentHashCode()
        result = 31 * result + signedEKey.contentHashCode()
        result = 31 * result + eKey.contentHashCode()
        result = 31 * result + identityKey.contentHashCode()
        result = 31 * result + (ermKeys?.hashCode() ?: 0)
        return result
    }
}

fun ByteArray.paddedMessageBody(): ByteArray {
    
    
    
    val paddedMessageLength = paddedMessageLength(this.size + 1) - 1
    val paddedMessage = ByteArray(paddedMessageLength)
    val paddingByte: Byte = 0x80.toByte()
    System.arraycopy(this, 0, paddedMessage, 0, this.size)
    paddedMessage[this.size] = paddingByte
    return paddedMessage
}

fun ByteArray.removePadding(): ByteArray {
    var paddingStart = this.size
    for (i in this.size - 1 downTo 0) {
        if (this[i] == 0x80.toByte()) {
            paddingStart = i
            break
        } else if (this[i] != 0x00.toByte()) {
            L.w { "[Message] Failed to remove padding, returning unstripped padding" }
            return this
        }
    }
    return this.copyOfRange(0, paddingStart)
}

private fun paddedMessageLength(messageLength: Int): Int {
    val messageLengthWithTerminator = messageLength + 1
    var messagePartCount = messageLengthWithTerminator / 160
    if (messageLengthWithTerminator % 160 != 0) {
        messagePartCount++
    }
    return messagePartCount * 160
}

class RustEncryptionException(e: Exception) : RuntimeException(e)

data class EncryptCallKeyResult(
    val mKey: ByteArray,
    val eMKeys: Map<String, ByteArray>?,
    val eKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptCallKeyResult

        if (!mKey.contentEquals(other.mKey)) return false
        if (eMKeys != other.eMKeys) return false
        if (!eKey.contentEquals(other.eKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mKey.contentHashCode()
        result = 31 * result + (eMKeys?.hashCode() ?: 0)
        result = 31 * result + eKey.contentHashCode()
        return result
    }
}