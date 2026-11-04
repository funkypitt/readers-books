package com.freedomfighter.readersbooks.remote

import com.freedomfighter.readersbooks.books.Highlight
import com.freedomfighter.readersbooks.books.HighlightFile
import com.freedomfighter.readersbooks.books.Highlights
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The highlights kept on the phone: a file per book, `<key>.json`, of the same form as the one
 * that lies next to a book of the library on the drive (format "readers-highlights", version 1),
 * plus `dirty` — not yet where it goes (the drive's file, or the note in Reader's Notes), which
 * readers of that form leave aside. For a book of the shelf alone, it is the only copy.
 * Nothing here knows Android, so that it can be tried in a plain test.
 */
class HighlightStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    init { dir.mkdirs() }

    fun file(key: String) = File(dir, "$key.json")

    fun load(key: String): HighlightFile =
        runCatching { json.decodeFromString(HighlightFile.serializer(), file(key).readText()) }.getOrDefault(HighlightFile())

    fun save(key: String, highlights: List<Highlight>, dirty: Boolean, book: String = "") {
        runCatching { file(key).writeText(json.encodeToString(HighlightFile.serializer(), HighlightFile(book = book, highlights = highlights, dirty = dirty))) }
    }

    fun delete(key: String) { file(key).delete() }

    /**
     * The highlights of `from` go to `to`, folded into what `to` already has by the drive's rule
     * (per highlight, the one changed last; a removal stays a removal), marked as still to be
     * sent; `from` is gone. Returns what `to` now holds, or null when `from` had nothing.
     */
    fun move(from: String, to: String, book: String): List<Highlight>? {
        if (from == to) return null
        val mine = load(from).highlights
        delete(from)
        if (mine.isEmpty()) return null
        val merged = Highlights.merge(mine, load(to).highlights)
        save(to, merged, true, book)
        return merged
    }
}
