package com.freedomfighter.readersbooks.remote

import com.freedomfighter.readersbooks.books.Highlight
import com.freedomfighter.readersbooks.books.HighlightFile
import com.freedomfighter.readersbooks.books.Highlights
import com.freedomfighter.readersbooks.books.Position
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * What goes over the network for a book's highlights: the file next to the book, and the book's
 * note in the Reader's Notes folder. Nothing here knows the phone, so that it can be run against
 * a real server from a plain test — the same cases as the desktop's tests/bench_sync.py.
 */
object Sidecar {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    class Synced(val merged: List<Highlight>, val marks: Boolean, val theirs: Position?)

    fun enc(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /**
     * Ours and the drive's folded together and written back where they differ: the highlights one
     * by one, and the place in the book, where the one read last wins. `marks` = the drive's
     * highlights had to be written; `theirs` = the drive's place, when it is the newer one. Blocking.
     */
    fun sync(address: String, auth: String, bookName: String, ours: List<Highlight>, position: Position?): Synced {
        var missing = false
        val file = http.newCall(Request.Builder().url(address).get().header("Authorization", auth).build()).execute().use { r ->
            if (r.code == 404) { missing = true; null }
            else {
                check(r.code)
                runCatching { json.decodeFromString(HighlightFile.serializer(), r.body?.string() ?: "") }.getOrNull()?.takeIf { it.format == Highlights.FORMAT }
            }
        }
        val theirs = file?.highlights ?: emptyList()
        val there = file?.position
        val merged = Highlights.merge(ours, theirs)
        val marks = merged != Highlights.merge(theirs, emptyList()) && (merged.isNotEmpty() || !missing)
        val oursNewer = position != null && (there == null || position.modified > there.modified)
        val theirsNewer = there != null && (position == null || there.modified > position.modified)
        if (!marks && !oursNewer) return Synced(merged, false, there.takeIf { theirsNewer })
        // dirty is the phone's own business: it never travels
        val body = json.encodeToString(HighlightFile.serializer(), HighlightFile(book = bookName, highlights = merged, position = if (oursNewer) position else there))
        http.newCall(Request.Builder().url(address).put(body.toRequestBody("application/json".toMediaType())).header("Authorization", auth).build())
            .execute().use { check(it.code) }
        return Synced(merged, marks, there.takeIf { theirsNewer })
    }

    /** The book's note in Reader's Notes: written when there is something to say, removed when nothing is left. Blocking. */
    fun writeNote(notesFolder: String, auth: String, bookName: String, all: List<Highlight>, language: String) {
        val folder = notesFolder + enc(Highlights.BOOKS_FOLDER) + "/"
        val target = folder + enc(Highlights.noteFileName(bookName))
        if (Highlights.live(all).isEmpty()) {
            http.newCall(Request.Builder().url(target).delete().header("Authorization", auth).build()).execute().use { if (it.code != 404) check(it.code) }
            return
        }
        http.newCall(Request.Builder().url(folder).method("MKCOL", null).header("Authorization", auth).build()).execute()
            .use { if (it.code != 201 && it.code != 405 && it.code != 301) check(it.code) }     // made, or there already
        val text = Highlights.noteText(bookName, all, language)
        http.newCall(Request.Builder().url(target).put(text.toRequestBody("text/plain; charset=utf-8".toMediaType())).header("Authorization", auth).build())
            .execute().use { check(it.code) }
    }
}
