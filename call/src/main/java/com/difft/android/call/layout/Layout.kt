// Synced from call-layout bf6b84ecbe, ports/kotlin/src/main/kotlin/org/difft/calllayout/Layout.kt (spec v25), by scripts/sync-apps.ts.
// Do not edit here: change call-layout and sync again.

package com.difft.android.call.layout

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Ported line by line from src/core/layout.ts. Floating point runs in the same order as the TS so
// the golden vectors match; keep it that way when changing either side.

fun classifyWindow(win: Size, cfg: LayoutConfig = LayoutConfig.DEFAULT): WindowClass =
    if (win.w >= cfg.wideMinWidth && win.h >= cfg.wideMinHeight) WindowClass.WIDE else WindowClass.COMPACT

fun orientationOf(win: Size): Orientation = if (win.w > win.h) Orientation.LANDSCAPE else Orientation.PORTRAIT

fun postureOf(fold: Rect?): Posture {
    if (fold == null) return Posture.FLAT
    return if (fold.w >= fold.h) Posture.TABLETOP else Posture.BOOK
}

// JS truthiness of an optional aspect: missing, 0 and NaN all mean no video shape.
private fun hasAspect(a: Double?): Boolean = a != null && a != 0.0 && !a.isNaN()

private fun hasVideo(p: Participant): Boolean = p.videoOn && hasAspect(p.videoAspect)

private class Base(val windowClass: WindowClass, val orientation: Orientation, val posture: Posture, val controls: Rect? = null)

private class GridResult(val tiles: List<Tile>, val grid: GridInfo)

// A grid and the box it was laid out in.
private class Laid(val r: GridResult, val box: Rect)

// A fold counts only while it crosses the window, its line strictly inside; one reported for
// another window, or before the window has its new size, counts as none.
// A 1on1 that already holds a third person, before the app hears it became an instant call, lays out
// as a group (decision 40).
fun isOneOnOne(callType: CallType, count: Int): Boolean = callType == CallType.ONE_ON_ONE && count <= 2

fun crossingFold(win: Size, fold: Rect?): Rect? {
    if (fold == null) return null
    if (fold.w >= fold.h) return if (fold.y > 0 && fold.y + fold.h < win.h) fold else null
    return if (fold.x > 0 && fold.x + fold.w < win.w) fold else null
}

fun resolveLayout(raw: LayoutInput, cfg: LayoutConfig = LayoutConfig.DEFAULT): LayoutPlan {
    // A content area below nothing (a cover display too small for the call page) lays out as empty.
    val input = raw.copy(
        content = notNegative(raw.content),
        pagingContent = raw.pagingContent?.let(::notNegative),
        fold = crossingFold(raw.window, raw.fold),
    )
    val windowClass = classifyWindow(input.window, cfg)
    val orientation = orientationOf(input.window)
    val fold = input.fold
    val posture = postureOf(fold)
    val win = fullWindow(input)
    val upper = upperOf(input)
    val facing = facingSide(input.window, fold)
    // Tabletop: the app gives the size of its controls below the fold and the core places them (decision 39).
    val controlsSize = input.controlsSize
    val placed = if (fold != null && posture == Posture.TABLETOP && controlsSize != null) placeBelowFold(input, fold, controlsSize, cfg) else null
    val base = Base(windowClass, orientation, posture, placed?.controls?.let { roundRect(it) })
    val dock = placed?.dock ?: if (fold != null && posture == Posture.TABLETOP) dockBelow(fold, input.content, cfg) else null
    val c = input.content
    val facingContent = if (facing != null && fold != null) facingStage(facing, fold, c, cfg) else null

    val share = input.share
    if (share != null) {
        val region = upper ?: facing ?: win
        val zoom = share.zoom ?: ContentMode.FIT
        val rect = if (zoom == ContentMode.FILL) coverRect(share.aspect, region) else fitRect(share.aspect, region)
        // A compact window turns landscape for a share, unless a fold is half open across it: that is a
        // flip phone standing on its lower half, so the share stays above the fold and a window already
        // turned for the share turns back upright (decision 38).
        val request = when {
            windowClass != WindowClass.COMPACT || input.allowRotation -> RequestOrientation.ANY
            fold != null -> RequestOrientation.PORTRAIT
            else -> RequestOrientation.LANDSCAPE
        }
        val tile = Tile(
            id = "share",
            role = TileRole.SHARE,
            rect = rect,
            contentMode = zoom,
            mismatch = mismatch(share.aspect, aspectOf(Size(region.w, region.h))),
            z = 0,
        )
        return finish(base, request, LayoutMode.SHARE, listOf(tile), null, dock)
    }

    val request = if (windowClass == WindowClass.COMPACT && !input.allowRotation) RequestOrientation.PORTRAIT else RequestOrientation.ANY
    val ps = input.participants

    // 1on1: the other person in the main view, you in a floating window. Tabletop puts the main view
    // above the fold and the window below it; book posture keeps the main view on the left half, or
    // both on the side facing the user when the fold is off centre.
    if (isOneOnOne(input.callType, ps.size) && ps.any { !it.isLocal }) {
        val area = placed?.place ?: if (upper != null && dock != null) dock else facingContent ?: c
        val tiles = layoutOneOnOne(input, oneOnOneMedia(input), area, cfg)
        val pip = placed?.pip
        val laid = if (pip != null) tiles.map { t -> if (t.role == TileRole.PIP) pipTile(ps.first { it.id == t.id }, pip, cfg) else t } else tiles
        return finish(base, request, LayoutMode.PIP, laid, null, dock, placed?.pipArea ?: area)
    }

    if (ps.size <= 1) {
        val tiles = if (ps.size == 1) listOf(videoTile(ps[0], TileRole.SOLO, upper ?: facing ?: win, 0, cfg)) else emptyList()
        return finish(base, request, LayoutMode.SOLO, tiles, null, dock)
    }

    val stage = groupStage(input.window, c, fold, cfg)
    val stageIn = { content: Rect -> groupStage(input.window, content, fold, cfg) }
    // A group of two: two cards of the same size, you first (decisions 29, 30); the book posture splits
    // it at the fold instead, unless the fold is off centre.
    if (ps.size == 2 && (posture != Posture.BOOK || facingContent != null)) {
        return finish(base, request, LayoutMode.DUO, layoutDuo(ps, stage, input.swapped, cfg), null, dock)
    }
    // Desktop never scrolls (decision 19): up to moreMaxTiles cells with no minimum card size and rows
    // for up to that many people; from one more, the last cell stands for everyone not shown.
    val more = input.overflow == Overflow.MORE
    val folded = more && ps.size > cfg.moreMaxTiles
    val cells = if (folded) ps.take(cfg.moreMaxTiles - 1) + MORE_CELL else ps
    val gcfg = if (more) cfg.copy(adaptiveMaxCount = cfg.moreMaxTiles, cardMinWidth = 0.0, widenBarsMax = 0.0) else cfg
    // Half open the cards may get a little smaller before the rest go to the next page (decision 17).
    val opts = GridOptions(
        minCard = if (more) 0.0 else if (fold != null) cfg.minCardHalf else if (windowClass == WindowClass.WIDE) cfg.minCardWide else cfg.minCardCompact,
        compact = windowClass == WindowClass.COMPACT,
        scrollRows = !more,
    )
    // The grid for a list of people in a content area: above the fold or on the facing side, split at
    // a centred fold, else the content area itself.
    val layIn = { content: Rect ->
        lay@{ list: List<Participant>, whole: Boolean ->
            val o = if (whole) opts.copy(whole = true) else opts
            if ((fold != null && posture == Posture.TABLETOP) || facingContent != null) {
                val box = stageIn(content)
                return@lay Laid(layoutGrid(list, box, o, gcfg), box)
            }
            if (fold != null && posture == Posture.BOOK) {
                val halves = bookSplitGrid(list, content, fold, o, gcfg)
                if (halves != null) return@lay Laid(halves, content)
                // Pages split at the fold too (decision 28): as many people as both halves hold, up to a
                // page, and the rest on the pages after.
                if (opts.scrollRows) {
                    for (m in min(list.size - 1, gcfg.pageMax) downTo 2) {
                        val part = bookSplitGrid(list.take(m), content, fold, o, gcfg) ?: continue
                        return@lay Laid(GridResult(part.tiles, part.grid.copy(scroll = true, visibleCount = m)), content)
                    }
                }
            }
            Laid(layoutGrid(list, content, o, gcfg), content)
        }
    }
    if (more) {
        val r = layIn(c)(cells, false).r
        val g = if (!folded) r else withMoreCell(r, ps.size - (cells.size - 1))
        return finish(base, request, LayoutMode.GRID, g.tiles, g.grid.copy(page = 0, pages = 1, later = 0), dock)
    }
    val paging = input.pagingContent ?: c
    val r = paginate(ps, layIn(paging), if (paging == c) null else layIn(c), input.page, cfg.pageMax)
    return finish(base, request, LayoutMode.GRID, r.tiles, r.grid, dock)
}

