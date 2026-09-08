package com.freedomfighter.readersbooks.books

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A book on the shelf: its file in app storage, its title, and where the reader left it. */
@Serializable
data class Entry(
    val id: String,
    val fileName: String,
    val title: String,
    val format: BookFormat,
    val added: Long,
    val opened: Long = 0L,
    val chapter: Int = 0,
    val charOffset: Int = 0,
    /** Position as a share of the whole text, for the shelf line. */
    val progress: Int = 0
)

@Serializable
private data class LibraryState(val books: List<Entry> = emptyList())

/**
 * Books are copied into app storage when opened (no lingering document permissions). The
 * reading position is one (chapter, character offset) pair per book, independent of screen
 * and text size.
 */
class Library(private val context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val dir = File(context.filesDir, "books").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val _books = MutableStateFlow(load())
    /** Most recently opened first. */
    val books: StateFlow<List<Entry>> = _books
    private val cache = HashMap<String, Book>()

    private fun load(): List<Entry> = runCatching { json.decodeFromString(LibraryState.serializer(), file.readText()).books }.getOrDefault(emptyList())

    @Synchronized
    private fun update(transform: (List<Entry>) -> List<Entry>) {
        val next = transform(_books.value).sortedWith(compareByDescending<Entry> { maxOf(it.opened, it.added) })
        _books.value = next
        runCatching { file.writeText(json.encodeToString(LibraryState.serializer(), LibraryState(next))) }
    }

    fun get(id: String): Entry? = _books.value.firstOrNull { it.id == id }

    fun savePosition(id: String, chapter: Int, charOffset: Int, progress: Int) = update { l ->
        l.map { if (it.id == id) it.copy(chapter = chapter, charOffset = charOffset, progress = progress, opened = System.currentTimeMillis()) else it }
    }

    fun touch(id: String) = update { l -> l.map { if (it.id == id) it.copy(opened = System.currentTimeMillis()) else it } }

    fun remove(id: String) = update { l ->
        l.firstOrNull { it.id == id }?.let { File(dir, it.fileName).delete(); cache.remove(it.fileName) }
        l.filter { it.id != id }
    }

    /** Copy the chosen document onto the shelf, parse it once to validate. Blocking. */
    fun import(uri: Uri): Result<Entry> = runCatching {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "book"
        val tmp = File(dir, "import.tmp")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("cannot read")
        val format = BookParser.detect(tmp, displayName)
        val fallbackTitle = displayName.substringBeforeLast('.').replace('_', ' ')
        val book = BookParser.parse(tmp, format, fallbackTitle)
        val id = System.currentTimeMillis().toString(36)
        val fileName = "$id." + format.name.lowercase()
        val target = File(dir, fileName)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        cache[fileName] = book
        // the same book again replaces the old copy but keeps its place
        val same = _books.value.firstOrNull { it.title == book.title && it.format == format }
        val entry = Entry(id, fileName, book.title, format, added = System.currentTimeMillis(), opened = System.currentTimeMillis(), chapter = same?.chapter ?: 0, charOffset = same?.charOffset ?: 0, progress = same?.progress ?: 0)
        update { l -> l.filter { it.id != same?.id }.also { same?.let { s -> File(dir, s.fileName).delete() } } + entry }
        entry
    }

    /** Parse (or fetch from cache) a book. Blocking; call off the main thread. */
    fun book(e: Entry): Book {
        cache[e.fileName]?.let { return it }
        val book = BookParser.parse(File(dir, e.fileName), e.format, e.title)
        cache[e.fileName] = book
        return book
    }
}
