


package com.difft.android.call.layout

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt




fun classifyWindow(win: Size, cfg: LayoutConfig = LayoutConfig.DEFAULT): WindowClass =
    if (win.w >= cfg.wideMinWidth && win.h >= cfg.wideMinHeight) WindowClass.WIDE else WindowClass.COMPACT

fun orientationOf(win: Size): Orientation = if (win.w > win.h) Orientation.LANDSCAPE else Orientation.PORTRAIT

fun postureOf(fold: Rect?): Posture {
    if (fold == null) return Posture.FLAT
    return if (fold.w >= fold.h) Posture.TABLETOP else Posture.BOOK
}


private fun hasAspect(a: Double?): Boolean = a != null && a != 0.0 && !a.isNaN()

private fun hasVideo(p: Participant): Boolean = p.videoOn && hasAspect(p.videoAspect)

private class Base(val windowClass: WindowClass, val orientation: Orientation, val posture: Posture, val controls: Rect? = null)

private class GridResult(val tiles: List<Tile>, val grid: GridInfo)


private class Laid(val r: GridResult, val box: Rect)





fun isOneOnOne(callType: CallType, count: Int): Boolean = callType == CallType.ONE_ON_ONE && count <= 2

fun crossingFold(win: Size, fold: Rect?): Rect? {
    if (fold == null) return null
    if (fold.w >= fold.h) return if (fold.y > 0 && fold.y + fold.h < win.h) fold else null
    return if (fold.x > 0 && fold.x + fold.w < win.w) fold else null
}

