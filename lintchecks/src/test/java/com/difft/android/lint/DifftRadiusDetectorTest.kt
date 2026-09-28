package com.difft.android.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import org.junit.Test

class DifftRadiusDetectorTest {

    @Test
    fun `flags positional and named dp radii`() {
        lint()
            .files(
                DP_STUB,
                SHAPE_STUB,
                kotlin(
                    "src/com/difft/android/chat/Card.kt",
                    """
                    package com.difft.android.chat

                    import androidx.compose.foundation.shape.RoundedCornerShape
                    import androidx.compose.ui.unit.dp

                    val a = RoundedCornerShape(8.dp)
                    val b = RoundedCornerShape(size = 12.dp)
                    """,
                ).indented(),
            )
            .issues(DifftRadiusDetector.ISSUE)
            .allowMissingSdk()
            // @JvmOverloads cannot be applied to functions with value-class parameters, so that
            // harness mode rewrites the Dp stubs into something the Kotlin compiler rejects.
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectErrorCount(2)
            .expectContains("RoundedCornerShape(8.dp) hand-writes a corner radius")
            .expectContains("RoundedCornerShape(12.dp) hand-writes a corner radius")
    }

    @Test
    fun `ignores percent and per-corner shapes`() {
        lint()
            .files(
                DP_STUB,
                SHAPE_STUB,
                kotlin(
                    "src/com/difft/android/call/Tiles.kt",
                    """
                    package com.difft.android.call

                    import androidx.compose.foundation.shape.RoundedCornerShape
                    import androidx.compose.ui.unit.dp

                    val a = RoundedCornerShape(50)
                    val b = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                    val c = RoundedCornerShape(bottomStart = 4.dp)
                    """,
                ).indented(),
            )
            .issues(DifftRadiusDetector.ISSUE)
            .allowMissingSdk()
            // Same value-class stubs as above: keep the @JvmOverloads harness mode off everywhere.
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectClean()
    }

    @Test
    fun `ignores radius tokens and non-literal sizes`() {
        lint()
            .files(
                DP_STUB,
                SHAPE_STUB,
                RADIUS_STUB,
                kotlin(
                    "src/com/difft/android/chat/Tokens.kt",
                    """
                    package com.difft.android.chat

                    import androidx.compose.foundation.shape.RoundedCornerShape
                    import androidx.compose.ui.unit.Dp
                    import com.difft.android.base.ui.theme.DifftRadius

                    fun tokens(size: Dp) {
                        val a = DifftRadius.largeShape
                        val b = DifftRadius.pill
                        val c = RoundedCornerShape(size)
                        val d = RoundedCornerShape(DifftRadius.medium)
                    }
                    """,
                ).indented(),
            )
            .issues(DifftRadiusDetector.ISSUE)
            .allowMissingSdk()
            // Same value-class stubs as above: keep the @JvmOverloads harness mode off everywhere.
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectClean()
    }

    @Test
    fun `ignores a RoundedCornerShape function from another package`() {
        lint()
            .files(
                DP_STUB,
                kotlin(
                    "src/com/difft/android/base/ui/compose/Shapes.kt",
                    """
                    package com.difft.android.base.ui.compose

                    import androidx.compose.ui.unit.Dp

                    fun RoundedCornerShape(size: Dp): Int = 0
                    """,
                ).indented(),
                kotlin(
                    "src/com/difft/android/chat/Card.kt",
                    """
                    package com.difft.android.chat

                    import androidx.compose.ui.unit.dp
                    import com.difft.android.base.ui.compose.RoundedCornerShape

                    val a = RoundedCornerShape(8.dp)
                    """,
                ).indented(),
            )
            .issues(DifftRadiusDetector.ISSUE)
            .allowMissingSdk()
            // Same value-class stubs as above: keep the @JvmOverloads harness mode off everywhere.
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectClean()
    }

    private companion object {
        val DP_STUB = kotlin(
            "src/androidx/compose/ui/unit/Dp.kt",
            """
            package androidx.compose.ui.unit

            // Value class on purpose: real Compose mangles every function that takes a `Dp`
            // (`RoundedCornerShape-0680j_4`), and the detector must match through that.
            @JvmInline
            value class Dp(val value: Float)
            val Int.dp: Dp get() = Dp(this.toFloat())
            """,
        ).indented()

        // File name matters: Kotlin compiles top-level functions into `<FileName>Kt`, which the
        // detector matches on.
        val SHAPE_STUB = kotlin(
            "src/androidx/compose/foundation/shape/RoundedCornerShape.kt",
            """
            package androidx.compose.foundation.shape

            import androidx.compose.ui.unit.Dp

            class RoundedCornerShape

            fun RoundedCornerShape(size: Dp): RoundedCornerShape = RoundedCornerShape()
            fun RoundedCornerShape(percent: Int): RoundedCornerShape = RoundedCornerShape()
            fun RoundedCornerShape(
                topStart: Dp = Dp(0f),
                topEnd: Dp = Dp(0f),
                bottomEnd: Dp = Dp(0f),
                bottomStart: Dp = Dp(0f),
            ): RoundedCornerShape = RoundedCornerShape()
            """,
        ).indented()

        val RADIUS_STUB = kotlin(
            "src/com/difft/android/base/ui/theme/DifftShapes.kt",
            """
            package com.difft.android.base.ui.theme

            import androidx.compose.foundation.shape.RoundedCornerShape
            import androidx.compose.ui.unit.Dp

            object DifftRadius {
                val medium: Dp = Dp(4f)
                val largeShape = RoundedCornerShape(Dp(8f))
                val pill = RoundedCornerShape(50)
            }
            """,
        ).indented()
    }
}
