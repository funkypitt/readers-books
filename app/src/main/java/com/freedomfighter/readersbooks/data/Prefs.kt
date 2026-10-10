package com.freedomfighter.readersbooks.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class FontChoice { SERIF, SANS, MONO }
enum class TextSize { SMALL, MEDIUM, LARGE }
enum class Align { LEFT, CENTER }
/** Order of the books inside a library folder. */
enum class LibrarySort { NAME, NEWEST, OLDEST }
/** Order of the whole drive's books in one list: last change, name, author, last opened here. */
enum class AllBooksSort { NEWEST, NAME, AUTHOR, OPENED }

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
    val librarySort: LibrarySort = LibrarySort.NAME,
    val allBooksSort: AllBooksSort = AllBooksSort.NEWEST
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
        librarySort = enumOr(sp.getString("library_sort", null), LibrarySort.NAME),
        allBooksSort = enumOr(sp.getString("all_books_sort", null), AllBooksSort.NEWEST)
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
    fun setLibrarySort(v: LibrarySort) = sp.edit().putString("library_sort", v.name).apply()
    fun setAllBooksSort(v: AllBooksSort) = sp.edit().putString("all_books_sort", v.name).apply()
    /** The library account; the password is kept encrypted. Blank keys are kept as they are by the import, cleared here. */
    fun setLibrary(url: String, username: String, password: String) = sp.edit()
        .remove("library_share")
        .putString("library_url", url.trim())
        .putString("library_username", username.trim())
        .putString("library_password", runCatching { Secret.encrypt(password) }.getOrDefault(""))
        .apply()
    fun setNotes(url: String, username: String, password: String) = sp.edit()
        .putString("notes_url", url.trim())
        .putString("notes_username", username.trim())
        .putString("notes_password", if (password.isEmpty()) "" else runCatching { Secret.encrypt(password) }.getOrDefault(""))
        .apply()
    fun toggleTheme(systemIsDark: Boolean) {
        val dark = when (_settings.value.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemIsDark }
        setTheme(if (dark) ThemeMode.LIGHT else ThemeMode.DARK)
    }
}
