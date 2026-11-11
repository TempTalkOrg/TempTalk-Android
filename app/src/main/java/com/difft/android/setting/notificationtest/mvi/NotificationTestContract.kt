package com.difft.android.setting.notificationtest.mvi

import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestResults
import com.difft.android.setting.notificationtest.PrecheckBlock

object NotificationTestContract {

    
    enum class Phase {
        
        COUNTDOWN,

        
        WAIT_LOCK,

        
        POSTED,

        
        WAIT_CALL,

        FINISHING,
    }

    
    enum class Notice { NO_LOCK, POST_FAILED, VOID_UNLOCKED, VOID_NOT_LOCKED, VOID_DND, BUSY, PERSISTENCE_FAILED }

    sealed interface Dialog {
        data class Intro(val kind: NotificationTestKind) : Dialog
        data class Blocked(val block: PrecheckBlock, val fix: NotificationFix?) : Dialog
        data class Question(val kind: NotificationTestKind, val runId: String) : Dialog

        
        data class Failed(val kind: NotificationTestKind, val settingsFix: NotificationFix?) : Dialog
        data class Info(val notice: Notice) : Dialog
    }

    data class State(
        val loaded: Boolean = false,
        val results: NotificationTestResults = NotificationTestResults.NONE,
        val running: NotificationTestKind? = null,
        val phase: Phase? = null,
        val runId: String? = null,
        
        val postedAt: Long = 0,
        
        val postedFingerprint: String? = null,
        val dialog: Dialog? = null,
    )

    sealed interface Intent {
        
        data object Resume : Intent
        data class Start(val kind: NotificationTestKind) : Intent
        data object ConfirmStart : Intent
        data object Cancel : Intent
        data class Answer(val seen: Boolean, val runId: String) : Intent
        data object DismissDialog : Intent
        data class OpenFix(val fix: NotificationFix) : Intent
        data object OpenGuide : Intent
    }

    sealed interface Effect {
        data class OpenFix(val fix: NotificationFix) : Effect
        data object OpenGuide : Effect
    }
}