// Phones and folds turn pages instead of scrolling (decision 24). A page is the first screen of the
// people not shown yet, with you first on every page; once the rest fit one screen they are laid
// out on their own, so the last page's cards grow. A page holds at most `pageMax` people (decision 26):
// one cut short of what fits is laid out on its own too, with all its people on it, so its rows stay
// even (decision 35). Returns the page asked for, clamped.
// `relay`: with the controls hidden (decision 25) the pages are cut in the area with them shown and
// the page asked for is laid out on its own in the larger area; if it would not fit there on one
// screen, it keeps its place from the smaller one.
private fun paginate(ps: List<Participant>, lay: (List<Participant>, Boolean) -> Laid, relay: ((List<Participant>, Boolean) -> Laid)?, page: Int, pageMax: Int): GridResult {
    val you = ps.firstOrNull { it.isLocal }
    val mine = if (you != null) 1 else 0
    var rest = ps.filter { it !== you }
    val pages = mutableListOf<GridResult>()
    val later = mutableListOf<Int>()
    while (true) {
        val list = if (you != null) listOf(you) + rest else rest
        val laid = lay(list, false)
        val fit = if (laid.r.grid.scroll) laid.r.grid.visibleCount else list.size
        val count = min(fit, pageMax)
        if (count >= list.size || rest.isEmpty()) {
            pages.add(if (laid.r.grid.scroll) firstScreen(laid, list.size) else laid.r)
            later.add(0)
            break
        }
        // Room for one card only (a small cover display): the next person has the page to themselves
        // instead of a second card off the screen (decision 28).
        val alone = count <= mine
        val members = if (alone) rest.take(1) else list.take(count)
        var cut = if (alone) null else firstScreen(laid, count)
        if (alone || count < fit) {
            val own = lay(members, true)
            if (!own.r.grid.scroll) cut = own.r
            else if (cut == null) cut = firstScreen(own, members.size)
        }
        pages.add(cut!!)
        rest = rest.drop(members.size - (if (alone) 0 else mine))
        later.add(rest.size)
    }
    val p = max(0, min(pages.size - 1, page))
    var shown = pages[p]
    if (relay != null) {
        val ids = shown.tiles.map { it.id }.toSet()
        val again = relay(ps.filter { it.id in ids }, false).r
        if (!again.grid.scroll) shown = again
    }
    return GridResult(
        shown.tiles,
        shown.grid.copy(visibleCount = pages[0].tiles.size, page = p, pages = pages.size, later = later[p]),
    )
}

// The first `count` cards of a grid that would scroll, which are whole rows, centred in its box.
private fun firstScreen(laid: Laid, count: Int): GridResult {
    val tiles = laid.r.tiles.take(count)
    val top = tiles.minOf { it.rect.y }
    val height = tiles.maxOf { it.rect.y + it.rect.h } - top
    val dy = laid.box.y + max(0.0, laid.box.h - height) / 2 - top
    return GridResult(
        tiles.map { t -> t.copy(rect = t.rect.copy(y = t.rect.y + dy)) },
        laid.r.grid.copy(scroll = false, rows = tiles.map { r2(it.rect.y) }.toSet().size, contentHeight = r2(height)),
    )
}

// The "+N" cell of Overflow.MORE goes through the grid as a participant without video.
private val MORE_CELL = Participant(id = "__overflow", isLocal = false, videoOn = false)

private fun withMoreCell(r: GridResult, hidden: Int): GridResult = GridResult(
    tiles = r.tiles.map { t -> if (t.id == MORE_CELL.id) t.copy(role = TileRole.OVERFLOW, overflowCount = hidden) else t },
    grid = r.grid.copy(visibleCount = r.grid.visibleCount - 1),
)

// minCard: the short side below which cards stop shrinking and the rest go to later pages. compact:
// the window class, for the scrolling rows' avatar shape. scrollRows (phones and folds): rows that
// don't fit one screen run on past it (decision 21) and `paginate` cuts them into pages (decision 24),
// instead of falling back to the uniform grid; rows that fit are fitted (decision 31). whole: everyone
// goes on the first screen if they can, not only firstScreenMin (a page's people).
private data class GridOptions(val minCard: Double, val compact: Boolean, val scrollRows: Boolean, val whole: Boolean = false)

data class GridSolution(val cols: Int, val rows: Int, val card: Size, val fits: Boolean)

// Tries every column count and keeps the one with the largest card.
// Within 1% of the best area, fewer empty slots wins; otherwise fewer columns.
fun solveGrid(n: Int, box: Size, minCard: Double, cfg: LayoutConfig): GridSolution {
    var best: GridSolution? = null
    var bestArea = 0.0
    var bestEmpty = 0
    for (c in 1..n) {
        val r = ceil(n.toDouble() / c).toInt()
        val cellW = (box.w - (c - 1) * cfg.gap) / c
        val cellH = (box.h - (r - 1) * cfg.gap) / r
        if (cellW <= 0 || cellH <= 0) continue
        val card = clampAspect(cellW, cellH, cfg.cardAspectMin, cfg.cardAspectMax)
        val area = card.w * card.h
        val empty = c * r - n
        if (best == null || area > bestArea * 1.01 || (area >= bestArea * 0.99 && empty < bestEmpty)) {
            best = GridSolution(cols = c, rows = r, card = card, fits = min(card.w, card.h) >= minCard)
            bestArea = area
            bestEmpty = empty
        }
    }
    return best ?: GridSolution(cols = 1, rows = n, card = Size(0.0, 0.0), fits = false)
}

private fun sizeOf(r: Rect) = Size(r.w, r.h)

