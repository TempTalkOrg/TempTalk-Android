package com.difft.android.chat.invite

import android.os.Handler
import android.os.Looper
import android.view.View
import com.difft.android.chat.R
import com.difft.android.chat.util.doOnEachLayout
import kotlin.math.roundToInt












internal object InviteQrFit {

    



    private const val QR_MIN_DP = 96

    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(root: View) {
        val close = root.findViewById<View>(R.id.iv_close)
        val card = root.findViewById<View>(R.id.cv_content)
        val actions = root.findViewById<View>(R.id.ll_bottom_actions)
        val qr = root.findViewById<View>(R.id.iv_QR)
        
        val maxPx = qr.layoutParams.height
        val minPx = (QR_MIN_DP * qr.resources.displayMetrics.density).roundToInt()
        root.doOnEachLayout { fit(close, card, actions, qr, minPx, maxPx) }
    }

    private fun fit(close: View, card: View, actions: View, qr: View, minPx: Int, maxPx: Int) {
        val room = actions.top - close.bottom
        if (room <= 0 || card.height <= 0) return
        val target = qrSizePx(roomPx = room, cardPx = card.height, qrPx = qr.height, minPx = minPx, maxPx = maxPx)
        if (qr.layoutParams.height == target) return
        
        
        
        mainHandler.post {
            if (qr.layoutParams.height == target) return@post
            qr.layoutParams = qr.layoutParams.apply {
                width = target
                height = target
            }
        }
    }

    



    fun qrSizePx(roomPx: Int, cardPx: Int, qrPx: Int, minPx: Int, maxPx: Int): Int {
        val restOfCard = cardPx - qrPx
        return (roomPx - restOfCard).coerceIn(minPx, maxPx)
    }
}
