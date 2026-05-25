package com.difft.android.call

import com.difft.android.base.call.CallRole
import com.difft.android.base.user.CallConfig
import com.difft.android.call.data.VoicePreset
import dagger.assisted.AssistedFactory

@AssistedFactory
interface LCallViewModelFactory {
    fun create(
        e2eeEnable: Boolean,
        callIntent: CallIntent,
        callConfig: CallConfig,
        callRole: CallRole,
        initialVoicePreset: VoicePreset,
    ): LCallViewModel
}
