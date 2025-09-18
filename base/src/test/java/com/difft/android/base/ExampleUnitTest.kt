package com.difft.android.base

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.system.measureTimeMillis

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSharedFlow(): Unit = runBlocking {
        val sharedFlow = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)
        sharedFlow.take(3).onEach {
            delay(1000)
            println("onEach $it")
        }.launchIn(this)
        launch {
            val measureTime = measureTimeMillis {
                while (sharedFlow.subscriptionCount.value == 0) {
                    delay(5)
                }
                sharedFlow.emit(1)
                println("emit 1")
                sharedFlow.emit(2)
                println("emit 2")
                sharedFlow.emit(3)
                println("emit 3")
            }
            println("measureTime $measureTime")
        }

    }
    @Test
    fun testNewStringUTF8() {
        val byteArray = ByteArray(1024 * 1024) // 1 MB array initialized with zeros
        val string = String(byteArray, Charsets.UTF_8)
        println(string.count()) // Outputs: 1048576 (the length of the string)
        println(string.length) // Outputs: 1048576 (the length of the string)
    }

    @Test
    fun testSubstringUTF8() {

        fun String.utf8Substring(maxUtf8Len: Int): String {
            val bytes = this.toByteArray(UTF_8)
            if (bytes.size <= maxUtf8Len) {
                return this
            }

            var endIndex = maxUtf8Len
            // Ensure we do not cut a multi-byte character in the middle
            while (endIndex > 0 && (bytes[endIndex].toInt() and 0xC0) == 0x80) {
                endIndex--
            }

            // Convert the valid UTF-8 byte array back to a string
            return String(bytes, 0, endIndex, UTF_8)
        }

// Usage example
        val originalString = "Some UTF-8 string with emojis 🤔🚀"
        val maxUtf8Length = 38
        val substring = originalString.utf8Substring(maxUtf8Length)
        println(substring) // Output will be a substring within the specified UTF-8 byte limit
    }
}