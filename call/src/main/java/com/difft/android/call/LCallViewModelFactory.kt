package com.difft.android.call

import com.difft.android.call.session.CallSession
import dagger.assisted.AssistedFactory

@AssistedFactory
interface LCallViewModelFactory {
    fun create(session: CallSession): LCallViewModel
}
