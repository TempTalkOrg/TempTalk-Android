package com.difft.android.chat.contacts

import com.difft.android.base.user.UserManager
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.websocket.api.messages.Data
import com.difft.android.websocket.api.messages.Member
import com.difft.android.websocket.api.messages.TTNotifyMessage
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

/**
 * Tests for [ContactsUpdater] action=3 (peer disabled / lost friend) behavior.
 *
 * Weak-contact feature decoupling: the directory `action` cannot distinguish "I deleted the friend"
 * (action=2) from "I lost the friend" (action=3), so action=3 now does the **identical hard delete**
 * as action=2 (contactor delete + `removeRoomAndMessages` + unfriend + emit). Whether the contact
 * enters the weak state is signalled independently by `notifyType=25` and never affects this delete.
 * The real private `processContactNotifyMessage` is invoked via reflection (the public
 * `updateBySignalNotifyMessage` only enqueues onto a debounced channel processed asynchronously, not
 * directly testable). This invokes production, not a re-implementation.
 *
 * **WCDB Expression constraint**: the action=3 (and action=0) branches construct
 * `DBContactorModel.id.eq(...)` (CppObject native lib), not loadable on the host JVM, so they are
 * `@Ignore`-d as compilation guards + documented behavior (instrumentation only).
 */
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

    /** Invoke the real private suspend `processContactNotifyMessage(TTNotifyMessage)`. */
    private suspend fun invokeProcess(message: TTNotifyMessage) {
        val method = ContactsUpdater::class.java.getDeclaredMethod(
            "processContactNotifyMessage",
            TTNotifyMessage::class.java,
            kotlin.coroutines.Continuation::class.java,
        )
        method.isAccessible = true
        suspendInvoke(method, updater, message)
    }

    /** Bridge a reflective call to a Kotlin suspend function from a coroutine. */
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

    private fun member(number: String, action: Int): Member = Member(
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
        publicConfigs = null,
        privateConfigs = null,
    )

    private fun notify(members: List<Member>): TTNotifyMessage =
        TTNotifyMessage(
            data = Data(actionType = 0, directoryVersion = 1).apply { this.members = members },
            notifyTime = 1L,
            notifyType = TTNotifyMessage.NOTIFY_MESSAGE_TYPE_UPDATE_CONTACT,
        )

    // ---- action=3 hard deletes (identical to action=2) ----------------------------------

    @Test
    @Ignore("action=3 branch builds DBContactorModel.id.eq + removeRoomAndMessages (CppObject native lib); instrumentation only.")
    fun `T7 action 3 hard deletes contactor removes room unfriends and emits, like action 2`() = runTest {
        invokeProcess(notify(listOf(member("peer-x", action = 3))))

        // action=3 is now a hard delete identical to action=2 (weak state is independent of this).
        verify { wcdb.contactor.deleteObjects(any()) }
        verify { dbMessageStore.removeRoomAndMessages("peer-x") }
        verify { ContactorUtil.updateContactRequestStatus("peer-x", isDelete = true) }
        verify { ContactorUtil.emitContactsUpdate(listOf("peer-x")) }
    }

    // ---- companion: action=0 still adds (touches WINQ Expression → @Ignore) -------------

    @Test
    @Ignore("action=0 branch builds DBContactorModel.id.eq (CppObject native lib); instrumentation only.")
    fun `T7b action 0 still inserts contactor on same-shaped notify`() = runTest {
        invokeProcess(notify(listOf(member("peer-y", action = 0))))

        // action=0 deletes-then-inserts the contactor and emits an update.
        verify { wcdb.contactor.insertObject(any()) }
        verify { ContactorUtil.emitContactsUpdate(listOf("peer-y")) }
    }

    // ---- action=0 clears any weak placeholder (friend restored) but KEEPS the room ------
    // 方案1: restoring a friend comes through directory action=0 (not notify ct=1), so action=0
    // must clear the weak placeholder via clearWeakOnFriendRestored — which preserves the room.
    @Test
    @Ignore("action=0 branch builds DBContactorModel.id.eq (CppObject native lib); instrumentation only.")
    fun `T7c action 0 clears weak placeholder via friend-restored path without deleting room`() = runTest {
        invokeProcess(notify(listOf(member("peer-z", action = 0))))

        // Friend restored → clear the weak placeholder for this uid.
        coVerify(exactly = 1) { weakContactReconciler.clearWeakOnFriendRestored("peer-z") }
        // The conversation must be PRESERVED on restore — action=0 never deletes the room itself,
        // and clearWeakOnFriendRestored (vs removeWeak) is the room-preserving entry by contract.
        verify(exactly = 0) { dbMessageStore.removeRoomAndMessages("peer-z") }
    }
}
