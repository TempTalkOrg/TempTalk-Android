package com.difft.android.call.ui.incoming

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.difft.android.base.widget.DifftComposeView
import com.difft.android.call.R

/**
 * XML-hostable shell around [IncomingCallSlider] for the View-based incoming-call screen, shown
 * instead of the tap buttons when the call itself lit the screen.
 *
 * The host must give an explicit width (`match_parent` with the design's side margins): the track
 * fills the width it is offered. Icons are the project's own glyphs (tabler phone, hang-up).
 *
 * Unlike the toggle shells, the gesture lives in the composition rather than in a View click: the
 * whole point is that nothing click-like can activate it, so there is no View-level click path to
 * own. The shell contributes theming, lazy composition, the enabled state, [touchObserver] (every
 * pointer event that reaches the row, for the accept/reject log line) and pauses the idle nudge
 * while the row is not shown.
 */
class IncomingCallSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftComposeView(context, attrs, defStyleAttr) {

    var onAnswer: (() -> Unit)? = null
    var onDecline: (() -> Unit)? = null

    /** Sees every pointer event dispatched into this row before the composition handles it. */
    var touchObserver: ((MotionEvent) -> Unit)? = null

    private var shownState by mutableStateOf(false)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchObserver?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        shownState = isShown
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        shownState = isShown
    }

    @Composable
    override fun ThemedContent() {
        IncomingCallSlider(
            answerIcon = painterResource(com.difft.android.base.R.drawable.base_tabler_phone),
            declineIcon = painterResource(R.drawable.call_ic_hangup_glyph),
            declineLabel = stringResource(R.string.call_slider_decline),
            answerLabel = stringResource(R.string.call_slider_answer),
            hint = stringResource(R.string.call_slider_hint),
            onAnswer = { onAnswer?.invoke() },
            onDecline = { onDecline?.invoke() },
            enabled = enabledState,
            animateIdle = shownState,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
