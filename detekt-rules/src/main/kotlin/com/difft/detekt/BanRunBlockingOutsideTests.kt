package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression









class BanRunBlockingOutsideTests(config: Config) : Rule(
    config,
    "Forbids runBlocking outside test source paths to prevent main-thread blocking.",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression?.text ?: return
        if (callee != CALLEE_NAME) return

        
        
        

        report(
            Finding(
                entity = Entity.from(expression),
                message = "runBlocking{} is banned outside tests; use suspend + " +
                    "withContext, or add @Suppress with rationale.",
            )
        )
    }

    private companion object {
        const val CALLEE_NAME = "runBlocking"
    }
}
