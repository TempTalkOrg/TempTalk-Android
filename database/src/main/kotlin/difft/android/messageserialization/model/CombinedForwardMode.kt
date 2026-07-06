package difft.android.messageserialization.model

import com.google.gson.annotations.SerializedName

/**
 * Describes how the source selection relates to combined-forward (a.k.a. "chat history")
 * messages, per PRD v1.0 §5.3. Shared by [MessageActivityNoticeData] (copy notice) and
 * [ForwardNoticeData] (forward notice).
 *
 * Wire: maps to proto `MessageActivityNotice.CombinedForwardMode` (see
 * `SignalService.proto`). Mapping lives in :network so :database stays proto-free.
 *
 * @SerializedName is MANDATORY on every value — Gson Job (de)serialization defaults to
 * enum `.name()`. The string value must NEVER change after release: in-flight persisted
 * JobData would fail to deserialize → Job is dropped.
 */
enum class CombinedForwardMode {
    /** No CF involvement, OR sender is pre-PRD §5 and didn't populate the field. */
    @SerializedName("UNKNOWN")
    UNKNOWN,

    /** Main-conversation selection: mixed (regular + at least one CF bubble). */
    @SerializedName("CONTAINS_COMBINED_FORWARD")
    CONTAINS_COMBINED_FORWARD,

    /** Main-conversation selection: every selected bubble is a CF. */
    @SerializedName("ALL_COMBINED_FORWARD")
    ALL_COMBINED_FORWARD,

    /** Detail-view operation against a sub-message inside a CF (any nesting level). */
    @SerializedName("SUB_COMBINED_FORWARD")
    SUB_COMBINED_FORWARD,
}
