package com.freedomfighter.readersbooks.data

import org.json.JSONObject

/**
 * The Reader's credentials file, the same for every Reader's app: one JSON file,
 * `{"format": "readers-credentials", "version": 1, "<app>": {…}}`, one section per app. Carrying it
 * to another phone sets the library up in one step. Only our section and the keys we know are
 * read; the look is never in it. It holds the password.
 */
object Credentials {
    const val FORMAT = "readers-credentials"
    const val SECTION = "readers-books"
    /** Magazine Reader reaches the same kind of drive: its link or account does for us too. */
    const val FALLBACK = "magazine-reader"
    /** Scanner, Notes and Recorder keep a WebDAV account under "server": the drive and login do, their folder does not. */
    val SERVER_FALLBACKS = listOf("readers-scanner", "readers-notes", "readers-recorder")
    const val FILE_NAME = "readers-credentials-books.json"

    /** What a file brings; null = not in the file, keep what is set. */
    data class Account(val url: String?, val username: String?, val password: String?) {
        val isEmpty get() = url == null && username == null && password == null
    }
    data class Imported(val account: Account, val fromFallback: Boolean)

    class NotCredentials : Exception("not a Reader's credentials file")
    class NothingForUs : Exception("this file holds nothing for $SECTION")

    /** The folder of Reader's Notes and its login, for the notes of the books' highlights. */
    data class Notes(val url: String, val username: String, val password: String)

    fun build(url: String, username: String, password: String, notes: Notes? = null): String {
        val section = JSONObject()
        if (url.isNotBlank()) section.put("url", url)
        if (username.isNotBlank()) section.put("username", username)
        if (password.isNotEmpty()) section.put("password", password)
        if (notes != null && notes.url.isNotBlank()) {
            section.put("notes_url", notes.url)
            if (notes.username.isNotBlank()) section.put("notes_username", notes.username)
            if (notes.password.isNotEmpty()) section.put("notes_password", notes.password)
        }
        return JSONObject().put("format", FORMAT).put("version", 1).put(SECTION, section).toString(2)
    }

    fun read(text: String): Imported {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: throw NotCredentials()
        if (root.optString("format") != FORMAT) throw NotCredentials()
        for ((name, fallback) in listOf(SECTION to false, FALLBACK to true)) {
            root.optJSONObject(name)?.let { s ->
                val a = Account(s.str("url"), s.str("username"), s.str("password"))
                if (!a.isEmpty) return Imported(a, fallback)
            }
        }
        for (name in SERVER_FALLBACKS) {
            root.optJSONObject(name)?.let { s ->
                val a = Account(s.str("server"), s.str("username"), s.str("password"))
                if (a.url != null) return Imported(a, true)
            }
        }
        throw NothingForUs()
    }

    /**
     * The notes folder as a file names it: in our own section, or in Reader's Notes' (its server
     * and its folder make the address). Null when the file says nothing of it.
     */
    fun readNotes(text: String): Notes? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        root.optJSONObject(SECTION)?.let { s ->
            s.str("notes_url")?.let { return Notes(it, s.str("notes_username") ?: "", s.str("notes_password") ?: "") }
        }
        val notes = root.optJSONObject("readers-notes") ?: return null
        val server = notes.str("server")?.trim()?.trimEnd('/')?.ifEmpty { null } ?: return null
        val folder = (notes.str("folder") ?: "").trim().trim('/').ifEmpty { "Notes" }
        val path = folder.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return Notes("$server/$path/", notes.str("username") ?: "", notes.str("password") ?: "")
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) (opt(key) as? String) else null
}
