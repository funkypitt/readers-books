package com.freedomfighter.readersbooks.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.freedomfighter.readersbooks.books.Block
import com.freedomfighter.readersbooks.books.Book
import com.freedomfighter.readersbooks.books.ImageStore
import com.freedomfighter.readersbooks.books.Look
import com.freedomfighter.readersbooks.books.Para
import com.freedomfighter.readersbooks.books.ParaKind

/**
 * Where the characters of a laid-out string are in the chapter's text: runs of the text, in
 * order, with what lies between them (an indent, the line between two pieces of a table cell)
 * belonging to no place. A highlight is a place in the text; the page draws the string.
 */
internal class TextMap(private val laid: IntArray, private val place: IntArray, private val length: IntArray) {
    private fun runAtLaid(x: Int): Int {
        var lo = 0; var hi = laid.size - 1; var r = 0
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (laid[mid] <= x) { r = mid; lo = mid + 1 } else hi = mid - 1 }
        return r
    }

    private fun runAtPlace(x: Int): Int {
        var lo = 0; var hi = place.size - 1; var r = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (place[mid] <= x) { r = mid; lo = mid + 1 } else hi = mid - 1 }
        return r
    }

    fun toChapter(x: Int): Int {
        if (laid.isEmpty()) return 0
        val r = runAtLaid(x)
        val into = x - laid[r]
        return if (into < 0) place[r] else place[r] + minOf(into, length[r])
    }

    fun toLaid(x: Int): Int {
        if (laid.isEmpty()) return 0
        val r = runAtPlace(x)
        if (r < 0) return laid[0]
        val into = x - place[r]
        return laid[r] + minOf(into, length[r])
    }

    class Builder {
        private val laid = ArrayList<Int>(); private val place = ArrayList<Int>(); private val length = ArrayList<Int>()
        fun run(laidAt: Int, placeAt: Int, n: Int) {
            if (n <= 0) return
            val k = laid.size - 1
            if (k >= 0 && laid[k] + length[k] == laidAt && place[k] + length[k] == placeAt) { length[k] += n; return }
            laid += laidAt; place += placeAt; length += n
        }
        fun build() = TextMap(laid.toIntArray(), place.toIntArray(), length.toIntArray())
    }
}

/**
 * One chapter laid out at the current width/size: pages made of whole text lines, pictures,
 * rules and table rows. Text goes through android.text.StaticLayout, the same engine used by
 * working open-source readers: getLineTop/getLineBottom describe exactly what StaticLayout.draw
 * paints, so a page holds only whole lines and nothing is clipped or repeated. A picture moves
 * to the next page whole.
 */
internal sealed class Piece(val y: Int) {
    /** Lines first..last of a layout, its left edge at `x`, `clip` wide (a table cell is narrower than the page). */
    class Lines(y: Int, val layout: StaticLayout, val first: Int, val last: Int, val map: TextMap, val x: Int = 0, val clip: Int = -1, val justified: Boolean = false) : Piece(y) {
        val top: Int get() = layout.getLineTop(first)

        private val alone = HashMap<Int, StaticLayout>()

        /** Line `l` by itself, not justified: the widths of its characters as they are drawn. */
        private fun alone(l: Int): StaticLayout = alone.getOrPut(l) {
            val start = layout.getLineStart(l); val end = layout.getLineEnd(l)
            val sub = SpannableStringBuilder(layout.text.subSequence(start, end))
            for (span in sub.getSpans(0, sub.length, android.text.style.ParagraphStyle::class.java)) sub.removeSpan(span)
            StaticLayout.Builder.obtain(sub, 0, sub.length, layout.paint, 1 shl 20).setIncludePad(false).build()
        }

        /** The left margin of line `l`: those of its paragraph's spans, first lines and the others. */
        private fun margin(l: Int): Int {
            val text = layout.text as? Spanned ?: return 0
            val start = layout.getLineStart(l)
            var paraStart = start
            while (paraStart > 0 && text[paraStart - 1] != '\n') paraStart--
            val nth = l - layout.getLineForOffset(paraStart)
            return text.getSpans(start, start, LeadingMarginSpan::class.java).sumOf { span ->
                val count = if (span is LeadingMarginSpan.LeadingMarginSpan2) span.leadingMarginLineCount else 1
                span.getLeadingMargin(nth < count)
            }
        }

