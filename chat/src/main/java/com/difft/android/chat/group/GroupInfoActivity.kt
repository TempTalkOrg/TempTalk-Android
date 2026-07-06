package com.difft.android.chat.group

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.GlobalNotificationType
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.flow.catch
import org.difft.app.database.convertToContactorModels
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.messageserialization.db.store.getEffectiveAvatarJson
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsall.sortedByRoleThenPinyin
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getSortLetter
import com.difft.android.chat.databinding.ChatActivityGroupInfoBinding
import com.difft.android.chat.invite.InviteUtils
import com.difft.android.chat.search.SearchMessageActivity
import com.difft.android.chat.setting.ChatArchiveSettingsActivity
import com.difft.android.chat.setting.SaveToPhotosSettingsActivity
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBRoomStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.chat.common.AvatarPickTempCleaner
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.chat.common.GroupAvatarUtil
import com.difft.android.chat.common.GroupAvatarView
import com.difft.android.chat.common.LetterItem
import com.difft.android.chat.crypto.GroupCrypto
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.crypto.GroupKeyDistributor
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.group.AddOrRemoveMembersReq
import com.difft.android.network.group.GroupMemberBinding
import com.difft.android.network.group.GroupRepo
import com.difft.android.network.group.RotateGroupCryptoReq
import com.difft.android.network.group.UpgradeGroupToEncryptedReq
import com.difft.android.network.responses.MuteStatus
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
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
import util.ScreenLockUtil
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.models.GroupModel
import com.difft.android.chat.util.MessageNotificationUtil
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
const val KEY_GROUP_ID = "groupId"
const val KEY_GROUP_NAME = "groupName"
const val EXTRA_SELECTED_MEMBER_IDS = "extra_selected_member_ids"

// Server status for a rotate-crypto CAS conflict (baseGroupCryptoKeyVersion stale).
private const val STATUS_GROUP_CRYPTO_VERSION_CONFLICT = 29

@AndroidEntryPoint
// TODO interim: the reset-crypto dialog/avatar-picker/rotation logic inlined here pushed
// this Activity over the LargeClass threshold. Extract it into a dedicated controller when
// the design-driven reset UI lands (tracked with the interim-UI TODOs in showResetCryptoDialog).
@Suppress("LargeClass")
class GroupInfoActivity : BaseActivity() {

    val binding: ChatActivityGroupInfoBinding by viewbind()
    private var role = GROUP_ROLE_MEMBER
    private var selfGroupInfo: GroupMemberContactorModel? = null
    private var groupInfo: GroupModel? = null
    private var isMemberListExpanded = false

    // Guards against concurrent / double-tap rotation: a second performResetCrypto
    // returns early while one is already running. Cleared in the finally below.
    @Volatile
    private var resetInProgress = false

    private val groupId: String by lazy {
        intent.getStringExtra(KEY_GROUP_ID) ?: ""
    }

    // Staged avatar pick for the interim reset-crypto dialog. Compose-observable so the
    // dialog content recomposes when a pick lands. Reset to null each time the dialog
    // opens (see showResetCryptoDialog). Create-group model: the file is uploaded +
    // encrypted only at confirm time, not at pick time.
    private val resetAvatarPath = mutableStateOf<String?>(null)

    // Must be an Activity field so it's registered before the Activity is STARTED.
    private val onPicturePermissionForAvatar = registerPermission {
        onPicturePermissionForAvatarResult(it)
    }

    @Inject
    lateinit var groupUtil: GroupUtil

    @Inject
    lateinit var inviteUtils: InviteUtils

    @Inject
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var groupCryptoRepo: GroupCryptoRepo

    @Inject
    lateinit var groupKeyDistributor: GroupKeyDistributor

    @Inject
    lateinit var groupAvatarUploader: GroupAvatarUploader

