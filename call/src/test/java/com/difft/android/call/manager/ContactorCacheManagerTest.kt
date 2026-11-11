package com.difft.android.call.manager

import android.content.Context
import com.difft.android.call.LCallToChatController
import com.difft.android.call.data.AvatarData
import com.difft.android.call.data.CallUserDisplayInfo
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.difft.app.database.test.builders.buildContactor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Optional







@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContactorCacheManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val callToChatController: LCallToChatController = mockk(relaxed = true)
    private val mockContext: Context = mockk(relaxed = true)

    private lateinit var subject: ContactorCacheManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        subject = ContactorCacheManager(
            lazyCallToChatController = dagger.Lazy { callToChatController },
            context = mockContext,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    

    @Test
    fun `getParticipantDisplayInfo does NOT build an avatar View`() = runTest(testDispatcher) {
        val contactor = buildContactor(id = "+12312345678", name = "Alice")
        coEvery {
            callToChatController.getContactorById(any(), "+12312345678")
        } returns Optional.of(contactor)

        subject.getParticipantDisplayInfo("+12312345678.1")

        
        verify(exactly = 0) { callToChatController.createAvatarViewBinder(any(), any()) }
    }

    

    @Test
    fun `getParticipantDisplayInfo returns AvatarData_FromContactor when DB hit`() = runTest(testDispatcher) {
        val contactor = buildContactor(id = "+12312345678", name = "Alice")
        coEvery {
            callToChatController.getContactorById(any(), "+12312345678")
        } returns Optional.of(contactor)

        val result: CallUserDisplayInfo = subject.getParticipantDisplayInfo("+12312345678.1")

        assertEquals("+12312345678.1", result.id)
        assertNotNull(result.avatarData)
        assertTrue(
            "Expected FromContactor branch when DB hit, got ${result.avatarData}",
            result.avatarData is AvatarData.FromContactor
        )
        assertSame(contactor, (result.avatarData as AvatarData.FromContactor).contactor)
    }

    @Test
    fun `getParticipantDisplayInfo returns AvatarData_FromNameOrUid when DB miss`() = runTest(testDispatcher) {
        coEvery {
            callToChatController.getContactorById(any(), "+19998887777")
        } returns Optional.empty()

        val result: CallUserDisplayInfo = subject.getParticipantDisplayInfo("+19998887777.1")

        assertEquals("+19998887777.1", result.id)
        assertNotNull(result.avatarData)
        assertTrue(
            "Expected FromNameOrUid branch when DB miss, got ${result.avatarData}",
            result.avatarData is AvatarData.FromNameOrUid
        )
        val data = result.avatarData as AvatarData.FromNameOrUid
        assertEquals("+19998887777", data.userId)
    }

    

    @Test
    fun `getParticipantDisplayInfo body executes off Main`() = runTest {
        
        
        val standardMain = StandardTestDispatcher(testScheduler, name = "main-test")
        Dispatchers.setMain(standardMain)
        try {
            var capturedThreadName: String? = null
            coEvery { callToChatController.getContactorById(any(), any()) } answers {
                capturedThreadName = Thread.currentThread().name
                Optional.empty()
            }

            withContext(Dispatchers.Main) {
                subject.getParticipantDisplayInfo("+12312345678.1")
            }

            assertNotNull("Expected getContactorById stub to capture a thread name", capturedThreadName)
            assertFalse(
                "Body must NOT run on the Main dispatcher; captured thread was '$capturedThreadName'",
                capturedThreadName!!.startsWith("main")
            )
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    

    @Test
    fun `repeat 5 times — no avatar-construction API called`() = runTest(testDispatcher) {
        
        val contactor = buildContactor(id = "+12312345678", name = "Alice")
        coEvery {
            callToChatController.getContactorById(any(), any())
        } returns Optional.of(contactor)

        repeat(5) {
            subject.getParticipantDisplayInfo("+12312345678.1")
        }

        verify(exactly = 0) { callToChatController.createAvatarViewBinder(any(), any()) }
    }

    

    @Test
    fun `getParticipantDisplayInfo propagates uid into id field`() = runTest(testDispatcher) {
        coEvery { callToChatController.getContactorById(any(), any()) } returns Optional.empty()

        val result = subject.getParticipantDisplayInfo("+19998887777.2")

        assertEquals("+19998887777.2", result.id)
    }

    @Test
    fun `getParticipantDisplayInfo handles uid without device suffix`() = runTest(testDispatcher) {
        coEvery { callToChatController.getContactorById(any(), "+19998887777") } returns Optional.empty()

        val result = subject.getParticipantDisplayInfo("+19998887777")

        assertEquals("+19998887777", result.id)
        
        val data = result.avatarData as AvatarData.FromNameOrUid
        assertEquals("+19998887777", data.userId)
    }

    @Test
    fun `getParticipantDisplayInfo returns non-null avatarData for both branches`() = runTest(testDispatcher) {
        
        val contactor = buildContactor(id = "+10000000000", name = "Carol")
        coEvery { callToChatController.getContactorById(any(), "+10000000000") } returns Optional.of(contactor)
        assertNotNull(subject.getParticipantDisplayInfo("+10000000000.1").avatarData)

        
        coEvery { callToChatController.getContactorById(any(), "+10000000001") } returns Optional.empty()
        assertNotNull(subject.getParticipantDisplayInfo("+10000000001.1").avatarData)
    }

    @Test
    fun `getParticipantDisplayInfo with empty uid — defensive`() = runTest(testDispatcher) {
        coEvery { callToChatController.getContactorById(any(), "") } returns Optional.empty()

        val result = subject.getParticipantDisplayInfo("")

        assertEquals("", result.id)

        assertNotNull(result.avatarData)
    }
}
