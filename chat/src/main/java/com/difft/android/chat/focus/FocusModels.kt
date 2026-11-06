package com.difft.android.chat.focus

import com.google.gson.annotations.SerializedName

/**
 * The server's `focus` projection (`GET/PUT/DELETE /v1/focus` responses and notify type 29). The JSON
 * names are the cross-platform contract and are also what [GroupFocusStore] persists, so they are
 * pinned explicitly. Defaults mirror the server's "no row" state.
 */
data class FocusSnapshot(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("groupIds") val groupIds: List<String> = emptyList(),
    /** Epoch ms; null = manual end (or off). */
    @SerializedName("until") val until: Long? = null,
    @SerializedName("othersAllowMentionMe") val othersAllowMentionMe: Boolean = true,
    @SerializedName("othersAllowMentionAll") val othersAllowMentionAll: Boolean = false,
    @SerializedName("othersAllowGroupCall") val othersAllowGroupCall: Boolean = false,
    @SerializedName("foldOthers") val foldOthers: Boolean = true,
    /** Non-null = the focused group is gone (0 dismissed, 1 removed, 2 self left, 3 system, 4 expired). */
    @SerializedName("invalidReason") val invalidReason: Int? = null,
    /** Monotonic per account; 0 when the server has no row. */
    @SerializedName("version") val version: Int = 0,
)

/** `data` of `GET /v1/focus` and of the write endpoints. */
data class FocusResponse(
    @SerializedName("featureEnabled") val featureEnabled: Boolean = false,
    @SerializedName("serverTime") val serverTime: Long = 0L,
    @SerializedName("focus") val focus: FocusSnapshot? = null,
)

/** `data` of a notify type 29 (`FOCUS`); [trigger] is `USER`, `EXPIRED` or `INVALIDATED`. */
data class FocusNotifyData(
    @SerializedName("trigger") val trigger: String? = null,
    @SerializedName("focus") val focus: FocusSnapshot? = null,
)

/** Body of `PUT v1/focus`; [duration] is seconds (3600 / 28800) or null for "until I end it". */
data class FocusOpenRequest(
    @SerializedName("gids") val gids: List<String>,
    @SerializedName("duration") val duration: Int?,
)

/** Body of `PUT v1/focus/until`. */
data class FocusUntilRequest(
    @SerializedName("duration") val duration: Int?,
)

/** Body of `PUT v1/focus/options`; null fields are left unchanged by the server. At least one must be set. */
data class FocusOptionsRequest(
    @SerializedName("othersAllowMentionMe") val othersAllowMentionMe: Boolean? = null,
    @SerializedName("othersAllowMentionAll") val othersAllowMentionAll: Boolean? = null,
    @SerializedName("othersAllowGroupCall") val othersAllowGroupCall: Boolean? = null,
    @SerializedName("foldOthers") val foldOthers: Boolean? = null,
)

/** What the repository caches: the focus snapshot plus the server feature switch (only ever taken from a GET). */
data class GroupFocusState(
    @SerializedName("featureEnabled") val featureEnabled: Boolean = false,
    @SerializedName("focus") val focus: FocusSnapshot = FocusSnapshot(),
    /** Account the cache belongs to; a cache of another account is discarded on load. */
    @SerializedName("ownerUid") val ownerUid: String? = null,
)

/**
 * Focus as every consumer must read it: the cached snapshot judged against the trusted clock, so a
 * local expiry needs no new version. Derive with [derive]; never read the raw snapshot for decisions.
 */
data class EffectiveFocus(
    val featureEnabled: Boolean = false,
    /** Feature on, enabled, and [until] not reached. Includes the invalid state. */
    val active: Boolean = false,
    /** [active] and the focused group is gone; every group then counts as "other". */
    val invalid: Boolean = false,
    val invalidReason: Int? = null,
    val groupIds: Set<String> = emptySet(),
    val until: Long? = null,
    val othersAllowMentionMe: Boolean = true,
    val othersAllowMentionAll: Boolean = false,
    val othersAllowGroupCall: Boolean = false,
    val foldOthers: Boolean = true,
    val version: Int = 0,
) {
    /** The group the UI shows; the set may hold several (server cap 5). */
    val focusGid: String? get() = groupIds.firstOrNull()

    /** True when [gid] is a valid focused group right now. */
    fun isFocusedGroup(gid: String): Boolean = active && !invalid && gid in groupIds

    companion object {
        val INACTIVE = EffectiveFocus()

        fun derive(state: GroupFocusState, now: Long): EffectiveFocus {
            val f = state.focus
            val until = f.until
            val active = state.featureEnabled && f.enabled && (until == null || now < until)
            return EffectiveFocus(
                featureEnabled = state.featureEnabled,
                active = active,
                invalid = active && f.invalidReason != null,
                invalidReason = f.invalidReason.takeIf { active },
                groupIds = if (active) f.groupIds.toSet() else emptySet(),
                until = until.takeIf { active },
                othersAllowMentionMe = f.othersAllowMentionMe,
                othersAllowMentionAll = f.othersAllowMentionAll,
                othersAllowGroupCall = f.othersAllowGroupCall,
                foldOthers = f.foldOthers,
                version = f.version,
            )
        }
    }
}
