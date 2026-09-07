package com.difft.android.chat.group

import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarPickLauncher
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

/**
 * Binds [GroupInfoHeaderViewModel] to the group-settings page: renders the collapsing title bar and
 * the centered identity header into their ComposeViews, drives title collapse from the header
 * position, owns the tap-outside-the-header edit exit and maps effects to UI work (picker,
 * preview, wait dialog, toasts, keyboard). Back (system or title-bar arrow) keeps its meaning while
 * editing — it leaves the page; the pending name is simply dropped (design decision).
 *
 * Construct as an Activity field: the picker registers its permission contract at construction,
 * which must happen before STARTED. Binding / ViewModel are deferred providers because both are
 * lazy on the Activity.
 */
internal class GroupInfoHeaderBinder(
    private val activity: AppCompatActivity,
    private val bindingProvider: () -> ChatActivityGroupInfoBinding,
    private val viewModelProvider: () -> GroupInfoHeaderViewModel,
    private val groupIdProvider: () -> String,
) {
    /** Encryption meta row under the name; owned by the Activity's existing encryption-row logic. */
    var encryptionMeta by mutableStateOf<IdentityMeta?>(null)

    private val collapseTracker by lazy { TitleCollapseTracker(binding.composeTitleBar) }

    // Lazy: the binder is an Activity field, so at construction the Activity has no Context yet.
    private val outsideTapDetector by lazy {
        GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val state = viewModel.state.value
                if (!state.isEditing || isTouchOnHeader(e)) return false
                viewModel.dispatch(GroupInfoHeaderContract.Intent.RequestExit(GroupInfoHeaderContract.ExitSource.OutsideTap))
                // With an unsaved change the tap only opens the prompt; the row underneath must not act.
                return state.hasUnsavedChanges
            }
        })
    }

    /**
     * Back while editing goes through the ViewModel so an unsaved name can prompt first; without one
     * back keeps its meaning and leaves the page. Registered only for the duration of an edit.
     */
    private val backWhileEditing = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            viewModel.dispatch(GroupInfoHeaderContract.Intent.RequestExit(GroupInfoHeaderContract.ExitSource.Back))
        }
    }

    private val avatarPickLauncher = AvatarPickLauncher.forActivity(activity) { path ->
        viewModelProvider().dispatch(GroupInfoHeaderContract.Intent.AvatarPicked(path))
    }

    private val binding get() = bindingProvider()
    private val viewModel get() = viewModelProvider()

    fun setup() {
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
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.map { it.isEditing }.distinctUntilChanged().collect { editing ->
                        backWhileEditing.remove()
                        if (editing) activity.onBackPressedDispatcher.addCallback(activity, backWhileEditing)
                    }
                }
                launch { viewModel.effect.collect { effect -> handleEffect(effect) } }
            }
        }
    }

    /**
     * Call from the Activity's `dispatchTouchEvent` before `super`. A single tap (platform slop,
     * long-press and multi-touch semantics) outside the title bar / identity header while editing
     * resolves the draft: an unchanged name just leaves edit mode and the touch reaches its target,
     * so the setting row keeps its own click handler; an unsaved change opens the prompt instead and
     * this returns true, telling the Activity to cancel the touch so that row does not also fire.
     * Scrolls, flings, long presses and pinches leave the draft alone.
     */
    fun onDispatchTouchEvent(ev: MotionEvent): Boolean = outsideTapDetector.onTouchEvent(ev)

    private fun isTouchOnHeader(ev: MotionEvent): Boolean =
        ev.isInside(binding.composeTitleBar) || ev.isInside(binding.composeIdentityHeader)

    /** Pencil for everyone who can edit anything; "Done" while editing; nothing when locked out. */
    private fun titleBarAction(state: GroupInfoHeaderContract.State): TitleBarAction? = when {
        state.isEditing -> TitleBarAction.Text(activity.getString(R.string.group_edit_name_done)) {
            viewModel.dispatch(GroupInfoHeaderContract.Intent.SubmitName)
        }

        state.nameEditable || state.avatarEditable -> TitleBarAction.Icon(R.drawable.chat_ic_pencil) {
            viewModel.dispatch(GroupInfoHeaderContract.Intent.EnterEdit)
        }

        else -> null
    }

    private fun handleEffect(effect: GroupInfoHeaderContract.Effect) {
        when (effect) {
            is GroupInfoHeaderContract.Effect.ShowUnsavedDialog -> showUnsavedDialog(effect.source)
            GroupInfoHeaderContract.Effect.Finish -> activity.finish()
            GroupInfoHeaderContract.Effect.RequestPickAvatar -> avatarPickLauncher.launch()
            is GroupInfoHeaderContract.Effect.PreviewAvatar -> activity.lifecycleScope.launch {
                AvatarPreviewLauncher.previewGroup(activity, effect.data)
            }

            is GroupInfoHeaderContract.Effect.ShowToast -> {
                if (effect.text.isNullOrBlank()) ToastUtil.show(R.string.chat_net_error) else ToastUtil.showLong(effect.text)
            }

            is GroupInfoHeaderContract.Effect.ShowToastRes -> ToastUtil.show(effect.resId)
            GroupInfoHeaderContract.Effect.ShowWait -> ComposeDialogManager.showWait(activity, "")
            GroupInfoHeaderContract.Effect.DismissWait -> ComposeDialogManager.dismissWait()
            GroupInfoHeaderContract.Effect.HideKeyboard ->
                WindowInsetsControllerCompat(activity.window, binding.root).hide(WindowInsetsCompat.Type.ime())
        }
    }

    /** Must pick Save or Discard; tapping outside is not an answer, so the dialog is not cancelable. */
    private fun showUnsavedDialog(source: GroupInfoHeaderContract.ExitSource) {
        ComposeDialogManager.showMessageDialog(
            context = activity,
            title = "",
            message = activity.getString(R.string.group_edit_unsaved_message),
            confirmText = activity.getString(R.string.unsaved_changes_save),
            cancelText = activity.getString(R.string.unsaved_changes_discard),
            cancelable = false,
            onConfirm = { viewModel.dispatch(GroupInfoHeaderContract.Intent.SaveExit(source)) },
            onCancel = { viewModel.dispatch(GroupInfoHeaderContract.Intent.DiscardExit(source)) },
        )
    }

    private fun MotionEvent.isInside(view: View): Boolean {
        val bounds = Rect()
        return view.getGlobalVisibleRect(bounds) && bounds.contains(rawX.toInt(), rawY.toInt())
    }
}
