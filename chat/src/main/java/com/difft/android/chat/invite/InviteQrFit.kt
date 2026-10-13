package com.difft.android.chat.invite

import android.os.Handler
import android.os.Looper
import android.view.View
import com.difft.android.chat.R
import com.difft.android.chat.util.doOnEachLayout
import kotlin.math.roundToInt

/**
 * Keeps the invite card inside the room between the close button and the action row by
 * shrinking only the QR image. The card is a fixed vertical stack sized for a phone in
 * portrait; on a short window (608dp tall on the Xiaomi 2608BPX34C inner display held
 * landscape) it is taller than that room, and ConstraintLayout centers the overflow — the
 * avatar pokes into the status bar and the progress bar lands under the action row. Every
 * other element keeps its size, so a window with room shows the layout unchanged.
 *
 * Runs on every layout pass of the invite content root and converges: once the QR is at its
 * target size the next pass computes the same target and writes nothing.
 */
internal object InviteQrFit {

    /**
     * Floor for the QR. Below this a phone camera struggles to lock on the code, so a window
     * that cannot fit even this overflows again rather than shrinking the QR further.
     */
    private const val QR_MIN_DP = 96

    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(root: View) {
        val close = root.findViewById<View>(R.id.iv_close)
        val card = root.findViewById<View>(R.id.cv_content)
        val actions = root.findViewById<View>(R.id.ll_bottom_actions)
        val qr = root.findViewById<View>(R.id.iv_QR)
        // The layout's own QR size is the ceiling, so a window with room lands exactly on it.
        val maxPx = qr.layoutParams.height
        val minPx = (QR_MIN_DP * qr.resources.displayMetrics.density).roundToInt()
        root.doOnEachLayout { fit(close, card, actions, qr, minPx, maxPx) }
    }

    private fun fit(close: View, card: View, actions: View, qr: View, minPx: Int, maxPx: Int) {
        val room = actions.top - close.bottom
        if (room <= 0 || card.height <= 0) return
        val target = qrSizePx(roomPx = room, cardPx = card.height, qrPx = qr.height, minPx = minPx, maxPx = maxPx)
        if (qr.layoutParams.height == target) return
        // Deferred: this runs inside the layout pass, and a requestLayout from within one is
        // only tolerated (with a framework warning), not clean. A looper post rather than
        // View.post so it also runs for a hierarchy laid out without a window.
        mainHandler.post {
            if (qr.layoutParams.height == target) return@post
            qr.layoutParams = qr.layoutParams.apply {
                width = target
                height = target
            }
        }
    }

    /**
     * Pure sizing rule: the QR takes whatever the room leaves after the rest of the card,
     * clamped to [minPx, maxPx].
     */
    fun qrSizePx(roomPx: Int, cardPx: Int, qrPx: Int, minPx: Int, maxPx: Int): Int {
        val restOfCard = cardPx - qrPx
        return (roomPx - restOfCard).coerceIn(minPx, maxPx)
    }
}
