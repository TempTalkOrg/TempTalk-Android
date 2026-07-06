package difft.android.messageserialization.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * Immutable "forward notice" payload.
 *
 * Operator is NOT stored — it comes from Envelope.source on receive
 * and is implicit (self) on send.
 */
data class ForwardNoticeData(
    val scene: Scene,
    /** Deduped author IDs, first-seen order, unbounded. */
    val sourceAuthorIds: List<String>,
    /** Top-level forwarded message count (>= 1). Nested forwards count as 1. */
    val messageCount: Int,
    /**
     * Combined-forward mode of the source selection (PRD v1.0 §5.3). Default UNKNOWN
     * keeps existing callers untouched — Phase 4 dispatch sites will populate explicitly.
     */
    val combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
) : Serializable {
    /**
     * @SerializedName is MANDATORY on every value — Job serialize/deserialize
     * uses Gson which defaults to enum `.name()`. Renaming a value without
     * @SerializedName breaks in-flight persisted Jobs (persisted JobData fails
     * to deserialize → Job is dropped).
     */
    enum class Scene {
        @SerializedName("SINGLE")
        SINGLE,
        @SerializedName("ONE_BY_ONE")
        ONE_BY_ONE,
        @SerializedName("COMBINED")
        COMBINED,
        @SerializedName("SAVE_TO_NOTES")
        SAVE_TO_NOTES,
    }
}