// Rows when anyone is on camera and the rows fit one screen (4.3.1), else scrolling rows (4.3.2),
// else the uniform grid (4.3). On phones and folds the scrolling rows also win over rows that fit
// when they show more of the pictures (decision 31).
private fun layoutGrid(ps: List<Participant>, box: Rect, opts: GridOptions, cfg: LayoutConfig): GridResult {
    val n = ps.size
    val minCard = opts.minCard
    val whole = solveGrid(n, sizeOf(box), minCard, cfg)
    val video = cfg.adaptiveCards && ps.any { hasVideo(it) }
    // Avatar cards keep the uniform grid's shape so turning on the first camera doesn't reshape them.
    val avatarAspect = if (whole.card.h > 0) whole.card.w / whole.card.h else 1.0
    val rows = if (video && n <= cfg.adaptiveMaxCount) layoutRows(ps, box, avatarAspect, minCard, opts.scrollRows, cfg) else null
    if (rows != null && !opts.scrollRows) return rows
    // Phones and folds show at least firstScreenMin people on the first screen (decision 22).
    val want = if (!opts.scrollRows) 0 else if (opts.whole) n else min(n, cfg.firstScreenMin)
    val uniform = uniformGrid(ps, box, minCard, whole, want, cfg)
    if (!video || !opts.scrollRows) return uniform
    val target = max(wholeOnFirstScreen(uniform.grid, box, n, cfg), want)
    // Avatars are square on a phone, where the uniform grid's cards are nearly square anyway.
    val scrolled = layoutScrollRows(ps, box, if (opts.compact) 1.0 else avatarAspect, minCard, target, want, cfg)
    // Phones and folds (decision 31): rows that fit the screen against the scrolling rows, which crop
    // landscape cards to 4:3; the scrolling rows win only when they fit one screen too, keep within
    // rowsHeightRatioMax and even rows, and show more of the pictures or the rows that fit are uneven
    // (decision 35).
    if (rows == null || scrolled.grid.scroll) return rows ?: scrolled
    val better = shownArea(scrolled) > shownArea(rows) * 1.005 || !evenRows(rows)
    return if (heightRatio(scrolled) <= cfg.rowsHeightRatioMax && evenRows(scrolled) && better) scrolled else rows
}

// How many whole cards the uniform grid shows before scrolling.
private fun wholeOnFirstScreen(g: GridInfo, box: Rect, n: Int, cfg: LayoutConfig): Int {
    if (!g.scroll) return n
    return min(n.toDouble(), floor((box.h + cfg.gap + 0.5) / (g.card.h + cfg.gap)) * g.cols).toInt()
}

// Like solveGrid, but among the column counts whose cards keep at least `minCard` only; the
// largest-card count may not keep it while another does (a fold held level: 2 × 4 cards 155×87
// against 3 × 3 cards 104×119 above the fold, decision 28).
private fun solveGridFitting(n: Int, box: Size, minCard: Double, cfg: LayoutConfig): GridSolution {
    var best: GridSolution? = null
    var bestArea = 0.0
    var bestEmpty = 0
    for (c in 1..n) {
        val r = ceil(n.toDouble() / c).toInt()
        val cellW = (box.w - (c - 1) * cfg.gap) / c
        val cellH = (box.h - (r - 1) * cfg.gap) / r
        if (cellW <= 0 || cellH <= 0) continue
        val card = clampAspect(cellW, cellH, cfg.cardAspectMin, cfg.cardAspectMax)
        if (min(card.w, card.h) < minCard) continue
        val area = card.w * card.h
        val empty = c * r - n
        if (best == null || area > bestArea * 1.01 || (area >= bestArea * 0.99 && empty < bestEmpty)) {
            best = GridSolution(cols = c, rows = r, card = card, fits = true)
            bestArea = area
            bestEmpty = empty
        }
    }
    return best ?: solveGrid(n, box, minCard, cfg)
}

private data class UniformCards(val cols: Int, val card: Size, val scroll: Boolean, val visibleCount: Int)

// The uniform grid's cards: everyone on one screen while the cards keep at least `minCard`, else
// the card size of the largest crowd that does, the rest scrolling.
private fun uniformCards(n: Int, box: Rect, minCard: Double, whole: GridSolution, cfg: LayoutConfig): UniformCards {
    if (whole.fits) return UniformCards(whole.cols, whole.card, scroll = false, visibleCount = n)
    for (m in n - 1 downTo 1) {
        val s = solveGrid(m, sizeOf(box), minCard, cfg)
        if (s.fits) return UniformCards(s.cols, s.card, scroll = true, visibleCount = min(n, ceil(m.toDouble() / s.cols).toInt() * s.cols))
    }
    return UniformCards(1, solveGrid(1, sizeOf(box), minCard, cfg).card, scroll = true, visibleCount = 1)
}

// With fewer than `want` people on the first screen, the cards shrink to show `want`, down to
// minCardFloor; below that, the largest crowd at minCardFloor (decision 22).
private fun uniformGrid(ps: List<Participant>, box: Rect, minCard: Double, whole: GridSolution, want: Int, cfg: LayoutConfig): GridResult {
    val n = ps.size
    var u = uniformCards(n, box, minCard, whole, cfg)
    if (u.visibleCount < want) {
        val cardFloor = min(minCard, cfg.minCardFloor)
        val s = solveGridFitting(want, sizeOf(box), cardFloor, cfg)
        u = if (s.fits) {
            UniformCards(s.cols, s.card, scroll = n > want, visibleCount = min(n, ceil(want.toDouble() / s.cols).toInt() * s.cols))
        } else {
            uniformCards(n, box, cardFloor, solveGrid(n, sizeOf(box), cardFloor, cfg), cfg)
        }
    }
    val (cols, card, scroll, visibleCount) = u

    val rows = ceil(n.toDouble() / cols).toInt()
    val gap = cfg.gap
    val blockW = cols * card.w + (cols - 1) * gap
    val blockH = rows * card.h + (rows - 1) * gap
    val x0 = box.x + (box.w - blockW) / 2
    val y0 = if (scroll) box.y else box.y + (box.h - blockH) / 2

    val tiles = ps.mapIndexed { i, p ->
        val row = i / cols
        val col = i % cols
        val inRow = if (row == rows - 1) n - row * cols else cols
        val rowOffset = ((cols - inRow) * (card.w + gap)) / 2
        val rect = Rect(
            x = x0 + rowOffset + col * (card.w + gap),
            y = y0 + row * (card.h + gap),
            w = card.w,
            h = card.h,
        )
        videoTile(p, TileRole.GRID, rect, 0, cfg)
    }

    return GridResult(
        tiles,
        GridInfo(
            cols = cols,
            rows = rows,
            card = Size(r2(card.w), r2(card.h)),
            scroll = scroll,
            visibleCount = visibleCount,
            contentHeight = r2(blockH),
            adaptive = false,
        ),
    )
}

private class RowsPick(val mask: Int, val rows: Int, val area: Double, val minH: Double, val mean: Double, val rises: Int)

