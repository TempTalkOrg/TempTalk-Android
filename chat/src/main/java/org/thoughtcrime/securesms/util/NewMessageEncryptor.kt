package org.thoughtcrime.securesms.util

import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.config.GlobalConfigsManager
import com.google.gson.Gson
import org.json.JSONObject
import org.thoughtcrime.securesms.cryptonew.EncryptionDataManager
import com.difft.android.websocket.api.util.EncryptCallKeyResult
import com.difft.android.websocket.api.util.EncryptResult
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_CURRENT_VERSION
import com.difft.android.websocket.api.util.RtmEncryptedMessage
import com.difft.android.websocket.api.util.RustEncryptionException
import com.difft.android.websocket.api.util.paddedMessageBody
import uniffi.dtproto.DtProto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewMessageEncryptor @Inject constructor(
    private val globalConfigsManager: GlobalConfigsManager,
    private val encryptionDataManager: EncryptionDataManager
) : INewMessageContentEncryptor {
    companion object {
        const val DJB_TYPE: Byte = 0x05;
    }

    override fun encryptOneToOneMessage(contentByteArray: ByteArray, hisPublicKey: String): EncryptResult {
        try {
            val privateKey = encryptionDataManager.getAciIdentityKey().privateKey.serialize().map { it.toUByte() }
            val publicKey = (Base64.decode(hisPublicKey).map { it.toUByte() }.removeKeyType())
            val dtProto = DtProto(MESSAGE_CURRENT_VERSION)  // Version is set to 2
            dtProto.use {
                val encryptedMessage = it.encryptMessage(publicKey, emptyMap(), privateKey, contentByteArray.paddedMessageBody().map { it.toUByte() })
                return EncryptResult(
                    cipherText = encryptedMessage.cipherText.map { it.toByte() }.toByteArray(),
                    signedEKey = encryptedMessage.signedEKey.map { it.toByte() }.toByteArray(),
                    eKey = encryptedMessage.eKey.map { it.toByte() }.toByteArray(),
                    identityKey = encryptedMessage.identityKey.map { it.toByte() }.toByteArray(),
                    ermKeys = encryptedMessage.ermKeys?.mapValues { it.value.map { it.toByte() }.toByteArray() }
                )
            }
        } catch (e: Exception) {
            throw RustEncryptionException(e)
        }
    }

    override fun encryptGroupMessage(contentByteArray: ByteArray, theirPubKeys: Map<String, String>): EncryptResult {
        try {
            val privateKey = encryptionDataManager.getAciIdentityKey().privateKey.serialize().map { it.toUByte() }
            val publicKeys = theirPubKeys.mapValues { Base64.decode(it.value).map { it.toUByte() }.removeKeyType() }
            val dtProto = DtProto(MESSAGE_CURRENT_VERSION)  // Version is set to 2
            dtProto.use {
                val encryptedMessage = it.encryptMessage(emptyList(), publicKeys, privateKey, contentByteArray.paddedMessageBody().map { it.toUByte() })
                return EncryptResult(
                    cipherText = encryptedMessage.cipherText.map { it.toByte() }.toByteArray(),
                    signedEKey = encryptedMessage.signedEKey.map { it.toByte() }.toByteArray(),
                    eKey = encryptedMessage.eKey.map { it.toByte() }.toByteArray(),
                    identityKey = encryptedMessage.identityKey.map { it.toByte() }.toByteArray(),
                    ermKeys = encryptedMessage.ermKeys?.mapValues { it.value.map { it.toByte() }.toByteArray() }
                )
            }
        } catch (e: Exception) {
            throw RustEncryptionException(e)
        }
    }

    private fun List<UByte>.removeKeyType(): List<UByte> {
        return if (size == 33) {
            if (first() == DJB_TYPE.toUByte()) drop(1) else throw Exception("Invalid key type")
        } else this
    }

    override fun enableLegacyContent(): Boolean {
        return globalConfigsManager.getNewGlobalConfigs()?.data?.message?.tunnelSecurityEnable ?: false
    }

//    override fun encryptCallKey(pubKeys: Map<String, String>, mKey: String?): EncryptCallKeyResult {
//        try {
//            val mKeyUBytes = mKey?.let { key ->
//                (Base64.decode(key).map { it.toUByte() }.removeKeyType())
//            }
//            val publicKeys = pubKeys.mapValues { Base64.decode(it.value).map { key -> key.toUByte() }.removeKeyType() }
//            val dtProto = DtProto(MESSAGE_CURRENT_VERSION)  // Version is set to 2
//            dtProto.use {
//                val encryptedMessage = it.encryptKey(publicKeys, mKeyUBytes)
//                return EncryptCallKeyResult(
//                    mKey = encryptedMessage.mKey.map { it.toByte() }.toByteArray(),
//                    eMKeys = encryptedMessage.eMKeys.mapValues { it.value.map { it.toByte() }.toByteArray() },
//                    eKey = encryptedMessage.eKey.map { it.toByte() }.toByteArray()
//                )
//            }
//        } catch (e: Exception) {
//            throw RustEncryptionException(e)
//        }
//    }

    override fun generateKey(): ByteArray {
        val dtProto = DtProto(MESSAGE_CURRENT_VERSION)
        return dtProto.generateKey().map { it.toByte() }.toByteArray()
    }

    override fun encryptCallKey(pubKeys: Map<String, String>, mKey: ByteArray?): EncryptCallKeyResult {
        try {
            val mKeyUBytes = mKey?.let { key ->
                (key.map { it.toUByte() }.removeKeyType())
            }
            val publicKeys = pubKeys.mapValues { Base64.decode(it.value).map { key -> key.toUByte() }.removeKeyType() }
            val dtProto = DtProto(MESSAGE_CURRENT_VERSION)  // Version is set to 2
            dtProto.use {
                val encryptedMessage = it.encryptKey(publicKeys, mKeyUBytes)
                return EncryptCallKeyResult(
                    mKey = encryptedMessage.mKey.map { it.toByte() }.toByteArray(),
                    eMKeys = encryptedMessage.eMKeys.mapValues { it.value.map { it.toByte() }.toByteArray() },
                    eKey = encryptedMessage.eKey.map { it.toByte() }.toByteArray()
                )
            }
        } catch (e: Exception) {
            throw RustEncryptionException(e)
        }
    }

    override fun decryptCallKey(eKey: String, eMKey: String): ByteArray? {
        try {
            val eKeyBytes = Base64.decode(eKey).map { it.toUByte() }.removeKeyType()
            val privateKey = encryptionDataManager.getAciIdentityKey().privateKey.serialize().map { it.toUByte() }
            val eMKeyBytes = Base64.decode(eMKey).map { it.toUByte() }

            val dtProto = DtProto(MESSAGE_CURRENT_VERSION)  // Version is set to 2
            dtProto.use {
                val dtDecryptedKey = it.decryptKey(eKeyBytes, privateKey, eMKeyBytes)
                val dtDecryptedKeyBytes = dtDecryptedKey.mKey.map { it.toByte() }.toByteArray()
//                return String(dtDecryptedKeyBytes, Charsets.UTF_8)

//                return Base64.encodeBytes(dtDecryptedKeyBytes)

                return dtDecryptedKeyBytes
            }
        } catch (e: Exception) {
            L.e { "decryptCallKey error:" + e.stackTraceToString() }
            return null
//            throw RustEncryptionException(e)
        }
    }

    override fun encryptRtmMessage(message: ByteArray, localPrivateKey: ByteArray, aesKey: ByteArray): String {
        try {
            val dtProto = DtProto(MESSAGE_CURRENT_VERSION)  // Version is set to 2
            dtProto.use {
                val mMessageUBytes = message.let { key -> (key.map { it.toUByte() }) }
                val mLocalKeyUBytes = localPrivateKey.let { key -> (key.map { it.toUByte() }) }
                val mAesKeyUBytes = aesKey.copyOfRange(0, 32).map { it.toUByte() }
                val encryptedRtmMessage = it.encryptRtmMessage(mAesKeyUBytes, mLocalKeyUBytes, mMessageUBytes)
                val signatureByteArray = android.util.Base64.encode(encryptedRtmMessage.signature.map { it.toByte() }.toByteArray(), android.util.Base64.NO_WRAP)
                val payloadByteArray = android.util.Base64.encode(encryptedRtmMessage.cipherText.map { it.toByte() }.toByteArray(), android.util.Base64.NO_WRAP)

                val encryptedMessage = JSONObject()
                    .put("sendTimestamp", System.currentTimeMillis())
                    .put("uuid", UUID.randomUUID().toString())
                    .put("signature", String(signatureByteArray, Charsets.UTF_8))
                    .put("payload", String(payloadByteArray, Charsets.UTF_8))
                return encryptedMessage.toString()
            }
        }catch (e: Exception) {
            throw RustEncryptionException(e)
        }
    }

    override fun decryptRtmMessage(cipherMessage: ByteArray, hisPublicKey: String, aesKey: ByteArray): ByteArray {
        try {
            val publicKey = (Base64.decode(hisPublicKey).map { it.toUByte() }.removeKeyType())
            val decodedCipherMessage = String(cipherMessage, Charsets.UTF_8)
            val rtmEncryptedMessage = Gson().fromJson(decodedCipherMessage, RtmEncryptedMessage::class.java)
            val encryptedTextUBytes = android.util.Base64.decode(rtmEncryptedMessage.payload, android.util.Base64.NO_WRAP).map { it.toUByte() }
            val signatureUBytes = android.util.Base64.decode(rtmEncryptedMessage.signature, android.util.Base64.NO_WRAP).map { it.toUByte() }
            val mAesKey = aesKey.copyOfRange(0, 32).map { it.toUByte() }
            val dtProto = DtProto(MESSAGE_CURRENT_VERSION)  // Version is set to 2
            dtProto.use {
                val decryptedMessage = it.decryptRtmMessage(signatureUBytes, publicKey, mAesKey, encryptedTextUBytes)
                if(decryptedMessage.verifiedIdResult){
                    val plainTextByteArray = decryptedMessage.plainText.map { it.toByte() }.toByteArray()
                    return plainTextByteArray
                }else{
                    throw RustEncryptionException( Exception("RTM message signature verification failed for public key: $hisPublicKey") )
                }
            }
        } catch (e: Exception) {
            L.e { "decryptRtmMessage Failed to decrypt RTM message"}
            throw RustEncryptionException(e)
        }
    }
}