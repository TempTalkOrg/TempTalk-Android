package org.difft.app.database

import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pending rows waiting on a message are dropped only when the message is really deleted, never when
 * a confidential message turns into a placeholder: the placeholder keeps the message, so a recall or
 * receipt on it that failed transiently must stay for its retry (#1382).
 *
 * A source guard because these extensions bind the Hilt-provided `wcdb` global and WCDB's winq is
 * native, so neither path can run on the JVM.
 */
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

    /** The brace-balanced body following [signature]. */
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
