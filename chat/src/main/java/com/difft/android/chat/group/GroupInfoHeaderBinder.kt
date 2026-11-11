package com.difft.android.chat.group

import com.difft.android.base.utils.pane.PageWait
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarPickLauncher
import com.difft.android.chat.common.AvatarPreviewFlight
import com.difft.android.chat.common.AvatarPreviewLauncher
import com.difft.android.chat.common.compose.CollapsingTitleBar
import com.difft.android.chat.common.compose.GroupAvatar
import com.difft.android.chat.common.compose.IdentityHeader
import com.difft.android.chat.common.compose.IdentityHeaderMode
import com.difft.android.chat.common.compose.IdentityMeta
import com.difft.android.chat.common.compose.TitleBarAction
import com.difft.android.chat.common.compose.TitleCollapseTracker
import com.difft.android.chat.databinding.ChatActivityGroupInfoBinding
import com.difft.android.chat.group.mvi.GroupInfoHeaderContract
import com.difft.android.chat.group.mvi.GroupInfoHeaderViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch













internal class GroupInfoHeaderBinder(
    private val fragment: Fragment,
    private val bindingProvider: () -> ChatActivityGroupInfoBinding,
    private val viewModelProvider: () -> GroupInfoHeaderViewModel,
    private val groupIdProvider: () -> String,
    private val onLeave: () -> Unit,
) {
    
    private val pageWait = PageWait(fragment)
    private val previewFlight = AvatarPreviewFlight(fragment)

    
    var encryptionMeta by mutableStateOf<IdentityMeta?>(null)

    
    private val outsideTapDetector by lazy {
        GestureDetector(fragment.requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val state = viewModel.state.value
                if (!state.isEditing || isTouchOnHeader(e)) return false
                viewModel.dispatch(GroupInfoHeaderContract.Intent.RequestExit(GroupInfoHeaderContract.ExitSource.OutsideTap))
                
                return state.hasUnsavedChanges
            }
        })
    }

    




    private val backWhileEditing = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            viewModel.dispatch(GroupInfoHeaderContract.Intent.RequestExit(GroupInfoHeaderContract.ExitSource.Back))
        }
    }

    private val avatarPickLauncher = AvatarPickLauncher.forFragment(fragment) { path ->
        viewModelProvider().dispatch(GroupInfoHeaderContract.Intent.AvatarPicked(path))
    }

    private val binding get() = bindingProvider()
    private val viewModel get() = viewModelProvider()

    fun setup() {
        
        val collapseTracker = TitleCollapseTracker(binding.composeTitleBar)
        binding.composeTitleBar.setContent {
            DifftTheme(applyWindowBackground = false) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                CollapsingTitleBar(
                    title = state.name,
                    collapsed = collapseTracker.collapsed,
                    onBack = { viewModel.dispatch(GroupInfoHeaderContract.Intent.RequestExit(GroupInfoHeaderContract.ExitSource.Back)) },
                    action = titleBarAction(state)
                )
            }
        }
        binding.composeIdentityHeader.setContent {
            DifftTheme(applyWindowBackground = false) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                IdentityHeader(
                    name = state.name,
                    mode = if (state.isEditing) IdentityHeaderMode.Edit else IdentityHeaderMode.Browse,
                    avatar = { avatarModifier ->
                        GroupAvatar(
                            avatarData = state.avatarData,
                            gid = groupIdProvider(),
                            size = DifftTheme.spacing.avatarLarge,
                            modifier = avatarModifier,
                            localPath = state.pendingLocalAvatarPath,
                        )
                    },
                    onAvatarClick = { viewModel.dispatch(GroupInfoHeaderContract.Intent.AvatarClick) },
                    editingName = state.editingName,
                    onEditingNameChange = { viewModel.dispatch(GroupInfoHeaderContract.Intent.ChangeName(it)) },
                    onEditingNameDone = { viewModel.dispatch(GroupInfoHeaderContract.Intent.SubmitName) },
                    nameMaxLength = GroupInfoHeaderViewModel.MAX_GROUP_NAME_LENGTH,
                    avatarEditable = state.avatarEditable,
                    nameEditable = state.nameEditable,
                    meta = encryptionMeta,
                    onAvatarBottomChanged = collapseTracker::onAvatarBottomChanged,
                )
            }
        }
    }

    
    fun observe() {
        val owner = fragment.viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.map { it.isEditing }.distinctUntilChanged().collect { editing ->
                        backWhileEditing.remove()
                        if (editing) {
                            fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.detailBackOwner(), backWhileEditing)
                        }
                    }
                }
                launch { viewModel.effect.collect { effect -> handleEffect(effect) } }
            }
        }
    }

    







    fun onDispatchTouchEvent(ev: MotionEvent): Boolean = outsideTapDetector.onTouchEvent(ev)

    private fun isTouchOnHeader(ev: MotionEvent): Boolean =
        ev.isInside(binding.composeTitleBar) || ev.isInside(binding.composeIdentityHeader)

    
    private fun titleBarAction(state: GroupInfoHeaderContract.State): TitleBarAction? = when {
        state.isEditing -> TitleBarAction.Text(fragment.getString(R.string.group_edit_name_done), sideBarIcon = com.difft.android.base.R.drawable.base_ic_check) {
            viewModel.dispatch(GroupInfoHeaderContract.Intent.SubmitName)
        }

        state.nameEditable || state.avatarEditable -> TitleBarAction.Icon(R.drawable.chat_ic_pencil, fragment.getString(R.string.group_edit)) {
            viewModel.dispatch(GroupInfoHeaderContract.Intent.EnterEdit)
        }

        else -> null
    }

    private fun handleEffect(effect: GroupInfoHeaderContract.Effect) {
        when (effect) {
            is GroupInfoHeaderContract.Effect.ShowUnsavedDialog -> showUnsavedDialog(effect.source)
            GroupInfoHeaderContract.Effect.Finish -> onLeave()
            GroupInfoHeaderContract.Effect.RequestPickAvatar -> avatarPickLauncher.launch()
            is GroupInfoHeaderContract.Effect.PreviewAvatar -> previewFlight.launch { previewWait ->
                AvatarPreviewLauncher.previewGroup(fragment.requireActivity(), effect.data, wait = previewWait)
            }

            is GroupInfoHeaderContract.Effect.ShowToast -> {
                if (effect.text.isNullOrBlank()) ToastUtil.show(R.string.chat_net_error) else ToastUtil.showLong(effect.text)
            }

            is GroupInfoHeaderContract.Effect.ShowToastRes -> ToastUtil.show(effect.resId)
            GroupInfoHeaderContract.Effect.ShowWait -> pageWait.show()
            GroupInfoHeaderContract.Effect.DismissWait -> pageWait.dismiss()
            GroupInfoHeaderContract.Effect.HideKeyboard ->
                WindowInsetsControllerCompat(fragment.requireActivity().window, binding.root).hide(WindowInsetsCompat.Type.ime())
        }
    }

    
    private fun showUnsavedDialog(source: GroupInfoHeaderContract.ExitSource) {
        val activity = fragment.requireActivity()
        fragment.dismissWithView(ComposeDialogManager.showMessageDialog(
            context = activity,
            title = "",
            message = activity.getString(R.string.group_edit_unsaved_message),
            confirmText = activity.getString(R.string.unsaved_changes_save),
            cancelText = activity.getString(R.string.unsaved_changes_discard),
            cancelable = false,
            onConfirm = { viewModel.dispatch(GroupInfoHeaderContract.Intent.SaveExit(source)) },
            onCancel = { viewModel.dispatch(GroupInfoHeaderContract.Intent.DiscardExit(source)) },
        ))
    }

    private fun MotionEvent.isInside(view: View): Boolean {
        val bounds = Rect()
        return view.getGlobalVisibleRect(bounds) && bounds.contains(rawX.toInt(), rawY.toInt())
    }
}
