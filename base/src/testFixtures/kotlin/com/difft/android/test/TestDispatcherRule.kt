package com.difft.android.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit 4 TestRule that replaces [Dispatchers.Main] with a [TestDispatcher].
 *
 * Usage:
 * ```
 * @get:Rule
 * val dispatcherRule = TestDispatcherRule()
 * ```
 *
 * For tests that need explicit time control:
 * ```
 * @get:Rule
 * val dispatcherRule = TestDispatcherRule(StandardTestDispatcher())
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
