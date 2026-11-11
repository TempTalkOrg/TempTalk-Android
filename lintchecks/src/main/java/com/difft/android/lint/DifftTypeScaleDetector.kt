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









class DifftTypeScaleDetector : Detector(), Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> = listOf("Text", "BasicText", "copy")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val owner = method.containingClass?.qualifiedName ?: return
        
        
        val name = method.name.substringBefore('-')
        when {
            name == "copy" && owner in STYLE_CLASSES -> {
                val metric = firstMetricArgument(node, method) ?: return
                context.report(
                    issue = ISSUE,
                    scope = node,
                    location = context.getLocation(node),
                    message = "`${owner.substringAfterLast('.')}.copy(${metric} = …)` moves a text style off " +
                        "the type scale: pick the `DifftTheme.typography.<h3…p4>` step that has it",
                )
            }
            name != "copy" && owner in TEXT_FACADES -> {
                val metric = firstMetricArgument(node, method) ?: return
                context.report(
                    issue = ISSUE,
                    scope = node,
                    location = context.getLocation(node),
                    message = "`$name(${metric} = …)` hand-writes a text metric: " +
                        "use `style = DifftTheme.typography.<h3…p4>` instead",
                )
            }
        }
    }

    override fun getApplicableConstructorTypes(): List<String> = STYLE_CLASSES

    override fun visitConstructor(context: JavaContext, node: UCallExpression, constructor: PsiMethod) {
        val owner = constructor.containingClass?.qualifiedName ?: return
        if (owner !in STYLE_CLASSES) return
        val metric = firstMetricArgument(node, constructor) ?: return
        context.report(
            issue = ISSUE,
            scope = node,
            location = context.getLocation(node),
            message = "`${owner.substringAfterLast('.')}(${metric} = …)` hand-writes a text metric: use " +
                "`DifftTheme.typography.<h3…p4>` (or `.copy(color = …)` on it) instead",
        )
    }

    
    private fun firstMetricArgument(node: UCallExpression, method: PsiMethod): String? {
        val parameters = method.parameterList.parameters
        for (index in parameters.indices) {
            val name = parameters[index].name
            if (name in METRICS && node.getArgumentForParameter(index) != null) return name
        }
        return null
    }

    companion object {
        private val METRICS = setOf("fontSize", "fontWeight", "lineHeight", "fontFamily", "letterSpacing")

        private val TEXT_FACADES = setOf(
            "androidx.compose.material3.TextKt",
            "androidx.compose.material.TextKt",
            "androidx.compose.foundation.text.BasicTextKt",
        )

        private val STYLE_CLASSES = listOf(
            "androidx.compose.ui.text.TextStyle",
            "androidx.compose.ui.text.SpanStyle",
        )

        val ISSUE: Issue = Issue.create(
            id = "DifftTypeScaleRequired",
            briefDescription = "Hand-written text size/weight/line height instead of DifftTheme.typography",
            explanation = """
                Text metrics come from the Figma type scale on `DifftTheme.typography` \
                (`h3`…`h7` headings, `p2`…`p4` body, `footnote`). Passing `fontSize`, \
                `fontWeight`, `lineHeight`, `fontFamily` or `letterSpacing` to `Text`, or \
                building a `TextStyle` / `SpanStyle` with them (or `copy`-ing one with them), \
                re-states a step by hand and drifts from the scale. Use \
                `style = DifftTheme.typography.p3` and keep only \
                colour / alignment / line limits at the call site. Only `:base` (which defines \
                the scale) and the one-off sizes listed in `lint.xml` are exempt.
            """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(DifftTypeScaleDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
