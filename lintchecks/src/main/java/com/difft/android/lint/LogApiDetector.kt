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





















class LogApiDetector : Detector(), Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> =
        
        
        
        
        
        listOf("v", "d", "i", "w", "e", "wtf", "log", "println")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val evaluator = context.evaluator

        if (evaluator.isMemberInClass(method, "android.util.Log")) {
            
            
            
            
            val lMethodName = if (method.name == "println") "d" else method.name
            context.report(
                issue = LOG_NOT_L,
                scope = node,
                location = context.getLocation(node),
                message = "Using `android.util.Log.${method.name}` instead of `L.$lMethodName` " +
                    "(see docs/agents/logging-standards-full.md)",
                quickfixData = quickFixToL(
                    node,
                    lMethodName = lMethodName,
                    originalMethodName = method.name,
                    isLogCall = true,
                ),
            )
            return
        }

        
        
        
        
        
        if (evaluator.isMemberInClass(method, "timber.log.Timber") ||
            evaluator.isMemberInClass(method, "timber.log.Timber.Tree")
        ) {
            if (method.name == "log") {
                
                
                
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

    
































    private fun quickFixToL(
        node: UCallExpression,
        lMethodName: String,
        originalMethodName: String,
        isLogCall: Boolean,
    ): LintFix {
        val args = node.valueArguments
        val isLogPrintln = isLogCall && originalMethodName == "println"
        val messageSource = when {
            
            
            
            isLogPrintln && args.size == 3 -> sourceForMessageArg(args[2])
            
            
            isLogCall && args.size == 3 ->
                "${args[1].asSourceString()} + \": \" + ${args[2].asSourceString()}.stackTraceToString()"
            
            
            
            
            isLogCall && args.size == 2 -> sourceForMessageArg(args[1])
            
            
            
            
            
            
            
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

    














    private fun sourceForMessageArg(arg: org.jetbrains.uast.UExpression): String {
        return if (isThrowableType(arg)) {
            "${arg.asSourceString()}.stackTraceToString()"
        } else {
            arg.asSourceString()
        }
    }

    









    private fun sourceForTimberArgs(args: List<org.jetbrains.uast.UExpression>): String {
        val first = args[0]
        return when {
            isThrowableType(first) && args.size >= 2 ->
                "${args[1].asSourceString()} + \"\\n\" + " +
                    "${first.asSourceString()}.stackTraceToString()"
            else -> sourceForMessageArg(first)
        }
    }

    





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
                See `docs/agents/logging-standards-full.md` for the full rule.
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
                See `docs/agents/logging-standards-full.md`.
            """.trimIndent(),
            category = Category.MESSAGES,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(LogApiDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
