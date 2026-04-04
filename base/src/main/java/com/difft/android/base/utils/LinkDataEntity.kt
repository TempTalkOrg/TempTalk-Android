package com.difft.android.base.utils

import android.net.Uri

/**
 * Data entity for deeplink/navigation information passed via Intent.
 */
data class LinkDataEntity(
    var category: Int,
    val gid: String?,
    val uid: String?,
    var uri: Uri? = null
) {
    companion object {
        const val LINK_CATEGORY = "LINK_CATEGORY"

        const val CATEGORY_PUSH = 1
        const val CATEGORY_MESSAGE = 2
        const val CATEGORY_SCHEME = 3
        const val CATEGORY_BACKGROUND_CONNECTION_SETTINGS = 4
    }
}
