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

/** What a "focus this group" request must do given the current focus state. */
internal sealed interface FocusRequestPlan {
    data object None : FocusRequestPlan

    /** Not focusing: pick an end time, then start. */
    data object DurationSheet : FocusRequestPlan

    /** Focusing another valid group: confirm (D2), then switch. */
    data class ConfirmSwitch(val currentGid: String) : FocusRequestPlan

    /** Focus is in the invalid state: reselect without asking. */
    data object SwitchDirect : FocusRequestPlan
}

internal fun planFocusRequest(focus: EffectiveFocus, gid: String): FocusRequestPlan = when {
    !focus.featureEnabled -> None
    !focus.active -> DurationSheet
    focus.isFocusedGroup(gid) -> None
    focus.invalid -> SwitchDirect
    else -> ConfirmSwitch(focus.focusGid.orEmpty())
}

/**
 * Runs a focus write that selects [gid] as the focus group ([gid] null for the others). A local explicit
 * selection restores a deleted focus row, so the group's deleted-row record goes once the write succeeded;
 * a failed write changes nothing.
 */
internal suspend fun runSelectingWrite(gid: String?, deletedRows: FocusDeletedRowStore, call: suspend () -> FocusResult): FocusResult {
    val result = call()
    if (gid != null && result is FocusResult.Success) deletedRows.clear(gid)
    return result
}

/**
 * The focus actions every entry point shares (group settings, Me > Notifications, later the chat
 * list): start with the duration sheet, switch with D2, end, change the end time. Writes run on the
 * app scope so a finishing screen does not cancel a request in flight; failures show the generic
 * error toast. Success is silent (no toast): the cached state flow updates every consumer.
 */
@Singleton
class FocusActionLauncher @Inject constructor(
    private val repository: GroupFocusRepository,
    private val deletedRows: FocusDeletedRowStore,
    private val groups: FocusGroupDirectory,
    private val activityProvider: ActivityProvider,
    @param:AppCoroutineScope private val scope: CoroutineScope,
) {

    /** Focus [gid] from a "focus this group" entry (settings switch, list menu). */
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

    /**
     * A group chosen in the picker (Me > Notifications): while focusing, or in the invalid state, the
     * choice is the new focus group at once (no D2); otherwise the end time is asked first.
     */
    fun applyPickedGroup(activity: ComponentActivity, gid: String) {
        if (!repository.featureEnabled) return
        if (repository.effectiveNow().active) {
            write(activity, "pick-switch", gid) { repository.switchGroup(gid) }
        } else {
            activity.lifecycleScope.launch {
                val name = groups.name(gid).orEmpty()
                showDurationSheet(activity, name) { duration -> write(activity, "pick-start", gid) { repository.start(gid, duration.seconds) } }
            }
        }
    }

    /**
     * Reselect after an invalid focus, from the picker that is about to finish: the switch and its
     * error toast run on the app scope, so closing the picker loses neither.
     */
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

    /**
     * True from the "Reselect" tap until the picker's answer has landed (a pick's switch finished, or
     * the cancel was recorded): the invalid dialog stays away for the whole span, so "End Focus" can
     * neither race the switch nor reappear behind the picker.
     */
    val reselectInFlight: StateFlow<Boolean> = _reselectInFlight.asStateFlow()

    fun beginReselect() {
        _reselectInFlight.value = true
    }

    fun endReselect() {
        _reselectInFlight.value = false
    }

    /** Ends focus at once, without a confirmation. */
    fun endFocus(activity: ComponentActivity) {
        write(activity, "end") { repository.end() }
    }

    /** Reopens the duration sheet for the running focus. */
    fun changeEndTime(activity: ComponentActivity) {
        val focus = repository.effectiveNow()
        val gid = focus.focusGid ?: return
        activity.lifecycleScope.launch {
            val name = groups.name(gid).orEmpty()
            showDurationSheet(activity, name) { duration -> write(activity, "until") { repository.changeUntil(duration.seconds) } }
        }
    }

    /** Opens Me > Notifications, where the focus settings of the other groups live. */
    fun openNotificationSettings(activity: ComponentActivity) {
        val target = activityProvider.getActivityClass(ActivityType.NOTIFICATION_SETTINGS)
        activity.startActivity(Intent(activity, target))
    }

    private fun showDurationSheet(activity: ComponentActivity, groupName: String, onSelect: (FocusDuration) -> Unit) {
        val focus = repository.effectiveNow()
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(activity) {
            FocusDurationSheet(
                groupName = groupName,
                description = focusSheetDescription(focus),
                onSelect = { duration ->
                    dialog?.dismiss()
                    onSelect(duration)
                },
                onCancel = { dialog?.dismiss() },
            )
        }
    }

    private fun write(activity: ComponentActivity, op: String, selectedGid: String? = null, call: suspend () -> FocusResult) {
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
        }
    }
}

/** The existing generic failure toast; every focus write failure (network or business) uses it. */
fun showFocusError() = ToastUtil.show(R.string.operation_failed)
