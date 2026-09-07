package com.difft.android.chat.common

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMediaSelectionOrOpen
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.R
import com.difft.android.selector.basic.PictureSelector
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.config.SelectModeConfig
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnResultCallbackListener
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.pictureselector.GlideEngine
import com.difft.android.selector.pictureselector.ImageFileCompressEngine
import com.difft.android.selector.pictureselector.ImageFileCropEngine
import com.difft.android.selector.pictureselector.PictureSelectorUtils
import com.difft.android.selector.utils.ToastUtils
import util.ScreenLockUtil

/**
 * Single-image avatar picker: media permission → gallery (single, direct return) → circular UCrop
 * → Luban compress → [onPicked] with the upload path. Plaintext crop temps are dropped right away;
 * the caller deletes the upload temp once the server copy is rendered.
 *
 * Must be constructed as a field of the Activity / Fragment so the permission contract is
 * registered before STARTED.
 */
class AvatarPickLauncher private constructor(
    private val activityProvider: () -> AppCompatActivity,
    private val onPicked: (String) -> Unit,
) {
    private var permission: PermissionUtil.Permission? = null

    fun launch() {
        permission?.launchMediaSelectionOrOpen { openPictureSelector() }
    }

    private fun onPermissionResult(state: PermissionUtil.PermissionState) {
        val activity = activityProvider()
        when (state) {
            PermissionUtil.PermissionState.Granted -> openPictureSelector()
            PermissionUtil.PermissionState.Denied -> ToastUtils.showToast(
                activity,
                activity.getString(R.string.not_granted_necessary_permissions)
            )

            PermissionUtil.PermissionState.PermanentlyDenied -> ComposeDialogManager.showMessageDialog(
                context = activity,
                title = activity.getString(R.string.tip),
                message = activity.getString(R.string.no_permission_picture_tip),
                confirmText = activity.getString(R.string.notification_go_to_settings),
                cancelText = activity.getString(R.string.notification_ignore),
                cancelable = false,
                onConfirm = { PermissionUtil.launchSettings(activity) },
                onCancel = {
                    ToastUtils.showToast(activity, activity.getString(R.string.not_granted_necessary_permissions))
                },
            )
        }
    }

    private fun openPictureSelector() {
        val activity = activityProvider()
        ScreenLockUtil.temporarilyDisabled = true
        PictureSelector.create(activity)
            .openGallery(SelectMimeType.ofImage())
            .setDefaultLanguage(LanguageConfig.ENGLISH)
            .setLanguage(PictureSelectorUtils.getLanguage(activity))
            .setSelectorUIStyle(PictureSelectorUtils.getSelectorStyle(activity))
            .setImageEngine(GlideEngine.createGlideEngine())
            .setSelectionMode(SelectModeConfig.SINGLE)
            .isDirectReturnSingle(true)
            .setCropEngine(ImageFileCropEngine(activity, PictureSelectorUtils.getSelectorStyle(activity)))
            .setCompressEngine(ImageFileCompressEngine())
            .forResult(object : OnResultCallbackListener<LocalMedia> {
                override fun onResult(result: ArrayList<LocalMedia>) {
                    ScreenLockUtil.temporarilyDisabled = false
                    val media = result.firstOrNull() ?: return
                    val path = media.compressPath ?: media.realPath ?: return
                    AvatarPickTempCleaner.deleteCropTemp(media, keepPath = path)
                    onPicked(path)
                }

                override fun onCancel() {
                    ScreenLockUtil.temporarilyDisabled = false
                }
            })
    }

    companion object {
        fun forActivity(activity: AppCompatActivity, onPicked: (String) -> Unit): AvatarPickLauncher =
            AvatarPickLauncher({ activity }, onPicked).also { launcher ->
                launcher.permission = activity.registerPermission(launcher::onPermissionResult)
            }

        fun forFragment(fragment: Fragment, onPicked: (String) -> Unit): AvatarPickLauncher =
            AvatarPickLauncher({ fragment.requireActivity() as AppCompatActivity }, onPicked).also { launcher ->
                launcher.permission = fragment.registerPermission(launcher::onPermissionResult)
            }
    }
}
