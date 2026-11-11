package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.messageserialization.db.store.TestWcdbFactory
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.RoomModel
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals









@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("WCDB native library not loadable in JVM unit tests. Run via instrumentation test instead.")
class GlobalSearchQueriesTest {

    private lateinit var db: WCDB

    @Before
    fun setUp() {
        db = TestWcdbFactory.createInMemoryWcdb(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun message(id: String, room: String, text: String, shownAt: Long, mode: Int = 0, type: Int = MessageModel.TYPE_TEXT) {
        db.message.insertObject(MessageModel().apply {
            this.id = id
            roomId = room
            messageText = text
            systemShowTimestamp = shownAt
            timeStamp = shownAt
            this.mode = mode
            this.type = type
        })
    }

    private fun room(id: String, name: String, type: Int = 0) {
        db.room.insertObject(RoomModel().apply {
            roomId = id
            roomName = name
            roomType = type
        })
    }

    @Test
    fun `contacts match by email and by a substring of the id`() {
        db.contactor.insertObject(ContactorModel().apply { id = "+7001234"; name = "Zed" })
        db.contactor.insertObject(ContactorModel().apply { id = "+8000000"; name = "Yan"; email = "alice@x.org" })

        assertEquals(listOf("+7001234"), db.searchContactsByKeyword("0123").map { it.id })
        assertEquals(listOf("+8000000"), db.searchContactsByKeyword("ALICE").map { it.id })
    }

    @Test
    fun `group names match case-insensitively`() {
        db.group.insertObject(GroupModel().apply { gid = "a1"; name = "Design Team"; status = 0 })

        assertEquals(listOf("a1"), db.searchGroupsByKeyword("design").map { it.gid })
    }

    @Test
    fun `a group name in another script matches itself`() {
        db.group.insertObject(GroupModel().apply { gid = "b2"; name = "équipe"; status = 0 })
        db.group.insertObject(GroupModel().apply { gid = "c3"; name = "Команда"; status = 0 })

        assertEquals(listOf("b2"), db.searchGroupsByKeyword("équipe").map { it.gid })
        assertEquals(listOf("c3"), db.searchGroupsByKeyword("Команда").map { it.gid })
    }

    @Test
    fun `confidential and notify messages never match and a multi-match room shows its newest`() {
        room("r1", "One")
        message("m1", "r1", "hello old", shownAt = 1)
        message("m2", "r1", "hello new", shownAt = 2)
        message("m3", "r1", "hello secret", shownAt = 3, mode = 1)
        message("m4", "r1", "hello notice", shownAt = 4, type = MessageModel.TYPE_NOTIFY)

        val result = db.loadRoomSearchResultsByKeyword("hello").single()

        assertEquals(2, result.messageCount)
        assertEquals("m2", result.latestMessage?.id)
    }

    @Test
    fun `one-on-one rooms match by their stored name, groups and the server room do not`() {
        room("u1", "Alice")
        room("g1", "Alice fans", type = 1)
        room("server", "Alice")

        assertEquals(listOf("u1"), db.searchOneOnOneRoomsByName("ali").map { it.roomId })
    }
}
