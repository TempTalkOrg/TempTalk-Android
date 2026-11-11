package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
















class PrepareSelectMissingSelect(config: Config) : Rule(
    config,
    "WCDB prepareSelect() chain with no .select(...): allObjects()/firstObject() will NPE at runtime.",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        if (expression.calleeExpression?.text != PREPARE_SELECT) return

        if (!chainHasSelect(climbToTop(expression))) {
            report(
                Finding(
                    entity = Entity.from(expression),
                    message = "`prepareSelect()` chain has no `.select(...)`; `allObjects()`/" +
                        "`firstObject()` will throw NPE at runtime because `Select.fields` stays " +
                        "null. Add `.select(*<Model>.allBindingFields())` — see " +
                        "WcdbJobStorage. (#910)",
                )
            )
        }
    }

    
    private fun climbToTop(start: KtExpression): KtExpression {
        var top: KtExpression = start
        while (true) {
            val parent = top.parent
            if (parent is KtDotQualifiedExpression) top = parent else break
        }
        return top
    }

    
    private fun chainHasSelect(top: KtExpression): Boolean {
        var node: KtExpression? = top
        while (node is KtDotQualifiedExpression) {
            val selector = node.selectorExpression
            if (selector is KtCallExpression && selector.calleeExpression?.text == SELECT) {
                return true
            }
            node = node.receiverExpression
        }
        return false
    }

    private companion object {
        const val PREPARE_SELECT = "prepareSelect"
        const val SELECT = "select"
    }
}
