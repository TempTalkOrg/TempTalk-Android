package com.difft.android.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A DifftTheme(...) invocation found in source text that combines useFlatBackground = true with
 * no applyWindowBackground argument at all -- the family-D risk pattern (issue #1127). A call
 * that explicitly names applyWindowBackground (whichever value) is considered "aware" and is not
 * flagged -- this guard is about silent, unconsidered combination, not about forbidding
 * useFlatBackground = true outright.
 */
data class UnguardedFlatBackgroundViolation(val matchedText: String, val startIndex: Int)

/**
 * Scans Kotlin source text for DifftTheme(...) invocations that pass useFlatBackground = true
 * without also passing applyWindowBackground. Operates on raw source text (not a compiled AST) --
 * sufficient because both parameters are always passed as named arguments in this codebase
 * (verified repo-wide; no positional-argument DifftTheme call exists), so a
 * same-invocation-argument-list regex is a reliable proxy for "which parameters this call names".
 */
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

/**
 * Family-D structural guard, layer 1: proves the pure scanner function
 * itself has discriminating power via synthetic fixtures, before Layer 2
 * (IndexActivityFragmentContainerDetailThemeGuardTest) trusts it against real source files. A
 * static-analysis guard that can never fail is worthless -- these tests are the guard's own
 * "test the test" obligation.
 */
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
