package com.difft.android.chat.ui

import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.SettingsIdiomPage
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.sidechrome.padForSideChrome
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.EdgeToEdgeUtils
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.pane.ConversationOpener
import com.difft.android.base.utils.pane.RemovedConversations
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.launchOutlivingPage
import com.difft.android.base.utils.pane.isInDetailStack
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.compose.CollapsingTitleBar
import com.difft.android.chat.common.compose.ContactAvatar
import com.difft.android.chat.common.compose.IdentityHeader
import com.difft.android.chat.common.compose.IdentityHeaderMode
import com.difft.android.chat.common.compose.LetterAvatar
import com.difft.android.chat.common.compose.TitleBarAction
import com.difft.android.chat.common.compose.TitleCollapseTracker
import com.difft.android.chat.contacts.contactsdetail.showContactCard
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.chat.databinding.ChatActivitySettingBinding
import com.difft.android.chat.group.create.openCreateGroupFromConversation
import com.difft.android.chat.search.SearchMessageDestination
import com.difft.android.chat.setting.ChatArchiveSettingsDestination
import com.difft.android.chat.setting.SaveToPhotosSettingsDestination
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.chat.setting.translate.TranslateSettingsDestination
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.translate.translateEntryValue
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.util.MessageSoundLabel
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.network.requests.MuteStatus
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.getCommonGroupsCount
import org.difft.app.database.getContactorFromAllTable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException







@AndroidEntryPoint
class SingleChatSettingFragment : Fragment(), SettingsIdiomPage {
    
    private val pageWait = PageWait(this)


    @Inject
    lateinit var dbMessageStore: DBMessageStore

    @Inject
    lateinit var dbRoomStore: DBRoomStore

    @Inject
    lateinit var activityProvider: ActivityProvider

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var userManager: com.difft.android.base.user.UserManager

