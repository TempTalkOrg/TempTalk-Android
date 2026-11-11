package com.difft.android.chat.setting.translate.mvi

import androidx.annotation.StringRes
import com.difft.android.chat.translate.model.FailureReason








object TranslateSettingsContract {

    sealed interface Intent {
        
        data class Select(val value: Int) : Intent

        
        data object RetryDownload : Intent

        data object CancelDownload : Intent
    }

    data class State(
        
        val loaded: Boolean = false,
        val selected: Int = 0,
        




        val pending: Boolean = false,
        
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
