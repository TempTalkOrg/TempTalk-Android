package com.difft.android.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue








data class UnguardedFlatBackgroundViolation(val matchedText: String, val startIndex: Int)








fun scanForUnguardedFlatBackground(sourceText: String): List<UnguardedFlatBackgroundViolation> {
    val callPattern = Regex("""DifftTheme\(([^)]*)\)""")
    return callPattern.findAll(sourceText)
        .filter { match ->
            val args = match.groupValues[1]
            args.contains("useFlatBackground = true") && !args.contains("applyWindowBackground")
        }
        .map { UnguardedFlatBackgroundViolation(it.value, it.range.first) }
        .toList()
}








class UnguardedFlatBackgroundScannerTest {

    @Test
    fun `flags DifftTheme with useFlatBackground true and no applyWindowBackground`() {
        val source = """
            setContent {
                DifftTheme(useFlatBackground = true) {
                    Content()
                }
            }
        """.trimIndent()
        assertEquals(1, scanForUnguardedFlatBackground(source).size)
    }

    @Test
    fun `does not flag when applyWindowBackground is explicitly named`() {
        val source = """
            DifftTheme(useFlatBackground = true, applyWindowBackground = false) { Content() }
        """.trimIndent()
        assertTrue(scanForUnguardedFlatBackground(source).isEmpty())
    }

    @Test
    fun `does not flag default useFlatBackground (false)`() {
        val source = """DifftTheme { Content() }"""
        assertTrue(scanForUnguardedFlatBackground(source).isEmpty())
    }
}
