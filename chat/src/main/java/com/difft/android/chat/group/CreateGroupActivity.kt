package com.difft.android.chat.group

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import kotlin.coroutines.cancellation.CancellationException
import org.difft.app.database.getContactorsFromAllTable
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.messageserialization.db.store.getEffectiveAvatarJson
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import org.difft.app.database.search
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.chat.common.GroupAvatarUtil
import com.difft.android.chat.common.LetterItem
import com.difft.android.chat.contacts.contactsall.sortedByPinyin
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getFirstLetter
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.databinding.ChatActivityCreateGroupBinding
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.network.NetworkException
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.chat.crypto.GroupCrypto
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.crypto.GroupKeyDistributor
import com.difft.android.network.group.CreateGroupReq
import com.difft.android.network.group.GroupMemberBinding
import com.difft.android.network.group.GroupRepo
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.ImageFileCompressEngine
import com.luck.picture.lib.pictureselector.ImageFileCropEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import com.hi.dhl.binding.viewbind
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.config.SelectModeConfig
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnResultCallbackListener
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import util.ScreenLockUtil
import java.io.File
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
@AndroidEntryPoint
class CreateGroupActivity : BaseActivity() {
    private val TAG: String = "CreateGroupActivity"
    val binding: ChatActivityCreateGroupBinding by viewbind()

    private val memberModels: ArrayList<GroupMemberModel> = arrayListOf()
    private val selectedMap: HashMap<String?, String?> = hashMapOf()

    private val selectedIds: ArrayList<String> by lazy {
        intent.getStringArrayListExtra("ids") ?: arrayListOf()
    }

    @Inject
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    @Inject
    lateinit var wcdb: WCDB

    @Inject
    lateinit var groupUtil: GroupUtil

    @Inject
    lateinit var groupCryptoRepo: GroupCryptoRepo

    @Inject
    lateinit var groupKeyDistributor: GroupKeyDistributor

    @Inject
    lateinit var groupAvatarUploader: GroupAvatarUploader

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    companion object {
        fun startActivity(activity: Activity, selectedIds: ArrayList<String>?) {
            val intent = Intent(activity, CreateGroupActivity::class.java)
            intent.putExtra("ids", selectedIds)
            activity.startActivity(intent)
        }
    }

    private fun createGeneralGroup(groupName: String) {
        val list = selectedMap.mapNotNull { it.key }
        val encryptionEnabled = globalConfigsManager.isGroupEncryptionEnabled()
        ComposeDialogManager.showWait(this@CreateGroupActivity, "")
        mAvatarFilePath?.let { path ->
            lifecycleScope.launch {
                try {
                    val avatarJson = groupAvatarUploader.uploadAndBuildJson(path)
                    val (request, rGroup) = buildGroupRequest(groupName, list, avatarJson, encryptionEnabled)
                    createGroup(request, rGroup)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ComposeDialogManager.dismissWait()
                    L.e { "[${TAG}]create group avatar error:${e.stackTraceToString()}" }
                    showErrorToast((e as? NetworkException)?.errorMsg)
                }
            }
        } ?: run {
            val (request, rGroup) = buildGroupRequest(groupName, list, null, encryptionEnabled)
            createGroup(request, rGroup)
        }
    }

    /**
     * Build a group creation request, either plain or encrypted depending on the feature flag.
     * @return Pair of (request, rGroup?) — rGroup is null for plain groups.
     */
    private fun buildGroupRequest(
        groupName: String,
        members: List<String>,
        avatarJson: String?,
        encryptionEnabled: Boolean
    ): Pair<CreateGroupReq, ByteArray?> {
        return if (encryptionEnabled) {
            val (request, rGroup) = buildEncryptedGroupRequest(groupName, members, avatarJson)
            request to rGroup
        } else {
            CreateGroupReq(
                name = groupName,
                numbers = members,
                avatar = avatarJson
            ) to null
        }
    }

    /**
     * Build an encrypted group creation request.
     * @return Pair of (request, rGroup) — rGroup kept as coroutine-local to avoid Activity state loss.
     */
    private fun buildEncryptedGroupRequest(
        groupName: String,
        members: List<String>,
        avatarJson: String?
    ): Pair<CreateGroupReq, ByteArray> {
        val rGroup = GroupCrypto.generateRGroup()
        val kGroup = GroupCrypto.deriveKGroup(rGroup)
        val skBind = GroupCrypto.deriveSkBind(rGroup)
        val pkBind = GroupCrypto.derivePkBind(rGroup)

        val encryptedName = GroupCrypto.encryptGroupName(kGroup, groupName)
        val encryptedAvatar = avatarJson?.let { GroupCrypto.encryptGroupAvatar(kGroup, it) }

        // Sign all members including self
        val allMembers = (members + globalServices.myId).distinct()
        val memberBindings = allMembers.map { uid ->
            GroupMemberBinding(uid, GroupCrypto.signUid(skBind, uid))
        }
        val pkBindBase64 = GroupCrypto.pkBindToSpkiBase64(pkBind)

        val request = CreateGroupReq(
            name = null,
            numbers = members,
            avatar = null,
            groupCryptoMode = 1,
            encryptedName = encryptedName,
            encryptedAvatar = encryptedAvatar,
            groupMemberVerifyPublicKey = pkBindBase64,
            memberBindings = memberBindings
        )
        return request to rGroup
    }

