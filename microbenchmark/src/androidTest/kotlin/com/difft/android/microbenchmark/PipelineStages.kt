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

    
    fun decryptOne(envelope: SignalServiceProtos.Envelope): ByteArray {
        
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
