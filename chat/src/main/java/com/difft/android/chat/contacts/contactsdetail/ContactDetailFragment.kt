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
import com.difft.android.base.ui.theme.tokens.ColorTokens
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager
import com.difft.android.chat.call.LChatToCallController
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.base.utils.DualPaneUtils.isInDualPaneMode
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarCacheCipher
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.chat.media.AvatarEncryptedProvider
import com.difft.android.chat.media.AvatarPreview
import com.difft.android.chat.contacts.contactsremark.ContactSetRemarkActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.ContactorUtil.getEntryPoint
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.isOfficialAccount
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
import com.difft.android.messageserialization.db.store.getEffectiveAvatarJson
import com.difft.android.selector.basic.PictureSelector
import com.difft.android.selector.engine.ExoVideoPlayerEngine
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnExternalPreviewEventListener
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.pictureselector.GlideEngine
import com.difft.android.selector.pictureselector.PictureSelectorUtils
import com.difft.android.chat.util.Util
import android.content.Context
import com.difft.android.base.security.SafeLinkOpener
import com.difft.android.network.UrlManager
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.cache.OfficialAccountCache
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

    @Inject
    lateinit var urlManager: UrlManager

    private val callDataManager: CallDataManager by lazy {
        callDataManagerLazy.get()
    }

    companion object {
        // Public so IndexActivity can read it when reconstructing the dual-pane detail after recreation.
        const val ARG_CONTACT_ID = "ARG_CONTACT_ID"
        private const val ARG_CUSTOM_ID = "ARG_CUSTOM_ID"
        private const val ARG_CONTACT_NAME = "ARG_CONTACT_NAME"
        private const val ARG_SOURCE_TYPE = "ARG_SOURCE_TYPE"
        private const val ARG_SOURCE = "ARG_SOURCE"
        private const val ARG_AVATAR = "ARG_AVATAR"
        private const val ARG_JOINED_AT = "ARG_JOINED_AT"

        /** Server business status: target account logged out / deregistered / unavailable. */
        private const val ERR_ACCOUNT_UNAVAILABLE = 19009

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

    /** Weak-pending (delayed-removal) contact — drives the "Remove Now" entry. */
    private var isWeakPending = false

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
                        onOriginalAvatarClick = ::handleOriginalAvatarClick,
                        onEditClick = ::handleEditClick,
                        onMessageClick = ::navigateToChat,
                        onCallClick = ::handleCallClick,
                        onShareClick = ::shareContact,
                        onAddFriendClick = ::requestAddFriend,
                        onCommonGroupsClick = ::handleCommonGroupsClick,
                        onCopyUserId = ::handleCopyUserId,
                        onWebsiteClick = ::handleWebsiteClick,
                        onRemoveNowClick = ::requestRemoveNow
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
        // SharedFlow tryEmit can resume collector before viewLifecycleOwner cancels — guard before sync access (Crashlytics 936790a0).
        if (!isAdded || view == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Weak-pending check: pending contacts are already moved out of the contactor table,
                // so isPending=true forces non-friend + the "Remove Now" entry. Re-evaluated on every
                // initData() — observeContactsUpdate() re-runs this on contactsUpdate emits
                // (remove-now / reconcile), giving a reactive refresh.
                isWeakPending = withContext(Dispatchers.IO) {
                    requireContext().getEntryPoint().getPendingRemovalContactRepository().isPending(contactId)
                }

                val contact = withContext(Dispatchers.IO) {
                    java.util.Optional.ofNullable(wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contactId)))
                }

                val finalContact = if (contact.isPresent && !isWeakPending) {
                    isFriend = true
                    contact
                } else {
                    isFriend = false
                    withContext(Dispatchers.IO) {
                        // Weak-pending: use the value chain (getContactWithID inserts the weak
                        // snapshot layer) so the name/avatar render even after the peer deregisters.
                        if (isWeakPending) {
                            ContactorUtil.getContactWithID(requireContext(), contactId)
                        } else {
                            val rows = wcdb.groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.id.eq(contactId))
                            val selected = rows.firstOrNull { it.gid == "" } ?: rows.firstOrNull()
                            java.util.Optional.ofNullable(selected?.convertToContactorModel())
                        }
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
                val contacts = ContactorUtil.fetchContactors(listOf(contactId), requireContext())
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
                AvatarCacheCipher.writeEncrypted(originalFile, bytes)
                L.i { "[ContactDetailFragment] Original avatar downloaded and cached" }
            } catch (e: Exception) {
                L.e { "[ContactDetailFragment] Failed to download original avatar: ${e.message}" }
            }
        }
    }

    private fun observeContactsUpdate() {
        ContactorUtil.contactsUpdate
            .filter { it.contains(contactId) }
            .onEach {
                if (!isAdded || view == null) return@onEach // belt-and-suspenders, initData also guards
                if (skipNextUpdate) {
                    // Skip self-triggered update to avoid redundant reload
                    skipNextUpdate = false
                    L.d { "[ContactDetailFragment] Skip self-triggered update" }
                    return@onEach
                }
                initData()
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        // Self-heal if the official-account cache populates after this screen was built during the
        // pre-preload window. initData() rebuilds the UiState, so isOfficialAccount + the derived
        // website/label recompute.
        OfficialAccountCache.state
            .map { contactId in it }
            .distinctUntilChanged()
            .drop(1)
            .onEach {
                if (!isAdded || view == null) return@onEach
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
                        val status = LCallManager.joinCall(requireActivity(), callData)
                        if (!status) {
                            L.e { "[Call] ContactDetailFragment join call failed." }
                            ToastUtil.show(com.difft.android.call.R.string.call_join_failed_tip)
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

    private fun navigateToChat() {
        val navigationCallback = activity as? ConversationNavigationCallback
        if (isPopupMode) {
            (parentFragment as? androidx.fragment.app.DialogFragment)?.dismiss()
            val focusable = activity as? ChatInputFocusable
            if (focusable?.focusCurrentChatInputIfMatches(contactId) != true) {
                ChatPopupActivity.startActivity(
                    requireContext(),
                    contactId,
                    sourceType = sourceType,
                    source = source
                )
            }
        } else if (navigationCallback?.isDualPaneMode == true) {
            navigationCallback.onOneOnOneConversationSelected(contactId)
        } else {
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

    private fun handleWebsiteClick() {
        val website = uiState.website ?: return
        SafeLinkOpener.open(requireContext(), website)
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
        val isOfficialAccount = contactor.id.isOfficialAccount()
        // Mirror the priority chain that getDisplayNameForUI() walks for remarks
        // so hasRemark reflects what displayName actually surfaces, otherwise the
        // "原名" line under displayName would be dropped when remark only lives on
        // the group-member row (cache cold + contactor.remark null).
        val effectiveRemark = ContactRemarkCache.getRemark(contactor.id)?.takeIf { it.isNotEmpty() }
            ?: contactor.remark?.takeIf { it.isNotEmpty() }
            ?: contactor.groupMemberContactor?.remark?.takeIf { it.isNotEmpty() }
        val hasRemark = !effectiveRemark.isNullOrEmpty()
        val displayName = effectiveRemark ?: contactor.getDisplayNameForUI()

        val originalName = if (hasRemark) contactor.getDisplayNameWithoutRemarkForUI() else null

        // Same priority chain as remark name above.
        val effectiveRemarkAvatar = ContactRemarkCache.getRemarkAvatar(contactor.id)?.takeIf { it.isNotEmpty() }
            ?: contactor.remarkAvatar?.takeIf { it.isNotEmpty() }
            ?: contactor.groupMemberContactor?.remarkAvatar?.takeIf { it.isNotEmpty() }
        val hasRemarkAvatar = !effectiveRemarkAvatar.isNullOrEmpty()

        // Build userId display value (prefer customUid)
        val userId = if (contactor.customUid.isNullOrEmpty() && customId.isNullOrEmpty()) {
            contactor.id.formatBase58Id(false)
        } else {
            contactor.customUid ?: customId ?: ""
        }

        uiState = ContactDetailUiState(
            contactor = contactor,
            isFriend = isFriend,
            isWeakPending = isWeakPending,
            isSelf = isSelf,
            isOfficialAccount = isOfficialAccount,
            displayName = displayName,
            originalName = originalName,
            hasRemark = hasRemark,
            hasRemarkAvatar = hasRemarkAvatar,
            originalAvatarJson = if (hasRemarkAvatar) contactor.avatar else null,
            userId = userId,
            joinedAt = contactor.joinedAt,
            sourceDescribe = if (!isSelf) contactor.sourceDescribe else null,
            commonGroupsCount = commonGroupsCount,
            website = if (isOfficialAccount) urlManager.installationGuideUrl else null
        )
    }

    private fun requestAddFriend() {
        ComposeDialogManager.showWait(requireActivity(), "")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = ContactorUtil.fetchAddFriendRequest(
                    requireContext(),
                    (userManager.getUserData()?.microToken ?: ""),
                    contactId,
                    sourceType,
                    source
                )

                if (!isAdded || view == null) return@launch
                if (response.status == 0) {
                    ContactorUtil.sendFriendRequestMessage(
                        viewLifecycleOwner.lifecycleScope,
                        getString(R.string.contact_friend_request),
                        For.Account(contactId)
                    )
                    ComposeDialogManager.dismissWait()
                    navigateToChat()
                } else {
                    ComposeDialogManager.dismissWait()
                    when {
                        // 19009 = peer account logged out / deregistered. Weak-pending (still in the
                        // address book, removable) → alert offering a delete entry; otherwise a plain
                        // toast. Both use the product-fixed copy, not the server reason.
                        response.status == ERR_ACCOUNT_UNAVAILABLE && isWeakPending ->
                            showAccountUnavailableDialog()

                        response.status == ERR_ACCOUNT_UNAVAILABLE ->
                            ToastUtil.show(getString(R.string.weak_contact_account_unavailable_message))

                        else -> response.reason?.let { ToastUtil.show(it) }
                    }
                }
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                e.message?.let { ToastUtil.show(it) }
            }
        }
    }

    /**
     * 19009 + weak-pending: the peer account is gone but the contact is still in the address book.
     * Offer a delete entry (reuses "Remove Now") so the user can clear it instead of retrying add.
     * Not weak-pending → the caller shows a toast instead (no delete entry, per the product spec).
     */
    private fun showAccountUnavailableDialog() {
        ComposeDialogManager.showMessageDialog(
            context = requireContext(),
            title = getString(R.string.weak_contact_account_unavailable_title),
            message = getString(R.string.weak_contact_account_unavailable_message),
            confirmText = getString(R.string.contact_remove),
            cancelText = getString(R.string.weak_contact_account_unavailable_ok),
            confirmButtonColor = ColorTokens.Error, // align with detail page "Remove Now" (DifftTheme.colors.error)
            onConfirm = { requestRemoveNow() }
        )
    }

    /**
     * "Remove Now" for a weak-pending contact. Pessimistic: the server DELETE must succeed before
     * the local removal happens — onSuccess means the record is gone, so close the card; onFailure
     * leaves the card open with a retry toast.
     */
    private fun requestRemoveNow() {
        viewLifecycleOwner.lifecycleScope.launch {
            requireContext().getEntryPoint().getWeakContactReconciler().removeNow(contactId)
                .onSuccess {
                    // Close the card via the popup-aware path (dismiss sheet in popup mode,
                    // finish Activity otherwise) — a bare finish() would kill the host Activity.
                    if (isAdded) handleCloseClick()
                }
                .onFailure {
                    if (isAdded) ToastUtil.show(getString(R.string.weak_contact_remove_now_failed))
                }
        }
    }

    /** Preview the rendered (effective) avatar — honors the remark chain. */
    private fun handleAvatarClick() {
        val contact = mContactor ?: return
        val data = contact.getEffectiveAvatarJson()?.getContactAvatarData() ?: return
        previewAvatar(data.getContactAvatarUrl(), data.encKey)
    }

    /** Preview the public (non-remark) avatar from the inline subtitle thumbnail. */
    private fun handleOriginalAvatarClick() {
        val contact = mContactor ?: return
        val data = contact.avatar?.getContactAvatarData() ?: return
        previewAvatar(data.getContactAvatarUrl(), data.encKey)
    }

    private fun previewAvatar(url: String?, key: String?) {
        if (url.isNullOrEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val cacheFile = withContext(Dispatchers.IO) {
                AvatarUtil.getCacheFile(url)
            }
            if (cacheFile != null) {
                openAvatarPreview(cacheFile)
                return@launch
            }

            L.i { "[ContactDetailFragment] Avatar cache not found, downloading..." }
            ComposeDialogManager.showWait(requireActivity(), "")
            val success = withContext(Dispatchers.IO) {
                try {
                    val bytes = AvatarUtil.fetchAvatar(requireContext(), url, key.orEmpty())
                    val newCacheFile = java.io.File(
                        com.difft.android.base.utils.FileUtil.getAvatarCachePath(),
                        "avatar_${url.substringAfterLast("/")}"
                    )
                    AvatarCacheCipher.writeEncrypted(newCacheFile, bytes)
                    true
                } catch (e: Exception) {
                    L.e { "[ContactDetailFragment] Failed to download avatar: ${e.message}" }
                    false
                }
            }
            ComposeDialogManager.dismissWait()
            if (!success || !isAdded || view == null) return@launch
            val newFile = withContext(Dispatchers.IO) {
                AvatarUtil.getCacheFile(url)
            } ?: return@launch
            openAvatarPreview(newFile)
        }
    }

    private fun openAvatarPreview(cacheFile: java.io.File) {
        if (!isAdded || activity == null) return

        val list = arrayListOf<LocalMedia>().apply {
            add(AvatarPreview.localMediaFor(AvatarEncryptedProvider.DIR_AVATAR, cacheFile))
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