// Adaptive cards: each card takes its video's shape (avatars use `avatarAspect`), cards keep
// their order and are packed into rows of equal height that fill the box width; on phones and
// folds no card is narrower than cardMinWidth (decision 36, `spanHeight`). Every way of breaking
// the sequence into rows is tried (bit i of the mask = break after card i, masks in ascending
// order); ones with a row shorter than the minimum card size are skipped. Each row is first as
// tall as spans the box width. When the rows are too tall for the box:
// - Desktop: they shrink together. The winner has the tallest shortest row, so everyone stays
//   about equally big; within 1% the larger total card area wins, within 0.5% of that the fewer
//   rows, then the earlier mask.
// - Phones and folds (`fill`, decision 31): only the tallest shrink, to one height at which the
//   rows fill the box; the short rows keep theirs. Ways whose rows differ more than
//   rowsHeightRatioMax times in height, or by more than one card in length, are skipped; the
//   lengths only while some way keeps them that even. The winner has the largest geometric mean of
//   everyone's card height (decision 35), so no one or two cards take most of the screen; within
//   0.5% the taller shortest row, within 1% of that the fewer rows, then the rows fuller at the top
//   (three and two, not two and three), then the earlier mask. A row that does not span the box
//   widens its cards (decision 37).
// Returns null when no way fits.
private fun layoutRows(ps: List<Participant>, box: Rect, avatarAspect: Double, minCard: Double, fill: Boolean, cfg: LayoutConfig): GridResult? {
    val n = ps.size
    val gap = cfg.gap
    val aspects = DoubleArray(n) { i ->
        val p = ps[i]
        if (hasVideo(p)) min(cfg.adaptiveAspectMax, max(cfg.adaptiveAspectMin, p.videoAspect!!)) else avatarAspect
    }

    val minW = cfg.cardMinWidth
    // Row sums, sizes, natural heights, fitted heights and sorted heights go into reused arrays:
    // Desktop tries 2^15 ways, phones and folds 2^11.
    val sums = DoubleArray(n)
    val lens = IntArray(n)
    val natural = DoubleArray(n)
    val fitted = DoubleArray(n)
    val sorted = DoubleArray(n)
    fun measure(mask: Int): Int {
        var count = 0
        var sum = 0.0
        var len = 0
        for (i in 0 until n) {
            sum += aspects[i]
            len++
            if (i == n - 1 || (mask shr i) and 1 != 0) {
                sums[count] = sum
                lens[count] = len
                natural[count] = spanHeight(aspects, i + 1 - len, i + 1, box.w, gap, minW)
                count++
                sum = 0.0
                len = 0
            }
        }
        return count
    }
    // Fits the first `count` rows into the box height (see above); false when the gaps alone fill it.
    fun fit(count: Int): Boolean {
        val room = box.h - (count - 1) * gap
        if (!(room > 0)) return false
        for (r in 0 until count) if (!(natural[r] > 0)) return false
        var total = 0.0
        for (r in 0 until count) total += natural[r]
        var cap = Double.POSITIVE_INFINITY
        var k = 1.0
        if (total > room && !fill) k = room / total
        if (total > room && fill) {
            // stretchCap on the first `count` natural heights, sorted by insertion in place.
            for (r in 0 until count) {
                var j = r
                while (j > 0 && sorted[j - 1] > natural[r]) {
                    sorted[j] = sorted[j - 1]
                    j--
                }
                sorted[j] = natural[r]
            }
            var below = 0.0
            for (j in 0 until count) {
                cap = (room - below) / (count - j)
                if (cap <= sorted[j]) break
                below += sorted[j]
            }
        }
        for (r in 0 until count) fitted[r] = min(natural[r], cap) * k
        return true
    }
    var best: RowsPick? = null
    // Phones and folds try the ways with rows of even length first (decision 35), the rest only when
    // none of those fits.
    var pass = if (fill) 0 else 1
    while (pass < 2 && best == null) {
        for (mask in 0 until (1 shl (n - 1))) {
            val count = measure(mask)
            var shortRow = n
            var longRow = 0
            var rises = 0
            for (r in 0 until count) {
                shortRow = min(shortRow, lens[r])
                longRow = max(longRow, lens[r])
                if (r > 0 && lens[r] > lens[r - 1]) rises++
            }
            if (pass == 0 && longRow - shortRow > 1) continue
            if (!fit(count)) continue
            var minH = Double.POSITIVE_INFINITY
            var maxH = 0.0
            var area = 0.0
            var logs = 0.0
            for (r in 0 until count) {
                val h = fitted[r]
                minH = min(minH, h)
                maxH = max(maxH, h)
                area += h * h * sums[r]
                logs += lens[r] * ln(h)
            }
            if (minH < minCard) continue
            if (fill && maxH > minH * cfg.rowsHeightRatioMax) continue
            val mean = exp(logs / n)
            val b = best
            val better = b == null ||
                if (fill) {
                    mean > b.mean * 1.005 ||
                        (mean >= b.mean * 0.995 &&
                            (minH > b.minH * 1.01 || (minH >= b.minH * 0.99 && (count < b.rows || (count == b.rows && rises < b.rises)))))
                } else {
                    minH > b.minH * 1.01 || (minH >= b.minH * 0.99 && (area > b.area * 1.005 || (area >= b.area * 0.995 && count < b.rows)))
                }
            if (better) best = RowsPick(mask, count, area, minH, mean, rises)
        }
        pass++
    }
    val pick = best ?: return null
    val chosen = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    for (i in 0 until n) {
        current.add(i)
        if (i < n - 1 && (pick.mask shr i) and 1 != 0) {
            chosen.add(current)
            current = mutableListOf()
        }
    }
    chosen.add(current)
    fit(measure(pick.mask))
    val heights = DoubleArray(chosen.size) { r -> fitted[r] }

    val blockH = heights.fold(0.0) { s, h -> s + h } + (chosen.size - 1) * gap
    var y = box.y + (box.h - blockH) / 2
    val rects = arrayOfNulls<Rect>(n)
    chosen.forEachIndexed { r, row ->
        val h = heights[r]
        val widths = widenRow(row.map { aspects[it] }, h, box.w, gap, minW, cfg.widenBarsMax)
        var x = box.x + (box.w - (widths.sum() + (row.size - 1) * gap)) / 2
        row.forEachIndexed { j, i ->
            rects[i] = Rect(x, y, widths[j], h)
            x += widths[j] + gap
        }
        y += h + gap
    }

    val first = rects[0]!!
    return GridResult(
        ps.mapIndexed { i, p -> gridTile(p, rects[i]!!, aspects[i], cfg) },
        GridInfo(
            cols = chosen.maxOf { it.size },
            rows = chosen.size,
            card = Size(r2(first.w), r2(first.h)),
            scroll = false,
            visibleCount = n,
            contentHeight = r2(blockH),
            adaptive = true,
        ),
    )
}

// `H`: the row height it was arranged for; `minW`: the narrowest a card may be in it.
private class Arrangement(val rows: List<List<Int>>, val heights: List<Double>, val onFirst: Int, val H: Double, val minW: Double)

