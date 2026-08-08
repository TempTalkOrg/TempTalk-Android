package com.difft.android.selector.basic

import android.annotation.SuppressLint
import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.R
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectLimitType
import com.difft.android.selector.config.SelectModeConfig
import com.difft.android.selector.dialog.RemindDialog
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.manager.SelectedManager
import com.difft.android.selector.utils.ActivityCompatHelper
import com.difft.android.selector.utils.DateUtils
import com.difft.android.selector.utils.PictureFileUtils

/**
 * Selection count / mime / duration / file-size validation extracted from
 * PictureCommonFragment (issue #1077). Reaches base state through [host].
 */
internal class SelectionValidator(private val host: PictureCommonFragment) {

    private val config get() = host.selectorConfig

    fun confirmSelect(currentMedia: LocalMedia, isSelected: Boolean): Int {
        val checkSelectValidity = isCheckSelectValidity(currentMedia, isSelected)
        if (checkSelectValidity != SelectedManager.SUCCESS) {
            return SelectedManager.INVALID
        }
        val selectedResult = config.selectedResult
        val resultCode: Int
        if (isSelected) {
            selectedResult.remove(currentMedia)
            resultCode = SelectedManager.REMOVE
        } else {
            if (config.selectionMode == SelectModeConfig.SINGLE) {
                if (selectedResult.size > 0) {
                    host.sendFixedSelectedChangeEvent(selectedResult[0])
                    selectedResult.clear()
                }
            }
            selectedResult.add(currentMedia)
            currentMedia.num = selectedResult.size
            resultCode = SelectedManager.ADD_SUCCESS
            host.playClickEffect()
        }
        host.sendSelectedChangeEvent(resultCode == SelectedManager.ADD_SUCCESS, currentMedia)
        return resultCode
    }

    /** Validate whether the selection is allowed. */
    fun isCheckSelectValidity(currentMedia: LocalMedia, isSelected: Boolean): Int {
        val curMimeType = currentMedia.mimeType
        val curDuration = currentMedia.duration
        val curFileSize = currentMedia.size
        val selectedResult = config.selectedResult
        if (config.isWithVideoImage) {
            var selectVideoSize = 0
            for (i in selectedResult.indices) {
                val mimeType = selectedResult[i].mimeType
                if (PictureMimeType.isHasVideo(mimeType)) {
                    selectVideoSize++
                }
            }
            if (checkWithMimeTypeValidity(currentMedia, isSelected, curMimeType, selectVideoSize, curFileSize, curDuration)) {
                return SelectedManager.INVALID
            }
        } else {
            if (checkOnlyMimeTypeValidity(currentMedia, isSelected, curMimeType, config.resultFirstMimeType, curFileSize, curDuration)) {
                return SelectedManager.INVALID
            }
        }
        return SelectedManager.SUCCESS
    }

