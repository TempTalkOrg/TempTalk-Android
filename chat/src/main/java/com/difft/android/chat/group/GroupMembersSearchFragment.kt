package com.difft.android.chat.group

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailKeyboard
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.hideDetailKeyboardBeforeLeaving
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.chat.contacts.contactsdetail.showContactCard
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.group.GroupMemberListContract.Effect
import com.difft.android.chat.group.GroupMemberListContract.Intent as ListIntent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch





@AndroidEntryPoint
class GroupMembersSearchFragment : Fragment() {

    private val viewModel: GroupMemberListViewModel by viewModels()

    private val groupId: String by lazy { arguments?.getString(ARG_GROUP_ID).orEmpty() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val padsBottom = containerPadsBottom()
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = false, useFlatBackground = true) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    GroupMemberListScreen(
                        state = state,
                        onQueryChange = { viewModel.dispatch(ListIntent.QueryChanged(it)) },
                        onOpen = { viewModel.dispatch(ListIntent.Open(it)) },
                        onBack = ::leave,
                        modifier = Modifier.detailPageInsets(padsBottom, ime = true),
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        
        if (ownsWindow()) requireActivity().window.setBackgroundDrawableResource(com.difft.android.base.R.color.bg1)
        if (groupId.isEmpty()) {
            L.w { "[GroupMemberList] page without a group id" }
            detailNavigation().pop(this)
            return
        }
        
        detailKeyboard(showOnFirstOpen = false, focus = {}, savedInstanceState = savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is Effect.OpenContact -> {
                            hideDetailKeyboardBeforeLeaving()
                            showContactCard(this@GroupMembersSearchFragment, effect.id,
                                sourceType = FriendSourceType.FROM_GROUP, source = effect.groupId)
                        }
                    }
                }
            }
        }
    }

    private fun leave() {
        hideDetailKeyboardBeforeLeaving()
        detailNavigation().pop(this)
    }

    companion object {
        private const val ARG_GROUP_ID = GroupMemberListViewModel.ARG_GROUP_ID

        fun arguments(groupId: String): Bundle = Bundle().apply { putString(ARG_GROUP_ID, groupId) }

        fun newInstance(groupId: String): GroupMembersSearchFragment =
            GroupMembersSearchFragment().apply { arguments = arguments(groupId) }
    }
}


class GroupMembersSearchDestination(private val groupId: String) : DetailDestination {
    override fun newFragment(): Fragment = GroupMembersSearchFragment.newInstance(groupId)
    override fun shellIntent(context: Context): Intent = GroupMembersSearchActivity.intent(context, groupId)
    override val subjectId: String get() = groupId
}
