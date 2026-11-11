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












class PrintLnDetector : Detector(), Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> =
        listOf("println", "print")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val evaluator = context.evaluator

        
        
        if (evaluator.isMemberInClass(method, "kotlin.io.ConsoleKt")) {
            report(context, node, "kotlin.io.${method.name}")
            return
        }

        
        if (evaluator.isMemberInClass(method, "java.io.PrintStream")) {
            val streamName = resolveSystemStream(node.receiver) ?: return
            report(context, node, "System.$streamName.${method.name}")
        }
    }

    















    private fun resolveSystemStream(receiver: UExpression?): String? {
        when (receiver) {
            is UQualifiedReferenceExpression -> {
                
                val selector = receiver.selector.asRenderString()
                if (selector != "out" && selector != "err") return null
                val resolved = (receiver.receiver as? UReferenceExpression)?.resolve()
                val qualifiedName = (resolved as? com.intellij.psi.PsiClass)?.qualifiedName
                return if (qualifiedName == "java.lang.System") selector else null
            }
            is UReferenceExpression -> {
                
                
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
                "per docs/agents/logging-standards-full.md) or `L.i` for permanent logging.",
            category = Category.MESSAGES,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(PrintLnDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
