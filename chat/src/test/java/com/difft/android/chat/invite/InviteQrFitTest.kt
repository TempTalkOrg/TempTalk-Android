package com.difft.android.chat.invite

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.difft.android.chat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Pins [InviteQrFit] against the real `layout_invite`: on a window too short for the card's
 * fixed stack (an unfolded foldable held landscape, modelled here as a 640dp window — see the
 * comment in the test) only the QR image shrinks and the card stops overlapping the action row
 * and the close button; on a phone-height window nothing changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InviteQrFitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density = context.resources.displayMetrics.density
    private fun Int.dp(): Int = (this * density).toInt()

    private fun inflateAndFit(widthDp: Int, heightDp: Int): View {
        val root = LayoutInflater.from(context).inflate(R.layout.layout_invite, null)
        InviteQrFit.attach(root)
        // No window here, so the relayout the fit requests must be driven by hand: the fit posts
        // its resize to the main looper; the first pass shrinks, the second confirms, and the
        // third proves the fixed point holds.
        repeat(3) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(widthDp.dp(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightDp.dp(), View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, widthDp.dp(), heightDp.dp())
            ShadowLooper.idleMainLooper()
        }
        return root
    }

    @Test
    fun `short landscape window shrinks only the QR and clears the action row`() {
        // Robolectric's text metrics make the card's fixed part taller than on a device, so the
        // window that lands the QR between its floor and its design size is 640dp here (a real
        // unfolded foldable's ~530dp usable height lands it there too).
        val root = inflateAndFit(widthDp = 640, heightDp = 640)
        val close = root.findViewById<View>(R.id.iv_close)
        val card = root.findViewById<View>(R.id.cv_content)
        val actions = root.findViewById<View>(R.id.ll_bottom_actions)
        val qr = root.findViewById<View>(R.id.iv_QR)
        val avatar = root.findViewById<View>(R.id.imageview_avatar)

        assertTrue("the QR must give up height on a short window (was ${qr.height}px)", qr.height < 200.dp())
        assertTrue("the QR must not shrink below its floor (was ${qr.height}px)", qr.height >= 96.dp())
        assertEquals("the QR stays square", qr.width, qr.height)
        assertTrue("the card must sit below the close button", card.top >= close.bottom)
        assertTrue("the card must end above the action row", card.bottom <= actions.top)
        assertTrue("the avatar must stay inside the sheet", avatar.top >= 0)
    }

    @Test
    fun `phone window keeps the QR at its design size`() {
        val root = inflateAndFit(widthDp = 411, heightDp = 800)
        val qr = root.findViewById<View>(R.id.iv_QR)
        assertEquals(200.dp(), qr.height)
    }

    @Test
    fun `sizing rule hands the QR the room left by the rest of the card, within its bounds`() {
        // room 320dp, card 417dp of which the QR is 200dp → 217dp of other content → 103dp left.
        assertEquals(103, InviteQrFit.qrSizePx(roomPx = 320, cardPx = 417, qrPx = 200, minPx = 96, maxPx = 200))
        assertEquals(200, InviteQrFit.qrSizePx(roomPx = 600, cardPx = 417, qrPx = 200, minPx = 96, maxPx = 200))
        assertEquals(96, InviteQrFit.qrSizePx(roomPx = 250, cardPx = 417, qrPx = 200, minPx = 96, maxPx = 200))
    }
}
