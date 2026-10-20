package com.freedomfighter.readersbooks.data

import com.freedomfighter.readersbooks.remote.Folders
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The inks, the phone's choice between them, then the Plume palettes (see [Palettes]). */
enum class ThemeMode { DARK, LIGHT, SYSTEM, LAVANDE, SAUGE, NORDIQUE, TERRACOTTA, COQUETTE, MILITANTE, TORRIDE, NATURE }
enum class FontChoice { SERIF, SANS, MONO }
enum class TextSize { SMALL, MEDIUM, LARGE }
enum class Align { LEFT, CENTER }
/** Order of a list of the library's books: last change, name, author, last opened here. */
enum class BookSort { NEWEST, OLDEST, NAME, AUTHOR, OPENED }
/** Order of the library's folders: name, latest book, number of books. */
enum class FolderSort { NAME, NEWEST, COUNT }
/** Order of the shelf: last opened (or added), title, last added, largest file. */
enum class ShelfSort { OPENED, TITLE, ADDED, SIZE }

/** How long a book of the library stays on the shelf unopened before it leaves by itself: never, or a month, three, six. */
val SHELF_KEEP_DAYS = listOf(0, 30, 90, 180)

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val font: FontChoice = FontChoice.SANS,
    val textSize: TextSize = TextSize.MEDIUM,
    val align: Align = Align.LEFT,
    val haptics: Boolean = true,
    /** Reading size in sp; 0 = from the screen width. */
    val readerSp: Int = 0,
    val keepScreenOn: Boolean = true,
    /** Book text in the serif reading face (Literata) instead of the sans-serif one. */
    val bookSerif: Boolean = false,
    /** The optional library: a WebDAV address with a login, which the app reads and writes. */
    val url: String = "",
    val username: String = "",
    val password: String = "",
    /** Where Reader's Notes keeps its notes (a WebDAV folder), for the note of each book's highlights; the login is the library's unless another is given. */
    val notesUrl: String = "",
    val notesUsername: String = "",
    val notesPassword: String = "",
    /** Order of the books of a folder, of all books, of the folders, of the shelf. */
    val librarySort: BookSort = BookSort.NAME,
    val allBooksSort: BookSort = BookSort.NEWEST,
    val folderSort: FolderSort = FolderSort.NAME,
    val shelfSort: ShelfSort = ShelfSort.OPENED,
    /** The library lists its top folders only, each standing for everything inside it. */
    val libraryTopFolders: Boolean = false,
    /** A folder of the library lists the books of the folders inside it too. */
    val librarySubfolders: Boolean = false,
    /** The folders of the drive that make the library (paths below its top); empty = the whole drive. */
    val libraryFolders: List<String> = emptyList(),
    /** The folders of the drive left out of the library, with everything inside them, whatever is chosen. */
    val libraryExcluded: List<String> = emptyList(),
    /** The choice of folders was offered once the drive was first walked. */
    val libraryFoldersAsked: Boolean = false,
    /** The folder of the drive a book of the shelf was last sent to; null when none was yet. */
    val sendFolder: String? = null,
    /** Days a book of the library may stay on the shelf unopened before it leaves by itself; 0 = it stays. Books kept on the phone always stay. */
    val shelfKeepDays: Int = 0
) {
    /** An address with a login: that is what reaches the library. */
    val libraryConfigured: Boolean get() = url.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    /** Anything typed at all, so the setup rows know whether to show what is there. */
    val libraryStarted: Boolean get() = url.isNotBlank() || username.isNotBlank() || password.isNotEmpty()
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = read() }
    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun read() = Settings(
        theme = enumOr(sp.getString("theme", null), ThemeMode.DARK),
        font = enumOr(sp.getString("font", null), FontChoice.SANS),
        textSize = enumOr(sp.getString("text_size", null), TextSize.MEDIUM),
        align = enumOr(sp.getString("align", null), Align.LEFT),
        haptics = sp.getBoolean("haptics", true),
        readerSp = sp.getInt("reader_sp", 0),
        keepScreenOn = sp.getBoolean("keep_screen_on", true),
        // Never chosen: a serif app font used to give serif pages, and still does.
        bookSerif = if (sp.contains("book_serif")) sp.getBoolean("book_serif", false) else sp.getString("font", null) == FontChoice.SERIF.name,
        url = sp.getString("library_url", "") ?: "",
        username = sp.getString("library_username", "") ?: "",
        password = Secret.decrypt(sp.getString("library_password", "") ?: ""),
        notesUrl = sp.getString("notes_url", "") ?: "",
        notesUsername = sp.getString("notes_username", "") ?: "",
        notesPassword = Secret.decrypt(sp.getString("notes_password", "") ?: ""),
        librarySort = enumOr(sp.getString("library_sort", null), BookSort.NAME),
        allBooksSort = enumOr(sp.getString("all_books_sort", null), BookSort.NEWEST),
        folderSort = enumOr(sp.getString("folder_sort", null), FolderSort.NAME),
        shelfSort = enumOr(sp.getString("shelf_sort", null), ShelfSort.OPENED),
        libraryTopFolders = sp.getBoolean("library_top_folders", false),
        librarySubfolders = sp.getBoolean("library_subfolders", false),
        libraryFolders = (sp.getStringSet("library_folders", null) ?: emptySet()).filter { it.isNotEmpty() }.sorted(),
        libraryExcluded = (sp.getStringSet("library_excluded", null) ?: emptySet()).filter { it.isNotEmpty() }.sorted(),
        libraryFoldersAsked = sp.getBoolean("library_folders_asked", false),
        sendFolder = sp.getString("send_folder", null),
        shelfKeepDays = sp.getInt("shelf_keep_days", 0)
    )
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    fun setTheme(m: ThemeMode) = sp.edit().putString("theme", m.name).apply()
    fun setFont(f: FontChoice) = sp.edit().putString("font", f.name).apply()
    fun setTextSize(t: TextSize) = sp.edit().putString("text_size", t.name).apply()
    fun setAlign(a: Align) = sp.edit().putString("align", a.name).apply()
    fun setHaptics(v: Boolean) = sp.edit().putBoolean("haptics", v).apply()
    fun setReaderSp(v: Int) = sp.edit().putInt("reader_sp", v).apply()
    fun setBookSerif(v: Boolean) = sp.edit().putBoolean("book_serif", v).apply()
    fun setKeepScreenOn(v: Boolean) = sp.edit().putBoolean("keep_screen_on", v).apply()
    fun setLibrarySort(v: BookSort) = sp.edit().putString("library_sort", v.name).apply()
    fun setAllBooksSort(v: BookSort) = sp.edit().putString("all_books_sort", v.name).apply()
    fun setFolderSort(v: FolderSort) = sp.edit().putString("folder_sort", v.name).apply()
    fun setShelfSort(v: ShelfSort) = sp.edit().putString("shelf_sort", v.name).apply()
    fun setLibraryTopFolders(v: Boolean) = sp.edit().putBoolean("library_top_folders", v).apply()
    fun setLibrarySubfolders(v: Boolean) = sp.edit().putBoolean("library_subfolders", v).apply()
    /** The folders that make the library; chosen, the choice is no longer to be offered. */
    fun setLibraryFolders(folders: List<String>) = sp.edit().putStringSet("library_folders", folders.filter { it.isNotEmpty() }.toSet()).putBoolean("library_folders_asked", true).apply()
    /** The folders left out of the library; a chosen folder that is now left out is no longer chosen. */
    fun setLibraryExcluded(folders: List<String>) {
        val excluded = folders.filter { it.isNotEmpty() }.toSet()
        val chosen = _settings.value.libraryFolders.filter { c -> excluded.none { Folders.under(it, c) } }.toSet()
        sp.edit().putStringSet("library_excluded", excluded).putStringSet("library_folders", chosen).apply()
    }
    fun setSendFolder(folder: String) = sp.edit().putString("send_folder", folder).apply()
    fun setShelfKeepDays(days: Int) = sp.edit().putInt("shelf_keep_days", days).apply()
    fun setLibraryFoldersAsked() = sp.edit().putBoolean("library_folders_asked", true).apply()
    /** The library account; the password is kept encrypted. Blank keys are kept as they are by the import, cleared here. Another drive: its folders are not these. */
    fun setLibrary(url: String, username: String, password: String) = sp.edit()
        .remove("library_share")
        .apply { if (url.trim() != _settings.value.url) remove("library_folders").remove("library_excluded").remove("library_folders_asked").remove("send_folder") }
        .putString("library_url", url.trim())
        .putString("library_username", username.trim())
        .putString("library_password", runCatching { Secret.encrypt(password) }.getOrDefault(""))
        .apply()
    fun setNotes(url: String, username: String, password: String) = sp.edit()
        .putString("notes_url", url.trim())
        .putString("notes_username", username.trim())
        .putString("notes_password", if (password.isEmpty()) "" else runCatching { Secret.encrypt(password) }.getOrDefault(""))
        .apply()
    /** One tap: the next theme, round and round (the inks first, then the palettes). */
    fun nextTheme() = setTheme(Palettes.next(_settings.value.theme))
}