    @Inject
    lateinit var dbRoomStore: DBRoomStore

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var userManager: com.difft.android.base.user.UserManager

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> {
            it.create(For.Group(groupId))
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.ibBack.setOnClickListener { finish() }

        // Subscribe to conversationSet for all config-related UI updates
        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach { conversationSet ->
                // Update mute status
                binding.switch2mute.isChecked = conversationSet.isMuted
                // Update disappearing time
                binding.disappearingTimeText.text = conversationSet.messageExpiry.toArchiveTimeDisplayText()
                // Update save to photos
                binding.saveToPhotosText.text = getSaveToPhotosDisplayText(conversationSet.saveToPhotos)
            }
            .launchIn(lifecycleScope)

        binding.switch2mute.setOnClickListener {
            var muteStatus = MuteStatus.UNMUTED.value
            if (binding.switch2mute.isChecked) {
                muteStatus = MuteStatus.MUTED.value
            }
            chatSettingViewModel.setConversationConfigs(
                activity = this,
                conversation = groupId,
                muteStatus = muteStatus,
            )
        }

        // 异步加载置顶状态
        lifecycleScope.launch(Dispatchers.IO) {
            val isPinned = isPined()
            withContext(Dispatchers.Main) {
                binding.switchStick.isChecked = isPinned
            }
        }
        binding.switchStick.setOnClickListener {
            val newPinned = binding.switchStick.isChecked
            pinChattingRoom(newPinned)
        }


        groupUtil.singleGroupsUpdate
            .onEach {
                if (it.gid == groupId) {
                    if (it.status != 0) {
                        // Group is gone (kicked / left / dismissed / destroyed) — close.
                        finish()
                        return@onEach
                    }
                    groupInfo = it
                    selfGroupInfo = groupInfo?.members?.find { member -> member.id == globalServices.myId }
                    selfGroupInfo?.let { info ->
                        role = info.groupRole ?: GROUP_ROLE_MEMBER
                    }
                    initView()
                }
            }
            .catch { L.w { "[GroupInfoActivity] observe singleGroupsUpdate error: ${it.stackTraceToString()}" } }
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .launchIn(lifecycleScope)

        lifecycleScope.launch {
            ContactorUtil.contactsUpdate.collect {
                updateMemberList(isMemberListExpanded)
            }
        }

        getGroupInfo()
    }

