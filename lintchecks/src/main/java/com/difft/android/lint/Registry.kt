package com.difft.android.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/**
 * IssueRegistry for TempTalk's custom Lint checks.
 *
 * When adding a new Issue:
 *   1. Implement the Detector in this package
 *   2. Expose the Issue as a `companion object` constant
 *   3. Add it to the [issues] list below — the detector activates
 *      automatically (no `checkOnly` whitelist gate; AGP's full ruleset
 *      runs alongside, governed by per-module `lint-baseline.xml`).
 *
 * Categories of issues currently shipped:
 *   - Logging API blacklist (LogNotL / KotlinIOPrintLn / TimberDirectCall
 *     / PrintStackTraceNoArg)
 *   - Project-specific helpers (ChativeHttpClientRequired /
 *     ForegroundServiceStarterRequired)
 *   - Retrofit auth declarations (AuthStyleMissing /
 *     AuthHeaderParamForbidden / AuthExplicitMissingHeader)
 *   - UI unification (DifftWidgetRequired, DifftTypeScaleRequired,
 *     DifftColorTokenRequired, DifftRadiusRequired)
 *
 * See `docs/claude/lintchecks-extension-roadmap.md` for the future-rule
 * roadmap (concurrency anti-patterns, Android-generic patterns, etc.).
 */
class Registry : IssueRegistry() {
    override val vendor = Vendor(
        vendorName = "TempTalk",
        identifier = "difft.android",
        feedbackUrl = "https://github.com/difftim/TempTalk-Android/issues",
        contact = "https://github.com/difftim/TempTalk-Android",
    )

    override val issues: List<Issue> = listOf(
        // Logging API blacklist — see .claude/rules/logging-standards.md
        LogApiDetector.LOG_NOT_L,
        LogApiDetector.TIMBER_DIRECT_CALL,
        PrintLnDetector.KOTLIN_IO_PRINTLN,
        PrintStackTraceDetector.PRINT_STACK_TRACE_NO_ARG,
        // Project-specific helpers — go through the project's wrappers
        ChativeHttpClientDetector.CHATIVE_HTTP_CLIENT_REQUIRED,
        // Retrofit auth declarations — one style per endpoint, set only by HeaderInterceptor
        AuthStyleDetector.AUTH_STYLE_MISSING,
        AuthStyleDetector.AUTH_HEADER_PARAM_FORBIDDEN,
        AuthStyleDetector.AUTH_EXPLICIT_MISSING_HEADER,
        ForegroundServiceStarterDetector.FOREGROUND_SERVICE_STARTER_REQUIRED,
        // UI unification — raw button/switch/checkbox widgets only inside :base
        DifftWidgetDetector.ISSUE,
        // UI unification — text metrics come from DifftTheme.typography (issue #1232)
        DifftTypeScaleDetector.ISSUE,
        // Design-system alignment — colours and corner radii come from DifftTheme tokens
        DifftColorTokenDetector.ISSUE,
        DifftRadiusDetector.ISSUE,
    )

    override val api: Int = CURRENT_API
}
