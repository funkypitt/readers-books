package com.freedomfighter.readersbooks.remote

import com.freedomfighter.readersbooks.App
import com.freedomfighter.readersbooks.books.Entry
import com.freedomfighter.readersbooks.books.Highlight
import com.freedomfighter.readersbooks.books.HighlightFile
import com.freedomfighter.readersbooks.books.Highlights
import com.freedomfighter.readersbooks.books.Position
import com.freedomfighter.readersbooks.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

/**
 * The highlights of the books of the library: kept on the phone, folded with the file that lies
 * next to the book on the drive ("<book file>.highlights.json"), with the place the book was
 * left at, and written as a
 * note per book into the "Reader's Books" folder of the Reader's Notes folder when one is named.
 * All of it in the background; what could not be sent is sent the next time.
 */
class HighlightSync(private val app: App) {
    private val dir = File(app.filesDir, "highlights").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = HashSet<String>()
    private val again = HashSet<String>()

    /** The book that is open: its key and every highlight it has, removed ones included. */
    private val _current = MutableStateFlow<Pair<String, List<Highlight>>?>(null)
    val current: StateFlow<Pair<String, List<Highlight>>?> = _current

    fun key(entry: Entry): String =
        MessageDigest.getInstance("SHA-1").digest((entry.source ?: entry.id).toByteArray()).joinToString("") { "%02x".format(it) }

    private fun load(key: String): HighlightFile =
        runCatching { json.decodeFromString(HighlightFile.serializer(), File(dir, "$key.json").readText()) }.getOrDefault(HighlightFile())

    private fun save(key: String, highlights: List<Highlight>, dirty: Boolean) {
        runCatching { File(dir, "$key.json").writeText(json.encodeToString(HighlightFile.serializer(), HighlightFile(highlights = highlights, dirty = dirty))) }
    }

    /** A place read later on another device, for the page that is just being opened: the book's key and the place. */
    private val _arrived = MutableStateFlow<Pair<String, Position>?>(null)
    val arrived: StateFlow<Pair<String, Position>?> = _arrived
    fun taken() { _arrived.value = null }

    private fun positionOf(entry: Entry): Position? =
        if (entry.opened > 0L) Position(entry.chapter, entry.charOffset, entry.progress, entry.opened) else null

    /**
     * A book from the library was opened: what is known of its highlights, then what the drive
     * says. `leftAt` is the place the book had before this opening marked it as read just now.
     */
    fun open(entry: Entry, leftAt: Position?) {
        val k = key(entry)
        _current.value = k to load(k).highlights
        _arrived.value = null
        sync(entry, leftAt, opening = true)
    }

    fun change(entry: Entry, highlights: List<Highlight>) {
        val k = key(entry)
        save(k, highlights, true)
        _current.value = k to highlights
        sync(entry)
    }

    /** Where the book stands now, for the other devices: after a pause in the reading, and on leaving it. */
    fun place(id: String) { app.library.get(id)?.let { sync(it) } }

    /** At the start: every book of the shelf that came from the library, so that the shelf says how far each one is wherever it was last read. */
    fun shelf() { app.library.books.value.forEach { sync(it) } }

    /** The name of the book's file on the drive: kept when it was fetched, else read from its address, else from the list of the drive's books. */
    fun bookName(entry: Entry): String {
        entry.sourceName?.let { return it }
        val source = entry.source ?: return entry.title
        if (source.startsWith("http://") || source.startsWith("https://")) {
            val last = source.substringBefore('?').trimEnd('/').substringAfterLast('/')
            return runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        }
        return app.remote.index.value.books.firstOrNull { it.ref == source }?.name ?: entry.title
    }

    private class Place(val address: String, val auth: String)

    /** A book fetched from the drive: the one kind that has highlights, and a place that travels. */
    fun fromLibrary(entry: Entry): Boolean = entry.source?.let { it.startsWith("http://") || it.startsWith("https://") } == true

    /**
     * Where the highlights of a book and the place it was left at are kept for every device: in
     * a file next to the book. Null for a book that did not come from the library.
     */
    private fun sidecar(s: Settings, entry: Entry): Place? {
        if (!fromLibrary(entry) || !s.libraryConfigured) return null
        return Place(entry.source + Highlights.SUFFIX, Credentials.basic(s.username.trim(), s.password, Charsets.UTF_8))
    }

    private class NotesAccount(val folder: String, val auth: String)

    /** The folder of Reader's Notes and the login for it — the library's unless another is given. */
    private fun notes(s: Settings): NotesAccount? {
        val url = s.notesUrl.trim().ifEmpty { return null }
        val username = s.notesUsername.trim().ifEmpty { s.username.trim() }
        val password = s.notesPassword.ifEmpty { s.password }
        if (username.isEmpty() || password.isEmpty()) return null
        return NotesAccount(if (url.endsWith("/")) url else "$url/", Credentials.basic(username, password, Charsets.UTF_8))
    }

    private fun sync(entry: Entry, position: Position? = null, opening: Boolean = false) {
        val k = key(entry)
        val s = app.prefs.settings.value
        if (sidecar(s, entry) == null) return
        synchronized(running) {
            if (!running.add(k)) { again += k; return }
        }
        scope.launch {
            val before = load(k)
            runCatching {
                val place = sidecar(s, entry) ?: return@runCatching
                val r = Sidecar.sync(place.address, place.auth, bookName(entry), before.highlights, position ?: positionOf(entry))
                // The note is written again whenever the drive's highlights were: two devices that
                // met there at the same moment leave a note that lacks what one of them had.
                if (before.dirty || r.marks || r.merged != before.highlights) notes(s)?.let { n ->
                    Sidecar.writeNote(n.folder, n.auth, bookName(entry), r.merged, Locale.getDefault().language)
                }
                if (load(k).highlights == before.highlights) {      // nothing was changed here meanwhile
                    if (before.dirty || r.merged != before.highlights) save(k, r.merged, false)
                    val reading = _current.value?.first == k
                    if (r.merged != before.highlights && reading) _current.value = k to r.merged
                    r.theirs?.let { there ->
                        // Read later elsewhere: taken for the shelf, and for the page itself when the
                        // book is just being opened. A book being read here is the newer place.
                        if (!reading) app.library.savePosition(entry.id, there.chapter, there.charOffset, there.progress, there.modified)
                        else if (opening) _arrived.value = k to there
                    }
                }
            }
            val rerun = synchronized(running) { running -= k; again.remove(k) }
            if (rerun) app.library.get(entry.id)?.let { sync(it) }
        }
    }

    /** The same text as the note, for the share menu. */
    fun export(entry: Entry): String = Highlights.noteText(bookName(entry), load(key(entry)).highlights, Locale.getDefault().language)
}
