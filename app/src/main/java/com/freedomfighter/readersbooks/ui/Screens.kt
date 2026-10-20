package com.freedomfighter.readersbooks.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freedomfighter.readersbooks.App
import com.freedomfighter.readersbooks.R
import com.freedomfighter.readersbooks.books.Entry
import com.freedomfighter.readersbooks.data.FontChoice
import com.freedomfighter.readersbooks.data.Palettes
import com.freedomfighter.readersbooks.data.SHELF_KEEP_DAYS
import com.freedomfighter.readersbooks.data.ShelfSort
import com.freedomfighter.readersbooks.data.TextSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed class Screen {
    data object Shelf : Screen()
    data class Book(val id: String) : Screen()
    data class Chapters(val id: String) : Screen()
    data object Settings : Screen()
    data object Library : Screen()
    data object LibraryFolders : Screen()
    /** The books of one folder; a `group` is a top folder standing for everything inside it. */
    data class Folder(val path: String, val group: Boolean = false) : Screen()
    data object AllBooks : Screen()
    /** The books of the library whose name, author or folder carries what is typed. */
    data object Search : Screen()
    /** Choosing the folder of the library a book of the shelf is sent to. */
    data class SendToLibrary(val id: String) : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Shelf)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
    var version by mutableIntStateOf(0)
    var importing by mutableStateOf(false)
    var importFailed by mutableStateOf(false)
}

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

val BOOK_MIME = arrayOf(
    "application/epub+zip", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook",
    "application/x-fictionbook+xml", "text/plain", "application/octet-stream", "*/*"
)

fun whenLabel(millis: Long, yesterday: String): String {
    if (millis == 0L) return ""
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()); val today = LocalDate.now()
    return when (d.toLocalDate()) { today -> d.format(DateTimeFormatter.ofPattern("HH:mm")); today.minusDays(1) -> yesterday; else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yyyy")).lowercase() }
}

