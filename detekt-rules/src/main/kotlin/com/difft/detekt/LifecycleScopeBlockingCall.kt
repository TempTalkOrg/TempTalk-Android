package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * Detects `lifecycleScope.launch { ... }` / `viewModelScope.launch { ... }`
 * calls that do NOT explicitly pass a non-Main dispatcher.
 *
 * Why: `lifecycleScope` and `viewModelScope` default to `Dispatchers.Main`.
 * Any blocking code inside the lambda body executes on the UI thread.
 * This is the exact pattern that produced the ContactorUtil ANR in
 * issue #718.
 *
 * Heuristic: flag the `launch { ... }` call when:
 *   - receiver root identifier is `lifecycleScope` or `viewModelScope`
 *     (also matches `viewLifecycleOwner.lifecycleScope` via root-receiver
 *     walking → `viewLifecycleOwner`; see false-positive note below)
 *   - the `launch` call has no positional argument that mentions
 *     `Dispatchers.IO` / `Dispatchers.Default`
 *
 * False positives:
 *   - Lambdas that only do non-blocking suspend work (collect, flow, ...)
 *     are also flagged. Add `@Suppress("LifecycleScopeBlockingCall")` with
 *     a KDoc explaining the body is non-blocking.
 *   - `viewLifecycleOwner.lifecycleScope` form: root receiver text is
 *     `viewLifecycleOwner`, so we additionally match by callee receiver
 *     immediate parent (see [isMainBoundScope]).
 *
 * Severity: warning (high false-positive rate without type resolution).
 */
class LifecycleScopeBlockingCall(config: Config) : Rule(
    config,
    "lifecycleScope/viewModelScope.launch without explicit non-Main dispatcher.",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        if (expression.calleeExpression?.text != LAUNCH) return
        if (!isMainBoundScope(expression)) return
        if (hasNonMainDispatcherArg(expression)) return

        report(
            Finding(
                entity = Entity.from(expression),
                message = "$LAUNCH on a Main-bound scope without explicit dispatcher; " +
                    "blocking code inside this lambda runs on the UI thread. " +
                    "Pass `Dispatchers.IO`/`Dispatchers.Default`, or `@Suppress` " +
                    "if the body is verifiably non-blocking.",
            )
        )
    }

    /** Receiver of `.launch(...)` is a known Main-bound scope. */
    private fun isMainBoundScope(launchCall: KtCallExpression): Boolean {
        // Use KtQualifiedExpression (shared supertype of KtDotQualifiedExpression `.`
        // and KtSafeQualifiedExpression `?.`) so safe-call forms like
        // `viewLifecycleOwner?.lifecycleScope.launch{}` are also matched.
        val qualified = launchCall.parent as? KtQualifiedExpression ?: return false
        // Walk to the immediate receiver of the .launch member access.
        // For `lifecycleScope.launch{}` → receiver is identifier `lifecycleScope`.
        // For `viewLifecycleOwner.lifecycleScope.launch{}` → receiver is
        //   another KtQualifiedExpression `viewLifecycleOwner.lifecycleScope`.
        val receiverText = qualified.receiverExpression.text
        // Either bare or member-access form ending in a Main-bound scope name.
        return MAIN_BOUND_SCOPES.any { scope ->
            receiverText == scope || receiverText.endsWith(".$scope")
        }
    }

    /** True when launch(...) has an arg referencing Dispatchers.IO / .Default. */
    private fun hasNonMainDispatcherArg(launchCall: KtCallExpression): Boolean {
        // Filter out trailing-lambda arguments — only positional/named value args
        // can carry a dispatcher.
        val explicitArgs = launchCall.valueArguments.filter { it !is KtLambdaArgument }
        return explicitArgs.any { arg ->
            val text = arg.text ?: return@any false
            NON_MAIN_DISPATCHER_MARKERS.any { marker -> marker in text }
        }
    }

    private companion object {
        const val LAUNCH = "launch"
        val MAIN_BOUND_SCOPES = setOf(
            "lifecycleScope",
            "viewModelScope",
            // Note: `appScope` is now IO-bound per PR #722; not included.
        )
        // Text markers for non-Main dispatchers; matched against arg source text.
        // Using string-match because rules run without type resolution.
        val NON_MAIN_DISPATCHER_MARKERS = listOf(
            "Dispatchers.IO",
            "Dispatchers.Default",
            "Dispatchers.Unconfined",
        )
    }
}