// Scrolling rows (decision 21): when the rows don't fit one screen, phones and folds keep them,
// running on past the screen, instead of falling back to the uniform grid; `paginate` turns them
// into pages (decision 24). Video cards take their video's shape, with
// landscape capped at scrollAspectMax; avatar cards take `avatarAspect`. Cards keep their order.
// - Arranging for k rows on the first screen: H = (box.h - (k - 1)·gap) / k. Cards are packed
//   greedily at height H: a card starts a new row when it would make the row wider than the box.
//   Then every row grows to span the box width, up to scrollStretch × H; if the first k rows would
//   no longer fit the screen, they grow evenly less, so they fill it exactly (stretchCap).
// - k counts up from 1. While H is at least minCard, the first arrangement whose first k rows hold
//   at least `target` people wins (the largest cards that show as many people as the uniform grid).
//   Below minCard, down to minCardFloor, rows only go on shrinking until the first screen holds
//   `want` people (decision 22). If none does, the last one tried. A box lower than the floor gets
//   one unstretched row of minCard per screen.
// - No card is narrower than cardMinWidth (decision 36), unless that keeps the first screen from
//   holding `want` people (a flip phone's cover display, a small half-open stage): then the cards
//   keep their own widths and the first screen holds as many as it did before decision 36.
// - Rows are centred across; a block shorter than the box is centred in it and does not scroll.
private fun layoutScrollRows(
    ps: List<Participant>,
    box: Rect,
    avatarAspect: Double,
    minCard: Double,
    target: Int,
    want: Int,
    cfg: LayoutConfig,
): GridResult {
    val n = ps.size
    val gap = cfg.gap
    val cardFloor = min(minCard, cfg.minCardFloor)
    val aspects = DoubleArray(n) { i ->
        val p = ps[i]
        if (hasVideo(p)) min(cfg.scrollAspectMax, max(cfg.adaptiveAspectMin, p.videoAspect!!)) else avatarAspect
    }
    fun arrange(k: Int, H: Double, stretch: Boolean, minW: Double): Arrangement {
        val rows = mutableListOf<List<Int>>()
        var row = mutableListOf<Int>()
        var w = 0.0
        aspects.forEachIndexed { i, a ->
            val cw = max(a * H, minW)
            if (row.isNotEmpty() && w + gap + cw > box.w + 0.01) {
                rows.add(row)
                row = mutableListOf()
                w = 0.0
            }
            w += (if (row.isNotEmpty()) gap else 0.0) + cw
            row.add(i)
        }
        rows.add(row)
        // Rows that all fit the first screen hold an even number of cards, the fuller ones on top, when
        // the cards fit across that way at H (decision 35); filling each row in turn can leave one card
        // on a row of its own.
        val lens = rows.map { it.size }
        if (rows.size > 1 && rows.size <= k && lens.max() - lens.min() > 1) {
            val even = mutableListOf<List<Int>>()
            var at = 0
            for (r in rows.indices) {
                val len = n / rows.size + (if (r < n % rows.size) 1 else 0)
                even.add((at until at + len).toList())
                at += len
            }
            val fits = even.all { r -> r.fold(0.0) { acc, i -> acc + max(aspects[i] * H, minW) } + (r.size - 1) * gap <= box.w + 0.01 }
            if (fits) {
                rows.clear()
                rows.addAll(even)
            }
        }
        // A row whose cards cannot span the box at any height (a box narrower than cardMinWidth) spans
        // it as if there were no minimum width.
        val spans = rows.map { r ->
            val ra = DoubleArray(r.size) { aspects[r[it]] }
            val h = spanHeight(ra, 0, ra.size, box.w, gap, minW)
            if (h != 0.0) h else spanHeight(ra, 0, ra.size, box.w, gap, 0.0)
        }
        val first = min(k, rows.size)
        val room = box.h - (first - 1) * gap
        var cap = if (stretch) cfg.scrollStretch * H else H
        if (stretch && spans.take(first).fold(0.0) { s, f -> s + min(f, cap) } > room) cap = stretchCap(spans.take(first), room)
        val heights = spans.map { f -> min(f, cap) }
        val onFirst = rows.take(first).fold(0) { s, r -> s + r.size }
        return Arrangement(rows, heights, onFirst, H, minW)
    }
    fun choose(minW: Double): Arrangement {
        var pick: Arrangement? = null
        for (k in 1..n) {
            val H = (box.h - (k - 1) * gap) / k
            if (H < cardFloor) break
            val low = H < minCard
            if (low && pick != null && pick.onFirst >= want) break
            pick = arrange(k, H, true, minW)
            if (pick.onFirst >= (if (low) want else target)) break
        }
    // Still short of `want` on a low, wide stage (a fold held level and half open, a cover display):
    // rows lower than a whole division of the screen hold more cards each. Every height at which a run
    // of cards just spans the box, from the tried height down to the floor, is tried with as many rows
    // as fit; the first that reaches `want` wins, else the one holding the most (decision 28).
        val short = pick
        if (short != null && short.onFirst < want) {
            val tried = short.H
            val heights = mutableSetOf(cardFloor)
            for (i in 0 until n) {
                var sum = 0.0
                for (j in i until n) {
                    sum += aspects[j]
                    val H = (box.w - (j - i) * gap) / sum
                    if (H >= cardFloor && H < tried) heights.add(H)
                }
            }
            var best: Arrangement = short
            for (H in heights.sortedDescending()) {
                if (H >= tried) continue
                val a = arrange(max(1, floor((box.h + gap) / (H + gap)).toInt()), H, true, minW)
                if (a.onFirst > best.onFirst) best = a
                if (best.onFirst >= want) break
            }
            pick = best
        }
        // A box lower than the floor (a cover display too small for the call page): one row as high as the box.
        return pick ?: arrange(1, max(0.0, min(minCard, box.h)), false, minW)
    }
    var chosen = choose(cfg.cardMinWidth)
    if (chosen.onFirst < want && cfg.cardMinWidth > 0) {
        val own = choose(0.0)
        if (own.onFirst > chosen.onFirst) chosen = own
    }
    val rows = chosen.rows
    val heights = chosen.heights

    val blockH = heights.fold(0.0) { s, h -> s + h } + (rows.size - 1) * gap
    val scroll = blockH > box.h + 0.5
    var y = if (scroll) box.y else box.y + (box.h - blockH) / 2
    val rects = arrayOfNulls<Rect>(n)
    rows.forEachIndexed { r, row ->
        val h = heights[r]
        val widths = widenRow(row.map { aspects[it] }, h, box.w, gap, chosen.minW, cfg.widenBarsMax).map { min(it, box.w) }
        var x = box.x + (box.w - (widths.sum() + (row.size - 1) * gap)) / 2
        row.forEachIndexed { j, i ->
            rects[i] = Rect(x, y, widths[j], h)
            x += widths[j] + gap
        }
        y += h + gap
    }

    val first = rects[0]!!
    return GridResult(
        ps.mapIndexed { i, p -> gridTile(p, rects[i]!!, aspects[i], cfg) },
        GridInfo(
            cols = rows.maxOf { it.size },
            rows = rows.size,
            card = Size(r2(first.w), r2(first.h)),
            scroll = scroll,
            visibleCount = if (scroll) chosen.onFirst else n,
            contentHeight = r2(blockH),
            adaptive = true,
        ),
    )
}

// The picture a grid shows: its card area less what fill crops away.
private fun shownArea(g: GridResult): Double =
    g.tiles.fold(0.0) { s, t -> s + t.rect.w * t.rect.h * (if (t.contentMode != null) 1 - t.mismatch else 1.0) }

// No row has more than one card more than another (decision 35).
private fun evenRows(g: GridResult): Boolean {
    val counts = g.tiles.groupingBy { it.rect.y }.eachCount().values
    return counts.max() - counts.min() <= 1
}

// The tallest card over the shortest.
private fun heightRatio(g: GridResult): Double {
    val hs = g.tiles.map { it.rect.h }
    return hs.max() / hs.min()
}

