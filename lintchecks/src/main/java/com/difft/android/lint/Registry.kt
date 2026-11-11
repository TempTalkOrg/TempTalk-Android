package com.difft.android.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue





class Registry : IssueRegistry() {
    override val vendor = Vendor(
        vendorName = "TempTalk",
        identifier = "difft.android",
        feedbackUrl = "https://github.com/difftim/TempTalk-Android/issues",
        contact = "https://github.com/difftim/TempTalk-Android",
    )

    override val issues: List<Issue> = listOf(
        
        LogApiDetector.LOG_NOT_L,
        LogApiDetector.TIMBER_DIRECT_CALL,
        PrintLnDetector.KOTLIN_IO_PRINTLN,
        PrintStackTraceDetector.PRINT_STACK_TRACE_NO_ARG,
        
        ChativeHttpClientDetector.CHATIVE_HTTP_CLIENT_REQUIRED,
        
        AuthStyleDetector.AUTH_STYLE_MISSING,
        AuthStyleDetector.AUTH_HEADER_PARAM_FORBIDDEN,
        AuthStyleDetector.AUTH_EXPLICIT_MISSING_HEADER,
        ForegroundServiceStarterDetector.FOREGROUND_SERVICE_STARTER_REQUIRED,
        
        DifftWidgetDetector.ISSUE,
        
        DifftTypeScaleDetector.ISSUE,
        
        DifftColorTokenDetector.ISSUE,
        DifftRadiusDetector.ISSUE,
        
        DifftHairlineDetector.ISSUE,
    )

    override val api: Int = CURRENT_API
}
