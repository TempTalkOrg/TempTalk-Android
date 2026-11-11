package com.difft.android.chat.ui







internal data class SingleChatSettingRows(
    val mute: Boolean,
    val remove: Boolean,
    val disappearing: Boolean,
    val block: Boolean,
    val commonGroups: Boolean,
    val createGroupAction: Boolean,
) {
    companion object {
        fun of(isSelf: Boolean, isOfficial: Boolean, inContacts: Boolean) = SingleChatSettingRows(
            mute = !isSelf,
            remove = !isSelf && !isOfficial && inContacts,
            disappearing = !isSelf,
            block = !isOfficial,
            commonGroups = !isSelf && !isOfficial,
            createGroupAction = !isOfficial,
        )
    }
}
