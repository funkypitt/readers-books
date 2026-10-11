package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.remote.Folders
import com.freedomfighter.readersbooks.remote.RemoteBook
import com.freedomfighter.readersbooks.remote.TRANSCRIPTS_FOLDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The choice of folders and the merge of one folder listed again into the books known so far. */
class FoldersTest {
    private fun book(folder: String, name: String) = RemoteBook(name, folder, "ref:$folder/$name", 1L, 1L)

    @Test fun nothingChosenIsTheWholeDrive() {
        assertTrue(Folders.inLibrary(emptyList(), ""))
        assertTrue(Folders.inLibrary(emptyList(), "a/b"))
        assertEquals(listOf(""), Folders.roots(emptyList()))
    }

    @Test fun aChosenFolderStandsForWhatIsInside() {
        val chosen = listOf("Books")
        assertTrue(Folders.inLibrary(chosen, "Books"))
        assertTrue(Folders.inLibrary(chosen, "Books/Maigret"))
        assertFalse(Folders.inLibrary(chosen, "Bookshelf"))   // a prefix of the name is not inside
        assertFalse(Folders.inLibrary(chosen, ""))
        assertEquals("Books", Folders.partOf(chosen, "Books/Maigret"))
        assertNull(Folders.partOf(chosen, "Books"))
        assertNull(Folders.partOf(chosen, "Other"))
    }

    @Test fun theTranscriptsFolderAlwaysStays() {
        val chosen = listOf("Books")
        assertTrue(Folders.inLibrary(chosen, TRANSCRIPTS_FOLDER))
        assertTrue(Folders.inLibrary(chosen, "$TRANSCRIPTS_FOLDER/Huberman"))
        assertEquals(TRANSCRIPTS_FOLDER, Folders.partOf(chosen, "$TRANSCRIPTS_FOLDER/Huberman"))
        assertEquals(listOf("Books", TRANSCRIPTS_FOLDER), Folders.roots(chosen))
        assertEquals(listOf(TRANSCRIPTS_FOLDER), Folders.roots(listOf(TRANSCRIPTS_FOLDER)))
    }

    @Test fun listingAFolderAgainReplacesItsOwnBooksOnly() {
        val known = listOf(book("a", "old.epub"), book("a", "kept.epub"), book("a/sub", "deep.epub"), book("b", "other.epub"))
        val merged = Folders.merge(known, "a", listOf("a/sub"), listOf(book("a", "kept.epub"), book("a", "new.epub")))
        assertEquals(listOf("a/sub/deep.epub", "b/other.epub", "a/kept.epub", "a/new.epub").sorted(), merged.map { it.folder + "/" + it.name }.sorted())
    }

    @Test fun aSubfolderGoneFromTheDriveTakesItsBooksAway() {
        val known = listOf(book("a/gone", "x.epub"), book("a/gone/deeper", "y.epub"), book("a/sub", "z.epub"), book("b", "w.epub"))
        val merged = Folders.merge(known, "a", listOf("a/sub"), emptyList())
        assertEquals(listOf("a/sub/z.epub", "b/w.epub"), merged.map { it.folder + "/" + it.name }.sorted())
    }

    @Test fun theTopOfTheDriveMergesTheSameWay() {
        val known = listOf(book("", "top.epub"), book("gone", "x.epub"), book("stays", "y.epub"))
        val merged = Folders.merge(known, "", listOf("stays"), listOf(book("", "top2.epub")))
        assertEquals(listOf("/top2.epub", "stays/y.epub"), merged.map { it.folder + "/" + it.name }.sorted())
    }

    @Test fun booksFoundElsewhereAreNotMixedIn() {
        // a listing only ever carries the folder's own books; anything else in `found` is ignored
        val merged = Folders.merge(emptyList(), "a", emptyList(), listOf(book("a", "ok.epub"), book("b", "stray.epub")))
        assertEquals(listOf("a/ok.epub"), merged.map { it.folder + "/" + it.name })
    }
}
