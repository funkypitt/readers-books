package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.books.Block
import com.freedomfighter.readersbooks.books.Book
import com.freedomfighter.readersbooks.books.Highlights
import com.freedomfighter.readersbooks.books.Para
import com.freedomfighter.readersbooks.data.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Highlights — the same cases as the desktop's tests/test_books.py, so that both count alike. */
class HighlightsTest {
    private fun chapter(vararg paragraphs: String) = Book.Chapter("c", listOf(Block.Text(paragraphs.map { Para(it) })))

    @Test fun theTextOfAChapterIsAsLongAsTheChapter() {
        val ch = Book.Chapter("c", listOf(Block.Text(listOf(Para("Un"), Para("Deux\ntrois"))), Block.Image("x.png"), Block.Text(listOf(Para("Quatre 😀 cinq")))))
        assertEquals(ch.length, Highlights.chapterText(ch).length)
    }

    @Test fun aPassageIsWidenedToWholeWords() {
        val t = "Il pleuvait sur la ville, aujourd'hui.\n"
        fun cut(a: Int, b: Int) = Highlights.words(t, a, b)?.let { t.substring(it.first, it.second) }
        assertEquals("pleuvait sur", cut(5, 13))
        assertEquals("pleuvait sur", cut(13, 5))
        assertEquals("aujourd'hui", cut(27, 30))
        assertEquals(",", cut(24, 26))
        assertNull(cut(25, 26))
    }

    @Test fun twoDevicesFoldTheirHighlights() {
        val a = Highlights.new(1, 10, 20, "dix mots")
        val b = Highlights.new(0, 5, 9, "cinq")
        val newer = a.copy(comment = "vu", modified = a.modified + 5)
        val removed = b.copy(deleted = true, text = "", modified = b.modified + 9)
        val merged = Highlights.merge(listOf(a, b), listOf(newer, removed))
        assertEquals(listOf(Triple(b.id, "", true), Triple(a.id, "vu", false)), merged.map { Triple(it.id, it.comment, it.deleted) })
        assertEquals(merged, Highlights.merge(listOf(newer, removed), listOf(a, b)))        // whichever side asks
        assertEquals(listOf(a.id), Highlights.live(merged).map { it.id })
    }

    @Test fun theNoteListsThePassagesInTheOrderOfTheBook() {
        val hs = listOf(
            Highlights.new(2, 5, 9, "plus  loin\ndans le livre", "Une remarque."), Highlights.new(0, 50, 60, "au début"),
            Highlights.new(0, 1, 2, "ôté").copy(deleted = true)
        )
        assertEquals(
            "Simenon - Le chien jaune\n\n«\u00A0au début\u00A0»\n\n«\u00A0plus loin dans le livre\u00A0»\n\nUne remarque.\n",
            Highlights.noteText("Simenon - Le chien jaune.epub", hs, "fr")
        )
        assertEquals("Un\n\n“x”\n", Highlights.noteText("Un.epub", listOf(Highlights.new(0, 0, 1, "x")), "en"))
        assertEquals("Un titre.txt", Highlights.noteFileName("Un: \"titre\"?.epub"))
    }

    @Test fun aHighlightFollowsItsWords() {
        val book = Book("b", listOf(chapter("Un mot de plus. Il pleuvait sur la ville.")))
        val shown = Highlights.anchor(listOf(Highlights.new(0, 16, 27, "Il pleuvait"), Highlights.new(0, 0, 11, "Il pleuvait"), Highlights.new(0, 0, 5, "absent")), book)
        assertEquals(listOf(16 to 27, 16 to 27), shown.map { it.start to it.end })
    }

    @Test fun theNotesFolderIsReadFromOurSectionOrFromReadersNotes() {
        val ours = Credentials.build("https://d/", "u", "p", Credentials.Notes("https://d/Notes/", "n", "q"))
        assertEquals(Credentials.Notes("https://d/Notes/", "n", "q"), Credentials.readNotes(ours))
        val theirs = """{"format":"readers-credentials","readers-notes":{"server":"https://dav.example","folder":"Mes notes","username":"n","password":"q"}}"""
        assertEquals(Credentials.Notes("https://dav.example/Mes%20notes/", "n", "q"), Credentials.readNotes(theirs))
        assertNull(Credentials.readNotes(Credentials.build("https://d/", "u", "p")))
    }
}
