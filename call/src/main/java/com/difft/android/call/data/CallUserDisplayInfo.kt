package com.difft.android.call.data

import org.difft.app.database.models.ContactorModel

data class CallUserDisplayInfo(
    val id: String?,
    val name: String?,
    val avatarData: AvatarData?
)

/**
 * Data-only avatar description handed to `LCallToChatController.Avatar`. Compose compares it by
 * equals at that call site because this field-less sealed base is inferred stable, which is what
 * lets an equals-equal re-fetch skip the wrapper instead of re-running `setAvatar`. Keep the base
 * field-less and replace a wrapped [ContactorModel] wholesale rather than mutating it in place;
 * it is deliberately not annotated `@Stable`/`@Immutable` because the wrapped entity is mutable.
 */
sealed class AvatarData {
    data class FromContactor(val contactor: ContactorModel) : AvatarData()
    data class FromNameOrUid(val name: String?, val userId: String) : AvatarData()
}
