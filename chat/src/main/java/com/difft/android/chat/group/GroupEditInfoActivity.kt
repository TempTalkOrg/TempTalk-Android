package com.difft.android.chat.group

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.crypto.GroupCrypto
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.databinding.ChatActivityGroupEditInfoBinding
import com.difft.android.chat.util.ViewUtil
import com.difft.android.network.NetworkException
import com.difft.android.network.group.ChangeGroupSettingsReq
import com.difft.android.network.group.GroupRepo
import com.hi.dhl.binding.viewbind
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.config.SelectModeConfig
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnResultCallbackListener
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.ImageFileCompressEngine
import com.luck.picture.lib.pictureselector.ImageFileCropEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.wcdb
import util.ScreenLockUtil

@AndroidEntryPoint
class GroupEditInfoActivity : BaseActivity() {
    val binding: ChatActivityGroupEditInfoBinding by viewbind()

    private val groupId by lazy { intent.getStringExtra(KEY_GROUP_ID) ?: "" }
    private val originalNameFromIntent by lazy { intent.getStringExtra(KEY_GROUP_NAME) ?: "" }

    /** Name currently persisted on the server (kept in sync after each successful rename). */
    private var currentName: String = ""

    /** Avatar editing is only offered for encrypted groups with a local R_group key. */
    private var avatarEditable: Boolean = false

    /** Name editing is allowed for plain groups and for encrypted groups with a local R_group key. */
    private var nameEditable: Boolean = false

    private var nameEditing: Boolean = false

    @Inject
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var groupUtil: GroupUtil

    @Inject
    lateinit var groupCryptoRepo: GroupCryptoRepo

    @Inject
    lateinit var groupAvatarUploader: GroupAvatarUploader

    private val onPicturePermissionForAvatar = registerPermission {
        onPicturePermissionResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ChatActivityGroupEditInfoBinding.inflate(layoutInflater).root)

        binding.ibBack.setOnClickListener { finish() }

        currentName = originalNameFromIntent.trim()
        binding.editGroupName.setText(currentName)

        loadCurrentAvatar()

        binding.groupAvatar.setOnClickListener {
            if (!avatarEditable) return@setOnClickListener
            onPicturePermissionForAvatar.launchMultiplePermission(PermissionUtil.picturePermissions)
        }

