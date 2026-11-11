package com.difft.android.call.ui.invite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.handler.InviteCallHandler
import com.difft.android.call.LCallViewModel
import com.difft.android.call.ui.screenshare.isCallImmersive
import com.difft.android.call.ui.tabletop.LocalCallTabletop
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

enum class InviteScreenState {
    INITIAL,
    ADD_MEMBERS,
    CONFIRM
}

enum class InviteViewState {
    DISMISS,
    INVITE,
}








@OptIn(FlowPreview::class)
@Composable
fun MeetingInviteScreen(
    callViewModel: LCallViewModel,
    inviteCallHandler: InviteCallHandler?,
    onInviteViewAction: (InviteViewState) -> Unit
) {
    if (inviteCallHandler == null) { return }

    val context = LocalContext.current
    val showInviteViewEnable by callViewModel.callUiController.showInviteViewEnable.collectAsState(false)
    val latestInviteHandler = rememberUpdatedState(inviteCallHandler)
    val latestInviteAction = rememberUpdatedState(onInviteViewAction)
    
    val tabletop = LocalCallTabletop.current
    val dockHeightPx = tabletop?.dockHeightPx
    val latestDockHeightPx = rememberUpdatedState(dockHeightPx)
    
    val statusBarHidden = isCallImmersive()
    val latestStatusBarHidden = rememberUpdatedState(statusBarHidden)

    LaunchedEffect(context) {
        val activity = context as? androidx.fragment.app.FragmentActivity ?: return@LaunchedEffect
        snapshotFlow { showInviteViewEnable }
            .distinctUntilChanged()
            .debounce(100)
            .collectLatest { shouldShow ->
                if (shouldShow) {
                    val existingFragment = activity.supportFragmentManager
                        .findFragmentByTag("MeetingInviteBottomSheet") as? MeetingInviteBottomSheetFragment

                    if (existingFragment == null) {
                        val fragment = MeetingInviteBottomSheetFragment.newInstance()
                        fragment.setDockHeight(latestDockHeightPx.value)
                        fragment.setStatusBarHidden(latestStatusBarHidden.value)
                        latestInviteHandler.value?.let { handler ->
                            fragment.setInviteCallHandler(handler)
                        }
                        fragment.onInviteAction = { action ->
                            callViewModel.callUiController.setShowInviteViewEnable(false)
                            latestInviteAction.value.invoke(action)
                        }
                        try {
                            fragment.show(activity.supportFragmentManager, "MeetingInviteBottomSheet")
                        } catch (e: IllegalStateException) {
                            L.e { "[call] MeetingInvite show fragment error: ${e.message}" }
                        }
                    }
                } else {
                    val existingFragment = activity.supportFragmentManager
                        .findFragmentByTag("MeetingInviteBottomSheet") as? MeetingInviteBottomSheetFragment
                    existingFragment?.dismiss()
                }
            }
    }

    
    LaunchedEffect(context, dockHeightPx) {
        val activity = context as? androidx.fragment.app.FragmentActivity ?: return@LaunchedEffect
        (activity.supportFragmentManager.findFragmentByTag("MeetingInviteBottomSheet") as? MeetingInviteBottomSheetFragment)
            ?.setDockHeight(dockHeightPx)
    }
    LaunchedEffect(context, statusBarHidden) {
        val activity = context as? androidx.fragment.app.FragmentActivity ?: return@LaunchedEffect
        (activity.supportFragmentManager.findFragmentByTag("MeetingInviteBottomSheet") as? MeetingInviteBottomSheetFragment)
            ?.setStatusBarHidden(statusBarHidden)
    }
}