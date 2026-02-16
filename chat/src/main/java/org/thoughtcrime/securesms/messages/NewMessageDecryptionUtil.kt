package org.thoughtcrime.securesms.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_CURRENT_VERSION
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_MINIMUM_SUPPORTED_VERSION
import com.difft.android.websocket.api.util.removePadding
import com.google.gson.Gson
import org.signal.libsignal.protocol.InvalidVersionException
import org.thoughtcrime.securesms.cryptonew.EncryptionDataManager
import com.difft.android.base.utils.Base64
import org.whispersystems.signalservice.internal.push.EncryptedMessageProtos
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import uniffi.dtproto.DtProto
import uniffi.dtproto.DtProtoException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewMessageDecryptionUtil @Inject constructor(
    private val encryptionDataManager: EncryptionDataManager
) {
    fun decrypt(envelope: Envelope): SignalServiceDataClass {
        val content = if (envelope.getType().number == Envelope.Type.ENCRYPTEDTEXT_VALUE) { //is encrypted envelop
            L.i { "[Message] decrypt encrypted message===${envelope.timestamp}" }
            val version = envelope.content.first().toUInt().shr(4).toInt()

            if (version > MESSAGE_CURRENT_VERSION || version < MESSAGE_MINIMUM_SUPPORTED_VERSION) {
                throw InvalidVersionException("Unknown version: $version")
            }

            val encryptedContent = envelope.content.drop(1).toByteArray()
            val encryptedMessage = EncryptedMessageProtos.EncryptContent.parseFrom(encryptedContent)
            val decryptResult = try {
                val dtProto = DtProto(2)  // Version is set to 2
                dtProto.use {
                    it.decryptMessage(
                        encryptedMessage.signedEKey.toByteArray().map { it.toUByte() },
                        encryptedMessage.identityKey.toByteArray().map { it.toUByte() },
                        Base64.decode(envelope.identityKey).map { it.toUByte() }.drop(1),
                        encryptedMessage.eKey.toByteArray().map { it.toUByte() },
                        encryptionDataManager.getAciIdentityKey().privateKey.serialize().map { it.toUByte() },
                        Base64.decode(envelope.peerContext).map { it.toUByte() },
                        encryptedMessage.cipherText.toByteArray().map { it.toUByte() },
                    )
                }
            } catch (e: Exception) {
                // Decryption with the original identity key fails, trying to decrypt with the old identity key.
                if (e is DtProtoException.DecryptMessageDataException && encryptionDataManager.hasOldAciIdentityKey() && !encryptionDataManager.checkOldAciIdentityExpired()) {
                    try {
                        val dtProto = DtProto(2)  // Version is set to 2
                        dtProto.use {
                            it.decryptMessage(
                                encryptedMessage.signedEKey.toByteArray().map { it.toUByte() },
                                encryptedMessage.identityKey.toByteArray().map { it.toUByte() },
                                Base64.decode(envelope.identityKey).map { it.toUByte() }.drop(1),
                                encryptedMessage.eKey.toByteArray().map { it.toUByte() },
                                encryptionDataManager.getAciIdentityOldKey().privateKey.serialize().map { it.toUByte() },
                                Base64.decode(envelope.peerContext).map { it.toUByte() },
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
            decryptResult.plainText.map { it.toByte() }.toByteArray().removePadding()
        } else {
//            L.i { "[Message] ====receive plain text message===$envelope" }
            envelope.content.toByteArray()
        }
        if (envelope.getType().number == Envelope.Type.NOTIFY_VALUE) {
            val contentString = String(content)
            val notifyMessage = Gson().fromJson(
                contentString,
                TTNotifyMessage::class.java
            )
            L.d { "[Message] 收到notify消息 ${notifyMessage.notifyType}" }
            return SignalServiceDataClass(envelope, null, notifyMessage)
        } else {
            val contentObj = org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content.parseFrom(content)
            return SignalServiceDataClass(envelope, contentObj, null)
        }
    }
}