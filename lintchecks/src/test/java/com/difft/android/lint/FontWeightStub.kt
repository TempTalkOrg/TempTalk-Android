package com.difft.android.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin

/** Minimal `androidx.compose.ui.text.font.FontWeight` for detector tests that reference it. */
internal val FONT_WEIGHT_STUB: TestFile = kotlin(
    "src/androidx/compose/ui/text/font/FontWeight.kt",
    """
    package androidx.compose.ui.text.font

    class FontWeight(val weight: Int) {
        companion object {
            val Normal = FontWeight(400)
            val Medium = FontWeight(500)
            val Bold = FontWeight(700)
        }
    }
    """,
).indented()
