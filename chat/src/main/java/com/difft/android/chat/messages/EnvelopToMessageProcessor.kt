package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.google.gson.JsonSyntaxException
import com.google.protobuf.InvalidProtocolBufferException
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import uniffi.dtproto.DtProtoException
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class EnvelopToMessageProcessor @Inject constructor(
    private val newMessageDecryptionUtil: NewMessageDecryptionUtil,
    private val messageContentProcessor: MessageContentProcessor,
    private val dbMessageStore: DBMessageStore,
) {

    /**
     * Decrypt + content-process + persist a single envelope, returning an
     * [EnvelopeProcessResult] classifying the outcome.
     *
     * Replaces the previous "throw any exception" contract: callers now branch
     * on `when` so deterministic failures (decryption, protobuf, JSON, Base64)
     * stop feeding the failed_message retry queue at the source.
     *
     * Persistence (`putWhenNonExist`) is included inside this method so its
     * WCDBException flows through the same [classify] boundary as decryption
     * and MessageContentProcessor exceptions — callers don't need their own
     * try-catch around the DB write. `putWhenNonExist` is idempotent
     * (primary-key dedup + in-memory `processingMessageIds`), so multiple
     * delivery channels (WebSocket / FCM / pull) safely converge.
     *
     * Dispatcher: `Dispatchers.IO`. The work mix is IO-dominated —
     * `MessageContentProcessor` issues many WCDB writes (group / contact /
     * reaction / room) plus occasional network calls (group sync), and
     * `putWhenNonExist` is a blocking WCDB write. The only CPU-bound piece
     * is `decrypt()` (DtProto JNI, ~5–10 ms per envelope) which is short
     * enough that running it on the IO pool is fine — the alternative
     * (`Dispatchers.Default`, ~CPU-count threads) would block app-wide
     * Default consumers when those DB writes stall, and is dramatically
     * smaller than the IO pool's default 64 threads under burst load.
     * All callers (`appScope`, `IncomingMessageObserver` scope) are also
     * `Dispatchers.IO`, so this `withContext` is a no-op in the steady
     * state and only kicks in if a Default-scoped caller is added.
     */
    suspend fun process(envelope: Envelope, tag: String): EnvelopeProcessResult = withContext(Dispatchers.IO) {
        try {
            val result = envelope
                .takeIf { envelopSizeNotExceedOneMillion(it, tag) }
                ?.decrypt()
                ?.takeIf { dataMessageBodyNotExceed8K(it, tag) }
                ?.processContentToMessage(tag)
                ?.compatTimestamp()
            result?.let { dbMessageStore.putWhenNonExist(it.message) }
            EnvelopeProcessResult.Success(result)
        } catch (e: CancellationException) {
            // Coroutine cancellation (logout / scope shutdown) is not a message
            // failure — rethrow so the caller's coroutine actually cancels.
            throw e
        } catch (e: Throwable) {
            // System-level errors (OOM, StackOverflow) bubble up to crash
            // reporters; don't try to handle them as message failures.
            if (e is OutOfMemoryError || e is StackOverflowError) throw e
            classify(envelope, tag, e)
        }
    }

    /**
     * Map a thrown exception to a [EnvelopeProcessResult]. Permanent failures
     * are deterministic (same input → same failure); transient failures might
     * recover on retry. When in doubt we default to transient — losing a
     * recoverable message is a worse failure mode than retrying a doomed one,
     * because `MAX_RETRIES` caps the latter.
     *
     * `WCDBException` from `putWhenNonExist` (above) lands here in the `else`
     * branch — transient (disk full, DB lock are recoverable). Same for
     * `IOException` (network) / `NullPointerException` (race) / other
     * `DtProtoException` variants whose internal semantics aren't deterministic.
     */
    private fun classify(envelope: Envelope, tag: String, e: Throwable): EnvelopeProcessResult {
        val reason: DropReason? = when (e) {
            // Both ACI key fallbacks failed → ciphertext / MAC is junk. Deterministic.
            is DtProtoException.DecryptMessageDataException -> DropReason.DECRYPTION_FAILED
            // Protobuf bytes are corrupt — will fail the same way every time.
            is InvalidProtocolBufferException -> DropReason.DECRYPTION_DATA_CORRUPT
            // NOTIFY JSON is corrupt.
            is JsonSyntaxException -> DropReason.MALFORMED_NOTIFY_JSON
            // Envelope identityKey / peerContext was not valid Base64 — see
            // [NewMessageDecryptionUtil.decodeBase64OrThrow].
            is Base64DecodeException -> DropReason.BASE64_DECODE_FAILED
            // Everything else → Transient. MAX_RETRIES bounds the cost if it
            // turns out to be permanent.
            else -> null
        }
        return if (reason != null) {
            // Local log + Crashlytics record happen in [reportPermanentDrop] at
            // the call site (it has the canonical `tag` and bundles both writes
            // so they can't drift apart).
            EnvelopeProcessResult.PermanentFailure(reason, e)
        } else {
            L.e {
                "[Message][$tag] TRANSIENT failure ts=${envelope.timestamp} err=${e.stackTraceToString()}"
            }
            EnvelopeProcessResult.TransientFailure(e)
        }
    }

    private fun dataMessageBodyNotExceed8K(it: SignalServiceDataClass, tag: String): Boolean {
        val dataMessageBodyExceed8K =
            (it.signalServiceContent?.dataMessage?.body?.length
                ?: 0) > 8 * 1024
        if (dataMessageBodyExceed8K) {
            L.e { "[Message][${tag}] dataMessage body exceed 8K, ignore this envelop" }
        }
        return !dataMessageBodyExceed8K
    }

    private fun envelopSizeNotExceedOneMillion(it: Envelope, tag: String): Boolean {
        val sizeExceedOneMillion = it.toByteArray().size > 1024 * 1024
        if (sizeExceedOneMillion) {
            L.e { "[Message][${tag}] message size exceed 1 million, ignore this envelop" }
        }
        return !sizeExceedOneMillion
    }

    private fun Envelope.decrypt(): SignalServiceDataClass? {
        // 对 envelope 的 content 进行解密（针对端上加密）
        return newMessageDecryptionUtil.decrypt(this)
    }

    private suspend fun SignalServiceDataClass.processContentToMessage(tag: String): Result? {
        L.d { "[Message][${tag}] Start processing Message " }
        return messageContentProcessor.process(content = this, tag)?.let {
            Result(
                message = it,
                shouldShowNotification = shouldShowNotification,
                conversation = conversation
            )
        }
    }

    private fun Result.compatTimestamp(): Result {
        if (message.systemShowTimestamp == 0L) {
            message.systemShowTimestamp = message.timeStamp
        }
        return this
    }

    data class Result(
        val message: Message,
        val shouldShowNotification: Boolean,
        val conversation: For,
    )
}
