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

@Singleton
class NewMessageDecryptionUtil @Inject constructor(
    private val encryptionDataManager: EncryptionDataManager
) {
    fun decrypt(envelope: Envelope): SignalServiceDataClass? {
        val content = if (envelope.getType().number == Envelope.Type.ENCRYPTEDTEXT_VALUE) { //is encrypted envelop
            L.i { "[Message] decrypt encrypted message===${envelope.timestamp}" }
            val version = envelope.content.first().toUInt().shr(4).toInt()

            if (version !in MESSAGE_MINIMUM_SUPPORTED_VERSION..MESSAGE_MAX_SUPPORTED_VERSION) {
                // Drop instead of throwing: version mismatch is permanent, and
                // throwing would land the envelope in the failed_message retry queue.
                L.w { "[Message] decrypt: unsupported version=$version, drop ts=${envelope.timestamp}" }
                return null
            }

            val encryptedContent = envelope.content.drop(1).toByteArray()
            val encryptedMessage = EncryptedMessageProtos.EncryptContent.parseFrom(encryptedContent)
            val decryptResult = try {
                val dtProto = DtProto(version)
                dtProto.use {
                    it.decryptMessage(
                        encryptedMessage.signedEKey.toByteArray().map { it.toUByte() },
                        encryptedMessage.identityKey.toByteArray().map { it.toUByte() },
                        Base64.decode(envelope.identityKey).map { it.toUByte() }.drop(1),
                        null, // cachedTheirIdKey: no local cache yet
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
                        val dtProto = DtProto(version)
                        dtProto.use {
                            it.decryptMessage(
                                encryptedMessage.signedEKey.toByteArray().map { it.toUByte() },
                                encryptedMessage.identityKey.toByteArray().map { it.toUByte() },
                                Base64.decode(envelope.identityKey).map { it.toUByte() }.drop(1),
                                null, // cachedTheirIdKey: no local cache yet
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
            val rawDecrypted = decryptResult.plainText.map { it.toByte() }.toByteArray()
            val afterPadding = rawDecrypted.removePadding()
            afterPadding
        } else {
            envelope.content.toByteArray()
        }
        if (envelope.getType().number == Envelope.Type.NOTIFY_VALUE) {
            val contentString = String(content)
            val notifyMessage = Gson().fromJson(
                contentString,
                TTNotifyMessage::class.java
            )
            // Front-runner classifier log for the whole notify path. Subsequent
            // handlers (GroupUpdater / ContactsUpdater / ...) emit their own
            // entry logs once dispatched; this one tells you what arrived and
            // which sub-path it should hit before any dispatching happens.
            L.i {
                val d = notifyMessage.data
                "[Message] received notify type=${notifyMessage.notifyType}" +
                        " groupDetail=${d?.groupNotifyDetailedType}" +
                        " action=${d?.actionType}" +
                        " changeType=${d?.changeType}" +
                        " gid=${d?.gid}" +
                        " display=${notifyMessage.display}"
            }
            // Local debug only: logcat-only (not persisted to file), contains sensitive fields
            // (group/member names, critical alert content). Do NOT promote to L.i.
            // L.d { "[Message] received notify raw json=$contentString" }
            return SignalServiceDataClass(envelope, null, notifyMessage)
        } else {
            val contentObj = org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content.parseFrom(content)
            return SignalServiceDataClass(envelope, contentObj, null)
        }
    }
}