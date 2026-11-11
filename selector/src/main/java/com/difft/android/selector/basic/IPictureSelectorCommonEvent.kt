package com.difft.android.selector.basic

import android.content.Intent
import android.os.Bundle
import com.difft.android.selector.entity.LocalMedia

interface IPictureSelectorCommonEvent {

    fun onCreateLoader()

    fun getResourceId(): Int

    fun onKeyBackFragmentFinish()

    fun onFragmentResume()

    fun handlePermissionDenied(permissionArray: Array<String>)

    fun reStartSavedInstance(savedInstanceState: Bundle?)

    fun handlePermissionSettingResult(permissions: Array<String>)

    fun initAppLanguage()

    fun onRecreateEngine()

    fun onSelectedOnlyCamera()

    fun openSelectedCamera()

    fun openImageCamera()

    fun openVideoCamera()

    fun openSoundRecording()

    






    fun confirmSelect(currentMedia: LocalMedia, isSelected: Boolean): Int

    fun checkWithMimeTypeValidity(
        media: LocalMedia,
        isSelected: Boolean,
        curMimeType: String,
        selectVideoSize: Int,
        fileSize: Long,
        duration: Long
    ): Boolean

    fun checkOnlyMimeTypeValidity(
        media: LocalMedia,
        isSelected: Boolean,
        curMimeType: String,
        existMimeType: String,
        fileSize: Long,
        duration: Long
    ): Boolean

    fun onSelectedChange(isAddRemove: Boolean, currentMedia: LocalMedia)

    fun onFixedSelectedChange(oldLocalMedia: LocalMedia)

    fun dispatchCameraMediaResult(media: LocalMedia)

    fun sendSelectedChangeEvent(isAddRemove: Boolean, currentMedia: LocalMedia)

    fun sendFixedSelectedChangeEvent(currentMedia: LocalMedia)

    fun sendChangeSubSelectPositionEvent(adapterChange: Boolean)

    fun sendSelectedOriginalChangeEvent()

    fun onCheckOriginalChange()

    fun onEditMedia(intent: Intent)

    fun onResultEvent(result: ArrayList<LocalMedia>)

    fun onCrop(result: ArrayList<LocalMedia>)

    fun onOldCrop(result: ArrayList<LocalMedia>)

    fun onCompress(result: ArrayList<LocalMedia>)

    @Deprecated("")
    fun onOldCompress(result: ArrayList<LocalMedia>)

    fun checkCropValidity(): Boolean

    @Deprecated("")
    fun checkOldCropValidity(): Boolean

    fun checkCompressValidity(): Boolean

    @Deprecated("")
    fun checkOldCompressValidity(): Boolean

    fun checkTransformSandboxFile(): Boolean

    @Deprecated("")
    fun checkOldTransformSandboxFile(): Boolean

    fun onApplyPermissionsEvent(event: Int, permissionArray: Array<String>)

    fun onPermissionExplainEvent(isDisplayExplain: Boolean, permissionArray: Array<String>)

    fun onEnterFragment()

    fun onExitFragment()

    fun showLoading()

    fun dismissLoading()
}
