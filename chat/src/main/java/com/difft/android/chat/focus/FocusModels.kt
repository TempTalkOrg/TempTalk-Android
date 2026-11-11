package com.difft.android.chat.focus

import com.google.gson.annotations.SerializedName






data class FocusSnapshot(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("groupIds") val groupIds: List<String> = emptyList(),
    
    @SerializedName("until") val until: Long? = null,
    @SerializedName("othersAllowMentionMe") val othersAllowMentionMe: Boolean = true,
    @SerializedName("othersAllowMentionAll") val othersAllowMentionAll: Boolean = false,
    @SerializedName("othersAllowGroupCall") val othersAllowGroupCall: Boolean = false,
    @SerializedName("foldOthers") val foldOthers: Boolean = true,
    
    @SerializedName("invalidReason") val invalidReason: Int? = null,
    
    @SerializedName("version") val version: Int = 0,
)


data class FocusResponse(
    @SerializedName("featureEnabled") val featureEnabled: Boolean = false,
    @SerializedName("serverTime") val serverTime: Long = 0L,
    @SerializedName("focus") val focus: FocusSnapshot? = null,
)


data class FocusNotifyData(
    @SerializedName("trigger") val trigger: String? = null,
    @SerializedName("focus") val focus: FocusSnapshot? = null,
)


data class FocusOpenRequest(
    @SerializedName("gids") val gids: List<String>,
    @SerializedName("duration") val duration: Int?,
)


data class FocusUntilRequest(
    @SerializedName("duration") val duration: Int?,
)


data class FocusOptionsRequest(
    @SerializedName("othersAllowMentionMe") val othersAllowMentionMe: Boolean? = null,
    @SerializedName("othersAllowMentionAll") val othersAllowMentionAll: Boolean? = null,
    @SerializedName("othersAllowGroupCall") val othersAllowGroupCall: Boolean? = null,
    @SerializedName("foldOthers") val foldOthers: Boolean? = null,
)


data class GroupFocusState(
    @SerializedName("featureEnabled") val featureEnabled: Boolean = false,
    @SerializedName("focus") val focus: FocusSnapshot = FocusSnapshot(),
    
    @SerializedName("ownerUid") val ownerUid: String? = null,
)





data class EffectiveFocus(
    val featureEnabled: Boolean = false,
    
    val active: Boolean = false,
    
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
    
    val focusGid: String? get() = groupIds.firstOrNull()

    
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
