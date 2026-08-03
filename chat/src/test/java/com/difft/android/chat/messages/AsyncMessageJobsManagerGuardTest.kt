package com.difft.android.chat.messages

import android.content.Context
import com.difft.android.base.utils.appScope
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupUtil
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AsyncMessageJobsManager.runAsyncJobs] fail-soft guard: when `wcdb.isDbInaccessible` is true,
 * the one-shot `appScope.launch` must return before touching WCDB, so no network fetch is issued.
 *
 * `appScope` is replaced with a [TestScope] so the launch runs under the test clock. The relaxed
 * [WCDB] mock is only ever asked for `isDbInaccessible`; staying green is itself proof the guard
 * fired, since reaching a WCDB `Table` getter on the relaxed mock would instantiate a native
 * `CppObject` and crash on the host JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsyncMessageJobsManagerGuardTest {

    private val context = mockk<Context>(relaxed = true)
    private lateinit var wcdb: WCDB
    private lateinit var groupUtil: GroupUtil

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var manager: AsyncMessageJobsManager

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns testScope
        mockkObject(ContactorUtil)

        wcdb = mockk(relaxed = true)
        groupUtil = mockk(relaxed = true)
        manager = AsyncMessageJobsManager(context, wcdb, groupUtil)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `G3 runAsyncJobs skips all DB and network work when DB inaccessible`() = runTest(testDispatcher) {
        every { wcdb.isDbInaccessible } returns true

        manager.needFetchSpecifiedContactors(listOf("u1"))
        manager.makeSureGroupExist("g1")
        manager.runAsyncJobs()
        advanceUntilIdle()

        // No group/contact fetch reached (guard returned first; green == no WCDB Table touch).
        coVerify(exactly = 0) { groupUtil.getSingleGroupInfo(any(), any()) }
        coVerify(exactly = 0) { ContactorUtil.fetchContactors(any(), any()) }
    }
}
