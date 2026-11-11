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











class IncomingCallControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftComposeView(context, attrs, defStyleAttr) {

    private var rejectIconRes by mutableIntStateOf(0)
    private var acceptIconRes by mutableIntStateOf(0)

    var onReject: (() -> Unit)? = null
    var onAccept: (() -> Unit)? = null

    
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
