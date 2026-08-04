package com.difft.android.chat.contacts.contactsremark

import com.difft.android.base.utils.globalServices

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMediaSelectionOrOpen
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarPickTempCleaner
import com.difft.android.chat.common.upload.ContactAvatarUploader
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.databinding.ChatActivityContactRemarkBinding
import com.difft.android.chat.util.ViewUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.ConversationSetRequestBody
import com.hi.dhl.binding.viewbind
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
import dagger.hilt.android.AndroidEntryPoint
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.getContactorFromAllTable
import org.difft.app.database.models.ContactorModel
import javax.inject.Inject
import util.ScreenLockUtil

@AndroidEntryPoint
class ContactSetRemarkActivity : BaseActivity() {

    companion object {
        private const val BUNDLE_KEY_CONTACT_ID = "BUNDLE_KEY_CONTACT_ID"

        fun startActivity(activity: Activity, contactID: String) {
            val intent = Intent(activity, ContactSetRemarkActivity::class.java)
            intent.contactID = contactID
            activity.startActivity(intent)
        }

        private var Intent.contactID: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_ID)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_ID, value)
            }
    }

    private val mBinding: ChatActivityContactRemarkBinding by viewbind()

    private val contactId: String by lazy { intent.contactID ?: "" }

    @Inject
    lateinit var contactAvatarUploader: ContactAvatarUploader

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    @Inject
    lateinit var wcdb: WCDB

    private var currentContactor: ContactorModel? = null

    /** Snapshot of name text on entering edit mode, restored on cancel. */
    private var nameBeforeEditing: String = ""

    private var isEditingName: Boolean = false

    private val onPicturePermissionForAvatar = registerPermission {
        onPicturePermissionResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
        loadInitialData()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.avatar.setOnClickListener {
            if (isEditingName) return@setOnClickListener
            if (hasRemarkAvatar()) {
                showAvatarActionSheet()
            } else {
                // Open directly when media is already usable (full/partial); else request.
                onPicturePermissionForAvatar.launchMediaSelectionOrOpen { openPictureSelector() }
            }
        }

        mBinding.btnEditName.setOnClickListener {
            if (isEditingName) onDoneClicked() else onEditClicked()
        }
    }

    private fun loadInitialData() {
        lifecycleScope.launch {
            try {
                val contact = withContext(Dispatchers.IO) {
                    wcdb.getContactorFromAllTable(contactId)
                }
                if (contact != null) {
                    currentContactor = contact
                    renderUi(contact)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[ContactSetRemark] loadInitialData error uid=$contactId: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun renderUi(contact: ContactorModel) {
        mBinding.avatar.setAvatar(contact)
        // Show only the effective remark (cache > contactor > gMember). Empty
        // when no remark exists so the placeholder is visible.
        val remark = ContactRemarkCache.getRemark(contact.id)?.takeIf { it.isNotEmpty() }
            ?: contact.remark?.takeIf { it.isNotEmpty() }
            ?: contact.groupMemberContactor?.remark?.takeIf { it.isNotEmpty() }
            ?: ""
        mBinding.editName.setText(remark)
        mBinding.editName.setSelection(mBinding.editName.text?.length ?: 0)
    }

    // region name edit

    private fun onEditClicked() {
        nameBeforeEditing = mBinding.editName.text?.toString().orEmpty()
        isEditingName = true
        mBinding.editName.isEnabled = true
        mBinding.btnEditName.text = getString(R.string.group_edit_name_done)
        mBinding.btnEditName.setTextColor(getColor(com.difft.android.base.R.color.t_info))
        ViewUtil.focusAndMoveCursorToEndAndOpenKeyboard(mBinding.editName)
    }

    private fun onDoneClicked() {
        val newRemark = mBinding.editName.text?.toString().orEmpty().trim()
        val originalRemark = nameBeforeEditing.trim()
        if (newRemark == originalRemark) {
            exitNameEditing(restoreOriginalText = false)
            return
        }

        val encryptedRemark = encryptWithRemarkKey(newRemark)
        ComposeDialogManager.showWait(this, "")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    httpClient.httpService.fetchConversationSet(
                        (globalServices.userManager.getUserData()?.baseAuth ?: ""),
                        ConversationSetRequestBody(
                            conversation = contactId,
                            remark = encryptedRemark
                        )
                    )
                }
                ComposeDialogManager.dismissWait()
                if (result.status == 0) {
                    withContext(Dispatchers.IO) {
                        ContactorUtil.updateRemark(contactId, encryptedRemark)
                    }
                    exitNameEditing(restoreOriginalText = false)
                } else {
                    L.w { "[ContactSetRemark] save name failed status=${result.status} reason=${result.reason} uid=$contactId" }
                    showErrorToast(result.reason)
                }
            } catch (e: CancellationException) {
                ComposeDialogManager.dismissWait()
                throw e
            } catch (e: NetworkException) {
                ComposeDialogManager.dismissWait()
                L.w { "[ContactSetRemark] save name network error uid=$contactId: ${e.stackTraceToString()}" }
                showErrorToast(e.errorMsg)
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                L.e { "[ContactSetRemark] save name failed uid=$contactId: ${e.stackTraceToString()}" }
                ToastUtil.show(getString(R.string.chat_net_error))
            }
        }
    }

    private fun exitNameEditing(restoreOriginalText: Boolean) {
        isEditingName = false
        if (restoreOriginalText) {
            mBinding.editName.setText(nameBeforeEditing)
            mBinding.editName.setSelection(mBinding.editName.text?.length ?: 0)
        }
        mBinding.editName.isEnabled = false
        mBinding.editName.clearFocus()
        mBinding.btnEditName.text = getString(R.string.group_edit_name_edit)
        mBinding.btnEditName.setTextColor(getColor(com.difft.android.base.R.color.t_third))
        ViewUtil.hideKeyboard(this, mBinding.editName)
    }

    // endregion

    // region avatar edit

    private fun onAvatarPicked(filePath: String) {
        ComposeDialogManager.showWait(this, "")
        lifecycleScope.launch {
            try {
                val plainAvatarJson = contactAvatarUploader.uploadAndBuildJson(filePath)
                val encrypted = encryptWithRemarkKey(plainAvatarJson)
                if (encrypted.isEmpty()) {
                    ComposeDialogManager.dismissWait()
                    L.w { "[ContactSetRemark] avatar encrypt produced empty payload uid=$contactId" }
                    ToastUtil.show(getString(R.string.chat_net_error))
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    httpClient.httpService.fetchConversationSet(
                        (globalServices.userManager.getUserData()?.baseAuth ?: ""),
                        ConversationSetRequestBody(
                            conversation = contactId,
                            remarkAvatar = encrypted
                        )
                    )
                }
                ComposeDialogManager.dismissWait()
                if (result.status == 0) {
                    withContext(Dispatchers.IO) {
                        ContactorUtil.updateRemarkAvatar(contactId, encrypted)
                    }
                    refreshContactAndRender()
                    // Rendered from server data now — drop the plaintext compress/crop temp.
                    AvatarPickTempCleaner.deleteUploadedTemp(this@ContactSetRemarkActivity, filePath)
                } else {
                    L.w { "[ContactSetRemark] save avatar failed status=${result.status} reason=${result.reason} uid=$contactId" }
                    showErrorToast(result.reason)
                }
            } catch (e: CancellationException) {
                ComposeDialogManager.dismissWait()
                throw e
            } catch (e: NetworkException) {
                ComposeDialogManager.dismissWait()
                L.w { "[ContactSetRemark] avatar upload network error uid=$contactId: ${e.stackTraceToString()}" }
                showErrorToast(e.errorMsg)
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                L.e { "[ContactSetRemark] avatar upload failed uid=$contactId: ${e.stackTraceToString()}" }
                ToastUtil.show(getString(R.string.chat_net_error))
            }
        }
    }

    /** V1| ciphertext for the server. Empty plain returns empty (server treats it as clear). */
    private fun encryptWithRemarkKey(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = ContactRemarkUtil.encryptRemark(plain.toByteArray(), keyForUid(contactId))
        return if (cipher.isNullOrEmpty()) "" else "V1|$cipher"
    }

    private fun keyForUid(uid: String): ByteArray =
        (uid + uid + uid).padEnd(32, '+').toByteArray().copyOf(32)

    private suspend fun refreshContactAndRender() {
        val refreshed = withContext(Dispatchers.IO) {
            wcdb.getContactorFromAllTable(contactId)
        }
        if (refreshed != null) {
            currentContactor = refreshed
            mBinding.avatar.setAvatar(refreshed)
        }
    }

    // endregion

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
                    ScreenLockUtil.temporarilyDisabled = false
                    val media = result.firstOrNull() ?: return
                    val path = media.compressPath ?: media.realPath ?: return
                    // Upload uses compressPath/realPath; drop the plaintext crop output immediately.
                    AvatarPickTempCleaner.deleteCropTemp(media, keepPath = path)
                    onAvatarPicked(path)
                }

                override fun onCancel() {
                    ScreenLockUtil.temporarilyDisabled = false
                }
            })
    }

    // endregion

    // region avatar action sheet

    private fun hasRemarkAvatar(): Boolean {
        val contactor = currentContactor ?: return false
        val v = ContactRemarkCache.getRemarkAvatar(contactor.id)?.takeIf { it.isNotEmpty() }
            ?: contactor.remarkAvatar?.takeIf { it.isNotEmpty() }
            ?: contactor.groupMemberContactor?.remarkAvatar?.takeIf { it.isNotEmpty() }
        return !v.isNullOrEmpty()
    }

    private fun showAvatarActionSheet() {
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(this) {
            RemarkAvatarActionSheet(
                onChoosePhotos = {
                    dialog?.dismiss()
                    // Open directly when media is already usable (full/partial); else request.
                    onPicturePermissionForAvatar.launchMediaSelectionOrOpen { openPictureSelector() }
                },
                onRestore = {
                    dialog?.dismiss()
                    resetRemarkAvatar()
                },
                onCancel = { dialog?.dismiss() },
            )
        }
    }

    /** Clear the remark avatar via `remarkAvatar = ""` (server treats empty as clear, not null). */
    private fun resetRemarkAvatar() {
        ComposeDialogManager.showWait(this, "")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    httpClient.httpService.fetchConversationSet(
                        (globalServices.userManager.getUserData()?.baseAuth ?: ""),
                        ConversationSetRequestBody(
                            conversation = contactId,
                            remarkAvatar = ""
                        )
                    )
                }
                ComposeDialogManager.dismissWait()
                if (result.status == 0) {
                    withContext(Dispatchers.IO) {
                        ContactorUtil.updateRemarkAvatar(contactId, "")
                    }
                    refreshContactAndRender()
                } else {
                    L.w { "[ContactSetRemark] reset avatar failed status=${result.status} reason=${result.reason} uid=$contactId" }
                    showErrorToast(result.reason)
                }
            } catch (e: CancellationException) {
                ComposeDialogManager.dismissWait()
                throw e
            } catch (e: NetworkException) {
                ComposeDialogManager.dismissWait()
                L.w { "[ContactSetRemark] reset avatar network error uid=$contactId: ${e.stackTraceToString()}" }
                showErrorToast(e.errorMsg)
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                L.e { "[ContactSetRemark] reset avatar failed uid=$contactId: ${e.stackTraceToString()}" }
                ToastUtil.show(getString(R.string.chat_net_error))
            }
        }
    }

    // endregion

    private fun showErrorToast(reason: String?) {
        if (!reason.isNullOrBlank()) {
            ToastUtil.showLong(reason)
        } else {
            ToastUtil.show(R.string.chat_net_error)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isEditingName) {
            exitNameEditing(restoreOriginalText = true)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
