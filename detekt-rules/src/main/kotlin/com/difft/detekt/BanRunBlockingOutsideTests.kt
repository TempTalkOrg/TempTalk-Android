package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Forbids `runBlocking { ... }` in non-test source paths.
 *
 * Why: `runBlocking` blocks the calling thread on completion of the body.
 * When called transitively from the main thread (UI handler, Service
 * callback, layout init, etc.), the entire app freezes. Issue #718 / #722
 * documented multiple cases where this pattern produced ANRs (e.g.,
 * `ContactorUtil.fetchContactors` on `lifecycleScope.launch`).
 *
 * Legitimate exceptions exist (OkHttp `Interceptor.intercept()` is a sync
 * API; startup recovery decisions before WCDB is ready; etc.) — those must
 * be marked with `@Suppress("BanRunBlockingOutsideTests")` and a KDoc /
 * inline comment explaining why.
 *
 * This rule does NOT distinguish thread of caller; we ban the pattern
 * uniformly because Detekt cannot reliably trace caller dispatcher.
 *
 * Scope:
 *   - Test source paths are excluded via `excludes:` in detekt.yml — Detekt 2.0
 *     (K2) returns only the filename in `virtualFilePath`, so in-code path
 *     filtering is unreliable; YAML `excludes:` operates on project-relative
 *     paths and is the canonical mechanism.
 *   - `kotlinx.coroutines.test.runBlocking` would still match the simple
 *     callee name "runBlocking" — relies on YAML excludes to skip test files.
 */
class BanRunBlockingOutsideTests(config: Config) : Rule(
    config,
    "Forbids runBlocking outside test source paths to prevent main-thread blocking.",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression?.text ?: return
        if (callee != CALLEE_NAME) return

        // Test-source exclusion is configured in detekt.yml `excludes:`.
        // Detekt 2.0 (K2) `virtualFilePath` returns only the file name (see
        // detekt#6965), making in-code path filtering unreliable.

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
