package com.difft.android.chat.media

import com.difft.android.chat.common.AvatarCacheCipher
import com.difft.android.selector.entity.LocalMedia
import java.io.File

/**
 * Builds a [LocalMedia] entry for the full-screen [com.difft.android.selector.PictureSelector] avatar
 * preview so the image engine (Glide) reads **decrypted** bytes through [AvatarEncryptedProvider] —
 * no plaintext is materialised on disk. The avatar analogue of [AttachmentPreview].
 *
 * [dir] is [AvatarEncryptedProvider.DIR_AVATAR] or [AvatarEncryptedProvider.DIR_GROUP_AVATAR].
 */
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
