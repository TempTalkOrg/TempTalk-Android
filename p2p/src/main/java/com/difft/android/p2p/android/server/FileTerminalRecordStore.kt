package com.difft.android.p2p.android.server

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.model.P2pJson
import com.difft.android.p2p.engine.session.TerminalRecord
import com.difft.android.p2p.engine.session.TerminalRecordStore
import java.io.File
import kotlinx.serialization.encodeToString










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
