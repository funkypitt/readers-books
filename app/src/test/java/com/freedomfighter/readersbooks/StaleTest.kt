package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.books.BookFormat
import com.freedomfighter.readersbooks.books.Entry
import com.freedomfighter.readersbooks.books.Library
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which books of the shelf may leave it by themselves. */
class StaleTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    private fun entry(id: String, source: String?, opened: Long, added: Long = opened, kept: Boolean = false) =
        Entry(id, "$id.epub", id, BookFormat.EPUB, added = added, opened = opened, source = source, kept = kept)

    @Test fun nothingLeavesWhenTheShelfIsNotCleared() {
        val old = entry("a", "https://drive/a.epub", now - 400 * day)
        assertEquals(emptyList<Entry>(), Library.stale(listOf(old), 0, now))
    }

    @Test fun onlyBooksOfTheLibraryLongUnopenedAndNotKept() {
        val old = entry("a", "https://drive/a.epub", now - 40 * day)
        val recent = entry("b", "https://drive/b.epub", now - 10 * day)
        val kept = entry("c", "https://drive/c.epub", now - 40 * day, kept = true)
        val own = entry("d", null, now - 40 * day)
        val fromAFile = entry("e", "content://docs/e", now - 40 * day)
        val justAdded = entry("f", "https://drive/f.epub", opened = 0L, added = now - 2 * day)
        assertEquals(listOf("a"), Library.stale(listOf(old, recent, kept, own, fromAFile, justAdded), 30, now).map { it.id })
        assertEquals(emptyList<String>(), Library.stale(listOf(old, recent, kept, own, fromAFile, justAdded), 90, now).map { it.id })
    }

    @Test fun aBookOpenedLaterThanAddedCountsFromItsLastOpening() {
        val read = entry("a", "https://drive/a.epub", opened = now - 5 * day, added = now - 200 * day)
        assertEquals(emptyList<String>(), Library.stale(listOf(read), 30, now).map { it.id })
    }
}
