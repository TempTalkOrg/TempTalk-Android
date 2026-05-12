package com.difft.android.websocket.api.util

import difft.android.messageserialization.model.ForwardNoticeData
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ForwardNoticeMessage.ForwardScene

/**
 * Proto ↔ Kotlin enum mapping for [ForwardNoticeData.Scene] ↔ [ForwardScene].
 *
 * Located in :network (not :database) because :database cannot import
 * SignalServiceProtos — the module dependency graph is
 *   :network  -> :database  -> :base
 * so proto-aware extensions must live in :network or above.
 *
 * Note on proto2 + unknown enum values: this .proto is `syntax = "proto2"`,
 * so protobuf-java-lite does NOT generate `UNRECOGNIZED` on enums.
 * Unknown values received from iOS/Web are dropped at parse time —
 * `hasScene()` returns false and `getScene()` returns the proto default
 * (first declared value). We keep `UNKNOWN` as the first declared value
 * so that both "unset" and "unknown future value" collapse to the same
 * sentinel the receiver can reject explicitly.
 *
 * [toKotlinEnum] returns `null` for `UNKNOWN` — the receive-side handler
 * is expected to drop the message in that case (see
 * `MessageContentProcessor.handleForwardNoticeMessage`). [toProtoEnum] stays
 * non-null because the sender's Kotlin enum has no UNKNOWN counterpart
 * and must never emit one.
 *
 * Both `when` expressions have no `else` branch — adding a new Scene
 * to proto without updating both mappings fails to compile, forcing
 * both directions to stay in sync.
 */

fun ForwardNoticeData.Scene.toProtoEnum(): ForwardScene = when (this) {
    ForwardNoticeData.Scene.SINGLE        -> ForwardScene.SINGLE
    ForwardNoticeData.Scene.ONE_BY_ONE    -> ForwardScene.ONE_BY_ONE
    ForwardNoticeData.Scene.COMBINED      -> ForwardScene.COMBINED
    ForwardNoticeData.Scene.SAVE_TO_NOTES -> ForwardScene.SAVE_TO_NOTES
}

fun ForwardScene.toKotlinEnum(): ForwardNoticeData.Scene? = when (this) {
    ForwardScene.UNKNOWN       -> null   // unset OR unknown future value → drop
    ForwardScene.SINGLE        -> ForwardNoticeData.Scene.SINGLE
    ForwardScene.ONE_BY_ONE    -> ForwardNoticeData.Scene.ONE_BY_ONE
    ForwardScene.COMBINED      -> ForwardNoticeData.Scene.COMBINED
    ForwardScene.SAVE_TO_NOTES -> ForwardNoticeData.Scene.SAVE_TO_NOTES
}
