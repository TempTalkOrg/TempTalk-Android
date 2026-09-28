package com.difft.android.network

import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockkClass
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Answers every `EntryPointAccessors.fromApplication` with a relaxed mock, so classes that reach
 * the Hilt graph from a static initialiser (`UserAgentManager`) or a hot path
 * (`HttpClientInterceptor`'s `UrlManager` lookup) work without a Hilt test component.
 *
 * Register a real stand-in with [put] when a test needs to control what an entry point returns.
 */
class HiltEntryPointRule : TestWatcher() {

    private val overrides = mutableMapOf<Class<*>, Any>()

    fun <T : Any> put(type: Class<T>, value: T): HiltEntryPointRule = apply { overrides[type] = value }

    override fun starting(description: Description) {
        mockkStatic(EntryPointAccessors::class)
        every { EntryPointAccessors.fromApplication(any<Context>(), any<Class<Any>>()) } answers {
            val type = secondArg<Class<*>>()
            overrides[type] ?: mockkClass(type.kotlin, relaxed = true)
        }
    }

    override fun finished(description: Description) {
        overrides.clear()
        unmockkStatic(EntryPointAccessors::class)
    }
}
