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






















class ChativeHttpClientDetector : Detector(), Detector.UastScanner {

    override fun getApplicableConstructorTypes(): List<String> = listOf(
        "okhttp3.OkHttpClient",
        "okhttp3.OkHttpClient.Builder",
    )

    override fun visitConstructor(
        context: JavaContext,
        node: UCallExpression,
        constructor: PsiMethod,
    ) {
        val typeName = constructor.containingClass?.qualifiedName ?: return
        val isBuilder = typeName.endsWith(".Builder")
        val displayName = if (isBuilder) "OkHttpClient.Builder()" else "OkHttpClient()"

        context.report(
            issue = CHATIVE_HTTP_CLIENT_REQUIRED,
            scope = node,
            location = context.getLocation(node),
            message = "Direct `$displayName` bypasses ChativeHttpClient's auth, TLS, " +
                "and shared connection pool. Inject `ChativeHttpClient` via Hilt and " +
                "call `getService(...)` instead. If a legitimate exception applies " +
                "(non-auth domain, e.g. OSS uploads), suppress with " +
                "`@SuppressLint(\"ChativeHttpClientRequired\")` plus a code comment " +
                "explaining why.",
            quickfixData = null, 
        )
    }

    companion object {
        val CHATIVE_HTTP_CLIENT_REQUIRED: Issue = Issue.create(
            id = "ChativeHttpClientRequired",
            briefDescription = "Direct OkHttpClient bypasses ChativeHttpClient",
            explanation = "TempTalk routes all backend HTTP through " +
                "`com.difft.android.network.ChativeHttpClient`, which provides " +
                "authentication interceptors, TLS pinning, URL routing, and a " +
                "shared connection pool. Constructing OkHttpClient directly in " +
                "business modules:\n" +
                "  - Skips authentication (server requests fail with 401)\n" +
                "  - Skips TLS pinning (MITM risk)\n" +
                "  - Creates a private connection pool + dispatcher (memory leak " +
                "    / pthread_create OOM — see WebSocketConnection.kt:62-66)\n\n" +
                "Inject `ChativeHttpClient` via Hilt and use `getService(...)` " +
                "instead.\n\n" +
                "The `network/` module is exempt (it contains ChativeHttpClient " +
                "itself plus documented low-level WebSocket / SpeedTest clients).",
            category = Category.SECURITY,
            priority = 7,
            severity = Severity.ERROR,
            implementation = Implementation(
                ChativeHttpClientDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
