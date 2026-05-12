package org.difft.app.database.cache

/** Per-uid plaintext remark metadata cached in [ContactRemarkCache]. */
data class ContactRemarkInfo(
    val remark: String? = null,
    val remarkAvatar: String? = null,
) {
    val isEmpty: Boolean
        get() = remark.isNullOrEmpty() && remarkAvatar.isNullOrEmpty()
}