// The cap c at which rows of natural heights `spans`, each grown to min(span, c), add up to `room`.
private fun stretchCap(spans: List<Double>, room: Double): Double {
    val sorted = spans.sorted()
    var below = 0.0
    for (j in sorted.indices) {
        val c = (room - below) / (sorted.size - j)
        if (c <= sorted[j]) return c
        below += sorted[j]
    }
    return sorted[sorted.size - 1]
}

// Book pose: no card across the fold, and the halves mirror each other around it. Both are as wide
// as the narrower side allows, so a status bar or cutout on one side doesn't make them differ. The
// first ceil(n/2) people go left, the rest right. Without video the right side reuses the left
// side's grid and card size, so an odd count leaves its last slot empty; with video each side
// follows its own video shapes in the same width. Returns null when a half would not fit one
// screen; the caller then splits the people page by page (decision 28).
private fun bookSplitGrid(ps: List<Participant>, content: Rect, fold: Rect, opts: GridOptions, cfg: LayoutConfig): GridResult? {
    val w = min(fold.x - cfg.gap - content.x, content.x + content.w - (fold.x + fold.w + cfg.gap))
    if (w <= 0) return null
    val left = Rect(fold.x - cfg.gap - w, content.y, w, content.h)
    val right = Rect(fold.x + fold.w + cfg.gap, content.y, w, content.h)
    val nLeft = ceil(ps.size / 2.0).toInt()
    val a = layoutGrid(ps.take(nLeft), left, opts, cfg)
    var b = layoutGrid(ps.drop(nLeft), right, opts, cfg)
    if (a.grid.scroll || b.grid.scroll) return null
    if (!a.grid.adaptive && !b.grid.adaptive) {
        val dx = right.x - left.x
        b = GridResult(
            ps.drop(nLeft).mapIndexed { i, p -> videoTile(p, TileRole.GRID, a.tiles[i].rect.copy(x = a.tiles[i].rect.x + dx), 0, cfg) },
            a.grid,
        )
    }
    return GridResult(
        a.tiles + b.tiles,
        GridInfo(
            cols = a.grid.cols + b.grid.cols,
            rows = max(a.grid.rows, b.grid.rows),
            card = a.grid.card,
            scroll = false,
            visibleCount = ps.size,
            contentHeight = max(a.grid.contentHeight, b.grid.contentHeight),
            adaptive = a.grid.adaptive || b.grid.adaptive,
        ),
    )
}

// Where a dragged floating window lands: the corner of `area` on the side of its centre.
fun snapPipCorner(center: Point, area: Rect): PipCorner {
    val bottom = center.y > area.y + area.h / 2
    val right = center.x > area.x + area.w / 2
    return when {
        bottom && right -> PipCorner.BR
        bottom -> PipCorner.BL
        right -> PipCorner.TR
        else -> PipCorner.TL
    }
}

private val PipCorner.bottom get() = this == PipCorner.BL || this == PipCorner.BR
private val PipCorner.right get() = this == PipCorner.TR || this == PipCorner.BR

// The main view takes `media`, its video always whole; your video floats in a corner of `area`,
// shaped like your camera and always the same size. The main video keeps clear of the window when its
// letterbox leaves room (clearOfPip); otherwise the window floats over it.
// 1on1: who takes the main view and who the floating window. A swap holds only while both videos
// show: otherwise one of you would have no card at all, or your avatar would take the main view
// (decision 33).
private class OneOnOneRoles(val main: Participant, val second: Participant?)

private fun oneOnOneRoles(input: LayoutInput): OneOnOneRoles {
    val ps = input.participants
    val local = ps.firstOrNull { it.isLocal }
    val remote = ps.first { !it.isLocal }
    val mainIsLocal = input.swapped && local != null && local.videoOn && remote.videoOn
    return if (mainIsLocal) OneOnOneRoles(local, remote) else OneOnOneRoles(remote, local)
}

// The floating window in `area`: the area pipLong × pipShort in its video's shape, clamped like a
// card (decision 18), keeping its margin from the area's edges, so a short area shrinks it (decision 28),
// but never narrower than cardMinWidth (decision 41).
private fun pipSizeIn(videoAspect: Double?, area: Rect, cfg: LayoutConfig): Size {
    val w = sqrt(cfg.pipLong * cfg.pipShort * pipAspect(videoAspect, cfg))
    val m = cfg.pipMargin
    val inner = Rect(area.x + m, area.y + m, max(0.0, area.w - 2 * m), max(0.0, area.h - 2 * m))
    val size = fitSize(Size(w, (cfg.pipLong * cfg.pipShort) / w), inner)
    return Size(max(size.w, min(cfg.cardMinWidth, inner.w)), size.h)
}

private fun pipAspect(videoAspect: Double?, cfg: LayoutConfig): Double =
    min(cfg.adaptiveAspectMax, max(cfg.adaptiveAspectMin, videoAspect ?: (9.0 / 16)))

// A floating window widened past its video's shape shows the video whole, with bars (decision 41).
private fun pipTile(p: Participant, rect: Rect, cfg: LayoutConfig): Tile {
    val tile = videoTile(p, TileRole.PIP, rect, 1, cfg)
    val widened = tile.contentMode != null && rect.h > 0 && rect.w / rect.h > pipAspect(p.videoAspect, cfg) * 1.0001
    return if (widened) tile.copy(contentMode = ContentMode.FIT) else tile
}

private class PlacedBelowFold(val controls: Rect, val dock: Rect, val pipArea: Rect?, val place: Rect?, val pip: Rect?)

// Tabletop with the app's controls (`size`) placed by the core (decision 39). They sit at the window
// bottom, centred, and the dock is what is left above them. A 1on1's floating window keeps the size
// it has with the device open flat (decision 18, shrinking only where it does not fit) and sits at
// the top of the space below the fold: centred above them when it comes out at least as large there,
// else beside them, the window at its usual margin from the edge on its corner's side (right by
// default), as it sits open flat, and the controls next to it, a gap away, so the two read as one
// group. Dragged above the fold (a top corner), the window floats in that corner of the upper half
// over the main view, as open flat, and the controls stay centred. `pipArea`, the drag area, is the
// content area cut so that its middle is the middle of the fold: a drop snaps to a top corner above
// the fold and to a bottom one below it. `place` is the area the window is placed in, `pip` the
// window itself when the core places it below the fold.
private fun placeBelowFold(input: LayoutInput, fold: Rect, size: Size, cfg: LayoutConfig): PlacedBelowFold {
    val c = input.content
    val top = fold.y + fold.h + cfg.gap
    val bottom = input.window.h - (input.safe?.bottom ?: 0.0)
    val w = min(size.w, c.w)
    fun centred(x: Double) = Rect(x, bottom - size.h, w, size.h)
    var controls = centred(c.x + (c.w - w) / 2)
    fun dockOf(k: Rect) = Rect(c.x, top, c.w, max(0.0, k.y - cfg.gap - top))
    // A share draws no floating window, so the controls stay centred.
    val second = if (input.share == null && isOneOnOne(input.callType, input.participants.size) && input.participants.any { !it.isLocal }) oneOnOneRoles(input).second else null
    if (second == null || !second.videoOn) return PlacedBelowFold(controls, dockOf(controls), null, null, null)
    val m = cfg.pipMargin
    val corner = input.pipCorner ?: PipCorner.BR
    val mid = fold.y + fold.h / 2
    val dragArea = Rect(c.x, c.y, c.w, max(0.0, 2 * (mid - c.y)))
    if (corner == PipCorner.TL || corner == PipCorner.TR) {
        val upperArea = Rect(c.x, c.y, c.w, max(0.0, fold.y - cfg.gap - c.y))
        return PlacedBelowFold(controls, dockOf(controls), dragArea, upperArea, null)
    }
    val above = pipSizeIn(second.videoAspect, dockOf(controls), cfg)
    val beside = pipSizeIn(second.videoAspect, Rect(0.0, 0.0, max(0.0, c.w - w - cfg.gap), max(0.0, bottom - top)), cfg)
    val pip: Rect
    // Heights, not widths: a window kept 100 wide (decision 41) is only as big as its height.
    if (beside.h > above.h + 1e-6) {
        val onLeft = corner == PipCorner.BL
        pip = Rect(if (onLeft) c.x + m else c.x + c.w - m - beside.w, top + m, beside.w, beside.h)
        controls = centred(if (onLeft) pip.x + pip.w + cfg.gap else pip.x - cfg.gap - w)
    } else {
        pip = Rect(c.x + (c.w - above.w) / 2, top + m, above.w, above.h)
    }
    return PlacedBelowFold(controls, dockOf(controls), dragArea, Rect(pip.x - m, pip.y - m, pip.w + 2 * m, pip.h + 2 * m), pip)
}

