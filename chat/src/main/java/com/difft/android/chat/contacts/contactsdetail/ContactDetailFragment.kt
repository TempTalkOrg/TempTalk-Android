package com.difft.android.chat.contacts.contactsdetail

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager
import com.difft.android.call.LChatToCallController
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.base.utils.DualPaneUtils.isInDualPaneMode
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.chat.contacts.contactsremark.ContactSetRemarkActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.ui.ChatInputFocusable
import com.difft.android.chat.ui.ChatPopupActivity
import com.difft.android.chat.ui.GroupInCommonActivity
import com.difft.android.chat.ui.SelectChatsUtils
import com.difft.android.chat.ui.SingleChatSettingActivity
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.engine.ExoVideoPlayerEngine
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnExternalPreviewEventListener
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import org.thoughtcrime.securesms.util.Util
import android.content.Context
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import org.difft.app.database.convertToContactorModel
import org.difft.app.database.getCommonGroupsCount
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import dagger.Lazy

@AndroidEntryPoint
class ContactDetailFragment : Fragment() {

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    lateinit var callDataManagerLazy: Lazy<CallDataManager>

    @Inject
    lateinit var contactorCacheManager: ContactorCacheManager

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var callManager: LChatToCallController

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    private val callDataManager: CallDataManager by lazy {
        callDataManagerLazy.get()
    }

