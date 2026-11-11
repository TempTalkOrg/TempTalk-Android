package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression













class UnsafeWcdbConflictInsert(config: Config) : Rule(
    config,
    "WCDB conflict insert (insertOrReplace*/insertOrIgnore*) on a databaseId-PK " +
        "table collapses a batch into one row — silent data loss.",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression?.text ?: return
        if (callee !in CONFLICT_INSERT_METHODS) return

        val accessor = expression.tableAccessor() ?: return
        if (accessor in NATURAL_KEY_PK_ACCESSORS) return

        report(
            Finding(
                entity = Entity.from(expression),
                message = "`$callee` on `$accessor` resolves its conflict on the PRIMARY KEY. " +
                    "If `$accessor` uses an auto-increment `databaseId` PK, a batch of " +
                    "freshly-built models (all `databaseId = 0`) collapses into a single row — " +
                    "silent data loss. CORRECT pattern: read-then-write (recover the existing " +
                    "`databaseId` by natural key, then insertOrReplace/update). " +
                    "ONLY IF `$accessor`'s model declares its PRIMARY KEY as the natural key " +
                    "(`@WCDBField(isPrimary = true)` on the business field, NOT an auto-increment " +
                    "`databaseId`): add \"$accessor\" to NATURAL_KEY_PK_ACCESSORS in " +
                    "detekt-rules/src/main/kotlin/com/difft/detekt/UnsafeWcdbConflictInsert.kt. (#914)",
            )
        )
    }

    






    private fun KtCallExpression.tableAccessor(): String? {
        val qualified = parent as? KtQualifiedExpression ?: return null
        return when (val receiver = qualified.receiverExpression) {
            is KtQualifiedExpression -> receiver.selectorExpression?.text
            else -> receiver.text
        }
    }

    private companion object {
        val CONFLICT_INSERT_METHODS = setOf(
            "insertOrReplaceObject",
            "insertOrReplaceObjects",
            "insertOrIgnoreObject",
            "insertOrIgnoreObjects",
        )

        














        val NATURAL_KEY_PK_ACCESSORS = setOf(
            "publicKeyInfo",         
            "groupCryptoKeys",       
            "draft",                 
            "pendingMessageNew",     
            "resetIdentityKey",      
            "readInfo",              
            "readInfoTable",         
            "groupCryptoKeysTable",  
            "notificationCache",     
            "pendingRemovalContact", 
            "jobSpec",               
            "jobConstraint",         
            "favoriteGifs",          
        )
    }
}
