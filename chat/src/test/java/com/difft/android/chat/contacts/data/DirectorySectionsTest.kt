package com.difft.android.chat.contacts.data

import org.junit.Test
import kotlin.test.assertEquals

class DirectorySectionsTest {
    @Test fun `Han Latin and decomposable accents use letters while other scripts use hash`() {
        mapOf(
            "张三" to "Z", "李四" to "L", "alice" to "A", "Émile" to "E", "Åsa" to "A",
            "1 team" to "#", "😀" to "#", "# team" to "#", "Алекс" to "#", "アキ" to "#",
            "علي" to "#", "" to "#",
        ).forEach { (name, expected) -> assertEquals(expected, directoryLetter(name), name) }
    }

    @Test fun `sections contain only present letters with hash last and pinyin order within buckets`() {
        val sections = directorySections(listOf("#", "张三", "Alex", "赵六", "Amy", "1")) { it }
        assertEquals(listOf("A", "Z", "#"), sections.map { it.letter })
        assertEquals(listOf("Alex", "Amy"), sections[0].rows)
        assertEquals(listOf("张三", "赵六"), sections[1].rows)
        assertEquals(listOf("#", "1"), sections[2].rows)
    }

    @Test fun `empty directory has no index letters`() {
        assertEquals(emptyList(), directorySections(emptyList<String>()) { it })
    }
}
