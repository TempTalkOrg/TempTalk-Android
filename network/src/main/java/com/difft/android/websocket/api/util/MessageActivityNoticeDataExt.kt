package com.difft.android.websocket.api.util

import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.MessageActivityNoticeData
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CopyData
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageActivityNotice

/**
 * Proto ↔ Kotlin mapping between [MessageActivityNotice] (oneof typeData) and
 * [MessageActivityNoticeData] (flat [Type] + fields).
 *
 * Located in :network (not :database) because :database cannot import
 * SignalServiceProtos — the module dependency graph is
 *   :network → :database → :base
 * so proto-aware extensions must live in :network or above.
 *
 * ## Unknown-case handling
 *
 * Proto's `MessageActivityNotice.TypeDataCase` enum includes:
 *   - one entry per declared oneof case (e.g., `COPYDATA`)
 *   - `TYPEDATA_NOT_SET` for absent/unrecognized
 *
 * When receiving a future-type message the current client doesn't understand
 * (e.g., FORWARDDATA when only COPYDATA is recognized here), the protobuf
 * runtime preserves the unknown field in the message but `getTypeDataCase()`
 * still returns `TYPEDATA_NOT_SET`. Either way the mapping returns `null` and
 * the receiver drops the message — see
 * `MessageContentProcessor.handleActivityNoticeMessage` (added in Phase 5).
 *
 * Both `when` expressions have no `else` branch — adding a new typeData case
 * without updating both directions of the mapping fails to compile, forcing
 * both directions to stay in sync.
 */

/**
 * Build a proto `MessageActivityNotice` (typeData oneof) from the flat Kotlin
 * model. Sender is responsible for assembling the wire payload; the [type]
 * field drives which oneof case is populated.
 *
 * Returns the typeData portion only; callers add `conversation` and any
 * outer-frame fields themselves.
 */
fun MessageActivityNoticeData.toProtoTypeDataBuilder(): MessageActivityNotice.Builder {
    val builder = MessageActivityNotice.newBuilder()
    when (this.type) {
        MessageActivityNoticeData.Type.COPY -> {
            builder.copyData = CopyData.newBuilder()
                .addAllSourceAuthorIds(this.sourceAuthorIds)
                .setMessageCount(this.messageCount)
                .setCombinedForwardMode(this.combinedForwardMode.toProtoEnum())
                .build()
        }
    }
    return builder
}

/**
 * Parse a proto `MessageActivityNotice` into the Kotlin domain model.
 * Returns null when:
 *   - typeData oneof is not set (TYPEDATA_NOT_SET)
 *   - the case is recognized by proto but not yet handled by this client
 *     (the `null` branch in [TypeDataCase.toKotlinType])
 *
 * Receiver must drop the message in either case (do not silently render as
 * an unrelated type — see §3.1 rule 1 in the design doc).
 */
fun MessageActivityNotice.toKotlinDataOrNull(): MessageActivityNoticeData? {
    return when (this.typeDataCase) {
        MessageActivityNotice.TypeDataCase.COPYDATA -> {
            val copy = this.copyData
            MessageActivityNoticeData(
                type = MessageActivityNoticeData.Type.COPY,
                sourceAuthorIds = copy.sourceAuthorIdsList.toList(),
                // Protocol-violation degrade: coerce to >= 1 so plurals always renders.
                // Do NOT raise to authorIds.size — a peer could craft a payload with
                // messageCount=1 and 100 authors to inflate the displayed count.
                // Same defense as ForwardNotice receive path (MessageContentProcessor.kt:256).
                messageCount = maxOf(1, copy.messageCount),
                combinedForwardMode = copy.combinedForwardMode.toKotlinEnum(),
            )
        }
        MessageActivityNotice.TypeDataCase.TYPEDATA_NOT_SET -> null   // sentinel: drop
    }
}

/**
 * Proto ↔ Kotlin mapping for [CombinedForwardMode] ↔
 * [MessageActivityNotice.CombinedForwardMode]. Shared by both copy notice (CopyData)
 * and forward notice (ForwardNoticeMessage) — see PRD v1.0 §5.3.
 *
 * Both directions use `when` as an expression with no `else` branch — adding a new
 * proto value without updating both mappings fails to compile, forcing them in sync.
 *
 * Unknown future values from peers: protobuf-java-lite decodes them as the first
 * declared enum value (= UNKNOWN), so the inbound mapping collapses both "unset" and
 * "unknown future value" to [CombinedForwardMode.UNKNOWN]. The receiver does NOT drop
 * the message in that case — UNKNOWN is a legitimate mode (pre-PRD §5 sender / no CF
 * involvement), unlike [ForwardNoticeData.Scene] where UNKNOWN means "drop".
 */
fun CombinedForwardMode.toProtoEnum(): MessageActivityNotice.CombinedForwardMode = when (this) {
    CombinedForwardMode.UNKNOWN                   -> MessageActivityNotice.CombinedForwardMode.UNKNOWN
    CombinedForwardMode.CONTAINS_COMBINED_FORWARD -> MessageActivityNotice.CombinedForwardMode.CONTAINS_COMBINED_FORWARD
    CombinedForwardMode.ALL_COMBINED_FORWARD      -> MessageActivityNotice.CombinedForwardMode.ALL_COMBINED_FORWARD
    CombinedForwardMode.SUB_COMBINED_FORWARD      -> MessageActivityNotice.CombinedForwardMode.SUB_COMBINED_FORWARD
}

fun MessageActivityNotice.CombinedForwardMode.toKotlinEnum(): CombinedForwardMode = when (this) {
    MessageActivityNotice.CombinedForwardMode.UNKNOWN                   -> CombinedForwardMode.UNKNOWN
    MessageActivityNotice.CombinedForwardMode.CONTAINS_COMBINED_FORWARD -> CombinedForwardMode.CONTAINS_COMBINED_FORWARD
    MessageActivityNotice.CombinedForwardMode.ALL_COMBINED_FORWARD      -> CombinedForwardMode.ALL_COMBINED_FORWARD
    MessageActivityNotice.CombinedForwardMode.SUB_COMBINED_FORWARD      -> CombinedForwardMode.SUB_COMBINED_FORWARD
}
