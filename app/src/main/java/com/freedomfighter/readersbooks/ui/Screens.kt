package com.freedomfighter.readersbooks.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.freedomfighter.readersbooks.books.Magazines
import com.freedomfighter.readersbooks.remote.Folders
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
    /** The library's newspapers and magazines, the newest issue first. */
    data object News : Screen()
    /** The books of the library whose name, author or folder carries what is typed. */
    data object Search : Screen()
    /** Choosing the folder of the library a book of the shelf is sent to. */
    data class SendToLibrary(val id: String) : Screen()
    /** The issues on the phone of one magazine (by its key), or of one followed folder of the drive. */
    data class Issues(val magazine: String? = null, val folder: String? = null) : Screen()
}

/**
 * A book of the shelf opened: at its page. A magazine never opened shows its contents first;
 * opened before, it goes back to the article it was left at, the contents one tap above it.
 */
fun Nav.openBook(e: Entry) = push(if (e.magazine && e.opened == 0L) Screen.Chapters(e.id) else Screen.Book(e.id))

/** From an article of a magazine to its contents: back down to them, or in place of the article. */
fun Nav.toContents(id: String) {
    if (below == Screen.Chapters(id)) pop() else { pop(); push(Screen.Chapters(id)) }
}

/**
 * The screens, one on top of the other. The first one is the home screen: the library when a
 * drive is set up (the shelf is then a row of it), the shelf otherwise — see [homeFor].
 */
class Nav(home: Screen = Screen.Shelf) {
    val stack = mutableStateListOf(home)
    val current: Screen get() = stack.last()
    /** The screen a back would lead to, null on the home screen. */
    val below: Screen? get() = stack.getOrNull(stack.size - 2)
    val atHome: Boolean get() = stack.size == 1
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
    /**
     * The home screen changes (a drive set up, or forgotten): the screens above it stay, the same
     * screen just above it is folded into it.
     */
    fun rehome(home: Screen) {
        if (stack[0] == home) return
        stack[0] = home
        while (stack.size > 1 && stack[1] == home) stack.removeAt(1)
    }
    /** To the library: back down to it when it is the home screen, else on top. */
    fun toLibrary() { if (stack[0] == Screen.Library) home() else push(Screen.Library) }
    var version by mutableIntStateOf(0)
    var importing by mutableStateOf(false)
    var importFailed by mutableStateOf(false)
}

/** What a back leads to, named as the screen below calls itself: for the « back » items of the menus. */
@Composable
fun backLabel(nav: Nav): String = stringResource(when {
    nav.below == Screen.Library || nav.below is Screen.Folder || nav.below == Screen.AllBooks || nav.below == Screen.Search -> R.string.library
    nav.below == Screen.Shelf && nav.stack[0] != Screen.Shelf -> R.string.on_this_phone
    else -> R.string.shelf
})

/** The home screen: the library when one is set up, else the shelf. */
fun homeFor(s: com.freedomfighter.readersbooks.data.Settings): Screen = if (s.libraryConfigured) Screen.Library else Screen.Shelf

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
    // the issues of one magazine make one line, where its latest issue would stand
    val groups = remember(books) { Magazines.groups(books) }
    val lines = remember(shelf, groups) {
        val done = HashSet<String>()
        shelf.mapNotNull { b ->
            val key = if (b.magazine) Magazines.key(b.title) else ""
            val issues = groups[key]
            if (issues == null) b else if (done.add(key)) MagazineLine(key, issues) else null
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
                r.getOrNull()?.let { nav.openBook(it) }
            }
        }
    }
    fun pick() = runCatching { picker.launch(BOOK_MIME) }
    // the home screen when no library is set up; else a page of the library, back leads to it
    BackHandler(enabled = !nav.atHome) { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(if (nav.atHome) R.string.shelf else R.string.on_this_phone), onBack = if (nav.atHome) null else ({ nav.pop() }), trailing = "⋯", onTrailing = { menu = true })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                if (nav.importing) item { Small("…", Modifier.padding(horizontal = rowPadH, vertical = rowPadV)) }
                else if (nav.importFailed) item { Small(stringResource(R.string.reader_unsupported), Modifier.padding(horizontal = rowPadH, vertical = rowPadV)) }
                if (books.isEmpty() && !nav.importing) item { Small(stringResource(R.string.empty_shelf), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 4) }
                else item { Small(room, Modifier.padding(horizontal = rowPadH).padding(top = 8.dp, bottom = 4.dp), maxLines = 1) }
                items(lines, key = { l -> if (l is Entry) l.id else "m:" + (l as MagazineLine).key }) { l ->
                    if (l is Entry) ShelfBook(l, l.title, onClick = { nav.openBook(l) }, onLongPress = { bookMenu = l })
                    else {
                        // one tap: the latest issue, where it was left; held long: every issue
                        val (key, issues) = l as MagazineLine
                        val latest = issues.first()
                        val interaction = remember { MutableInteractionSource() }
                        Column(Modifier.fillMaxWidth().tileCard(rowSurface(interaction)).pressable(onClick = { nav.openBook(latest) }, onLongPress = { nav.push(Screen.Issues(magazine = key)) }, interactionSource = interaction).padding(horizontal = rowInset(), vertical = rowPadV * 0.7f)) {
                            T(Magazines.name(latest.title) + " · " + pluralStringResource(R.plurals.n_issues, issues.size, issues.size), size = typo.title, maxLines = 2)
                            Small(listOf(Magazines.issue(latest.title), if (latest.opened > 0L) "${latest.progress}%" else stringResource(R.string.not_started)).joinToString(" · "), maxLines = 1)
                        }
                    }
                }
            }
            Rule()
            TextRow(stringResource(R.string.open_book), size = typo.title) { pick() }
            // no library yet: one quiet line says what it would bring, and leads to setting it up
            if (!settings.libraryConfigured) TextRow(stringResource(R.string.setup_library), secondary = stringResource(R.string.setup_library_hint), size = typo.title * 0.85f) { nav.push(Screen.Library) }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(null, listOf(MenuItem(stringResource(R.string.open_book)) { pick() }, MenuItem(stringResource(R.string.library)) { nav.toLibrary() },
            MenuItem(stringResource(R.string.order), secondary = orderLabel) { orderMenu = true }), onDismiss = { menu = false }, footer = listOf(
            MenuItem(themeName(Palettes.next(settings.theme))) { app.prefs.nextTheme() },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) }
        ))
        if (orderMenu) TextMenu(stringResource(R.string.order), ShelfSort.entries.map { o -> MenuItem(shelfSortLabel(o)) { app.prefs.setShelfSort(o) } }, onDismiss = { orderMenu = false })
        bookMenu?.let { b -> ShelfBookMenu(nav, app, b) { bookMenu = null } }
    }
}

