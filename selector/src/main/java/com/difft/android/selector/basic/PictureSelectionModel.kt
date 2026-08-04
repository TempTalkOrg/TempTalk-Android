package com.difft.android.selector.basic

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.difft.android.selector.R
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.config.SelectModeConfig
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.engine.CompressFileEngine
import com.difft.android.selector.engine.CropFileEngine
import com.difft.android.selector.engine.ImageEngine
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnResultCallbackListener
import com.difft.android.selector.style.PictureSelectorStyle
import com.difft.android.selector.utils.DoubleUtils

class PictureSelectionModel(private val selector: PictureSelector, chooseMode: Int) {
    private val selectionConfig: SelectorConfig = SelectorConfig()

    init {
        SelectorProviders.getInstance().addSelectorConfigQueue(selectionConfig)
        selectionConfig.chooseMode = chooseMode
        setMaxVideoSelectNum(selectionConfig.maxVideoSelectNum)
    }

    /** PictureSelector theme style settings */
    fun setSelectorUIStyle(uiStyle: PictureSelectorStyle?): PictureSelectionModel {
        if (uiStyle != null) {
            selectionConfig.selectorStyle = uiStyle
        }
        return this
    }

    /** Set App Language */
    fun setLanguage(language: Int): PictureSelectionModel {
        selectionConfig.language = language
        return this
    }

    /** Set App default Language */
    fun setDefaultLanguage(defaultLanguage: Int): PictureSelectionModel {
        selectionConfig.defaultLanguage = defaultLanguage
        return this
    }

    /** Image Load the engine */
    fun setImageEngine(engine: ImageEngine?): PictureSelectionModel {
        selectionConfig.imageEngine = engine
        return this
    }

    /** Image Compress the engine */
    fun setCompressEngine(engine: CompressFileEngine?): PictureSelectionModel {
        selectionConfig.compressFileEngine = engine
        selectionConfig.isCompressEngine = true
        return this
    }

    /** Image Crop the engine */
    fun setCropEngine(engine: CropFileEngine?): PictureSelectionModel {
        selectionConfig.cropFileEngine = engine
        return this
    }

    /** @param selectionMode PictureSelector Selection model, [SelectModeConfig.MULTIPLE] or [SelectModeConfig.SINGLE] */
    fun setSelectionMode(selectionMode: Int): PictureSelectionModel {
        selectionConfig.selectionMode = selectionMode
        selectionConfig.maxSelectNum = if (selectionConfig.selectionMode == SelectModeConfig.SINGLE) 1 else selectionConfig.maxSelectNum
        return this
    }

    /** You can select pictures and videos at the same time */
    fun isWithSelectVideoImage(isWithVideoImage: Boolean): PictureSelectionModel {
        selectionConfig.isWithVideoImage = selectionConfig.chooseMode == SelectMimeType.ofAll() && isWithVideoImage
        return this
    }

    /** Select the maximum number of files */
    fun setMaxSelectNum(maxSelectNum: Int): PictureSelectionModel {
        selectionConfig.maxSelectNum = if (selectionConfig.selectionMode == SelectModeConfig.SINGLE) 1 else maxSelectNum
        return this
    }

    /** @param isDirectReturn Select whether to return directly */
    fun isDirectReturnSingle(isDirectReturn: Boolean): PictureSelectionModel {
        if (isDirectReturn) {
            selectionConfig.isFastSlidingSelect = false
        }
        selectionConfig.isDirectReturnSingle = selectionConfig.selectionMode == SelectModeConfig.SINGLE && isDirectReturn
        return this
    }

    /** Select the maximum video number of files */
    fun setMaxVideoSelectNum(maxVideoSelectNum: Int): PictureSelectionModel {
        selectionConfig.maxVideoSelectNum = if (selectionConfig.chooseMode == SelectMimeType.ofVideo()) 0 else maxVideoSelectNum
        return this
    }

    /** @param isGif Whether to open gif */
    fun isGif(isGif: Boolean): PictureSelectionModel {
        selectionConfig.isGif = isGif
        return this
    }

    /** Select original image to skip compression */
    fun isOriginalSkipCompress(isOriginalSkipCompress: Boolean): PictureSelectionModel {
        selectionConfig.isOriginalSkipCompress = isOriginalSkipCompress
        return this
    }

    /** Start PictureSelector with a result callback */
    fun forResult(call: OnResultCallbackListener<LocalMedia>?) {
        if (!DoubleUtils.isFastDoubleClick()) {
            val activity = selector.getActivity()
                ?: throw NullPointerException("Activity cannot be null")
            if (call == null) {
                throw NullPointerException("OnResultCallbackListener cannot be null")
            }
            selectionConfig.isResultListenerBack = true
            selectionConfig.isActivityResultBack = false
            selectionConfig.onResultCallListener = call
            if (selectionConfig.imageEngine == null && selectionConfig.chooseMode != SelectMimeType.ofAudio()) {
                throw NullPointerException("imageEngine is null,Please implement ImageEngine")
            }
            val intent = Intent(activity, PictureSelectorSupporterActivity::class.java)
            activity.startActivity(intent)
            val windowAnimationStyle = selectionConfig.selectorStyle.windowAnimationStyle!!
            activity.overridePendingTransition(windowAnimationStyle.activityEnterAnimation, R.anim.ps_anim_fade_in)
        }
    }

    /** ActivityResultLauncher PictureSelector */
    fun forResult(launcher: ActivityResultLauncher<Intent>?) {
        if (!DoubleUtils.isFastDoubleClick()) {
            val activity = selector.getActivity()
                ?: throw NullPointerException("Activity cannot be null")
            if (launcher == null) {
                throw NullPointerException("ActivityResultLauncher cannot be null")
            }
            selectionConfig.isResultListenerBack = false
            selectionConfig.isActivityResultBack = true
            if (selectionConfig.imageEngine == null && selectionConfig.chooseMode != SelectMimeType.ofAudio()) {
                throw NullPointerException("imageEngine is null,Please implement ImageEngine")
            }
            val intent = Intent(activity, PictureSelectorSupporterActivity::class.java)
            launcher.launch(intent)
            val windowAnimationStyle = selectionConfig.selectorStyle.windowAnimationStyle!!
            activity.overridePendingTransition(windowAnimationStyle.activityEnterAnimation, R.anim.ps_anim_fade_in)
        }
    }
}
