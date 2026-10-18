package com.difft.android.call.session

import com.difft.android.base.call.CallType
import com.difft.android.base.user.CallConfig
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.core.CallUiController
import com.difft.android.call.handler.CriticalAlertDispatcher
import com.difft.android.call.handler.RoomEventDispatcher
import com.difft.android.call.media.CallMediaController
import com.difft.android.call.network.NetworkQualityCoordinator
import com.difft.android.call.session.harness.CallIntentFixture
import com.difft.android.call.ui.screenshare.ScreenShareFloatingSpeakerStateHolder
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.difft.app.database.models.GroupModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The session graph writes UI output through [CallSessionUiState] only. Row T3-16: group updates
 * from the IO-backed group listener set the session's critical-alert flag only for this call's
 * group, and no session-graph collaborator can take a [CallUiController].
 */
class SessionUiDecouplingTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private val scope = CoroutineScope(testDispatcher)

    @After
    fun tearDown() {
        scope.cancel()
        unmockkAll()
    }

    @Test
    fun `T3-16 a group update sets the session critical-alert flag only for this call's group`() {
        val uiState = CallSessionUiState()
        val delivered = CountDownLatch(1)
        val chat = mockk<LCallToChatController>(relaxed = true) {
            every { getContactsUpdateListener() } returns emptyFlow()
            every { getGroupsUpdateListener() } returns flow {
                emit(group("g1", criticalAlert = true))
                emit(group("other", criticalAlert = false))
                delivered.countDown()
            }
        }

        CallObservers.register(
            scope = scope,
            room = mockk(relaxed = true),
            participantManager = mockk(relaxed = true) { every { participants } returns MutableStateFlow(emptyList()) },
            uiState = uiState,
            contactorCacheManager = mockk(relaxed = true),
            callToChatController = chat,
            speakerState = mockk(relaxed = true),
            callConfig = CallConfig(),
            callIntent = CallIntentFixture.build(CallIntent.Action.JOIN_CALL, CallType.GROUP.type, conversationId = "g1"),
            activeSpeakers = MutableStateFlow(emptyList()),
        )

        assertTrue("group listener drained on IO", delivered.await(AWAIT_TIMEOUT_S, TimeUnit.SECONDS))
        assertEquals("matching gid sets the flag; another group's update leaves it", true, uiState.groupCriticalAlert.value)
    }

    @Test
    fun `T3-16 no session-graph collaborator takes a CallUiController`() {
        val scanned = listOf(
            RoomEventDispatcher::class.java,
            CriticalAlertDispatcher::class.java,
            NetworkQualityCoordinator::class.java,
            CallObservers::class.java,
            CallMediaController::class.java,
            ScreenShareFloatingSpeakerStateHolder::class.java,
            CallEndController::class.java,
        )

        val offenders = scanned.flatMap { cls ->
            val params = cls.declaredConstructors.flatMap { it.parameterTypes.asList() } +
                cls.declaredMethods.flatMap { it.parameterTypes.asList() }
            params.filter { CallUiController::class.java.isAssignableFrom(it) }.map { "${cls.simpleName} <- ${it.simpleName}" }
        }

        assertTrue("CallUiController parameters: $offenders", offenders.isEmpty())
    }

    private fun group(gid: String, criticalAlert: Boolean) = mockk<GroupModel>(relaxed = true) {
        every { this@mockk.gid } returns gid
        every { this@mockk.criticalAlert } returns criticalAlert
    }

    private companion object {
        const val AWAIT_TIMEOUT_S = 5L
    }
}
