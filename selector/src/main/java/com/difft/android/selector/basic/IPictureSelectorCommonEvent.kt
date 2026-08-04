package com.difft.android.selector.basic

import android.content.Intent
import android.os.Bundle
import com.difft.android.selector.entity.LocalMedia

interface IPictureSelectorCommonEvent {

    /** 创建数据查询器 */
    fun onCreateLoader()

    /** View Layout */
    fun getResourceId(): Int

    /** onKey back fragment or finish */
    fun onKeyBackFragmentFinish()

    /** fragment onResume */
    fun onFragmentResume()

    /** 权限被拒 */
    fun handlePermissionDenied(permissionArray: Array<String>)

    /** onSavedInstance */
    fun reStartSavedInstance(savedInstanceState: Bundle?)

    /** 权限设置结果 */
    fun handlePermissionSettingResult(permissions: Array<String>)

    /** 设置app语言 */
    fun initAppLanguage()

    /** 重新创建所需引擎 */
    fun onRecreateEngine()

    /** 选择拍照或拍视频 */
    fun onSelectedOnlyCamera()

    /** 选择相机类型；拍照、视频、或录音 */
    fun openSelectedCamera()

    /** 拍照 */
    fun openImageCamera()

    /** 拍视频 */
    fun openVideoCamera()

    /** 录音 */
    fun openSoundRecording()

    /**
     * 选择结果
     *
     * @param currentMedia 当前操作对象
     * @param isSelected   选中状态
     * @return 返回当前选择的状态
     */
    fun confirmSelect(currentMedia: LocalMedia, isSelected: Boolean): Int

    /** 验证共选类型模式可选条件 */
    fun checkWithMimeTypeValidity(
        media: LocalMedia,
        isSelected: Boolean,
        curMimeType: String,
        selectVideoSize: Int,
        fileSize: Long,
        duration: Long
    ): Boolean

    /** 验证单一类型模式可选条件 */
    fun checkOnlyMimeTypeValidity(
        media: LocalMedia,
        isSelected: Boolean,
        curMimeType: String,
        existMimeType: String,
        fileSize: Long,
        duration: Long
    ): Boolean

    /** 选择结果数据发生改变 */
    fun onSelectedChange(isAddRemove: Boolean, currentMedia: LocalMedia)

    /** 刷新指定数据 */
    fun onFixedSelectedChange(oldLocalMedia: LocalMedia)

    /** 分发拍照后生成的LocalMedia */
    fun dispatchCameraMediaResult(media: LocalMedia)

    /** 发送选择数据发生变化的通知 */
    fun sendSelectedChangeEvent(isAddRemove: Boolean, currentMedia: LocalMedia)

    /** 刷新指定数据 */
    fun sendFixedSelectedChangeEvent(currentMedia: LocalMedia)

    /**
     * isSelectNumberStyle模式下对选择结果编号进行排序
     */
    fun sendChangeSubSelectPositionEvent(adapterChange: Boolean)

    /** 原图选项发生变化 */
    fun sendSelectedOriginalChangeEvent()

    /** 原图选项发生变化 */
    fun onCheckOriginalChange()

    /** 编辑资源 */
    fun onEditMedia(intent: Intent)

    /** 选择结果回调 */
    fun onResultEvent(result: ArrayList<LocalMedia>)

    /** 裁剪 */
    fun onCrop(result: ArrayList<LocalMedia>)

    /** 裁剪 */
    fun onOldCrop(result: ArrayList<LocalMedia>)

    /** 压缩 */
    fun onCompress(result: ArrayList<LocalMedia>)

    /** 压缩 */
    @Deprecated("")
    fun onOldCompress(result: ArrayList<LocalMedia>)

    /** 验证是否需要裁剪 */
    fun checkCropValidity(): Boolean

    /** 验证是否需要裁剪 */
    @Deprecated("")
    fun checkOldCropValidity(): Boolean

    /** 验证是否需要压缩 */
    fun checkCompressValidity(): Boolean

    /** 验证是否需要压缩 */
    @Deprecated("")
    fun checkOldCompressValidity(): Boolean

    /** 验证是否需要做沙盒转换处理 */
    fun checkTransformSandboxFile(): Boolean

    /** 验证是否需要做沙盒转换处理 */
    @Deprecated("")
    fun checkOldTransformSandboxFile(): Boolean

    /** 权限申请 */
    fun onApplyPermissionsEvent(event: Int, permissionArray: Array<String>)

    /** 权限说明 */
    fun onPermissionExplainEvent(isDisplayExplain: Boolean, permissionArray: Array<String>)

    /** 进入Fragment */
    fun onEnterFragment()

    /** 退出Fragment */
    fun onExitFragment()

    /** show loading */
    fun showLoading()

    /** dismiss loading */
    fun dismissLoading()
}
