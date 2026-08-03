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

/**
 * Thrown when a Base64-encoded envelope field (identityKey / peerContext) is
 * malformed. Same input → same failure, so [EnvelopToMessageProcessor.classify]
 * routes this to [DropReason.BASE64_DECODE_FAILED] (Permanent).
 *
 * Wrapping the raw `IOException` / `IllegalArgumentException` / `NullPointerException`
 * from `Base64.decode` lets `classify()` match on a stable type instead of
 * sniffing stack frames.
 */
class Base64DecodeException(field: String, cause: Throwable?) :
    RuntimeException("Base64 decode failed for envelope field: $field", cause)

@Singleton
class NewMessageDecryptionUtil @Inject constructor(
    private val encryptionDataManager: EncryptionDataManager,
    private val gson: Gson,
) {
    fun decrypt(envelope: Envelope): SignalServiceDataClass? {
        val typeNumber = envelope.getType().number

        // NOTIFY: server-driven plaintext control signal (group / contact updates).
        // It is not an E2E chat message and is never rendered as one — it is parsed
        // as JSON and dispatched to the notify handlers. Kept as its own path.
        if (typeNumber == Envelope.Type.NOTIFY_VALUE) {
            val contentString = String(envelope.content.toByteArray())
            val notifyMessage = gson.fromJson(
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
        }

        // Security whitelist: ENCRYPTEDTEXT is the ONLY type that carries a real
        // E2E-encrypted, signed, source-authenticated message. Envelope.type is a
        // server-controlled field, so any other value (PLAINTEXT/CIPHERTEXT/
        // KEY_EXCHANGE/PREKEY_BUNDLE/RECEIPT/UNKNOWN/...) would otherwise let a
        // malicious or compromised server hand us unauthenticated content and have
        // us render it as a genuine message — i.e. forge messages from any sender.
        // Senders only ever emit ENCRYPTEDTEXT, so dropping everything else costs
        // no legitimate functionality.
        if (typeNumber != Envelope.Type.ENCRYPTEDTEXT_VALUE) {
            L.w { "[Message] drop non-encrypted envelope type=$typeNumber ts=${envelope.timestamp}" }
            return null
        }

        L.i { "[Message] decrypt encrypted message===${envelope.timestamp}" }
        val version = envelope.content.first().toUInt().shr(4).toInt()

        if (version !in MESSAGE_MINIMUM_SUPPORTED_VERSION..MESSAGE_MAX_SUPPORTED_VERSION) {
            // Drop instead of throwing: version mismatch is permanent, and
            // throwing would land the envelope in the failed_message retry queue.
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
                    null, // cachedTheirIdKey: no local cache yet
                    encryptedMessage.eKey.toByteArray().map { it.toUByte() },
                    encryptionDataManager.getAciIdentityKey().privateKey.serialize().map { it.toUByte() },
                    peerContextBytes.map { it.toUByte() },
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
                            identityKeyBytes.map { it.toUByte() }.drop(1),
                            null, // cachedTheirIdKey: no local cache yet
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

    /**
     * Decode a Base64 envelope field, wrapping any failure (returns-null,
     * IOException, IllegalArgumentException, NPE) as [Base64DecodeException]
     * so the classifier gets a stable type to match on.
     */
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