/** The shelf: one line per book, the one you were reading first. */
@Composable
fun ShelfScreen(nav: Nav, app: App) {
    val typo = LocalTypo.current
    val books by app.library.books.collectAsState()
    val settings by app.prefs.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var orderMenu by remember { mutableStateOf(false) }
    var bookMenu by remember { mutableStateOf<Entry?>(null) }
    // the shelf's order: by last opened (or added), by title, by last added, or by size
    val shelf = remember(books, settings.shelfSort) {
        when (settings.shelfSort) {
            ShelfSort.OPENED -> books
            ShelfSort.TITLE -> books.sortedBy { it.title.lowercase() }
            ShelfSort.ADDED -> books.sortedByDescending { it.added }
            ShelfSort.SIZE -> books.sortedByDescending { it.size }
        }
    }
    val orderLabel = shelfSortLabel(settings.shelfSort)
    // what the shelf takes on the phone, and how much of it is there to stay
    val kept = books.count { it.kept }
    val room = listOfNotNull(pluralStringResource(R.plurals.n_books, books.size, books.size), sizeLabel(books.sumOf { it.size }).ifEmpty { null },
        if (kept > 0) pluralStringResource(R.plurals.n_kept, kept, kept) else null).joinToString(" · ")
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            nav.importing = true; nav.importFailed = false
            scope.launch {
                val r = withContext(Dispatchers.IO) { app.library.import(uri) }
                nav.importing = false; nav.importFailed = r.isFailure
                r.getOrNull()?.let { nav.push(if (it.magazine) Screen.Chapters(it.id) else Screen.Book(it.id)) }
            }
        }
    }
    fun pick() = runCatching { picker.launch(BOOK_MIME) }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.shelf), onBack = null, trailing = "⋯", onTrailing = { menu = true })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                if (nav.importing) item { Small("…", Modifier.padding(horizontal = rowPadH, vertical = rowPadV)) }
                else if (nav.importFailed) item { Small(stringResource(R.string.reader_unsupported), Modifier.padding(horizontal = rowPadH, vertical = rowPadV)) }
                if (books.isEmpty() && !nav.importing) item { Small(stringResource(R.string.empty_shelf), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 4) }
                else item { Small(room, Modifier.padding(horizontal = rowPadH).padding(top = 8.dp, bottom = 4.dp), maxLines = 1) }
                items(shelf, key = { it.id }) { b ->
                    Column(Modifier.fillMaxWidth().pressable(onClick = { nav.push(if (b.magazine) Screen.Chapters(b.id) else Screen.Book(b.id)) }, onLongPress = { bookMenu = b }).padding(horizontal = rowPadH, vertical = rowPadV * 0.7f)) {
                        T(b.title, size = typo.title, maxLines = 2)
                        Small(listOf(if (b.opened > 0L) "${b.progress}%" else stringResource(R.string.not_started), whenLabel(b.opened, stringResource(R.string.yesterday)), b.format.name.lowercase(), sizeLabel(b.size),
                            if (b.kept) stringResource(R.string.kept) else "").filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1)
                    }
                }
            }
            Rule()
            TextRow(stringResource(R.string.open_book), size = typo.title) { pick() }
            if (settings.libraryConfigured) TextRow(stringResource(R.string.library), size = typo.title) { nav.push(Screen.Library) }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(null, listOf(MenuItem(stringResource(R.string.open_book)) { pick() }, MenuItem(stringResource(R.string.library)) { nav.push(Screen.Library) },
            MenuItem(stringResource(R.string.order), secondary = orderLabel) { orderMenu = true }), onDismiss = { menu = false }, footer = listOf(
            MenuItem(themeName(Palettes.next(settings.theme))) { app.prefs.nextTheme() },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) }
        ))
        if (orderMenu) TextMenu(stringResource(R.string.order), ShelfSort.entries.map { o -> MenuItem(shelfSortLabel(o)) { app.prefs.setShelfSort(o) } }, onDismiss = { orderMenu = false })
        bookMenu?.let { b ->
            TextMenu(b.title, buildList {
                add(MenuItem(stringResource(R.string.read)) { nav.push(Screen.Book(b.id)) })
                add(MenuItem(stringResource(if (b.magazine) R.string.contents else R.string.chapters)) { nav.push(Screen.Chapters(b.id)) })
                // a book of the library: kept on the phone, or free to go when the shelf is cleared; removed, it stays on the drive
                if (b.fromLibrary) add(MenuItem(stringResource(if (b.kept) R.string.unkeep else R.string.keep_on_phone)) { app.library.setKept(b.id, !b.kept) })
                add(MenuItem(stringResource(R.string.remove), secondary = if (b.fromLibrary) stringResource(R.string.remove_copy_hint) else null) { app.library.remove(b.id) })
                if (canSend(settings, b)) add(MenuItem(stringResource(R.string.send_to_library)) { nav.push(Screen.SendToLibrary(b.id)) })
            }, onDismiss = { bookMenu = null })
        }
    }
}

@Composable
private fun shelfSortLabel(sort: ShelfSort) = stringResource(when (sort) { ShelfSort.OPENED -> R.string.sort_opened; ShelfSort.TITLE -> R.string.sort_title; ShelfSort.ADDED -> R.string.sort_added; ShelfSort.SIZE -> R.string.sort_size })

/** How long a book of the library stays on the shelf unopened, as the settings say it. */
@Composable
fun keepDaysLabel(days: Int) = stringResource(when (days) { 0 -> R.string.cleanup_never; 30 -> R.string.cleanup_month; 90 -> R.string.cleanup_three_months; else -> R.string.cleanup_six_months })

