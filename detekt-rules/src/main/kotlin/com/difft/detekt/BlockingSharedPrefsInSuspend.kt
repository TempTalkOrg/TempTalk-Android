package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
















class BlockingSharedPrefsInSuspend(config: Config) : Rule(
    config,
    "Synchronous SharedPreferences call inside a suspend function with no withContext(IO).",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression?.text ?: return

        val signature = when {
            callee in BLOCKING_SP_METHODS -> {
                
                val root = expression.rootReceiverText() ?: return
                if (root !in SP_FACADE_ROOTS) return
                "$root.$callee"
            }
            callee == "commit" -> {
                
                
                
                
                "commit"
            }
            else -> return
        }

        when (analyzeSuspendCoverage(expression)) {
            SuspendCoverage.UNCOVERED -> {
                report(
                    Finding(
                        entity = Entity.from(expression),
                        message = "Synchronous SP call `$signature` in suspend context; " +
                            "wrap the enclosing suspend body in `withContext(Dispatchers.IO)`.",
                    )
                )
            }
            else -> Unit
        }
    }

    private companion object {
        
        val SP_FACADE_ROOTS = setOf(
            "SecureSharedPrefsUtil",
            "SharedPrefsUtil",
        )
        
        
        val BLOCKING_SP_METHODS = setOf(
            "getString", "putString",
            "getBoolean", "putBoolean",
            "getInt", "putInt",
            "getLong", "putLong",
            "getFloat", "putFloat",
            "getStringSet", "putStringSet",
            "getBasicAuth", "putBasicAuth",
        )
    }
}
