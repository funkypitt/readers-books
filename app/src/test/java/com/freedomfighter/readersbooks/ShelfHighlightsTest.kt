package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.books.BookFormat
import com.freedomfighter.readersbooks.books.BookIdentity
import com.freedomfighter.readersbooks.books.Entry
import com.freedomfighter.readersbooks.books.HighlightFile
import com.freedomfighter.readersbooks.books.Highlights
import com.freedomfighter.readersbooks.remote.HighlightStore
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The highlights of a book of the shelf alone, kept on the phone in the contract's form, and
 * taken into the library's file when the same book comes from the library (or is sent to it).
 */
class ShelfHighlightsTest {
    private val lenient = Json { ignoreUnknownKeys = true }
    private fun tmp(): File = Files.createTempDirectory("shelf-highlights").toFile()

    @Test fun aShelfBookKeepsItsHighlightsInTheContractsForm() {
        val store = HighlightStore(tmp())
        val h = Highlights.new(2, 10, 18, "un passage", "vu")
        store.save("k", listOf(h), dirty = true, book = "Livre.epub")
        // what another device's reader of that form sees: format, version, book, highlights
        val o = JSONObject(store.file("k").readText())
        assertEquals(Highlights.FORMAT, o.getString("format"))
        assertEquals(1, o.getInt("version"))
        assertEquals("Livre.epub", o.getString("book"))
        val first = o.getJSONArray("highlights").getJSONObject(0)
        assertEquals(listOf("chapter", "comment", "created", "deleted", "end", "id", "modified", "start", "text"), first.keys().asSequence().sorted().toList())
        assertEquals(10, first.getInt("start")); assertEquals("un passage", first.getString("text"))
        // read back by the drive's own parser, which leaves `dirty` aside
        val parsed = lenient.decodeFromString(HighlightFile.serializer(), store.file("k").readText())
        assertEquals(listOf(h), parsed.highlights)
        assertTrue(store.load("k").dirty)
    }

    @Test fun theHighlightsOfAShelfBookGoToTheLibraryBookAndFoldWithItsOwn() {
        val store = HighlightStore(tmp())
        val mine = Highlights.new(0, 1, 5, "mine")
        val both = Highlights.new(1, 3, 9, "both")
        val removedHere = both.copy(deleted = true, text = "", modified = both.modified + 10)
        val theirs = Highlights.new(3, 0, 4, "drive")
        store.save("shelf", listOf(mine, removedHere), dirty = false)
        store.save("library", listOf(both, theirs), dirty = false)
        val merged = store.move("shelf", "library", "Book.epub")!!
        assertEquals(listOf(mine.id, both.id, theirs.id), merged.map { it.id })
        assertTrue(merged.first { it.id == both.id }.deleted)                  // the later removal wins
        assertFalse(store.file("shelf").exists())
        val now = store.load("library")
        assertEquals(merged, now.highlights)
        assertTrue(now.dirty)                                                   // still to be sent
        assertEquals("Book.epub", now.book)
    }

    @Test fun aBookWithoutHighlightsMovesNothing() {
        val store = HighlightStore(tmp())
        val theirs = Highlights.new(3, 0, 4, "drive")
        store.save("library", listOf(theirs), dirty = false)
        assertNull(store.move("shelf", "library", "Book.epub"))
        assertNull(store.move("library", "library", "Book.epub"))
        assertEquals(listOf(theirs), store.load("library").highlights)
        assertFalse(store.load("library").dirty)
    }

    private fun epub(dir: File, name: String, uid: String, title: String, extra: String = ""): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/epub+zip".toByteArray())
            z.putNextEntry(ZipEntry("META-INF/container.xml"))
            z.write("""<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            z.putNextEntry(ZipEntry("OEBPS/content.opf"))
            z.write("""<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="isbn">978-0</dc:identifier><dc:identifier id="pub-id">$uid</dc:identifier><dc:title>$title</dc:title>$extra</metadata></package>""".toByteArray())
        }
        return f
    }

    private fun entry(file: File, title: String, format: BookFormat = BookFormat.EPUB) =
        Entry(file.name, file.name, title, format, 0L, fingerprint = BookIdentity.fingerprint(file), bookId = BookIdentity.epubId(file, format))

    @Test fun theSameBookIsTheSameFileOrTheSameEpubIdentifierAndTitle() {
        val d = tmp()
        val a = epub(d, "a.epub", "urn:uuid:1234", "Le Livre")
        val copy = File(d, "copy.epub").also { a.copyTo(it) }
        val rewritten = epub(d, "b.epub", "urn:uuid:1234", "Le Livre", "<dc:subject>x</dc:subject>")
        val otherTitle = epub(d, "c.epub", "urn:uuid:1234", "Un autre")
        val otherBook = epub(d, "d.epub", "urn:uuid:9999", "Le Livre")
        assertEquals("urn:uuid:1234", BookIdentity.epubId(a, BookFormat.EPUB))
        assertEquals(64, BookIdentity.fingerprint(a).length)
        assertTrue(BookIdentity.same(entry(a, "Le Livre"), entry(copy, "Le Livre")))            // same bytes
        assertTrue(BookIdentity.same(entry(a, "Le Livre"), entry(rewritten, "Le Livre")))       // same identifier and title
        assertFalse(BookIdentity.same(entry(a, "Le Livre"), entry(otherTitle, "Un autre")))     // a stamp shared by many books
        assertFalse(BookIdentity.same(entry(a, "Le Livre"), entry(otherBook, "Le Livre")))      // a title alone is not enough
        // other formats: the bytes only
        val t1 = File(d, "x.fb2").apply { writeText("<FictionBook>un</FictionBook>") }
        val t2 = File(d, "y.fb2").apply { writeText("<FictionBook>deux</FictionBook>") }
        assertEquals("", BookIdentity.epubId(t1, BookFormat.FB2))
        assertFalse(BookIdentity.same(entry(t1, "T", BookFormat.FB2), entry(t2, "T", BookFormat.FB2)))
        // a copy that never learnt its fingerprint is never "the same" by accident
        assertFalse(BookIdentity.same(Entry("1", "1.fb2", "T", BookFormat.FB2, 0L), Entry("2", "2.fb2", "T", BookFormat.FB2, 0L)))
    }

    @Test fun thePackageIdentifierIsTheOneThePackageNames() {
        assertEquals("abc", BookIdentity.packageId("""<opf:package unique-identifier='bookid'><dc:identifier opf:scheme="ISBN">1</dc:identifier><dc:identifier id='bookid'> abc </dc:identifier></opf:package>"""))
        assertEquals("", BookIdentity.packageId("""<package><dc:identifier id="x">1</dc:identifier></package>"""))
    }
}
