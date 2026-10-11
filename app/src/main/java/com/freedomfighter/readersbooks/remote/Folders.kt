package com.freedomfighter.readersbooks.remote

/**
 * Which folders of the drive make the library. Empty = the whole drive. A chosen folder stands
 * for everything inside it; the folder Reader's Podcasts sends its transcripts to is always in.
 * The choice is kept in the settings; a sync walks only these folders, and only their books are shown.
 */
object Folders {
    fun under(parent: String, folder: String) = parent.isEmpty() || folder == parent || folder.startsWith("$parent/")

    fun inLibrary(chosen: List<String>, folder: String): Boolean =
        chosen.isEmpty() || under(TRANSCRIPTS_FOLDER, folder) || chosen.any { under(it, folder) }

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
}
