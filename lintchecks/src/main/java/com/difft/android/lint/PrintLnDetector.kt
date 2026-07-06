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
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression

/**
 * Detects Kotlin top-level `println` / `print` AND `System.out.*` /
 * `System.err.*` — all four write to stdout/stderr and never reach the
 * local log file or the UID masking layer.
 *
 * Receiver resolution uses PSI (not an `endsWith("System")` string match)
 * to avoid false positives on lookalike classes from other packages.
 *
 * `ignoreTestSources = true` (configured in root build.gradle.kts) keeps unit
 * tests free to use println as an observation tool during flow/coroutine work.
 */
class PrintLnDetector : Detector(), Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> =
        listOf("println", "print")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val evaluator = context.evaluator

        // Kotlin top-level println/print: both live on kotlin.io.ConsoleKt
        // (kotlin-stdlib). Either is equally invisible in release builds.
        if (evaluator.isMemberInClass(method, "kotlin.io.ConsoleKt")) {
            report(context, node, "kotlin.io.${method.name}")
            return
        }

        // System.out.println / System.err.println / etc.
        if (evaluator.isMemberInClass(method, "java.io.PrintStream")) {
            val streamName = resolveSystemStream(node.receiver) ?: return
            report(context, node, "System.$streamName.${method.name}")
        }
    }

    /**
     * Returns "out" or "err" if the receiver is `java.lang.System.{out,err}`,
     * null otherwise. Uses the PSI resolver instead of an `endsWith("System")`
     * string match — the latter would false-positive on any class whose name
     * ends in "System" (e.g. `com.acme.System`) and false-negative on
     * `import java.lang.System.out` + bare `out.println(...)`.
     *
     * Two receiver shapes are handled:
     *
     *  1. `System.out.println(...)` — `node.receiver` is a
     *     [UQualifiedReferenceExpression] (`System.out`).
     *
     *  2. `import java.lang.System.out; out.println(...)` — `node.receiver`
     *     is a bare [UReferenceExpression] resolving to the `out` / `err`
     *     PsiField on `java.lang.System`.
     */
    private fun resolveSystemStream(receiver: UExpression?): String? {
        when (receiver) {
            is UQualifiedReferenceExpression -> {
                // Qualified form: `<System>.out` / `<System>.err`
                val selector = receiver.selector.asRenderString()
                if (selector != "out" && selector != "err") return null
                val resolved = (receiver.receiver as? UReferenceExpression)?.resolve()
                val qualifiedName = (resolved as? com.intellij.psi.PsiClass)?.qualifiedName
                return if (qualifiedName == "java.lang.System") selector else null
            }
            is UReferenceExpression -> {
                // Static-import form: bare `out` / `err` resolved to the field
                // on java.lang.System.
                val field = receiver.resolve() as? com.intellij.psi.PsiField ?: return null
                val name = field.name
                if (name != "out" && name != "err") return null
                val containingClass = field.containingClass?.qualifiedName ?: return null
                return if (containingClass == "java.lang.System") name else null
            }
            else -> return null
        }
    }

    private fun report(context: JavaContext, node: UCallExpression, api: String) {
        context.report(
            issue = KOTLIN_IO_PRINTLN,
            scope = node,
            location = context.getLocation(node),
            message = "Using `$api` instead of project logger. " +
                "Output to stderr/stdout never reaches the local log file.",
            quickfixData = quickFixToLd(node),
        )
    }

    /**
     * Best-effort quick-fix from `println(x)` / `print(x)` to `L.d { ... }`.
     *
     * **This is a SUGGESTION, not a guaranteed-correct transformation.**
     * The developer MUST review the generated code before accepting:
     *
     *   - Kotlin's `println` accepts `Any?` — non-String arguments
     *     (`println(42)`, `println(someList)`) are wrapped in `.toString()`
     *     so the generated `L.d { ... }` lambda compiles, but the chosen
     *     log level (`L.d`) and the resulting representation may not be
     *     what the developer wants.
     *   - L.d is the closest equivalent (debug-only, dropped from
     *     production log file via FileLoggingTree). Developer should
     *     re-evaluate whether `L.i` (permanent) is more appropriate.
     *   - Type detection uses the same name-based heuristic as
     *     [LogApiDetector]; exotic Throwable subclasses are not
     *     auto-wrapped in `stackTraceToString()`.
     */
    private fun quickFixToLd(node: UCallExpression): LintFix {
        val args = node.valueArguments
        val msg = if (args.isEmpty()) "\"\"" else messageSource(args[0])
        val replacement = "com.difft.android.base.log.lumberjack.L.d { $msg }"
        return fix()
            .replace()
            .text(node.asSourceString())
            .with(replacement)
            .shortenNames()
            .reformat(true)
            .build()
    }

    /**
     * Converts a `println` / `print` argument expression into a Kotlin
     * source expression that produces a `String`.
     *
     *   - String argument            → returned as-is
     *   - Throwable argument         → wrapped in `.stackTraceToString()`
     *   - Any other (Int, List, ...) → wrapped in `.toString()` so the
     *                                  L.d lambda body compiles
     *
     * Empty type info (e.g. when the resolver hasn't bound the expression
     * yet) is treated as `String` — the most common print target — to
     * avoid spurious `.toString()` insertions on literals.
     */
    private fun messageSource(arg: org.jetbrains.uast.UExpression): String {
        val typeName = arg.getExpressionType()?.canonicalText.orEmpty()
        val isThrowable = typeName == "java.lang.Throwable" ||
            typeName.endsWith("Exception") ||
            typeName.endsWith("Error")
        val isStringLike = typeName == "java.lang.String" || typeName.isEmpty()
        return when {
            isThrowable -> "${arg.asSourceString()}.stackTraceToString()"
            isStringLike -> arg.asSourceString()
            else -> "${arg.asSourceString()}.toString()"
        }
    }

    companion object {
        val KOTLIN_IO_PRINTLN: Issue = Issue.create(
            id = "KotlinIOPrintLn",
            briefDescription = "Using println/print/System.{out,err} instead of project logger",
            explanation = "Kotlin top-level `println` / `print` and " +
                "`System.out.{print,println}` / `System.err.{print,println}` all " +
                "write to stdout/stderr. In release builds that output is invisible " +
                "— it never reaches the local log file (`FileLoggingTree`) and is " +
                "not UID-masked.\n\n" +
                "Use `L.d { \"...\" }` for transient debug output (cleaned up pre-PR " +
                "per .claude/rules/logging-standards.md) or `L.i` for permanent logging.",
            category = Category.MESSAGES,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(PrintLnDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
