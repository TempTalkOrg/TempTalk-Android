package com.difft.android.startup.prompt

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.notification.ReadinessIssue
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared harness: the real coordinator and store over an in-memory DataStore, controlled clocks and providers. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class StartupPromptCoordinatorTestBase {

    protected val day = 86_400_000L
    protected var now = 1_000 * day

    protected val dataStore = RecordingPreferencesDataStore()
    protected val store = StartupPromptStore(dataStore, Gson())
    protected val sessions = MutableStateFlow(PromptSession(0, active = false))
    protected val gates = MutableStateFlow<Set<GateId>>(emptySet())
    protected val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    protected val answers = mutableMapOf<PromptId, ProviderAnswer>()
    protected val providers: StartupPromptProviders = mockk {
        every { gates() } returns gates
        every { changes() } returns changes
        every { onSessionStart() } just runs
        coEvery { evaluate(any()) } answers { answers[firstArg()] ?: ProviderAnswer.NOT_ELIGIBLE }
    }

    protected fun eligible(id: PromptId, issue: ReadinessIssue? = null, requestable: Boolean = false) {
        answers[id] = ProviderAnswer(Eligibility.ELIGIBLE, issue, permissionRequestable = requestable)
    }

    protected fun TestScope.coordinator(): StartupPromptCoordinator =
        StartupPromptCoordinator(store, providers, sessions, { now }, { now }, backgroundScope).also {
            it.start()
            runCurrent()
        }

    protected fun TestScope.startSession(
        c: StartupPromptCoordinator,
        presentable: Boolean = true,
        dualPane: Boolean = false,
        rationale: Boolean = false,
    ) {
        sessions.value = PromptSession(sessions.value.id + 1, active = true)
        runCurrent()
        if (presentable) {
            c.setHostState(true, dualPane, rationale)
            runCurrent()
        }
    }

    protected fun TestScope.endSession() {
        sessions.value = sessions.value.copy(active = false)
        runCurrent()
    }

    protected suspend fun record(id: PromptId) = store.read()[id]

    /** Delegates to [delegate]; every write throws while [fail] is set. */
    protected class FailingWrites(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        var fail = false
        override val data: Flow<Preferences> get() = delegate.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (fail) throw IOException("disk full")
            return delegate.updateData(transform)
        }
    }
}