    private fun getGroupInfo() {
        lifecycleScope.launch {
            try {
                val group = groupUtil.getSingleGroupInfo(groupId)
                if (group != null) {
                    groupInfo = group
                    selfGroupInfo = group.members?.find { it.id == globalServices.myId }
                    selfGroupInfo?.let { role = it.groupRole ?: GROUP_ROLE_MEMBER }
                    initView()
                }
            } catch (e: Exception) {
                L.w { "[GroupInfoActivity] getGroupInfo error: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun initView() {
        val title = groupInfo?.name + "(" + groupInfo?.members?.size.toString() + ")"
        binding.title.text = title
        setMembersView()
        setOtherView()
    }

    private val mGroupsAdapter: GroupInfoMemberAdapter by lazy {
        object : GroupInfoMemberAdapter() {
            override fun onItemClick(contact: GroupMemberModel) {
                when (contact.uid) {
                    "+" -> {
                        if (role < GROUP_ROLE_MEMBER || groupInfo?.invitationRule == 2) {
                            gotoMembersActivity(GroupSelectMemberActivity.TYPE_ADD_MEMBER)
                        } else {
                            ToastUtil.show(getString(R.string.group_permission_denied))
                        }
                    }

                    "-" -> {
                        if (role < GROUP_ROLE_MEMBER || groupInfo?.anyoneRemove == true) {
                            gotoMembersActivity(GroupSelectMemberActivity.TYPE_REMOVE_MEMBER)
                        } else {
                            ToastUtil.show(getString(R.string.group_permission_denied))
                        }
                    }

                    else -> {
                        ContactDetailActivity.startActivity(this@GroupInfoActivity, contact.uid, sourceType = FriendSourceType.FROM_GROUP, source = groupId)
                    }
                }
            }
        }
    }

    private fun gotoMembersActivity(type: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val memberIds = groupInfo?.members?.mapNotNull { it.id } ?: emptyList()

            withContext(Dispatchers.Main) {
                val intent = Intent(this@GroupInfoActivity, GroupSelectMemberActivity::class.java).apply {
                    putExtra(GroupSelectMemberActivity.EXTRA_TYPE, type)
                    putStringArrayListExtra(EXTRA_SELECTED_MEMBER_IDS, ArrayList(memberIds))
                    putExtra(GroupSelectMemberActivity.EXTRA_GID, groupId)
                }
                startActivity(intent)
            }
        }
    }

    private val defaultDisplaySize = 13 //默认显示的人数

    private fun setMembersView() {
        binding.edittextSearchInput.setOnClickListener {
            GroupMembersSearchActivity.startActivity(this, groupId)
        }

        binding.rvMembers.apply {
            layoutManager = GridLayoutManager(this@GroupInfoActivity, 5)
            adapter = mGroupsAdapter
            itemAnimator = null
        }

        if ((groupInfo?.members?.size ?: 0) > defaultDisplaySize) {
            binding.relViewAll.visibility = View.VISIBLE
            binding.relViewAll.setOnClickListener {
                isMemberListExpanded = !isMemberListExpanded
                binding.tvViewAll.isVisible = !isMemberListExpanded
                binding.tvCollapse.isVisible = isMemberListExpanded
                updateMemberList(isMemberListExpanded)
            }
        } else {
            binding.relViewAll.visibility = View.GONE
        }

        binding.tvViewAll.isVisible = !isMemberListExpanded
        binding.tvCollapse.isVisible = isMemberListExpanded
        updateMemberList(isMemberListExpanded)
    }

    private fun updateMemberList(showAll: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val contactorModels = groupInfo?.members?.convertToContactorModels()

            contactorModels?.let {
                var members = mutableListOf<GroupMemberModel>()

                it.forEach { member ->
                    val contactAvatar = member.getEffectiveAvatarJson()?.getContactAvatarData()
                    members.add(
                        GroupMemberModel(
                            member.getDisplayNameForUI(),
                            member.id,
                            contactAvatar?.getContactAvatarUrl(),
                            contactAvatar?.encKey,
                            member.getDisplayNameForUI().getSortLetter(),
                            member.groupMemberContactor?.groupRole ?: GROUP_ROLE_MEMBER,
                            letterName = member.getDisplayNameWithoutRemarkForUI()
                        )
                    )
                }

                val sortedMembers = members.sortedByRoleThenPinyin().toMutableList()

                if (!showAll && sortedMembers.size > defaultDisplaySize) {
                    members = sortedMembers.subList(0, defaultDisplaySize)
                } else {
                    members = sortedMembers
                }

                members.add(GroupMemberModel("", "+", "", "", ""))
                members.add(GroupMemberModel("", "-", "", "", ""))

                withContext(Dispatchers.Main) {
                    mGroupsAdapter.submitList(members)
                }
            }
        }
    }

    private fun setOtherView() {
        setupEncryptionRow()

        binding.llSearchChatHistory.setOnClickListener {
            SearchMessageActivity.startActivity(this@GroupInfoActivity, groupId, true, null)
        }

        binding.saveToPhotosContainer.setOnClickListener {
            val target = groupId
                .takeIf { it.isNotBlank() }
                ?.let { For.Group(it) } ?: return@setOnClickListener

            SaveToPhotosSettingsActivity.start(this@GroupInfoActivity, target)
        }

        binding.disappearingTimeContainer.setOnClickListener {
            val target = groupId
                .takeIf { it.isNotBlank() }
                ?.let { For.Group(it) } ?: return@setOnClickListener

            ChatArchiveSettingsActivity.start(this@GroupInfoActivity, target)
        }

        if (messageNotificationUtil.supportConversationNotification()) {
            binding.relNotificationSound.visibility = View.VISIBLE
            binding.relNotificationSound.setOnClickListener {
                messageNotificationUtil.createChannelForConversation(groupId, groupInfo?.name ?: groupId)
                messageNotificationUtil.openMessageNotificationChannelSettings(this, groupId)
            }
        } else {
            binding.relNotificationSound.visibility = View.GONE
        }

        val notification = if (selfGroupInfo?.useGlobal == true) {
            userManager.getUserData()?.globalNotification
        } else {
            selfGroupInfo?.notification
        }
        binding.tvNotification.text = when (notification) {
            GlobalNotificationType.ALL.value -> getString(R.string.notification_all)
            GlobalNotificationType.MENTION.value -> getString(R.string.notification_mention_only)
            GlobalNotificationType.OFF.value -> getString(R.string.notification_off)
            else -> getString(R.string.notification_all)
        }
        binding.relNotification.setOnClickListener {
            GroupNotificationSettingsActivity.start(this, groupId)
        }

//        if (groupInfo?.linkInviteSwitch == true) {
//            binding.groupLinkContainer.visibility = View.VISIBLE
//            binding.groupLinkContainer.setOnClickListener {
//                getInviteCode(selfGroupInfo?.displayName ?: "", groupInfo?.name ?: "", groupInfo?.avatar)
//            }
//        } else {
//            binding.groupLinkContainer.visibility = View.GONE
//        }

        if (role == GROUP_ROLE_OWNER) {
            binding.relGroupManagement.visibility = View.VISIBLE
            binding.relGroupManagement.setOnClickListener {
                GroupManagementActivity.startActivity(this, groupId)
            }

            binding.leaveButton.setText(R.string.group_disband)
            binding.leaveContainer.setOnClickListener {
                ComposeDialogManager.showMessageDialog(
                    context = this,
                    title = getString(R.string.group_disband),
                    message = getString(R.string.group_disband_tips),
                    confirmText = getString(R.string.group_disband_disband),
                    cancelText = getString(R.string.group_leave_cancel),
                    onConfirm = {
                        lifecycleScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    groupRepo.deleteGroup(groupId)
                                }
                                if (result.status == 0) {
                                    finish()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                L.w { "[GroupInfoActivity] deleteGroup error: ${e.stackTraceToString()}" }
                                ToastUtil.showLong(R.string.chat_net_error)
                            }
                        }
                    }
                )
            }
        } else {
            binding.relGroupManagement.visibility = View.GONE
            binding.leaveButton.setText(R.string.group_leave)
            binding.leaveContainer.setOnClickListener {
                ComposeDialogManager.showMessageDialog(
                    context = this,
                    title = getString(R.string.group_leave),
                    message = getString(R.string.group_leave_notice),
                    confirmText = getString(R.string.group_leave_leave),
                    cancelText = getString(R.string.group_leave_cancel),
                    onConfirm = {
                        lifecycleScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    groupRepo.leaveGroup(
                                        groupId,
                                        AddOrRemoveMembersReq(mutableListOf(globalServices.myId))
                                    )
                                }
                                L.i { "response leave group it.status=${result.status}" }
                                if (result.status == 0) {
                                    finish()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                L.i { "leave group error it=${e.message}" }
                                ToastUtil.showLong(R.string.chat_net_error)
                            }
                        }
                    }
                )
            }
        }

        binding.editGroupContainer.setOnClickListener {
            val editIntent = Intent(this, GroupEditInfoActivity::class.java).apply {
                putExtra(KEY_GROUP_ID, groupId)
                putExtra(KEY_GROUP_NAME, groupInfo?.name)
            }
            startActivity(editIntent)
        }
    }

    private fun pinChattingRoom(isPinned: Boolean) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    dbRoomStore.updatePinnedTime(
                        For.Group(groupId),
                        if (isPinned) System.currentTimeMillis() else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[GroupInfoActivity] pinChattingRoom error: ${e.stackTraceToString()}" }
            }
        }
    }

    private suspend fun isPined(): Boolean {
        return dbRoomStore.getPinnedTime(For.Group(groupId)).isPresent
    }

    /**
     * Get the display text for save to photos setting
     * @param saveToPhotos null: follow global, 0: never, 1: always
     */
    private fun getSaveToPhotosDisplayText(saveToPhotos: Int?): String {
        return when (saveToPhotos) {
            1 -> getString(R.string.save_to_photos_always)
            0 -> getString(R.string.save_to_photos_never)
            else -> {
                // Default - show dynamic status based on global setting
                val globalEnabled = userManager.getUserData()?.saveToPhotos == true
                val statusText = if (globalEnabled) {
                    getString(R.string.save_to_photos_on)
                } else {
                    getString(R.string.save_to_photos_off)
                }
                getString(R.string.save_to_photos_default, statusText)
            }
        }
    }

    private fun setupEncryptionRow() {
        val isEncryptedGroup = (groupInfo?.groupCryptoMode ?: 0) > 0

        if (isEncryptedGroup) {
            // Flag gates new encryption actions only; encrypted groups always show their status.
            binding.llEncryptionRow.visibility = View.VISIBLE
            binding.ivEncryptionLock.visibility = View.VISIBLE
            binding.ivEncryptionLock.setColorFilter(getColor(com.difft.android.base.R.color.t_primary))
            binding.tvEncryptionLabel.text = getString(R.string.group_encrypted_label)
            binding.tvEncryptionLabel.setTextColor(getColor(com.difft.android.base.R.color.t_primary))
            binding.ivEncryptionArrow.visibility = View.VISIBLE
            binding.llEncryptionRow.setOnClickListener {
                showEncryptedGroupInfoSheet()
            }
        } else {
            // Plain group: upgrade entry shown only when flag is on and user is owner/admin.
            val canShowUpgrade = globalConfigsManager.isGroupEncryptionEnabled() && role <= GROUP_ROLE_ADMIN
            if (canShowUpgrade) {
                binding.llEncryptionRow.visibility = View.VISIBLE
                binding.ivEncryptionLock.visibility = View.GONE
                binding.tvEncryptionLabel.text = getString(R.string.group_upgrade_to_encrypted)
                binding.tvEncryptionLabel.setTextColor(getColor(com.difft.android.base.R.color.primary))
                binding.ivEncryptionArrow.visibility = View.GONE
                binding.llEncryptionRow.setOnClickListener {
                    showUpgradeToEncryptedSheet()
                }
            } else {
                binding.llEncryptionRow.visibility = View.GONE
            }
        }
    }

    private fun showUpgradeToEncryptedSheet() {
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(this) {
            GroupEncryptionBottomSheet(
                isUpgrade = true,
                onUpgrade = {
                    dialog?.dismiss()
                    performUpgradeToEncrypted()
                },
                onDismiss = { dialog?.dismiss() }
            )
        }
    }

    private fun showEncryptedGroupInfoSheet() {
        // Reset entry gated to owner/admin, behind the group-encryption flag AND
        // its own independent reset flag (hidden when reset flag is off).
        val canReset = role <= GROUP_ROLE_ADMIN &&
                globalConfigsManager.isGroupEncryptionEnabled() &&
                globalConfigsManager.isGroupEncryptionKeyResetEnabled()
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(this) {
            GroupEncryptionBottomSheet(
                isUpgrade = false,
                onUpgrade = { },
                onDismiss = { dialog?.dismiss() },
                canReset = canReset,
                onReset = {
                    // Prefill from the operator's view: WITH key → decrypted current name +
                    // current avatar. WITHOUT key (can't decrypt the placeholders) → empty
                    // name + a freshly generated default avatar, staged so it's both shown
                    // in the dialog and uploaded on confirm.
                    lifecycleScope.launch {
                        val hasKey = withContext(Dispatchers.IO) { groupCryptoRepo.hasKeys(groupId) }
                        resetAvatarPath.value = if (hasKey) {
                            null
                        } else {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    generateDefaultAvatarFile(groupInfo?.members ?: emptyList())
                                }.getOrNull()
                            }
                        }
                        showResetCryptoDialog(prefillName = if (hasKey) groupInfo?.name ?: "" else "")
                    }
                }
            )
        }
    }

    private fun onPicturePermissionForAvatarResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {
                ToastUtils.showToast(this, getString(R.string.not_granted_necessary_permissions))
            }

            PermissionUtil.PermissionState.Granted -> {
                createResetAvatarPictureSelector()
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {
                ComposeDialogManager.showMessageDialog(
                    context = this,
                    title = getString(R.string.tip),
                    message = getString(R.string.no_permission_picture_tip),
                    confirmText = getString(R.string.notification_go_to_settings),
                    cancelText = getString(R.string.notification_ignore),
                    cancelable = false,
                    onConfirm = { PermissionUtil.launchSettings(this) },
                    onCancel = {
                        ToastUtils.showToast(this, getString(R.string.not_granted_necessary_permissions))
                    }
                )
            }
        }
    }

    /**
     * Picker for the interim reset dialog. Clone of [CreateGroupActivity.createPictureSelector].
     * On result we only stage the path into [resetAvatarPath] (Compose-observable) — the
     * upload+encrypt happens later at confirm time (create-group model). We do NOT touch any
     * binding; the dialog content observes the state and recomposes.
     */
    private fun createResetAvatarPictureSelector() {
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
                    if (result.isNotEmpty()) {
                        val localMedia = result[0]
                        resetAvatarPath.value = localMedia.compressPath ?: localMedia.realPath
                        // Upload uses compressPath/realPath; drop the plaintext crop output immediately.
                        AvatarPickTempCleaner.deleteCropTemp(localMedia, keepPath = resetAvatarPath.value)
                    }
                }

                override fun onCancel() {
                }
            })
    }

    /**
     * Interim reset-crypto dialog — replace with the proper design-driven form
     * when the interaction design lands. A centered [ComposeDialogManager]
     * message dialog with an editable avatar (staged pick) + a single name field
     * (prefilled with the currently decrypted group name). cancelable=false so an
     * accidental outside-tap can't dismiss a destructive key-rotation entry mid-edit,
     * and so the dialog survives the picker round-trip.
     */
    // TODO interim UI — replace with proper form when design lands
    private fun showResetCryptoDialog(prefillName: String) {
        // Hoist the name state to the Activity so the imperative onConfirm can read
        // the final value (the @Composable content slot re-runs on each keystroke,
        // so we must not own the state inside it). remember{} keeps the same
        // instance across recompositions of the content slot.
        // prefillName is the decrypted name when the operator holds the key, else ""
        // (no-key case shows an empty field). resetAvatarPath is pre-set by the caller
        // (null when key is held → preview shows current avatar; a generated path
        // otherwise → preview shows the random default).
        val nameState = mutableStateOf(prefillName)
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showMessageDialog(
            context = this,
            title = getString(R.string.group_crypto_reset_title),
            confirmText = getString(R.string.group_crypto_reset_confirm),
            cancelText = getString(R.string.chat_dialog_cancel),
            showCancel = true,
            cancelable = false,
            // Validate before closing: a blank name must keep the dialog open (and preserve the
            // staged avatar pick), so disable auto-dismiss and dismiss manually on valid confirm.
            autoDismiss = false,
            content = { ResetCryptoDialogContent(nameState) },
            onConfirm = {
                // Trim leading/trailing spaces, then block submit on an empty name (keep the
                // dialog open); otherwise close and rotate with the trimmed name.
                val name = nameState.value.trim()
                if (name.isBlank()) {
                    ToastUtil.showLong(R.string.group_crypto_reset_name_hint)
                } else {
                    dialog?.dismiss()
                    performResetCrypto(name)
                }
            },
            onCancel = { dialog?.dismiss() }
        )
    }

    /**
     * Editable content slot for [showResetCryptoDialog]: body line + staged-pick avatar
     * (tap → picker) + a single name field. [nameState] is hoisted to the Activity so the
     * imperative onConfirm can read the final value; remember{} keeps the same instance
     * across recompositions of this slot.
     */
    @Composable
    private fun ResetCryptoDialogContent(nameState: MutableState<String>) {
        val rememberedState = remember { nameState }
        // Observe the staged pick so the avatar recomposes after a pick lands.
        val pickedPath by resetAvatarPath
        Column(modifier = Modifier.fillMaxWidth()) {
            // Body (above the editable fields): what reset does.
            Text(
                text = stringResource(R.string.group_crypto_reset_message),
                fontSize = 14.sp,
                color = colorResource(com.difft.android.base.R.color.t_secondary)
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Editable group avatar: shows the staged pick when present, else the
            // current group avatar (default group icon when data is null). Tapping
            // launches the picker; the staged path is uploaded+encrypted at confirm
            // time. cancelable=false keeps the dialog alive across the picker round-trip.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.clickable {
                        onPicturePermissionForAvatar.launchMultiplePermission(
                            PermissionUtil.picturePermissions
                        )
                    }
                ) {
                    AndroidView(
                        factory = { ctx -> GroupAvatarView(ctx) },
                        update = { v ->
                            val p = pickedPath
                            if (p != null) {
                                v.setAvatar(p)
                            } else {
                                v.setAvatar(groupInfo?.getDisplayAvatarData(), gid = groupId)
                            }
                        },
                        modifier = Modifier.size(64.dp)
                    )
                    // Camera overlay (bottom-end): 24dp circle + centered 12dp camera
                    // icon, replicating chat_activity_group_edit_info.xml. The bg is a
                    // <shape> oval (icon fill + bg_popup stroke), which Compose
                    // painterResource can't load — recreate it with clip/background/border.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(colorResource(com.difft.android.base.R.color.icon))
                            .border(
                                2.dp,
                                colorResource(com.difft.android.base.R.color.bg_popup),
                                CircleShape
                            )
                    ) {
                        Image(
                            painter = painterResource(R.drawable.chat_contact_camera),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(
                                colorResource(com.difft.android.base.R.color.bg_popup)
                            ),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Name input — styled to match the create-group / forward inputs
            // (forward_input_bg). 64-char cap mirrors create-group's maxLength.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        colorResource(com.difft.android.base.R.color.bg3),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                BasicTextField(
                    value = rememberedState.value,
                    onValueChange = { if (it.length <= 64) rememberedState.value = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 18.sp,
                        color = colorResource(com.difft.android.base.R.color.t_primary)
                    ),
                    cursorBrush = SolidColor(colorResource(com.difft.android.base.R.color.primary)),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        if (rememberedState.value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.group_crypto_reset_name_hint),
                                fontSize = 18.sp,
                                color = colorResource(com.difft.android.base.R.color.t_disable)
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }
    }

    /**
     * Plaintext avatar JSON to preserve when no new avatar is picked: decrypt the current
     * [GroupModel.encryptedAvatar] with the stored K_group, so it can be re-encrypted under
     * the fresh key. Returns null when there's no stored key or no encrypted avatar (caller
     * then generates a default). Deliberately does NOT read [GroupModel.avatar]: that field
     * holds the decrypted JSON only when the model came through decryptGroupFieldsIfNeeded,
     * which getSingleGroupInfo(forceUpdate=false) skips — so for an encrypted group it can be
     * the blank/un-decrypted cached value. Must be called from an IO thread.
     */
    private fun preserveCurrentAvatarJson(group: GroupModel): String? {
        val encryptedAvatar = group.encryptedAvatar ?: return null
        val rGroupBytes = groupCryptoRepo.getRGroupBytes(groupId) ?: return null
        return try {
            GroupCrypto.decryptGroupAvatar(GroupCrypto.deriveKGroup(rGroupBytes), encryptedAvatar)
        } catch (e: Exception) {
            L.w { "[GE] preserve avatar decrypt failed gid=$groupId: ${e.message}" }
            null
        }
    }

    private fun performResetCrypto(newName: String) {
        val group = groupInfo ?: return
        // Snapshot the staged pick now so a late dialog dismissal can't race the build.
        val pickedAvatarPath = resetAvatarPath.value
        if (resetInProgress) {
            L.i { "[GE] resetCrypto already in progress gid=$groupId, ignoring re-entry" }
            return
        }
        resetInProgress = true
        ComposeDialogManager.showWait(this, "")

        lifecycleScope.launch {
            try {
                // Resolve the new avatar's plaintext JSON ONCE — it doesn't change between CAS
                // attempts, so the upload happens a single time and a CAS retry never re-uploads;
                // each attempt only re-encrypts this JSON with its own fresh K_group. Resolved
                // INSIDE try so an upload/decrypt failure still hits the catch (dismissWait) and
                // finally (clears resetInProgress) — otherwise the wait spinner would stick and
                // rotation would be permanently blocked.
                //  - picked file → upload + build JSON
                //  - else preserve the current avatar → decrypt encryptedAvatar with the stored key
                //  - else (all-lost / no custom avatar) → generate + upload a default
                val plaintextAvatarJson = withContext(Dispatchers.IO) {
                    when {
                        pickedAvatarPath != null -> groupAvatarUploader.uploadAndBuildJson(pickedAvatarPath)
                        else -> preserveCurrentAvatarJson(group)
                            ?: groupAvatarUploader.uploadAndBuildJson(generateDefaultAvatarFile(group.members ?: emptyList()))
                    }
                }
                // One rotate attempt: force-fetch the latest group info, build the request against
                // that fresh CAS base + current members, and call rotate-crypto. The group-info
                // page can hold a stale cached groupCryptoKeyVersion (getSingleGroupInfo
                // forceUpdate=false returns the DB cache, which may lag the server), so we always
                // refetch — the CAS base must be the server's current version, per contract. On a
                // fetch dedup (a concurrent in-flight fetch returns null) fall back to the freshest
                // PERSISTED group (getSingleGroupInfo reads the cache the concurrent fetch just
                // wrote), not the stale in-memory one. Returns (response, freshRGroup).
                suspend fun attemptRotate() = withContext(Dispatchers.IO) {
                    val freshGroup = groupUtil.fetchAndSaveSingleGroupInfo(groupId)
                        ?: groupUtil.getSingleGroupInfo(groupId)
                        ?: group
                    val (request, rGroup) = buildRotateRequest(freshGroup, newName, plaintextAvatarJson)
                    groupRepo.rotateCrypto(groupId, request) to rGroup
                }
                var result = attemptRotate()
                // CAS conflict: another rotation beat our base between the fetch and the
                // request. Re-fetch + retry exactly once (fresh base); don't loop, to avoid
                // a rotation storm.
                if (result.first.status == STATUS_GROUP_CRYPTO_VERSION_CONFLICT) {
                    L.w { "[GE] rotate version conflict gid=$groupId, retrying once with fresh base" }
                    result = attemptRotate()
                }
                val response = result.first
                val newRGroup = result.second
                ComposeDialogManager.dismissWait()

                if (response.status == 0) {
                    // Robust version: prefer the server-authoritative keyVersion. When the
                    // server omits it, fall back to a monotonic-forward (stored+1) instead
                    // of a flat 1, which would collide with an already-rotated generation.
                    // setRotatedRGroup writes unconditionally regardless of the gate.
                    try {
                        withContext(Dispatchers.IO) {
                            val newVersion = response.data?.groupCryptoKeyVersion
                                ?: (groupCryptoRepo.getKeyVersion(groupId) + 1)
                            groupCryptoRepo.setRotatedRGroup(groupId, newRGroup, newVersion)
                            groupKeyDistributor.distributeToGroup(groupId)
                            groupUtil.fetchAndSaveSingleGroupInfo(groupId, true)
                            L.i { "[GE] Reset crypto key for group $groupId v=$newVersion" }
                        }
                        ToastUtil.show(getString(R.string.group_crypto_reset_success))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Server rotated successfully but the local apply failed: the group
                        // is now rotated server-side while our key state is stranded. Log
                        // distinctly so the partial state is observable; the generic catch
                        // below would mask it as a plain net error.
                        L.e { "[GE] rotate succeeded server-side but local apply failed gid=$groupId: ${e.stackTraceToString()}" }
                        ToastUtil.showLong(R.string.group_crypto_reset_failed)
                    }
                    // Uploaded to the server — drop the plaintext compress/crop temp of the pick
                    // (no-op for the current-avatar or generated-default paths).
                    AvatarPickTempCleaner.deleteUploadedTemp(this@GroupInfoActivity, pickedAvatarPath)
                } else {
                    // Already refetched + retried once above for a CAS conflict; if still
                    // failing, just surface the error (attemptRotate refetches a fresh base
                    // on the next manual attempt too).
                    L.w { "[GE] Reset crypto for group $groupId failed status=${response.status}: ${response.reason}" }
                    val reason = response.reason
                    if (!reason.isNullOrEmpty()) {
                        ToastUtil.showLong(reason)
                    } else {
                        ToastUtil.showLong(R.string.group_crypto_reset_failed)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                L.w { "[GroupInfoActivity] resetCrypto error: ${e.stackTraceToString()}" }
                ToastUtil.showLong(R.string.group_crypto_reset_failed)
            } finally {
                resetInProgress = false
            }
        }
    }

    /**
     * Build the rotate-crypto request: a fresh R_group, new derived keys, the form
     * name re-encrypted, and all current members re-signed. Modeled on
     * [buildUpgradeRequest] — the originator never reads the old key, so this also
     * works when the group is in the all-members-lost state.
     *
     * The avatar's plaintext JSON is resolved by the caller ONCE (uploaded a single time)
     * and passed in, so a CAS retry only re-encrypts it with the new K_group rather than
     * re-uploading. This method just encrypts it; the rotate contract requires
     * encryptedAvatar to be present, so the caller always supplies a non-null JSON.
     *
     * Must be called from IO thread.
     * @param plaintextAvatarJson the avatar JSON to encrypt with the fresh K_group.
     * @return Pair of (request, newRGroup) — newRGroup kept coroutine-local.
     */
    private fun buildRotateRequest(
        group: GroupModel,
        newName: String,
        plaintextAvatarJson: String
    ): Pair<RotateGroupCryptoReq, ByteArray> {
        val newRGroup = GroupCrypto.generateRGroup()
        val kGroup = GroupCrypto.deriveKGroup(newRGroup)
        val skBind = GroupCrypto.deriveSkBind(newRGroup)
        val pkBind = GroupCrypto.derivePkBind(newRGroup)

        val name = newName.ifBlank { getString(R.string.new_group) }
        val encryptedName = GroupCrypto.encryptGroupName(kGroup, name)

        val members = group.members ?: emptyList()
        val encryptedAvatar = GroupCrypto.encryptGroupAvatar(kGroup, plaintextAvatarJson)

        val memberBindings = members.mapNotNull { member ->
            member.id?.let { uid ->
                GroupMemberBinding(uid, GroupCrypto.signUid(skBind, uid))
            }
        }
        val pkBindBase64 = GroupCrypto.pkBindToSpkiBase64(pkBind)

        val request = RotateGroupCryptoReq(
            encryptedName = encryptedName,
            encryptedAvatar = encryptedAvatar,
            groupMemberVerifyPublicKey = pkBindBase64,
            // CAS base: the current version we last saw from the server. un-rotated default 0.
            // TODO 联调: confirm server's un-rotated groupCryptoKeyVersion value
            baseGroupCryptoKeyVersion = group.groupCryptoKeyVersion ?: 0,
            memberBindings = memberBindings
        )
        return request to newRGroup
    }

    /**
     * Generate a default group letter avatar PNG from the current members and return
     * its local file path. Mirrors [CreateGroupActivity.generateAvatar]'s mechanism
     * (LetterItems from member display names, color keyed by member id).
     * Must be called from IO thread.
     */
    private fun generateDefaultAvatarFile(members: List<GroupMemberContactorModel>): String {
        val letterItems = members
            .filter { !it.id.isNullOrEmpty() && !it.displayName.isNullOrEmpty() }
            .take(6)
            .map { member ->
                val letter = ContactorUtil.getFirstLetter(member.displayName).first()
                val color = AvatarUtil.getBgColorResId(member.id!!)
                LetterItem(letter.uppercaseChar(), color)
            }
        val usedColors = letterItems.map { it.color }.toSet()
        val availableColors = AvatarUtil.colors.filterNot { usedColors.contains(it) }
        val backgroundColor = availableColors.randomOrNull()
            ?: getColor(com.difft.android.base.R.color.primary)

        return GroupAvatarUtil.generateAvatarFile(letterItems, backgroundColor)
            ?: throw java.io.IOException("default_avatar_generate_failed")
    }

    private fun performUpgradeToEncrypted() {
        val group = groupInfo ?: return
        ComposeDialogManager.showWait(this, "")

        lifecycleScope.launch {
            try {
                val (request, rGroup) = withContext(Dispatchers.IO) {
                    buildUpgradeRequest(group)
                }
                val response = withContext(Dispatchers.IO) {
                    groupRepo.upgradeToEncrypted(groupId, request)
                }
                ComposeDialogManager.dismissWait()

                if (response.status == 0) {
                    withContext(Dispatchers.IO) {
                        groupCryptoRepo.saveRGroupIfNeeded(groupId, rGroup)
                        groupKeyDistributor.distributeToGroup(groupId)
                        groupUtil.fetchAndSaveSingleGroupInfo(groupId, true)
                    }
                    L.i { "[GE] Upgraded group $groupId to encrypted" }
                    ToastUtil.show(getString(R.string.operation_successful))
                } else {
                    L.w { "[GE] Upgrade group $groupId failed: ${response.reason}" }
                    response.reason?.let { ToastUtil.showLong(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                L.w { "[GroupInfoActivity] upgradeToEncrypted error: ${e.stackTraceToString()}" }
                ToastUtil.showLong(R.string.chat_net_error)
            }
        }
    }

    /**
     * Build the upgrade request with encrypted fields and member bindings.
     * Must be called from IO thread.
     * @return Pair of (request, rGroup) — rGroup kept as coroutine-local to avoid Activity state loss.
     */
    private fun buildUpgradeRequest(group: GroupModel): Pair<UpgradeGroupToEncryptedReq, ByteArray> {
        val rGroup = GroupCrypto.generateRGroup()
        val kGroup = GroupCrypto.deriveKGroup(rGroup)
        val skBind = GroupCrypto.deriveSkBind(rGroup)
        val pkBind = GroupCrypto.derivePkBind(rGroup)

        val encryptedName = GroupCrypto.encryptGroupName(kGroup, group.name ?: "")
        val encryptedAvatar = group.avatar?.let { GroupCrypto.encryptGroupAvatar(kGroup, it) }

        val members = group.members ?: emptyList()
        val memberBindings = members.mapNotNull { member ->
            member.id?.let { uid ->
                GroupMemberBinding(uid, GroupCrypto.signUid(skBind, uid))
            }
        }
        val pkBindBase64 = GroupCrypto.pkBindToSpkiBase64(pkBind)

        val request = UpgradeGroupToEncryptedReq(
            encryptedName = encryptedName,
            encryptedAvatar = encryptedAvatar,
            groupMemberVerifyPublicKey = pkBindBase64,
            memberBindings = memberBindings
        )
        return request to rGroup
    }
}