        /**
         * Where a character of line `l` begins, from the left of the layout. Android's own
         * getPrimaryHorizontal misplaces it on a justified line that holds characters of another
         * size (small capitals, a note): taken here from the line laid out alone, its spaces
         * widened alike as justifying does.
         */
        fun xOf(l: Int, offset: Int): Float {
            if (!justified) return layout.getPrimaryHorizontal(offset)
            val start = layout.getLineStart(l)
            var end = layout.getLineEnd(l)
            val text = layout.text
            val at = offset.coerceIn(start, end)
            val one = alone(l)
            val before = one.getPrimaryHorizontal(at - start)
            val left = margin(l).toFloat()
            if (end >= text.length || text[end - 1] == '\n') return left + before
            while (end > start && text[end - 1] == ' ') end--
            var spaces = 0; var spacesBefore = 0
            for (k in start until end) if (text[k] == ' ') { spaces++; if (k < at) spacesBefore++ }
            if (spaces == 0) return left + before
            val extra = (layout.width - left - one.getPrimaryHorizontal(end - start)) / spaces
            return left + before + spacesBefore * extra
        }

        /** The character under `x` on line `l` (see [xOf]). */
        fun offsetAt(l: Int, x: Float): Int {
            if (!justified) return layout.getOffsetForHorizontal(l, x)
            val start = layout.getLineStart(l)
            var end = layout.getLineEnd(l)
            if (end > start && layout.text[end - 1] == '\n') end--
            var best = start
            for (k in start..end) { if (xOf(l, k) <= x) best = k else break }
            if (best < end && x - xOf(l, best) > xOf(l, best + 1) - x) best++
            return best
        }
        val height: Int get() = layout.getLineBottom(last) - top
        val startChar: Int get() = map.toChapter(layout.getLineStart(first))
        fun toChapter(laid: Int): Int = map.toChapter(laid)
        fun toLaid(place: Int): Int = map.toLaid(place)
    }
    class Picture(y: Int, val place: Int, val source: String, val width: Int, val height: Int, val x: Int) : Piece(y)
    /** A short centred line, drawn where the book has a rule. */
    class Rule(y: Int, val x1: Int, val x2: Int, val place: Int) : Piece(y)
    /** The borders of one table row: each cell from x to x + width. */
    class Grid(y: Int, val height: Int, val cells: IntArray, val place: Int) : Piece(y)
}

internal class PageDef(val pieces: List<Piece>) {
    val startChar: Int = when (val p = pieces.firstOrNull()) {
        is Piece.Lines -> p.startChar
        is Piece.Picture -> p.place
        is Piece.Rule -> p.place
        is Piece.Grid -> pieces.filterIsInstance<Piece.Lines>().firstOrNull()?.startChar ?: p.place
        null -> 0
    }
}

internal class ChapterLayout(val pages: List<PageDef>) {
    val pageCount: Int get() = pages.size
    fun pageStartChar(page: Int): Int = pages[page].startChar
    fun pageFor(charOffset: Int): Int {
        var p = 0
        for (i in pages.indices) if (pages[i].startChar <= charOffset) p = i else break
        return p
    }
}

/**
 * Lines sit 1.7 em apart whatever the face: 1.45 times the line of the system fonts, less for
 * a face that is drawn with tall line boxes of its own (Literata).
 */
internal fun lineSpacing(paint: TextPaint): Float {
    val natural = paint.fontMetrics.let { it.descent - it.ascent } / paint.textSize
    return if (natural > 1.3f) 1.7f / natural else 1.45f
}

private fun buildLayout(text: CharSequence, paint: TextPaint, widthPx: Int, spacingMul: Float, justify: Boolean = false): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, spacingMul)
        .setIncludePad(false)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
        .setJustificationMode(if (justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
        .build()

/** A paragraph's margin: its left margin and first-line indent, a list marker in it, a drop cap over its first two lines. */
private class Margin(
    private val first: Int, private val rest: Int, private val left: Int,
    private val marker: String?, private val drop: String?, private val dropPaint: TextPaint?, private val gap: Int
) : LeadingMarginSpan.LeadingMarginSpan2 {
    override fun getLeadingMarginLineCount(): Int = if (drop != null) 2 else 1
    override fun getLeadingMargin(first: Boolean): Int = if (first) this.first else rest
    override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {
        if (!first) return
        if (marker != null) {
            val w = p.measureText(marker)
            c.drawText(marker, x + left - gap - w, baseline.toFloat(), p)
        }
        if (drop != null && dropPaint != null && layout != null) {
            val line = layout.getLineForOffset(start)
            val second = if (line + 1 < layout.lineCount && layout.getLineStart(line + 1) < end) layout.getLineBaseline(line + 1) else baseline + (bottom - top)
            dropPaint.color = p.color
            c.drawText(drop, (x + left).toFloat(), second.toFloat(), dropPaint)
        }
    }
}

/** The characters of a drop cap where they are in the text: there, but taking no room (they are drawn large in the margin). */
private object Hidden : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = 0
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = Unit
}

/**
 * Raised or lowered when drawn only: a superscript that moved the baseline while measuring would
 * make its line taller than the others.
 */
private class Shift(private val up: Boolean) : android.text.style.CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) { tp.baselineShift += if (up) -(tp.textSize * 0.5f).toInt() else (tp.textSize * 0.25f).toInt() }
}

