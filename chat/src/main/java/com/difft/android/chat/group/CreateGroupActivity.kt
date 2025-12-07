package com.difft.android.chat.group

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import org.difft.app.database.getContactorsFromAllTable
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import org.difft.app.database.search
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.chat.common.GroupAvatarUtil
import com.difft.android.chat.common.LetterItem
import com.difft.android.chat.contacts.contactsall.PinyinComparator
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getFirstLetter
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.databinding.ChatActivityCreateGroupBinding
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.CreateGroupReq
import com.difft.android.network.group.GroupAvatarData
import com.difft.android.network.group.GroupAvatarResponse
import com.difft.android.network.group.GroupRepo
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.ImageFileCompressEngine
import com.luck.picture.lib.pictureselector.ImageFileCropEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import com.google.gson.Gson
import com.hi.dhl.binding.viewbind
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.config.SelectModeConfig
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnResultCallbackListener
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import util.ScreenLockUtil
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.File
import java.util.Collections
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
@AndroidEntryPoint
class CreateGroupActivity : BaseActivity() {
    private val TAG: String = "CreateGroupActivity"
    val binding: ChatActivityCreateGroupBinding by viewbind()

    private val memberModels: ArrayList<GroupMemberModel> = arrayListOf()
    private val selectedMap: HashMap<String?, String?> = hashMapOf()

    private val pinyinComparator: PinyinComparator by lazy {
        PinyinComparator()
    }

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

    companion object {
        fun startActivity(activity: Activity, selectedIds: ArrayList<String>?) {
            val intent = Intent(activity, CreateGroupActivity::class.java)
            intent.putExtra("ids", selectedIds)
            activity.startActivity(intent)
        }
    }

    @ChativeHttpClientModule.Chat
    @Inject
    lateinit var httpClient: ChativeHttpClient

    @Inject
    @ChativeHttpClientModule.NoHeader
    lateinit var noHeaderClient: ChativeHttpClient

    private fun createGeneralGroup(groupName: String) {
        val list = selectedMap.mapNotNull { it.key }
        ComposeDialogManager.showWait(this@CreateGroupActivity, "")
        mAvatarFilePath?.let { path ->
            httpClient.httpService
                .fetchAttachmentInfo(SecureSharedPrefsUtil.getBasicAuth())
                .flatMap { response ->
                    if (response.status != 0) {
                        return@flatMap Single.error<String>(NetworkException(response.status, response.reason ?: ""))
                    }

                    val data = FileUtil.readFile(File(path))
                        ?: return@flatMap Single.error<String>(NetworkException(message = "Failed to read avatar file"))

                    val encryptResult = GroupAvatarUtil.encryptGroupAvatar(data)

                    val encryptionKey = encryptResult["encryptionKey"] as? String
                        ?: return@flatMap Single.error<String>(NetworkException(message = "Missing encryption key"))

                    val digest = encryptResult["digest"] as? String
                        ?: return@flatMap Single.error<String>(NetworkException(message = "Missing digest"))

                    val encryptedData = encryptResult["encryptedData"] as? ByteArray
                        ?: return@flatMap Single.error<String>(NetworkException(message = "Missing encrypted data"))

                    val requestBody = RequestBody.create(null, encryptedData)

                    noHeaderClient.httpService.fetchUploadAvatar(
                        response.location.orEmpty(),
                        requestBody
                    ).map {
                        val groupAvatarData = GroupAvatarData(
                            0,
                            data.size.toString(),
                            MediaUtil.getMimeType(this@CreateGroupActivity, Uri.parse(path)),
                            digest,
                            encryptionKey,
                            response.id.toString()
                        )
                        val groupAvatarDataString = Base64.encodeToString(
                            Gson().toJson(groupAvatarData).toByteArray(),
                            Base64.NO_WRAP
                        )
                        Gson().toJson(GroupAvatarResponse(groupAvatarDataString))
                    }
                }
                .compose(RxUtil.getSingleSchedulerComposer())
                .to(RxUtil.autoDispose(this))
                .subscribe({ response ->
                    val request = CreateGroupReq(groupName, list, response)
                    createGroup(request)
                }) {
                    it.printStackTrace()
                    L.e { "[${TAG}]create group avatar error:${it.stackTraceToString()}" }
                    ToastUtil.show(R.string.chat_net_error)
                }
        } ?: run {
            val request = CreateGroupReq(groupName, list)
            createGroup(request)
        }
    }

    private fun createGroup(request: CreateGroupReq) {
        groupRepo.createGroup(request)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                ComposeDialogManager.dismissWait()
                if (it.status == 0) {
                    it.data?.gid?.let { it1 ->
                        GroupChatContentActivity.startActivity(this, it1)
                    }
                    setResult(RESULT_OK)
                    finish()
                } else if (it.status == 10125) {
                    it.data?.strangers?.let { strangers ->
                        val content = strangers.map { s -> s.name }.joinToString(separator = ", ")
                        ToastUtil.show(getString(R.string.group_not_your_friend))
                    }
                }
            }, { error ->
                ComposeDialogManager.dismissWait()
                error.printStackTrace()
                L.e { "[${TAG}]createGeneralGroup - error=${error.stackTraceToString()}" }
                ToastUtil.show(R.string.chat_net_error)
            })
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

        Single.fromCallable { wcdb.getContactorsFromAllTable(selectedIds + globalServices.myId) }
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ contacts ->
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
            }, { error -> error.printStackTrace() })

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
        val contacts = list.filterNot { it.id == globalServices.myId || (it.id.isBotId()) }
        Collections.sort(contacts, pinyinComparator)
        memberModels.clear()
        contacts.forEach {
            val selected = selectedMap.contains(it.id)
            val defaultSelected = selectedIds.find { id -> id == it.id } != null
            val avatarData = it.avatar?.getContactAvatarData()
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
                    showCheckBox = true
                )
            )
        }
        mAdapter.notifyDataSetChanged()

        addMembersToSelectedMemberList()
    }

    private fun searchContacts(key: String) {
        Single.fromCallable {
            wcdb.contactor.search(key)
        }
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                refreshContactsList(it)
            }, { it.printStackTrace() })
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
                e.printStackTrace()
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