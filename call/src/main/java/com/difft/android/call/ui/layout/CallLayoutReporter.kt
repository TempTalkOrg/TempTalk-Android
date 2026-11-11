package com.difft.android.call.ui.layout

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.difft.android.call.core.CallFrameSizeSink
import com.difft.android.call.core.CallUiController
import com.difft.android.call.layout.CallLayoutController
import com.difft.android.call.layout.Posture
import com.difft.android.call.layout.postureOf
import com.difft.android.call.ui.actionbar.rememberCallActionBarPlan
import com.difft.android.call.ui.actionbar.rememberCallSideRail
import com.difft.android.call.ui.actionbar.rememberCallWindowSizeDp
import com.difft.android.call.ui.tabletop.TabletopGeometry
import com.difft.android.call.ui.tabletop.CallTabletopSpec
import com.difft.android.call.ui.tabletop.bottomReserveDp
import com.difft.android.call.ui.tabletop.rememberTabletopBottomInsetDp
import com.difft.android.call.ui.tabletop.foldDp
import kotlin.math.max
import com.difft.android.call.layout.Insets as LayoutInsets
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.layout.Size as LayoutSize


internal val LocalCallFrameSizeSink = staticCompositionLocalOf<CallFrameSizeSink?> { null }





@Stable
internal class CallLayoutReportKey {
    var value: CallLayoutWindowReport? by mutableStateOf(null)
        internal set
}


internal val LocalCallLayoutReportKey = staticCompositionLocalOf<CallLayoutReportKey?> { null }





internal data class CallLayoutWindowReport(
    val size: LayoutSize,
    val safe: LayoutInsets,
    val fold: LayoutRect?,
    val content: CallContentRects,
    val controls: LayoutSize? = null,
)






internal class CallLayoutReportGate {
    private var pipWindow: LayoutSize? = null
    private var last: CallLayoutWindowReport? = null

    fun admit(inPip: Boolean, report: CallLayoutWindowReport): Boolean {
        if (inPip) {
            pipWindow = report.size
            return false
        }
        pipWindow?.let { pip ->
            if (report.size == pip) return false
            pipWindow = null
        }
        if (report == last) return false
        last = report
        return true
    }
}







@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CallLayoutReporter(
    controller: CallLayoutController,
    uiController: CallUiController,
    isGroup: Boolean,
    statusBarHidden: Boolean,
    gatedFold: TabletopGeometry?,
) {
    val window = rememberCallWindowSizeDp()
    val density = LocalDensity.current
    val scale = density.density.toDouble()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val status = WindowInsets.statusBarsIgnoringVisibility
    val nav = WindowInsets.navigationBarsIgnoringVisibility
    
    
    val navShown = WindowInsets.navigationBars
    val statusBand = status.getTop(density) / scale
    val safe = LayoutInsets(
        top = if (statusBarHidden) 0.0 else max(statusBand, nav.getTop(density) / scale),
        right = max(status.getRight(density, LayoutDirection.Ltr), nav.getRight(density, LayoutDirection.Ltr)) / scale,
        bottom = max(status.getBottom(density), navShown.getBottom(density)) / scale,
        left = max(status.getLeft(density, LayoutDirection.Ltr), nav.getLeft(density, LayoutDirection.Ltr)) / scale,
    )
    val size = LayoutSize(window.width.toDouble(), window.height.toDouble())

    val navBottomDp = rememberTabletopBottomInsetDp()
    val fold = remember(gatedFold, size, navBottomDp) { gatedFoldDp(gatedFold, size, navBottomDp) }

    val barPlan = rememberCallActionBarPlan(isGroup)
    val posture = postureOf(fold)
    
    
    val tabletop = posture == Posture.TABLETOP
    val rail = rememberCallSideRail().takeIf { !tabletop }
    val bottomReserve = bottomReserveDp(posture, barPlan, navBottomDp)
    val content = remember(size, safe, statusBand, bottomReserve, navBottomDp, rail, tabletop, isRtl) {
        callContentRects(
            window = size,
            safe = safe,
            statusBandDp = statusBand,
            barBottomReserveDp = bottomReserve,
            navigationBarDp = navBottomDp.toDouble(),
            rail = rail,
            tabletop = tabletop,
            isRtl = isRtl,
        )
    }

    val inPip by uiController.isInPipMode.collectAsState()
    val gate = remember { CallLayoutReportGate() }
    val reportKey = LocalCallLayoutReportKey.current
    val report = CallLayoutWindowReport(size, safe, fold, content, CallTabletopSpec.CONSOLE_SIZE.takeIf { tabletop })
    SideEffect {
        if (gate.admit(inPip, report)) {
            reportKey?.value = report
            controller.setTabletopControls(report.controls)
            controller.setWindow(report.size, report.safe, report.fold)
            controller.setContent(report.content.shown, report.content.hidden)
        }
    }
}








internal fun gatedFoldDp(fold: TabletopGeometry?, window: LayoutSize, navBottomDp: Float): LayoutRect? =
    fold?.let { foldInWindow(padFold(it.foldDp(), vertical = !it.horizontal), window) }
        ?.takeIf { postureOf(it) != Posture.TABLETOP || consoleFitsBelow(it, window, navBottomDp) }


private fun consoleFitsBelow(fold: LayoutRect, window: LayoutSize, navBottomDp: Float): Boolean =
    window.h - (fold.y + fold.h + CallTabletopSpec.GAP_DP) - navBottomDp >= CallTabletopSpec.CONSOLE_HEIGHT_DP
