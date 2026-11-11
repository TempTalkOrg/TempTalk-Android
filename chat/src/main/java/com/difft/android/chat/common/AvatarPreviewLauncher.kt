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










object AvatarPreviewLauncher {

    
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






class AvatarPreviewFlight(private val page: Fragment) {

    private val previewWait = PageWait(page)
    private var previewJob: Job? = null

    
    fun launch(preview: suspend (previewWait: PageWait) -> Unit): Boolean {
        if (previewJob?.isActive == true) return false
        previewJob = page.viewLifecycleOwner.lifecycleScope.launch { preview(previewWait) }
        return true
    }
}
