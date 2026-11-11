package com.difft.android.chat.ui

import app.cash.turbine.test
import com.difft.android.chat.contacts.data.ContactorUtil
import difft.android.messageserialization.For
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.isKnownContact
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config






@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageViewModelE2eeSignalsTest : ChatMessageViewModelTestBase() {

    @After
    fun resetOfficialAccountCache() {
        OfficialAccountCache.put(OFFICIAL_ID, false)
    }

    
    @Test
    fun `isE2eeHintEligible true for group`() {
        assertTrue(viewModel(For.Group("g1"), initialize = false).isE2eeHintEligible)
    }

    
    @Test
    fun `isE2eeHintEligible false for self account`() {
        assertFalse(viewModel(For.Account(MY_ID), initialize = false).isE2eeHintEligible)
    }

    
    @Test
    fun `isE2eeHintEligible false for official account`() {
        OfficialAccountCache.put(OFFICIAL_ID, true)
        assertFalse(viewModel(For.Account(OFFICIAL_ID), initialize = false).isE2eeHintEligible)
    }

    
    @Test
    fun `isE2eeHintEligible true for non-self non-official 1v1`() {
        assertTrue(viewModel(For.Account("stranger-uid"), initialize = false).isE2eeHintEligible)
    }

    
    @Test
    fun `isNonFriendOneToOne emits true when peer has no contactor row`() = runTest {
        every { wcdb.isKnownContact(PEER_ID) } returns false
        val vm = viewModel(For.Account(PEER_ID), initialize = false)

        vm.isNonFriendOneToOne.test {
            assertFalse(awaitItem()) 
            
            
            
            pumpMainLooper()
            assertTrue(awaitItem()) 
        }
    }

    
    @Test
    fun `isNonFriendOneToOne reflects friendStatusUpdate optimistically`() = runTest {
        every { wcdb.isKnownContact(PEER_ID) } returns false
        val vm = viewModel(For.Account(PEER_ID), initialize = false)

        vm.isNonFriendOneToOne.test {
            
            pumpMainLooper()
            var item = awaitItem()
            while (!item) item = awaitItem()
            assertTrue(item)

            ContactorUtil.emitFriendStatusUpdate(PEER_ID, true)
            pumpMainLooper()
            assertFalse(awaitItem())
        }
    }

    
    @Test
    fun `contactsUpdate re-query supersedes stale optimistic override`() = runTest {
        every { wcdb.isKnownContact(PEER_ID) } returns false
        val vm = viewModel(For.Account(PEER_ID), initialize = false)

        vm.isNonFriendOneToOne.test {
            pumpMainLooper()
            var item = awaitItem()
            while (!item) item = awaitItem()
            assertTrue(item)

            ContactorUtil.emitFriendStatusUpdate(PEER_ID, true)
            pumpMainLooper()
            assertFalse(awaitItem())

            
            every { wcdb.isKnownContact(PEER_ID) } returns false
            ContactorUtil.emitContactsUpdate(listOf(PEER_ID))
            pumpMainLooper()
            assertTrue(awaitItem())
        }
    }

    
    @Test
    fun `isNonFriendOneToOne stays false and never queries wcdb for a group`() = runTest {
        val vm = viewModel(For.Group("g1"), initialize = false)

        vm.isNonFriendOneToOne.test {
            assertFalse(awaitItem())
            expectNoEvents()
        }
        io.mockk.verify(exactly = 0) { wcdb.isKnownContact(any()) }
    }

    private companion object {
        const val OFFICIAL_ID = "official-uid"
        const val PEER_ID = "peer-uid"
    }
}
