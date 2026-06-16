package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Flags a WCDB `prepareSelect()` chain that never calls `.select(...)`.
 *
 * `Select.fields` defaults to null and is set only by `.select(fields)`; without it
 * `.allObjects()`/`.firstObject()` NPE at runtime (`Field.getBindClass(null).length`) —
 * yet it compiles fine. This was the read-receipt regression from #910, which JVM tests
 * (WCDB native is `@Ignore`d) and static review structurally can't catch; a compile-time
 * rule can.
 *
 * Correct form (see [com.difft.android.chat.jobs.WcdbJobStorage]):
 *   `wcdb.x.prepareSelect().select(*Model.allBindingFields())...allObjects()`
 *
 * False positive: splitting `prepareSelect()` and `.select(...)` across statements isn't
 * detected as having a select — keep them in one chain or `@Suppress` with a note.
 */
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

    /** Climb to the outermost dot-qualified expression containing [start] — the whole chain. */
    private fun climbToTop(start: KtExpression): KtExpression {
        var top: KtExpression = start
        while (true) {
            val parent = top.parent
            if (parent is KtDotQualifiedExpression) top = parent else break
        }
        return top
    }

    /** Walk the receiver chain of [top], returning true if any link calls `.select(...)`. */
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