/** What a page is laid out with: the reader's paint and colours, the width and height of the text, the screen's density. */
internal class Frame(
    val paint: TextPaint, val dimArgb: Int, val widthPx: Int, val pageHeight: Int, val density: Float,
    val images: ImageStore, val magazine: Boolean
) {
    val em = paint.textSize
    val spacing = lineSpacing(paint)
    val lineStep = Math.round((paint.fontMetrics.descent - paint.fontMetrics.ascent) * spacing)
    fun px(ems: Float): Int = Math.round(ems * em)
}

private fun markerText(look: Look): String? = when (look.marker) {
    Look.DISC -> "•"
    Look.CIRCLE -> "◦"
    Look.SQUARE -> "▪"
    Look.DECIMAL -> "${look.number}."
    Look.LOWER_ALPHA -> "${alpha(look.number)}."
    Look.UPPER_ALPHA -> "${alpha(look.number).uppercase()}."
    Look.LOWER_ROMAN -> "${roman(look.number)}."
    Look.UPPER_ROMAN -> "${roman(look.number).uppercase()}."
    else -> null
}

private fun alpha(n: Int): String {
    if (n <= 0) return "$n"
    var k = n; val sb = StringBuilder()
    while (k > 0) { k--; sb.insert(0, 'a' + k % 26); k /= 26 }
    return sb.toString()
}

private fun roman(n: Int): String {
    if (n <= 0 || n >= 4000) return "$n"
    val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val s = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
    var k = n; val sb = StringBuilder()
    for (i in v.indices) while (k >= v[i]) { sb.append(s[i]); k -= v[i] }
    return sb.toString()
}

/**
 * Italic, bold and the rest on part of the laid-out string. Capitals, small capitals and
 * lower case change the drawn characters only, one for one: the text keeps its own.
 */
private fun setIn(sb: SpannableStringBuilder, start: Int, end: Int, flags: Int, sizePercent: Int = 0) {
    if (end <= start) return
    val x = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    when (flags and (Para.ITALIC or Para.BOLD)) {
        Para.ITALIC -> sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, x)
        Para.BOLD -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, x)
        Para.ITALIC or Para.BOLD -> sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, x)
    }
    if (flags and Para.UNDERLINE != 0) sb.setSpan(UnderlineSpan(), start, end, x)
    if (flags and Para.STRIKE != 0) sb.setSpan(StrikethroughSpan(), start, end, x)
    if (flags and Para.MONO != 0) sb.setSpan(TypefaceSpan("monospace"), start, end, x)
    if (flags and Para.SUPER != 0) { sb.setSpan(Shift(true), start, end, x); sb.setSpan(RelativeSizeSpan(0.7f), start, end, x) }
    else if (flags and Para.SUB != 0) { sb.setSpan(Shift(false), start, end, x); sb.setSpan(RelativeSizeSpan(0.7f), start, end, x) }
    else if (sizePercent > 0) sb.setSpan(RelativeSizeSpan(sizePercent / 100f), start, end, x)
}

private const val CASES = Para.UPPER or Para.LOWER or Para.CAPITALIZE or Para.SMALL_CAPS

/** For each character of the paragraph, the change of case it is drawn in; null when none. */
private fun casesOf(p: Para): IntArray? {
    val look = p.look ?: return null
    val base = look.flags and CASES
    val r = p.styles
    if (base == 0 && (r == null || (r.indices step 3).none { r[it + 2] and CASES != 0 })) return null
    val out = IntArray(p.text.length) { base }
    if (r != null) for (q in r.indices step 3) for (k in r[q] until r[q + 1]) out[k] = out[k] or (r[q + 2] and CASES)
    return out
}