private fun layoutOneOnOne(input: LayoutInput, media: Rect, area: Rect, cfg: LayoutConfig): List<Tile> {
    val roles = oneOnOneRoles(input)
    val main = roles.main
    val second = roles.second
    val corner = input.pipCorner ?: PipCorner.BR
    // The main video is always shown whole (decision 4); its tile is the area the video takes.
    var mainTile = videoTile(main, TileRole.MAIN, media, 0, cfg)
    val whole = if (hasVideo(main)) fitRect(main.videoAspect!!, media) else null
    if (whole != null) mainTile = mainTile.copy(rect = whole, contentMode = ContentMode.FIT)
    if (second == null || !second.videoOn) return listOf(mainTile)
    var size = pipSizeIn(second.videoAspect, area, cfg)
    if (whole != null) {
        val clear = clearOfPip(whole, media, area, corner, size, cfg)
        if (clear != null) {
            mainTile = mainTile.copy(rect = clear.first)
            size = clear.second
        }
    }
    return listOf(mainTile, pipTile(second, atCorner(size, area, corner, cfg.pipMargin), cfg))
}

// The margin gives way when the area is too small for it, so the window never leaves the area.
private fun atCorner(size: Size, area: Rect, corner: PipCorner, margin: Double): Rect {
    val mx = max(0.0, min(margin, (area.w - size.w) / 2))
    val my = max(0.0, min(margin, (area.h - size.h) / 2))
    return Rect(
        x = if (corner.right) area.x + area.w - mx - size.w else area.x + mx,
        y = if (corner.bottom) area.y + area.h - my - size.h else area.y + my,
        w = size.w,
        h = size.h,
    )
}

private fun overlaps(a: Rect, b: Rect) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

// Moves a letterboxed main video away from the window's corner, along the axis it has room on,
// no further than `area` (or where it already is). If that is not enough the window shrinks, down
// to pipMinScale. null when the two cannot be kept apart.
private fun clearOfPip(video: Rect, media: Rect, area: Rect, corner: PipCorner, size: Size, cfg: LayoutConfig): Pair<Rect, Size>? {
    val m = cfg.pipMargin
    if (!overlaps(video, atCorner(size, area, corner, m))) return video to size
    val vertical = video.h < media.h - 0.5
    if (!vertical && video.w >= media.w - 0.5) return null
    // Work along one axis: position and length of the video, the window and the area.
    val pos = if (vertical) video.y else video.x
    val len = if (vertical) video.h else video.w
    val winLen = if (vertical) size.h else size.w
    val start = if (vertical) area.y else area.x
    val end = if (vertical) area.y + area.h else area.x + area.w
    val mStart = if (vertical) media.y else media.x
    val mEnd = if (vertical) media.y + media.h else media.x + media.w
    val far = if (vertical) corner.bottom else corner.right
    // The window sits at the far end: the video moves towards the start, and the reverse.
    val limit = if (far) min(pos, max(mStart, start)) else max(pos, min(mEnd, end) - len)
    val room = if (far) end - m - (limit + len + m) else limit - m - (start + m)
    val k = min(1.0, room / winLen)
    if (k < cfg.pipMinScale) return null
    val fitted = Size(size.w * k, size.h * k)
    val want = if (far) end - m - winLen * k - m - len else start + m + winLen * k + m
    val next = if (far) min(pos, want) else max(pos, want)
    return (if (vertical) video.copy(y = next) else video.copy(x = next)) to fitted
}

// A group of two (decision 29): two halves of the same size, you first as in every grid (the other
// person when swapped, decision 30). A card with video takes its video's shape in its half (9:16 to 16:9); an avatar card
// takes the half, its shape kept within the card range (3:4 to 16:9). The area is split across
// and along; the split whose smaller card is larger wins, a tie goes across on an upright area and
// along on a wide one. The two cards are centred together.
private class DuoSplit(val stacked: Boolean, val sizes: List<Size>, val smaller: Double)

private fun layoutDuo(ps: List<Participant>, area: Rect, swapped: Boolean, cfg: LayoutConfig): List<Tile> {
    val youAt = ps.indexOfFirst { it.isLocal }.let { if (it < 0) 0 else it }
    val you = ps[youAt]
    val other = ps[ps.indices.first { it != youAt }]
    val order = if (swapped) listOf(other, you) else listOf(you, other)
    val g = cfg.gap
    fun split(stacked: Boolean): DuoSplit {
        val half = if (stacked) Rect(0.0, 0.0, area.w, max(0.0, (area.h - g) / 2)) else Rect(0.0, 0.0, max(0.0, (area.w - g) / 2), area.h)
        val sizes = order.map { p ->
            val a = if (hasVideo(p)) {
                min(cfg.adaptiveAspectMax, max(cfg.adaptiveAspectMin, p.videoAspect!!))
            } else {
                min(cfg.cardAspectMax, max(cfg.cardAspectMin, half.w / half.h))
            }
            val r = fitRect(a, half)
            Size(r.w, r.h)
        }
        return DuoSplit(stacked, sizes, sizes.minOf { it.w * it.h })
    }
    val across = split(true)
    val along = split(false)
    val upright = area.h >= area.w
    val pick = when {
        across.smaller > along.smaller * 1.0001 -> across
        along.smaller > across.smaller * 1.0001 -> along
        upright -> across
        else -> along
    }
    val stacked = pick.stacked
    val sizes = pick.sizes
    var at = if (stacked) area.y + (area.h - (sizes[0].h + g + sizes[1].h)) / 2 else area.x + (area.w - (sizes[0].w + g + sizes[1].w)) / 2
    return order.mapIndexed { i, p ->
        val s = sizes[i]
        val rect = if (stacked) Rect(area.x + (area.w - s.w) / 2, at, s.w, s.h) else Rect(at, area.y + (area.h - s.h) / 2, s.w, s.h)
        at += (if (stacked) s.h else s.w) + g
        videoTile(p, TileRole.GRID, rect, 0, cfg)
    }
}

private fun dockBelow(fold: Rect, content: Rect, cfg: LayoutConfig): Rect {
    val top = fold.y + fold.h + cfg.gap
    return Rect(content.x, top, content.w, max(0.0, content.y + content.h - top))
}

