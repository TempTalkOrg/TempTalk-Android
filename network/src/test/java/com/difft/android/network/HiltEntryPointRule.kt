package com.difft.android.network

import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockkClass
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.rules.TestWatcher
import org.junit.runner.Description








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
