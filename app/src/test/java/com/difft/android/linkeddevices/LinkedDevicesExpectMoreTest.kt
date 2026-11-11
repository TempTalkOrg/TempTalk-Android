package com.difft.android.linkeddevices

import androidx.lifecycle.viewModelScope
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.test.TestDispatcherRule
import com.difft.android.websocket.api.messages.multidevice.DeviceInfo
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals





@OptIn(ExperimentalCoroutinesApi::class)
class LinkedDevicesExpectMoreTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule(StandardTestDispatcher())

    private lateinit var repo: DeviceRepository
    private lateinit var countStore: LinkedDevicesCountStore

    private val builtViewModels = mutableListOf<LinkedDevicesViewModel>()

    @Before
    fun setup() {
        repo = mockk()
        countStore = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        builtViewModels.forEach { it.viewModelScope.cancel() }
        builtViewModels.clear()
        clearMocks(repo, countStore)
    }

    private fun buildViewModel() =
        LinkedDevicesViewModel(repo, countStore).also { builtViewModels += it }

    private fun device(id: Int) = DeviceInfo(id = id, name = "dev$id", created = 1000L, lastSeen = 2000L)

    
    @Test
    fun `poll re-fetches on interval while expecting`() = runTest(dispatcherRule.testDispatcher) {
        coEvery { repo.getDevices() } returns listOf(device(2)) 
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           
        vm.onLinkNewDeviceClicked()          
        vm.refresh(); runCurrent()           

        advanceTimeBy(10_000); runCurrent()  
        advanceTimeBy(10_000); runCurrent()  
        coVerify(atLeast = 4) { repo.getDevices() } 

        vm.stopExpecting()                   
    }

    
    @Test
    fun `poll stops when a new device appears`() = runTest(dispatcherRule.testDispatcher) {
        var call = 0
        coEvery { repo.getDevices() } coAnswers {
            call++
            if (call >= 3) listOf(device(2), device(3)) else listOf(device(2))
        }
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           
        vm.onLinkNewDeviceClicked()          
        vm.refresh(); runCurrent()           

        advanceTimeBy(10_000); runCurrent()  
        assertEquals(2, vm.uiState.value.devices.size)

        advanceTimeBy(30_000); runCurrent()  
        coVerify(exactly = 3) { repo.getDevices() }
    }

    
    
    @Test
    fun `auth failure stops the poll`() = runTest(dispatcherRule.testDispatcher) {
        var call = 0
        coEvery { repo.getDevices() } coAnswers {
            call++
            if (call <= 2) listOf(device(2))
            else throw com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException(401, "unauthorized")
        }
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           
        vm.onLinkNewDeviceClicked()          
        vm.refresh(); runCurrent()           

        advanceTimeBy(10_000); runCurrent()  
        advanceTimeBy(60_000); runCurrent()  
        coVerify(exactly = 3) { repo.getDevices() }
    }

    
    @Test
    fun `stopExpecting halts the poll`() = runTest(dispatcherRule.testDispatcher) {
        coEvery { repo.getDevices() } returns listOf(device(2))
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           
        vm.onLinkNewDeviceClicked()          
        vm.refresh(); runCurrent()           

        vm.stopExpecting()                   
        advanceTimeBy(30_000); runCurrent()  
        coVerify(exactly = 2) { repo.getDevices() }
    }

    
    @Test
    fun `poll arms even when the first post-scan refresh fails`() = runTest(dispatcherRule.testDispatcher) {
        var call = 0
        coEvery { repo.getDevices() } coAnswers {
            call++
            when (call) {
                1 -> listOf(device(2))               
                2 -> throw RuntimeException("boom")   
                3 -> listOf(device(2))               
                else -> listOf(device(2), device(3)) 
            }
        }
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           
        vm.onLinkNewDeviceClicked()          
        vm.refresh(); runCurrent()           

        advanceTimeBy(10_000); runCurrent()  
        advanceTimeBy(10_000); runCurrent()  
        assertEquals(2, vm.uiState.value.devices.size)
        coVerify(atLeast = 4) { repo.getDevices() }
    }

    
    @Test
    fun `poll gives up after the tick limit`() = runTest(dispatcherRule.testDispatcher) {
        coEvery { repo.getDevices() } returns listOf(device(2)) 
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           
        vm.onLinkNewDeviceClicked()          
        vm.refresh(); runCurrent()           

        advanceTimeBy(10_000L * 30); runCurrent() 
        coVerify(exactly = 20) { repo.getDevices() } 
    }

    
    @Test
    fun `baseline rebases after an unlink so a later link still satisfies`() =
        runTest(dispatcherRule.testDispatcher) {
            var call = 0
            coEvery { repo.getDevices() } coAnswers {
                call++
                when (call) {
                    1 -> listOf(device(2), device(3))    
                    2 -> listOf(device(2), device(3))    
                    3 -> listOf(device(2))               
                    else -> listOf(device(2), device(4)) 
                }
            }
            val vm = buildViewModel()

            vm.refresh(); runCurrent()           
            vm.onLinkNewDeviceClicked()          
            vm.refresh(); runCurrent()           

            advanceTimeBy(10_000); runCurrent()  
            advanceTimeBy(10_000); runCurrent()  
            assertEquals(2, vm.uiState.value.devices.size)

            advanceTimeBy(30_000); runCurrent()  
            coVerify(exactly = 4) { repo.getDevices() }
        }
}
