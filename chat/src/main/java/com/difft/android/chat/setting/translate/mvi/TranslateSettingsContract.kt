package com.difft.android.chat.setting.translate.mvi

import androidx.annotation.StringRes
import com.difft.android.chat.translate.model.FailureReason

/**
 * MVI contract for the conversation auto-translate settings screen.
 *
 * The screen owns the single write path for `RoomModel.translateSetting`: picking an option writes
 * the row, broadcasts the change and asks for the translation pack. Everything else in the app
 * reads that value.
 */
object TranslateSettingsContract {

    sealed interface Intent {
        /** 0 off, 1 Chinese, 2 English — the values stored in `RoomModel.translateSetting`. */
        data class Select(val value: Int) : Intent

        /** Settings-screen retry: clears the backoff window and restarts a wedged download. */
        data object RetryDownload : Intent

        data object CancelDownload : Intent
    }

    data class State(
        /** False until the stored value has been read; the option rows stay inert meanwhile. */
        val loaded: Boolean = false,
        val selected: Int = 0,
        /**
         * True while a selection is being written. [selected] only moves once the row is written, so
         * the rows are dimmed and inert for that window — otherwise a second tap would be silently
         * dropped with nothing on screen to say so.
         */
        val pending: Boolean = false,
        /** One row per model the current target needs; empty while the setting is off. */
        val models: List<ModelRow> = emptyList(),
    ) {
        data class ModelRow(
            val modelCode: String,
            val phase: Phase,
            val bytesSoFar: Long = 0,
            val totalBytes: Long = -1,
            val reason: FailureReason? = null,
            val stuck: Boolean = false,
        )

        enum class Phase { IDLE, DOWNLOADING, READY, FAILED }
    }

    sealed interface Effect {
        data class ShowToast(@param:StringRes val res: Int) : Effect
    }
}
