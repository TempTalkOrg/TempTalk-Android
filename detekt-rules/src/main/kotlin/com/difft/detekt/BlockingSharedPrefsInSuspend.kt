package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Detects synchronous SharedPreferences-family reads/writes inside a
 * `suspend fun` body that is not wrapped in `withContext(IO)`.
 *
 * Targets project-specific SP wrappers (`SecureSharedPrefsUtil`,
 * `SharedPrefsUtil`) and direct `SharedPreferences.commit`.
 *
 * Why: SP reads/writes block on disk; `commit()` runs an fsync.
 * Issue #722 documented `LoginViewModel.signIn` cases where SP writes
 * happened on `viewModelScope.launch` (Main) without `withContext`.
 *
 * Coverage analysis: see [analyzeSuspendCoverage].
 *
 * Note: `apply()` is async by design and not flagged.
 */
class BlockingSharedPrefsInSuspend(config: Config) : Rule(
    config,
    "Synchronous SharedPreferences call inside a suspend function with no withContext(IO).",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression?.text ?: return

        val signature = when {
            callee in BLOCKING_SP_METHODS -> {
                // Need to verify receiver — these method names are common.
                val root = expression.rootReceiverText() ?: return
                if (root !in SP_FACADE_ROOTS) return
                "$root.$callee"
            }
            callee == "commit" -> {
                // SharedPreferences.Editor.commit() — accept any receiver but
                // require it's not "View" / "Activity" common methods. Detekt
                // without type resolution can't fully disambiguate; we accept
                // false positives here and rely on @Suppress.
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
        // Project-specific SP wrapper classes used as receiver roots.
        val SP_FACADE_ROOTS = setOf(
            "SecureSharedPrefsUtil",
            "SharedPrefsUtil",
        )
        // Synchronous SP read/write method names on the facades.
        // `apply()` (async) is deliberately excluded.
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
