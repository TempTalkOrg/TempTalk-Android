package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Detects synchronous WCDB calls inside a `suspend fun` body that is not
 * wrapped in `withContext(IO|Default)`.
 *
 * Why: WCDB ops are synchronous, blocking IO. Issue #722 fixed
 * `ContactorUtil.fetchContactors` which had this exact pattern — a
 * `suspend fun` calling `wcdb.contactor.deleteObjects/insertObjects/...`
 * directly, then callers on `lifecycleScope.launch` (Main) hit ANR
 * Crashlytics issue `f4891dc99f35cb83c177f088c540e12f`.
 *
 * Coverage analysis: see [SuspendCoverage] / [analyzeSuspendCoverage].
 *
 * False positives:
 *   - Helper suspend functions that delegate to another suspend (which
 *     itself dispatches to IO) will be flagged. Use `@Suppress` + KDoc.
 *   - Builder boundary skip (e.g., body of `flow { wcdb.. }`) defers to
 *     other rules — this rule only addresses direct suspend bodies.
 */
class BlockingWcdbInSuspend(config: Config) : Rule(
    config,
    "Synchronous WCDB call inside a suspend function with no withContext(IO) wrapper.",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression?.text ?: return
        if (callee !in BLOCKING_WCDB_METHODS) return

        // Check that this is actually a wcdb.* call, not some other class
        // with a deleteObjects-named method.
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
            else -> Unit // COVERED / BUILDER_BOUNDARY / NOT_SUSPEND — skip
        }
    }

    private companion object {
        const val WCDB_ROOT = "wcdb"
        // Synchronous WCDB methods from org.difft.app.database.wcdb facade.
        // Keep aligned with the WCDB DAO surface; extend as the surface grows.
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
