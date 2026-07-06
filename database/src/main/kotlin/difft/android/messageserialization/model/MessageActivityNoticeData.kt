package difft.android.messageserialization.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * Immutable "activity notice" payload. Mirrors proto `MessageActivityNotice` (see
 * `SignalService.proto`), which carries activity notifications inserted into the
 * source conversation when a user takes an action against messages in it.
 *
 * Current activity types (this iteration): COPY
 * Future types (in oneof typeData on the wire): FORWARD, SAVE_TO_NOTES, PASTE, ...
 *
 * Operator is NOT stored here — it comes from `Envelope.source` on receive
 * and is implicit (self) on send.
 *
 * ## Domain model vs proto wire model
 *
 * On the wire, type-specific data lives in a `oneof typeData` block (one
 * sub-message per type). At the Kotlin domain layer we flatten the fields and
 * use a top-level [type] enum as discriminator — same pattern as
 * [ForwardNoticeData]. Rationale:
 *
 *   - Gson can (de)serialize this directly with `@SerializedName` per enum value;
 *     a sealed-class hierarchy would require a custom RuntimeTypeAdapterFactory and
 *     complicate Job persistence/recovery.
 *   - This iteration's only type (COPY) shares the same shape as future forward
 *     migration (sourceAuthorIds + messageCount), so flat fields incur no harm.
 *   - When a future type needs type-specific data (e.g., PASTE's clipboard id),
 *     refactor to a sealed-class hierarchy at that point.
 *
 * The proto↔Kotlin mapping ([MessageActivityNoticeDataExt]) is responsible for
 * translating `oneof typeData` cases into the flat [type] discriminator + fields.
 */
data class MessageActivityNoticeData(
    val type: Type,
    /** Deduped author IDs, first-seen order, unbounded. */
    val sourceAuthorIds: List<String>,
    /** Top-level message count (>= 1). For combined forward this counts as 1. */
    val messageCount: Int,
    /**
     * Combined-forward mode of the source selection (PRD v1.0 §5.3). Default UNKNOWN
     * keeps existing callers untouched — Phase 4 dispatch sites will populate explicitly.
     */
    val combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
) : Serializable {

    /**
     * @SerializedName is MANDATORY on every value — Job serialize/deserialize uses
     * Gson which defaults to enum `.name()`. Renaming a Kotlin enum constant is
     * allowed (refactoring symbol), but the @SerializedName string value must NEVER
     * change after release — in-flight persisted JobData would fail to deserialize
     * → Job is dropped.
     */
    enum class Type {
        @SerializedName("COPY")
        COPY,
        // Future:
        // @SerializedName("FORWARD") FORWARD,
        // @SerializedName("SAVE_TO_NOTES") SAVE_TO_NOTES,
        // @SerializedName("PASTE") PASTE,
    }
}
