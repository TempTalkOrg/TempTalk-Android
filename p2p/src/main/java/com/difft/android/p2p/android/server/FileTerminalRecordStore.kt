package com.difft.android.p2p.android.server

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.model.P2pJson
import com.difft.android.p2p.engine.session.TerminalRecord
import com.difft.android.p2p.engine.session.TerminalRecordStore
import java.io.File
import kotlinx.serialization.encodeToString

/**
 * Terminal records as one JSON file in the module's private directory — the same place the receiver
 * keeps its certificate, and deliberately not inside the chat database: a promote replaces that
 * database file wholesale, and a record of how a session ended must not vanish with the very step
 * whose outcome it records.
 *
 * Writes go through a temp file and a rename so a crash mid-write leaves the previous list intact
 * rather than a truncated one. Unreadable content reads as an empty list (see [TerminalRecordStore]).
 */
class FileTerminalRecordStore(private val dir: File) : TerminalRecordStore {

    private val file: File get() = File(dir, FILE_NAME)
    private val tmp: File get() = File(dir, "$FILE_NAME.tmp")

    override fun load(): List<TerminalRecord> {
        val f = file
        if (!f.exists()) return emptyList()
        return runCatching { P2pJson.decodeFromString<List<TerminalRecord>>(f.readText()) }
            .onFailure { L.w { "[P2P][Receiver] terminal records unreadable, starting empty: ${it.message}" } }
            .getOrDefault(emptyList())
    }

    override fun save(records: List<TerminalRecord>) {
        runCatching {
            dir.mkdirs()
            tmp.writeText(P2pJson.encodeToString(records))
            if (!tmp.renameTo(file)) {
                // Some filesystems refuse to rename over an existing file; fall back to replace.
                file.delete()
                check(tmp.renameTo(file)) { "rename failed" }
            }
        }.onFailure {
            L.w { "[P2P][Receiver] terminal records write failed: ${it.message}" }
            runCatching { tmp.delete() }
        }
    }

    companion object {
        const val FILE_NAME = "p2p_terminal_records.json"
    }
}
