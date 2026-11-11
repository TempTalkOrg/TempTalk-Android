package com.difft.android.chat.media

import com.difft.android.chat.common.AvatarCacheCipher
import com.difft.android.selector.entity.LocalMedia
import java.io.File








object AvatarPreview {

    fun localMediaFor(dir: String, cacheFile: File): LocalMedia {
        val uri = AvatarEncryptedProvider.contentUri(dir, cacheFile.name)
        return LocalMedia.create().apply {
            path = uri.toString()
            realPath = cacheFile.absolutePath
            fileName = cacheFile.name
            mimeType = "image/*"
            size = AvatarCacheCipher.plaintextLength(cacheFile)
        }
    }
}