/** The issues of one magazine, on the shelf: one line under its name. */
private data class MagazineLine(val key: String, val issues: List<Entry>)

/** One book of the shelf: its title, then where it was left, when, its format and size. */
@Composable
fun ShelfBook(b: Entry, title: String, onClick: () -> Unit, onLongPress: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().tileCard(rowSurface(interaction)).pressable(onClick = onClick, onLongPress = onLongPress, interactionSource = interaction).padding(horizontal = rowInset(), vertical = rowPadV * 0.7f)) {
        T(title, size = LocalTypo.current.title, maxLines = 2)
        Small(listOf(if (b.opened > 0L) "${b.progress}%" else stringResource(R.string.not_started), whenLabel(b.opened, stringResource(R.string.yesterday)), b.format.name.lowercase(), sizeLabel(b.size),
            if (b.kept) stringResource(R.string.kept) else "").filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1)
    }
}

/** What can be done to a book of the shelf, held long. */
@Composable
fun ShelfBookMenu(nav: Nav, app: App, b: Entry, onDismiss: () -> Unit) {
    val settings by app.prefs.settings.collectAsState()
    TextMenu(b.title, buildList {
        add(MenuItem(stringResource(R.string.read)) { nav.push(Screen.Book(b.id)) })
        add(MenuItem(stringResource(if (b.magazine) R.string.contents else R.string.chapters)) { nav.push(Screen.Chapters(b.id)) })
        // a book of the library: kept on the phone, or free to go when the shelf is cleared; removed, it stays on the drive
        if (b.fromLibrary) add(MenuItem(stringResource(if (b.kept) R.string.unkeep else R.string.keep_on_phone)) { app.library.setKept(b.id, !b.kept) })
        add(MenuItem(stringResource(R.string.remove), secondary = if (b.fromLibrary) stringResource(R.string.remove_copy_hint) else null) { app.library.remove(b.id) })
        if (canSend(settings, b)) add(MenuItem(stringResource(R.string.send_to_library)) { nav.push(Screen.SendToLibrary(b.id)) })
    }, onDismiss = onDismiss)
}

/**
 * The issues on this phone of one magazine, or of one followed folder of the drive, the newest
 * first. A tap opens one (its contents the first time, else where it was left); held long, the
 * same menu as on the shelf.
 */
@Composable
fun IssuesScreen(nav: Nav, app: App, magazine: String?, folder: String?) {
    val books by app.library.books.collectAsState()
    val index by app.remote.index.collectAsState()
    var bookMenu by remember { mutableStateOf<Entry?>(null) }
    val issues = remember(books, index, magazine, folder) {
        if (magazine != null) Magazines.issues(books, magazine)
        else {
            val refs = index.books.filter { Folders.under(folder ?: "", it.folder) }.map { it.ref }.toSet()
            books.filter { it.source in refs }.sortedWith(compareByDescending<Entry> { Magazines.time(it) }.thenByDescending { it.title })
        }
    }
    val title = if (magazine != null) issues.firstOrNull()?.let { Magazines.name(it.title) } ?: "" else (folder ?: "").substringAfterLast('/')
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(title, onBack = { nav.pop() })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                item { Small(if (issues.isEmpty()) stringResource(R.string.no_issues) else pluralStringResource(R.plurals.n_issues, issues.size, issues.size), Modifier.padding(horizontal = rowPadH).padding(top = 8.dp, bottom = 4.dp), maxLines = 3) }
                items(issues, key = { it.id }) { e ->
                    ShelfBook(e, if (magazine != null) Magazines.issue(e.title) else e.title, onClick = { nav.openBook(e) }, onLongPress = { bookMenu = e })
                }
            }
            if (folder != null) { Rule(); TextRow(stringResource(R.string.whole_folder), size = LocalTypo.current.title) { nav.push(Screen.Folder(folder)) } }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        bookMenu?.let { b -> ShelfBookMenu(nav, app, b) { bookMenu = null } }
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
                if (s.libraryStarted) TextRow(stringResource(R.string.forget_library), secondary = stringResource(R.string.forget_library_hint)) { app.remote.forget(); app.following.forget(); app.prefs.setLibrary("", "", "") }
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
