package com.difft.android.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression







class ForegroundServiceStarterDetector : Detector(), Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> = listOf("startForeground")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val evaluator = context.evaluator
        
        
        
        
        val inServiceHierarchy = evaluator.isMemberInClass(method, "android.app.Service") ||
            evaluator.extendsClass(method.containingClass, "android.app.Service", false)
        if (!inServiceHierarchy) return

        context.report(
            issue = FOREGROUND_SERVICE_STARTER_REQUIRED,
            scope = node,
            location = context.getLocation(node),
            message = "Direct `service.startForeground(...)` re-introduces the " +
                "`ForegroundServiceStartNotAllowedException` crash signature on " +
                "Android 12+. Call `ForegroundServiceStarter.startForegroundSafely(...)` " +
                "from `base/utils/ForegroundServiceStarter.kt` instead.",
            quickfixData = null, 
        )
    }

    companion object {
        val FOREGROUND_SERVICE_STARTER_REQUIRED: Issue = Issue.create(
            id = "ForegroundServiceStarterRequired",
            briefDescription = "Service.startForeground bypasses crash-safe wrapper",
            explanation = "Android 12 (API 31) added a process-level " +
                "`mAllowStartForeground` gate; when closed, `Service.startForeground` " +
                "throws `ForegroundServiceStartNotAllowedException`. Without a catch " +
                "the process FATAL-kills (see crash 8cd6fe30 / PR #788).\n\n" +
                "The project routes all foreground-service starts through " +
                "`com.difft.android.base.utils.ForegroundServiceStarter.startForegroundSafely`, " +
                "which:\n" +
                "  - catches FGSNAE narrowly (other IllegalStateException still throws)\n" +
                "  - calls `stopSelf()` on refusal so caller can return early\n" +
                "  - logs the first occurrence with full stacktrace (throttled)\n\n" +
                "If you must bypass (extremely rare — only the helper itself does), " +
                "suppress with `@SuppressLint(\"ForegroundServiceStarterRequired\")` " +
                "AND document the FGSNAE-safe reason.",
            category = Category.CORRECTNESS,
            priority = 7,
            severity = Severity.ERROR,
            implementation = Implementation(
                ForegroundServiceStarterDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