    companion object {
        private const val ARG_CONTACT_ID = "ARG_CONTACT_ID"
        private const val ARG_CUSTOM_ID = "ARG_CUSTOM_ID"
        private const val ARG_CONTACT_NAME = "ARG_CONTACT_NAME"
        private const val ARG_SOURCE_TYPE = "ARG_SOURCE_TYPE"
        private const val ARG_SOURCE = "ARG_SOURCE"
        private const val ARG_AVATAR = "ARG_AVATAR"
        private const val ARG_JOINED_AT = "ARG_JOINED_AT"

        fun newInstance(
            contactId: String,
            customId: String? = null,
            contactName: String? = null,
            sourceType: String? = null,
            source: String? = null,
            avatar: String? = null,
            joinedAt: String? = null
        ): ContactDetailFragment {
            return ContactDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONTACT_ID, contactId)
                    putString(ARG_CUSTOM_ID, customId)
                    putString(ARG_CONTACT_NAME, contactName)
                    putString(ARG_SOURCE_TYPE, sourceType)
                    putString(ARG_SOURCE, source)
                    putString(ARG_AVATAR, avatar)
                    putString(ARG_JOINED_AT, joinedAt)
                }
            }
        }
    }

    private var mContactor: ContactorModel? = null

    /** Skip next self-triggered update event to avoid redundant reload */
    private var skipNextUpdate = false

    private val contactId: String by lazy {
        arguments?.getString(ARG_CONTACT_ID) ?: ""
    }

    private val customId: String? by lazy {
        arguments?.getString(ARG_CUSTOM_ID)
    }

    private val contactName: String? by lazy {
        arguments?.getString(ARG_CONTACT_NAME)
    }

    private val sourceType: String? by lazy {
        arguments?.getString(ARG_SOURCE_TYPE)
    }

    private val source: String? by lazy {
        arguments?.getString(ARG_SOURCE)
    }

    private val avatar: String? by lazy {
        arguments?.getString(ARG_AVATAR)
    }

    private val joinedAt: String? by lazy {
        arguments?.getString(ARG_JOINED_AT)
    }

    private var isFriend = true

    /** Whether this fragment is displayed in popup (BottomSheet) mode */
    private val isPopupMode: Boolean
        get() = parentFragment is com.google.android.material.bottomsheet.BottomSheetDialogFragment

    // Compose UI state
    private var uiState by mutableStateOf(ContactDetailUiState())
    private var commonGroupsCount by mutableIntStateOf(0)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme {
                    ContactDetailScreen(
                        uiState = uiState.copy(commonGroupsCount = commonGroupsCount),
                        isPopupMode = isPopupMode,
                        showBackButton = !isInDualPaneMode(),
                        onCloseClick = ::handleCloseClick,
                        onMoreClick = ::handleMoreClick,
                        onAvatarClick = ::handleAvatarClick,
                        onEditClick = ::handleEditClick,
                        onMessageClick = ::handleMessageClick,
                        onCallClick = ::handleCallClick,
                        onShareClick = ::shareContact,
                        onAddFriendClick = ::requestAddFriend,
                        onCommonGroupsClick = ::handleCommonGroupsClick,
                        onCopyUserId = ::handleCopyUserId
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (TextUtils.isEmpty(contactId)) return

        initData()
        observeContactsUpdate()
    }

    override fun onResume() {
        super.onResume()
        handleCommonGroupsDisplay()
    }

    private fun initData() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val contact = withContext(Dispatchers.IO) {
                    java.util.Optional.ofNullable(wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contactId)))
                }

                val finalContact = if (contact.isPresent) {
                    isFriend = true
                    contact
                } else {
                    isFriend = false
                    withContext(Dispatchers.IO) {
                        java.util.Optional.ofNullable(wcdb.groupMemberContactor.getFirstObject(DBGroupMemberContactorModel.id.eq(contactId))?.convertToContactorModel())
                    }
                }

                if (!isAdded || view == null) return@launch
                if (finalContact.isPresent) {
                    updateContactView(finalContact.get())
                }
                getContactorInfoFromServer()
            } catch (e: Exception) {
                L.e { "[ContactDetailFragment] Error in initData: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun getContactorInfoFromServer() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val contacts = ContactorUtil.fetchContactors(listOf(contactId), requireContext()).await()
                if (!isAdded || view == null) return@launch
                contacts.firstOrNull()?.let { serverContact ->
                    checkAndNotifyIfNeeded(serverContact)
                    updateContactView(serverContact)
                }
            } catch (e: Exception) {
                L.e { "[ContactDetailFragment] Error fetching contact info: ${e.message}" }
            }
        }
    }

    /**
     * Check if contact info changed, emit update event if needed.
     * Also triggers original avatar download if only small image is cached.
     */
    private fun checkAndNotifyIfNeeded(serverContact: ContactorModel) {
        if (mContactor != serverContact) {
            val localAvatar = mContactor?.avatar
            val serverAvatar = serverContact.avatar
            L.i { "[ContactDetailFragment] Contact info changed for $contactId, localAvatar:${localAvatar != null}, serverAvatar:${serverAvatar != null}, notify update" }
            skipNextUpdate = true
            ContactorUtil.emitContactsUpdate(listOf(contactId))
            return
        }

        // Data unchanged, ensure original avatar is cached
        ensureOriginalAvatarCached(serverContact)
    }

    /**
     * Check if original avatar image is cached, if not download it in background.
     * This gradually replaces small avatar cache with original images.
     */
    private fun ensureOriginalAvatarCached(contact: ContactorModel) {
        val avatarData = contact.avatar?.getContactAvatarData() ?: return
        val avatarUrl = avatarData.getContactAvatarUrl() ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Check if original image exists (new format without _SMALL suffix)
            val originalFile = java.io.File(
                com.difft.android.base.utils.FileUtil.getAvatarCachePath(),
                "avatar_${avatarUrl.substringAfterLast("/")}"
            )

            if (originalFile.exists()) {
                L.d { "[ContactDetailFragment] Original avatar already cached" }
                return@launch
            }

            // Original not found, download it
            L.i { "[ContactDetailFragment] Original avatar not cached, downloading in background..." }
            try {
                val bytes = AvatarUtil.fetchAvatar(requireContext(), avatarUrl, avatarData.encKey ?: "")
                originalFile.writeBytes(bytes)
                L.i { "[ContactDetailFragment] Original avatar downloaded and cached" }
            } catch (e: Exception) {
                L.e { "[ContactDetailFragment] Failed to download original avatar: ${e.message}" }
            }
        }
    }

    private fun observeContactsUpdate() {
        ContactorUtil.contactsUpdate
            .asFlow()
            .filter { it.contains(contactId) }
            .onEach {
                if (skipNextUpdate) {
                    // Skip self-triggered update to avoid redundant reload
                    skipNextUpdate = false
                    L.d { "[ContactDetailFragment] Skip self-triggered update" }
                    return@onEach
                }
                initData()
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    /**
     * Handle the display and query of common groups between current user and contact
     */
    private fun handleCommonGroupsDisplay() {
        if (contactId == globalServices.myId) {
            commonGroupsCount = 0
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val count = withContext(Dispatchers.IO) {
                        wcdb.getCommonGroupsCount(contactId, globalServices.myId)
                    }

                    if (!isAdded || view == null) return@launch
                    commonGroupsCount = count
                } catch (e: Exception) {
                    L.e { "[ContactDetailFragment] Error querying common groups: ${e.stackTraceToString()}" }
                    commonGroupsCount = 0
                }
            }
        }
    }

    private fun handleCloseClick() {
        if (isPopupMode) {
            (parentFragment as? androidx.fragment.app.DialogFragment)?.dismiss()
        } else if (!isInDualPaneMode()) {
            activity?.finish()
        }
    }

    private fun handleMoreClick() {
        SingleChatSettingActivity.startActivity(requireActivity(), contactId)
    }

    private fun handleEditClick() {
        ContactSetRemarkActivity.startActivity(requireActivity(), contactId)
    }

    private fun handleCallClick() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val chatRoomName = withContext(Dispatchers.IO) {
                    contactorCacheManager.getDisplayName(contactId) ?: ""
                }
                if (!isAdded || view == null) return@launch
                if (onGoingCallStateManager.isInCalling()) {
                    if (onGoingCallStateManager.getConversationId() == contactId) {
                        L.i { "[call] ContactDetailFragment bringing back the current call." }
                        LCallManager.bringCallScreenToFront(requireActivity())
                    } else {
                        ToastUtil.show(R.string.call_is_calling_tip)
                    }
                } else {
                    // 判断当前是否有livekit会议，有则join会议
                    val callData = callDataManager.getCallDataByConversationId(contactId)
                    if (callData != null) {
                        L.i { "[call] ContactDetailFragment join call, roomId:${callData.roomId}." }
                        LCallManager.joinCall(requireActivity(), callData) { status ->
                            if (!status) {
                                L.e { "[Call] ContactDetailFragment join call failed." }
                                ToastUtil.show(com.difft.android.call.R.string.call_join_failed_tip)
                            }
                        }
                        return@launch
                    }
                    // 否则发起livekit call通话
                    L.i { "[call] ContactDetailFragment start call." }
                    callManager.startCall(requireActivity(), For.Account(contactId), chatRoomName) { status, message ->
                        if (!status) {
                            L.e { "[Call] ContactDetailFragment start call failed." }
                            message?.let { ToastUtil.show(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                L.e { "[call] ContactDetailFragment start call error:${e.message}" }
                ToastUtil.show("start call error")
            }
        }
    }

    private fun handleMessageClick() {
        val navigationCallback = activity as? ConversationNavigationCallback
        if (isPopupMode) {
            // Dismiss the popup first
            (parentFragment as? androidx.fragment.app.DialogFragment)?.dismiss()
            // Check if currently in the same chat and focus input if so
            val focusable = activity as? ChatInputFocusable
            if (focusable?.focusCurrentChatInputIfMatches(contactId) != true) {
                // Not in the same chat, open chat popup
                ChatPopupActivity.startActivity(
                    requireContext(),
                    contactId,
                    sourceType = sourceType,
                    source = source
                )
            }
        } else if (navigationCallback?.isDualPaneMode == true) {
            // Dual-pane mode: open chat in detail pane
            navigationCallback.onOneOnOneConversationSelected(contactId)
        } else {
            // Activity mode: open full chat activity
            ChatActivity.startActivity(
                requireContext(),
                contactId,
                sourceType = sourceType,
                source = source
            )
        }
    }

    private fun handleCommonGroupsClick() {
        if (commonGroupsCount > 0) {
            GroupInCommonActivity.startActivity(requireActivity(), contactId)
        }
    }

    private fun handleCopyUserId() {
        val userId = uiState.userId
        if (userId.isNotEmpty()) {
            Util.copyToClipboard(requireContext(), userId)
        }
    }

    private fun shareContact() {
        selectChatsUtils.showChatSelectAndSendDialog(
            requireActivity(),
            getString(R.string.chat_contact_card),
            forwardContexts = listOf(ForwardContext(emptyList(), false, contactId, mContactor?.getDisplayNameWithoutRemarkForUI()))
        )
    }

    private fun updateContactView(contactor: ContactorModel?) {
        if (contactor == null || contactor == mContactor) {
            L.d { "[ContactDetailFragment] Contact data is null or same, skip refresh" }
            return
        }
        mContactor = contactor

        // 通过邀请码查找联系人，部分信息是直接带过来的
        if (!avatar.isNullOrEmpty()) {
            contactor.avatar = avatar
        }
        if (!joinedAt.isNullOrEmpty()) {
            contactor.joinedAt = joinedAt
        }

        val isSelf = globalServices.myId == contactId
        val isBot = contactor.id.isBotId()
        val hasRemark = !TextUtils.isEmpty(contactor.remark)

        val displayName = if (hasRemark) {
            contactor.remark ?: contactor.getDisplayNameForUI()
        } else {
            contactor.getDisplayNameForUI()
        }

        val originalName = if (hasRemark) {
            contactor.name ?: contactor.publicName ?: contactor.id
        } else {
            null
        }

        // Build userId display value (prefer customUid)
        val userId = if (contactor.customUid.isNullOrEmpty() && customId.isNullOrEmpty()) {
            contactor.id.formatBase58Id(false)
        } else {
            contactor.customUid ?: customId ?: ""
        }

        uiState = ContactDetailUiState(
            contactor = contactor,
            isFriend = isFriend,
            isSelf = isSelf,
            isBot = isBot,
            displayName = displayName,
            originalName = originalName,
            hasRemark = hasRemark,
            userId = userId,
            joinedAt = contactor.joinedAt,
            sourceDescribe = if (!isSelf) contactor.sourceDescribe else null,
            commonGroupsCount = commonGroupsCount
        )
    }

    private fun requestAddFriend() {
        ComposeDialogManager.showWait(requireActivity(), "")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = ContactorUtil.fetchAddFriendRequest(
                    requireContext(),
                    SecureSharedPrefsUtil.getToken(),
                    contactId,
                    sourceType,
                    source
                ).await()

                if (!isAdded || view == null) return@launch
                if (response.status == 0) {
                    ContactorUtil.sendFriendRequestMessage(
                        viewLifecycleOwner.lifecycleScope,
                        getString(R.string.contact_friend_request),
                        For.Account(contactId)
                    )
                    ComposeDialogManager.dismissWait()
                    ToastUtil.show(R.string.contact_request_sent)
                } else {
                    ComposeDialogManager.dismissWait()
                    response.reason?.let { ToastUtil.show(it) }
                }
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                e.message?.let { ToastUtil.show(it) }
            }
        }
    }

    /**
     * Handle avatar click to open avatar preview.
     * - Checks if original image cache exists, if not triggers download
     * - Uses original image path first, falls back to small image for compatibility
     */
    private fun handleAvatarClick() {
        val contact = mContactor ?: return
        if (TextUtils.isEmpty(contact.avatar)) return
        val avatarData = contact.avatar?.getContactAvatarData() ?: return
        val avatarUrl = avatarData.getContactAvatarUrl() ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val cacheFile = withContext(Dispatchers.IO) {
                AvatarUtil.getCacheFile(avatarUrl)
            }

            if (cacheFile == null) {
                // No cache exists, show loading and download
                L.i { "[ContactDetailFragment] Avatar cache not found, downloading..." }
                ComposeDialogManager.showWait(requireActivity(), "")

                val success = withContext(Dispatchers.IO) {
                    try {
                        val bytes = AvatarUtil.fetchAvatar(requireContext(), avatarUrl, avatarData.encKey ?: "")
                        val newCacheFile = java.io.File(
                            com.difft.android.base.utils.FileUtil.getAvatarCachePath(),
                            "avatar_${avatarUrl.substringAfterLast("/")}"
                        )
                        newCacheFile.writeBytes(bytes)
                        true
                    } catch (e: Exception) {
                        L.e { "[ContactDetailFragment] Failed to download avatar: ${e.message}" }
                        false
                    }
                }

                ComposeDialogManager.dismissWait()

                if (!success || !isAdded || view == null) return@launch

                val newFile = withContext(Dispatchers.IO) {
                    AvatarUtil.getCacheFile(avatarUrl)
                } ?: return@launch

                openAvatarPreview(newFile.path)
            } else {
                // Cache exists, open preview directly
                openAvatarPreview(cacheFile.path)
            }
        }
    }

    private fun openAvatarPreview(filePath: String) {
        if (!isAdded || activity == null) return

        val list = arrayListOf<LocalMedia>().apply {
            add(LocalMedia.generateLocalMedia(requireActivity(), filePath))
        }

        PictureSelector.create(requireActivity())
            .openPreview()
            .isHidePreviewDownload(true)
            .isHidePreviewShare(true)
            .setDefaultLanguage(LanguageConfig.ENGLISH)
            .setLanguage(PictureSelectorUtils.getLanguage(requireContext()))
            .setImageEngine(GlideEngine.createGlideEngine())
            .startActivityPreview(0, false, list)
    }
}