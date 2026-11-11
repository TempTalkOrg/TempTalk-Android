package com.difft.android.chat.focus

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.di.AppCoroutineScope
import com.difft.android.chat.focus.FocusRequestPlan.ConfirmSwitch
import com.difft.android.chat.focus.FocusRequestPlan.DurationSheet
import com.difft.android.chat.focus.FocusRequestPlan.None
import com.difft.android.chat.focus.FocusRequestPlan.SwitchDirect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow


internal sealed interface FocusRequestPlan {
    data object None : FocusRequestPlan

    
    data object DurationSheet : FocusRequestPlan

    
    data class ConfirmSwitch(val currentGid: String) : FocusRequestPlan

    
    data object SwitchDirect : FocusRequestPlan
}

internal fun planFocusRequest(focus: EffectiveFocus, gid: String): FocusRequestPlan = when {
    !focus.featureEnabled -> None
    !focus.active -> DurationSheet
    focus.isFocusedGroup(gid) -> None
    focus.invalid -> SwitchDirect
    else -> ConfirmSwitch(focus.focusGid.orEmpty())
}






internal suspend fun runSelectingWrite(gid: String?, deletedRows: FocusDeletedRowStore, call: suspend () -> FocusResult): FocusResult {
    val result = call()
    if (gid != null && result is FocusResult.Success) deletedRows.clear(gid)
    return result
}







@Singleton
class FocusActionLauncher @Inject constructor(
    private val repository: GroupFocusRepository,
    private val deletedRows: FocusDeletedRowStore,
    private val groups: FocusGroupDirectory,
    private val activityProvider: ActivityProvider,
    @param:AppCoroutineScope private val scope: CoroutineScope,
) {

    
    fun requestFocus(activity: ComponentActivity, gid: String, groupName: String) {
        when (val plan = planFocusRequest(repository.effectiveNow(), gid)) {
            None -> Unit
            DurationSheet -> showDurationSheet(activity, groupName) { duration -> write(activity, "start", gid) { repository.start(gid, duration.seconds) } }
            SwitchDirect -> write(activity, "reselect", gid) { repository.switchGroup(gid) }
            is ConfirmSwitch -> activity.lifecycleScope.launch {
                val oldName = groups.name(plan.currentGid).orEmpty()
                FocusDialogs.confirmSwitch(activity, groupName, oldName) {
                    write(activity, "switch", gid) { repository.switchGroup(gid) }
                }
            }
        }
    }

    





    fun applyPickedGroup(activity: ComponentActivity, gid: String, onDone: (Boolean) -> Unit = {}) {
        if (!repository.featureEnabled) {
            onDone(true)
            return
        }
        if (repository.effectiveNow().active) {
            write(activity, "pick-switch", gid, onDone = { onDone(true) }) { repository.switchGroup(gid) }
        } else {
            activity.lifecycleScope.launch {
                val name = groups.name(gid).orEmpty()
                showDurationSheet(activity, name, onCancel = { onDone(false) }) { duration ->
                    write(activity, "pick-start", gid, onDone = { onDone(true) }) { repository.start(gid, duration.seconds) }
                }
            }
        }
    }

    



    fun applyReselectedGroup(gid: String) {
        if (!repository.featureEnabled || !repository.effectiveNow().invalid) {
            endReselect()
            return
        }
        beginReselect()
        scope.launch {
            try {
                val result = runSelectingWrite(gid, deletedRows) { repository.switchGroup(gid) }
                if (result is FocusResult.Failure) {
                    L.w { "[FocusAction] reselect failed error=${result.error}" }
                    showFocusError()
                }
            } finally {
                endReselect()
            }
        }
    }

    private val _reselectInFlight = MutableStateFlow(false)

    




    val reselectInFlight: StateFlow<Boolean> = _reselectInFlight.asStateFlow()

    fun beginReselect() {
        _reselectInFlight.value = true
    }

    fun endReselect() {
        _reselectInFlight.value = false
    }

    
    fun endFocus(activity: ComponentActivity) {
        write(activity, "end") { repository.end() }
    }

    
    fun changeEndTime(activity: ComponentActivity) {
        val focus = repository.effectiveNow()
        val gid = focus.focusGid ?: return
        activity.lifecycleScope.launch {
            val name = groups.name(gid).orEmpty()
            showDurationSheet(activity, name) { duration -> write(activity, "until") { repository.changeUntil(duration.seconds) } }
        }
    }

    
    fun openNotificationSettings(activity: ComponentActivity) {
        val target = activityProvider.getActivityClass(ActivityType.NOTIFICATION_SETTINGS)
        activity.startActivity(Intent(activity, target))
    }

    private fun showDurationSheet(
        activity: ComponentActivity,
        groupName: String,
        onCancel: () -> Unit = {},
        onSelect: (FocusDuration) -> Unit,
    ) {
        val focus = repository.effectiveNow()
        val outcome = DurationSheetOutcome(onCancel)
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(activity, onDismiss = outcome::onDismissed) {
            FocusDurationSheet(
                groupName = groupName,
                description = focusSheetDescription(focus),
                onSelect = { duration ->
                    outcome.markChosen()
                    dialog?.dismiss()
                    onSelect(duration)
                },
                onCancel = { dialog?.dismiss() },
            )
        }
    }

    private fun write(
        activity: ComponentActivity,
        op: String,
        selectedGid: String? = null,
        onDone: () -> Unit = {},
        call: suspend () -> FocusResult,
    ) {
        activity.lifecycleScope.launch {
            val token = ComposeDialogManager.showWait(activity, "")
            try {
                val result = scope.async { runSelectingWrite(selectedGid, deletedRows, call) }.await()
                if (result is FocusResult.Failure) {
                    L.w { "[FocusAction] $op failed error=${result.error}" }
                    showFocusError()
                }
            } finally {
                ComposeDialogManager.dismissWait(token)
            }
            onDone()
        }
    }
}





internal class DurationSheetOutcome(private val onCancel: () -> Unit) {
    private var settled = false

    
    fun markChosen() {
        settled = true
    }

    fun onDismissed() {
        if (settled) return
        settled = true
        onCancel()
    }
}


fun showFocusError() = ToastUtil.show(R.string.operation_failed)
