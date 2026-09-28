package com.difft.android.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.getContainingUClass

/**
 * Keeps every Retrofit endpoint's auth style declared exactly once, at the endpoint (issue #1231).
 *
 * `HeaderInterceptor` is the only place `Authorization` is set, and it resolves the style from the
 * `@Auth` declaration it reads off Retrofit's `Invocation` tag. Three ways to defeat that are
 * reported here:
 *   - an endpoint with no declaration at all — it would take the interceptor's fail-safe fallback,
 *     which logs an error nobody reads in a release build
 *   - an endpoint that carries the header by hand — the drift #1231 removed: a call site's cached
 *     JWT outlives its 20-minute lifetime, and the endpoint gets two sources of truth
 *   - an `@Auth(AuthStyle.EXPLICIT)` endpoint with no header parameter left to supply the
 *     credential — it would go out unauthenticated, silently
 *
 * If a legitimate exception ever exists, suppress at the method with the matching
 * `@SuppressLint("...")` AND a comment explaining why — the convention
 * [ChativeHttpClientDetector] documents.
 */
class AuthStyleDetector : Detector(), Detector.UastScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitMethod(node: UMethod) {
            if (!node.hasHttpAnnotation()) return

            // Method declaration wins over the interface's, matching AuthStyleResolver.
            val auth = node.ownAuth() ?: node.getContainingUClass()?.classAuth()
            val headerParams = node.authorizationHeaderParams()

            if (auth == null) {
                context.report(
                    issue = AUTH_STYLE_MISSING,
                    scope = node as UElement,
                    location = context.getNameLocation(node),
                    message = "`${node.name}` declares no auth style. Add " +
                        "`@Auth(AuthStyle.BASIC|JWT|NONE|EXPLICIT)` to the method, or to the " +
                        "service interface when every endpoint on it shares one style.",
                    quickfixData = null, // The right style is semantic — no mechanical quick-fix.
                )
                return
            }

            if (auth.isExplicit()) {
                if (headerParams.isEmpty()) {
                    context.report(
                        issue = AUTH_EXPLICIT_MISSING_HEADER,
                        scope = node as UElement,
                        location = context.getNameLocation(node),
                        message = "`${node.name}` is `@Auth(AuthStyle.EXPLICIT)` but has no " +
                            "`@Header(\"$AUTHORIZATION\")` parameter, so the request would carry " +
                            "no credential. Restore the parameter, or declare the style the " +
                            "interceptor should apply.",
                        quickfixData = null,
                    )
                }
                return
            }

            headerParams.forEach { parameter ->
                context.report(
                    issue = AUTH_HEADER_PARAM_FORBIDDEN,
                    scope = parameter as UElement,
                    location = context.getLocation(parameter as UElement),
                    message = "`$AUTHORIZATION` is supplied by `HeaderInterceptor` from this " +
                        "endpoint's `@Auth` declaration — drop the `${parameter.name}` parameter. " +
                        "Only `@Auth(AuthStyle.EXPLICIT)` endpoints may carry it.",
                    quickfixData = null,
                )
            }
        }
    }

    private fun UMethod.hasHttpAnnotation(): Boolean =
        uAnnotations.any { it.qualifiedName in HTTP_ANNOTATIONS }

    private fun UMethod.ownAuth(): UAnnotation? =
        uAnnotations.firstOrNull { it.qualifiedName == AUTH }

    private fun org.jetbrains.uast.UClass.classAuth(): UAnnotation? =
        uAnnotations.firstOrNull { it.qualifiedName == AUTH }

    /** True for `@Auth(AuthStyle.EXPLICIT)` however the enum constant is qualified at the site. */
    private fun UAnnotation.isExplicit(): Boolean =
        attribute("style")?.asSourceString()?.endsWith(EXPLICIT) == true

    private fun UMethod.authorizationHeaderParams(): List<UParameter> =
        uastParameters.filter { parameter ->
            parameter.uAnnotations.any { annotation ->
                annotation.qualifiedName == HEADER &&
                    (annotation.attribute("value")?.evaluate() as? String)
                        .equals(AUTHORIZATION, ignoreCase = true)
            }
        }

    private fun UAnnotation.attribute(name: String) =
        findAttributeValue(name) ?: findAttributeValue(null)

    companion object {
        private const val AUTH = "com.difft.android.network.auth.Auth"
        private const val HEADER = "retrofit2.http.Header"
        private const val AUTHORIZATION = "Authorization"
        private const val EXPLICIT = "EXPLICIT"

        private val HTTP_ANNOTATIONS = setOf(
            "retrofit2.http.GET",
            "retrofit2.http.POST",
            "retrofit2.http.PUT",
            "retrofit2.http.DELETE",
            "retrofit2.http.PATCH",
            "retrofit2.http.HEAD",
            "retrofit2.http.OPTIONS",
            "retrofit2.http.HTTP",
        )

        val AUTH_STYLE_MISSING: Issue = Issue.create(
            id = "AuthStyleMissing",
            briefDescription = "Retrofit endpoint declares no @Auth style",
            explanation = "Every Retrofit endpoint must declare how its `Authorization` header is " +
                "produced, with `@Auth(AuthStyle.BASIC|JWT|NONE|EXPLICIT)` on the method or on " +
                "the service interface. `HeaderInterceptor` reads that declaration off Retrofit's " +
                "`Invocation` tag and is the only place the header is set.\n\n" +
                "An undeclared endpoint falls back to the pre-declaration rule (a header already " +
                "present wins, absent means implicit Basic) and logs an error — correct, but " +
                "silent in a release build. Declare the style instead.",
            category = Category.SECURITY,
            priority = 7,
            severity = Severity.ERROR,
            implementation = Implementation(AuthStyleDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )

        val AUTH_HEADER_PARAM_FORBIDDEN: Issue = Issue.create(
            id = "AuthHeaderParamForbidden",
            briefDescription = "Authorization passed by hand instead of declared",
            explanation = "`Authorization` is supplied by `HeaderInterceptor` from the endpoint's " +
                "`@Auth` declaration, so a caller must not pass it as a " +
                "`@Header(\"Authorization\")` parameter: the value goes stale (a cached JWT " +
                "outlives its 20-minute lifetime) and the endpoint ends up with two competing " +
                "sources of truth.\n\n" +
                "The one sanctioned exception is `@Auth(AuthStyle.EXPLICIT)`, for the routes that " +
                "run before the credential is persisted — login sign-in, pre-key registration, " +
                "and the token refresh itself.",
            category = Category.SECURITY,
            priority = 7,
            severity = Severity.ERROR,
            implementation = Implementation(AuthStyleDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )

        val AUTH_EXPLICIT_MISSING_HEADER: Issue = Issue.create(
            id = "AuthExplicitMissingHeader",
            briefDescription = "@Auth(EXPLICIT) endpoint has no Authorization parameter",
            explanation = "`@Auth(AuthStyle.EXPLICIT)` tells `HeaderInterceptor` to leave the " +
                "header exactly as the caller built it. With no `@Header(\"Authorization\")` " +
                "parameter left to build it, the request goes out with no credential at all — " +
                "silently.\n\n" +
                "Either restore the parameter, or declare the style the interceptor should apply " +
                "(`BASIC`, `JWT` or `NONE`).",
            category = Category.SECURITY,
            priority = 7,
            severity = Severity.ERROR,
            implementation = Implementation(AuthStyleDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
