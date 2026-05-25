package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L

/**
 * Classified outcome of [EnvelopToMessageProcessor.process].
 *
 * Replaces the previous "throw any exception" contract: callers now branch on
 * `when` so permanent failures stop feeding the retry queue at the source.
 */
sealed class EnvelopeProcessResult {
    /**
     * Decryption + content processing + persistence all succeeded.
     * `result` may still be null for non-displayable envelopes (sync ACKs,
     * drops by size guard, NOTIFY envelopes that don't materialize a message).
     */
    data class Success(val result: EnvelopToMessageProcessor.Result?) : EnvelopeProcessResult()

    /** Deterministic failure — same input will fail the same way. Do NOT enqueue for retry. */
    data class PermanentFailure(val reason: DropReason, val cause: Throwable?) : EnvelopeProcessResult()

    /** Possibly recoverable failure (network, DB lock, missing referenced data). Enqueue for retry with backoff. */
    data class TransientFailure(val cause: Throwable) : EnvelopeProcessResult()
}

/**
 * Why a permanent drop was issued. Used by [reportPermanentDrop] for Crashlytics
 * reason tagging — Crashlytics coalesces same-reason / same-stack events into a
 * single issue so reason-tagged event counts are the diagnostic signal.
 */
enum class DropReason {
    /** `DtProtoException.DecryptMessageDataException` — MAC / ciphertext corrupt after both ACI key fallbacks. */
    DECRYPTION_FAILED,

    /** `InvalidProtocolBufferException` — protobuf bytes corrupt. */
    DECRYPTION_DATA_CORRUPT,

    /** `JsonSyntaxException` — NOTIFY JSON corrupt. */
    MALFORMED_NOTIFY_JSON,

    /** `Base64DecodeException` — envelope.identityKey / peerContext not valid Base64. */
    BASE64_DECODE_FAILED,

    /**
     * `PendingMessageHelper.buildEnvelope` failed to construct an
     * [org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope]
     * from the pending-message payload (Base64.decode IOException,
     * protobuf builder error, NPE on a malformed field, etc.).
     */
    BUILD_ENVELOPE_FAILED,

    /**
     * `FailedMessageProcessor.bumpRetryOrGiveUp` hit MAX_RETRIES — gives
     * up retrying a transient failure and reports it as permanent.
     */
    RETRY_EXHAUSTED,

    /** Defensive: PR #755 drops unsupported version inside `decrypt()` (returns null instead of throwing) so this enum value should be unreachable in practice. */
    UNSUPPORTED_VERSION,
}

/**
 * Record a permanent envelope drop to both the local log file AND Crashlytics.
 *
 * No throttling: classification + MAX_RETRIES cap per-envelope event count to
 * ≤ 1 *per delivery channel* (Permanent path is fire-and-drop, retry give-up
 * is single-shot). The same envelope arriving via WebSocket AND FCM/pull can
 * fire twice — accepted, since Crashlytics coalesces duplicates by stack trace
 * and the per-issue event count is itself useful volume diagnostic.
 *
 * `L.w` is unconditional so on-device logs always have the diagnostic record
 * even when Crashlytics network upload fails.
 */
fun reportPermanentDrop(
    reason: DropReason,
    cause: Throwable?,
    envelopeTs: Long,
    tag: String,
) {
    L.w {
        "[Message][$tag] permanent drop ts=$envelopeTs reason=$reason: " +
            (cause?.stackTraceToString() ?: "no cause")
    }
}
