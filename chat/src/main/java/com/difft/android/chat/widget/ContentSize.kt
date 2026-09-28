package com.difft.android.chat.widget

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.R

/**
 * Size of the box message content may occupy, for the conversation surface's size-deriving call
 * sites ([ImageAndVideoMessageView] and `ChatMessageContainerView`) — one shared source so they
 * cannot diverge.
 *
 * Every accessor here runs per message measure / per message bind, so all of them must stay
 * allocation-free and must NOT call `WindowMetricsCalculator` (see [chatContainerWidthPx]).
 */

/**
 * Width of the conversation viewport this view is laid out in, in px — NOT the window.
 *
 * That distinction is the whole point: the same conversation fragment renders inside the
 * dual-pane detail pane (`fragment_container_detail`) and full-screen in `ChatActivity`
 * (reached from search, a contact profile, a deep link or a notification), and only the
 * container knows which. Deriving from the container also keeps the two view classes free of
 * any `isDualPaneMode` check: the resource qualifier `layout-w673dp-h480dp/` stays the single
 * dual-pane enforcement mechanism, and a second runtime gate could disagree with it near the
 * boundary (`WindowMetrics` bounds include system decoration while `w<N>dp` matches
 * `Configuration.screenWidthDp`).
 *
 * ## Why the message `RecyclerView` is preferred over the nearest full-width ancestor
 *
 * The answer must be the CURRENT layout pass's width, and among a message view's ancestors only
 * the message list can promise that. The detail pane can be resized with NO configuration
 * change at all — the user drags the pane divider — and a multi-window resize that only moves
 * `screenSize` is likewise handled without recreation. During such an in-place re-measure the
 * ancestors wrapping a message view (the row root `ChatMessageItemView`, a forwarded card) are
 * measured AROUND the descendant, so while a descendant's `onMeasure`/bind runs their
 * `measuredWidth` is still the PREVIOUS pass's value. Reading them yields a stale size that
 * nothing later corrects, because a matching stale value also satisfies the re-resolve guard in
 * `ImageAndVideoMessageView`.
 *
 * A `MATCH_PARENT` `RecyclerView` has no such window: it always receives an `EXACTLY` width
 * spec, and `RecyclerView`'s auto-measure sets its measured dimension from that spec and
 * RETURNS before touching a child — children are created, bound and measured later, from
 * `onLayout` -> `dispatchLayout`. So at every bind and every child measure
 * `recyclerView.measuredWidth` is already the new width. `wrap_content` `RecyclerView`s
 * (nested history lists) do measure children from their own `onMeasure` and are deliberately
 * excluded by the `MATCH_PARENT` filter.
 *
 * Anchoring on the list is also what the two callers actually want: both ask "how wide is the
 * conversation viewport", not "how wide is the card this bubble sits in".
 *
 * ## Fallbacks, in order
 *
 * 1. The nearest `MATCH_PARENT` ancestor, for hosts that are not a message list at all (a
 *    pinned banner, a search result row, an isolated inflation in a test harness).
 *    `MATCH_PARENT` is the filter that keeps this a *container* width: every ancestor between
 *    a message view and its row root (`contentFrame`, `contentContainer`) is `wrap_content`
 *    and would otherwise report the previously bound message's width.
 * 2. `resources.displayMetrics.widthPixels`, when there is no parent chain at all. That is the
 *    state `RecyclerView` binds a freshly created row in (`onCreateViewHolder` inflates with
 *    `attachToRoot = false`), so the first bind of a new row lands here — the window, which
 *    over-sizes content in a narrow pane. Any caller that consumes this AT BIND TIME must
 *    re-resolve once the view is attached and once it is measured; see
 *    `ImageAndVideoMessageView.onAttachedToWindow` / `onMeasure`.
 *
 * `resources.displayMetrics` is that last fallback, not `windowWidthPx()`, on purpose:
 * `WindowMetricsCalculator.computeCurrentWindowMetrics` resolves its bounds through
 * `Configuration.windowConfiguration` private-field reflection on API 26-29 (minSdk is 26) —
 * plus a `Class.forName("android.view.DisplayInfo")` on API 28 — on every call, which is not
 * something this scroll path can afford. For a View hosted by an Activity,
 * `resources.displayMetrics` is already scoped to that Activity's window (so it follows
 * split-screen), and it excludes system decoration.
 */
internal fun View.chatContainerWidthPx(): Int {
    var nearestFullWidthPx = 0
    var ancestor: ViewGroup? = parent as? ViewGroup
    while (ancestor != null) {
        if (ancestor.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            val contentWidthPx = ancestor.measuredWidth - ancestor.paddingLeft - ancestor.paddingRight
            if (contentWidthPx > 0) {
                if (ancestor is RecyclerView) return contentWidthPx
                if (nearestFullWidthPx == 0) nearestFullWidthPx = contentWidthPx
            }
        }
        ancestor = ancestor.parent as? ViewGroup
    }
    return if (nearestFullWidthPx > 0) nearestFullWidthPx else resources.displayMetrics.widthPixels
}

/**
 * Width available to message content the app SIZES ITSELF, in px: the container width capped by
 * `R.dimen.chat_content_max_width`.
 *
 * The cap is a genuine ceiling on content this code assigns an explicit width to — today only
 * the image/video bubble — so a photo does not stretch edge-to-edge on a wide FULL-SCREEN
 * window. 560dp is above every phone width, so phone rendering is unchanged by arithmetic. It
 * is deliberately a single band-independent value: a narrower per-band cap is selected by
 * window width alone, so it would also bind on full-screen conversations at those widths and
 * render content NARROWER there than a phone gets.
 *
 * Do NOT use this where the question is "how much room does the bubble actually have" — a
 * bubble whose width this code does not assign (a text bubble, or an image caption, both
 * `wrap_content` bounded only by the row's ConstraintLayout) can occupy the full container,
 * so measuring it against a 560dp ceiling under-reports the room by the whole difference and
 * pushes the inline timestamp below the text on a wide window with room still free; that
 * question reads [chatContainerWidthPx] instead.
 */
fun View.chatContentWidthPx(): Int =
    minOf(chatContainerWidthPx(), resources.getDimensionPixelSize(R.dimen.chat_content_max_width))

/**
 * Height available to message content, in px.
 *
 * The detail pane is full height, so — unlike width — there is no pane term here; the only
 * requirement is that the value stays the app window's usable height.
 * `resources.displayMetrics` is that, and it follows the Activity's own window in
 * split-screen. It is NOT the window bounds: those INCLUDE the system bars, which would
 * silently grow every `screenHeight / 3` media-bubble cap by the navigation-bar height on
 * every phone.
 */
fun View.chatContentHeightPx(): Int = resources.displayMetrics.heightPixels