    @SuppressLint("StringFormatInvalid", "StringFormatMatches")
    fun checkWithMimeTypeValidity(
        media: LocalMedia, isSelected: Boolean, curMimeType: String,
        selectVideoSize: Int, fileSize: Long, duration: Long
    ): Boolean {
        if (config.selectMaxFileSize > 0) {
            if (fileSize > config.selectMaxFileSize) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MAX_FILE_SIZE_LIMIT) == true) {
                    return true
                }
                val maxFileSize = PictureFileUtils.formatFileSize(config.selectMaxFileSize)
                showTipsDialog(host.getString(R.string.ps_select_max_size, maxFileSize))
                return true
            }
        }
        if (config.selectMinFileSize > 0) {
            if (fileSize < config.selectMinFileSize) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MIN_FILE_SIZE_LIMIT) == true) {
                    return true
                }
                val minFileSize = PictureFileUtils.formatFileSize(config.selectMinFileSize)
                showTipsDialog(host.getString(R.string.ps_select_min_size, minFileSize))
                return true
            }
        }

        if (PictureMimeType.isHasVideo(curMimeType)) {
            if (config.selectionMode == SelectModeConfig.MULTIPLE) {
                if (config.maxVideoSelectNum <= 0) {
                    if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_NOT_WITH_SELECT_LIMIT) == true) {
                        return true
                    }
                    showTipsDialog(host.getString(R.string.ps_rule))
                    return true
                }

                if (!isSelected && config.selectedResult.size >= config.maxSelectNum) {
                    if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MAX_SELECT_LIMIT) == true) {
                        return true
                    }
                    showTipsDialog(host.getString(R.string.ps_message_max_num, config.maxSelectNum))
                    return true
                }

                if (!isSelected && selectVideoSize >= config.maxVideoSelectNum) {
                    if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MAX_VIDEO_SELECT_LIMIT) == true) {
                        return true
                    }
                    showTipsDialog(getTipsMsg(host.getAppContext(), curMimeType, config.maxVideoSelectNum))
                    return true
                }
            }

            if (!isSelected && config.selectMinDurationSecond > 0 && DateUtils.millisecondToSecond(duration) < config.selectMinDurationSecond) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MIN_VIDEO_SECOND_SELECT_LIMIT) == true) {
                    return true
                }
                showTipsDialog(host.getString(R.string.ps_select_video_min_second, config.selectMinDurationSecond / 1000))
                return true
            }

            if (!isSelected && config.selectMaxDurationSecond > 0 && DateUtils.millisecondToSecond(duration) > config.selectMaxDurationSecond) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MAX_VIDEO_SECOND_SELECT_LIMIT) == true) {
                    return true
                }
                showTipsDialog(host.getString(R.string.ps_select_video_max_second, config.selectMaxDurationSecond / 1000))
                return true
            }
        } else {
            if (config.selectionMode == SelectModeConfig.MULTIPLE) {
                if (!isSelected && config.selectedResult.size >= config.maxSelectNum) {
                    if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MAX_SELECT_LIMIT) == true) {
                        return true
                    }
                    showTipsDialog(host.getString(R.string.ps_message_max_num, config.maxSelectNum))
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("StringFormatInvalid")
    fun checkOnlyMimeTypeValidity(
        media: LocalMedia, isSelected: Boolean, curMimeType: String,
        existMimeType: String, fileSize: Long, duration: Long
    ): Boolean {
        if (!PictureMimeType.isMimeTypeSame(existMimeType, curMimeType)) {
            if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_NOT_WITH_SELECT_LIMIT) == true) {
                return true
            }
            showTipsDialog(host.getString(R.string.ps_rule))
            return true
        }
        if (config.selectMaxFileSize > 0) {
            if (fileSize > config.selectMaxFileSize) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MAX_FILE_SIZE_LIMIT) == true) {
                    return true
                }
                val maxFileSize = PictureFileUtils.formatFileSize(config.selectMaxFileSize)
                showTipsDialog(host.getString(R.string.ps_select_max_size, maxFileSize))
                return true
            }
        }
        if (config.selectMinFileSize > 0) {
            if (fileSize < config.selectMinFileSize) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MIN_FILE_SIZE_LIMIT) == true) {
                    return true
                }
                val minFileSize = PictureFileUtils.formatFileSize(config.selectMinFileSize)
                showTipsDialog(host.getString(R.string.ps_select_min_size, minFileSize))
                return true
            }
        }
        if (PictureMimeType.isHasVideo(curMimeType)) {
            if (config.selectionMode == SelectModeConfig.MULTIPLE) {
                config.maxVideoSelectNum = if (config.maxVideoSelectNum > 0) config.maxVideoSelectNum else config.maxSelectNum
                if (!isSelected && config.selectCount >= config.maxVideoSelectNum) {
                    if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MAX_VIDEO_SELECT_LIMIT) == true) {
                        return true
                    }
                    showTipsDialog(getTipsMsg(host.getAppContext(), curMimeType, config.maxVideoSelectNum))
                    return true
                }
            }
            if (!isSelected && config.selectMinDurationSecond > 0 && DateUtils.millisecondToSecond(duration) < config.selectMinDurationSecond) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MIN_VIDEO_SECOND_SELECT_LIMIT) == true) {
                    return true
                }
                showTipsDialog(host.getString(R.string.ps_select_video_min_second, config.selectMinDurationSecond / 1000))
                return true
            }

            if (!isSelected && config.selectMaxDurationSecond > 0 && DateUtils.millisecondToSecond(duration) > config.selectMaxDurationSecond) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MAX_VIDEO_SECOND_SELECT_LIMIT) == true) {
                    return true
                }
                showTipsDialog(host.getString(R.string.ps_select_video_max_second, config.selectMaxDurationSecond / 1000))
                return true
            }
        } else if (PictureMimeType.isHasAudio(curMimeType)) {
            if (config.selectionMode == SelectModeConfig.MULTIPLE) {
                if (!isSelected && config.selectedResult.size >= config.maxSelectNum) {
                    if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MAX_SELECT_LIMIT) == true) {
                        return true
                    }
                    showTipsDialog(getTipsMsg(host.getAppContext(), curMimeType, config.maxSelectNum))
                    return true
                }
            }

            if (!isSelected && config.selectMinDurationSecond > 0 && DateUtils.millisecondToSecond(duration) < config.selectMinDurationSecond) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MIN_AUDIO_SECOND_SELECT_LIMIT) == true) {
                    return true
                }
                showTipsDialog(host.getString(R.string.ps_select_audio_min_second, config.selectMinDurationSecond / 1000))
                return true
            }
            if (!isSelected && config.selectMaxDurationSecond > 0 && DateUtils.millisecondToSecond(duration) > config.selectMaxDurationSecond) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MAX_AUDIO_SECOND_SELECT_LIMIT) == true) {
                    return true
                }
                showTipsDialog(host.getString(R.string.ps_select_audio_max_second, config.selectMaxDurationSecond / 1000))
                return true
            }
        } else {
            if (config.selectionMode == SelectModeConfig.MULTIPLE) {
                if (!isSelected && config.selectedResult.size >= config.maxSelectNum) {
                    if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), media, config, SelectLimitType.SELECT_MAX_SELECT_LIMIT) == true) {
                        return true
                    }
                    showTipsDialog(getTipsMsg(host.getAppContext(), curMimeType, config.maxSelectNum))
                    return true
                }
            }
        }
        return false
    }

    /** Validate the precondition to complete the selection. */
    fun checkCompleteSelectLimit(): Boolean {
        if (config.selectionMode != SelectModeConfig.MULTIPLE || config.isOnlyCamera) {
            return false
        }
        if (config.isWithVideoImage) {
            val selectedResult = config.selectedResult
            var selectImageSize = 0
            var selectVideoSize = 0
            for (i in selectedResult.indices) {
                val mimeType = selectedResult[i].mimeType
                if (PictureMimeType.isHasVideo(mimeType)) {
                    selectVideoSize++
                } else {
                    selectImageSize++
                }
            }
            if (config.minSelectNum > 0) {
                if (selectImageSize < config.minSelectNum) {
                    if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), null, config, SelectLimitType.SELECT_MIN_SELECT_LIMIT) == true) {
                        return true
                    }
                    showTipsDialog(host.getString(R.string.ps_min_img_num, config.minSelectNum.toString()))
                    return true
                }
            }
            if (config.minVideoSelectNum > 0) {
                if (selectVideoSize < config.minVideoSelectNum) {
                    if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), null, config, SelectLimitType.SELECT_MIN_VIDEO_SELECT_LIMIT) == true) {
                        return true
                    }
                    showTipsDialog(host.getString(R.string.ps_min_video_num, config.minVideoSelectNum.toString()))
                    return true
                }
            }
        } else {
            val mimeType = config.resultFirstMimeType
            if (PictureMimeType.isHasImage(mimeType) && config.minSelectNum > 0 && config.selectCount < config.minSelectNum) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), null, config, SelectLimitType.SELECT_MIN_SELECT_LIMIT) == true) {
                    return true
                }
                showTipsDialog(host.getString(R.string.ps_min_img_num, config.minSelectNum.toString()))
                return true
            }
            if (PictureMimeType.isHasVideo(mimeType) && config.minVideoSelectNum > 0 && config.selectCount < config.minVideoSelectNum) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), null, config, SelectLimitType.SELECT_MIN_VIDEO_SELECT_LIMIT) == true) {
                    return true
                }
                showTipsDialog(host.getString(R.string.ps_min_video_num, config.minVideoSelectNum.toString()))
                return true
            }
            if (PictureMimeType.isHasAudio(mimeType) && config.minAudioSelectNum > 0 && config.selectCount < config.minAudioSelectNum) {
                if (config.onSelectLimitTipsListener?.onSelectLimitTips(host.getAppContext(), null, config, SelectLimitType.SELECT_MIN_AUDIO_SELECT_LIMIT) == true) {
                    return true
                }
                showTipsDialog(host.getString(R.string.ps_min_audio_num, config.minAudioSelectNum.toString()))
                return true
            }
        }
        return false
    }

    /** Tips dialog. */
    fun showTipsDialog(tips: String) {
        if (ActivityCompatHelper.isDestroy(host.activity)) {
            return
        }
        try {
            val dialog = host.tipsDialog
            if (dialog != null && dialog.isShowing) {
                return
            }
            val newDialog = RemindDialog.buildDialog(host.getAppContext(), tips)
            host.tipsDialog = newDialog
            newDialog.show()
        } catch (e: Exception) {
            L.w(e) { "[PictureCommonFragment] showTipsDialog error:" }
        }
    }

    /** Toast text by mime type. */
    @SuppressLint("StringFormatInvalid")
    private fun getTipsMsg(context: Context, mimeType: String?, maxSelectNum: Int): String {
        return if (PictureMimeType.isHasVideo(mimeType)) {
            context.getString(R.string.ps_message_video_max_num, maxSelectNum.toString())
        } else if (PictureMimeType.isHasAudio(mimeType)) {
            context.getString(R.string.ps_message_audio_max_num, maxSelectNum.toString())
        } else {
            context.getString(R.string.ps_message_max_num, maxSelectNum.toString())
        }
    }
}
