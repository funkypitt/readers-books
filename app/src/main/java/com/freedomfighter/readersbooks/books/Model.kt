package com.freedomfighter.readersbooks.books

/** How a paragraph is set. The reader keeps one paint and derives the others from it. */
enum class ParaKind { NORMAL, HEADING, LEAD, SMALL }

/**
 * A paragraph: its text, as it is counted (highlights are places in it), and its kind. `styles`
 * marks the words set apart, as runs (start, end, flags) of the text, three numbers a run: the
 * flags are [ITALIC], [BOLD] and the others below, and a size in per cent in the bits from
 * [SIZE_SHIFT] (0 = the paragraph's own). `look` is how the book sets the paragraph (see
 * [Look]). Neither changes anything in the text itself.
 */
class Para(val text: String, val kind: ParaKind = ParaKind.NORMAL, val styles: IntArray? = null, val look: Look? = null) {
    /** The part of the paragraph from `from` to `to`, with its runs. */
    fun slice(from: Int, to: Int): Para {
        val l = look?.slice(from, to)
        val s = styles ?: return Para(text.substring(from, to), kind, null, l)
        val runs = ArrayList<Int>()
        for (i in s.indices step 3) {
            val a = maxOf(s[i], from); val b = minOf(s[i + 1], to)
            if (b > a) { runs += a - from; runs += b - from; runs += s[i + 2] }
        }
        return Para(text.substring(from, to), kind, if (runs.isEmpty()) null else runs.toIntArray(), l)
    }
    companion object {
        const val ITALIC = 1
        const val BOLD = 2
        const val SMALL_CAPS = 4
        const val SUPER = 8
        const val SUB = 16
        const val UNDERLINE = 32
        const val STRIKE = 64
        const val MONO = 128
        const val UPPER = 256
        const val LOWER = 512
        const val CAPITALIZE = 1024
        /** Every flag of a run but its size. */
        const val FLAGS = 2047
        const val SIZE_SHIFT = 16
    }
}

/**
 * How the book sets a paragraph (or a picture), from its style sheets as far as a reader with
 * its own font, size, margins and colours follows them: never a character of the text. Lengths
 * are in ems of the reader's size; NaN or [AUTO] means the book said nothing, and the reader
 * does as it always did.
 *
 * - `align`: [AUTO], [LEFT], [CENTER], [RIGHT] or [JUSTIFY];
 * - `indent`: the first line's indent (negative: a hanging line), NaN = the reader's own rule;
 * - `left`/`right`: the paragraph's margins (quotations, lists, verse);
 * - `above`: the space before it; `pageBreak`: it begins a page; `rule`: a short line before it;
 * - `size`: its size against the reader's (0.8–1.6); `flags`: [Para] flags of the whole of it;
 * - `level`: 1–6 for a heading; `marker`/`number`: the bullet or number drawn in its margin;
 * - `dropCap`: the first so many characters drawn large over two lines;
 * - `soft`: where a line break of the text is drawn as a space (the source's own line ends);
 * - `cells`: a table row: (start, end, row, column, columns spanned, header) per cell, in a table
 *   numbered `table` in its chapter;
 * - `width`: a picture's width as a part of the text's (NaN = its own size); `pixels`: the width
 *   the page gives it in CSS pixels (0 = the picture's own).
 */
class Look(
    val align: Int = AUTO,
    val indent: Float = Float.NaN,
    val left: Float = 0f,
    val right: Float = 0f,
    val above: Float = 0f,
    val pageBreak: Boolean = false,
    val rule: Boolean = false,
    val size: Float = 1f,
    val flags: Int = 0,
    val level: Int = 0,
    val marker: Int = 0,
    val number: Int = 0,
    val dropCap: Int = 0,
    val soft: IntArray? = null,
    val cells: IntArray? = null,
    val table: Int = 0,
    val width: Float = Float.NaN,
    val pixels: Int = 0,
) {
    /** The look of the part from `from` to `to`: what belongs to its first line goes with the first part only. */
    fun slice(from: Int, to: Int): Look {
        val first = from == 0
        val soft = this.soft?.filter { it in from until to }?.map { it - from }?.takeIf { it.isNotEmpty() }?.toIntArray()
        val cells = this.cells?.let { c ->
            val out = ArrayList<Int>()
            for (i in c.indices step 6) {
                val s = maxOf(c[i], from); val e = minOf(c[i + 1], to)
                if (e > s) { out += s - from; out += e - from; for (k in 2 until 6) out += c[i + k] }
            }
            if (out.isEmpty()) null else out.toIntArray()
        }
        return Look(align, if (first) indent else 0f, left, right, if (first) above else 0f, first && pageBreak, first && rule,
            size, flags, level, if (first) marker else 0, number, if (first) dropCap else 0, soft, cells, table, width, pixels)
    }

    companion object {
        const val AUTO = 0
        const val LEFT = 1
        const val CENTER = 2
        const val RIGHT = 3
        const val JUSTIFY = 4
        // markers drawn in a list item's margin
        const val DISC = 1
        const val CIRCLE = 2
        const val SQUARE = 3
        const val DECIMAL = 4
        const val LOWER_ALPHA = 5
        const val UPPER_ALPHA = 6
        const val LOWER_ROMAN = 7
        const val UPPER_ROMAN = 8
    }
}

/** A chapter is a run of paragraphs with images between them. */
sealed class Block {
    class Text(val paragraphs: List<Para>) : Block() {
        val length: Int = paragraphs.sumOf { it.text.length + 1 }
    }
    /** An image inside the book file; `source` is what [ImageStore] understands (zip entry, data). */
    class Image(val source: String, val look: Look? = null) : Block()
}

/** A parsed book: paragraphs and images, no styles or links. */
class Book(val title: String, val chapters: List<Chapter>, val magazine: Magazine? = null) {
    class Chapter(val title: String, val blocks: List<Block>) {
        val paragraphs: List<Para> get() = blocks.filterIsInstance<Block.Text>().flatMap { it.paragraphs }
        val length: Int = blocks.sumOf { if (it is Block.Text) it.length else 1 }
    }
    val totalLength: Int = chapters.sumOf { it.length }
    fun charsBefore(chapter: Int): Int = chapters.take(chapter).sumOf { it.length }
}

/**
 * A magazine issue, as produced by the newspapers pipeline: each article is a chapter, and the
 * table of contents carries a category, a title, an author and a cover image per article.
 */
class Magazine(val articles: List<Article>) {
    class Article(val chapter: Int, val category: String, val title: String, val author: String?, val image: String?)
}
