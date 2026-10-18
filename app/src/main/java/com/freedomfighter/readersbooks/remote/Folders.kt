package com.freedomfighter.readersbooks.remote

import com.freedomfighter.readersbooks.data.BookSort
import com.freedomfighter.readersbooks.data.FolderSort

/**
 * Which folders of the drive make the library. Empty = the whole drive. A chosen folder stands
 * for everything inside it; the folder Reader's Podcasts sends its transcripts to is always in.
 * A folder left out is out with everything inside it, whatever else is chosen: it is neither
 * shown nor walked by a sync. Both lists are kept in the settings; a sync walks only the chosen
 * folders, skips the ones left out, and only the remaining books are shown.
 */
object Folders {
    fun under(parent: String, folder: String) = parent.isEmpty() || folder == parent || folder.startsWith("$parent/")

    /** True when the folder, or a folder above it, was left out. */
    fun leftOut(excluded: List<String>, folder: String): Boolean = excluded.any { it.isNotEmpty() && under(it, folder) }

    fun inLibrary(chosen: List<String>, folder: String, excluded: List<String> = emptyList()): Boolean =
        !leftOut(excluded, folder) && (chosen.isEmpty() || under(TRANSCRIPTS_FOLDER, folder) || chosen.any { under(it, folder) })

    /** The chosen folder a folder is part of without being chosen itself; null otherwise. */
    fun partOf(chosen: List<String>, folder: String): String? =
        chosen.firstOrNull { it != folder && under(it, folder) } ?: (if (folder != TRANSCRIPTS_FOLDER && under(TRANSCRIPTS_FOLDER, folder) && chosen.isNotEmpty()) TRANSCRIPTS_FOLDER else null)

    /** Where a sync starts: the chosen folders and the transcripts', or the top of the drive. */
    fun roots(chosen: List<String>): List<String> = if (chosen.isEmpty()) listOf("") else (chosen + TRANSCRIPTS_FOLDER).distinct()

    /**
     * The books after one folder was listed again: its own books are replaced by what was found,
     * and the books of subfolders the drive no longer has (not in `subfolders`) are dropped, with
     * everything below them. Books elsewhere stay as they were.
     */
    fun merge(books: List<RemoteBook>, folder: String, subfolders: List<String>, found: List<RemoteBook>): List<RemoteBook> {
        val keptSub = subfolders.toSet()
        val kept = books.filter { b ->
            if (b.folder == folder) return@filter false
            if (!under(folder, b.folder)) return@filter true
            val child = if (folder.isEmpty()) b.folder.substringBefore('/') else folder + "/" + b.folder.removePrefix("$folder/").substringBefore('/')
            child in keptSub
        }
        return kept + found.filter { it.folder == folder }
    }

    /** The top folder a folder is in: its first name; "" for the top of the drive itself. */
    fun top(folder: String): String = folder.substringBefore('/')

    /** A folder's path as seen from inside `base`: "" for the base itself. */
    fun relative(base: String, folder: String): String = if (folder == base) "" else if (base.isEmpty()) folder else folder.removePrefix("$base/")

    /**
     * The folders these books are in, each with its count and its latest book: every folder, or
     * the top folders only, each standing for everything inside it (the books of the top of the
     * drive stand by themselves). `subfolders` = how many folders a top folder spans.
     */
    fun grouped(books: List<RemoteBook>, top: Boolean): List<Folder> =
        books.groupBy { if (top) top(it.folder) else it.folder }.map { (p, b) ->
            Folder(p, b.size, b.maxOf { it.modified }, if (top) b.map { it.folder }.toSet().size else 1)
        }

    fun sortFolders(folders: List<Folder>, sort: FolderSort): List<Folder> = when (sort) {
        FolderSort.NAME -> folders.sortedBy { it.path.lowercase() }
        FolderSort.NEWEST -> folders.sortedWith(compareByDescending<Folder> { it.modified }.thenBy { it.path.lowercase() })
        FolderSort.COUNT -> folders.sortedWith(compareByDescending<Folder> { it.count }.thenBy { it.path.lowercase() })
    }

    /**
     * The books whose name, author or folder carries every word of `query`, accents and case
     * aside ("maigret 1950" finds "Simenon - Maigret et la vieille dame.epub" in "Books/1950s").
     * An empty query finds nothing: the list is asked for, not spilled.
     */
    fun search(books: List<RemoteBook>, query: String): List<RemoteBook> {
        val words = plain(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return books.filter { b ->
            val text = plain(b.title + " " + b.author + " " + b.folder.replace('/', ' '))
            words.all { it in text }
        }
    }

    /** Text as searched: lower case, accents and other marks taken off, every separator a space. */
    fun plain(text: String): String {
        val bare = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "")
        return bare.replace(SEPARATORS, " ").trim()
    }
    private val MARKS = Regex("\\p{M}+")
    private val SEPARATORS = Regex("[\\s_\\-–—.,;:()\\[\\]'’\"«»]+")

    /** The books of one list in the chosen order; `opened` = when this app last opened each, by its reference. */
    fun sortBooks(books: List<RemoteBook>, sort: BookSort, opened: Map<String, Long> = emptyMap()): List<RemoteBook> = when (sort) {
        BookSort.NEWEST -> books.sortedWith(compareByDescending<RemoteBook> { it.modified }.thenBy { it.name.lowercase() })
        BookSort.OLDEST -> books.sortedWith(compareBy<RemoteBook> { it.modified }.thenBy { it.name.lowercase() })
        BookSort.NAME -> books.sortedBy { it.name.lowercase() }
        // books whose author is not known go last, by name
        BookSort.AUTHOR -> books.sortedWith(compareBy<RemoteBook>({ it.author.isEmpty() }, { it.author.lowercase() }, { it.title.lowercase() }))
        BookSort.OPENED -> books.sortedWith(compareByDescending<RemoteBook> { opened[it.ref] ?: 0L }.thenBy { it.name.lowercase() })
    }
}