    private val contactId: String by lazy { requireArguments().getString(ARG_CONTACT_ID).orEmpty() }

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> {
            it.create(For.Account(contactId))
        }
    })

    private var _binding: ChatActivitySettingBinding? = null
    private val binding get() = _binding!!

    private var mContact: ContactorModel? = null

    
    private var headerContact by mutableStateOf<ContactorModel?>(null)
    private var collapseTracker: TitleCollapseTracker? = null

    



    private val fallbackContact by lazy { ContactorModel().apply { id = contactId } }

    
    private var isOfficial by mutableStateOf(false)
    private var blockStatus: Int? = null

    private fun rows(inContacts: Boolean = false) =
        SingleChatSettingRows.of(isSelf = contactId == globalServices.myId, isOfficial = isOfficial, inContacts = inContacts)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ChatActivitySettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (contactId.isEmpty()) {
            L.w { "[SingleChatSetting] empty contact id, closing" }
            detailNavigation().pop(this)
            return
        }
        
        
        if (isInDetailStack()) EdgeToEdgeUtils.applyBottomInsetAsPadding(binding.root)
        padForSideChrome(binding.root)

        isOfficial = contactId.isOfficialAccount()
        collapseTracker = TitleCollapseTracker(binding.composeTitleBar)
        setupHeaderCompose()
        setupEntries()
        loadAndRenderContact()
        observe()
        applyAccountGating()
        setupToggles()
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null || contactId.isEmpty()) return
        handleCommonGroupsDisplay()
        refreshNotificationSound()
    }

    override fun onDestroyView() {
        collapseTracker = null
        _binding = null
        super.onDestroyView()
    }

    private val viewScope get() = viewLifecycleOwner.lifecycleScope

    private fun setupEntries() {
        val target = For.Account(contactId)
        binding.saveToPhotosContainer.setOnClickListener { detailNavigation().push(this, SaveToPhotosSettingsDestination(target)) }
        binding.translateContainer.setOnClickListener { detailNavigation().push(this, TranslateSettingsDestination(target)) }
        binding.disappearingTimeContainer.setOnClickListener { detailNavigation().push(this, ChatArchiveSettingsDestination(target)) }

        if (messageNotificationUtil.supportConversationNotification()) {
            binding.relNotificationSound.visibility = View.VISIBLE
            binding.relNotificationSound.setOnClickListener {
                messageNotificationUtil.createChannelForConversation(contactId, mContact?.getDisplayNameForUI() ?: contactId.formatBase58Id())
                messageNotificationUtil.openMessageNotificationChannelSettings(requireActivity(), contactId)
            }
        } else {
            binding.relNotificationSound.visibility = View.GONE
        }

        binding.llSearchChatHistory.setOnClickListener {
            detailNavigation().push(this, SearchMessageDestination(contactId, isGroup = false))
        }
        binding.tvUnblock.setOnClickListener {
            chatSettingViewModel.setConversationConfigs(requireActivity(), contactId, null, null, 0, false, getString(R.string.contact_unblocked))
        }
        binding.tvBlock.setOnClickListener { showBlockDialog() }
    }

    private fun observe() {
        
        ContactorUtil.contactsUpdate
            .filter { it.contains(contactId) }
            .onEach { loadAndRenderContact() }
            .launchIn(viewScope)

        
        
        
        OfficialAccountCache.state
            .map { contactId in it }
            .distinctUntilChanged()
            .drop(1)
            .onEach { official ->
                L.i { "[SingleChatSetting] official flag flipped official=$official" }
                isOfficial = official
                applyAccountGating()
                applyBlockRow()
                handleCommonGroupsDisplay()
            }
            .launchIn(viewScope)

        
        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach { conversationSet ->
                blockStatus = conversationSet.blockStatus
                applyBlockRow()
                binding.switch2mute1on1.isChecked = conversationSet.isMuted
                binding.saveToPhotosText.text = getSaveToPhotosDisplayText(conversationSet.saveToPhotos)
                binding.disappearingTimeText.text = conversationSet.messageExpiry.toArchiveTimeDisplayText()
            }
            .launchIn(viewScope)

        
        if (chatSettingViewModel.translateSupported) {
            chatSettingViewModel.translateSetting
                .onEach { binding.translateText.text = translateEntryValue(requireContext(), it) }
                .launchIn(viewScope)
        } else {
            binding.translateContainer.visibility = View.GONE
        }
    }

    
    private fun applyAccountGating() {
        val isSelf = contactId == globalServices.myId
        val inContacts = !isSelf && !isOfficial &&
            wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contactId)) != null
        val rows = rows(inContacts)
        binding.clMute.visibility = if (rows.mute) View.VISIBLE else View.GONE
        binding.relRemove.visibility = if (rows.remove) View.VISIBLE else View.GONE
        if (rows.remove) binding.tvRemove.setOnClickListener { showRemoveDialog() }
        binding.disappearingTimeContainer.visibility = if (rows.disappearing) View.VISIBLE else View.GONE
        
        if (isSelf) binding.translateContainer.visibility = View.GONE
        if (!rows.block) binding.clBlock.visibility = View.GONE
    }

    
    private fun applyBlockRow() {
        val status = blockStatus ?: return
        if (!rows().block) {
            binding.clBlock.visibility = View.GONE
            return
        }
        binding.clBlock.visibility = View.VISIBLE
        binding.tvUnblock.visibility = if (status == 1) View.VISIBLE else View.GONE
        binding.tvBlock.visibility = if (status == 1) View.GONE else View.VISIBLE
    }

    private fun setupToggles() {
        
        
        binding.switch2mute1on1.setOnToggleRequestListener { view, requested ->
            val activity = requireActivity()
            pageWait.show()
            view.guardWhile(
                chatSettingViewModel.setConversationConfigs(
                    activity = activity,
                    conversation = contactId,
                    muteStatus = MuteStatus.of(requested).value,
                )
            ) {
                pageWait.dismiss()
            }
        }

        viewScope.launch(Dispatchers.IO) {
            val isPinned = isPined()
            withContext(Dispatchers.Main) {
                _binding?.switchStick?.isChecked = isPinned
            }
        }
        
        binding.switchStick.setOnToggleRequestListener { view, requested ->
            view.guardWhile(pinChattingRoom(requested))
        }
    }

    
    private fun refreshNotificationSound() {
        if (!messageNotificationUtil.supportConversationNotification()) return
        val appContext = requireContext().applicationContext
        viewScope.launch {
            try {
                val label = withContext(Dispatchers.IO) { MessageSoundLabel.readForConversation(appContext, contactId) }
                binding.tvNotificationSound.text = label
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                L.w { "[SingleChatSetting] notification sound read failed error=${e.javaClass.simpleName}" }
            }
        }
    }

    

    private fun setupHeaderCompose() {
        val tracker = collapseTracker ?: return
        binding.composeTitleBar.setContent {
            DifftTheme(applyWindowBackground = false) {
                CollapsingTitleBar(
                    title = headerContact?.getDisplayNameForUI().orEmpty(),
                    collapsed = tracker.collapsed,
                    onBack = { detailNavigation().pop(this@SingleChatSettingFragment) },
                    action = if (!rows().createGroupAction) {
                        null
                    } else {
                        TitleBarAction.Icon(R.drawable.chat_ic_users_plus, getString(R.string.chat_option_create_group)) { createGroupWithContact() }
                    }
                )
            }
        }
        binding.composeIdentityHeader.setContent {
            DifftTheme(applyWindowBackground = false) {
                
                
                val contact = headerContact
                IdentityHeader(
                    name = contact?.getDisplayNameForUI().orEmpty(),
                    mode = IdentityHeaderMode.Browse,
                    avatar = { avatarModifier ->
                        if (contact != null) {
                            ContactAvatar(contactor = contact, size = DifftTheme.spacing.avatarLarge, modifier = avatarModifier)
                        } else {
                            LetterAvatar(firstLetter = null, id = contactId, size = DifftTheme.spacing.avatarLarge, modifier = avatarModifier)
                        }
                    },
                    onAvatarClick = { openContactCard() },
                    onHeaderClick = { openContactCard() },
                    onAvatarBottomChanged = tracker::onAvatarBottomChanged,
                )
            }
        }
    }

    private fun openContactCard() {
        showContactCard(this, contactId)
    }

    private fun createGroupWithContact() {
        viewScope.launch {
            try {
                val contact = withContext(Dispatchers.IO) {
                    wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contactId))
                }
                if (contact != null) {
                    openCreateGroupFromConversation(this@SingleChatSettingFragment, contact.id)
                } else {
                    ToastUtil.showLong(getString(R.string.contact_not_in_list))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[SingleChatSetting] createGroup error: ${e.stackTraceToString()}" }
            }
        }
    }

    

    private fun loadAndRenderContact() {
        viewScope.launch {
            try {
                val contact = withContext(Dispatchers.IO) {
                    wcdb.getContactorFromAllTable(contactId)
                }
                mContact = contact
                headerContact = contact ?: fallbackContact
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[SingleChatSetting] getContactWithID error: ${e.stackTraceToString()}" }
                headerContact = headerContact ?: fallbackContact
            }
        }
    }

    
    @SuppressLint("SetTextI18n")
    private fun handleCommonGroupsDisplay() {
        if (!rows().commonGroups) {
            binding.relGroupInCommon.visibility = View.GONE
            return
        }
        binding.relGroupInCommon.visibility = View.VISIBLE
        viewScope.launch {
            try {
                val commonGroupsCount = withContext(Dispatchers.IO) {
                    wcdb.getCommonGroupsCount(contactId, globalServices.myId)
                }
                binding.tvCommonGroupCount.text = commonGroupsCount.toString()
                if (commonGroupsCount > 0) {
                    binding.arrow1.visibility = View.VISIBLE
                    binding.relGroupInCommon.setOnClickListener {
                        detailNavigation().push(this@SingleChatSettingFragment, GroupInCommonDestination(contactId))
                    }
                } else {
                    binding.arrow1.visibility = View.INVISIBLE
                    binding.relGroupInCommon.setOnClickListener(null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[SingleChatSetting] Error querying common groups: ${e.stackTraceToString()}" }
                binding.relGroupInCommon.visibility = View.GONE
            }
        }
    }

    private fun showRemoveDialog() {
        SingleChatSettingDialogs.confirmRemove(this) { removeFriend() }
    }

    



    private fun removeFriend() {
        val appContext = requireContext().applicationContext
        val contactId = contactId
        val messageStore = dbMessageStore
        launchOutlivingPage {
            try {
                val result = withContext(Dispatchers.IO) { ContactorUtil.fetchRemoveFriend(appContext, contactId) }
                if (result.status == 0) {
                    withContext(Dispatchers.IO) {
                        wcdb.contactor.deleteObjects(DBContactorModel.id.eq(contactId))
                        messageStore.removeRoomAndMessages(contactId)
                    }
                    ContactorUtil.emitContactsUpdate(listOf(contactId))
                    
                    
                    
                    RemovedConversations.add(contactId)
                    (appContext as? ConversationOpener)?.closeConversation(contactId)
                    onPage {
                        ToastUtil.show(R.string.contact_removed)
                        binding.relRemove.visibility = View.GONE
                        leaveRemovedConversation()
                    }
                } else {
                    result.reason?.let { message -> onPage { ToastUtil.show(message) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message?.let { message -> onPage { ToastUtil.show(message) } }
                L.w { "[SingleChatSetting] removeFriend error: ${e.stackTraceToString()}" }
            }
        }
    }

    





    private fun leaveRemovedConversation() {
        if (isInDetailStack()) {
            detailNavigation().closeConversation(this)
        } else {
            startActivity(Intent(requireContext(), activityProvider.getActivityClass(ActivityType.INDEX)))
        }
    }

    private fun showBlockDialog() {
        val activity = requireActivity()
        SingleChatSettingDialogs.confirmBlock(this) {
            chatSettingViewModel.setConversationConfigs(activity, contactId, null, null, 1, false, getString(R.string.contact_blocked))
        }
    }

    
    private fun pinChattingRoom(isPinned: Boolean): Job {
        return viewScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    dbRoomStore.updatePinnedTime(
                        For.Account(contactId),
                        if (isPinned) System.currentTimeMillis() else null
                    )
                }
                binding.switchStick.isChecked = isPinned
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[SingleChatSetting] pinChattingRoom error: ${e.stackTraceToString()}" }
                ToastUtil.show(getString(R.string.operation_failed))
            }
        }
    }

    private suspend fun isPined(): Boolean {
        return dbRoomStore.getPinnedTime(For.Account(contactId)).isPresent
    }

    



    private fun getSaveToPhotosDisplayText(saveToPhotos: Int?): String = when (saveToPhotos) {
        1 -> getString(R.string.save_to_photos_always)
        0 -> getString(R.string.save_to_photos_never)
        else -> {
            val globalEnabled = userManager.getUserData()?.saveToPhotos == true
            val statusText = getString(if (globalEnabled) R.string.save_to_photos_on else R.string.save_to_photos_off)
            getString(R.string.save_to_photos_default, statusText)
        }
    }

    companion object {
        private const val ARG_CONTACT_ID = "id"

        fun newInstance(contactId: String): SingleChatSettingFragment =
            SingleChatSettingFragment().apply { arguments = bundleOf(ARG_CONTACT_ID to contactId) }
    }
}
