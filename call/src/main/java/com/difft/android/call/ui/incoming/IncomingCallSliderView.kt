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














class IncomingCallSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftComposeView(context, attrs, defStyleAttr) {

    var onAnswer: (() -> Unit)? = null
    var onDecline: (() -> Unit)? = null

    
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
