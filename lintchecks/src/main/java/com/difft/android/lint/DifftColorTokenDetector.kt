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



















class DifftColorTokenDetector : Detector(), Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> = listOf("Color", "colorResource")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val owner = method.containingClass?.qualifiedName ?: return
        
        
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
