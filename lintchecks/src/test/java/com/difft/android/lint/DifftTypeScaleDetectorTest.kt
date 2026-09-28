package com.difft.android.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import org.junit.Test

class DifftTypeScaleDetectorTest {

    @Test
    fun `flags Text calls that pass a metric and leaves style-only calls alone`() {
        lint()
            .files(
                UNIT_STUB,
                FONT_WEIGHT_STUB,
                TEXT_STUB,
                STYLE_STUB,
                kotlin(
                    "src/com/difft/android/chat/Screen.kt",
                    """
                    package com.difft.android.chat

                    import androidx.compose.material3.Text
                    import androidx.compose.ui.text.TextStyle
                    import androidx.compose.ui.text.font.FontWeight
                    import androidx.compose.ui.unit.sp

                    fun Screen(token: TextStyle) {
                        Text(text = "a", fontSize = 14.sp)
                        Text(text = "b", fontWeight = FontWeight.Medium)
                        Text(text = "c", style = token)
                        Text(text = "d", style = token, maxLines = 1)
                    }
                    """,
                ).indented(),
            )
            .issues(DifftTypeScaleDetector.ISSUE)
            .allowMissingSdk()
            // @JvmOverloads cannot be applied to functions with value-class parameters, so that
            // harness mode rewrites the stubs into something the Kotlin compiler rejects.
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectErrorCount(2)
            .expectContains("Text(fontSize = …) hand-writes a text metric")
            .expectContains("Text(fontWeight = …) hand-writes a text metric")
    }

    @Test
    fun `flags TextStyle and SpanStyle literals that set a metric but not colour-only ones`() {
        lint()
            .files(
                UNIT_STUB,
                FONT_WEIGHT_STUB,
                STYLE_STUB,
                kotlin(
                    "src/com/difft/android/call/Styles.kt",
                    """
                    package com.difft.android.call

                    import androidx.compose.ui.text.SpanStyle
                    import androidx.compose.ui.text.TextStyle
                    import androidx.compose.ui.unit.sp

                    val a = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
                    val b = TextStyle(color = 0xFF000000)
                    val c = SpanStyle(fontSize = 12.sp)
                    val d = SpanStyle(color = 0xFF000000)
                    """,
                ).indented(),
            )
            .issues(DifftTypeScaleDetector.ISSUE)
            .allowMissingSdk()
            // Same value-class stubs as above: keep the @JvmOverloads harness mode off everywhere.
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectErrorCount(2)
            .expectContains("TextStyle(fontSize = …) hand-writes a text metric")
            .expectContains("SpanStyle(fontSize = …) hand-writes a text metric")
    }

    @Test
    fun `flags copy calls that change a metric but not colour-only copies`() {
        lint()
            .files(
                UNIT_STUB,
                FONT_WEIGHT_STUB,
                STYLE_STUB,
                kotlin(
                    "src/com/difft/android/chat/Copies.kt",
                    """
                    package com.difft.android.chat

                    import androidx.compose.ui.text.TextStyle
                    import androidx.compose.ui.text.font.FontWeight
                    import androidx.compose.ui.unit.sp

                    fun copies(token: TextStyle) {
                        val a = token.copy(fontSize = 18.sp)
                        val b = token.copy(fontWeight = FontWeight.Bold)
                        val c = token.copy(color = 0xFF000000)
                    }
                    """,
                ).indented(),
            )
            .issues(DifftTypeScaleDetector.ISSUE)
            .allowMissingSdk()
            // @JvmOverloads cannot be applied to functions with value-class parameters, so that
            // harness mode rewrites the stubs into something the Kotlin compiler rejects.
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectErrorCount(2)
            .expectContains("TextStyle.copy(fontSize = …) moves a text style off the type scale")
            .expectContains("TextStyle.copy(fontWeight = …) moves a text style off the type scale")
    }

    @Test
    fun `ignores a Text function from another package`() {
        lint()
            .files(
                UNIT_STUB,
                kotlin(
                    "src/com/difft/android/base/ui/compose/Text.kt",
                    """
                    package com.difft.android.base.ui.compose

                    import androidx.compose.ui.unit.TextUnit

                    fun Text(text: String, fontSize: TextUnit) {}
                    """,
                ).indented(),
                kotlin(
                    "src/com/difft/android/chat/Screen.kt",
                    """
                    package com.difft.android.chat

                    import com.difft.android.base.ui.compose.Text
                    import androidx.compose.ui.unit.sp

                    fun Screen() {
                        Text(text = "a", fontSize = 14.sp)
                    }
                    """,
                ).indented(),
            )
            .issues(DifftTypeScaleDetector.ISSUE)
            .allowMissingSdk()
            // Same value-class stubs as above: keep the @JvmOverloads harness mode off everywhere.
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectClean()
    }

    private companion object {
        val UNIT_STUB = kotlin(
            "src/androidx/compose/ui/unit/TextUnit.kt",
            """
            package androidx.compose.ui.unit

            // Value class on purpose: real Compose mangles every function that takes a `TextUnit`
            // (`Text-Nvy7gAk`, `copy-p1EtxEg`), and the detector must match through that.
            @JvmInline
            value class TextUnit(val packedValue: Long) {
                companion object {
                    val Unspecified = TextUnit(0L)
                }
            }
            val Int.sp: TextUnit get() = TextUnit(this.toLong())
            """,
        ).indented()

        // File name matters: Kotlin compiles top-level functions into `<FileName>Kt`, which the
        // detector matches on.
        val TEXT_STUB = kotlin(
            "src/androidx/compose/material3/Text.kt",
            """
            package androidx.compose.material3

            import androidx.compose.ui.text.TextStyle
            import androidx.compose.ui.text.font.FontWeight
            import androidx.compose.ui.unit.TextUnit

            fun Text(
                text: String,
                fontSize: TextUnit = TextUnit.Unspecified,
                fontWeight: FontWeight? = null,
                lineHeight: TextUnit = TextUnit.Unspecified,
                maxLines: Int = Int.MAX_VALUE,
                style: TextStyle? = null,
            ) {}
            """,
        ).indented()

        val STYLE_STUB = kotlin(
            "src/androidx/compose/ui/text/TextStyle.kt",
            """
            package androidx.compose.ui.text

            import androidx.compose.ui.unit.TextUnit

            class TextStyle(
                val color: Long = 0,
                val fontSize: TextUnit = TextUnit.Unspecified,
                val fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
                val lineHeight: TextUnit = TextUnit.Unspecified,
            ) {
                fun copy(
                    color: Long = this.color,
                    fontSize: TextUnit = this.fontSize,
                    fontWeight: androidx.compose.ui.text.font.FontWeight? = this.fontWeight,
                    lineHeight: TextUnit = this.lineHeight,
                ): TextStyle = this
            }

            class SpanStyle(
                val color: Long = 0,
                val fontSize: TextUnit = TextUnit.Unspecified,
            )
            """,
        ).indented()
    }
}
