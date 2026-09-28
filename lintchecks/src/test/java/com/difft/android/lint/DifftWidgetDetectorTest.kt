package com.difft.android.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFiles.xml
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class DifftWidgetDetectorTest {

    @Test
    fun `flags raw widget tags in a layout and leaves the Difft shells alone`() {
        lint()
            .files(
                xml(
                    "res/layout/raw_widgets.xml",
                    """
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent">
                        <Button
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content" />
                        <androidx.appcompat.widget.SwitchCompat
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content" />
                        <com.google.android.material.checkbox.MaterialCheckBox
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content" />
                        <com.difft.android.base.widget.DifftButtonView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content" />
                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content" />
                    </LinearLayout>
                    """,
                ).indented(),
            )
            .issues(DifftWidgetDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(3)
            .expectContains("Use <com.difft.android.base.widget.DifftButtonView> instead of <Button>")
            .expectContains("instead of <androidx.appcompat.widget.SwitchCompat>")
            .expectContains("instead of <com.google.android.material.checkbox.MaterialCheckBox>")
    }

    @Test
    fun `flags Material3 composables but not TextButton`() {
        lint()
            .files(
                MATERIAL3_BUTTON_STUB,
                MATERIAL3_SWITCH_STUB,
                MATERIAL3_CHECKBOX_STUB,
                kotlin(
                    "src/com/difft/android/chat/Screen.kt",
                    """
                    package com.difft.android.chat

                    import androidx.compose.material3.Button
                    import androidx.compose.material3.Checkbox
                    import androidx.compose.material3.OutlinedButton
                    import androidx.compose.material3.Switch
                    import androidx.compose.material3.TextButton

                    fun Screen() {
                        Button(onClick = {}) {}
                        OutlinedButton(onClick = {}) {}
                        Switch(checked = true, onCheckedChange = null)
                        Checkbox(checked = true, onCheckedChange = null)
                        TextButton(onClick = {}) {}
                    }
                    """,
                ).indented(),
            )
            .issues(DifftWidgetDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(4)
            .expectContains("Use DifftButton instead of Button from androidx.compose.material3.ButtonKt")
            .expectContains("Use DifftSwitch instead of Switch")
            .expectContains("Use DifftCheckBox instead of Checkbox")
    }

    @Test
    fun `ignores a same-named composable from another package`() {
        lint()
            .files(
                kotlin(
                    "src/com/difft/android/base/ui/compose/DifftButton.kt",
                    """
                    package com.difft.android.base.ui.compose

                    fun Button(onClick: () -> Unit) {}
                    """,
                ).indented(),
                kotlin(
                    "src/com/difft/android/chat/Screen.kt",
                    """
                    package com.difft.android.chat

                    import com.difft.android.base.ui.compose.Button

                    fun Screen() {
                        Button(onClick = {})
                    }
                    """,
                ).indented(),
            )
            .issues(DifftWidgetDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    @Test
    fun `flags a raw widget constructed in code`() {
        lint()
            .files(
                java(
                    """
                    package androidx.appcompat.widget;

                    public class AppCompatButton {
                        public AppCompatButton(Object context) {}
                    }
                    """,
                ).indented(),
                kotlin(
                    "src/com/difft/android/chat/Factory.kt",
                    """
                    package com.difft.android.chat

                    import androidx.appcompat.widget.AppCompatButton

                    fun make(context: Any) = AppCompatButton(context)
                    """,
                ).indented(),
            )
            .issues(DifftWidgetDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("instead of constructing androidx.appcompat.widget.AppCompatButton")
    }

    private companion object {
        // File names matter: Kotlin compiles top-level functions into `<FileName>Kt`, and the
        // detector matches on those facade classes.
        val MATERIAL3_BUTTON_STUB = kotlin(
            "src/androidx/compose/material3/Button.kt",
            """
            package androidx.compose.material3

            fun Button(onClick: () -> Unit, content: () -> Unit) {}
            fun OutlinedButton(onClick: () -> Unit, content: () -> Unit) {}
            fun TextButton(onClick: () -> Unit, content: () -> Unit) {}
            """,
        ).indented()

        val MATERIAL3_SWITCH_STUB = kotlin(
            "src/androidx/compose/material3/Switch.kt",
            """
            package androidx.compose.material3

            fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {}
            """,
        ).indented()

        val MATERIAL3_CHECKBOX_STUB = kotlin(
            "src/androidx/compose/material3/Checkbox.kt",
            """
            package androidx.compose.material3

            fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {}
            """,
        ).indented()
    }
}
