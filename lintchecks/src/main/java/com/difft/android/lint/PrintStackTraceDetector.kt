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
 * Detects no-arg `Throwable.printStackTrace()` — which drops the stack to
 * stderr and is lost in production builds.
 *
 * The overloads `printStackTrace(PrintStream)` and `printStackTrace(PrintWriter)`
 * are EXEMPT — they're the standard Java pattern for stack-trace string
 * extraction (see `BaseTree.getStackTraceString` and `ExceptionUtil` in :base).
 */
class PrintStackTraceDetector : Detector(), Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> = listOf("printStackTrace")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        // Only flag the zero-arg overload. Stream/writer overloads have args.
        if (node.valueArgumentCount != 0) return

        val evaluator = context.evaluator
        // Walks the class hierarchy so a `printStackTrace` override on a
        // Throwable subclass (e.g. `class MyException : Exception() { ... }`)
        // also triggers the rule. `isMemberInClass` alone would miss it.
        val inThrowableHierarchy = evaluator.isMemberInClass(method, "java.lang.Throwable") ||
            evaluator.extendsClass(method.containingClass, "java.lang.Throwable", false)
        if (!inThrowableHierarchy) return

        // Receiver can be null when the call is `printStackTrace()` with an
        // implicit `this` — i.e. from inside a Throwable subclass method.
        // Fall back to `this` so the generated `L.e { ... }` lambda still
        // compiles in that case (`e` would be an undefined identifier).
        val receiverSrc = node.receiver?.asSourceString() ?: "this"
        context.report(
            issue = PRINT_STACK_TRACE_NO_ARG,
            scope = node,
            location = context.getLocation(node),
            message = "`$receiverSrc.printStackTrace()` drops the stack to stderr — not " +
                "captured in release builds. Use `L.e { \"…: \${$receiverSrc.stackTraceToString()}\" }`.",
            quickfixData = quickFixToLe(node, receiverSrc),
        )
    }

    /**
     * Generates the replacement `L.e { "...: ${<receiver>.stackTraceToString()}" }`.
     *
     * CAVEAT: the receiver source is embedded verbatim — twice would change
     * behavior. For simple receivers (`e.printStackTrace()`, `t.printStackTrace()`),
     * which are 99% of project usage, this is correct because `stackTraceToString`
     * is called exactly once, just like the original `printStackTrace()` was.
     *
     * For side-effecting receivers (`computeException().printStackTrace()` —
     * not seen in this codebase but theoretically valid), the lambda body
     * captures the receiver expression and invokes it again when the lambda
     * runs. The developer must review the quick-fix and capture the receiver
     * into a `val` first if re-evaluation is unsafe.
     */
    private fun quickFixToLe(node: UCallExpression, receiver: String): LintFix {
        val replacement = "com.difft.android.base.log.lumberjack.L.e { " +
            "\"...: \${$receiver.stackTraceToString()}\" }"
        return fix()
            .replace()
            .text(node.asSourceString())
            .with(replacement)
            .shortenNames()
            .reformat(true)
            .build()
    }

    companion object {
        val PRINT_STACK_TRACE_NO_ARG: Issue = Issue.create(
            id = "PrintStackTraceNoArg",
            briefDescription = "Throwable.printStackTrace() drops stack to stderr",
            explanation = "The no-arg `Throwable.printStackTrace()` overload writes to stderr — " +
                "invisible in release builds, not captured by `FileLoggingTree`, not reported " +
                "to Crashlytics. Use `L.e { \"...: \" + e.stackTraceToString() }` (or the Java " +
                "supplier form `L.e(e, () -> \"...\")`).\n\n" +
                "The `printStackTrace(PrintStream)` / `printStackTrace(PrintWriter)` overloads " +
                "are EXEMPT — they are the standard Java idiom for converting a stack trace to " +
                "a string (see :base's `ExceptionUtil` and `BaseTree`).",
            category = Category.MESSAGES,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(PrintStackTraceDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
