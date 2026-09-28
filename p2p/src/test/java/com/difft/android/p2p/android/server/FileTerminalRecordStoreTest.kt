package com.difft.android.p2p.android.server

import com.difft.android.p2p.engine.model.TransferStages
import com.difft.android.p2p.engine.model.TransferTerminal
import com.difft.android.p2p.engine.session.TerminalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The on-disk memory of how sessions ended: round-trips, survives a missing dir, and reads garbage as nothing. */
@RunWith(RobolectricTestRunner::class)
class FileTerminalRecordStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun record(id: String, at: Long) =
        TerminalRecord(id, "hash-$id", TransferTerminal.SUCCEEDED, TransferStages.DONE, records = 3, attachmentBytes = 9, progressRevision = 4, at = at)

    @Test
    fun `saves into a dir that does not exist yet and loads the same list back`() {
        val dir = File(tempFolder.root, "not/yet/there")
        val store = FileTerminalRecordStore(dir)
        assertEquals(emptyList<TerminalRecord>(), store.load())

        val records = listOf(record("a", 1), record("b", 2))
        store.save(records)
        assertEquals(records, FileTerminalRecordStore(dir).load())
        assertTrue(File(dir, FileTerminalRecordStore.FILE_NAME).exists())
    }

    @Test
    fun `an unreadable file reads as an empty list and a later save replaces it`() {
        val dir = tempFolder.newFolder("records")
        File(dir, FileTerminalRecordStore.FILE_NAME).writeText("{ not json")
        val store = FileTerminalRecordStore(dir)
        assertEquals(emptyList<TerminalRecord>(), store.load())

        store.save(listOf(record("c", 3)))
        assertEquals(listOf(record("c", 3)), store.load())
    }
}
