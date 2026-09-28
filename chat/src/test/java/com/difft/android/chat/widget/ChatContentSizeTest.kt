package com.difft.android.chat.widget

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.chat.R
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Pins [View.chatContentWidthPx] / [View.chatContentHeightPx] — the shared content box the
 * conversation surface sizes message content against.
 *
 * Four properties are load-bearing and each has a case below:
 *
 *  1. **Width follows the CONTAINER, not the window.** The same conversation fragment renders in
 *     the dual-pane detail pane and full-screen in `ChatActivity`; only the container it is laid
 *     out in distinguishes them, and `wrap_content` ancestors in between must be skipped.
 *  1b. **The container width must be the CURRENT layout pass's.** The detail pane can be
 *     resized with no configuration change at all (the pane divider drag), and a multi-window
 *     resize that only moves `screenSize` re-measures attached rows in place — and while a row
 *     is mid-measure its own root still reports the previous width. Only the message
 *     `RecyclerView` is measured before its children, so it is the one ancestor that may answer.
 *  2. **The dimen is a ceiling, not a pane surrogate.** `chat_content_max_width` is a single
 *     band-independent 560dp value. A narrower per-band value is selected by window width alone,
 *     so it would ALSO bind on a full-screen conversation at that width and render content
 *     narrower than a 393dp phone gets — the regression `full-screen conversation at MEDIUM
 *     width` locks out.
 *  3. **Neither accessor may consult `WindowMetricsCalculator`.** Both run per message measure /
 *     bind, and `computeCurrentWindowMetrics` resolves bounds through private-field reflection on
 *     API 26-29. For height that helper is also semantically wrong: window bounds INCLUDE the
 *     system bars, so it would grow every `screenHeight / 3` media cap on every phone.
 *
 * Band assertions carry a 0.5dp tolerance because `getDimensionPixelSize` rounds to px.
 *
 * **Scope boundary — read before adding a case here.** Every view below is built either fully
 * detached or already inside a MEASURED container. Neither is the state `RecyclerView` binds a
 * FRESHLY CREATED row in (parentless, nothing above measured), so this class structurally cannot
 * see the bind-time fallback, and it asserts over the accessors only — never over
 * `ImageAndVideoMessageView`'s reaction to them (the bind -> attach sequence, the attach-time
 * re-resolve, and the measure-time re-resolve a pane resize triggers under an attached row).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ChatContentSizeTest {

    /** Sentinel no real window can produce, used to detect a `WindowMetricsCalculator` read. */
    private val sentinelPx = 7

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun activity(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    /** A View with no measured ancestor — the isolated-inflation / screenshot-harness case. */
    private fun detachedView(): View = View(activity())

    /**
     * A `wrap_content` row inside a `wrap_content` bubble inside a full-width container measured to
     * [containerDp] — the production shape: `contentFrame` / `contentContainer` are both
     * `wrap_content`, and the full-width ancestor is the message list (or the row root).
     */
    private fun viewInContainer(containerDp: Int): View {
        val activity = activity()
        val density = activity.resources.displayMetrics.density
        val container = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val bubble = FrameLayout(activity)
        val row = View(activity)
        // A narrower-than-the-container row, so a stale `wrap_content` ancestor read would be
        // visible as a much smaller result instead of the container width.
        bubble.addView(row, ViewGroup.LayoutParams((100 * density).toInt(), 40))
        container.addView(
            bubble,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.measure(
            View.MeasureSpec.makeMeasureSpec((containerDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return row
    }

    private fun View.capPx(): Int = resources.getDimensionPixelSize(R.dimen.chat_content_max_width)

    private fun View.widthDp(): Float = chatContentWidthPx() / resources.displayMetrics.density

    // ── Property 1: width follows the container ────────────────────────────────────────────────

    @Test
    @Config(qualifiers = "w691dp-h716dp")
    fun `width follows the detail pane container, not the window`() {
        // OPPO Find N6 / PLP110 inner display: a 691dp window whose detail pane is
        // 691 - 72 rail - 1 divider - 280 list = 338dp.
        val row = viewInContainer(containerDp = 338)

        assertEquals(
            "chatContentWidthPx() MUST be the pane container's width. If it tracks the window " +
                "instead, message content is sized against 691dp while it renders in a 338dp " +
                "pane — the over-sizing this accessor exists to prevent.",
            338f,
            row.widthDp(),
            0.5f,
        )
        assertTrue(
            "The pane result must be strictly below the 691dp window width, or the container " +
                "term is not actually being read.",
            row.chatContentWidthPx() < WindowSizeClassUtil.getWindowWidthPx(row.context as ComponentActivity),
        )
    }

    /**
     * A message `RecyclerView` measured to [listDp] whose row root is still measured to
     * [staleRowDp] — the state EVERY descendant sees while the row is mid-measure.
     *
     * The two widths are set independently on purpose, because that is what the platform does:
     * `RecyclerView` with a `MATCH_PARENT` width always gets an `EXACTLY` spec, so its auto-measure
     * assigns the new measured width and RETURNS; children are measured afterwards, from
     * `onLayout` -> `dispatchLayout`. So when a fold re-measures a conversation in place, the list
     * already reports the NEW width while the row root still reports the OLD one.
     */
    private fun viewInStaleRow(listDp: Int, staleRowDp: Int): View {
        val activity = activity()
        val density = activity.resources.displayMetrics.density
        val list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
        activity.setContentView(list)
        check(list.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            "setContentView must give the list MATCH_PARENT params or the walk skips it"
        }

        val rowRoot = FrameLayout(activity)
        val bubble = FrameLayout(activity)
        val row = View(activity)
        bubble.addView(row, ViewGroup.LayoutParams((100 * density).toInt(), 40))
        bubble.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        rowRoot.addView(bubble)
        list.addView(
            rowRoot,
            RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // The row root carries the PRE-fold measurement...
        rowRoot.measure(
            View.MeasureSpec.makeMeasureSpec((staleRowDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // ...while the list has already been re-measured to the POST-fold width.
        list.measure(
            View.MeasureSpec.makeMeasureSpec((listDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        check(rowRoot.measuredWidth == (staleRowDp * density).toInt()) {
            "Pre-condition: the row root must still hold the stale ${staleRowDp}dp measurement " +
                "(got ${rowRoot.measuredWidth}px) or this case cannot detect a stale read"
        }
        check(list.measuredWidth == (listDp * density).toInt()) {
            "Pre-condition: the list must hold the fresh ${listDp}dp measurement " +
                "(got ${list.measuredWidth}px)"
        }
        return row
    }

    @Test
    @Config(qualifiers = "w691dp-h716dp")
    fun `a widening resize answers from the message list, not from the mid-measure row root`() {
        // A pane-divider drag (or a resize without recreation) re-measures attached rows in
        // place. Reading the row root here returns the previous width forever — the re-resolve
        // guard in ImageAndVideoMessageView matches the stale value and never corrects it.
        val row = viewInStaleRow(listDp = 560, staleRowDp = 378)

        assertEquals(
            "chatContainerWidthPx() MUST answer from the message RecyclerView (560dp), which is " +
                "measured before its children, not from the row root that is still mid-measure " +
                "at the pre-fold 378dp.",
            560f,
            row.chatContainerWidthPx() / row.resources.displayMetrics.density,
            0.5f,
        )
    }

    @Test
    @Config(qualifiers = "w691dp-h716dp")
    fun `a narrowing resize answers from the message list too, so the correction works both ways`() {
        // The inverse: narrowing the pane makes a stale read too WIDE and content clips.
        // Covered separately because min/max heuristics pass one direction and fail the other.
        val row = viewInStaleRow(listDp = 378, staleRowDp = 560)

        assertEquals(
            "A narrowing re-measure must also be tracked: the list is 378dp while the row root " +
                "still reports 560dp.",
            378f,
            row.chatContainerWidthPx() / row.resources.displayMetrics.density,
            0.5f,
        )
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)
    fun `list-derived and row-derived widths agree when nothing is stale - phone parity`() {
        // The guard on preferring the list: whenever the tree is consistent (every ordinary
        // measure on every phone), the list content box and the row root's measured width are the
        // same number, so this preference cannot shift phone rendering or a committed baseline.
        val row = viewInStaleRow(listDp = 393, staleRowDp = 393)

        assertEquals(
            "A MATCH_PARENT row root in a horizontally unpadded list measures to exactly the " +
                "list's content box, so the two candidate answers are identical in the steady state.",
            393f,
            row.chatContainerWidthPx() / row.resources.displayMetrics.density,
            0.5f,
        )
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)
    fun `wrap_content ancestors are skipped so a recycled bind cannot inherit a stale width`() {
        // `ImageAndVideoMessageView.setupImageView` runs at BIND time, before the row is measured:
        // its `contentFrame` / `contentContainer` ancestors still hold the PREVIOUS message's
        // width. Only MATCH_PARENT ancestors may answer.
        val row = viewInContainer(containerDp = 393)

        assertEquals(
            "The 100dp wrap_content bubble around the row must be skipped in favour of the " +
                "full-width container.",
            393f,
            row.widthDp(),
            0.5f,
        )
    }

    // ── Property 2: the dimen is a band-independent ceiling ────────────────────────────────────

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)
    fun `phone parity - width is the display width and the 560dp ceiling does not fire`() {
        val view = detachedView()

        assertTrue(
            "Pixel5 (${view.widthDp()}dp) must stay strictly below the 560dp ceiling. If the " +
                "ceiling ever drops below a phone window, min() starts clipping message content " +
                "on phones and every committed chat baseline shifts.",
            view.resources.displayMetrics.widthPixels < view.capPx(),
        )
        assertEquals(
            "Phone parity: with no measured container the accessor MUST equal " +
                "displayMetrics.widthPixels, the value ChatMessageContainerView and " +
                "ImageAndVideoMessageView read before this change.",
            view.resources.displayMetrics.widthPixels,
            view.chatContentWidthPx(),
        )
        assertEquals("Pixel5 is a 393dp-wide window.", 393f, view.widthDp(), 1f)
    }

    @Test
    @Config(qualifiers = "w691dp-h716dp")
    fun `full-screen conversation at MEDIUM width is never narrower than a phone`() {
        // The regression lock. A conversation opened from search / a contact profile / a deep
        // link / a notification runs full-screen with no pane. A per-band cap would bind here
        // purely because the WINDOW is >= 673dp, giving this 691dp foldable LESS content width
        // than a 393dp phone.
        val view = detachedView()

        assertEquals(
            "A full-screen conversation on a 691dp window must get the 560dp ceiling.",
            560f,
            view.widthDp(),
            0.5f,
        )
        assertTrue(
            "A 691dp full-screen window must get MORE content width than a 393dp phone, not " +
                "less. A per-window-size-class cap below 393dp reintroduces the regression.",
            view.widthDp() > 393f,
        )
    }

    @Test
    @Config(qualifiers = "w673dp-h479dp")
    fun `the ceiling does not vary with the dual-pane height gate`() {
        // >= 673dp wide but < 480dp tall (folded landscape): layout-w673dp-h480dp/ does NOT apply,
        // so IndexActivity is single-pane full width. A pane-sized cap must not bind here either.
        assertEquals(560f, detachedView().widthDp(), 0.5f)
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun `the container accessor is uncapped so bubble-room decisions are not under-reported`() {
        // The two accessors answer different questions and MUST diverge above the ceiling:
        // chatContentWidthPx() caps content this code assigns an explicit width to (the image
        // bubble), while chatContainerWidthPx() is the room the bubble actually gets — what
        // ChatMessageContainerView's inline-vs-below timestamp decision needs. Using the capped
        // value there under-reports a 1000dp window by 440dp.
        val row = viewInContainer(containerDp = 1000)
        val density = row.resources.displayMetrics.density

        assertEquals(
            "chatContainerWidthPx() must report the full container width, uncapped.",
            1000f,
            row.chatContainerWidthPx() / density,
            0.5f,
        )
        assertEquals(
            "chatContentWidthPx() must still apply the 560dp ceiling on the same view.",
            560f,
            row.widthDp(),
            0.5f,
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h480dp")
    fun `the ceiling is band-independent in the tablet band`() {
        assertEquals(
            "chat_content_max_width must resolve to the single 560dp value in every band; a " +
                "narrower pane is handled by the container width, not by a narrower dimen.",
            560f,
            detachedView().widthDp(),
            0.5f,
        )
    }

    // ── Property 3: no WindowMetricsCalculator on the per-message path ─────────────────────────

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)
    fun `phone parity - height is the usable display height, not the window bounds`() {
        val view = detachedView()

        // Pinned against the PRE-CHANGE source of truth, not against the accessor's own
        // expression: `Resources.getSystem().displayMetrics.heightPixels` is literally what
        // ImageAndVideoMessageView read before this issue, and it is a different Resources object
        // from the View's, so this cannot pass by tautology. Asserting
        // `view.resources.displayMetrics.heightPixels` instead would restate the implementation and
        // could not detect the divergence being pinned here (a window-bounds read that grows the
        // screenHeight/3 media-bubble cap by the navigation-bar height on every phone).
        assertEquals(
            "Phone parity: chatContentHeightPx() MUST still equal the " +
                "Resources.getSystem().displayMetrics.heightPixels that ImageAndVideoMessageView " +
                "read before this change. That value excludes system decoration; window bounds " +
                "INCLUDE the system bars.",
            Resources.getSystem().displayMetrics.heightPixels,
            view.chatContentHeightPx(),
        )
        // Independent literal pin, so a unit slip (dp instead of px) or a different metric cannot
        // hide behind two equal-but-wrong sources: Pixel5 is 851dp tall at density 2.75.
        assertEquals(
            "Pixel5's usable display height is 851dp x 2.75 = 2340px.",
            2340,
            view.chatContentHeightPx(),
        )
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)
    fun `neither accessor reads the reflective window-bounds helper`() {
        // WindowMetricsCalculator.computeCurrentWindowMetrics resolves bounds through
        // Configuration.windowConfiguration private-field reflection on API 26-29 (minSdk 26),
        // plus Class.forName("android.view.DisplayInfo") on API 28. Both accessors run inside
        // onMeasure / onBind of a per-message row, so neither may call it. Stubbing the helper to
        // a sentinel is what makes this discriminating: Robolectric reports identical numbers for
        // window bounds and displayMetrics, so a value assertion alone could not tell them apart.
        mockkObject(WindowSizeClassUtil)
        every { WindowSizeClassUtil.getWindowWidthPx(any()) } returns sentinelPx
        every { WindowSizeClassUtil.getWindowHeightPx(any()) } returns sentinelPx

        val view = detachedView()

        assertNotEquals(
            "chatContentWidthPx() must not route through WindowSizeClassUtil.getWindowWidthPx.",
            sentinelPx,
            view.chatContentWidthPx(),
        )
        assertNotEquals(
            "chatContentHeightPx() must not route through WindowSizeClassUtil.getWindowHeightPx.",
            sentinelPx,
            view.chatContentHeightPx(),
        )
        assertEquals(view.resources.displayMetrics.widthPixels, view.chatContentWidthPx())
        assertEquals(view.resources.displayMetrics.heightPixels, view.chatContentHeightPx())
    }
}
