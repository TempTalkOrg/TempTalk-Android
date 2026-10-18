package com.difft.android.call

import android.app.Application
import com.difft.android.call.session.CallSession
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.network.UrlManager
import io.mockk.mockk

/**
 * JVM [LCallViewModel] over a [CallSessionTestHarness]: the session is started through the
 * harness holder, as the entry point does, and handed to the view model, as the page does.
 * `AndroidViewModel` only stores the (mocked) Application, and `viewModelScope` runs on the
 * `Dispatchers.Main` that `TestDispatcherRule` installs.
 */
internal object LCallViewModelFixture {
    fun build(
        harness: CallSessionTestHarness,
        session: CallSession = harness.start(),
        urlManager: UrlManager = mockk(relaxed = true),
    ): LCallViewModel = LCallViewModel(
        application = mockk<Application>(relaxed = true),
        session = session,
        userManager = mockk(relaxed = true),
        urlManager = urlManager,
        onGoingCallStateManager = harness.ogsm,
    )
}
