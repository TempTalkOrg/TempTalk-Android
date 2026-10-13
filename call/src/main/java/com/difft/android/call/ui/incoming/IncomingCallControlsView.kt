package com.difft.android.call.ui.incoming

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.core.content.withStyledAttributes
import com.difft.android.base.widget.DifftComposeView
import com.difft.android.call.R

/**
 * XML-hostable shell around [IncomingCallControls] (the tap buttons) for the View-based
 * incoming-call screen.
 *
 * The host must give an explicit width (`match_parent`): the row fills the width it is offered.
 * Icons come from `app:icc_rejectIcon` / `app:icc_acceptIcon` so the shell can live in :call while
 * the assets stay with the layout's module. The clicks live in the composition (one clickable per
 * button); the shell contributes theming, lazy composition, the enabled state and [touchObserver]
 * (every pointer event that reaches this row, for the accept/reject log line).
 */
class IncomingCallControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftComposeView(context, attrs, defStyleAttr) {

    private var rejectIconRes by mutableIntStateOf(0)
    private var acceptIconRes by mutableIntStateOf(0)

    var onReject: (() -> Unit)? = null
    var onAccept: (() -> Unit)? = null

    /** Sees every pointer event dispatched into this row before the composition handles it. */
    var touchObserver: ((MotionEvent) -> Unit)? = null

    init {
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.IncomingCallControlsView) {
                rejectIconRes = getResourceId(R.styleable.IncomingCallControlsView_icc_rejectIcon, 0)
                acceptIconRes = getResourceId(R.styleable.IncomingCallControlsView_icc_acceptIcon, 0)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchObserver?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }

    @Composable
    override fun ThemedContent() {
        if (rejectIconRes == 0 || acceptIconRes == 0) return
        IncomingCallControls(
            rejectIcon = painterResource(rejectIconRes),
            acceptIcon = painterResource(acceptIconRes),
            onReject = { onReject?.invoke() },
            onAccept = { onAccept?.invoke() },
            enabled = enabledState,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
