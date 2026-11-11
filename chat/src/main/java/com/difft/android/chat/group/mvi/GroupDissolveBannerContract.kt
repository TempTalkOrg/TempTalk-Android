package com.difft.android.chat.group.mvi

import org.difft.app.database.models.GroupModel

object GroupDissolveBannerContract {
    data class State(
        val groupId: String = "",
        val expireTime: Long? = null,
        val joinCallBarVisible: Boolean = false,
        val dismissed: Boolean = false,
        val daysLeft: Int? = null,
    )

    sealed interface Intent {
        data class UpdateGroup(val group: GroupModel?) : Intent
        data class SetJoinCallBarVisible(val visible: Boolean) : Intent
        data object Refresh : Intent
        data object Dismiss : Intent
    }
}

internal fun shouldShowGroupDissolveBanner(
    expireTime: Long?, now: Long, dismissed: Boolean, joinCallBarVisible: Boolean,
): Boolean = expireTime != null && expireTime > now && !dismissed && !joinCallBarVisible
