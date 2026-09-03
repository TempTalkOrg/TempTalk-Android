package com.difft.android.microbenchmark

import com.difft.android.base.utils.Base64
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_CURRENT_VERSION
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_MINIMUM_SUPPORTED_VERSION
import com.difft.android.websocket.api.util.paddedMessageBody
import com.google.protobuf.ByteString
import org.difft.app.database.models.MessageModel
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.whispersystems.signalservice.internal.push.EncryptedMessageProtos
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import uniffi.dtproto.DtProto

/**
 * Self-contained encrypted-envelope fixture for the pipeline benchmarks (issue #1166 L2/L3).
 *
 * Keypairs are generated in-process (libsignal Curve); every envelope is encrypted with the
 * production send-path call shape (`encryptMessage(receiverPub-minus-type-byte, emptyMap(),
 * senderPriv, paddedPlaintext)` at MESSAGE_CURRENT_VERSION). For a 1:1 message the envelope's
 * peerContext is empty — the send path fills it only for group recipients — so decryption
 * needs nothing outside this process.
 */
class PipelineFixture private constructor(
    val plaintextContents: List<ByteArray>,
    val envelopeBytes: List<ByteArray>,
    val envelopes: List<SignalServiceProtos.Envelope>,
    val receiverPrivateKey: List<UByte>,
) {
    companion object {
        fun create(count: Int): PipelineFixture {
            val senderKeys = ECKeyPair.generate()
            val receiverKeys = ECKeyPair.generate()
            // The wire form carries the key-type byte; the receive path Base64-decodes then drop(1)s.
            val senderIdentityKeyB64 = Base64.encodeBytes(senderKeys.publicKey.serialize())
            val receiverPubDropped = receiverKeys.publicKey.serialize().drop(1).map { b -> b.toUByte() }
            val senderPrivate = senderKeys.privateKey.serialize().map { b -> b.toUByte() }

            val plaintexts = (0 until count).map { i ->
                SignalServiceProtos.Content.newBuilder()
                    .setDataMessage(
                        SignalServiceProtos.DataMessage.newBuilder().setBody("benchmark message $i"),
                    )
                    .build()
                    .toByteArray()
            }

            val envelopeBytes = DtProto(MESSAGE_CURRENT_VERSION).use { proto ->
                plaintexts.mapIndexed { i, content ->
                    val encrypted = proto.encryptMessage(
                        receiverPubDropped,
                        emptyMap(),
                        senderPrivate,
                        content.paddedMessageBody().map { it.toUByte() },
                    )
                    val encryptContent = EncryptedMessageProtos.EncryptContent.newBuilder()
                        .setVersion(MESSAGE_CURRENT_VERSION)
                        .setCipherText(encrypted.cipherText.toByteString())
                        .setSignedEKey(encrypted.signedEKey.toByteString())
                        .setEKey(encrypted.eKey.toByteString())
                        .setIdentityKey(encrypted.identityKey.toByteString())
                        .build()
                    SignalServiceProtos.Envelope.newBuilder()
                        .setType(SignalServiceProtos.Envelope.Type.ENCRYPTEDTEXT)
                        .setSource(SENDER)
                        .setSourceDevice(1)
                        .setTimestamp(BASE_TS + i)
                        .setSystemShowTimestamp(BASE_TS + i)
                        .setContent(
                            ByteString.copyFrom(
                                // Wire prefix is a packed nibble pair (current<<4 | minimum),
                                // matching NewSignalServiceMessageSender.intsToByteHigh — the
                                // receive path reads the version back out of the high nibble.
                                byteArrayOf(
                                    ((MESSAGE_CURRENT_VERSION shl 4) or MESSAGE_MINIMUM_SUPPORTED_VERSION).toByte(),
                                ) + encryptContent.toByteArray(),
                            ),
                        )
                        .setIdentityKey(senderIdentityKeyB64)
                        .setPeerContext("")
                        .build()
                        .toByteArray()
                }
            }
            return PipelineFixture(
                plaintextContents = plaintexts,
                envelopeBytes = envelopeBytes,
                envelopes = envelopeBytes.map { SignalServiceProtos.Envelope.parseFrom(it) },
                receiverPrivateKey = receiverKeys.privateKey.serialize().map { it.toUByte() },
            )
        }

        private fun List<UByte>.toByteString(): ByteString =
            ByteString.copyFrom(map { it.toByte() }.toByteArray())

        const val BASE_TS = 1_700_000_000_000L
        const val SENDER = "+benchsender"
    }

    /** Production decrypt call shape — NewMessageDecryptionUtil.kt:81-107, re-expressed. */
    fun decryptOne(envelope: SignalServiceProtos.Envelope): ByteArray {
        // Version comes off the high nibble of the wire prefix, as production does.
        val version = envelope.content.first().toUInt().shr(4).toInt()
        val identityKeyBytes = Base64.decode(envelope.identityKey)
        val peerContextBytes = Base64.decode(envelope.peerContext)
        val encryptedContent = envelope.content.drop(1).toByteArray()
        val encryptedMessage = EncryptedMessageProtos.EncryptContent.parseFrom(encryptedContent)
        val result = DtProto(version).use {
            it.decryptMessage(
                encryptedMessage.signedEKey.toByteArray().map { b -> b.toUByte() },
                encryptedMessage.identityKey.toByteArray().map { b -> b.toUByte() },
                identityKeyBytes.map { b -> b.toUByte() }.drop(1),
                null,
                encryptedMessage.eKey.toByteArray().map { b -> b.toUByte() },
                receiverPrivateKey,
                peerContextBytes.map { b -> b.toUByte() },
                encryptedMessage.cipherText.toByteArray().map { b -> b.toUByte() },
            )
        }
        return result.plainText.map { it.toByte() }.toByteArray()
    }
}

/**
 * Minimal text-message Content→MessageModel mapping shared by the L2 convert stage and the
 * L3 half-pipeline: the persistence-relevant fields of a plain 1:1 text message, nothing
 * more. This is a SHAPE re-expression — the production mapping lives inside
 * MessageContentProcessor, which is not constructible in a benchmark process (15+ Hilt
 * dependencies) and additionally performs group/contact/receipt side effects that are out
 * of scope for a compute-stage benchmark.
 */
fun toMessageModel(
    envelope: SignalServiceProtos.Envelope,
    content: SignalServiceProtos.Content,
): MessageModel = MessageModel().apply {
    id = "${envelope.source}_${envelope.timestamp}"
    fromWho = envelope.source
    roomId = envelope.source
    roomType = 0
    timeStamp = envelope.timestamp
    systemShowTimestamp = envelope.systemShowTimestamp
    receivedTimeStamp = envelope.timestamp
    type = MessageModel.TYPE_TEXT
    messageText = content.dataMessage.body
}
