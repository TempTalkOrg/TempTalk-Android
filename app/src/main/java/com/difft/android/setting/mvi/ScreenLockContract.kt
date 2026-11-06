package com.difft.android.setting.mvi

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

object ScreenLockContract {
    enum class Kind { Pattern, Passcode }
    enum class Action { Enable, Delete, BindOnly }
    enum class Stage { ChoosingBinding, ReadyForBinding, AwaitingBinding, ReadyForVerification, AwaitingVerification, ReadyForSetup, AwaitingSetup }
    @Parcelize
    data class Pending(
        val requestId: String,
        val accountId: String,
        val kind: Kind,
        val action: Action,
        val stage: Stage,
        val requiresVerification: Boolean,
        val bindingType: String? = null,
        val verifiedInProcess: Boolean = false,
    ) : Parcelable
    data class State(
        val pattern: Boolean = false,
        val passcode: Boolean = false,
        val showPath: Boolean = true,
        val timeout: Int? = null,
        val pending: Pending? = null,
        val promptBinding: Boolean = false,
    )
    sealed interface Intent {
        data object Refresh : Intent
        data class Toggle(val kind: Kind, val enabled: Boolean) : Intent
        data class SetShowPath(val enabled: Boolean) : Intent
        data class ChooseBinding(val type: String) : Intent
        data object DismissBinding : Intent
        data object BeginBinding : Intent
        data class Result(val requestId: String?, val stage: Stage, val success: Boolean) : Intent
        data object DismissPrompt : Intent
    }
    sealed interface Effect {
        data class Disabled(val kind: Kind) : Effect
    }
}
