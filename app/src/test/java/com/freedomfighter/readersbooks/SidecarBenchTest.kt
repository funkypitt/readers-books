package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.books.Highlight
import com.freedomfighter.readersbooks.books.Highlights
import com.freedomfighter.readersbooks.books.Position
import com.freedomfighter.readersbooks.remote.Sidecar
import com.freedomfighter.readersbooks.remote.Unauthorized
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.nio.file.Files

/**
 * The phone's side of the synchronisation against a real WebDAV server (`rclone serve webdav`),
 * two devices sharing one drive — the cases of the desktop's tests/bench_sync.py, and a file
 * written by the desktop itself. Skipped where rclone is not installed.
 */
class SidecarBenchTest {
    private val book = "Zoé d'Arc + l'été: «essai».epub"
    private val password = "mot de passe é"
    private val auth = Credentials.basic("lecteur", password, Charsets.UTF_8)
    private val port = 8096
    private lateinit var drive: File
    private var server: Process? = null
    private val address get() = "http://127.0.0.1:$port/Livres/" + Sidecar.enc(book) + Highlights.SUFFIX
    private val notes get() = "http://127.0.0.1:$port/Mes%20notes/"

    /** One installation: what it holds, and the place it stands at. */
    private inner class Device {
        var all: List<Highlight> = emptyList()
        var position: Position? = null
        var dirty = false
        fun add(start: Int, text: String, comment: String = ""): String {
            val h = Highlights.new(0, start, start + text.length, text, comment); all = all + h; dirty = true; return h.id
        }
        fun edit(id: String, change: (Highlight) -> Highlight) { all = all.map { if (it.id == id) change(it).copy(modified = System.currentTimeMillis()) else it }; dirty = true }
        fun remove(id: String) = edit(id) { it.copy(deleted = true, text = "", comment = "") }
        fun readTo(chapter: Int, offset: Int, progress: Int) { position = Position(chapter, offset, progress, System.currentTimeMillis()) }
        /** As HighlightSync does it. */
        fun sync(key: String = auth, noteAuth: String = auth): Sidecar.Synced {
            val r = Sidecar.sync(address, key, book, all, position)
            if (dirty || r.marks || r.merged != all) Sidecar.writeNote(notes, noteAuth, book, r.merged, "fr")
            all = r.merged; dirty = false
            r.theirs?.let { position = it }
            return r
        }
        fun live() = Highlights.live(all).map { it.text to it.comment }
    }

    private fun serve() {
        server = ProcessBuilder("rclone", "serve", "webdav", drive.path, "--addr", "127.0.0.1:$port", "--user", "lecteur", "--pass", password)
            .redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        repeat(60) { if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return; Thread.sleep(100) }
    }

    @Before fun start() {
        assumeTrue("rclone is needed to serve the test drive", runCatching { ProcessBuilder("rclone", "version").start().waitFor() == 0 }.getOrDefault(false))
        drive = Files.createTempDirectory("readers-books-bench").toFile()
        File(drive, "Livres").mkdirs(); File(drive, "Mes notes").mkdirs()
        File(drive, "Livres/$book").writeText("a book")
        serve()
    }

    @After fun stop() { server?.destroy(); server?.waitFor(); if (::drive.isInitialized) drive.deleteRecursively() }

    private fun tick() = Thread.sleep(3)
    private fun note(): String? = File(drive, "Mes notes/${Highlights.BOOKS_FOLDER}/" + Highlights.noteFileName(book)).takeIf { it.exists() }?.readText()
    private fun onDrive(): String? = File(drive, "Livres/$book${Highlights.SUFFIX}").takeIf { it.exists() }?.readText()
    private fun q(text: String) = "«\u00A0$text\u00A0»"

    @Test fun aHighlightCrossesWithItsCommentAndTheNoteFollows() {
        val a = Device(); val b = Device()
        val id = a.add(10, "un passage")
        a.sync(); b.sync()
        assertEquals(listOf("un passage" to ""), b.live())
        assertEquals("Zoé d'Arc + l'été: «essai»\n\n${q("un passage")}\n", note())
        tick(); b.edit(id) { it.copy(comment = "Bien vu.") }
        b.sync(); a.sync()
        assertEquals(listOf("un passage" to "Bien vu."), a.live())
        assertEquals("Zoé d'Arc + l'été: «essai»\n\n${q("un passage")}\n\nBien vu.\n", note())
    }

    @Test fun aRemovalCrossesStaysAndTakesTheNoteWithIt() {
        val a = Device(); val b = Device()
        val keep = a.add(10, "à garder"); val gone = a.add(50, "à retirer")
        a.sync(); b.sync(); tick()
        b.remove(gone); b.sync(); a.sync(); b.sync(); a.sync()
        assertEquals(listOf("à garder" to ""), a.live())
        assertEquals(a.live(), b.live())
        assertFalse(note()!!.contains("à retirer"))
        tick(); a.remove(keep); a.sync()
        assertNull(note())
        assertTrue(onDrive()!!.contains("\"deleted\": true"))
    }

