package com.difft.android.chat.crypto

import com.difft.android.network.group.GroupRepo
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("WCDB native lib not loadable in JVM unit tests (Expression class clinit). " +
        "See ConversationManagerImplTest:135 and JobModelRoundTripTest precedent.")
class GroupCryptoRepoTest {

    private lateinit var repo: GroupCryptoRepo
    private lateinit var groupRepo: GroupRepo

    @Before
    fun setUp() {
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        mockkObject(GroupCrypto)

        groupRepo = mockk(relaxed = true)
        repo = GroupCryptoRepo()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    

    
    @Test
    fun plain_group_skips_no_dispose() = runBlocking {
        every { repo.getRGroupBytes("g1") } returns null
        repo.verifyAllPendingForGroup("g1", groupRepo)
        coVerify(exactly = 0) { groupRepo.cryptoDispose(any(), any()) }
    }

    
    @Test
    fun derivePkBind_failure_skips_silently() = runBlocking {
        every { repo.getRGroupBytes("g1") } returns ByteArray(32) { 0 }
        every { GroupCrypto.derivePkBind(any()) } throws RuntimeException("boom")
        repo.verifyAllPendingForGroup("g1", groupRepo)
        coVerify(exactly = 0) { groupRepo.cryptoDispose(any(), any()) }
    }

    
    
    
    
    
    
    
    
    
    
}
