package com.freedomfighter.readersbooks.books

import kotlinx.serialization.Serializable

/**
 * A highlighted passage of one chapter: its first character and the one after its last, counted
 * in the text of the chapter (see [Highlights.chapterText]) — the same count on the phone and on
 * the desktop — with the words themselves and, if one was written, a comment. One that was
 * removed stays in the list as removed, which is what tells the other devices.
 */
@Serializable
data class Highlight(
    val id: String,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val text: String = "",
    val comment: String = "",
    val created: Long = 0L,
    val modified: Long = 0L,
    val deleted: Boolean = false
)

/** Where a book was left, and when: the place read last is the one every device opens at. */
@Serializable
data class Position(val chapter: Int = 0, val charOffset: Int = 0, val progress: Int = 0, val modified: Long = 0L)

/** The file that lies next to the book on the drive, and its copy on the phone (`dirty` = not sent yet). */
@Serializable
data class HighlightFile(
    val format: String = Highlights.FORMAT,
    val version: Int = 1,
    val book: String = "",
    val highlights: List<Highlight> = emptyList(),
    val position: Position? = null,
    val dirty: Boolean = false
)

object Highlights {
    const val FORMAT = "readers-highlights"
    const val SUFFIX = ".highlights.json"
    /** The folder of the notes folder where a note per book is written; Reader's Notes shows it read-only. */
    const val BOOKS_FOLDER = "Reader's Books"
    private const val OBJECT = "￼"

    /** The text of a chapter as its characters are counted: every paragraph followed by a line break, a picture standing for one character. */
    fun chapterText(chapter: Book.Chapter): String = buildString {
        chapter.blocks.forEach { b ->
            if (b is Block.Text) b.paragraphs.forEach { append(it.text).append('\n') } else append(OBJECT)
        }
    }

    private fun isWord(text: String, i: Int): Boolean {
        val c = text.getOrNull(i) ?: return false
        return c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-' || c.isSurrogate()
    }

    /** The passage widened to whole words and narrowed to what is not blank at its ends; null when nothing is left. */
    fun words(text: String, from: Int, to: Int): Pair<Int, Int>? {
        var start = minOf(from, to).coerceIn(0, text.length)
        var end = maxOf(from, to).coerceIn(0, text.length)
        while (start > 0 && isWord(text, start - 1) && isWord(text, start)) start--
        while (end < text.length && end > 0 && isWord(text, end - 1) && isWord(text, end)) end++
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (end > start) start to end else null
    }

    fun new(chapter: Int, start: Int, end: Int, text: String, comment: String = ""): Highlight {
        val now = System.currentTimeMillis()
        return Highlight(java.util.UUID.randomUUID().toString().replace("-", "").take(12), chapter, start, end, text, comment, now, now)
    }

    /**
     * Both lists folded into one: the same highlight (same id) is the one changed last, and one
     * removed stays in the list as removed, so that it does not come back from the other side.
     */
    fun merge(ours: List<Highlight>, theirs: List<Highlight>): List<Highlight> {
        val merged = LinkedHashMap<String, Highlight>()
        (theirs + ours).forEach { h ->
            val old = merged[h.id]
            if (old == null || h.modified > old.modified || (h.modified == old.modified && h.deleted && !old.deleted)) merged[h.id] = h
        }
        return merged.values.sortedWith(compareBy({ it.chapter }, { it.start }, { it.id }))
    }

    /** What is shown: not removed, in the order of the book. */
    fun live(all: List<Highlight>): List<Highlight> = all.filter { !it.deleted }.sortedWith(compareBy({ it.chapter }, { it.start }))

    /**
     * A highlight whose words are no longer where it says (the book's file replaced by another
     * edition) is looked for in its chapter; found, it follows them, otherwise it is not shown.
     */
    fun anchor(all: List<Highlight>, book: Book): List<Highlight> {
        val texts = HashMap<Int, String>()
        return live(all).mapNotNull { h ->
            if (h.chapter !in book.chapters.indices) return@mapNotNull null
            val t = texts.getOrPut(h.chapter) { chapterText(book.chapters[h.chapter]) }
            if (h.text.isEmpty() || (h.start >= 0 && h.end <= t.length && h.start <= h.end && t.substring(h.start, h.end) == h.text)) return@mapNotNull h
            val at = t.indexOf(h.text)
            if (at < 0) null else h.copy(start = at, end = at + h.text.length)
        }
    }

    private fun quotes(language: String): Pair<String, String> = when (language) {
        "fr" -> "« " to " »"
        "en" -> "“" to "”"
        "de" -> "„" to "“"
        else -> "«" to "»"
    }

    /**
     * The note Reader's Notes shows for a book: its name, then every passage between quotation
     * marks, in the order of the book, each followed by its comment when it has one.
     */
    fun noteText(bookName: String, all: List<Highlight>, language: String): String {
        val (opening, closing) = quotes(language)
        val parts = ArrayList<String>()
        parts += bookName.substringBeforeLast('.', bookName)
        live(all).forEach { h ->
            parts += opening + h.text.trim().split(Regex("\\s+")).joinToString(" ") + closing
            if (h.comment.isNotBlank()) parts += h.comment.trim()
        }
        return parts.joinToString("\n\n") + "\n"
    }

    /** The same rule as Reader's Notes for the name of a note's file. */
    fun noteFileName(bookName: String): String {
        val base = bookName.substringBeforeLast('.', bookName)
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]"), " ")
            .replace(Regex("[ \\t\\n\\x0b\\f\\r]+"), " ").trim().trimEnd('.')
        return base.ifEmpty { "untitled" } + ".txt"
    }
}
