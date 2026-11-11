package com.difft.android.chat.contacts.groupcard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.ui.compose.FloatingIconButton
import com.difft.android.base.ui.sidechrome.PublishSideChrome
import com.difft.android.base.ui.sidechrome.SideChromeModel
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Action
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Effect
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Intent
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardViewModel
import com.difft.android.chat.group.GroupInfoDestination
import com.difft.android.chat.group.GroupMembersSearchDestination
import com.difft.android.chat.group.GroupSelectMemberDestination
import com.difft.android.chat.group.mvi.GroupPageContract.MemberAction
import com.difft.android.chat.ui.ChatBackgroundDrawable
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GroupCardFragment : Fragment() {
    private val viewModel: GroupCardViewModel by viewModels()
    @Inject lateinit var callLauncher: GroupCardCallLauncher

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val sheet = parentFragment is GroupCardBottomSheetDialogFragment
        val padsBottom = containerPadsBottom()
        return ComposeView(requireContext()).apply {
            if (!sheet) background = ChatBackgroundDrawable(context, this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = false) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    
                    
                    val showBack = !sheet && showsDetailBackButton()
                    val goBack: () -> Unit = { detailNavigation().pop(this@GroupCardFragment) }
                    val onRail = !sheet && PublishSideChrome(SideChromeModel(back = goBack.takeIf { showBack }))
                    Box(
                        modifier = Modifier.detailPageInsets(padsBottom).then(
                            if (sheet) Modifier.fillMaxWidth() else Modifier.fillMaxSize().padding(DifftTheme.contacts.cardPadding),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        GroupCard(state, viewModel::dispatch,
                            Modifier.widthIn(max = DifftTheme.contacts.cardMaxWidth),
                            floating = !sheet)
                        if (showBack && !onRail) FloatingIconButton(
                            iconRes = com.difft.android.base.R.drawable.chative_ic_back,
                            contentDescription = stringResource(R.string.contact_remove_dialog_cancel),
                            onClick = goBack,
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is Effect.Navigate -> navigate(effect)
                        is Effect.Call -> callLauncher.launch(requireActivity(), effect.groupId, effect.name,
                            viewLifecycleOwner.lifecycleScope, { ToastUtil.show(it) })
                        Effect.CannotSpeak -> ToastUtil.show(getString(R.string.group_only_moderators_can_speak_tip))
                        Effect.CannotInvite -> ToastUtil.show(getString(R.string.group_permission_denied))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.dispatch(Intent.Refresh)
    }

    private fun navigate(effect: Effect.Navigate) {
        val navigation = detailNavigation()
        when (effect.action) {
            Action.MESSAGE -> navigation.openConversation(ConversationTarget(ConversationTarget.Kind.GROUP, effect.groupId))
            Action.INVITE -> navigation.push(this, GroupSelectMemberDestination(effect.groupId, MemberAction.ADD))
            Action.MORE -> navigation.push(this, GroupInfoDestination(effect.groupId))
            Action.MEMBERS -> navigation.push(this, GroupMembersSearchDestination(effect.groupId))
            Action.CALL -> Unit
        }
        (parentFragment as? GroupCardBottomSheetDialogFragment)?.dismiss()
    }

    companion object {
        fun newInstance(gid: String, preloadKey: String? = null): GroupCardFragment = GroupCardFragment().apply {
            arguments = Bundle().apply {
                putString(GroupCardViewModel.ARG_GROUP_ID, gid)
                putString(GroupCardViewModel.ARG_PRELOAD_KEY, preloadKey)
            }
        }
    }
}