@Composable
fun SettingsScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val current = if (s.readerSp > 0) s.readerSp else defaultReaderSp()
    var prompt by remember { mutableStateOf<String?>(null) }
    val export = rememberCredentialsExport()
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.settings), onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                TextRow(themeName(s.theme), secondary = stringResource(R.string.colours)) { app.prefs.nextTheme() }
                TextRow(if (s.readerSp == 0) stringResource(R.string.reading_size_auto, current) else "$current", secondary = stringResource(R.string.reading_size)) { app.prefs.setReaderSp((current + 2).coerceAtMost(40)) }
                TextRow(stringResource(R.string.smaller_text), secondary = "$current → ${(current - 2).coerceAtLeast(12)}") { app.prefs.setReaderSp((current - 2).coerceAtLeast(12)) }
                TextRow(stringResource(R.string.auto_size), secondary = stringResource(R.string.auto_size_hint)) { app.prefs.setReaderSp(0) }
                TextRow(stringResource(if (s.bookSerif) R.string.font_serif else R.string.font_sans), secondary = stringResource(R.string.book_text)) { app.prefs.setBookSerif(!s.bookSerif) }
                TextRow(when (s.font) { FontChoice.SANS -> stringResource(R.string.font_sans); FontChoice.SERIF -> stringResource(R.string.font_serif); FontChoice.MONO -> stringResource(R.string.font_mono) }, secondary = stringResource(R.string.font)) {
                    app.prefs.setFont(when (s.font) { FontChoice.SANS -> FontChoice.SERIF; FontChoice.SERIF -> FontChoice.MONO; FontChoice.MONO -> FontChoice.SANS })
                }
                TextRow(when (s.textSize) { TextSize.SMALL -> "S"; TextSize.MEDIUM -> "M"; TextSize.LARGE -> "L" }, secondary = stringResource(R.string.ui_size)) {
                    app.prefs.setTextSize(when (s.textSize) { TextSize.SMALL -> TextSize.MEDIUM; TextSize.MEDIUM -> TextSize.LARGE; TextSize.LARGE -> TextSize.SMALL })
                }
                TextRow(if (s.keepScreenOn) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.keep_screen_on)) { app.prefs.setKeepScreenOn(!s.keepScreenOn) }
                TextRow(if (s.haptics) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.haptics)) { app.prefs.setHaptics(!s.haptics) }
                Rule(Modifier.padding(vertical = 8.dp))
                Small(stringResource(R.string.library_hint), Modifier.padding(horizontal = rowPadH).padding(top = 8.dp, bottom = 4.dp), maxLines = 12)
                LibraryAccountRows(s) { prompt = it }
                if (s.libraryConfigured) TextRow(stringResource(R.string.library_folders), secondary = listOfNotNull(
                    if (s.libraryFolders.isEmpty()) stringResource(R.string.whole_drive) else pluralStringResource(R.plurals.n_folders_chosen, s.libraryFolders.size, s.libraryFolders.size),
                    if (s.libraryExcluded.isEmpty()) null else pluralStringResource(R.plurals.n_folders_left_out, s.libraryExcluded.size, s.libraryExcluded.size)
                ).joinToString(" · ")) { nav.push(Screen.LibraryFolders) }
                if (s.libraryConfigured) {
                    // books of the library not opened for a while leave the shelf by themselves — tap for the next span
                    TextRow(keepDaysLabel(s.shelfKeepDays), secondary = stringResource(R.string.shelf_cleanup)) { app.prefs.setShelfKeepDays(SHELF_KEEP_DAYS[(SHELF_KEEP_DAYS.indexOf(s.shelfKeepDays) + 1) % SHELF_KEEP_DAYS.size]) }
                    Small(stringResource(R.string.shelf_cleanup_hint), Modifier.padding(horizontal = rowPadH).padding(bottom = 4.dp), maxLines = 6)
                }
                CredentialsRows(app, s, export)
                if (s.libraryStarted) TextRow(stringResource(R.string.forget_library), secondary = stringResource(R.string.forget_library_hint)) { app.remote.forget(); app.prefs.setLibrary("", "", "") }
                Rule(Modifier.padding(vertical = 8.dp))
                Small(stringResource(R.string.highlights_hint), Modifier.padding(horizontal = rowPadH).padding(top = 8.dp, bottom = 4.dp), maxLines = 16)
                NotesAccountRows(s) { prompt = it }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(stringResource(R.string.app_name), secondary = stringResource(R.string.about)) { }
                TextRow(stringResource(R.string.credits)) { }
                TextRow("Literata", secondary = "The Literata Project Authors · SIL Open Font License 1.1") { }
                TextRow("Roboto, Roboto Mono", secondary = "Christian Robertson, Google · Apache License 2.0") { }
                TextRow("OkHttp", secondary = "Square, Inc. · Apache License 2.0") { }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        LibraryAccountPrompt(app, s, prompt) { prompt = null }
        CredentialsExportMenu(export)
    }
}
