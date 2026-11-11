package com.difft.android.chat.group

import com.difft.android.base.ui.sidechrome.padForSideChrome
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.SettingsIdiomPage
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.difft.android.chat.common.AvatarPickLauncher
import com.difft.android.chat.common.compose.IdentityMeta
import com.difft.android.chat.group.mvi.GroupInfoHeaderContract
import com.difft.android.chat.group.mvi.GroupInfoHeaderViewModel
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.log.lumberjack.L
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.flow.catch
import org.difft.app.database.convertToContactorModels
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.messageserialization.db.store.getEffectiveAvatarJson
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.EdgeToEdgeUtils
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.isInDetailStack
import com.difft.android.base.utils.pane.waitWhileVisibleTop
import org.difft.app.database.members
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsall.sortedByRoleThenPinyin
import com.difft.android.chat.contacts.contactsdetail.showContactCard
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getSortLetter
import com.difft.android.chat.databinding.ChatActivityGroupInfoBinding
import com.difft.android.chat.focus.FocusPinCoordinator
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.focus.settings.GroupFocusSettingsBinder
import com.difft.android.chat.focus.settings.GroupFocusSettingsContract
import com.difft.android.chat.focus.settings.GroupFocusSettingsViewModel
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.chat.translate.translateEntryValue
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBRoomStore
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.crypto.GroupKeyDistributor
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.group.GroupRepo
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import com.difft.android.base.utils.sampleAfterFirst
import org.difft.app.database.WCDBUpdateService
import kotlinx.coroutines.withContext
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.models.GroupModel
import com.difft.android.chat.util.MessageNotificationUtil
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
const val KEY_GROUP_ID = "groupId"





@AndroidEntryPoint
class GroupInfoFragment : Fragment(), SettingsIdiomPage {
    
    private val pageWait = PageWait(this)


    private var _binding: ChatActivityGroupInfoBinding? = null
    val binding get() = _binding!!
    internal var role = GROUP_ROLE_MEMBER
    internal var selfGroupInfo: GroupMemberContactorModel? = null
    internal var groupInfo: GroupModel? = null
    private var isMemberListExpanded = false

    private val headerViewModel: GroupInfoHeaderViewModel by viewModels()

    
    
    private val headerBinder = GroupInfoHeaderBinder(this, { binding }, { headerViewModel }, { groupId }) {
        detailNavigation().pop(this)
    }

    
    private val cryptoWait = PageWait(this)

    internal val groupId: String by lazy {
        requireArguments().getString(KEY_GROUP_ID) ?: ""
    }

    
    
    
    
    internal val resetAvatarPath = mutableStateOf<String?>(null)

    
    
    internal val resetAvatarPicker = AvatarPickLauncher.forFragment(this) { resetAvatarPath.value = it }

    @Inject
    lateinit var groupUtil: GroupUtil

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
    lateinit var focusPinCoordinator: FocusPinCoordinator

    @Inject
    internal lateinit var focusRepository: GroupFocusRepository

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

    
    internal val viewScope get() = viewLifecycleOwner.lifecycleScope

    private val focusSettingsViewModel: GroupFocusSettingsViewModel by viewModels()

    
    private val focusSettingsBinder = GroupFocusSettingsBinder(
        { requireActivity() as AppCompatActivity },
        { this },
        { viewLifecycleOwner },
        { binding.composeNotificationSection },
        { groupId },
        { focusSettingsViewModel },
        { chatSettingViewModel },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ChatActivityGroupInfoBinding.inflate(inflater, container, false)
        
        return TapPeekFrameLayout(inflater.context).apply {
            addView(binding.root)
            onPeek = headerBinder::onDispatchTouchEvent
        }
    }

    



    private fun observeCryptoOperation() {
        waitWhileVisibleTop(GroupCryptoOperations.running.map { groupId in it }, cryptoWait)
    }