/** A character as drawn in its case: one for one, so the places do not move. */
private fun cased(c: Char, mode: Int, prev: Char?): Char = when {
    mode == 0 -> c
    mode and Para.UPPER != 0 -> c.uppercaseChar()
    mode and Para.LOWER != 0 -> c.lowercaseChar()
    mode and Para.CAPITALIZE != 0 && c.isLetter() && (prev == null || !prev.isLetterOrDigit()) -> c.uppercaseChar()
    mode and Para.SMALL_CAPS != 0 -> c.uppercaseChar()
    else -> c
}

/** Small capitals: the lower-case letters drawn as smaller capitals. */
private fun smallCaps(sb: SpannableStringBuilder, p: Para, cases: IntArray?, from: Int, to: Int, at: (Int) -> Int) {
    cases ?: return
    var k = from
    while (k < to) {
        if (cases[k] and Para.SMALL_CAPS != 0 && cases[k] and (Para.UPPER or Para.LOWER) == 0 && p.text[k].isLowerCase()) {
            var e = k
            while (e < to && cases[e] and Para.SMALL_CAPS != 0 && p.text[e].isLowerCase()) e++
            sb.setSpan(RelativeSizeSpan(0.8f), at(k), at(e), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            k = e
        } else k++
    }
}

/** The runs of a paragraph, from `from` to `to` of its text, at `at` of the laid-out string (one character for one). */
private fun runs(sb: SpannableStringBuilder, p: Para, from: Int, to: Int, at: (Int) -> Int) {
    val r = p.styles ?: return
    for (k in r.indices step 3) {
        val a = maxOf(r[k], from); val b = minOf(r[k + 1], to)
        if (b > a) setIn(sb, at(a), at(b), r[k + 2] and Para.FLAGS, r[k + 2] ushr Para.SIZE_SHIFT)
    }
}

/** Where the line breaks of a paragraph's text stay line breaks (the others are drawn as spaces). */
private fun hardBreaks(p: Para): List<Int> {
    val soft = p.look?.soft
    return p.text.indices.filter { p.text[it] == '\n' && (soft == null || java.util.Arrays.binarySearch(soft, it) < 0) }
}

/** `drops`: (paragraph, start, end in the string) of each drop cap. */
/** The reader's own indent, when the book says nothing: two ems. */
private const val INDENT = 2f

private class Built(val text: CharSequence, val map: TextMap, val drops: List<IntArray>)

/**
 * A run of paragraphs as one string. Multi-paragraph layouts report line tops and bottoms that
 * do not match what is drawn once a line height is set (paragraph boundaries trim differently),
 * which clipped the last line of a page and repeated it on the next: the paragraphs of one run
 * never have space between them (a space between paragraphs starts a new run). The reader's own
 * indent, the book's margins, indents, list markers and drop caps are
 * paragraph spans, its sizes and the rest character spans on the same string.
 */
private fun buildRun(block: Block.Text, from: Int, to: Int, base: Int, f: Frame, noDrop: Set<Int> = emptySet()): Built {
    val sb = SpannableStringBuilder()
    val map = TextMap.Builder()
    val drops = ArrayList<IntArray>()
    var place = base
    for (i in from until to) {
        val p = block.paragraphs[i]
        val look = p.look
        if (i > from) { map.run(sb.length, place - 1, 1); sb.append("\n") }
        val start = sb.length
        val prevKind = if (i > 0) block.paragraphs[i - 1].kind else null
        val centred = look != null && (look.align == Look.CENTER || look.align == Look.RIGHT)
        // the reader's own indent: a paragraph of text after another, when the book says nothing
        val indent = i > 0 && p.kind == ParaKind.NORMAL && prevKind == ParaKind.NORMAL && (look == null || (look.indent.isNaN() && !centred && look.dropCap == 0 && look.marker == 0 && look.flags and Para.MONO == 0))
        val hard = hardBreaks(p)
        // laid-out place of each character of the text
        val laidOf = IntArray(p.text.length + 1)
        var k = 0
        val chunks = ArrayList<Int>() // where each line of the paragraph (between hard breaks) begins, in the string
        chunks += sb.length
        var hb = 0
        val cases = casesOf(p)
        while (k < p.text.length) {
            laidOf[k] = start + k
            val c = p.text[k]
            if (c == '\n') {
                val isHard = hb < hard.size && hard[hb] == k
                if (isHard) hb++
                sb.append(if (isHard) '\n' else ' ')
                if (isHard) chunks += sb.length
            } else sb.append(if (cases == null) c else cased(c, cases[k], if (k > 0) p.text[k - 1] else null))
            k++
        }
        laidOf[p.text.length] = start + p.text.length
        // the text's places, run by run between the indents
        var runFrom = 0
        for (q in 0..p.text.length) if (q == p.text.length || (q > 0 && laidOf[q] != laidOf[q - 1] + 1)) {
            map.run(laidOf[runFrom], place + runFrom, q - runFrom); runFrom = q
        }
        val end = sb.length
        place += p.text.length + 1
        val x = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        if (look == null) {
            p.styles?.let { r -> for (q in r.indices step 3) setIn(sb, laidOf[r[q]], laidOf[r[q + 1]], r[q + 2] and Para.FLAGS) }
            // the reader's own indent, on every line of the paragraph
            if (indent) for ((n, c) in chunks.withIndex()) sb.setSpan(LeadingMarginSpan.Standard(f.px(INDENT), 0), c, if (n + 1 < chunks.size) chunks[n + 1] else end, x)
            when (p.kind) {
                ParaKind.HEADING -> { sb.setSpan(RelativeSizeSpan(1.3f), start, end, x); sb.setSpan(StyleSpan(Typeface.BOLD), start, end, x) }
                ParaKind.LEAD -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, x)
                ParaKind.SMALL -> { sb.setSpan(RelativeSizeSpan(0.8f), start, end, x); sb.setSpan(ForegroundColorSpan(f.dimArgb), start, end, x) }
                ParaKind.NORMAL -> Unit
            }
            continue
        }
        // the whole paragraph, then its runs
        if (look.size != 1f) sb.setSpan(RelativeSizeSpan(look.size), start, end, x)
        if (p.kind == ParaKind.SMALL) sb.setSpan(ForegroundColorSpan(f.dimArgb), start, end, x)
        // a drop cap's own letters take no room: nothing of theirs may make the line taller
        val dropped = if (look.dropCap in 1..p.text.length && i !in noDrop) look.dropCap else 0
        setIn(sb, laidOf[dropped], end, look.flags)
        runs(sb, p, dropped, p.text.length) { laidOf[it] }
        smallCaps(sb, p, cases, dropped, p.text.length) { laidOf[it] }
        // margins, indent, marker, drop cap, alignment: one span per line of the paragraph
        // a list's marker sits in the margin: the margin is never narrower than it
        val marker = markerText(look)
        val left = maxOf(f.px(look.left).coerceAtMost(f.widthPx * 35 / 100), if (marker != null) Math.round(f.paint.measureText(marker) + f.em * 0.6f) else 0)
        val first = if (look.indent.isNaN() || look.dropCap > 0) 0 else f.px(look.indent)
        var drop: String? = null
        var dropPaint: TextPaint? = null
        var dropWidth = 0
        if (look.dropCap in 1..p.text.length && i !in noDrop) {
            val letters = sb.substring(laidOf[0], laidOf[look.dropCap])
            dropPaint = TextPaint(f.paint).apply {
                typeface = Typeface.create(f.paint.typeface, Typeface.BOLD)
                val bounds = android.graphics.Rect(); f.paint.getTextBounds("H", 0, 1, bounds)
                val cap = bounds.height().toFloat().coerceAtLeast(f.em * 0.6f)
                textSize = f.em * (f.lineStep + cap) / cap
            }
            dropWidth = Math.round(dropPaint.measureText(letters) + f.em * 0.2f)
            drop = letters
            sb.setSpan(Hidden, laidOf[0], laidOf[look.dropCap], x)
            drops += intArrayOf(i, start, end)
        }
        for ((n, c) in chunks.withIndex()) {
            val e = if (n + 1 < chunks.size) chunks[n + 1] else end
            val firstPx = (left + (if (indent) f.px(INDENT) else 0) + if (n == 0) first + dropWidth else 0).coerceIn(0, f.widthPx / 2)
            sb.setSpan(Margin(firstPx, left, left, if (n == 0) marker else null, if (n == 0) drop else null, dropPaint, Math.round(f.em * 0.4f)), c, e, x)
            when (look.align) {
                Look.CENTER -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), c, e, x)
                Look.RIGHT -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), c, e, x)
            }
        }
    }
    return Built(sb, map.build(), drops)
}

