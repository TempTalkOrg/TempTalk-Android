package com.difft.android.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

/**
 * Detects two related anti-patterns:
 *
 *  1. `android.util.Log.{v,d,i,w,e,wtf,println}` — bypasses the project's
 *     logger (`com.difft.android.base.log.lumberjack.L`). Note `println`
 *     has a different arg layout — `Log.println(priority, tag, msg)` —
 *     handled separately in the quick-fix. Direct calls mean:
 *       - is not written to the local log file (only INFO+ via L.x is)
 *       - bypasses UID masking (`L.replaceUid`)
 *     See .claude/rules/logging-standards.md §"日志工具约束".
 *
 *  2. `timber.log.Timber.{v,d,i,w,e,wtf,log}` and `Timber.tag(...).{v,d,i,w,...}` —
 *     L is implemented on top of Timber. Calling Timber directly (static
 *     form OR via the tagged-Tree form) bypasses the same UID masking layer.
 *     `Timber.log(priority, ...)` (priority-first signature) is the same
 *     anti-pattern with a different shape; map to `L.log(priority, ...)`.
 *
 * Both detected in the same method-name visitor — only the receiver class
 * differs, so reusing the AST visitor is cheaper than two detector classes.
 */
class LogApiDetector : Detector(), Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> =
        // Covers all public android.util.Log methods (incl. `println`, which
        // has a different arg layout: priority, tag, msg) and all Timber
        // logging methods. Same list works for both classes — the dispatch
        // happens in visitMethodCall via isMemberInClass.
        // `log` is the priority-first Timber overload (Timber.log(priority, ...)).
        listOf("v", "d", "i", "w", "e", "wtf", "log", "println")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val evaluator = context.evaluator

        if (evaluator.isMemberInClass(method, "android.util.Log")) {
            // `Log.println(priority, tag, msg)` has no direct 1-to-1 L
            // equivalent — the priority would map to L.v/d/i/w/e/wtf based
            // on its int value. Default the quick-fix to `L.d`; the
            // developer re-evaluates the level by inspecting args[0].
            val lMethodName = if (method.name == "println") "d" else method.name
            context.report(
                issue = LOG_NOT_L,
                scope = node,
                location = context.getLocation(node),
                message = "Using `android.util.Log.${method.name}` instead of `L.$lMethodName` " +
                    "(see .claude/rules/logging-standards.md)",
                quickfixData = quickFixToL(
                    node,
                    lMethodName = lMethodName,
                    originalMethodName = method.name,
                    isLogCall = true,
                ),
            )
            return
        }

        // Covers both `Timber.d("msg")` (static dispatch on `timber.log.Timber`)
        // AND the tagged form `Timber.tag("TAG").d("msg")` — the latter routes
        // through `Timber.tag(...)` returning a `Timber.Tree`, then `.d()` is
        // dispatched on `timber.log.Timber.Tree`. Without the second check the
        // chained form silently bypasses the rule.
        if (evaluator.isMemberInClass(method, "timber.log.Timber") ||
            evaluator.isMemberInClass(method, "timber.log.Timber.Tree")
        ) {
            if (method.name == "log") {
                // `Timber.log(priority, ...)` puts priority at args[0], which the
                // generic quick-fix (designed for the v/d/i/w/e/wtf shape) cannot
                // safely rewrite. Report the issue with manual-conversion guidance.
                context.report(
                    issue = TIMBER_DIRECT_CALL,
                    scope = node,
                    location = context.getLocation(node),
                    message = "Calling `Timber.log` directly bypasses `L.replaceUid` " +
                        "UID masking. Use `L.log(priority, ...) { ... }` instead.",
                )
                return
            }
            context.report(
                issue = TIMBER_DIRECT_CALL,
                scope = node,
                location = context.getLocation(node),
                message = "Calling `Timber.${method.name}` directly bypasses `L.replaceUid` " +
                    "UID masking. Use `L.${method.name} { ... }` instead.",
                quickfixData = quickFixToL(
                    node,
                    lMethodName = method.name,
                    originalMethodName = method.name,
                    isLogCall = false,
                ),
            )
        }
    }

    /**
     * Best-effort quick-fix — drops the Log tag argument (L derives its own
     * tag from the stack frame) and wraps the message in a lambda body.
     *
     * **This is a SUGGESTION, not a guaranteed-correct transformation.**
     * The developer MUST review the generated code before accepting:
     *
     *   - Type detection is name-based (`Throwable` / `*Exception` /
     *     `*Error`); exotic subclasses without that naming convention
     *     are not auto-wrapped in `stackTraceToString()`.
     *   - Format-string overloads (`Log.d(tag, "x=%d", val)` /
     *     `Timber.d("x=%d", val)`) are flattened to the format-string
     *     literal — the developer must re-thread format args manually.
     *   - Side-effecting receiver / argument expressions are inlined
     *     verbatim and may be re-evaluated when the lambda runs.
     *   - Non-String message types (`Any?` accepted by Timber's overloads)
     *     other than `Throwable` are NOT type-checked.
     *
     * The body is a Kotlin string-concat expression rather than a wrapped
     * string template, because `args[i].asSourceString()` may itself be an
     * arbitrary expression (e.g. `"failed: " + path`) that cannot be safely
     * embedded inside `"..."` literal quoting. Concatenation works for both
     * literal and expression inputs.
     *
     * `isLogCall` differentiates the two API shapes for 2-arg calls:
     *   - `android.util.Log.x(tag, msg)`   → message is args[1]
     *   - `Timber.x(msg, fmtArg)` or       → message is args[0]
     *     `Timber.x(throwable, msg)`         (Timber's primary exception
     *                                         overload puts Throwable at
     *                                         index 0 and message at 1 —
     *                                         we combine both into the
     *                                         L lambda body)
     */
    private fun quickFixToL(
        node: UCallExpression,
        lMethodName: String,
        originalMethodName: String,
        isLogCall: Boolean,
    ): LintFix {
        val args = node.valueArguments
        val isLogPrintln = isLogCall && originalMethodName == "println"
        val messageSource = when {
            // android.util.Log.println(priority, tag, msg) — message at args[2].
            // The priority arg is dropped (developer maps to L.x level based
            // on its int value).
            isLogPrintln && args.size == 3 -> sourceForMessageArg(args[2])
            // android.util.Log.x(tag, msg, throwable)
            // → L.x { msg + ": " + throwable.stackTraceToString() }
            isLogCall && args.size == 3 ->
                "${args[1].asSourceString()} + \": \" + ${args[2].asSourceString()}.stackTraceToString()"
            // android.util.Log.w(tag, Throwable) is a real 2-arg overload —
            // args[1] is a Throwable, not a String. Without the type-check
            // the generated `L.w { throwableExpr }` would not compile (L's
            // lambda must return String).
            isLogCall && args.size == 2 -> sourceForMessageArg(args[1])
            // Timber overloads:
            //   Timber.x(msg, ...args)         → args[0] is the message
            //   Timber.x(Throwable t)          → args[0] is the throwable
            //   Timber.x(Throwable t, msg, ..) → args[0] = throwable,
            //                                    args[1] = message
            // For the last form we combine both into one lambda body so
            // the developer-provided context is preserved.
            !isLogCall && args.isNotEmpty() -> sourceForTimberArgs(args)
            else -> "\"...\""
        }
        val replacement = "com.difft.android.base.log.lumberjack.L.$lMethodName { $messageSource }"
        return fix()
            .replace()
            .text(node.asSourceString())
            .with(replacement)
            .shortenNames()
            .reformat(true)
            .build()
    }

    /**
     * Returns a Kotlin source expression that produces a `String`, suitable
     * for the body of an `L.x { ... }` lambda.
     *
     * If the argument is already a String-yielding expression, the original
     * source is returned. If it's a `Throwable` (`Log.w(tag, t)` /
     * `Timber.e(t)` overloads, etc.), the source is wrapped in
     * `.stackTraceToString()` so the quick-fix result compiles.
     *
     * Type detection uses a name-based check (`Throwable` / `*Exception` /
     * `*Error`) — it covers the standard library and all common subclasses
     * without needing full PSI type resolution. Worst case is a false
     * negative on an exotic Throwable subclass not following the naming
     * convention; the developer sees a non-compiling fix and adjusts.
     */
    private fun sourceForMessageArg(arg: org.jetbrains.uast.UExpression): String {
        return if (isThrowableType(arg)) {
            "${arg.asSourceString()}.stackTraceToString()"
        } else {
            arg.asSourceString()
        }
    }

    /**
     * Handles Timber's argument layout, where the FIRST argument may be a
     * `Throwable` and a `String` message may follow:
     *
     *   `Timber.e(msg)`                → L.e { msg }
     *   `Timber.e(t)`                  → L.e { t.stackTraceToString() }
     *   `Timber.e(t, msg)`             → L.e { msg + "\n" + t.stackTraceToString() }
     *   `Timber.e(t, msg, fmtArgs...)` → L.e { msg + "\n" + t.stackTraceToString() }
     *                                    (format args dropped — dev re-threads)
     */
    private fun sourceForTimberArgs(args: List<org.jetbrains.uast.UExpression>): String {
        val first = args[0]
        return when {
            isThrowableType(first) && args.size >= 2 ->
                "${args[1].asSourceString()} + \"\\n\" + " +
                    "${first.asSourceString()}.stackTraceToString()"
            else -> sourceForMessageArg(first)
        }
    }

    /**
     * Name-based detection. Covers `java.lang.Throwable` plus any subclass
     * following the `*Exception` / `*Error` convention. Worst case is a
     * false negative on an exotic subclass — developer sees a non-compiling
     * fix and adjusts.
     */
    private fun isThrowableType(arg: org.jetbrains.uast.UExpression): Boolean {
        val typeName = arg.getExpressionType()?.canonicalText.orEmpty()
        return typeName == "java.lang.Throwable" ||
            typeName.endsWith("Exception") ||
            typeName.endsWith("Error")
    }

    companion object {
        val LOG_NOT_L: Issue = Issue.create(
            id = "LogNotL",
            briefDescription = "Using android.util.Log instead of project L logger",
            explanation = """
                TempTalk uses `com.difft.android.base.log.lumberjack.L` as the single \
                logging entry point. Direct `android.util.Log` calls:
                  - do not reach the local log file (FileLoggingTree filters at INFO+)
                  - skip `L.replaceUid` UID masking, leaking phone-number prefixes to \
                    logcat in release builds
                See `.claude/rules/logging-standards.md` for the full rule.
            """.trimIndent(),
            category = Category.MESSAGES,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(LogApiDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )

        val TIMBER_DIRECT_CALL: Issue = Issue.create(
            id = "TimberDirectCall",
            briefDescription = "Calling Timber directly bypasses L's UID masking",
            explanation = """
                `L` is layered on top of Timber. Calling `Timber.{d,i,w,e,...}` directly \
                skips `L.replaceUid`, which masks phone-number-style UIDs before they \
                reach the log file or logcat.
                Use `L.{d,i,w,e,...} { "..." }` instead.
                See `.claude/rules/logging-standards.md`.
            """.trimIndent(),
            category = Category.MESSAGES,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(LogApiDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
