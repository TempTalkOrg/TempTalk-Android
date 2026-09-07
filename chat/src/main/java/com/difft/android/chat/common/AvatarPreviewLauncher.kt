package com.difft.android.chat.common

import androidx.fragment.app.FragmentActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.media.AvatarEncryptedProvider
import com.difft.android.chat.media.AvatarPreview
import com.difft.android.network.group.GroupAvatarData
import com.difft.android.selector.basic.PictureSelector
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.pictureselector.GlideEngine
import com.difft.android.selector.pictureselector.PictureSelectorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen avatar preview for contact and group avatars. Both flows end in the same
 * PictureSelector preview fed by a decrypting `content://` uri, so no plaintext touches disk.
 * On a cache miss the avatar is downloaded behind a wait dialog (the avatar utils own the cache
 * layout and retry); a failed download is logged there and the preview is simply not opened.
 */
object AvatarPreviewLauncher {

    /** Contact avatar (public or remark) addressed by its CDN [url] and AES-GCM [key]. */
    suspend fun previewContact(activity: FragmentActivity, url: String?, key: String?) {
        if (url.isNullOrEmpty()) return
        val cached = withContext(Dispatchers.IO) { AvatarUtil.getCacheFile(url) }
        if (cached != null) {
            open(activity, AvatarEncryptedProvider.DIR_AVATAR, cached)
            return
        }

        L.i { "[AvatarPreview] contact avatar cache miss, downloading" }
        ComposeDialogManager.showWait(activity, "")
        val downloaded = AvatarUtil.ensureCached(activity, url, key.orEmpty())
        ComposeDialogManager.dismissWait()
        if (downloaded != null && !activity.isFinishing) {
            open(activity, AvatarEncryptedProvider.DIR_AVATAR, downloaded)
        }
    }

    /** Group avatar; [GroupAvatarUtil.ensureCached] already downloads + encrypts on a miss. */
    suspend fun previewGroup(activity: FragmentActivity, data: GroupAvatarData) {
        val serverId = data.serverId ?: return
        val cached = withContext(Dispatchers.IO) { GroupAvatarUtil.getCacheFile(serverId) }
        val file = if (cached != null) {
            cached
        } else {
            L.i { "[AvatarPreview] group avatar cache miss, downloading" }
            ComposeDialogManager.showWait(activity, "")
            val fetched = GroupAvatarUtil.ensureCached(activity, data)
            ComposeDialogManager.dismissWait()
            fetched
        }
        if (file != null && !activity.isFinishing) {
            open(activity, AvatarEncryptedProvider.DIR_GROUP_AVATAR, file)
        }
    }

    private fun open(activity: FragmentActivity, dir: String, cacheFile: File) {
        val list = arrayListOf<LocalMedia>(AvatarPreview.localMediaFor(dir, cacheFile))
        PictureSelector.create(activity)
            .openPreview()
            .isHidePreviewDownload(true)
            .isHidePreviewShare(true)
            .setDefaultLanguage(LanguageConfig.ENGLISH)
            .setLanguage(PictureSelectorUtils.getLanguage(activity))
            .setImageEngine(GlideEngine.createGlideEngine())
            .startActivityPreview(0, false, list)
    }
}
