package com.difft.android.websocket.api.messages

import org.signal.libsignal.protocol.IdentityKey
import com.difft.android.websocket.api.push.exceptions.ProofRequiredException
import com.difft.android.websocket.api.push.exceptions.RateLimitException


class SendMessageResult private constructor(
    val address: String,
    val success: Success?,
    private val networkFailure: Boolean,
    private val unregisteredFailure: Boolean,
    private val identityFailure: IdentityFailure?,
    private val proofRequiredFailure: ProofRequiredException?,
    private val rateLimitFailure: RateLimitException?
) {

    fun isSuccess(): Boolean {
        return success != null
    }

    class Success internal constructor(
        val isNeedsSync: Boolean,
        val duration: Long,
//        val content: SignalServiceProtos.Content,
        val systemShowTimestamp: Long,
        val notifySequenceId: Long,
        val sequenceId: Long
    ) {
        override fun toString(): String {
            return "Success{" +
                    "needsSync=" + isNeedsSync +
                    ", duration=" + duration +
//                    ", content=" + content +
                    ", systemShowTimestamp=" + systemShowTimestamp +
                    ", notifySequenceId=" + notifySequenceId +
                    ", sequenceId=" + sequenceId +
                    '}'
        }
    }

    class IdentityFailure private constructor(val identityKey: IdentityKey) {
        override fun toString(): String {
            return "IdentityFailure{" +
                    "identityKey=" + identityKey +
                    '}'
        }
    }

    override fun toString(): String {
        return "SendMessageResult{" +
                "address=" + address +
                ", success=" + success +
                ", networkFailure=" + networkFailure +
                ", unregisteredFailure=" + unregisteredFailure +
                ", identityFailure=" + identityFailure +
                ", proofRequiredFailure=" + proofRequiredFailure +
                ", rateLimitFailure=" + rateLimitFailure +
                '}'
    }

    companion object {
        fun success(
            address: String,
            needsSync: Boolean,
            duration: Long,
//            content: SignalServiceProtos.Content,
            systemShowTimestamp: Long,
            notifySequenceId: Long,
            sequenceId: Long
        ): SendMessageResult {
            return SendMessageResult(
                address,
                Success(
                    needsSync,
                    duration,
//                    content,
                    systemShowTimestamp,
                    notifySequenceId,
                    sequenceId
                ),
                false,
                false,
                null,
                null,
                null
            )
        }
    }
}
