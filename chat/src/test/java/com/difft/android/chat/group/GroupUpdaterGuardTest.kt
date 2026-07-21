package com.difft.android.chat.group

import android.content.Context
import com.difft.android.base.utils.appScope
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.group.GroupRepo
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [GroupUpdater] fail-soft guards when `wcdb.isDbInaccessible` is true (a one-way flag that never
 * clears in-process):
 *  - Producer ([GroupUpdater.handleGroupNotifyMessage]) DROPS the enqueue, so nothing accumulates
 *    in the buffered channel that no consumer will ever drain (bounded back-pressure guard).
 *  - Consumer ([GroupUpdater.processNotifyMessages]) BREAKS out of the poll loop on the first
 *    guarded iteration (log once, no 3s spam) instead of `continue`-ing forever.
 *
 * `appScope` is replaced with a [TestScope]; constructing [GroupUpdater] launches the loop, which
 * suspends at the first `delay(3000)`. Asserting the (private) channel stays empty proves the
 * producer guard dropped the enqueue. `advanceUntilIdle()` COMPLETING (rather than spinning
 * forever, which is what a regressed `continue` + `while(true)` + `delay(3000)` would do) proves
 * the consumer loop broke. WCDB `Table` getters are never touched (a relaxed `CppObject` mock would
 * crash the host JVM), so green also proves no DB access.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupUpdaterGuardTest {

    private val context = mockk<Context>(relaxed = true)
    private lateinit var wcdb: WCDB

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var updater: GroupUpdater

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns testScope
        // GroupUpdater.processGroupNotifyMessage resolves operator names through ContactorUtil; the
        // guard skips it, but mock the object so construction/loop cannot touch a real singleton.
        mockkObject(ContactorUtil)

        wcdb = mockk(relaxed = true)

        updater = GroupUpdater(
            context = context,
            messageArchiveManager = mockk<MessageArchiveManager>(relaxed = true),
            conversationSettingsManager = mockk<ConversationSettingsManager>(relaxed = true),
            gson = Gson(),
            dbMessageStore = mockk<DBMessageStore>(relaxed = true),
            wcdb = wcdb,
            groupUtil = mockk<GroupUtil>(relaxed = true),
            groupCryptoRepo = mockk<GroupCryptoRepo>(relaxed = true),
            groupRepo = mockk<GroupRepo>(relaxed = true),
            httpClient = mockk<ChativeHttpClient>(relaxed = true),
            groupMemberWriter = mockk<GroupMemberWriter>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `G2 producer drops enqueue and consumer breaks poll loop when DB inaccessible`() = runTest(testDispatcher) {
        every { wcdb.isDbInaccessible } returns true

        // Producer guard: enqueue is dropped while DB is inaccessible, so nothing lands in the
        // buffered channel that no consumer will ever drain.
        updater.handleGroupNotifyMessage(mockk(relaxed = true), mockk(relaxed = true))

        val channel = notifyChannelOf(updater)
        assertTrue(
            "producer guard must drop the enqueue while DB is inaccessible — channel stays empty",
            channel.isEmpty
        )

        // Consumer guard: the loop (launched in init, parked at the first delay(3000)) resumes,
        // hits the guard, and BREAKS. With the old `continue`, while(true) + delay(3000) would
        // reschedule forever and advanceUntilIdle would spin indefinitely; it COMPLETING here proves
        // the loop terminated (log-once, no 3s spam) without touching any WCDB Table.
        advanceUntilIdle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun notifyChannelOf(updater: GroupUpdater): Channel<*> {
        val field = GroupUpdater::class.java.getDeclaredField("notifyMessageChannel")
        field.isAccessible = true
        return field.get(updater) as Channel<*>
    }
}
