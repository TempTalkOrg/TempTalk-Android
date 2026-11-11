package com.difft.android.chat.contacts.contactsdetail

import com.difft.android.base.utils.pane.PageWait
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.filterNotNull
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager
import com.difft.android.chat.call.LChatToCallController
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import com.difft.android.base.utils.pane.isInDetailStack
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.chat.R
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.difft.android.chat.common.AvatarPreviewFlight
import com.difft.android.chat.common.AvatarPreviewLauncher
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactRemarkEditContract
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactRemarkEditViewModel
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.ui.ChatInputFocusable
import com.difft.android.chat.ui.ChatPopupActivity
import com.difft.android.chat.ui.GroupInCommonDestination
import com.difft.android.chat.ui.SelectChatsUtils
import com.difft.android.chat.ui.SingleChatSettingDestination
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.messageserialization.db.store.getEffectiveAvatarJson
import com.difft.android.chat.util.Util
import com.difft.android.base.security.SafeLinkOpener
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import dagger.Lazy

@AndroidEntryPoint
class ContactDetailFragment : Fragment() {
    
    private val pageWait = PageWait(this)
    private val previewFlight = AvatarPreviewFlight(this)

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    lateinit var callDataManagerLazy: Lazy<CallDataManager>

    @Inject
    lateinit var contactorCacheManager: ContactorCacheManager

    @Inject
    lateinit var callManager: LChatToCallController

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    private val callDataManager: CallDataManager by lazy {
        callDataManagerLazy.get()
    }

    companion object {
        





        internal fun isSheetOverList(isPopupMode: Boolean, inHome: Boolean, conversationOnScreen: String?): Boolean =
            isPopupMode && inHome && conversationOnScreen == null

        




        internal fun closeCard(card: Fragment, contactGone: Boolean = false) {
            val sheet = card.parentFragment as? com.google.android.material.bottomsheet.BottomSheetDialogFragment
            when {
                sheet != null -> sheet.dismiss()
                contactGone -> card.detailNavigation().closeConversation(card)
                else -> card.detailNavigation().pop(card)
            }
        }

        
        const val ARG_CONTACT_ID = "ARG_CONTACT_ID"
        private const val ARG_CUSTOM_ID = "ARG_CUSTOM_ID"
        private const val ARG_CONTACT_NAME = "ARG_CONTACT_NAME"
        private const val ARG_SOURCE_TYPE = "ARG_SOURCE_TYPE"
        private const val ARG_SOURCE = "ARG_SOURCE"
        private const val ARG_AVATAR = "ARG_AVATAR"
        private const val ARG_JOINED_AT = "ARG_JOINED_AT"
        private const val ARG_PRELOAD_KEY = "ARG_PRELOAD_KEY"

        fun newInstance(
            contactId: String,
            customId: String? = null,
            contactName: String? = null,
            sourceType: String? = null,
            source: String? = null,
            avatar: String? = null,
            joinedAt: String? = null,
            preloadKey: String? = null,
        ): ContactDetailFragment {
            return ContactDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PRELOAD_KEY, preloadKey)
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

    private val detailViewModel: com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailViewModel by viewModels()
    private val uiState get() = detailViewModel.state.value.ui
    private val mContactor get() = uiState.contactor
    private val commonGroupsCount get() = uiState.commonGroupsCount

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

    private val isWeakPending get() = uiState.isWeakPending

    private val isPopupMode: Boolean
        get() = parentFragment is com.google.android.material.bottomsheet.BottomSheetDialogFragment

    private val remarkEditViewModel: ContactRemarkEditViewModel by viewModels()

    
    private val remarkEditBinder = ContactRemarkEditBinder(this, { remarkEditViewModel }, ::performClose)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val padsBottom = containerPadsBottom()
        return ComposeView(requireContext()).apply {
            if (!isPopupMode) background = com.difft.android.chat.ui.ChatBackgroundDrawable(context, this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                
                DifftTheme(applyWindowBackground = false) {
                    val detail by detailViewModel.state.collectAsStateWithLifecycle()
                    val uiState = detail.ui
                    val remarkEdit by remarkEditViewModel.state.collectAsStateWithLifecycle()
                    Box(Modifier.detailPageInsets(padsBottom)) {
                    ContactDetailScreen(
                        uiState = uiState.copy(
                            commonGroupsCount = commonGroupsCount,
                            actionsEnabled = !detail.submitting,
                            isEditingRemark = remarkEdit.isEditing,
                            editingRemarkName = remarkEdit.editingName,
                            quickFillName = remarkEdit.quickFillName,
                            showQuickFill = remarkEdit.showQuickFill,
                        ),
                        isPopupMode = isPopupMode,
                        showBackButton = showsDetailBackButton(),
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
                        onRemoveNowClick = ::confirmRemoveNow,
                        onEditingRemarkNameChange = { remarkEditViewModel.dispatch(ContactRemarkEditContract.Intent.ChangeName(it)) },
                        onSubmitRemark = { remarkEditViewModel.dispatch(ContactRemarkEditContract.Intent.SubmitName) },
                        onAvatarClickInEdit = { remarkEditViewModel.dispatch(ContactRemarkEditContract.Intent.AvatarClick) },
                        onQuickFillRemark = { remarkEditViewModel.dispatch(ContactRemarkEditContract.Intent.QuickFillName) }
                    )
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (TextUtils.isEmpty(contactId)) {
            L.w { "[ContactDetail] card without a contact id" }
            if (isInDetailStack()) detailNavigation().pop(this)
            return
        }

        detailViewModel.dispatch(com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Intent.Load(
            com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Input(
                contactId, customId, contactName, avatar, joinedAt, sourceType, source,
            ),
            preloadKey = arguments?.getString(ARG_PRELOAD_KEY),
        ))
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    detailViewModel.state.map { it.ui.contactor }.filterNotNull().distinctUntilChanged().collect {
                        remarkEditViewModel.dispatch(ContactRemarkEditContract.Intent.Load(it))
                    }
                }
                launch { detailViewModel.effect.collect(::handleDetailEffect) }
            }
        }
        remarkEditBinder.observe()
    }

    override fun onResume() {
        super.onResume()
        if (uiState.isRelationResolved) detailViewModel.dispatch(
            com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Intent.Refresh
        )
    }

    
    private fun handleCloseClick() {
        remarkEditViewModel.dispatch(ContactRemarkEditContract.Intent.RequestClose)
    }

    private fun performClose() = closeCard(this)

    private fun handleMoreClick() {
        pushFromCard(SingleChatSettingDestination(contactId))
    }

    private fun pushFromCard(destination: DetailDestination) {
        val sheet = parentFragment as? com.google.android.material.bottomsheet.BottomSheetDialogFragment
        val overConversation = (sheet as? ContactDetailBottomSheetDialogFragment)?.overConversation
        ContactCardNavigation.push(this, destination, sheet, overConversation)
    }

    private fun handleEditClick() {
        remarkEditViewModel.dispatch(ContactRemarkEditContract.Intent.EnterEdit)
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
        val sheetOverList = isSheetOverList(
            isPopupMode = isPopupMode,
            inHome = navigationCallback != null,
            conversationOnScreen = navigationCallback?.currentConversationOnScreen(),
        )
        val fullConversation = (parentFragment as? ContactDetailBottomSheetDialogFragment)?.opensFullConversation == true
        if (isPopupMode) {
            (parentFragment as? androidx.fragment.app.DialogFragment)?.dismiss()
        }
        when {
            isPopupMode && !sheetOverList && !fullConversation -> {
                val focusable = activity as? ChatInputFocusable
                if (focusable?.focusCurrentChatInputIfMatches(contactId) != true) {
                    ChatPopupActivity.startActivity(
                        requireContext(),
                        contactId,
                        sourceType = sourceType,
                        source = source
                    )
                }
            }
            
            
            else -> detailNavigation().openConversation(
                ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, contactId, sourceType = sourceType, source = source)
            )
        }
    }

