package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.books.BookFormat
import com.freedomfighter.readersbooks.books.Entry
import com.freedomfighter.readersbooks.data.Settings
import com.freedomfighter.readersbooks.ui.canSend
import com.freedomfighter.readersbooks.ui.sendName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file name a book of the shelf goes to the drive under, and which books may go. */
class SendNameTest {
    private fun entry(title: String, format: BookFormat = BookFormat.EPUB, source: String? = null, sourceName: String? = null) =
        Entry("id", "id.epub", title, format, added = 1L, source = source, sourceName = sourceName)
    private val library = Settings(url = "https://d/", username = "u", password = "p")

    @Test fun `the name it was opened with, when it has a book's extension`() {
        assertEquals("Simenon - Le chien jaune.epub", sendName(entry("Le chien jaune", sourceName = "Simenon - Le chien jaune.epub")))
        assertEquals("Un livre.azw3", sendName(entry("Un livre", BookFormat.MOBI, sourceName = "Un livre.azw3")))
    }

    @Test fun `else the title, cleaned, with the format's extension`() {
        assertEquals("book", sendName(entry("book", sourceName = "book")).substringBefore('.'))
        assertEquals("Vol. 1 a b.epub", sendName(entry("Vol. 1 / a:b", sourceName = "document:12")))
        assertEquals("Un mobi.mobi", sendName(entry("Un mobi", BookFormat.MOBI)))
        assertEquals("book.fb2", sendName(entry("   ", BookFormat.FB2)))
        assertEquals(120 + 5, sendName(entry("x".repeat(300))).length)
    }

    @Test fun `only books that are not from the library, and that a sync would list`() {
        assertTrue(canSend(library, entry("a")))
        assertFalse(canSend(Settings(), entry("a")))
        assertFalse(canSend(library, entry("a", source = "https://d/a.epub")))
        assertTrue(canSend(library, entry("a", source = "share|old")))
        assertFalse(canSend(library, entry("a", BookFormat.TXT)))
    }
}
