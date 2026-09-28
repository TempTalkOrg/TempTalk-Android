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
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression

/**
 * Forbids hand-written corner radii in Compose code outside the theme package.
 *
 * The radius tiers live on `DifftTheme.radius` (`smallShape` 2, `mediumShape` 4, `largeShape` 8,
 * `pill`). A `RoundedCornerShape(8.dp)` at a call site re-states a tier by hand and drifts from
 * it the moment the library changes. The theme package is exempt via `lint.xml` because it
 * defines the tiers; the genuinely off-scale radii (6, 12, 16, 20, 24) are exempt by path there
 * too, each with its value recorded, until design either adds the step or drops the size.
 *
 * Only the single-`size` overload with a `<n>.dp` literal is flagged. The percent overload
 * (`RoundedCornerShape(50)`), the per-corner form (`RoundedCornerShape(topStart = …)`) and any
 * non-literal argument stay free — none of them is a tier restated by hand.
 */
class DifftRadiusDetector : Detector(), Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> = listOf(SHAPE_FACTORY)

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (method.containingClass?.qualifiedName != SHAPE_FACADE) return
        // Kotlin mangles a function whose signature involves an inline class (`Dp`); the readable
        // name is what precedes the dash.
        if (method.name.substringBefore('-') != SHAPE_FACTORY) return

        // Only the single-`size` overload; the per-corner overload names its parameters
        // topStart/topEnd/... and the percent overload takes an Int, so neither matches here.
        val sizeIndex = method.parameterList.parameters.indexOfFirst { it.name == "size" }
        if (sizeIndex < 0) return
        val argument = node.getArgumentForParameter(sizeIndex) ?: return
        val dp = dpLiteral(argument) ?: return

        context.report(
            issue = ISSUE,
            scope = node,
            location = context.getLocation(node),
            message = "`RoundedCornerShape($dp.dp)` hand-writes a corner radius: " +
                "use `DifftTheme.radius.{small,medium,large}Shape` or `radius.pill`",
        )
    }

    /** The literal number in `8.dp` / `12.5.dp`, or null when the argument is anything else. */
    private fun dpLiteral(argument: UExpression): String? {
        val qualified = argument as? UQualifiedReferenceExpression ?: return null
        val selector = qualified.selector as? USimpleNameReferenceExpression ?: return null
        if (selector.identifier != "dp") return null
        val receiver = qualified.receiver as? ULiteralExpression ?: return null
        val value = receiver.value
        return if (value is Number) value.toString() else null
    }

    companion object {
        private const val SHAPE_FACTORY = "RoundedCornerShape"
        private const val SHAPE_FACADE = "androidx.compose.foundation.shape.RoundedCornerShapeKt"

        val ISSUE: Issue = Issue.create(
            id = "DifftRadiusRequired",
            briefDescription = "Hand-written corner radius instead of a DifftTheme radius tier",
            explanation = """
                Corner radii come from the design library: `DifftTheme.radius.smallShape` (2), \
                `.mediumShape` (4), `.largeShape` (8) and `.pill` for a full radius. A \
                `RoundedCornerShape(8.dp)` at a call site re-states a tier by hand and drifts \
                from it as soon as the library changes. Percent and per-corner forms are not \
                covered. Only the theme package (which defines the tiers) and the off-scale \
                radii listed in `lint.xml` are exempt.
            """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(DifftRadiusDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
