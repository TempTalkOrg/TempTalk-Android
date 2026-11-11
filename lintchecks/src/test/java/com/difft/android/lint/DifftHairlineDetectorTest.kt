package com.difft.android.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFiles.xml
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import org.junit.Test

class DifftHairlineDetectorTest {

    @Test
    fun `flags dp rules in a divider colour in layouts and leaves the hairline and other views alone`() {
        lint()
            .files(
                xml(
                    "res/layout/rules.xml",
                    """
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent">
                        <View
                            android:layout_width="match_parent"
                            android:layout_height="1dp"
                            android:background="@color/divider" />
                        <View
                            android:layout_width="0.5dp"
                            android:layout_height="0dp"
                            android:background="@color/line" />
                        <View
                            android:layout_width="match_parent"
                            android:layout_height="@dimen/hairline"
                            android:background="@color/line" />
                        <View
                            android:layout_width="match_parent"
                            android:layout_height="6dp"
                            android:background="@color/line" />
                        <View
                            android:layout_width="match_parent"
                            android:layout_height="1dp"
                            android:background="@color/bg1" />
                        <EditText
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:backgroundTint="@color/line" />
                    </LinearLayout>
                    """,
                ).indented(),
            )
            .issues(DifftHairlineDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(4)
            .expectContains("A 1dp divider is density-scaled")
            .expectContains("A 0.5dp divider is density-scaled")
            
            .expectContains("A separator in the line colour: use divider")
    }

    @Test
    fun `flags a dp size on a divider-coloured shape and leaves outlines alone`() {
        lint()
            .files(
                xml(
                    "res/drawable/row_divider.xml",
                    """
                    <shape xmlns:android="http://schemas.android.com/apk/res/android"
                        android:shape="rectangle">
                        <size android:height="1dp" />
                        <solid android:color="@color/divider" />
                    </shape>
                    """,
                ).indented(),
                xml(
                    "res/drawable/row_divider_ok.xml",
                    """
                    <shape xmlns:android="http://schemas.android.com/apk/res/android"
                        android:shape="rectangle">
                        <size android:height="@dimen/hairline" />
                        <solid android:color="@color/divider" />
                    </shape>
                    """,
                ).indented(),
                xml(
                    "res/drawable/row_divider_line.xml",
                    """
                    <shape xmlns:android="http://schemas.android.com/apk/res/android"
                        android:shape="rectangle">
                        <size android:height="@dimen/hairline" />
                        <solid android:color="@color/line" />
                    </shape>
                    """,
                ).indented(),
                xml(
                    "res/drawable/field_outline.xml",
                    """
                    <shape xmlns:android="http://schemas.android.com/apk/res/android"
                        android:shape="rectangle">
                        <stroke android:width="1dp" android:color="@color/line" />
                        <corners android:radius="8dp" />
                    </shape>
                    """,
                ).indented(),
            )
            .issues(DifftHairlineDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(2)
            .expectContains("row_divider.xml")
            .expectContains("row_divider_line.xml")
            .expectContains("A separator in the line colour: use divider")
    }

    @Test
    fun `flags Compose dividers with a dp or default thickness`() {
        lint()
            .files(
                DP_STUB,
                MODIFIER_STUB,
                COLOR_STUB,
                DIVIDER_STUB,
                SIZE_STUB,
                BACKGROUND_STUB,
                THEME_STUB,
                kotlin(
                    "src/com/difft/android/chat/Rows.kt",
                    """
                    package com.difft.android.chat

                    import androidx.compose.material3.HorizontalDivider
                    import androidx.compose.material3.VerticalDivider
                    import androidx.compose.ui.unit.dp
                    import com.difft.android.base.ui.theme.DifftTheme

                    fun rows() {
                        HorizontalDivider(color = DifftTheme.colors.divider)
                        HorizontalDivider(thickness = 1.dp, color = DifftTheme.colors.line)
                        VerticalDivider(thickness = 0.5.dp)
                    }
                    """,
                ).indented(),
            )
            .issues(DifftHairlineDetector.ISSUE)
            .allowMissingSdk()
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectErrorCount(4)
            .expectContains("HorizontalDivider without thickness draws Material's 1.dp")
            .expectContains("A separator in the line colour: use divider")
            .expectContains("HorizontalDivider(thickness = 1.dp) is density-scaled")
            .expectContains("VerticalDivider(thickness = 0.5.dp) is density-scaled")
    }

    @Test
    fun `flags a sized box in a divider colour in either chain order and leaves lengths alone`() {
        lint()
            .files(
                DP_STUB,
                MODIFIER_STUB,
                COLOR_STUB,
                DIVIDER_STUB,
                SIZE_STUB,
                BACKGROUND_STUB,
                THEME_STUB,
                kotlin(
                    "src/com/difft/android/chat/Boxes.kt",
                    """
                    package com.difft.android.chat

                    import androidx.compose.foundation.background
                    import androidx.compose.foundation.layout.height
                    import androidx.compose.foundation.layout.width
                    import androidx.compose.ui.Modifier
                    import androidx.compose.ui.unit.dp
                    import com.difft.android.base.ui.theme.DifftTheme

                    fun boxes() {
                        val a = Modifier.height(1.dp).background(DifftTheme.colors.divider)
                        val b = Modifier.background(DifftTheme.colors.line).width(0.5.dp)
                        val c = Modifier.width(1.dp).height(10.dp).background(DifftTheme.colors.line)
                        val d = Modifier.height(1.dp).background(DifftTheme.colors.bg1)
                        val e = Modifier.height(DifftTheme.hairline).background(DifftTheme.colors.divider)
                    }
                    """,
                ).indented(),
            )
            .issues(DifftHairlineDetector.ISSUE)
            .allowMissingSdk()
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectErrorCount(5)
            .expectContains("A height(1.dp) rule in a divider colour is density-scaled")
            .expectContains("A separator in the line colour: use divider")
            .expectContains("A width(0.5.dp) rule in a divider colour is density-scaled")
            .expectContains("A width(1.dp) rule in a divider colour is density-scaled")
    }

    @Test
    fun `leaves hairline dividers and non-divider thicknesses alone`() {
        lint()
            .files(
                DP_STUB,
                MODIFIER_STUB,
                COLOR_STUB,
                DIVIDER_STUB,
                SIZE_STUB,
                BACKGROUND_STUB,
                THEME_STUB,
                kotlin(
                    "src/com/difft/android/chat/Clean.kt",
                    """
                    package com.difft.android.chat

                    import androidx.compose.material3.HorizontalDivider
                    import androidx.compose.ui.unit.Dp
                    import com.difft.android.base.ui.theme.DifftTheme

                    fun clean(custom: Dp) {
                        HorizontalDivider(thickness = DifftTheme.hairline, color = DifftTheme.colors.divider)
                        HorizontalDivider(thickness = custom)
                    }
                    """,
                ).indented(),
            )
            .issues(DifftHairlineDetector.ISSUE)
            .allowMissingSdk()
            .skipTestModes(TestMode.JVM_OVERLOADS)
            .run()
            .expectClean()
    }

    private companion object {
        val DP_STUB = kotlin(
            "src/androidx/compose/ui/unit/Dp.kt",
            """
            package androidx.compose.ui.unit

            // Value class on purpose: real Compose mangles every function that takes a `Dp`, and
            // the detector must match through that.
            @JvmInline
            value class Dp(val value: Float)
            val Int.dp: Dp get() = Dp(this.toFloat())
            val Double.dp: Dp get() = Dp(this.toFloat())
            """,
        ).indented()

        val MODIFIER_STUB = kotlin(
            "src/androidx/compose/ui/Modifier.kt",
            """
            package androidx.compose.ui

            interface Modifier {
                companion object : Modifier
            }
            """,
        ).indented()

        val COLOR_STUB = kotlin(
            "src/androidx/compose/ui/graphics/Color.kt",
            """
            package androidx.compose.ui.graphics

            class Color
            """,
        ).indented()

        
        
        val DIVIDER_STUB = kotlin(
            "src/androidx/compose/material3/Divider.kt",
            """
            package androidx.compose.material3

            import androidx.compose.ui.Modifier
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.unit.Dp

            fun HorizontalDivider(modifier: Modifier = Modifier, thickness: Dp = Dp(1f), color: Color = Color()) {}
            fun VerticalDivider(modifier: Modifier = Modifier, thickness: Dp = Dp(1f), color: Color = Color()) {}
            """,
        ).indented()

        val SIZE_STUB = kotlin(
            "src/androidx/compose/foundation/layout/Size.kt",
            """
            package androidx.compose.foundation.layout

            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.Dp

            fun Modifier.height(height: Dp): Modifier = this
            fun Modifier.width(width: Dp): Modifier = this
            """,
        ).indented()

        val BACKGROUND_STUB = kotlin(
            "src/androidx/compose/foundation/Background.kt",
            """
            package androidx.compose.foundation

            import androidx.compose.ui.Modifier
            import androidx.compose.ui.graphics.Color

            fun Modifier.background(color: Color): Modifier = this
            """,
        ).indented()

        val THEME_STUB = kotlin(
            "src/com/difft/android/base/ui/theme/DifftTheme.kt",
            """
            package com.difft.android.base.ui.theme

            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.unit.Dp

            object DifftColors {
                val divider: Color = Color()
                val line: Color = Color()
                val bg1: Color = Color()
            }

            object DifftTheme {
                val colors: DifftColors = DifftColors
                val hairline: Dp = Dp(1f)
            }
            """,
        ).indented()
    }
}