// Shrinks a pip size to fit inside `box`, keeping its aspect.
private fun fitSize(size: Size, box: Rect): Size {
    val k = minOf(1.0, box.w / size.w, box.h / size.h)
    return Size(size.w * k, size.h * k)
}

// The height at which cards of aspects[from until to] span `width` with gaps between them, none
// narrower than `minW` (decision 36): the cards that would be narrower take minW and the rest share
// what is left, which lowers the height, so this repeats until no more cards drop to minW. 0 when
// minW alone overfills the width.
private fun spanHeight(aspects: DoubleArray, from: Int, to: Int, width: Double, gap: Double, minW: Double): Double {
    val room = width - (to - from - 1) * gap
    var sum = 0.0
    for (i in from until to) sum += aspects[i]
    var h = room / sum
    while (true) {
        var narrow = 0
        var rest = 0.0
        for (i in from until to) {
            if (aspects[i] * h < minW) narrow++ else rest += aspects[i]
        }
        val left = room - narrow * minW
        if (rest == 0.0 || !(left > 0)) return 0.0
        val next = left / rest
        if (next >= h - 1e-9) return h
        h = next
    }
}

// The card widths of a row of height h: each card its video's width, or minW when that is narrower
// (decision 36); a row narrower than the box then widens its cards alike, the video whole in each,
// until it spans the box or a card's bars would take barsMax of it (decision 37).
private fun widenRow(aspects: List<Double>, h: Double, width: Double, gap: Double, minW: Double, barsMax: Double): List<Double> {
    val widths = aspects.map { max(it * h, minW) }.toMutableList()
    val room = width - (aspects.size - 1) * gap
    val caps = aspects.indices.map { i -> max(widths[i], (aspects[i] * h) / (1 - barsMax)) }
    var free = aspects.indices.toList()
    var pass = 0
    while (pass < aspects.size && free.isNotEmpty()) {
        pass++
        val fixed = widths.indices.fold(0.0) { s, i -> s + (if (i in free) 0.0 else widths[i]) }
        val flex = free.fold(0.0) { s, i -> s + widths[i] }
        if (!(flex > 0)) break
        val scale = (room - fixed) / flex
        if (!(scale > 1 + 1e-9)) break
        val over = free.filter { widths[it] * scale > caps[it] }
        if (over.isEmpty()) {
            for (i in free) widths[i] *= scale
            break
        }
        for (i in over) widths[i] = caps[i]
        free = free.filter { it !in over }
    }
    return widths
}

// A card in rows; one widened past its video shows the video whole (decisions 36, 37).
private fun gridTile(p: Participant, rect: Rect, aspect: Double, cfg: LayoutConfig): Tile {
    val t = videoTile(p, TileRole.GRID, rect, 0, cfg)
    return if (t.contentMode != null && rect.w > rect.h * aspect + 0.01) t.copy(contentMode = ContentMode.FIT) else t
}

// Grid cards and large views (1on1, solo, pip) have their own fill thresholds.
private fun videoTile(p: Participant, role: TileRole, rect: Rect, z: Int, cfg: LayoutConfig): Tile {
    val dst = if (rect.h > 0) rect.w / rect.h else 0.0
    if (!hasVideo(p)) return Tile(p.id, role, rect, null, 0.0, z)
    val src = p.videoAspect!!
    return Tile(
        id = p.id,
        role = role,
        rect = rect,
        contentMode = contentModeFor(src, dst, if (role == TileRole.GRID) cfg.fillThreshold else cfg.largeFillThreshold),
        mismatch = mismatch(src, dst),
        z = z,
    )
}

// Tabletop: everything people watch goes above the fold, the half lying flat is for controls.
private fun upperOf(input: LayoutInput): Rect? {
    val fold = input.fold
    if (fold == null || postureOf(fold) != Posture.TABLETOP) return null
    val safe = input.safe ?: Insets.ZERO
    return Rect(safe.left, safe.top, input.window.w - safe.left - safe.right, fold.y - safe.top)
}

// Book posture with the fold well off the middle, as on a trifold standing on one folded-back
// third: the larger side faces the user and takes the whole call, the smaller side is a stand.
// Folds near the middle split the call between the halves instead (decision 14).
private const val OFF_CENTRE = 1.25

fun facingSide(win: Size, fold: Rect?): Rect? {
    if (fold == null || postureOf(fold) != Posture.BOOK) return null
    val left = fold.x
    val right = win.w - fold.x - fold.w
    if (max(left, right) < min(left, right) * OFF_CENTRE) return null
    return if (left > right) Rect(0.0, 0.0, left, win.h) else Rect(fold.x + fold.w, 0.0, right, win.h)
}

// The content area on the facing side, a gap clear of the fold.
private fun facingStage(side: Rect, fold: Rect, content: Rect, cfg: LayoutConfig): Rect {
    val right = side.x > fold.x
    val x0 = if (right) max(content.x, fold.x + fold.w + cfg.gap) else content.x
    val x1 = if (right) content.x + content.w else min(content.x + content.w, fold.x - cfg.gap)
    return Rect(x0, content.y, max(0.0, x1 - x0), content.h)
}

// Where a group's cards go: the content area, cut at the fold on a tabletop and narrowed to the
// facing side when a book fold is off centre. A grid that does not fit turns pages inside it.
fun groupStage(win: Size, content: Rect, fold: Rect?, cfg: LayoutConfig = LayoutConfig.DEFAULT): Rect {
    if (fold != null && postureOf(fold) == Posture.TABLETOP) return Rect(content.x, content.y, content.w, fold.y - cfg.gap - content.y)
    val facing = facingSide(win, fold)
    return if (facing != null && fold != null) facingStage(facing, fold, content, cfg) else content
}

// What the 1on1 main view is fitted into: above the fold on a tabletop, the left half in book
// posture (the facing side when the fold is off centre), else the whole window.
fun oneOnOneMedia(input: LayoutInput): Rect {
    val win = fullWindow(input)
    val fold = input.fold
    upperOf(input)?.let { return it }
    facingSide(input.window, fold)?.let { return it }
    return if (fold != null && postureOf(fold) == Posture.BOOK) Rect(win.x, win.y, fold.x - win.x, win.h) else win
}

private fun notNegative(r: Rect): Rect = if (r.w >= 0 && r.h >= 0) r else r.copy(w = max(0.0, r.w), h = max(0.0, r.h))

private fun fullWindow(input: LayoutInput) = Rect(0.0, 0.0, input.window.w, input.window.h)

// Rects are made drawable here, so input no layout expects (a window or content area next to
// nothing) never reaches the app as NaN or a negative size.
private fun finish(
    base: Base,
    requestOrientation: RequestOrientation,
    mode: LayoutMode,
    tiles: List<Tile>,
    grid: GridInfo?,
    dock: Rect?,
    pipArea: Rect? = null,
): LayoutPlan = LayoutPlan(
    windowClass = base.windowClass,
    orientation = base.orientation,
    requestOrientation = requestOrientation,
    mode = mode,
    posture = base.posture,
    tiles = tiles.map { it.copy(rect = roundRect(saneRect(it.rect)), mismatch = r2(it.mismatch)) },
    grid = grid,
    dock = dock?.let { roundRect(saneRect(it)) },
    pipArea = pipArea?.let { roundRect(saneRect(it)) },
    controls = base.controls,
)
