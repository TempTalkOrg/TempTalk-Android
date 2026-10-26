package com.difft.android.base.notification

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationChannelIdsTest {

    @Test
    fun `every kept channel and the group has a display name`() {
        (NotificationChannelIds.KEEP + NotificationChannelIds.MESSAGE_GROUP)
            .forEach { assertNotNull(NotificationChannelIds.nameRes(it), "id=$it") }
        assertNull(NotificationChannelIds.nameRes("MESSAGE+1234"))
        NotificationChannelIds.OBSOLETE.forEach { assertNull(NotificationChannelIds.nameRes(it), "id=$it") }
    }

    // No kept id is ever deleted, and the sets are exactly as designed.
    @Test
    fun `the keep and obsolete sets never overlap`() {
        assertEquals(emptySet(), NotificationChannelIds.KEEP.toSet() intersect NotificationChannelIds.OBSOLETE.toSet())
        assertEquals(5, NotificationChannelIds.KEEP.toSet().size)
        assertEquals(18, NotificationChannelIds.OBSOLETE.toSet().size)
        assertTrue(NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY in NotificationChannelIds.OBSOLETE)
        assertTrue(NotificationChannelIds.OBSOLETE_BACKGROUND in NotificationChannelIds.OBSOLETE)
    }

    // No production source outside NotificationChannelIds spells an obsolete id, so
    // nothing can post to (or re-create) a channel the migration deletes.
    @Test
    fun `obsolete ids appear as literals only in NotificationChannelIds`() {
        val root = File("..").canonicalFile
        val sources = root.listFiles().orEmpty()
            .map { File(it, "src") }
            .filter { it.isDirectory }
            .flatMap { src -> src.listFiles().orEmpty().filterNot { it.name.startsWith("test") || it.name == "androidTest" } }
            .flatMap { set -> set.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
        assertTrue(sources.any { it.name == "NotificationChannelIds.kt" }, "scan root=$root found no sources")
        val offenders = sources.filterNot { it.name == "NotificationChannelIds.kt" }.flatMap { file ->
            val text = file.readText()
            NotificationChannelIds.OBSOLETE.filter { "\"$it\"" in text }.map { "${file.relativeTo(root)}: $it" }
        }
        assertEquals(emptyList(), offenders)
    }
}
