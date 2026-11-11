package com.difft.android.lint

import com.android.resources.ResourceFolderType
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.XmlScannerConstants
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.w3c.dom.Element
import java.util.EnumSet




















class DifftHairlineDetector : Detector(), Detector.XmlScanner, Detector.UastScanner {

    

    override fun appliesTo(folderType: ResourceFolderType): Boolean =
        folderType == ResourceFolderType.LAYOUT || folderType == ResourceFolderType.DRAWABLE

    override fun getApplicableElements(): Collection<String> = XmlScannerConstants.ALL

    override fun visitElement(context: XmlContext, element: Element) {
        when (context.resourceFolderType) {
            ResourceFolderType.LAYOUT -> visitLayoutElement(context, element)
            ResourceFolderType.DRAWABLE -> visitDrawableElement(context, element)
            else -> Unit
        }
    }

    private fun visitLayoutElement(context: XmlContext, element: Element) {
        val background = element.getAttributeNS(ANDROID_NS, "background")
        if (background !in DIVIDER_COLOURS) return
        val dimensions = listOf("layout_height", "layout_width").map { it to element.getAttributeNS(ANDROID_NS, it) }
        val thin = dimensions.firstOrNull { (_, value) -> isThinDp(value) }
        if (thin != null) {
            context.report(
                issue = ISSUE,
                scope = element,
                location = context.getLocation(element.getAttributeNodeNS(ANDROID_NS, thin.first)),
                message = "A `${thin.second}` divider is density-scaled: use `@dimen/hairline` (one physical pixel)",
            )
        }
        val isRule = thin != null || dimensions.any { (_, value) -> value == HAIRLINE_DIMEN }
        if (isRule && background == LINE_COLOUR) {
            context.report(
                issue = ISSUE,
                scope = element,
                location = context.getLocation(element.getAttributeNodeNS(ANDROID_NS, "background")),
                message = LINE_COLOUR_MESSAGE,
            )
        }
    }