fun resolveLayout(raw: LayoutInput, cfg: LayoutConfig = LayoutConfig.DEFAULT): LayoutPlan {
    
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

    
    
    
    
    if (isOneOnOne(input.callType, ps.size) && ps.any { !it.isLocal }) {
        val halves = if (fold != null && posture == Posture.BOOK && facingContent == null) bookOneOnOne(input, c, fold, cfg) else null
        if (halves != null) return finish(base, request, LayoutMode.DUO, halves, null, dock)
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
    
    
    
    if (ps.size == 2 && (posture != Posture.BOOK || facingContent != null)) {
        return finish(base, request, LayoutMode.DUO, layoutDuo(ps, stage, input.swapped, cfg), null, dock)
    }
    
    
    
    val more = input.overflow == Overflow.MORE
    val folded = more && ps.size > cfg.moreMaxTiles
    val cells = if (folded) ps.take(cfg.moreMaxTiles - 1) + MORE_CELL else ps
    val gcfg = if (more) cfg.copy(adaptiveMaxCount = cfg.moreMaxTiles, cardMinWidth = 0.0) else cfg
    
    val opts = GridOptions(
        minCard = if (more) 0.0 else if (fold != null) cfg.minCardHalf else if (windowClass == WindowClass.WIDE) cfg.minCardWide else cfg.minCardCompact,
        compact = windowClass == WindowClass.COMPACT,
        scrollRows = !more,
    )
    
    
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
        
        
        val alone = count <= mine
        val members = if (alone) rest.take(1) else list.take(count)
        var cut = if (alone) null else firstScreen(laid, count)
        
        if (alone || count < fit || members.any { hasVideo(it) }) {
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


private val MORE_CELL = Participant(id = "__overflow", isLocal = false, videoOn = false)

private fun withMoreCell(r: GridResult, hidden: Int): GridResult = GridResult(
    tiles = r.tiles.map { t -> if (t.id == MORE_CELL.id) t.copy(role = TileRole.OVERFLOW, overflowCount = hidden) else t },
    grid = r.grid.copy(visibleCount = r.grid.visibleCount - 1),
)







private data class GridOptions(
    val minCard: Double,
    val compact: Boolean,
    val scrollRows: Boolean,
    val whole: Boolean = false,
    val maxArea: Double = Double.POSITIVE_INFINITY,
)

data class GridSolution(val cols: Int, val rows: Int, val card: Size, val fits: Boolean)



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



private fun layoutGrid(ps: List<Participant>, box: Rect, opts: GridOptions, cfg: LayoutConfig): GridResult {
    val n = ps.size
    val minCard = opts.minCard
    val whole = solveGrid(n, sizeOf(box), minCard, cfg)
    val video = cfg.adaptiveCards && ps.any { hasVideo(it) }
    
    val avatarAspect = if (whole.card.h > 0) whole.card.w / whole.card.h else 1.0
    
    val floor = min(minCard, cfg.minCardFloor)
    fun balanced(least: Double): GridResult? =
        if (video && n <= cfg.adaptiveMaxCount) layoutBalancedRows(ps, box, avatarAspect, least, cfg, opts.maxArea) else null
    val rows = balanced(if (opts.whole) floor else minCard)
    if (rows != null) return rows
    
    val want = if (!opts.scrollRows) 0 else if (opts.whole) n else min(n, cfg.firstScreenMin)
    val uniform = uniformGrid(ps, box, minCard, whole, want, cfg, opts.maxArea)
    if (!video || !opts.scrollRows) return uniform
    val target = max(wholeOnFirstScreen(uniform.grid, box, n, cfg), want)
    
    val scrolled = layoutScrollRows(ps, box, if (opts.compact) 1.0 else avatarAspect, minCard, target, want, cfg)
    
    if (!opts.whole && floor < minCard && (!scrolled.grid.scroll || scrolled.grid.visibleCount >= n)) {
        val low = balanced(floor)
        if (low != null) return low
    }
    return scrolled
}


private fun wholeOnFirstScreen(g: GridInfo, box: Rect, n: Int, cfg: LayoutConfig): Int {
    if (!g.scroll) return n
    return min(n.toDouble(), floor((box.h + cfg.gap + 0.5) / (g.card.h + cfg.gap)) * g.cols).toInt()
}




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



private fun uniformCards(n: Int, box: Rect, minCard: Double, whole: GridSolution, cfg: LayoutConfig): UniformCards {
    if (whole.fits) return UniformCards(whole.cols, whole.card, scroll = false, visibleCount = n)
    for (m in n - 1 downTo 1) {
        val s = solveGrid(m, sizeOf(box), minCard, cfg)
        if (s.fits) return UniformCards(s.cols, s.card, scroll = true, visibleCount = min(n, ceil(m.toDouble() / s.cols).toInt() * s.cols))
    }
    return UniformCards(1, solveGrid(1, sizeOf(box), minCard, cfg).card, scroll = true, visibleCount = 1)
}





private fun uniformGrid(
    ps: List<Participant>,
    box: Rect,
    minCard: Double,
    whole: GridSolution,
    want: Int,
    cfg: LayoutConfig,
    maxArea: Double = Double.POSITIVE_INFINITY,
): GridResult {
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
    val (cols, fitted, scroll, visibleCount) = u
    val card = if (fitted.w * fitted.h > maxArea) {
        val f = sqrt(maxArea / (fitted.w * fitted.h))
        Size(fitted.w * f, fitted.h * f)
    } else {
        fitted
    }

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














private val RATIO_STEPS = doubleArrayOf(1.0, 1.5, 2.25, 1e6)


private const val BISECT = 22


private class BalancedPlan(val score: Double, val lens: IntArray, val h: DoubleArray, val w: Array<DoubleArray>, val spread: Int, val rises: Int)

private fun layoutBalancedRows(
    ps: List<Participant>,
    box: Rect,
    avatarAspect: Double,
    minCard: Double,
    cfg: LayoutConfig,
    maxArea: Double = Double.POSITIVE_INFINITY,
): GridResult? {
    fun shape(p: Participant): Double =
        if (hasVideo(p)) min(cfg.adaptiveAspectMax, max(cfg.adaptiveAspectMin, p.videoAspect!!)) else avatarAspect
    fun search(order: List<Participant>, ratio: Double): BalancedPlan? = searchBalancedRows(
        DoubleArray(order.size) { shape(order[it]) },
        BooleanArray(order.size) { hasVideo(order[it]) },
        box, minCard, if (ps.size == 1) cfg.cardAreaRatioMax * RATIO_STEPS.last() else ratio, maxArea, cfg,
    )
    val grouped = byShape(ps)
    var order = ps
    var plan: BalancedPlan? = null
    for (step in RATIO_STEPS) {
        val ratio = cfg.cardAreaRatioMax * step
        val inOrder = search(ps, ratio)
        val regrouped = if (grouped != null) search(grouped, ratio) else null
        if (regrouped != null && (inOrder == null || regrouped.score > inOrder.score * (1 + cfg.regroupGain))) {
            order = grouped!!
            plan = regrouped
        } else {
            plan = inOrder
        }
        if (plan != null) break
    }
    val chosen = plan ?: return null
    val gap = cfg.gap
    val rowCount = chosen.lens.size
    val blockH = chosen.h.fold(0.0) { s, h -> s + h } + (rowCount - 1) * gap
    var y = box.y + (box.h - blockH) / 2
    val rects = HashMap<String, Rect>()
    var at = 0
    for (r in 0 until rowCount) {
        val len = chosen.lens[r]
        val widths = chosen.w[r]
        var x = box.x + (box.w - (widths.fold(0.0) { s, w -> s + w } + (len - 1) * gap)) / 2
        for (j in 0 until len) {
            rects[order[at + j].id] = Rect(x, y, widths[j], chosen.h[r])
            x += widths[j] + gap
        }
        at += len
        y += chosen.h[r] + gap
    }
    val first = rects.getValue(ps[0].id)
    return GridResult(
        ps.map { p -> gridTile(p, rects.getValue(p.id), shape(p), cfg) },
        GridInfo(
            cols = chosen.lens.max(),
            rows = rowCount,
            card = Size(r2(first.w), r2(first.h)),
            scroll = false,
            visibleCount = ps.size,
            contentHeight = r2(blockH),
            adaptive = true,
        ),
    )
}



private fun byShape(ps: List<Participant>): List<Participant>? {
    val more = if (ps.isNotEmpty() && ps.last().id == MORE_CELL.id) ps.last() else null
    val people = if (more != null) ps.dropLast(1) else ps
    fun kind(p: Participant): Int {
        if (!hasVideo(p)) return 3
        val a = p.videoAspect!!
        return if (a < 0.9) 0 else if (a > 1.1) 1 else 2
    }
    val kinds = mutableListOf<Int>()
    for (p in people) if (kind(p) !in kinds) kinds.add(kind(p))
    val grouped = kinds.flatMap { k -> people.filter { kind(it) == k } }.toMutableList()
    if (more != null) grouped.add(more)
    return if (grouped.indices.all { grouped[it] === ps[it] }) null else grouped
}




private fun searchBalancedRows(
    aspects: DoubleArray,
    video: BooleanArray,
    box: Rect,
    minCard: Double,
    ratio: Double,
    maxArea: Double,
    cfg: LayoutConfig,
): BalancedPlan? {
    val n = aspects.size
    val gap = cfg.gap
    val W = box.w
    val minW = cfg.cardMinWidth
    
    
    val lo = DoubleArray(n) { i -> if (video[i]) max(cfg.adaptiveAspectMin, aspects[i] * (1 - cfg.cropMax)) else cfg.cardAspectMin }
    val hi = DoubleArray(n) { i -> if (video[i]) aspects[i] / (1 - cfg.widenBarsMax) else cfg.cardAspectMax }
    var best: BalancedPlan? = null
    val lensBuf = IntArray(max(n, 1))
    for (mask in 0 until (1 shl (n - 1))) {
        val R = splitRows(mask, n, lensBuf)
        if (R == 0) continue
        val roomH = box.h - (R - 1) * gap
        if (!(roomH > 0)) continue
        val lens = lensBuf.copyOf(R)
        val start = IntArray(R)
        run {
            var at = 0
            for (r in 0 until R) {
                start[r] = at
                at += lens[r]
            }
        }
        val loMax = DoubleArray(R) { r ->
            var m = lo[start[r]]
            for (i in start[r] + 1 until start[r] + lens[r]) m = max(m, lo[i])
            m
        }
        val hiMin = DoubleArray(R) { r ->
            var m = hi[start[r]]
            for (i in start[r] + 1 until start[r] + lens[r]) m = min(m, hi[i])
            m
        }
        
        var apart = false
        for (r in 0 until R) if (loMax[r] > ratio * hiMin[r] * (1 + 1e-9)) apart = true
        if (apart) continue
        fun cap(A: Double) = min(ratio * A, maxArea)
        fun widthAt(r: Int, h: Double, A: Double): Double {
            var s = 0.0
            for (i in start[r] until start[r] + lens[r]) s += max(max(lo[i] * h, A / h), minW)
            return s + (lens[r] - 1) * gap
        }
        fun hMin(r: Int, A: Double) = max(minCard, sqrt(A / hiMin[r]))
        
        
        fun hMax(r: Int, A: Double) = min(sqrt(cap(A) / loMax[r]), if (minW > 0) cap(A) / minW else Double.POSITIVE_INFINITY)
        fun fits(A: Double): Boolean {
            if (A > maxArea) return false
            var sum = 0.0
            for (r in 0 until R) {
                val h = hMin(r, A)
                if (h > hMax(r, A) * (1 + 1e-9) || widthAt(r, h, A) > W + 1e-6) return false
                sum += h
            }
            return sum <= roomH + 1e-6
        }
        
        
        var aLo = 0.0
        for (r in 0 until R) aLo = max(aLo, (minCard * minCard * loMax[r]) / ratio)
        
        
        if (minW > 0) {
            for (r in 0 until R) aLo = max(max(aLo, (minW * minCard) / ratio), (minW * minW) / (ratio * ratio * hiMin[r]))
        }
        var inv = 0.0
        for (v in hiMin) inv += 1 / sqrt(v)
        
        val side = roomH / inv
        var aHi = min(maxArea, side * side)
        if (!(aHi >= aLo)) continue
        
        val b0 = best
        if (b0 != null && cap(aHi) < b0.score * 0.995) continue
        if (!fits(aLo)) continue
        repeat(BISECT) {
            val mid = (aLo + aHi) / 2
            if (fits(mid)) aLo = mid else aHi = mid
        }
        val A = aLo
        
        val base = DoubleArray(R) { r -> hMin(r, A) }
        val caps = DoubleArray(R) { r ->
            var low = base[r]
            var high = hMax(r, A)
            if (widthAt(r, high, A) <= W) {
                high
            } else {
                repeat(BISECT) {
                    val mid = (low + high) / 2
                    if (widthAt(r, mid, A) <= W) low = mid else high = mid
                }
                low
            }
        }
        fun heightAt(f: Double): Double {
            var s = 0.0
            for (r in 0 until R) s += min(caps[r], base[r] * f)
            return s
        }
        var fLo = 1.0
        while (fLo < 64 && heightAt(fLo * 2) <= roomH) fLo *= 2
        var fHi = fLo * 2
        repeat(BISECT) {
            val mid = (fLo + fHi) / 2
            if (heightAt(mid) <= roomH) fLo = mid else fHi = mid
        }
        val h = DoubleArray(R) { r -> min(caps[r], base[r] * fLo) }
        val w = Array(R) { r -> rowWidths(start[r], lens[r], h[r], A, cap(A), aspects, lo, hi, W, minW, gap) }
        val score = pictureScore(lens, start, h, w, aspects, video)
        
        
        val spread = lens.max() - lens.min()
        var rises = 0
        for (r in 1 until R) if (lens[r] > lens[r - 1]) rises++
        val b = best
        val tie = b != null && (R < b.lens.size || (R == b.lens.size && (spread < b.spread || (spread == b.spread && rises < b.rises))))
        if (b == null || score > b.score * 1.005 || (score >= b.score * 0.995 && tie)) {
            best = BalancedPlan(score, lens, h, w, spread, rises)
        }
    }
    return best
}






private fun splitRows(mask: Int, n: Int, lens: IntArray): Int {
    var R = 0
    var len = 0
    for (i in 0 until n) {
        len++
        if (i == n - 1 || (mask shr i) and 1 != 0) {
            lens[R++] = len
            len = 0
        }
    }
    if (n > 10) {
        if (R > 6) return 0
        for (r in 0 until R) if (abs(lens[r] - n.toDouble() / R) > 2) return 0
    }
    return R
}




private fun rowWidths(
    s0: Int,
    l: Int,
    hr: Double,
    A: Double,
    capA: Double,
    aspects: DoubleArray,
    lo: DoubleArray,
    hi: DoubleArray,
    W: Double,
    minW: Double,
    gap: Double,
): DoubleArray {
    val lower = DoubleArray(l) { j -> max(max(lo[s0 + j] * hr, A / hr), minW) }
    val upper = DoubleArray(l) { j -> max(lower[j], min(hi[s0 + j] * hr, capA / hr)) }
    val pref = DoubleArray(l) { j -> min(upper[j], max(lower[j], aspects[s0 + j] * hr)) }
    val room = W - (l - 1) * gap
    var total = 0.0
    for (v in pref) total += v
    return if (total > room) {
        var slack = 0.0
        for (j in 0 until l) slack += pref[j] - lower[j]
        val k = if (slack > 0) min(1.0, (total - room) / slack) else 0.0
        DoubleArray(l) { j -> pref[j] - (pref[j] - lower[j]) * k }
    } else {
        var slack = 0.0
        for (j in 0 until l) slack += upper[j] - pref[j]
        val k = if (slack > 0) min(1.0, (room - total) / slack) else 0.0
        DoubleArray(l) { j -> pref[j] + (upper[j] - pref[j]) * k }
    }
}


private fun pictureScore(
    lens: IntArray,
    start: IntArray,
    h: DoubleArray,
    w: Array<DoubleArray>,
    aspects: DoubleArray,
    video: BooleanArray,
): Double {
    var logs = 0.0
    for (r in lens.indices) {
        for (j in 0 until lens[r]) {
            val i = start[r] + j
            val c = w[r][j] / h[r]
            val shown = if (video[i]) min(c, aspects[i]) / max(c, aspects[i]) else 1.0
            logs += StrictMath.log(w[r][j] * h[r] * shown)
        }
    }
    return StrictMath.exp(logs / aspects.size)
}


private class Arrangement(val rows: List<List<Int>>, val heights: List<Double>, val onFirst: Int, val H: Double, val minW: Double)


















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








private fun bookSplitGrid(ps: List<Participant>, content: Rect, fold: Rect, opts: GridOptions, cfg: LayoutConfig): GridResult? {
    
    
    val wl = fold.x - cfg.gap - content.x
    val wr = content.x + content.w - (fold.x + fold.w + cfg.gap)
    if (!(wl > 0) || !(wr > 0)) return null
    val left = Rect(content.x, content.y, wl, content.h)
    val right = Rect(fold.x + fold.w + cfg.gap, content.y, wr, content.h)
    val same = abs(wl - wr) < 0.5
    val nLeft = if (wl >= wr - 0.5) ceil(ps.size / 2.0).toInt() else ps.size / 2
    var a = layoutGrid(ps.take(nLeft), left, opts, cfg)
    var b = layoutGrid(ps.drop(nLeft), right, opts, cfg)
    if (a.grid.scroll || b.grid.scroll) return null
    
    
    
    
    if ((a.grid.adaptive || b.grid.adaptive || !same) && ps.size > 2) {
        fun areas(g: GridResult) = g.tiles.map { it.rect.w * it.rect.h }
        fun least(g: GridResult) = areas(g).minOrNull() ?: Double.POSITIVE_INFINITY
        fun most(g: GridResult) = areas(g).maxOrNull() ?: Double.NEGATIVE_INFINITY
        val cap = cfg.cardAreaRatioMax * min(least(a), least(b))
        if (most(a) > cap * 1.001) {
            val again = layoutGrid(ps.take(nLeft), left, opts.copy(maxArea = cap), cfg)
            if (!again.grid.scroll) a = again
        } else if (most(b) > cap * 1.001) {
            val again = layoutGrid(ps.drop(nLeft), right, opts.copy(maxArea = cap), cfg)
            if (!again.grid.scroll) b = again
        }
    }
    if (!a.grid.adaptive && !b.grid.adaptive && same) {
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




private class OneOnOneRoles(val main: Participant, val second: Participant?)







private fun bookOneOnOne(input: LayoutInput, content: Rect, fold: Rect, cfg: LayoutConfig): List<Tile>? {
    val main = input.participants.firstOrNull { !it.isLocal } ?: return null
    val second = input.participants.firstOrNull { it.isLocal } ?: return null
    val g = cfg.gap
    val wl = fold.x - g - content.x
    val rx = fold.x + fold.w + g
    val wr = content.x + content.w - rx
    if (!(wl > 0) || !(wr > 0) || !(content.h > 0)) return null
    fun card(p: Participant, rect: Rect): Tile {
        val t = videoTile(p, TileRole.GRID, rect, 0, cfg)
        return if (t.contentMode != null) t.copy(contentMode = ContentMode.FIT) else t
    }
    return listOf(card(main, Rect(content.x, content.y, wl, content.h)), card(second, Rect(rx, content.y, wr, content.h)))
}

private fun oneOnOneRoles(input: LayoutInput): OneOnOneRoles {
    val ps = input.participants
    val local = ps.firstOrNull { it.isLocal }
    val remote = ps.first { !it.isLocal }
    val mainIsLocal = input.swapped && local != null && local.videoOn && remote.videoOn
    return if (mainIsLocal) OneOnOneRoles(local, remote) else OneOnOneRoles(remote, local)
}




private fun pipSizeIn(videoAspect: Double?, area: Rect, cfg: LayoutConfig): Size {
    val w = sqrt(cfg.pipLong * cfg.pipShort * pipAspect(videoAspect, cfg))
    val m = cfg.pipMargin
    val inner = Rect(area.x + m, area.y + m, max(0.0, area.w - 2 * m), max(0.0, area.h - 2 * m))
    val size = fitSize(Size(w, (cfg.pipLong * cfg.pipShort) / w), inner)
    return Size(max(size.w, min(cfg.cardMinWidth, inner.w)), size.h)
}

private fun pipAspect(videoAspect: Double?, cfg: LayoutConfig): Double =
    min(cfg.adaptiveAspectMax, max(cfg.adaptiveAspectMin, videoAspect ?: (9.0 / 16)))


private fun pipTile(p: Participant, rect: Rect, cfg: LayoutConfig): Tile {
    val tile = videoTile(p, TileRole.PIP, rect, 1, cfg)
    val widened = tile.contentMode != null && rect.h > 0 && rect.w / rect.h > pipAspect(p.videoAspect, cfg) * 1.0001
    return if (widened) tile.copy(contentMode = ContentMode.FIT) else tile
}

private class PlacedBelowFold(val controls: Rect, val dock: Rect, val pipArea: Rect?, val place: Rect?, val pip: Rect?)












private fun placeBelowFold(input: LayoutInput, fold: Rect, size: Size, cfg: LayoutConfig): PlacedBelowFold {
    val c = input.content
    val top = fold.y + fold.h + cfg.gap
    val bottom = input.window.h - (input.safe?.bottom ?: 0.0)
    val w = min(size.w, c.w)
    fun centred(x: Double) = Rect(x, bottom - size.h, w, size.h)
    var controls = centred(c.x + (c.w - w) / 2)
    fun dockOf(k: Rect) = Rect(c.x, top, c.w, max(0.0, k.y - cfg.gap - top))
    
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
    val desktop = input.overflow == Overflow.MORE
    val corner = input.pipCorner ?: if (desktop) PipCorner.TL else PipCorner.BR
    
    var mainTile = videoTile(main, TileRole.MAIN, media, 0, cfg)
    val whole = if (hasVideo(main)) fitRect(main.videoAspect!!, media) else null
    if (whole != null) mainTile = mainTile.copy(rect = whole, contentMode = ContentMode.FIT)
    if (second == null || (!second.videoOn && !desktop)) return listOf(mainTile)
    val pipShape = if (hasVideo(second)) second.videoAspect else if (desktop) 16.0 / 9 else second.videoAspect
    var size = pipSizeIn(pipShape, area, cfg)
    if (whole != null) {
        val clear = clearOfPip(whole, media, area, corner, size, cfg)
        if (clear != null) {
            mainTile = mainTile.copy(rect = clear.first)
            size = clear.second
        }
    }
    return listOf(mainTile, pipTile(second, atCorner(size, area, corner, cfg.pipMargin), cfg))
}


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




private fun clearOfPip(video: Rect, media: Rect, area: Rect, corner: PipCorner, size: Size, cfg: LayoutConfig): Pair<Rect, Size>? {
    val m = cfg.pipMargin
    if (!overlaps(video, atCorner(size, area, corner, m))) return video to size
    val vertical = video.h < media.h - 0.5
    if (!vertical && video.w >= media.w - 0.5) return null
    
    val pos = if (vertical) video.y else video.x
    val len = if (vertical) video.h else video.w
    val winLen = if (vertical) size.h else size.w
    val start = if (vertical) area.y else area.x
    val end = if (vertical) area.y + area.h else area.x + area.w
    val mStart = if (vertical) media.y else media.x
    val mEnd = if (vertical) media.y + media.h else media.x + media.w
    val far = if (vertical) corner.bottom else corner.right
    
    val limit = if (far) min(pos, max(mStart, start)) else max(pos, min(mEnd, end) - len)
    val room = if (far) end - m - (limit + len + m) else limit - m - (start + m)
    val k = min(1.0, room / winLen)
    if (k < cfg.pipMinScale) return null
    val fitted = Size(size.w * k, size.h * k)
    val want = if (far) end - m - winLen * k - m - len else start + m + winLen * k + m
    val next = if (far) min(pos, want) else max(pos, want)
    return (if (vertical) video.copy(y = next) else video.copy(x = next)) to fitted
}







private class DuoSplit(val stacked: Boolean, val sizes: List<Size>, val smaller: Double)

private fun layoutDuo(ps: List<Participant>, area: Rect, swapped: Boolean, cfg: LayoutConfig): List<Tile> {
    val youAt = ps.indexOfFirst { it.isLocal }.let { if (it < 0) 0 else it }
    val you = ps[youAt]
    val other = ps[ps.indices.first { it != youAt }]
    val order = if (swapped) listOf(other, you) else listOf(you, other)
    val g = cfg.gap
    
    fun fitIn(p: Participant, box: Rect): Size {
        val a = if (hasVideo(p)) {
            min(cfg.adaptiveAspectMax, max(cfg.adaptiveAspectMin, p.videoAspect!!))
        } else {
            min(cfg.cardAspectMax, max(cfg.cardAspectMin, box.w / box.h))
        }
        val r = fitRect(a, box)
        return Size(r.w, r.h)
    }
    fun split(stacked: Boolean): DuoSplit {
        val room = max(0.0, (if (stacked) area.h else area.w) - g)
        fun boxOf(length: Double) = if (stacked) Rect(0.0, 0.0, area.w, length) else Rect(0.0, 0.0, length, area.h)
        fun along(z: Size) = if (stacked) z.h else z.w
        val halves = order.map { fitIn(it, boxOf(room / 2)) }
        val grown = halves.indices.map { i ->
            val other = halves[1 - i]
            if (room / 2 - along(other) <= 1e-6) {
                halves[i]
            } else {
                val g2 = fitIn(order[i], boxOf(room - along(other)))
                if (g2.w * g2.h > halves[i].w * halves[i].h) g2 else halves[i]
            }
        }
        val sizes = if (along(grown[0]) + along(grown[1]) <= room + 1e-6) grown else halves
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


private fun fitSize(size: Size, box: Rect): Size {
    val k = minOf(1.0, box.w / size.w, box.h / size.h)
    return Size(size.w * k, size.h * k)
}





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


private fun gridTile(p: Participant, rect: Rect, aspect: Double, cfg: LayoutConfig): Tile {
    val t = videoTile(p, TileRole.GRID, rect, 0, cfg)
    return if (t.contentMode != null && rect.w > rect.h * aspect + 0.01) t.copy(contentMode = ContentMode.FIT) else t
}


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


private fun upperOf(input: LayoutInput): Rect? {
    val fold = input.fold
    if (fold == null || postureOf(fold) != Posture.TABLETOP) return null
    val safe = input.safe ?: Insets.ZERO
    return Rect(safe.left, safe.top, input.window.w - safe.left - safe.right, fold.y - safe.top)
}




private const val OFF_CENTRE = 1.25

fun facingSide(win: Size, fold: Rect?): Rect? {
    if (fold == null || postureOf(fold) != Posture.BOOK) return null
    val left = fold.x
    val right = win.w - fold.x - fold.w
    if (max(left, right) < min(left, right) * OFF_CENTRE) return null
    return if (left > right) Rect(0.0, 0.0, left, win.h) else Rect(fold.x + fold.w, 0.0, right, win.h)
}


private fun facingStage(side: Rect, fold: Rect, content: Rect, cfg: LayoutConfig): Rect {
    val right = side.x > fold.x
    val x0 = if (right) max(content.x, fold.x + fold.w + cfg.gap) else content.x
    val x1 = if (right) content.x + content.w else min(content.x + content.w, fold.x - cfg.gap)
    return Rect(x0, content.y, max(0.0, x1 - x0), content.h)
}



fun groupStage(win: Size, content: Rect, fold: Rect?, cfg: LayoutConfig = LayoutConfig.DEFAULT): Rect {
    if (fold != null && postureOf(fold) == Posture.TABLETOP) return Rect(content.x, content.y, content.w, fold.y - cfg.gap - content.y)
    val facing = facingSide(win, fold)
    return if (facing != null && fold != null) facingStage(facing, fold, content, cfg) else content
}



fun oneOnOneMedia(input: LayoutInput): Rect {
    val win = fullWindow(input)
    val fold = input.fold
    upperOf(input)?.let { return it }
    facingSide(input.window, fold)?.let { return it }
    return if (fold != null && postureOf(fold) == Posture.BOOK) Rect(win.x, win.y, fold.x - win.x, win.h) else win
}

private fun notNegative(r: Rect): Rect = if (r.w >= 0 && r.h >= 0) r else r.copy(w = max(0.0, r.w), h = max(0.0, r.h))

private fun fullWindow(input: LayoutInput) = Rect(0.0, 0.0, input.window.w, input.window.h)



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
