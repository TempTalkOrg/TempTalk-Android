package com.difft.android.chat.contacts

import com.difft.android.base.user.UserManager
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.websocket.api.messages.Data
import com.difft.android.websocket.api.messages.Member
import com.difft.android.websocket.api.messages.PublicConfigs
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.tencent.wcdb.winq.Expression
import io.mockk.slot
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.PublicAccountType
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import com.difft.android.chat.contacts.data.ContactorUtil
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config








@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContactsUpdaterAction3Test {

    private val userManager: UserManager = mockk(relaxed = true)
    private val dbMessageStore: DBMessageStore = mockk(relaxed = true)
    private val wcdb: WCDB = mockk(relaxed = true)
    private val weakContactReconciler: WeakContactReconciler = mockk(relaxed = true)

    private lateinit var updater: ContactsUpdater

    @Before
    fun setUp() {
        mockkObject(ContactorUtil)
        every { ContactorUtil.emitContactsUpdate(any()) } just Runs
        every { ContactorUtil.updateContactRequestStatus(any(), any()) } just Runs
        updater = ContactsUpdater(userManager, dbMessageStore, wcdb, weakContactReconciler)
    }

    @After
    fun tearDown() {
        unmockkObject(ContactorUtil)
        clearMocks(userManager, dbMessageStore, wcdb, weakContactReconciler)
    }

    
    private suspend fun invokeProcess(message: TTNotifyMessage) {
        val method = ContactsUpdater::class.java.getDeclaredMethod(
            "processContactNotifyMessage",
            TTNotifyMessage::class.java,
            kotlin.coroutines.Continuation::class.java,
        )
        method.isAccessible = true
        suspendInvoke(method, updater, message)
    }

    
    private suspend fun suspendInvoke(
        method: java.lang.reflect.Method,
        receiver: Any,
        arg: Any?,
    ) = kotlin.coroutines.suspendCoroutine<Any?> { cont ->
        val result = method.invoke(receiver, arg, cont)
        if (result != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
            cont.resumeWith(Result.success(result))
        }
    }

    private fun member(number: String, action: Int, publicConfigs: PublicConfigs? = null): Member = Member(
        uid = number,
        customUid = null,
        displayName = "n-$number",
        rapidRole = 0,
        role = 0,
        action = action,
        avatar = null,
        extId = 0,
        friend = false,
        name = "n-$number",
        number = number,
        publicConfigs = publicConfigs,
        privateConfigs = null,
    )

    private fun notify(members: List<Member>): TTNotifyMessage =
        TTNotifyMessage(
            data = Data(actionType = 0, directoryVersion = 1).apply { this.members = members },
            notifyTime = 1L,
            notifyType = TTNotifyMessage.NOTIFY_MESSAGE_TYPE_UPDATE_CONTACT,
        )

    

    @Test
    @Ignore("action=3 branch builds DBContactorModel.id.eq + removeRoomAndMessages (CppObject native lib); instrumentation only.")
    fun `T7 action 3 hard deletes contactor removes room unfriends and emits, like action 2`() = runTest {
        invokeProcess(notify(listOf(member("peer-x", action = 3))))

        
        verify { wcdb.contactor.deleteObjects(any()) }
        verify { dbMessageStore.removeRoomAndMessages("peer-x") }
        verify { ContactorUtil.updateContactRequestStatus("peer-x", isDelete = true) }
        verify { ContactorUtil.emitContactsUpdate(listOf("peer-x")) }
    }

    

    @Test
    @Ignore("action=0 branch builds DBContactorModel.id.eq (CppObject native lib); instrumentation only.")
    fun `T7b action 0 still inserts contactor on same-shaped notify`() = runTest {
        invokeProcess(notify(listOf(member("peer-y", action = 0))))

        
        verify { wcdb.contactor.insertObject(any()) }
        verify { ContactorUtil.emitContactsUpdate(listOf("peer-y")) }
    }

    
    @Test
    @Ignore("action=0 branch builds DBContactorModel.id.eq (CppObject native lib); instrumentation only.")
    fun `T7c action 0 clears weak placeholder via friend-restored path without deleting room`() = runTest {
        invokeProcess(notify(listOf(member("peer-z", action = 0))))

        
        coVerify(exactly = 1) { weakContactReconciler.clearWeakOnFriendRestored("peer-z") }
        
        
        verify(exactly = 0) { dbMessageStore.removeRoomAndMessages("peer-z") }
    }

    

    
    
    @Test
    @Ignore("action=0 branch reads prior row + builds DBContactorModel.id.eq (CppObject native lib); instrumentation only.")
    fun `T8b action 0 restore with null publicAccountType keeps prior OFFICIAL type`() = runTest {
        val prior = ContactorModel().also { it.id = "peer-official"; it.publicAccountType = PublicAccountType.OFFICIAL }
        every { wcdb.contactor.getFirstObject(any<Expression>()) } returns prior
        val inserted = slot<ContactorModel>()
        every { wcdb.contactor.insertObject(capture(inserted)) } returns Unit

        invokeProcess(notify(listOf(member("peer-official", action = 0, publicConfigs = PublicConfigs(publicAccountType = null)))))

        
        assertEquals(PublicAccountType.OFFICIAL, inserted.captured.publicAccountType)
    }

    
    @Test
    @Ignore("action=0 branch reads prior row + builds DBContactorModel.id.eq (CppObject native lib); instrumentation only.")
    fun `T8c action 0 genuinely-new id with null field defaults to NORMAL`() = runTest {
        every { wcdb.contactor.getFirstObject(any<Expression>()) } returns null
        val inserted = slot<ContactorModel>()
        every { wcdb.contactor.insertObject(capture(inserted)) } returns Unit

        invokeProcess(notify(listOf(member("peer-new", action = 0, publicConfigs = PublicConfigs(publicAccountType = null)))))

        assertEquals(PublicAccountType.NORMAL, inserted.captured.publicAccountType)
    }

    
    
    @Test
    @Ignore("action=0 branch builds DBContactorModel.id.eq (CppObject native lib); instrumentation only.")
    fun `T8d action 0 insert failure leaves official cache unchanged`() = runTest {
        OfficialAccountCache.clear()
        OfficialAccountCache.put("peer-fail", true)   
        every { wcdb.contactor.getFirstObject(any<Expression>()) } returns null
        every { wcdb.contactor.insertObject(any<ContactorModel>()) } throws RuntimeException("wcdb insert failed")

        
        invokeProcess(notify(listOf(member("peer-fail", action = 0, publicConfigs = PublicConfigs(publicAccountType = null)))))

        assertTrue(OfficialAccountCache.contains("peer-fail"))
        OfficialAccountCache.clear()
    }
}