    private fun handleCommonGroupsClick() {
        if (commonGroupsCount > 0) {
            pushFromCard(GroupInCommonDestination(contactId))
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

    private fun requestAddFriend() = detailViewModel.dispatch(
        com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Intent.AddFriend
    )

    private fun handleDetailEffect(effect: com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Effect) {
        when (effect) {
            com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Effect.ShowWait -> pageWait.show()
            com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Effect.DismissWait -> pageWait.dismiss()
            com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Effect.FriendAdded -> { pageWait.dismiss(); navigateToChat() }
            is com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Effect.AccountUnavailable -> {
                if (effect.removable) showAccountUnavailableDialog()
                else ToastUtil.show(getString(R.string.weak_contact_account_unavailable_message))
            }
            is com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Effect.Failed ->
                ToastUtil.show(effect.message ?: getString(R.string.chat_net_error))
            com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Effect.Removed -> closeCard(this, contactGone = true)
            com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Effect.RemoveFailed -> ToastUtil.show(getString(R.string.weak_contact_remove_now_failed))
        }
    }

    
    private fun showAccountUnavailableDialog() {
        dismissWithView(ComposeDialogManager.showMessageDialog(
            context = requireContext(),
            title = getString(R.string.weak_contact_account_unavailable_title),
            message = getString(R.string.weak_contact_account_unavailable_message),
            confirmText = getString(R.string.contact_remove),
            cancelText = getString(R.string.weak_contact_account_unavailable_ok),
            confirmButtonColor = ColorTokens.Error, 
            onConfirm = { requestRemoveNow() }
        ))
    }

    private fun confirmRemoveNow() {
        if (!uiState.presentation.showDelete) return
        dismissWithView(ComposeDialogManager.showMessageDialog(
            context = requireContext(),
            title = getString(R.string.contact_remove),
            message = getString(R.string.contacts_card_remove_confirmation),
            confirmText = getString(R.string.contact_remove),
            cancelText = getString(R.string.contact_remove_dialog_cancel),
            confirmButtonColor = ColorTokens.Error,
            onConfirm = ::requestRemoveNow,
        ))
    }

    
    private fun requestRemoveNow() = detailViewModel.dispatch(
        com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Intent.RemoveNow
    )

    
    private fun handleAvatarClick() {
        val contact = mContactor ?: return
        val data = contact.getEffectiveAvatarJson()?.getContactAvatarData() ?: return
        previewAvatar(data.getContactAvatarUrl(), data.encKey)
    }

    
    private fun handleOriginalAvatarClick() {
        val contact = mContactor ?: return
        val data = contact.avatar?.getContactAvatarData() ?: return
        previewAvatar(data.getContactAvatarUrl(), data.encKey)
    }

    private fun previewAvatar(url: String?, key: String?) {
        val host = activity ?: return
        previewFlight.launch { previewWait ->
            AvatarPreviewLauncher.previewContact(host, url, key, wait = previewWait)
        }
    }

}
