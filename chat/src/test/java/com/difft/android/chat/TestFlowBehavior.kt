package com.difft.android.chat

import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import com.difft.android.websocket.api.websocket.WebSocketConnectionState

class TestFlowBehavior {

    @Test
    fun testStateFlowRepeatValue() = runTest {
        val stateFlow = MutableStateFlow(1)
        val job = launch {
            stateFlow.collect { value ->
                println(value)
                when (value) {
                    2 -> {
                        launch {
                            stateFlow.value = 3
                            println("current value after set 3 is ${stateFlow.value}")
                            stateFlow.value = 6
                            println("current value after set 6 is ${stateFlow.value}")
                            delay(2000)  // Short delay to ensure emission is collected
                            println("set 5")
                            stateFlow.value = 5
                            println("set 3")
                            stateFlow.value = 3
                            delay(4000)  // Short delay to ensure emission is collected
                            stateFlow.value = 4
                        }
                    }
                    3 -> {
                        println("Processing 3")
                    }
                    4 -> {
                        println("Complete")
                        cancel() // Ends the test when value reaches 4
                    }
                }
            }
        }

        yield()
        stateFlow.value = 2

        job.join() // Wait for the job to complete
    }

    @Test
    fun testSharedFlowLastValue() = runTest {
        val webSocketConnectionState = MutableSharedFlow<WebSocketConnectionState>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        webSocketConnectionState.tryEmit(WebSocketConnectionState.DISCONNECTED)
        val job = launch {
            webSocketConnectionState.collect { state ->
                println("current collected state is $state")
                delay(500)
                println("Current state inside sharedFlow's replayCache is ${webSocketConnectionState.replayCache.lastOrNull()}")
                when(state){
                    WebSocketConnectionState.FAILED ->{
                        cancel()
                    }
                    else -> Unit
                }
            }
        }
        yield()
        webSocketConnectionState.emit(WebSocketConnectionState.CONNECTING)
        println("Emitted CONNECTING state")

        delay(1000)

        webSocketConnectionState.emit(WebSocketConnectionState.FAILED)
        println("Emitted FAILED state")
        println("After connecting state inside sharedFlow's replayCache is ${webSocketConnectionState.replayCache.lastOrNull()}")

        job.join()

    }
}