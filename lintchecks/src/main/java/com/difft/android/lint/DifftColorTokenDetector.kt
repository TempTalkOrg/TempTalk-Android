package com.difft.android.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.ULiteralExpression

/**
 * Forbids hand-written colours in Compose code outside the theme package.
 *
 * Colour is a token, not a literal: `DifftTheme.colors.<semantic>` for anything that carries
 * meaning, `Palette.<step>` for a raw step of the ramp. A `Color(0xFF056FFA)` literal or a
 * `colorResource(R.color.…)` lookup pins one theme's value into a composable, so it does not
 * follow light/dark and drifts from the design library. The theme package is exempt via
 * `lint.xml` because it defines the tokens; the handful of genuine one-offs (overlay alphas,
 * Figma shadow rgba values, theme-independent launcher chrome) are exempt by path there too,
 * each with the reason recorded next to it.
 *
 * Two surfaces, one issue id:
 *  - `Color(<numeric literal>)` — the `Long` / `Int` factory in `androidx.compose.ui.graphics`
 *  - `colorResource(...)` — `androidx.compose.ui.res.colorResource`
 *
 * `Color.Transparent` / `Color.White` (property reads), `Color(red, green, blue)` built from
 * variables, and View-world APIs such as `setBackgroundColor(0x…)` are all left alone.
 */
class DifftColorTokenDetector : Detector(), Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> = listOf("Color", "colorResource")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val owner = method.containingClass?.qualifiedName ?: return
        // Kotlin mangles a function whose signature involves an inline class (`Color-8_81llA`);
        // the readable name is what precedes the dash.
        when (method.name.substringBefore('-')) {
            "Color" -> {
                if (owner != COLOR_FACADE) return
                if (!hasSingleNumericLiteralArgument(node)) return
            }
            "colorResource" -> if (owner != COLOR_RESOURCE_FACADE) return
            else -> return
        }
        context.report(
            issue = ISSUE,
            scope = node,
            location = context.getLocation(node),
            message = MESSAGE,
        )
    }

    /** True for `Color(0xFF056FFA)` / `Color(0x80000000)`, false for the float/variable forms. */
    private fun hasSingleNumericLiteralArgument(node: UCallExpression): Boolean {
        val argument = node.valueArguments.singleOrNull() as? ULiteralExpression ?: return false
        val value = argument.value
        return value is Int || value is Long
    }

    companion object {
        private const val COLOR_FACADE = "androidx.compose.ui.graphics.ColorKt"
        private const val COLOR_RESOURCE_FACADE = "androidx.compose.ui.res.ColorResources_androidKt"

        private const val MESSAGE =
            "`Color(0x…)`/`colorResource()` hand-writes a colour in Compose: " +
                "use `DifftTheme.colors.<token>` (or `Palette.<step>` for a non-semantic step)"

        val ISSUE: Issue = Issue.create(
            id = "DifftColorTokenRequired",
            briefDescription = "Hand-written colour literal instead of a DifftTheme colour token",
            explanation = """
                Colours come from the design library: `DifftTheme.colors.<semantic token>` for \
                anything meaningful, `Palette.<step>` for a raw ramp step. A \
                `Color(0xFF056FFA)` literal or a `colorResource(R.color.…)` lookup freezes one \
                theme's value into a composable — it stops following light/dark and drifts from \
                the library the moment the token changes. Only the theme package (which defines \
                the tokens) and the one-off literals listed in `lint.xml` are exempt.
            """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(DifftColorTokenDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
