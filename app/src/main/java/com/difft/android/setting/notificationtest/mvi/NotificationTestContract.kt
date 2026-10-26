package com.difft.android.setting.notificationtest.mvi

import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestResults
import com.difft.android.setting.notificationtest.PrecheckBlock

object NotificationTestContract {

    /** Where the running test is; the page shows one test at a time. */
    enum class Phase {
        /** Banner test: counting down before the notification is posted. */
        COUNTDOWN,

        /** Lock-screen and call tests: waiting for the user to lock the screen. */
        WAIT_LOCK,

        /** The notification is out; the banner and lock-screen tests wait to ask the user. */
        POSTED,

        /** Call test: waiting for the test call screen to open. */
        WAIT_CALL,
    }

    /** One-shot notices that end a test without a result. */
    enum class Notice { NO_LOCK, POST_FAILED, VOID_UNLOCKED, VOID_NOT_LOCKED, VOID_DND }

    sealed interface Dialog {
        data class Intro(val kind: NotificationTestKind) : Dialog
        data class Blocked(val block: PrecheckBlock, val fix: NotificationFix?) : Dialog
        data class Question(val kind: NotificationTestKind) : Dialog

        /** A recorded failure; [settingsFix] is the page offered next to the setup guide, if any. */
        data class Failed(val kind: NotificationTestKind, val settingsFix: NotificationFix?) : Dialog
        data class Info(val notice: Notice) : Dialog
    }

    data class State(
        val loaded: Boolean = false,
        val results: NotificationTestResults = NotificationTestResults.NONE,
        val running: NotificationTestKind? = null,
        val phase: Phase? = null,
        /** Epoch ms when the running test posted its notification; 0 before that. */
        val postedAt: Long = 0,
        /** Settings fingerprint taken when the notification was posted; results are stored with it (#1350). */
        val postedFingerprint: String? = null,
        val dialog: Dialog? = null,
    )

    sealed interface Intent {
        /** The page became visible again: reloads results and asks a pending question. */
        data object Resume : Intent
        data class Start(val kind: NotificationTestKind) : Intent
        data object ConfirmStart : Intent
        data object Cancel : Intent
        data class Answer(val seen: Boolean) : Intent
        data object DismissDialog : Intent
        data class OpenFix(val fix: NotificationFix) : Intent
        data object OpenGuide : Intent
    }

    sealed interface Effect {
        data class OpenFix(val fix: NotificationFix) : Effect
        data object OpenGuide : Effect
    }
}
