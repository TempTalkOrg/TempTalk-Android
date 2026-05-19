package com.difft.android.call.data

import org.difft.app.database.models.ContactorModel

data class CallUserDisplayInfo(
    val id: String?,
    val name: String?,
    val avatarData: AvatarData?
)

sealed class AvatarData {
    data class FromContactor(val contactor: ContactorModel) : AvatarData()
    data class FromNameOrUid(val name: String?, val userId: String) : AvatarData()
}
