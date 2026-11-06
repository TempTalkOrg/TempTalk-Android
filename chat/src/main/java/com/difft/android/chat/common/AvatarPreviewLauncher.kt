package com.difft.android.chat.common

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.withTaskWait
import com.difft.android.chat.media.AvatarEncryptedProvider
import com.difft.android.chat.media.AvatarPreview
import com.difft.android.network.group.GroupAvatarData
import com.difft.android.selector.basic.PictureSelector
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.pictureselector.GlideEngine
import com.difft.android.selector.pictureselector.PictureSelectorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen avatar preview for contact and group avatars. Both flows end in the same
 * PictureSelector preview fed by a decrypting `content://` uri, so no plaintext touches disk.
 * On a cache miss the avatar is downloaded behind a wait dialog (the avatar utils own the cache
 * layout and retry); a failed download is logged there and the preview is simply not opened.
 *
 * A page that can sit in the home screen's detail stack passes its [PageWait], so a page closed
 * during the download releases the dialog; otherwise the dialog is claimed and released by token.
 */
object AvatarPreviewLauncher {

    /** Contact avatar (public or remark) addressed by its CDN [url] and AES-GCM [key]. */
    suspend fun previewContact(activity: FragmentActivity, url: String?, key: String?, wait: PageWait? = null) {
        if (url.isNullOrEmpty()) return
        val cached = withContext(Dispatchers.IO) { AvatarUtil.getCacheFile(url) }
        if (cached != null) {
            open(activity, AvatarEncryptedProvider.DIR_AVATAR, cached)
            return
        }

        L.i { "[AvatarPreview] contact avatar cache miss, downloading" }
        val downloaded = withWait(activity, wait) { AvatarUtil.ensureCached(activity, url, key.orEmpty()) }
        if (downloaded != null && !activity.isFinishing) {
            open(activity, AvatarEncryptedProvider.DIR_AVATAR, downloaded)
        }
    }

    /** Group avatar; [GroupAvatarUtil.ensureCached] already downloads + encrypts on a miss. */
    suspend fun previewGroup(activity: FragmentActivity, data: GroupAvatarData, wait: PageWait? = null) {
        val serverId = data.serverId ?: return
        val cached = withContext(Dispatchers.IO) { GroupAvatarUtil.getCacheFile(serverId) }
        val file = if (cached != null) {
            cached
        } else {
            L.i { "[AvatarPreview] group avatar cache miss, downloading" }
            withWait(activity, wait) { GroupAvatarUtil.ensureCached(activity, data) }
        }
        if (file != null && !activity.isFinishing) {
            open(activity, AvatarEncryptedProvider.DIR_GROUP_AVATAR, file)
        }
    }

    /** The wait dialog around a download, released on every path (cancellation included). */
    internal suspend fun <T> withWait(activity: FragmentActivity, wait: PageWait?, block: suspend () -> T): T =
        withTaskWait(activity, wait, block)

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

/**
 * One avatar preview at a time for a page, with its own wait. The cache check runs before the
 * wait shows, so a second tap in that window would otherwise start a second download and viewer,
 * and the first download's release would close the wait while the second still runs.
 */
class AvatarPreviewFlight(private val page: Fragment) {

    private val previewWait = PageWait(page)
    private var previewJob: Job? = null

    /** Starts [preview] on the page's view scope unless one is running; returns whether it did. */
    fun launch(preview: suspend (previewWait: PageWait) -> Unit): Boolean {
        if (previewJob?.isActive == true) return false
        previewJob = page.viewLifecycleOwner.lifecycleScope.launch { preview(previewWait) }
        return true
    }
}
