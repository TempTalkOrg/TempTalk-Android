package com.difft.detekt

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * Shared call-tree analysis helpers for the "blocking-in-suspend" rule family.
 *
 * The rules in this set look for **blocking API calls** (WCDB, SharedPreferences,
 * synchronous File IO) that execute on the wrong dispatcher when invoked from
 * a suspend function without an explicit `withContext(IO)` wrapper.
 *
 * Coverage rules:
 *   - If the call's nearest enclosing lambda is the body of `withContext(...)`
 *     → COVERED (skip; dispatcher is explicitly switched).
 *   - If the call's nearest enclosing lambda is the body of a coroutine builder
 *     (`launch`, `async`, `runBlocking`, `flow`, `produce`, `channelFlow`,
 *     `callbackFlow`) → BUILDER_BOUNDARY (skip; a different rule
 *     [LifecycleScopeBlockingCall] handles these cases, or the builder caller
 *     is responsible).
 *   - Walk up past non-relevant lambdas (`forEach`, `map`, `apply`, etc.)
 *     to find the enclosing function.
 *   - If we reach a `suspend` function without hitting `withContext` →
 *     UNCOVERED (report).
 *   - Otherwise → NOT_SUSPEND (skip).
 */
internal enum class SuspendCoverage {
    /** Call site is inside withContext(...) — dispatcher is explicit. */
    COVERED,

    /** Crossed a coroutine builder lambda; this rule does not apply. */
    BUILDER_BOUNDARY,

    /** Inside a suspend function body without withContext wrapping. */
    UNCOVERED,

    /** Not inside any suspend context — this rule does not apply. */
    NOT_SUSPEND,
}

// Coroutine builders that establish a new dispatcher context or hand control
// off to an external scope (so blocking inside their lambda is the caller's
// or the builder's concern, not this rule's). Deliberately excluded:
//   - `coroutineScope` / `supervisorScope` — these inherit the caller's
//     CoroutineContext transparently, like `forEach`/`apply`. Including them
//     here would mask `coroutineScope { wcdb.foo() }` inside a suspend fun on
//     Main — exactly the ANR pattern this rule must catch.
internal val COROUTINE_BUILDER_NAMES = setOf(
    "launch", "async", "runBlocking", "flow", "produce", "channelFlow",
    "callbackFlow",
)

internal fun analyzeSuspendCoverage(call: KtCallExpression): SuspendCoverage {
    var node: PsiElement? = call.parent
    while (node != null) {
        when (node) {
            is KtLambdaExpression -> {
                // We're inside a lambda. Find the call that this lambda is an argument to.
                val enclosingCall = findCallTakingLambda(node)
                val callee = enclosingCall?.calleeExpression?.text
                when {
                    callee == "withContext" -> return SuspendCoverage.COVERED
                    callee in COROUTINE_BUILDER_NAMES -> return SuspendCoverage.BUILDER_BOUNDARY
                    // Other lambda (forEach, apply, ...) — keep walking up.
                }
            }
            is KtNamedFunction -> {
                return if (node.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
                    SuspendCoverage.UNCOVERED
                } else {
                    SuspendCoverage.NOT_SUSPEND
                }
            }
        }
        node = node.parent
    }
    return SuspendCoverage.NOT_SUSPEND
}

/**
 * Given a lambda expression, find the [KtCallExpression] that the lambda is
 * an argument to (handles both trailing-lambda and parenthesized-lambda forms).
 * Returns null if the lambda is not used as a call argument.
 */
private fun findCallTakingLambda(lambda: KtLambdaExpression): KtCallExpression? {
    // Trailing lambda: `launch { ... }` → KtLambdaArgument → KtCallExpression
    val asArg = lambda.parent as? KtLambdaArgument
    if (asArg != null) return asArg.parent as? KtCallExpression
    // Parenthesized: `launch({ ... })` → KtValueArgument → KtValueArgumentList → KtCallExpression
    var node: PsiElement? = lambda.parent
    while (node != null) {
        if (node is KtCallExpression) return node
        if (node is KtNamedFunction) return null
        node = node.parent
    }
    return null
}

/**
 * Returns the leftmost (root) receiver identifier text of a qualified call chain.
 *
 * For `wcdb.contactor.deleteObjects(...)`, walking up from the `deleteObjects`
 * call expression yields `wcdb` as the root receiver.
 *
 * Uses [KtQualifiedExpression] (the shared supertype of [KtDotQualifiedExpression]
 * `.` and [KtSafeQualifiedExpression] `?.`) so safe-call chains like
 * `wcdb?.contactor.deleteObjects(...)` are also recognized.
 *
 * Returns null if the call has no receiver chain (e.g., a top-level function).
 */
internal fun KtCallExpression.rootReceiverText(): String? {
    val qualified = parent as? KtQualifiedExpression ?: return null
    var receiver: PsiElement = qualified.receiverExpression
    while (receiver is KtQualifiedExpression) {
        receiver = receiver.receiverExpression
    }
    return receiver.text
}
