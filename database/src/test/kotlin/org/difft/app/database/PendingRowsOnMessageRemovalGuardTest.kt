package org.difft.app.database

import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue









class PendingRowsOnMessageRemovalGuardTest {

    private val source = File(moduleDir(), "src/main/java/org/difft/app/database/WCDBExtensions.kt").readText()

    @Test
    fun `a real deletion drops the pending rows on the message`() {
        assertTrue(body("fun MessageModel.delete()").contains("deletePendingMessagesTargetingThis()"))
        assertTrue(body("private fun MessageModel.deletePendingMessagesTargetingThis()").contains("pendingMessageNew"))
    }

    @Test
    fun `the placeholder path keeps the pending rows on the message`() {
        listOf(
            "fun MessageModel.deleteRelatedDataForMessage()",
            "fun MessageModel.convertToConfidentialPlaceholder()",
        ).forEach { signature ->
            val body = body(signature)
            assertFalse(body.contains("pendingMessageNew"), "$signature touches pending rows")
            assertFalse(body.contains("deletePendingMessagesTargetingThis"), "$signature drops pending rows")
        }
    }

    
    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "missing $signature")
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced body for $signature")
    }

    private fun moduleDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return if (File(cwd, "src/main").exists()) cwd else File(cwd, "database")
    }
}
