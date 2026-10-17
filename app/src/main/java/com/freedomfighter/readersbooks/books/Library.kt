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
    val progress: Int = 0,
    /** A magazine issue (articles with a contents page) rather than a book. */
    val magazine: Boolean = false,
    /** Where the library fetched it from (its reference on the drive), so the shelf copy is found again. */
    val source: String? = null,
    /**
     * The name of its file: on the drive, for a book of the library (the name its highlights and
     * its note go by), else as it was when it was opened, for the day it is sent to the library.
     */
    val sourceName: String? = null,
    /** The drive's last change of the file when it was fetched: a later one on the drive means a newer copy there. */
    val sourceModified: Long = 0L,
    /** Bytes of the file on the phone. */
    val size: Long = 0L,
    /** Kept on the phone: left where it is when the shelf is cleared of books not read for a while. */
    val kept: Boolean = false
) {
    /** A book fetched from the drive (or sent to it): the one kind that has highlights, a place that travels, and a copy to go back to. */
    val fromLibrary: Boolean get() = source?.let { it.startsWith("http://") || it.startsWith("https://") } == true
}

@Serializable
private data class LibraryState(val books: List<Entry> = emptyList())

/** Parsed books and the pictures of books held in memory at once: the one being read and the last few opened. */
private const val HELD = 3

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
    private val cache = object : LinkedHashMap<String, Book>(HELD + 1, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Book>) = size > HELD }
    private val images = object : LinkedHashMap<String, ImageStore>(HELD + 1, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageStore>) = size > HELD }
    /** Told of every book that leaves the shelf, so that what else was kept for it goes too. */
    var onRemoved: ((Entry) -> Unit)? = null

    // entries from before sizes were kept learn theirs once
    private fun load(): List<Entry> = runCatching { json.decodeFromString(LibraryState.serializer(), file.readText()).books }.getOrDefault(emptyList())
        .map { if (it.size == 0L) it.copy(size = File(dir, it.fileName).length()) else it }

    @Synchronized
    private fun update(transform: (List<Entry>) -> List<Entry>) {
        val next = transform(_books.value).sortedWith(compareByDescending<Entry> { maxOf(it.opened, it.added) })
        _books.value = next
        context.contentResolver.notifyChange(android.net.Uri.parse("content://com.freedomfighter.readersbooks/books"), null)
        runCatching { com.freedomfighter.readersbooks.widget.BookWidgets.refresh(context.applicationContext) }
        runCatching { file.writeText(json.encodeToString(LibraryState.serializer(), LibraryState(next))) }
    }

    fun get(id: String): Entry? = _books.value.firstOrNull { it.id == id }
    fun bySource(source: String): Entry? = _books.value.firstOrNull { it.source == source }

    /** `at` is given for a place read on another device: it keeps the date it was read. */
    fun savePosition(id: String, chapter: Int, charOffset: Int, progress: Int, at: Long = System.currentTimeMillis()) = update { l ->
        l.map { if (it.id == id) it.copy(chapter = chapter, charOffset = charOffset, progress = progress, opened = at) else it }
    }

    /** A book of the shelf that was just sent to the drive: from now on a book of the library, by that address and that file name. */
    fun link(id: String, source: String, sourceName: String) = update { l -> l.map { if (it.id == id) it.copy(source = source, sourceName = sourceName) else it } }

    fun touch(id: String) = update { l -> l.map { if (it.id == id) it.copy(opened = System.currentTimeMillis()) else it } }

    /** Kept on the phone, or no longer. */
    fun setKept(id: String, kept: Boolean) = update { l -> l.map { if (it.id == id) it.copy(kept = kept) else it } }

    fun remove(id: String) {
        var gone: Entry? = null
        update { l ->
            gone = l.firstOrNull { it.id == id }?.also { File(dir, it.fileName).delete(); synchronized(cache) { cache.remove(it.fileName) }; synchronized(images) { images.remove(it.fileName) } }
            l.filter { it.id != id }
        }
        gone?.let { onRemoved?.invoke(it) }
    }

    /** Copy the chosen document onto the shelf, parse it once to validate. Blocking. */
    fun import(uri: Uri): Result<Entry> = runCatching {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "book"
        val tmp = File(dir, "import.tmp")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("cannot read")
        importFile(tmp, displayName, null).getOrThrow()
    }

    /**
     * A library book already fetched to a file: moved onto the shelf, parsed once to validate.
     * `sourceModified` is the drive's date for it, `kept` asks for it to stay on the phone. Blocking.
     */
    fun importFile(downloaded: File, displayName: String, source: String?, sourceModified: Long = 0L, kept: Boolean = false): Result<Entry> = runCatching {
        val tmp = File(dir, "import.tmp")
        if (downloaded != tmp) { if (!downloaded.renameTo(tmp)) { downloaded.copyTo(tmp, overwrite = true); downloaded.delete() } }
        val format = BookParser.detect(tmp, displayName)
        val fallbackTitle = displayName.substringBeforeLast('.').replace('_', ' ')
        val book = BookParser.parse(tmp, format, fallbackTitle)
        val id = System.currentTimeMillis().toString(36)
        val fileName = "$id." + format.name.lowercase()
        val target = File(dir, fileName)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        synchronized(cache) { cache[fileName] = book }
        // the same book again (from the same place on the drive, else by title) replaces the old copy but keeps its place
        val same = source?.let { s -> _books.value.firstOrNull { it.source == s } } ?: _books.value.firstOrNull { it.title == book.title && it.format == format }
        val entry = Entry(id, fileName, book.title, format, added = System.currentTimeMillis(), opened = same?.opened ?: 0L, chapter = same?.chapter ?: 0, charOffset = same?.charOffset ?: 0, progress = same?.progress ?: 0, magazine = book.magazine != null, source = source ?: same?.source, sourceName = if (source != null || same?.source == null) displayName else same.sourceName,
            sourceModified = if (source != null) sourceModified else same?.sourceModified ?: 0L, size = target.length(), kept = kept || same?.kept == true)
        update { l -> l.filter { it.id != same?.id }.also { same?.let { s -> File(dir, s.fileName).delete() } } + entry }
        entry
    }

    /** Parse (or fetch from cache) a book. Blocking; call off the main thread. */
    fun book(e: Entry): Book {
        synchronized(cache) { cache[e.fileName] }?.let { return it }
        val book = BookParser.parse(File(dir, e.fileName), e.format, e.title)
        synchronized(cache) { cache[e.fileName] = book }
        // entries from before magazines were told apart learn what they are on first open
        if ((book.magazine != null) != e.magazine) update { l -> l.map { if (it.id == e.id) it.copy(magazine = book.magazine != null) else it } }
        return book
    }

    fun file(e: Entry): File = File(dir, e.fileName)

    fun images(e: Entry): ImageStore = synchronized(images) { images.getOrPut(e.fileName) { ImageStore(file(e)) } }

    companion object {
        /**
         * The books that may leave the shelf by themselves: those of the library (the drive has
         * them, with their highlights and their place), not kept on the phone, and neither
         * opened nor added for `days` days. Never anything with `days` at 0.
         */
        fun stale(entries: List<Entry>, days: Int, now: Long = System.currentTimeMillis()): List<Entry> {
            if (days <= 0) return emptyList()
            val before = now - days * 24 * 60 * 60 * 1000L
            return entries.filter { it.fromLibrary && !it.kept && maxOf(it.opened, it.added) < before }
        }
    }
}
