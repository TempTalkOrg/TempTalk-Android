package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_MAX_SUPPORTED_VERSION
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_MINIMUM_SUPPORTED_VERSION
import com.difft.android.websocket.api.util.removePadding
import com.google.gson.Gson
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.base.utils.Base64
import org.whispersystems.signalservice.internal.push.EncryptedMessageProtos
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import uniffi.dtproto.DtProto
import uniffi.dtproto.DtProtoException
import javax.inject.Inject
import javax.inject.Singleton










class Base64DecodeException(field: String, cause: Throwable?) :
    RuntimeException("Base64 decode failed for envelope field: $field", cause)

private val ACCOUNT_SOURCE = Regex("^\\+\\d+$")


internal fun isAccountSource(source: String?): Boolean = source != null && ACCOUNT_SOURCE.matches(source)

@Singleton
class NewMessageDecryptionUtil @Inject constructor(
    private val encryptionDataManager: EncryptionDataManager,
    private val gson: Gson,
) {
    fun decrypt(envelope: Envelope): SignalServiceDataClass? {
        val typeNumber = envelope.getType().number

        
        
        
        if (typeNumber == Envelope.Type.NOTIFY_VALUE) {
            
            
            
            
            if (isAccountSource(envelope.source)) {
                L.w { "[Message] drop notify from account source ts=${envelope.timestamp}" }
                return null
            }
            val contentString = String(envelope.content.toByteArray())
            val notifyMessage = gson.fromJson(
                contentString,
                TTNotifyMessage::class.java
            )
            
            
            
            
            L.i {
                val d = notifyMessage.data
                "[Message] received notify type=${notifyMessage.notifyType}" +
                        " groupDetail=${d?.groupNotifyDetailedType}" +
                        " action=${d?.actionType}" +
                        " changeType=${d?.changeType}" +
                        " gid=${d?.gid}" +
                        " display=${notifyMessage.display}"
            }
            
            
            
            return SignalServiceDataClass(envelope, null, notifyMessage)
        }

        
        
        
        
        
        
        
        
        if (typeNumber != Envelope.Type.ENCRYPTEDTEXT_VALUE) {
            L.w { "[Message] drop non-encrypted envelope type=$typeNumber ts=${envelope.timestamp}" }
            return null
        }

        L.d { "[Message] decrypt encrypted message===${envelope.timestamp}" }
        val version = envelope.content.first().toUInt().shr(4).toInt()

        if (version !in MESSAGE_MINIMUM_SUPPORTED_VERSION..MESSAGE_MAX_SUPPORTED_VERSION) {
            
            
            L.w { "[Message] decrypt: unsupported version=$version, drop ts=${envelope.timestamp}" }
            return null
        }

        val identityKeyBytes = decodeBase64OrThrow("identityKey", envelope.identityKey)
        val peerContextBytes = decodeBase64OrThrow("peerContext", envelope.peerContext)

        val encryptedContent = envelope.content.drop(1).toByteArray()
        val encryptedMessage = EncryptedMessageProtos.EncryptContent.parseFrom(encryptedContent)
        val decryptResult = try {
            val dtProto = DtProto(version)
            dtProto.use {
                it.decryptMessage(
                    encryptedMessage.signedEKey.toByteArray().map { it.toUByte() },
                    encryptedMessage.identityKey.toByteArray().map { it.toUByte() },
                    identityKeyBytes.map { it.toUByte() }.drop(1),
                    null, 
                    encryptedMessage.eKey.toByteArray().map { it.toUByte() },
                    encryptionDataManager.getAciIdentityKey().privateKey.serialize().map { it.toUByte() },
                    peerContextBytes.map { it.toUByte() },
                    encryptedMessage.cipherText.toByteArray().map { it.toUByte() },
                )
            }
        } catch (e: Exception) {
            
            if (e is DtProtoException.DecryptMessageDataException && encryptionDataManager.hasOldAciIdentityKey() && !encryptionDataManager.checkOldAciIdentityExpired()) {
                try {
                    val dtProto = DtProto(version)
                    dtProto.use {
                        it.decryptMessage(
                            encryptedMessage.signedEKey.toByteArray().map { it.toUByte() },
                            encryptedMessage.identityKey.toByteArray().map { it.toUByte() },
                            identityKeyBytes.map { it.toUByte() }.drop(1),
                            null, 
                            encryptedMessage.eKey.toByteArray().map { it.toUByte() },
                            encryptionDataManager.getAciIdentityOldKey().privateKey.serialize().map { it.toUByte() },
                            peerContextBytes.map { it.toUByte() },
                            encryptedMessage.cipherText.toByteArray().map { it.toUByte() },
                        )
                    }
                } catch (e: Exception) {
                    L.e { "[Message] decrypt error with old identity key:${e.message}" }
                    throw e
                }
            } else {
                throw e
            }
        }
        val rawDecrypted = decryptResult.plainText.map { it.toByte() }.toByteArray()
        val afterPadding = rawDecrypted.removePadding()

        val contentObj = org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content.parseFrom(afterPadding)
        return SignalServiceDataClass(envelope, contentObj, null)
    }

    




    private fun decodeBase64OrThrow(field: String, value: String?): ByteArray {
        return try {
            Base64.decode(value) ?: throw Base64DecodeException(field, null)
        } catch (e: Base64DecodeException) {
            throw e
        } catch (e: Exception) {
            throw Base64DecodeException(field, e)
        }
    }
}