    override fun onDestroyView() {
        cryptoWait.dismiss()
        _binding = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        
        if (isInDetailStack()) EdgeToEdgeUtils.applyBottomInsetAsPadding(view)
        padForSideChrome(view)

        observeCryptoOperation()
        headerBinder.setup()
        headerBinder.observe()

        
        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach { conversationSet ->
                
                binding.disappearingTimeText.text = conversationSet.messageExpiry.toArchiveTimeDisplayText()
                
                binding.saveToPhotosText.text = getSaveToPhotosDisplayText(conversationSet.saveToPhotos)
            }
            .launchIn(viewScope)

        
        if (chatSettingViewModel.translateSupported) {
            chatSettingViewModel.translateSetting
                .onEach { binding.translateText.text = translateEntryValue(requireContext(), it) }
                .launchIn(viewScope)
        } else {
            binding.translateContainer.visibility = View.GONE
        }

        focusSettingsBinder.setup()
        
        focusSettingsViewModel.state
            .map { it.featureEnabled }
            .distinctUntilChanged()
            .onEach { placeSearchCard(afterSettings = it) }
            .launchIn(viewScope)

        
        
        WCDBUpdateService.roomTableUpdated
            .onStart { emit(Unit) }
            .sampleAfterFirst(300)
            .map { withContext(Dispatchers.IO) { isPined() } }
            .onEach { binding.switchStick.isChecked = it }
            .launchIn(viewScope)
        
        binding.switchStick.setOnToggleRequestListener { view, requested ->
            view.guardWhile(pinChattingRoom(requested))
        }


        groupUtil.singleGroupsUpdate
            .onEach {
                if (it.gid == groupId) {
                    if (it.status != 0) {
                        
                        
                        detailNavigation().closeConversation(this@GroupInfoFragment)
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
            .catch { L.w { "[GroupInfo] observe singleGroupsUpdate error: ${it.stackTraceToString()}" } }
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .launchIn(viewScope)

        viewScope.launch {
            ContactorUtil.contactsUpdate.collect {
                updateMemberList(isMemberListExpanded)
            }
        }

        getGroupInfo()
    }

    private fun getGroupInfo() {
        viewScope.launch {
            try {
                val group = groupUtil.getSingleGroupInfo(groupId)
                if (group != null) {
                    groupInfo = group
                    selfGroupInfo = group.members?.find { it.id == globalServices.myId }
                    selfGroupInfo?.let { role = it.groupRole ?: GROUP_ROLE_MEMBER }
                    initView()
                }
            } catch (e: Exception) {
                L.w { "[GroupInfo] getGroupInfo error: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun initView() {
        groupInfo?.let { headerViewModel.dispatch(GroupInfoHeaderContract.Intent.Load(it)) }
        setMembersView()
        setOtherView()
    }

    private val mGroupsAdapter: GroupInfoMemberAdapter by lazy {
        object : GroupInfoMemberAdapter() {
            override fun onItemClick(contact: GroupMemberModel) {
                when (contact.uid) {
                    "+" -> {
                        if (canInviteGroupMembers(role, groupInfo?.invitationRule)) {
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
                        contact.uid?.let { uid ->
                            showContactCard(this@GroupInfoFragment, uid, sourceType = FriendSourceType.FROM_GROUP, source = groupId)
                        }
                    }
                }
            }
        }
    }

    private fun gotoMembersActivity(type: Int) {
        detailNavigation().push(this, GroupSelectMemberDestination(groupId,
            if (type == GroupSelectMemberActivity.TYPE_ADD_MEMBER)
                com.difft.android.chat.group.mvi.GroupPageContract.MemberAction.ADD
            else com.difft.android.chat.group.mvi.GroupPageContract.MemberAction.REMOVE))
    }

    private val defaultDisplaySize = 13

    private fun setMembersView() {
        binding.edittextSearchInput.setOnClickListener {
            detailNavigation().push(this@GroupInfoFragment, GroupMembersSearchDestination(groupId))
        }

        binding.rvMembers.apply {
            layoutManager = GridLayoutManager(requireContext(), 5)
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
        viewScope.launch(Dispatchers.IO) {
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
        bindConversationSettingRows()
        
        focusSettingsViewModel.dispatch(GroupFocusSettingsContract.Intent.Refresh)
        bindMembershipRows()
    }

    
    private fun placeSearchCard(afterSettings: Boolean) {
        val card = binding.cardSearchManagement
        val parent = card.parent as? ViewGroup ?: return
        val anchor = if (afterSettings) binding.cardChatSettings else binding.composeNotificationSection
        parent.removeView(card)
        val index = parent.indexOfChild(anchor)
        parent.addView(card, if (afterSettings) index + 1 else index)
    }

    
    private fun pinChattingRoom(isPinned: Boolean): Job {
        return viewScope.launch {
            try {
                
                focusPinCoordinator.setGroupPin(groupId, isPinned)
                binding.switchStick.isChecked = isPinned
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[GroupInfo] pinChattingRoom error: ${e.stackTraceToString()}" }
                ToastUtil.show(getString(R.string.operation_failed))
            }
        }
    }

    private suspend fun isPined(): Boolean {
        return dbRoomStore.getPinnedTime(For.Group(groupId)).isPresent
    }

    



    private fun getSaveToPhotosDisplayText(saveToPhotos: Int?): String {
        return when (saveToPhotos) {
            1 -> getString(R.string.save_to_photos_always)
            0 -> getString(R.string.save_to_photos_never)
            else -> {
                
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
        headerBinder.encryptionMeta = if (isEncryptedGroup) {
            IdentityMeta(
                text = getString(R.string.group_encrypted_label),
                iconRes = R.drawable.chat_ic_lock_cog,
                caution = false,
                onClick = { showEncryptedGroupInfoSheet() }
            )
        } else if (globalConfigsManager.isGroupEncryptionEnabled() && role <= GROUP_ROLE_ADMIN) {
            IdentityMeta(
                text = getString(R.string.group_upgrade_to_encrypted),
                iconRes = R.drawable.chat_ic_alert_circle,
                caution = true,
                onClick = { showUpgradeToEncryptedSheet() }
            )
        } else {
            null
        }
    }

    companion object {
        fun newInstance(groupId: String): GroupInfoFragment =
            GroupInfoFragment().apply { arguments = Bundle().apply { putString(KEY_GROUP_ID, groupId) } }
    }
}


class GroupInfoDestination(private val groupId: String) : DetailDestination {
    override fun newFragment(): Fragment = GroupInfoFragment.newInstance(groupId)
    override fun shellIntent(context: Context): Intent =
        Intent(context, GroupInfoActivity::class.java).putExtra(KEY_GROUP_ID, groupId)
    override val subjectId: String get() = groupId
}