    private fun createGroup(request: CreateGroupReq, rGroup: ByteArray?) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    groupRepo.createGroup(request)
                }
                ComposeDialogManager.dismissWait()
                if (result.status == 0) {
                    result.data?.gid?.let { gid ->
                        withContext(Dispatchers.IO) {
                            if (rGroup != null) {
                                groupCryptoRepo.saveRGroupIfNeeded(gid, rGroup)
                            }
                            groupUtil.fetchAndSaveSingleGroupInfo(gid, true)
                            if (rGroup != null) {
                                groupKeyDistributor.distributeToGroup(gid)
                            }
                        }
                        L.i { "[GE] Created group $gid encrypted=${rGroup != null}" }
                        GroupChatContentActivity.startActivity(this@CreateGroupActivity, gid)
                    }
                    setResult(RESULT_OK)
                    finish()
                } else if (result.status == 10125) {
                    result.data?.strangers?.let { strangers ->
                        val content = strangers.map { s -> s.name }.joinToString(separator = ", ")
                        ToastUtil.show(getString(R.string.group_not_your_friend))
                    }
                } else {
                    L.w { "[${TAG}]createGroup failed: status=${result.status}, reason=${result.reason}" }
                    showErrorToast(result.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                L.e { "[${TAG}]createGeneralGroup - error=${e.stackTraceToString()}" }
                showErrorToast((e as? NetworkException)?.errorMsg)
            }
        }
    }

    private fun showErrorToast(reason: String?) {
        if (!reason.isNullOrBlank()) {
            ToastUtil.showLong(reason)
        } else {
            ToastUtil.show(R.string.chat_net_error)
        }
    }

    private val onPicturePermissionForAvatar = registerPermission {
        onPicturePermissionForAvatarResult(it)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ChatActivityCreateGroupBinding.inflate(layoutInflater).root)

        binding.ibBack.setOnClickListener {
            finish()
        }

        binding.recyclerviewContacts.apply {
            this.adapter = mAdapter
            mAdapter.submitList(memberModels)
            this.layoutManager = LinearLayoutManager(this@CreateGroupActivity)
            itemAnimator = null
        }


        binding.createButton.setOnClickListener {
            val groupName = binding.groupNameEdit.text.toString().trim()
                .takeIf { it.isNotEmpty() } ?: getString(R.string.new_group)

            createGeneralGroup(groupName)
        }

        lifecycleScope.launch {
            try {
                val contacts = withContext(Dispatchers.IO) {
                    wcdb.getContactorsFromAllTable(selectedIds + globalServices.myId)
                }
                contacts.forEach {
                    selectedMap[it.id] = it.getDisplayNameWithoutRemarkForUI()
                }
                if (selectedIds.isNotEmpty()) {
                    val defaultName = buildString {
                        contacts.sortedBy { if (it.id == globalServices.myId) 0 else 1 }
                            .forEachIndexed { index, contact ->
                                append(contact.getDisplayNameWithoutRemarkForUI())
                                if (index != contacts.lastIndex) append(", ")
                            }
                    }
                    binding.groupNameEdit.setText(defaultName)
                    binding.groupNameEdit.setSelection(defaultName.length)
                }

                searchContacts("")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[CreateGroupActivity] loadContacts error: ${e.stackTraceToString()}" }
            }
        }

        binding.edittextSearchInput.addTextChangedListener {
            val etContent = binding.edittextSearchInput.text.toString().trim()
            searchContacts(etContent)
            resetButtonClear(etContent)
        }

        binding.buttonClear.setOnClickListener {
            binding.edittextSearchInput.text = null
        }

        resetButtonClear(null)

        binding.groupAvatar.setOnClickListener {
            onPicturePermissionForAvatar.launchMultiplePermission(PermissionUtil.picturePermissions)
        }
    }

    private fun resetButtonClear(etContent: String?) {
        binding.buttonClear.animate().apply {
            cancel()
            val toAlpha = if (!TextUtils.isEmpty(etContent)) 1.0f else 0f
            alpha(toAlpha)
        }
    }

    private val mAdapter: GroupMembersAdapter by lazy {
        object : GroupMembersAdapter(GroupSelectMemberActivity.TYPE_ADD_MEMBER) {
            override fun onMemberClicked(model: GroupMemberModel, position: Int) {
                L.d { "onMemberClicked: $model" }
            }

            override fun onCheckBoxClicked(model: GroupMemberModel, position: Int) {
                model.isSelected = !model.isSelected
                addMembersToSelectedMemberList()
                notifyItemChanged(position)

                if (!TextUtils.isEmpty(binding.edittextSearchInput.text)) {
                    binding.buttonClear.performClick()
                }
            }
        }
    }

    private fun addMembersToSelectedMemberList() {
        memberModels.forEach {
            if (it.isSelected) {
                selectedMap[it.uid] = it.name
            } else {
                selectedMap.remove(it.uid)
            }
        }
        if (selectedMap.size >= 2) {
            generateAvatar()
        } else {
            deleteCurrentRandomAvatarFile()
            mAvatarFilePath = null
            binding.groupAvatar.setAvatar(null)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshContactsList(list: List<ContactorModel>) {
        val sortedContacts = list.filterNot { it.id == globalServices.myId || (it.id.isBotId()) }
            .sortedByPinyin()
        memberModels.clear()
        sortedContacts.forEach {
            val selected = selectedMap.contains(it.id)
            val defaultSelected = selectedIds.find { id -> id == it.id } != null
            val avatarData = it.getEffectiveAvatarJson()?.getContactAvatarData()
            memberModels.add(
                GroupMemberModel(
                    it.getDisplayNameForUI(),
                    it.id,
                    avatarData?.getContactAvatarUrl(),
                    avatarData?.encKey,
                    it.getDisplayNameForUI().getFirstLetter(),
                    0,
                    isSelected = selected,
                    checkBoxEnable = !defaultSelected,
                    showCheckBox = true,
                    letterName = it.getDisplayNameWithoutRemarkForUI()
                )
            )
        }
        mAdapter.notifyDataSetChanged()

        addMembersToSelectedMemberList()
    }

    private fun searchContacts(key: String) {
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    wcdb.contactor.search(key)
                }
                refreshContactsList(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[CreateGroupActivity] searchContacts error: ${e.stackTraceToString()}" }
            }
        }
    }


    private fun onPicturePermissionForAvatarResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {
                L.d { "onPicturePermissionForAvatarResult: Denied" }
                ToastUtils.showToast(this, getString(com.difft.android.chat.R.string.not_granted_necessary_permissions))
            }

            PermissionUtil.PermissionState.Granted -> {
                L.d { "onPicturePermissionForAvatarResult: Granted" }
                createPictureSelector()
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {
                L.d { "onPicturePermissionForAvatarResult: PermanentlyDenied" }
                ComposeDialogManager.showMessageDialog(
                    context = this,
                    title = getString(com.difft.android.chat.R.string.tip),
                    message = getString(com.difft.android.chat.R.string.no_permission_picture_tip),
                    confirmText = getString(com.difft.android.chat.R.string.notification_go_to_settings),
                    cancelText = getString(com.difft.android.chat.R.string.notification_ignore),
                    cancelable = false,
                    onConfirm = {
                        PermissionUtil.launchSettings(this)
                    },
                    onCancel = {
                        ToastUtils.showToast(
                            this, getString(com.difft.android.chat.R.string.not_granted_necessary_permissions)
                        )
                    }
                )
            }
        }
    }

    var mAvatarFilePath: String? = null

    private fun createPictureSelector() {
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
                        mAvatarFilePath = localMedia.compressPath ?: localMedia.realPath
                        binding.groupAvatar.setAvatar(mAvatarFilePath ?: "")
                    }
                }

                override fun onCancel() {
                }
            })
    }

    private fun generateAvatar() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                deleteCurrentRandomAvatarFile()
                val letterItems = selectedMap
                    .entries
                    .filter { !it.key.isNullOrEmpty() && !it.value.isNullOrEmpty() }
                    .take(6)
                    .map { entry ->
                        val letter = ContactorUtil.getFirstLetter(entry.value).first()
                        val color = AvatarUtil.getBgColorResId(entry.key!!)
                        LetterItem(letter.uppercaseChar(), color)
                    }

                val usedColors = letterItems.map { it.color }.toSet()
                val availableColors = AvatarUtil.colors.filterNot { usedColors.contains(it) }
                val backgroundColor = availableColors.randomOrNull() ?: getColor(com.difft.android.base.R.color.primary)

                mAvatarFilePath = GroupAvatarUtil.generateAvatarFile(letterItems, backgroundColor)

                withContext(Dispatchers.Main) {
                    mAvatarFilePath?.let {
                        binding.groupAvatar.setAvatar(it)
                    }
                }
            } catch (e: Exception) {
                L.e { "[${TAG}]generateAvatar failed: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun deleteCurrentRandomAvatarFile() {
        mAvatarFilePath?.let { path ->
            File(path).takeIf { it.exists() }?.delete()
        }
    }

    override fun onDestroy() {
        deleteCurrentRandomAvatarFile()
        super.onDestroy()
    }
}