    @Test fun twoDevicesCutOffThenBack() {
        val a = Device(); val b = Device()
        server?.destroy(); server?.waitFor()
        a.add(200, "plus loin (A)"); b.add(20, "au début (B)", "note de B")
        try { a.sync(); fail("a sync with no server should fail") } catch (e: java.io.IOException) { a.dirty = true }
        serve()
        a.sync(); b.sync(); a.sync()
        val both = listOf("au début (B)" to "note de B", "plus loin (A)" to "")
        assertEquals(both, a.live()); assertEquals(both, b.live())
        assertEquals("Zoé d'Arc + l'été: «essai»\n\n${q("au début (B)")}\n\nnote de B\n\n${q("plus loin (A)")}\n", note())
    }

    @Test fun theLaterOfTwoChangesWins() {
        val a = Device(); val b = Device()
        val id = a.add(10, "un passage", "premier jet")
        a.sync(); b.sync(); tick()
        a.edit(id) { it.copy(comment = "version de A") }; tick()
        b.edit(id) { it.copy(comment = "version de B, plus tard") }
        b.sync(); a.sync(); b.sync()
        assertEquals(listOf("un passage" to "version de B, plus tard"), a.live()); assertEquals(a.live(), b.live())
        tick(); a.remove(id); tick(); b.edit(id) { it.copy(comment = "commenté après le retrait") }
        a.sync(); b.sync(); a.sync()
        assertEquals(listOf("un passage" to "commenté après le retrait"), a.live()); assertEquals(a.live(), b.live())
    }

    @Test fun twoDevicesAtTheSameMoment() {
        val a = Device(); val b = Device()
        a.add(10, "celui de A"); b.add(60, "celui de B")
        a.sync()
        val mine = onDrive()!!
        b.sync()
        // A's file put back over B's, as when A wrote a moment after B read
        okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(address).header("Authorization", auth)
            .put(okhttp3.RequestBody.create(null, mine.toByteArray())).build()).execute().close()
        b.sync(); a.sync()
        val both = listOf("celui de A" to "", "celui de B" to "")
        assertEquals(both, a.live()); assertEquals(both, b.live())
        assertEquals("Zoé d'Arc + l'été: «essai»\n\n${q("celui de A")}\n\n${q("celui de B")}\n", note())
    }

    @Test fun thePlaceFollowsTheReaderAndNeverGoesBack() {
        val a = Device(); val b = Device()
        a.readTo(3, 1200, 41); a.sync(); b.sync()
        assertEquals(Triple(3, 1200, 41), b.position!!.let { Triple(it.chapter, it.charOffset, it.progress) })
        tick(); b.readTo(5, 80, 63); b.sync(); a.sync()
        assertEquals(5, a.position!!.chapter)
        val stale = onDrive()!!
        tick(); a.readTo(6, 0, 70); a.sync()
        okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(address).header("Authorization", auth)
            .put(okhttp3.RequestBody.create(null, stale.toByteArray())).build()).execute().close()
        assertNull(a.sync().theirs)
        assertEquals(6, a.position!!.chapter)
        assertTrue(onDrive()!!.contains("\"chapter\": 6"))
        // a place alone never rewrites the note
        assertNull(note())
    }

    @Test fun whatTheDesktopWroteIsRead() {
        File(drive, "Livres/$book${Highlights.SUFFIX}").writeText("""{
 "format": "readers-highlights", "version": 1, "book": "x.epub",
 "highlights": [{"id": "abc123", "chapter": 0, "start": 113, "end": 156, "text": "faisait briller les pavés", "comment": "du bureau", "created": 5, "modified": 6, "deleted": false}],
 "position": {"chapter": 1, "charOffset": 4375, "progress": 23, "modified": 1791623523695}
}""")
        server?.destroy(); server?.waitFor(); serve()
        val phone = Device()
        val r = phone.sync()
        assertEquals(listOf("faisait briller les pavés" to "du bureau"), phone.live())
        assertEquals(1 to 4375, r.theirs!!.let { it.chapter to it.charOffset })
        assertFalse(onDrive()!!.contains("dirty\": true"))
    }

    @Test fun aFileThatIsNotOursAndALoginRefused() {
        File(drive, "Livres/$book${Highlights.SUFFIX}").writeText("not json at all")
        server?.destroy(); server?.waitFor(); serve()
        val a = Device()
        a.add(10, "le nôtre")
        try { a.sync(key = Credentials.basic("lecteur", "faux", Charsets.UTF_8)); fail("the library should have refused") } catch (e: Unauthorized) { a.dirty = true }
        assertEquals("not json at all", onDrive())
        try { a.sync(noteAuth = Credentials.basic("lecteur", "faux", Charsets.UTF_8)); fail("the notes folder should have refused") } catch (e: Unauthorized) { a.dirty = true }
        assertTrue(onDrive()!!.contains(Highlights.FORMAT))        // the highlights went up all the same
        assertNull(note())
        a.sync()
        assertEquals("Zoé d'Arc + l'été: «essai»\n\n${q("le nôtre")}\n", note())
    }
}
