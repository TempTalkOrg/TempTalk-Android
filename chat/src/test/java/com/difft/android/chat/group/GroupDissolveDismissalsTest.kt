package com.difft.android.chat.group

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupDissolveDismissalsTest {
    @Test fun `dismissals are per group and duplicate dismissal is idempotent`() = runTest {
        val store = GroupDissolveDismissals()
        store.groupIds.test {
            assertTrue(awaitItem().isEmpty())
            store.dismiss("a")
            assertEquals(setOf("a"), awaitItem())
            store.dismiss("a")
            expectNoEvents()
            store.dismiss("b")
            assertEquals(setOf("a", "b"), awaitItem())
        }
    }

    @Test fun `blank ids are ignored and a new process store starts empty`() {
        val oldProcess = GroupDissolveDismissals()
        oldProcess.dismiss("")
        oldProcess.dismiss(" ")
        assertTrue(oldProcess.groupIds.value.isEmpty())
        oldProcess.dismiss("a")
        assertTrue(GroupDissolveDismissals().groupIds.value.isEmpty())
        assertEquals(setOf("a"), oldProcess.groupIds.value)
    }
}
