package org.difft.app.database.cache


data class ContactRemarkInfo(
    val remark: String? = null,
    val remarkAvatar: String? = null,
) {
    val isEmpty: Boolean
        get() = remark.isNullOrEmpty() && remarkAvatar.isNullOrEmpty()
}