        binding.btnEditName.setOnClickListener {
            if (!nameEditable) return@setOnClickListener
            if (nameEditing) onDoneClicked() else onEditClicked()
        }
    }

    private fun loadCurrentAvatar() {
        lifecycleScope.launch {
            val group = withContext(Dispatchers.IO) {
                wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupId))
            } ?: return@launch

            binding.groupAvatar.setAvatar(group.getDisplayAvatarData())

            val encrypted = (group.groupCryptoMode ?: 0) > 0
            val hasKey = encrypted && withContext(Dispatchers.IO) {
                groupCryptoRepo.getRGroupBytes(groupId) != null
            }
            // Avatar editing is offered only for encrypted groups (with key available).
            avatarEditable = hasKey
            // Name editing: plain groups always; encrypted groups need the key.
            nameEditable = !encrypted || hasKey

            binding.cameraOverlay.visibility = if (avatarEditable) View.VISIBLE else View.GONE
            binding.btnEditName.visibility = if (nameEditable) View.VISIBLE else View.GONE
        }
    }

    // region name edit

    private fun onEditClicked() {
        nameEditing = true
        binding.editGroupName.isEnabled = true
        binding.btnEditName.text = getString(R.string.group_edit_name_done)
        binding.btnEditName.setTextColor(getColor(com.difft.android.base.R.color.t_info))
        ViewUtil.focusAndMoveCursorToEndAndOpenKeyboard(binding.editGroupName)
    }

    private fun onDoneClicked() {
        val newName = binding.editGroupName.text.toString().trim()
        if (newName == currentName) {
            exitNameEditing()
            return
        }
        if (newName.isEmpty()) {
            ToastUtil.show(R.string.group_edit_name_empty)
            return
        }
        if (newName.length > 64) {
            ToastUtil.show(R.string.chat_group_name_too_long)
            return
        }

        ComposeDialogManager.showWait(this, "")
        lifecycleScope.launch {
            try {
                val request = withContext(Dispatchers.IO) { buildNameChangeRequest(newName) }
                val response = groupRepo.changeGroupSettings(groupId, request)
                ComposeDialogManager.dismissWait()
                if (response.status == 0) {
                    currentName = newName
                    exitNameEditing()
                    groupUtil.fetchAndSaveSingleGroupInfo(groupId, true)
                } else {
                    L.w { "[GroupEditInfoActivity] name change failed: status=${response.status}, reason=${response.reason}" }
                    showErrorToast(response.reason)
                }
            } catch (e: CancellationException) {
                ComposeDialogManager.dismissWait()
                throw e
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                L.w { "[GroupEditInfoActivity] name change error: ${e.stackTraceToString()}" }
                showErrorToast((e as? NetworkException)?.errorMsg)
            }
        }
    }

    private fun exitNameEditing() {
        nameEditing = false
        binding.editGroupName.isEnabled = false
        binding.editGroupName.clearFocus()
        binding.btnEditName.text = getString(R.string.group_edit_name_edit)
        binding.btnEditName.setTextColor(getColor(com.difft.android.base.R.color.t_third))
        ViewUtil.hideKeyboard(this, binding.editGroupName)
    }

    private fun buildNameChangeRequest(newName: String): ChangeGroupSettingsReq {
        val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupId))
        val isEncrypted = (group?.groupCryptoMode ?: 0) > 0
        if (!isEncrypted) {
            return ChangeGroupSettingsReq(name = newName)
        }
        val rGroupBytes = groupCryptoRepo.getRGroupBytes(groupId)
            ?: throw IllegalStateException("no_encryption_key")
        val kGroup = GroupCrypto.deriveKGroup(rGroupBytes)
        return ChangeGroupSettingsReq(encryptedName = GroupCrypto.encryptGroupName(kGroup, newName))
    }

    // endregion

    // region avatar edit

    private fun onAvatarPicked(path: String) {
        ComposeDialogManager.showWait(this, "")
        lifecycleScope.launch {
            try {
                val avatarJson = groupAvatarUploader.uploadAndBuildJson(path)
                val request = withContext(Dispatchers.IO) { buildAvatarChangeRequest(avatarJson) }
                val response = groupRepo.changeGroupSettings(groupId, request)
                ComposeDialogManager.dismissWait()
                if (response.status == 0) {
                    binding.groupAvatar.setAvatar(path) // immediate feedback
                    groupUtil.fetchAndSaveSingleGroupInfo(groupId, true)
                } else {
                    L.w { "[GroupEditInfoActivity] avatar change failed: status=${response.status}, reason=${response.reason}" }
                    showErrorToast(response.reason)
                }
            } catch (e: CancellationException) {
                ComposeDialogManager.dismissWait()
                throw e
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                L.w { "[GroupEditInfoActivity] avatar change error: ${e.stackTraceToString()}" }
                showErrorToast((e as? NetworkException)?.errorMsg)
            }
        }
    }

    /**
     * Avatar editing is exposed only to encrypted groups (gated by [avatarEditable]),
     * so this always emits [ChangeGroupSettingsReq.encryptedAvatar].
     */
    private fun buildAvatarChangeRequest(avatarJson: String): ChangeGroupSettingsReq {
        val rGroupBytes = groupCryptoRepo.getRGroupBytes(groupId)
            ?: throw IllegalStateException("no_encryption_key")
        val kGroup = GroupCrypto.deriveKGroup(rGroupBytes)
        return ChangeGroupSettingsReq(encryptedAvatar = GroupCrypto.encryptGroupAvatar(kGroup, avatarJson))
    }

    // endregion

    private fun showErrorToast(reason: String?) {
        if (!reason.isNullOrBlank()) {
            ToastUtil.showLong(reason)
        } else {
            ToastUtil.show(R.string.chat_net_error)
        }
    }

    // region picture selector

    private fun onPicturePermissionResult(state: PermissionUtil.PermissionState) {
        when (state) {
            PermissionUtil.PermissionState.Granted -> openPictureSelector()
            PermissionUtil.PermissionState.Denied -> ToastUtils.showToast(
                this,
                getString(R.string.not_granted_necessary_permissions)
            )
            PermissionUtil.PermissionState.PermanentlyDenied -> ComposeDialogManager.showMessageDialog(
                context = this,
                title = getString(R.string.tip),
                message = getString(R.string.no_permission_picture_tip),
                confirmText = getString(R.string.notification_go_to_settings),
                cancelText = getString(R.string.notification_ignore),
                cancelable = false,
                onConfirm = { PermissionUtil.launchSettings(this) },
                onCancel = {
                    ToastUtils.showToast(this, getString(R.string.not_granted_necessary_permissions))
                },
            )
        }
    }

    private fun openPictureSelector() {
        ScreenLockUtil.temporarilyDisabled = true
        PictureSelector.create(this)
            .openGallery(SelectMimeType.ofImage())
            .setDefaultLanguage(LanguageConfig.ENGLISH)
            .setLanguage(PictureSelectorUtils.getLanguage(this))
            .setSelectorUIStyle(PictureSelectorUtils.getSelectorStyle(this))
            .setImageEngine(GlideEngine.createGlideEngine())
            .setSelectionMode(SelectModeConfig.SINGLE)
            .isDirectReturnSingle(true)
            .setCropEngine(ImageFileCropEngine(this, PictureSelectorUtils.getSelectorStyle(this)))
            .setCompressEngine(ImageFileCompressEngine())
            .forResult(object : OnResultCallbackListener<LocalMedia> {
                override fun onResult(result: ArrayList<LocalMedia>) {
                    val media = result.firstOrNull() ?: return
                    val path = media.compressPath ?: media.realPath ?: return
                    onAvatarPicked(path)
                }

                override fun onCancel() {}
            })
    }

    // endregion
}
