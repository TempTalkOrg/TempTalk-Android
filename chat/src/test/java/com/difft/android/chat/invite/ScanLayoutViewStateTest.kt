package com.difft.android.chat.invite

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.R
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * View-state tests for `activity_scan.xml` + [ScanFrameOverlayView] (design §7 T17–T19).
 *
 * **No pixel baseline:** `:chat` has NO View/XML Roborazzi harness (the only screenshot infra is
 * Compose-based), so per the [com.difft.android.chat.ui.ChatMessageViewHolderQuoteThumbnailTest]
 * precedent these tests inflate the real layout via `Robolectric.buildActivity` + `LayoutInflater`
 * and assert on resulting view state. Inflating proves the [ScanFrameOverlayView] `@JvmOverloads`
 * constructor exists (otherwise LayoutInflater throws InflateException). Visual parity vs the BGA
 * qrcv_* values (200dp rect, 20dp/3dp corners) recoloured to primary blue (#056FFA) is verified
 * by code review + on-device.
 *
 * Overlay/header are the only deterministic surface: PreviewView renders black in Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ScanLayoutViewStateTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
    }

    /** Inflates activity_scan, measures + lays out at the given dimensions, returns the root. */
    private fun inflateScan(widthPx: Int, heightPx: Int): ViewGroup {
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        val root = LayoutInflater.from(activity).inflate(R.layout.activity_scan, parent, false) as ViewGroup
        parent.addView(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        return root
    }

    // ── T17: portrait — preview + overlay full-bleed, header pinned top with back + title ──────
    @Test
    fun `T17 - portrait inflation yields full-bleed preview plus overlay and a top header`() {
        val w = 1080
        val h = 1920
        val root = inflateScan(w, h)

        val preview = root.findViewById<PreviewView>(R.id.previewView)
        assertNotNull("previewView must inflate", preview)
        assertEquals(View.VISIBLE, preview.visibility)
        assertEquals(w, preview.width)
        assertEquals(h, preview.height)

        // Inflating scanOverlay proves the @JvmOverloads (Context, AttributeSet) ctor exists.
        val overlay = root.findViewById<ScanFrameOverlayView>(R.id.scanOverlay)
        assertNotNull("scanOverlay must inflate (proves @JvmOverloads ctor)", overlay)
        assertEquals(w, overlay.width)
        assertEquals(h, overlay.height)

        val back = root.findViewById<View>(R.id.ib_back)
        assertNotNull("ib_back must inflate", back)
        assertEquals(View.VISIBLE, back.visibility)

        val title = root.findViewById<TextView>(R.id.tv_title)
        assertNotNull("tv_title must inflate", title)
        assertEquals(activity.getString(R.string.scan_scan), title.text.toString())
    }

    // ── T18: landscape (sw≥600dp) — the distortion-fix surface; preview fills, no fixed aspect ──
    @Test
    @Config(qualifiers = "sw600dp-land")
    fun `T18 - landscape inflation keeps preview full-bleed and overlay centered without distortion`() {
        val w = 1920
        val h = 1080
        val root = inflateScan(w, h)

        val preview = root.findViewById<PreviewView>(R.id.previewView)
        assertNotNull(preview)
        assertEquals(w, preview.width) // fills parent — no fixed aspect that would distort
        assertEquals(h, preview.height)

        val overlay = root.findViewById<ScanFrameOverlayView>(R.id.scanOverlay)
        assertNotNull(overlay)
        // overlay is full-bleed; its rect is computed from measured width/height in onDraw (centered),
        // not a fixed offset, so it stays centered regardless of orientation.
        assertEquals(w, overlay.width)
        assertEquals(h, overlay.height)

        val back = root.findViewById<View>(R.id.ib_back)
        assertEquals(View.VISIBLE, back.visibility) // header pinned top
    }

    // ── T19: dark parity — overlay inflates under a dark config; colors have no values-night ────
    @Test
    @Config(qualifiers = "night")
    fun `T19 - overlay inflates identically under dark mode (no values-night override)`() {
        // Theme parity exempt: scan_mask_color and primary (corners/scan line) are mode-identical →
        // mode-identical by design. Assert the overlay inflates without exception under a dark config.
        val root = inflateScan(1080, 1920)
        val overlay = root.findViewById<ScanFrameOverlayView>(R.id.scanOverlay)
        assertNotNull("overlay inflates under dark config", overlay)
        assertTrue(overlay.width > 0)
    }
}