    private fun visitDrawableElement(context: XmlContext, element: Element) {
        if (element.tagName != "size") return
        val shape = element.parentNode as? Element ?: return
        val solids = shape.getElementsByTagName("solid")
        val solid = (0 until solids.length)
            .mapNotNull { solids.item(it) as? Element }
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "color") in DIVIDER_COLOURS } ?: return
        val dimensions = listOf("height", "width").map { it to element.getAttributeNS(ANDROID_NS, it) }
        val thin = dimensions.firstOrNull { (_, value) -> isThinDp(value) }
        if (thin != null) {
            context.report(
                issue = ISSUE,
                scope = element,
                location = context.getLocation(element.getAttributeNodeNS(ANDROID_NS, thin.first)),
                message = "A `${thin.second}` divider is density-scaled: use `@dimen/hairline` (one physical pixel)",
            )
        }
        val isRule = thin != null || dimensions.any { (_, value) -> value == HAIRLINE_DIMEN }
        if (isRule && solid.getAttributeNS(ANDROID_NS, "color") == LINE_COLOUR) {
            context.report(
                issue = ISSUE,
                scope = solid,
                location = context.getLocation(solid.getAttributeNodeNS(ANDROID_NS, "color")),
                message = LINE_COLOUR_MESSAGE,
            )
        }
    }

    

    override fun getApplicableMethodNames(): List<String> = DIVIDER_COMPOSABLES + MODIFIER_SIZES

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val name = method.name.substringBefore('-') 
        val facade = method.containingClass?.qualifiedName ?: return
        when {
            name in DIVIDER_COMPOSABLES && facade in DIVIDER_FACADES -> visitDivider(context, node, method, name)
            name in MODIFIER_SIZES && facade == SIZE_FACADE -> visitSizedRule(context, node, method, name)
        }
    }

    private fun visitDivider(context: JavaContext, node: UCallExpression, method: PsiMethod, name: String) {
        val parameters = method.parameterList.parameters
        val thicknessIndex = parameters.indexOfFirst { it.name == "thickness" }
        if (thicknessIndex >= 0) {
            val argument = node.getArgumentForParameter(thicknessIndex)
            val problem = when {
                argument == null -> "`$name` without `thickness` draws Material's 1.dp"
                dpLiteral(argument) != null -> "`$name(thickness = ${dpLiteral(argument)}.dp)` is density-scaled"
                else -> null
            }
            if (problem != null) {
                context.report(
                    issue = ISSUE,
                    scope = node,
                    location = context.getLocation(node),
                    message = "$problem: pass `thickness = DifftTheme.hairline` (one physical pixel)",
                )
            }
        }
        val colorIndex = parameters.indexOfFirst { it.name == "color" }
        val color = if (colorIndex >= 0) node.getArgumentForParameter(colorIndex) else null
        if (color != null && usesLineColour(color.asSourceString().filterNot { it.isWhitespace() })) {
            context.report(issue = ISSUE, scope = node, location = context.getLocation(node), message = LINE_COLOUR_MESSAGE)
        }
    }

    private fun visitSizedRule(context: JavaContext, node: UCallExpression, method: PsiMethod, name: String) {
        val index = method.parameterList.parameters.indexOfFirst { it.name == name }
        if (index < 0) return
        val argument = node.getArgumentForParameter(index) ?: return
        val dp = dpLiteral(argument)
        val isRule = dp != null || argument.asSourceString().contains(HAIRLINE_TOKEN)
        if (!isRule) return
        
        
        var chain: UElement = node
        while (chain.uastParent is UQualifiedReferenceExpression || chain.uastParent is UParenthesizedExpression) {
            chain = chain.uastParent!!
        }
        val source = chain.asSourceString().filterNot { it.isWhitespace() }
        if (!source.contains("background(")) return
        if (DIVIDER_COLOR_TOKENS.none { source.contains(it) }) return
        if (dp != null) {
            context.report(
                issue = ISSUE,
                scope = node,
                location = context.getLocation(node),
                message = "A `$name($dp.dp)` rule in a divider colour is density-scaled: " +
                    "use `DifftTheme.hairline` (one physical pixel)",
            )
        }
        if (usesLineColour(source)) {
            context.report(issue = ISSUE, scope = node, location = context.getLocation(node), message = LINE_COLOUR_MESSAGE)
        }
    }

    



    private fun dpLiteral(argument: UExpression): String? {
        val qualified = argument as? UQualifiedReferenceExpression ?: return null
        val selector = qualified.selector as? USimpleNameReferenceExpression ?: return null
        if (selector.identifier != "dp") return null
        val receiver = qualified.receiver as? ULiteralExpression ?: return null
        val value = receiver.value as? Number ?: return null
        return if (value.toFloat() <= MAX_RULE_DP) value.toString() else null
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val LINE_COLOUR = "@color/line"
        private val DIVIDER_COLOURS = setOf(LINE_COLOUR, "@color/divider")
        private const val HAIRLINE_DIMEN = "@dimen/hairline"
        private const val HAIRLINE_TOKEN = "hairline"
        private const val LINE_COLOUR_MESSAGE =
            "A separator in the `line` colour: use `divider` (`line` is the outline colour)"

        
        private fun usesLineColour(source: String): Boolean = source.contains("colors.line")
        private val DP_LITERAL = Regex("""(\d+(?:\.\d+)?)dp""")

        
        private const val MAX_RULE_DP = 1f

        
        private fun isThinDp(value: String): Boolean {
            val match = DP_LITERAL.matchEntire(value) ?: return false
            val dp = match.groupValues[1].toFloat()
            return dp > 0f && dp <= MAX_RULE_DP
        }

        private val DIVIDER_COMPOSABLES = listOf("HorizontalDivider", "VerticalDivider", "Divider")
        private val DIVIDER_FACADES = setOf(
            "androidx.compose.material3.DividerKt",
            "androidx.compose.material.DividerKt",
        )
        private val MODIFIER_SIZES = listOf("height", "width")
        private const val SIZE_FACADE = "androidx.compose.foundation.layout.SizeKt"
        private val DIVIDER_COLOR_TOKENS = listOf("colors.divider", "colors.line")

        val ISSUE: Issue = Issue.create(
            id = "DifftHairlineRequired",
            briefDescription = "Density-scaled divider instead of the one-pixel hairline",
            explanation = """
                Every divider / separator rule is one physical pixel: `@dimen/hairline` in XML \
                and `DifftTheme.hairline` in Compose. A `1dp` rule renders as 3px on a 3x screen \
                and `0.5dp` antialiases to about 2px, heavier than iOS and the design system. \
                A rule is drawn in the `divider` colour; `line` is the outline token. \
                Outlines (`Modifier.border`, shape strokes) are not covered.
            """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                DifftHairlineDetector::class.java,
                EnumSet.of(Scope.JAVA_FILE, Scope.RESOURCE_FILE),
                Scope.JAVA_FILE_SCOPE,
                Scope.RESOURCE_FILE_SCOPE,
            ),
        )
    }
}
