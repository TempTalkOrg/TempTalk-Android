package com.difft.android.selector.basic

import android.content.Intent
import com.difft.android.selector.R
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.engine.ImageEngine
import com.difft.android.selector.engine.VideoPlayerEngine
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnExternalPreviewEventListener
import com.difft.android.selector.style.PictureSelectorStyle
import com.difft.android.selector.utils.DoubleUtils

class PictureSelectionPreviewModel(private val selector: PictureSelector) {
    private val selectionConfig: SelectorConfig = SelectorConfig()

    init {
        SelectorProviders.getInstance().addSelectorConfigQueue(selectionConfig)
        selectionConfig.isPreviewZoomEffect = false
    }

    /** Image Load the engine */
    fun setImageEngine(engine: ImageEngine?): PictureSelectionPreviewModel {
        selectionConfig.imageEngine = engine
        return this
    }

    /** Set up player engine. Used to preview custom player instances, MediaPlayer by default. */
    fun setVideoPlayerEngine(engine: VideoPlayerEngine<*>?): PictureSelectionPreviewModel {
        selectionConfig.videoPlayerEngine = engine
        return this
    }

    /** PictureSelector theme style settings */
    fun setSelectorUIStyle(uiStyle: PictureSelectorStyle?): PictureSelectionPreviewModel {
        if (uiStyle != null) {
            selectionConfig.selectorStyle = uiStyle
        }
        return this
    }

    /** Set App Language */
    fun setLanguage(language: Int): PictureSelectionPreviewModel {
        selectionConfig.language = language
        return this
    }

    /** Set App default Language */
    fun setDefaultLanguage(defaultLanguage: Int): PictureSelectionPreviewModel {
        selectionConfig.defaultLanguage = defaultLanguage
        return this
    }

    /** View lifecycle listener */
    fun setAttachViewLifecycle(viewLifecycle: IBridgeViewLifecycle?): PictureSelectionPreviewModel {
        selectionConfig.viewLifecycle = viewLifecycle
        return this
    }

    /** Whether to play video automatically when previewing */
    fun isAutoVideoPlay(isAutoPlay: Boolean): PictureSelectionPreviewModel {
        selectionConfig.isAutoVideoPlay = isAutoPlay
        return this
    }

    /** The video supports pause and resume */
    fun isVideoPauseResumePlay(isPauseResumePlay: Boolean): PictureSelectionPreviewModel {
        selectionConfig.isPauseResumePlay = isPauseResumePlay
        return this
    }

    /** Intercept external preview click events, and users can implement their own preview framework */
    fun setExternalPreviewEventListener(listener: OnExternalPreviewEventListener?): PictureSelectionPreviewModel {
        selectionConfig.onExternalPreviewEventListener = listener
        return this
    }

    /** @param isHidePreviewDownload Previews do not show downloads */
    fun isHidePreviewDownload(isHidePreviewDownload: Boolean): PictureSelectionPreviewModel {
        selectionConfig.isHidePreviewDownload = isHidePreviewDownload
        return this
    }

    /** @param isHidePreviewShare Previews do not show share button */
    fun isHidePreviewShare(isHidePreviewShare: Boolean): PictureSelectionPreviewModel {
        selectionConfig.isHidePreviewShare = isHidePreviewShare
        return this
    }

    /** @param isShowConfidentialTip Show confidential message tip bar */
    fun isShowConfidentialTip(isShowConfidentialTip: Boolean): PictureSelectionPreviewModel {
        selectionConfig.isShowConfidentialTip = isShowConfidentialTip
        return this
    }

    /**
     * preview LocalMedia
     *
     * You can use [setInjectActivityPreviewFragment] interface for custom preview.
     */
    fun startActivityPreview(currentPosition: Int, isDisplayDelete: Boolean, list: ArrayList<LocalMedia>) {
        if (!DoubleUtils.isFastDoubleClick()) {
            val activity = selector.getActivity()
                ?: throw NullPointerException("Activity cannot be null")
            if (selectionConfig.imageEngine == null && selectionConfig.chooseMode != SelectMimeType.ofAudio()) {
                throw NullPointerException("imageEngine is null,Please implement ImageEngine")
            }
            if (list.isEmpty()) {
                throw NullPointerException("preview data is null")
            }
            val intent = Intent(activity, PictureSelectorTransparentActivity::class.java)
            selectionConfig.addSelectedPreviewResult(list)
            intent.putExtra(PictureConfig.EXTRA_EXTERNAL_PREVIEW, true)
            intent.putExtra(PictureConfig.EXTRA_MODE_TYPE_SOURCE, PictureConfig.MODE_TYPE_EXTERNAL_PREVIEW_SOURCE)
            intent.putExtra(PictureConfig.EXTRA_PREVIEW_CURRENT_POSITION, currentPosition)
            intent.putExtra(PictureConfig.EXTRA_EXTERNAL_PREVIEW_DISPLAY_DELETE, isDisplayDelete)
            val fragment = selector.getFragment()
            if (fragment != null) {
                fragment.startActivity(intent)
            } else {
                activity.startActivity(intent)
            }
            if (selectionConfig.isPreviewZoomEffect) {
                activity.overridePendingTransition(R.anim.ps_anim_fade_in, R.anim.ps_anim_fade_in)
            } else {
                val windowAnimationStyle = selectionConfig.selectorStyle.windowAnimationStyle!!
                activity.overridePendingTransition(windowAnimationStyle.activityEnterAnimation, R.anim.ps_anim_fade_in)
            }
        }
    }
}
