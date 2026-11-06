package com.difft.android.chat.ui

/**
 * Which rows the 1:1 chat settings page shows. Recomputed when the contact turns out to be (or
 * stops being) an official account after the page opened: the official-account cache may fill
 * after the first render, and the page must not keep offering write actions (remove, block) to a
 * bot, nor hide them from a contact.
 */
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
