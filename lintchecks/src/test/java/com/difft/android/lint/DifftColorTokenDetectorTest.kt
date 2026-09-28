package com.difft.android.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import org.junit.Test

class DifftColorTokenDetectorTest {

    @Test
    fun `flags Color literals but leaves named colours and float components alone`() {
        lint()
            .files(
                COLOR_STUB,
                kotlin(
                    "src/com/difft/android/chat/Screen.kt",
                    """
                    package com.difft.android.chat

                    import androidx.compose.ui.graphics.Color

                    fun screen(r: Float, g: Float, b: Float) {
                        val a = Color(0xFF056FFA)
                        val b1 = Color(0x80000000)
                        val c = Color.Transparent
                        val d = Color.White
                        val e = Color(r, g, b)
                    }
                    """,
                ).indented(),
            )
            .issues(DifftColorTokenDetector.ISSUE)
            .allowMissingSdk()
            // Compose's Color is a value class, so @JvmOverloads cannot be applied to the stub
            // factories that return it — that harness mode rewrites them into invalid Kotlin.
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectErrorCount(2)
            .expectContains("Color(0xFF056FFA)")
            .expectContains("Color(0x80000000)")
            .expectContains("hand-writes a colour in Compose")
    }

    @Test
    fun `flags colorResource lookups`() {
        lint()
            .files(
                COLOR_STUB,
                COLOR_RESOURCE_STUB,
                kotlin(
                    "src/com/difft/android/call/Banner.kt",
                    """
                    package com.difft.android.call

                    import androidx.compose.ui.res.colorResource

                    fun banner() {
                        val a = colorResource(1)
                    }
                    """,
                ).indented(),
            )
            .issues(DifftColorTokenDetector.ISSUE)
            .allowMissingSdk()
            // Same value-class stubs as above: keep the @JvmOverloads harness mode off everywhere.
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectErrorCount(1)
            .expectContains("use DifftTheme.colors.<token>")
    }

    @Test
    fun `ignores token reads and View-world colour APIs`() {
        lint()
            .files(
                COLOR_STUB,
                THEME_STUB,
                kotlin(
                    "src/com/difft/android/base/Widget.kt",
                    """
                    package com.difft.android.base

                    class Widget {
                        fun setBackgroundColor(color: Int) {}
                    }
                    """,
                ).indented(),
                kotlin(
                    "src/com/difft/android/chat/Tokens.kt",
                    """
                    package com.difft.android.chat

                    import com.difft.android.base.Widget
                    import com.difft.android.base.ui.theme.DifftTheme

                    fun tokens(widget: Widget) {
                        val a = DifftTheme.colors.bgPrimary
                        widget.setBackgroundColor(-16777216)
                    }
                    """,
                ).indented(),
            )
            .issues(DifftColorTokenDetector.ISSUE)
            .allowMissingSdk()
            // Same value-class stubs as above: keep the @JvmOverloads harness mode off everywhere.
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectClean()
    }

    @Test
    fun `ignores a Color function from another package`() {
        lint()
            .files(
                kotlin(
                    "src/com/difft/android/base/ui/compose/Palettes.kt",
                    """
                    package com.difft.android.base.ui.compose

                    fun Color(color: Long): Long = color
                    """,
                ).indented(),
                kotlin(
                    "src/com/difft/android/chat/Screen.kt",
                    """
                    package com.difft.android.chat

                    import com.difft.android.base.ui.compose.Color

                    fun screen() {
                        val a = Color(0xFF056FFA)
                    }
                    """,
                ).indented(),
            )
            .issues(DifftColorTokenDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    private companion object {
        // File name matters: Kotlin compiles top-level functions into `<FileName>Kt`, which the
        // detector matches on. `Color` is a value class on purpose — real Compose mangles the
        // factory that returns it (`Color-8_81llA`), and the detector must match through that.
        val COLOR_STUB = kotlin(
            "src/androidx/compose/ui/graphics/Color.kt",
            """
            package androidx.compose.ui.graphics

            @JvmInline
            value class Color(val packedValue: ULong) {
                companion object {
                    val Transparent = Color(0UL)
                    val White = Color(1UL)
                }
            }

            fun Color(color: Long): Color = Color.Transparent
            fun Color(color: Int): Color = Color.Transparent
            fun Color(red: Float, green: Float, blue: Float, alpha: Float = 1f): Color = Color.Transparent
            """,
        ).indented()

        val COLOR_RESOURCE_STUB = kotlin(
            "src/androidx/compose/ui/res/ColorResources_android.kt",
            """
            package androidx.compose.ui.res

            import androidx.compose.ui.graphics.Color

            fun colorResource(id: Int): Color = Color.Transparent
            """,
        ).indented()

        val THEME_STUB = kotlin(
            "src/com/difft/android/base/ui/theme/DifftTheme.kt",
            """
            package com.difft.android.base.ui.theme

            import androidx.compose.ui.graphics.Color

            class DifftColorScheme(val bgPrimary: Color = Color.Transparent)

            object DifftTheme {
                val colors = DifftColorScheme()
            }
            """,
        ).indented()
    }
}