/** The left/right of the page a paragraph keeps and how it is justified: what one run of paragraphs shares. */
private fun sameRun(a: Para, b: Para): Boolean {
    val x = a.look; val y = b.look
    if (x == null || y == null) return x == null && y == null
    return (x.align == Look.JUSTIFY) == (y.align == Look.JUSTIFY) && x.right == y.right && (x.cells == null) == (y.cells == null)
}

/** True when the paragraph begins away from the one before it: space, a rule, a page. */
private fun apart(p: Para): Boolean { val l = p.look ?: return false; return l.above > 0f || l.rule || l.pageBreak || l.level == 1 }

private class Cell(val col: Int, val span: Int, val header: Boolean) {
    val pieces = ArrayList<IntArray>() // paragraph, from, to
}

/**
 * Pages of the chapter. Paragraphs go in runs (see [buildRun]); the space the book puts between
 * them, a rule, a page break, a chapter heading on a new page come between runs; a picture keeps
 * its own size unless it is large; a table is a grid of cells, each a small layout of its own.
 */
internal fun paginate(ch: Book.Chapter, f: Frame): ChapterLayout {
    val pages = ArrayList<PageDef>()
    var pieces = ArrayList<Piece>()
    var y = 0
    var place = 0 // the place of the block in the chapter's text: see Highlights.chapterText
    val blockGap = f.px(0.8f)
    var bodySeen = false
    var firstOfBlock: Boolean
    fun newPage() { if (pieces.isNotEmpty()) { pages += PageDef(pieces); pieces = ArrayList() }; y = 0 }

    /** Space before something: none at the top of a page. */
    fun space(px: Int) { if (y > 0) y += px }

    fun rule(at: Int) {
        val h = f.px(0.9f)
        if (y + h > f.pageHeight) newPage()
        val w = f.widthPx / 4
        pieces += Piece.Rule(y + h / 2, (f.widthPx - w) / 2, (f.widthPx + w) / 2, at)
        y += h
    }

    fun placeLines(layout: StaticLayout, map: TextMap, keepFirst: Int, dropLines: Set<Int>, justified: Boolean) {
        var line = 0
        // a heading stays with what follows: it moves on when it and two lines more do not fit
        if (keepFirst > 0 && y > 0 && y + layout.height + keepFirst > f.pageHeight && layout.height + keepFirst <= f.pageHeight) newPage()
        while (line < layout.lineCount) {
            val top = layout.getLineTop(line)
            var l = line
            // A line belongs to the page only if its whole box fits.
            while (l < layout.lineCount && layout.getLineBottom(l) - top <= f.pageHeight - y) l++
            // a drop cap's two lines stay together
            if (l < layout.lineCount && l - 1 > line && (l - 1) in dropLines) l--
            if (l == line) {
                if (y > 0) { newPage(); continue } // try again at the top of a fresh page
                l++ // a single line taller than the page
            }
            pieces += Piece.Lines(y, layout, line, l - 1, map, justified = justified)
            y += layout.getLineBottom(l - 1) - top
            line = l
            if (line < layout.lineCount) newPage()
        }
    }

    for (block in ch.blocks) {
        firstOfBlock = true
        when (block) {
            is Block.Text -> {
                val paras = block.paragraphs
                val placeOf = IntArray(paras.size + 1)
                for (i in paras.indices) placeOf[i + 1] = placeOf[i] + paras[i].text.length + 1
                var i = 0
                while (i < paras.size) {
                    val p = paras[i]
                    val look = p.look
                    // what comes before: space, a page, a rule
                    val gap = maxOf(if (firstOfBlock) blockGap else 0, f.px(look?.above ?: 0f))
                    val headingBreak = look != null && look.level == 1 && !f.magazine && bodySeen
                    if ((look?.pageBreak == true || headingBreak) && y > 0) newPage()
                    space(gap)
                    if (look?.rule == true) rule(place + placeOf[i])
                    firstOfBlock = false
                    // a table: its rows in a grid, or as paragraphs when it cannot be one
                    if (look?.cells != null) {
                        var j = i + 1
                        while (j < paras.size && paras[j].look?.cells != null && paras[j].look!!.table == look.table && !apart(paras[j])) j++
                        val rows = table(block, i, j, place + placeOf[i], placeOf, place, f)
                        if (rows != null) {
                            for (row in rows) {
                                if (y + row.height > f.pageHeight && y > 0) newPage()
                                pieces += Piece.Grid(y, row.height, row.edges, row.place)
                                for (c in row.cells) pieces += Piece.Lines(y + c.dy, c.layout, 0, c.layout.lineCount - 1, c.map, c.x, c.width)
                                y += row.height
                            }
                            i = j
                            continue
                        }
                    }
                    var j = i + 1
                    while (j < paras.size && sameRun(p, paras[j]) && !apart(paras[j]) && (paras[j].look?.cells == null) == (look?.cells == null)) j++
                    var built = buildRun(block, i, j, place + placeOf[i], f)
                    val right = f.px(look?.right ?: 0f).coerceAtMost(f.widthPx / 4)
                    var layout = buildLayout(built.text, f.paint, f.widthPx - right, f.spacing, look?.align == Look.JUSTIFY)
                    // a drop cap needs two lines to sit on: a paragraph of one line goes without
                    val short = built.drops.filter { d -> layout.getLineForOffset(d[1]) == layout.getLineForOffset(maxOf(d[1], d[2] - 1)) }.map { it[0] }.toSet()
                    if (short.isNotEmpty()) {
                        built = buildRun(block, i, j, place + placeOf[i], f, short)
                        layout = buildLayout(built.text, f.paint, f.widthPx - right, f.spacing, look?.align == Look.JUSTIFY)
                    }
                    val drops = built.drops.map { layout.getLineForOffset(it[1]) }.toSet()
                    val heading = j == i + 1 && (look?.level ?: 0) > 0
                    placeLines(layout, built.map, if (heading) f.lineStep * 2 else 0, drops, look?.align == Look.JUSTIFY)
                    if ((i until j).any { paras[it].kind == ParaKind.NORMAL && (paras[it].look?.level ?: 0) == 0 }) bodySeen = true
                    i = j
                }
                place += block.length
            }
            is Block.Image -> {
                val size = f.images.size(block.source)
                val look = block.look
                if (size != null && size.width >= 16 && size.height >= 16) {
                    // its own size when smaller than the text, all the text's width when large
                    val natural = (if ((look?.pixels ?: 0) > 0) look!!.pixels else size.width) * f.density
                    var w = when {
                        look != null && !look.width.isNaN() -> f.widthPx * look.width
                        look == null || natural >= f.widthPx * 0.75f -> f.widthPx.toFloat()
                        else -> natural
                    }.toInt().coerceIn(1, f.widthPx)
                    var h = (w.toLong() * size.height / size.width).toInt()
                    if (h > f.pageHeight) { h = f.pageHeight; w = (h.toLong() * size.width / size.height).toInt() }
                    if (look?.pageBreak == true && y > 0) newPage()
                    space(maxOf(blockGap, f.px(look?.above ?: 0f)))
                    if (look?.rule == true) rule(place)
                    if (y + h > f.pageHeight) newPage()
                    val x = when (look?.align) { Look.LEFT -> 0; Look.RIGHT -> f.widthPx - w; else -> (f.widthPx - w) / 2 }
                    pieces += Piece.Picture(y, place, block.source, w, h, x)
                    y += h
                }
                place += 1
            }
        }
    }
    newPage()
    if (pages.isEmpty()) pages += PageDef(emptyList())
    return ChapterLayout(pages)
}

