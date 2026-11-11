package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression








class BlockingWcdbInSuspend(config: Config) : Rule(
    config,
    "Synchronous WCDB call inside a suspend function with no withContext(IO) wrapper.",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression?.text ?: return
        if (callee !in BLOCKING_WCDB_METHODS) return

        
        
        if (expression.rootReceiverText() != WCDB_ROOT) return

        when (analyzeSuspendCoverage(expression)) {
            SuspendCoverage.UNCOVERED -> {
                report(
                    Finding(
                        entity = Entity.from(expression),
                        message = "WCDB `$callee` runs synchronously; wrap the enclosing " +
                            "suspend function body in `withContext(Dispatchers.IO)` or move " +
                            "to a dedicated IO scope.",
                    )
                )
            }
            else -> Unit 
        }
    }

    private companion object {
        const val WCDB_ROOT = "wcdb"
        
        
        val BLOCKING_WCDB_METHODS = setOf(
            "deleteObjects",
            "insertObjects",
            "updateObjects",
            "getAllObjects",
            "getFirstObject",
            "getObjects",
            "selectObject",
            "selectObjects",
        )
    }
}
