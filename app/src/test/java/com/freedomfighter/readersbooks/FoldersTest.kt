package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.data.BookSort
import com.freedomfighter.readersbooks.data.FolderSort
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

    @Test fun aFolderLeftOutIsOutWithWhatIsInsideWhateverIsChosen() {
        val excluded = listOf("Books/Old")
        assertTrue(Folders.leftOut(excluded, "Books/Old"))
        assertTrue(Folders.leftOut(excluded, "Books/Old/Deeper"))
        assertFalse(Folders.leftOut(excluded, "Books/Older"))   // a prefix of the name is not inside
        assertFalse(Folders.leftOut(excluded, "Books"))
        assertFalse(Folders.leftOut(emptyList(), "Books/Old"))
        // the whole drive, less that folder
        assertTrue(Folders.inLibrary(emptyList(), "Books", excluded))
        assertFalse(Folders.inLibrary(emptyList(), "Books/Old/Deeper", excluded))
        // a chosen folder with a part of it left out
        assertTrue(Folders.inLibrary(listOf("Books"), "Books/Maigret", excluded))
        assertFalse(Folders.inLibrary(listOf("Books"), "Books/Old", excluded))
        // even the transcripts' folder, when it is the one left out
        assertFalse(Folders.inLibrary(listOf("Books"), "$TRANSCRIPTS_FOLDER/x", listOf(TRANSCRIPTS_FOLDER)))
    }

    // ---- grouping by top folder, and the orders of folders and books

    private fun book(folder: String, name: String, modified: Long) = RemoteBook(name, folder, "ref:$folder/$name", 1L, modified)
    private val drive = listOf(
        book("Books/novels/Roald Dahl", "Matilda.epub", 30L), book("Books/novels", "Dune.epub", 10L),
        book("Books/science", "Cosmos.epub", 50L), book("Books/science", "Carl Sagan - Contact.epub", 20L),
        book("transcriptions/Huberman", "Sleep.epub", 60L), book("", "Loose.epub", 40L))

    @Test fun everyFolderOrTheTopOnes() {
        assertEquals(listOf("", "Books/novels", "Books/novels/Roald Dahl", "Books/science", "transcriptions/Huberman"),
            Folders.sortFolders(Folders.grouped(drive, false), FolderSort.NAME).map { it.path })
        val top = Folders.sortFolders(Folders.grouped(drive, true), FolderSort.NAME)
        assertEquals(listOf("", "Books", "transcriptions"), top.map { it.path })
        val books = top.first { it.path == "Books" }
        assertEquals(4, books.count); assertEquals(3, books.subfolders); assertEquals(50L, books.modified)
        assertEquals(1, top.first { it.path == "" }.subfolders)      // the top of the drive stands by itself
    }

    @Test fun foldersInEveryOrder() {
        val top = Folders.grouped(drive, true)
        assertEquals(listOf("transcriptions", "Books", ""), Folders.sortFolders(top, FolderSort.NEWEST).map { it.path })
        assertEquals(listOf("Books", "", "transcriptions"), Folders.sortFolders(top, FolderSort.COUNT).map { it.path })
    }

    @Test fun aFolderAloneOrWithWhatIsInside() {
        assertEquals(listOf("Dune.epub"), drive.filter { it.folder == "Books/novels" }.map { it.name })
        assertEquals(setOf("Dune.epub", "Matilda.epub"), drive.filter { Folders.under("Books/novels", it.folder) }.map { it.name }.toSet())
        assertEquals("novels/Roald Dahl", Folders.relative("Books", "Books/novels/Roald Dahl"))
        assertEquals("", Folders.relative("Books", "Books"))
        assertEquals("Books/science", Folders.relative("", "Books/science"))
        assertEquals("Books", Folders.top("Books/novels/Roald Dahl")); assertEquals("", Folders.top(""))
    }

    @Test fun booksInEveryOrder() {
        val b = drive.filter { Folders.under("Books", it.folder) }
        assertEquals(listOf("Cosmos.epub", "Matilda.epub", "Carl Sagan - Contact.epub", "Dune.epub"), Folders.sortBooks(b, BookSort.NEWEST).map { it.name })
        assertEquals(listOf("Dune.epub", "Carl Sagan - Contact.epub", "Matilda.epub", "Cosmos.epub"), Folders.sortBooks(b, BookSort.OLDEST).map { it.name })
        assertEquals(listOf("Carl Sagan - Contact.epub", "Cosmos.epub", "Dune.epub", "Matilda.epub"), Folders.sortBooks(b, BookSort.NAME).map { it.name })
        // the author: before " - " in the name, else the folder's name
        assertEquals(listOf("Carl Sagan - Contact.epub", "Dune.epub", "Matilda.epub", "Cosmos.epub"), Folders.sortBooks(b, BookSort.AUTHOR).map { it.name })
        val opened = mapOf("ref:Books/novels/Dune.epub" to 5L, "ref:Books/science/Cosmos.epub" to 9L)
        assertEquals(listOf("Cosmos.epub", "Dune.epub", "Carl Sagan - Contact.epub", "Matilda.epub"), Folders.sortBooks(b, BookSort.OPENED, opened).map { it.name })
    }

    @Test fun searchFindsEveryWordInNameAuthorOrFolder() {
        val b = listOf(book("Books/1950s", "Simenon - Maigret et la vieille dame.epub"), book("Books/Élie", "Les Thibault.epub"), book("", "Dune.epub"))
        assertEquals(emptyList<String>(), Folders.search(b, "").map { it.name })
        assertEquals(emptyList<String>(), Folders.search(b, "   ").map { it.name })
        assertEquals(listOf("Simenon - Maigret et la vieille dame.epub"), Folders.search(b, "MAIGRET 1950").map { it.name })   // case, folder
        assertEquals(listOf("Simenon - Maigret et la vieille dame.epub"), Folders.search(b, "simenon dame").map { it.name })   // author, two words
        assertEquals(listOf("Les Thibault.epub"), Folders.search(b, "elie").map { it.name })   // accent in the folder
        assertEquals(listOf("Les Thibault.epub"), Folders.search(b, "Thibault, les").map { it.name })   // punctuation
        assertEquals(listOf("Dune.epub"), Folders.search(b, "dune").map { it.name })   // a book of the root
        assertEquals(emptyList<String>(), Folders.search(b, "maigret dune").map { it.name })   // every word, in one book
        assertEquals("l etranger d albert camus", Folders.plain("L’Étranger — d'Albert_Camus."))
    }
}