private class LaidCell(val x: Int, val width: Int, val dy: Int, val layout: StaticLayout, val map: TextMap)
private class Row(val height: Int, val edges: IntArray, val cells: List<LaidCell>, val place: Int)

/**
 * Paragraphs from..to of a block, rows of one table, as a grid: thin borders, the columns shared
 * by what they hold, each cell's text wrapped in it. Null when it cannot be one (too many
 * columns, a row taller than a page): the rows are then read as paragraphs.
 */
private fun table(block: Block.Text, from: Int, to: Int, at: Int, placeOf: IntArray, base: Int, f: Frame): List<Row>? {
    val rows = java.util.TreeMap<Int, java.util.TreeMap<Int, Cell>>()
    for (i in from until to) {
        val c = block.paragraphs[i].look!!.cells!!
        for (k in c.indices step 6) {
            val row = rows.getOrPut(c[k + 2]) { java.util.TreeMap() }
            val cell = row.getOrPut(c[k + 3]) { Cell(c[k + 3], c[k + 4], c[k + 5] == 1) }
            cell.pieces += intArrayOf(i, c[k], c[k + 1])
        }
    }
    if (rows.isEmpty()) return null
    val columns = rows.values.maxOf { r -> r.values.maxOf { it.col + it.span } }
    if (columns < 1 || columns > 12) return null
    val pad = f.px(0.3f).coerceAtLeast(2)
    val cellPaint = TextPaint(f.paint)
    // each cell's string, and what it would like and needs at least
    class Text(val cell: Cell, val text: CharSequence, val map: TextMap, val wants: Float, val needs: Float)
    val texts = rows.mapValues { (_, r) ->
        r.values.map { cell ->
            val sb = SpannableStringBuilder()
            val map = TextMap.Builder()
            for ((n, piece) in cell.pieces.withIndex()) {
                val p = block.paragraphs[piece[0]]
                if (n > 0) sb.append('\n')
                val start = sb.length
                val laid = { k: Int -> start + k - piece[1] }
                val hard = hardBreaks(p).toSet()
                val cases = casesOf(p)
                for (k in piece[1] until piece[2]) sb.append(when {
                    p.text[k] == '\n' -> if (k in hard) '\n' else ' '
                    cases != null -> cased(p.text[k], cases[k], if (k > 0) p.text[k - 1] else null)
                    else -> p.text[k]
                })
                map.run(start, base + placeOf[piece[0]] + piece[1], piece[2] - piece[1])
                val look = p.look!!
                val x = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                if (look.size != 1f) sb.setSpan(RelativeSizeSpan(look.size), start, sb.length, x)
                setIn(sb, start, sb.length, look.flags or if (cell.header) Para.BOLD else 0)
                runs(sb, p, piece[1], piece[2], laid)
                smallCaps(sb, p, cases, piece[1], piece[2], laid)
                when (look.align) {
                    Look.CENTER -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, sb.length, x)
                    Look.RIGHT -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), start, sb.length, x)
                }
            }
            // measured with its own spans: a header in bold, a word in capitals
            var wants = 0f; var needs = 0f
            var a = 0
            for (q in 0..sb.length) if (q == sb.length || sb[q] == '\n') { wants = maxOf(wants, Layout.getDesiredWidth(sb, a, q, cellPaint)); a = q + 1 }
            a = 0
            for (q in 0..sb.length) if (q == sb.length || sb[q] == ' ' || sb[q] == '\n') { if (q > a) needs = maxOf(needs, Layout.getDesiredWidth(sb, a, q, cellPaint)); a = q + 1 }
            Text(cell, sb, map.build(), wants + 2 * pad + 2, needs + 2 * pad + 2)
        }
    }
    val wants = FloatArray(columns) { 1f }
    val needs = FloatArray(columns)
    for (r in texts.values) for (t in r) if (t.cell.span == 1) { wants[t.cell.col] = maxOf(wants[t.cell.col], t.wants); needs[t.cell.col] = maxOf(needs[t.cell.col], t.needs) }
    // the page's width shared by what each column would like, none below the longest of its words if it can be helped
    val total = wants.sum()
    val widths = FloatArray(columns) { f.widthPx * wants[it] / total }
    val short = (0 until columns).filter { widths[it] < needs[it] }
    if (short.isNotEmpty()) {
        val wanted = short.sumOf { (needs[it] - widths[it]).toDouble() }.toFloat()
        val spare = (0 until columns).filter { it !in short }.sumOf { (widths[it] - needs[it]).coerceAtLeast(0f).toDouble() }.toFloat()
        if (spare > 0f) {
            val k = minOf(1f, spare / wanted)
            for (c in 0 until columns) if (c in short) widths[c] += (needs[c] - widths[c]) * k else widths[c] -= (widths[c] - needs[c]).coerceAtLeast(0f) * (wanted * k / spare)
        }
    }
    val xs = IntArray(columns + 1)
    for (c in 0 until columns) xs[c + 1] = xs[c] + widths[c].toInt()
    xs[columns] = f.widthPx - 1
    val out = ArrayList<Row>()
    for ((_, r) in texts) {
        val cells = ArrayList<LaidCell>()
        val edges = ArrayList<Int>()
        var height = 0
        for (t in r) {
            val x0 = xs[t.cell.col]; val x1 = xs[minOf(columns, t.cell.col + t.cell.span)]
            val layout = buildLayout(t.text, cellPaint, (x1 - x0 - 2 * pad).coerceAtLeast(8), f.spacing)
            cells += LaidCell(x0 + pad, x1 - x0 - 2 * pad, pad, layout, t.map)
            edges += x0; edges += x1 - x0
            height = maxOf(height, layout.height + 2 * pad)
        }
        if (height > f.pageHeight) return null
        out += Row(height, edges.toIntArray(), cells, at)
    }
    return out
}
