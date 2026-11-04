package com.freedomfighter.readersbooks.books

/** How a paragraph is set. The reader keeps one paint and derives the others from it. */
enum class ParaKind { NORMAL, HEADING, LEAD, SMALL }

/**
 * A paragraph: its text, as it is counted (highlights are places in it), and its kind. `styles`
 * marks the words set in italic or bold, as runs (start, end, [ITALIC] | [BOLD]) of the text,
 * three numbers a run; they change nothing in the text itself.
 */
class Para(val text: String, val kind: ParaKind = ParaKind.NORMAL, val styles: IntArray? = null) {
    /** The part of the paragraph from `from` to `to`, with its runs. */
    fun slice(from: Int, to: Int): Para {
        val s = styles ?: return Para(text.substring(from, to), kind)
        val runs = ArrayList<Int>()
        for (i in s.indices step 3) {
            val a = maxOf(s[i], from); val b = minOf(s[i + 1], to)
            if (b > a) { runs += a - from; runs += b - from; runs += s[i + 2] }
        }
        return Para(text.substring(from, to), kind, if (runs.isEmpty()) null else runs.toIntArray())
    }
    companion object {
        const val ITALIC = 1
        const val BOLD = 2
    }
}

/** A chapter is a run of paragraphs with images between them. */
sealed class Block {
    class Text(val paragraphs: List<Para>) : Block() {
        val length: Int = paragraphs.sumOf { it.text.length + 1 }
    }
    /** An image inside the book file; `source` is what [ImageStore] understands (zip entry, data). */
    class Image(val source: String) : Block()
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
