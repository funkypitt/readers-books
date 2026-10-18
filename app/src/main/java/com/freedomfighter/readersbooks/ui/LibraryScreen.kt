package com.freedomfighter.readersbooks.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freedomfighter.readersbooks.App
import com.freedomfighter.readersbooks.R
import com.freedomfighter.readersbooks.data.BookSort
import com.freedomfighter.readersbooks.data.Credentials
import com.freedomfighter.readersbooks.data.CredentialsShare
import com.freedomfighter.readersbooks.data.FolderSort
import com.freedomfighter.readersbooks.data.Settings
import com.freedomfighter.readersbooks.books.BookFormat
import com.freedomfighter.readersbooks.books.Entry
import com.freedomfighter.readersbooks.remote.BOOK_EXTENSIONS
import com.freedomfighter.readersbooks.remote.Folders
import com.freedomfighter.readersbooks.remote.TRANSCRIPTS_FOLDER
import com.freedomfighter.readersbooks.remote.RemoteBook
import com.freedomfighter.readersbooks.remote.Unauthorized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// ---------------------------------------------------------------------------------------------
// The library: a drive's e-books listed by folder or all at once, fetched one by one when tapped.
// ---------------------------------------------------------------------------------------------

/** The drive could not be reached at all, as against refusing or failing: nothing to do but wait for a connection. */
fun offline(e: Throwable): Boolean = e is UnknownHostException || e is ConnectException || e is NoRouteToHostException || e is SocketTimeoutException

@Composable
private fun errorText(e: Throwable): String = when {
    e is Unauthorized -> stringResource(R.string.wrong_login)
    offline(e) -> stringResource(R.string.offline_list)
    else -> stringResource(R.string.library_error, e.message ?: e.javaClass.simpleName)
}

/** Why a book could not be fetched. */
@Composable
private fun fetchError(e: Throwable): String = when {
    e is Unauthorized -> stringResource(R.string.wrong_login)
    offline(e) -> stringResource(R.string.offline_drive)
    else -> stringResource(R.string.download_failed, e.message ?: stringResource(R.string.reader_unsupported))
}

fun sizeLabel(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1_000_000L -> "${(bytes / 1000).coerceAtLeast(1)} KB"
    else -> "%.1f MB".format(bytes / 1e6)
}

private fun dateLabel(millis: Long): String = if (millis == 0L) "" else
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

@Composable
private fun folderSortLabel(sort: FolderSort) = stringResource(when (sort) { FolderSort.NAME -> R.string.sort_name; FolderSort.NEWEST -> R.string.sort_newest; FolderSort.COUNT -> R.string.sort_count })

@Composable
private fun bookSortLabel(sort: BookSort) = stringResource(when (sort) {
    BookSort.NEWEST -> R.string.sort_newest; BookSort.OLDEST -> R.string.sort_oldest; BookSort.NAME -> R.string.sort_name
    BookSort.AUTHOR -> R.string.sort_author; BookSort.OPENED -> R.string.sort_opened
})

/** The orders a list of the library's books can take, as menu items; `set` keeps the one tapped. */
@Composable
private fun bookOrders(set: (BookSort) -> Unit): List<MenuItem> = BookSort.entries.map { o -> MenuItem(bookSortLabel(o)) { set(o) } }

/**
 * Folders of the drive that hold books — every folder, or the top folders only, each standing
 * for everything inside it (menu) — in the chosen order; the setup rows when no drive is set yet.
 */
@Composable
fun LibraryScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val index by app.remote.index.collectAsState()
    val progress by app.remote.progress.collectAsState()
    val error by app.remote.error.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var orderMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val export = rememberCredentialsExport()
    // the scan's notification (Android 13+); refused, the scan still runs, only unseen
    var pendingScan by remember { mutableStateOf<Boolean?>(null) }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pendingScan?.let { app.remote.requestScan(s, it) }; pendingScan = null }
    fun scan(fresh: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingScan = fresh; askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else app.remote.requestScan(s, fresh)
    }
    BackHandler { nav.pop() }
    // a drive just set up, or set up differently, is walked at once; a walk cut short carries on;
    // a library not synced for a while is synced again quietly; a fresh one only looks for new transcripts
    LaunchedEffect(s.url, s.username, s.password) {
        if (!s.libraryConfigured || progress != null) return@LaunchedEffect
        if (!app.remote.matches(s)) scan(true)
        else if (!index.complete && error == null) scan(false)
        else if (app.remote.stale() && error == null) app.remote.syncQuietly(s)
        else app.remote.refreshFolder(s)
    }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.library), onBack = { nav.pop() }, trailing = if (s.libraryConfigured) "⋯" else null, onTrailing = { menu = true })
            if (!s.libraryConfigured) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Small(stringResource(R.string.library_hint), Modifier.padding(horizontal = rowPadH).padding(top = 16.dp, bottom = 4.dp), maxLines = 12)
                    LibraryAccountRows(s) { prompt = it }
                    Rule(Modifier.padding(vertical = 8.dp))
                    CredentialsRows(app, s, export)
                }
            } else {
                val folders = remember(index, s.libraryFolders, s.libraryExcluded, s.libraryTopFolders, s.folderSort) { app.remote.folders(s) }
                val shown = remember(index, s.libraryFolders, s.libraryExcluded) { app.remote.shown(s) }
                val known = remember(index) { app.remote.knownFolders().filter { it.path.isNotEmpty() } }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                    // the sync, by hand, with where it is or when it last ran
                    item {
                        val state = progress?.let { p -> stringResource(R.string.scanning, p.folders, p.books) } ?: if (index.scannedAt > 0L) stringResource(R.string.scanned_at, dateLabel(index.scannedAt)) else null
                        TextRow(stringResource(R.string.sync_now), secondary = state) { if (progress == null) scan(false) }
                    }
                    error?.let { e -> item { Small(errorText(e), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 4) } }
                    // once the drive was walked: keep only some of its folders?
                    if (progress == null && index.complete && index.scannedAt > 0L && !s.libraryFoldersAsked && s.libraryFolders.isEmpty() && known.size > 1) item {
                        TextRow(stringResource(R.string.folders_offer), secondary = stringResource(R.string.folders_offer_hint, known.size)) { app.prefs.setLibraryFoldersAsked(); nav.push(Screen.LibraryFolders) }
                    }
                    if (progress == null && !index.complete && index.scannedAt > 0L) item {
                        TextRow(stringResource(R.string.continue_scan), secondary = stringResource(R.string.scan_interrupted, index.folders, index.books.size)) { scan(false) }
                    }
                    if (progress == null && index.complete && index.failed.isNotEmpty()) item {
                        TextRow(stringResource(R.string.continue_scan), secondary = stringResource(R.string.folders_unread, index.failed.size)) { scan(false) }
                    }
                    if (folders.isEmpty() && progress == null && error == null && index.complete && index.scannedAt > 0L) item { Small(stringResource(R.string.no_books_found), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 4) }
                    if (shown.isNotEmpty()) item { TextRow(stringResource(R.string.search), secondary = stringResource(R.string.search_hint)) { nav.push(Screen.Search) } }
                    if (shown.isNotEmpty()) item { TextRow(stringResource(R.string.all_books), secondary = pluralStringResource(R.plurals.n_books, shown.size, shown.size)) { nav.push(Screen.AllBooks) } }
                    items(folders, key = { it.path }) { f ->
                        // a top folder says how many folders it spans; its books are listed with them
                        val line = listOfNotNull(pluralStringResource(R.plurals.n_books, f.count, f.count),
                            if (f.subfolders > 1) pluralStringResource(R.plurals.n_folders, f.subfolders, f.subfolders) else null,
                            if (s.folderSort == FolderSort.NEWEST && f.modified > 0L) dateLabel(f.modified) else null).joinToString(" · ")
                        TextRow(f.path.ifEmpty { stringResource(R.string.root_folder) }, secondary = line,
                            onLongPress = if (f.path.isNotEmpty()) ({ folderMenu = f.path }) else null) { nav.push(Screen.Folder(f.path, group = s.libraryTopFolders && f.path.isNotEmpty())) }
                    }
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        // a folder held long: left out of the library, with everything inside it
        folderMenu?.let { path ->
            TextMenu(path, listOf(MenuItem(stringResource(R.string.leave_out), secondary = stringResource(R.string.leave_out_hint)) { app.prefs.setLibraryExcluded(s.libraryExcluded + path); folderMenu = null }), onDismiss = { folderMenu = null })
        }
        if (menu) TextMenu(null, listOfNotNull(
            if (progress == null && (!index.complete || index.failed.isNotEmpty())) MenuItem(stringResource(R.string.continue_scan)) { scan(false) } else null,
            if (progress == null) MenuItem(stringResource(R.string.scan_again)) { scan(true) } else MenuItem(stringResource(R.string.stop_scan)) { app.remote.cancel() },
            MenuItem(stringResource(if (s.libraryTopFolders) R.string.every_folder else R.string.group_top), secondary = stringResource(if (s.libraryTopFolders) R.string.every_folder_hint else R.string.group_top_hint)) { app.prefs.setLibraryTopFolders(!s.libraryTopFolders) },
            MenuItem(stringResource(R.string.order), secondary = folderSortLabel(s.folderSort)) { orderMenu = true },
            if (index.books.isNotEmpty()) MenuItem(stringResource(R.string.search)) { nav.push(Screen.Search) } else null,
            MenuItem(stringResource(R.string.library_folders)) { nav.push(Screen.LibraryFolders) },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) }
        ), onDismiss = { menu = false })
        if (orderMenu) TextMenu(stringResource(R.string.order), FolderSort.entries.map { o -> MenuItem(folderSortLabel(o)) { app.prefs.setFolderSort(o) } }, onDismiss = { orderMenu = false })
        LibraryAccountPrompt(app, s, prompt) { prompt = null }
        CredentialsExportMenu(export)
    }
}

/**
 * The books of one folder, in the chosen order: the folder alone, or with the folders inside it
 * (the menu; a top folder standing for a `group` always has them), each book then naming its
 * folder. Tap fetches the book and opens it.
 */
@Composable
fun FolderScreen(nav: Nav, app: App, path: String, group: Boolean = false) {
    val s by app.prefs.settings.collectAsState()
    val index by app.remote.index.collectAsState()
    val shelf by app.library.books.collectAsState()
    val opened = remember(shelf) { openedOnShelf(shelf) }
    val subfolders = group || s.librarySubfolders
    val hasSubfolders = remember(index, s.libraryFolders, s.libraryExcluded) { app.remote.hasSubfolders(s, path) }
    val books = remember(index, opened, s.librarySort, s.libraryFolders, s.libraryExcluded, subfolders) {
        Folders.sortBooks(app.remote.books(s, path, subfolders), s.librarySort, opened)
    }
    val withSubfolders = stringResource(R.string.with_subfolders)
    BookList(nav, app, if (group) path else path.substringAfterLast('/').ifEmpty { stringResource(R.string.root_folder) }, books,
        order = listOfNotNull(bookSortLabel(s.librarySort), if (subfolders && hasSubfolders) withSubfolders else null).joinToString(" · "),
        orders = bookOrders { app.prefs.setLibrarySort(it) },
        footer = if (!group && hasSubfolders) listOf(MenuItem(stringResource(if (subfolders) R.string.this_folder_only else R.string.with_subfolders)) { app.prefs.setLibrarySubfolders(!subfolders) }) else emptyList(),
        showFolder = subfolders, base = path, opened = if (s.librarySort == BookSort.OPENED) opened else null)
}

/** When each book of the library was last opened here, by its reference on the drive. */
private fun openedOnShelf(shelf: List<Entry>): Map<String, Long> = shelf.filter { it.source != null }.associate { it.source!! to maxOf(it.opened, it.added) }

/**
 * Every book of the drive in one list, the folders left out: by last change (newest first), by
 * name, by author, or by when this app last opened them. The folder's name stays on the second line.
 */
@Composable
fun AllBooksScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val index by app.remote.index.collectAsState()
    val shelf by app.library.books.collectAsState()
    val opened = remember(shelf) { openedOnShelf(shelf) }
    val books = remember(index, opened, s.allBooksSort, s.libraryFolders, s.libraryExcluded) { Folders.sortBooks(app.remote.shown(s), s.allBooksSort, opened) }
    BookList(nav, app, stringResource(R.string.all_books), books, showFolder = true,
        order = bookSortLabel(s.allBooksSort), orders = bookOrders { app.prefs.setAllBooksSort(it) },
        opened = if (s.allBooksSort == BookSort.OPENED) opened else null)
}

/**
 * The books of the library found by what is typed: every word must be in the name, the author
 * or the folder, accents and case aside; in the order of « all books ». The keyboard opens with
 * the page, and the list follows each letter.
 */
@Composable
fun SearchScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val index by app.remote.index.collectAsState()
    val shelf by app.library.books.collectAsState()
    val opened = remember(shelf) { openedOnShelf(shelf) }
    var query by rememberSaveable { mutableStateOf("") }
    val books = remember(index, opened, s.allBooksSort, s.libraryFolders, s.libraryExcluded, query) {
        Folders.sortBooks(Folders.search(app.remote.shown(s), query), s.allBooksSort, opened)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BookList(nav, app, stringResource(R.string.search), books, showFolder = true,
        order = if (query.isBlank()) "" else pluralStringResource(R.plurals.n_books, books.size, books.size) + " · " + bookSortLabel(s.allBooksSort),
        orders = bookOrders { app.prefs.setAllBooksSort(it) },
        opened = if (s.allBooksSort == BookSort.OPENED) opened else null,
        empty = if (query.isBlank()) null else stringResource(R.string.no_book_matches),
        searchable = false,
        header = {
            ReaderTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 10.dp).focusRequester(focus),
                placeholder = stringResource(R.string.search_hint), imeAction = ImeAction.Search)
            Rule()
        })
}

/**
 * A list of the drive's books under a title, with the current order named above it and the
 * orders offered in the ⋯ menu (`footer` = what else the menu offers). Tap fetches the book
 * (once) and opens it; held long, a book is fetched to be kept on the phone, or its copy on the
 * shelf is kept, renewed from the drive or removed. `showFolder` names each book's folder on its
 * line, as seen from `base`; `opened` adds when this app last opened each book. `header` sits
 * between the title and the list, `empty` is said when the list has nothing, and the menu
 * offers the search unless this is the search itself.
 */
@Composable
private fun BookList(nav: Nav, app: App, title: String, books: List<RemoteBook>, order: String, orders: List<MenuItem>, footer: List<MenuItem> = emptyList(), showFolder: Boolean = false, base: String = "", opened: Map<String, Long>? = null,
                     empty: String? = null, searchable: Boolean = true, header: (@Composable () -> Unit)? = null) {
    val s by app.prefs.settings.collectAsState()
    val shelf by app.library.books.collectAsState()
    val pending by app.downloads.pending.collectAsState()
    val background by app.downloads.failed.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var bookMenu by remember { mutableStateOf<RemoteBook?>(null) }
    var failed by remember { mutableStateOf<Throwable?>(null) }
    val rootFolder = stringResource(R.string.root_folder)
    val yesterday = stringResource(R.string.yesterday)
    fun open(b: RemoteBook) {
        app.library.bySource(b.ref)?.let { e -> nav.push(if (e.magazine) Screen.Chapters(e.id) else Screen.Book(e.id)); return }
        if (b.ref in pending) return
        failed = null
        scope.launch {
            val r = app.downloads.fetch(s, b)
            r.onSuccess { e -> nav.push(if (e.magazine) Screen.Chapters(e.id) else Screen.Book(e.id)) }
            r.onFailure { failed = it }
        }
    }
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(title, onBack = { nav.pop() }, trailing = "⋯", onTrailing = { menu = true })
            header?.invoke()
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                if (order.isNotEmpty()) item { Small(order, Modifier.padding(horizontal = rowPadH).padding(top = 8.dp, bottom = 4.dp), maxLines = 1) }
                if (books.isEmpty() && empty != null) item { Small(empty, Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 2) }
                failed?.let { e -> item { Small(fetchError(e), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 4) } }
                background?.let { (b, e) -> item { Small(b.title + " — " + fetchError(e), Modifier.noRippleClickable { app.downloads.seen() }.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 4) } }
                items(books, key = { it.ref }) { b ->
                    val copy = shelf.firstOrNull { it.source == b.ref }
                    val openedLabel = opened?.get(b.ref)?.let { stringResource(R.string.opened_on, whenLabel(it, yesterday)) } ?: ""
                    val line = listOf(
                        if (b.ref in pending) stringResource(R.string.downloading) else if (copy?.kept == true) stringResource(R.string.kept) else if (copy != null) stringResource(R.string.on_shelf) else "",
                        if (copy != null && app.downloads.newer(copy, b)) stringResource(R.string.newer_on_drive) else "",
                        openedLabel, if (showFolder) Folders.relative(base, b.folder).ifEmpty { if (base.isEmpty()) rootFolder else "" } else "",
                        dateLabel(b.modified), b.extension, sizeLabel(b.size)
                    ).filter { it.isNotEmpty() }.joinToString(" · ")
                    Column(Modifier.fillMaxWidth().pressable(onClick = { open(b) }, onLongPress = { bookMenu = b }).padding(horizontal = rowPadH, vertical = rowPadV * 0.7f)) {
                        T(b.title, size = LocalTypo.current.title, maxLines = 2)
                        Small(line, maxLines = 1)
                    }
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        val search = stringResource(R.string.search)
        if (menu) TextMenu(stringResource(R.string.order), orders, onDismiss = { menu = false },
            footer = footer + listOfNotNull(if (searchable) MenuItem(search) { nav.push(Screen.Search) } else null))
        // a book held long: on the phone to stay, or its copy there dealt with
        bookMenu?.let { b ->
            val copy = shelf.firstOrNull { it.source == b.ref }
            TextMenu(b.title, buildList {
                add(MenuItem(stringResource(R.string.read)) { open(b) })
                if (copy == null) add(MenuItem(stringResource(R.string.keep_on_phone), secondary = stringResource(R.string.keep_on_phone_hint)) { app.downloads.keep(s, b) })
                else {
                    add(MenuItem(stringResource(if (copy.kept) R.string.unkeep else R.string.keep_on_phone)) { app.library.setKept(copy.id, !copy.kept) })
                    if (app.downloads.newer(copy, b)) add(MenuItem(stringResource(R.string.download_again), secondary = stringResource(R.string.download_again_hint)) { app.downloads.keep(s, b, again = true) })
                    add(MenuItem(stringResource(R.string.remove), secondary = stringResource(R.string.remove_copy_hint)) { app.library.remove(copy.id) })
                }
            }, onDismiss = { bookMenu = null })
        }
    }
}

/**
 * The folders of the drive that make the library: every folder seen to hold books, the chosen
 * ones inverted along with what is inside them; a tap chooses or un-chooses. Held long, a folder
 * is left out (or taken back in): out with everything inside it, whatever is chosen, and skipped
 * by the syncs. Left changed, the library syncs quietly, so folders taken back in are read again.
 */
@Composable
fun LibraryFoldersScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val index by app.remote.index.collectAsState()
    val known = remember(index) { app.remote.knownFolders().filter { it.path.isNotEmpty() } }
    val chosen = s.libraryFolders
    val excluded = s.libraryExcluded
    val before = remember { s.libraryFolders to s.libraryExcluded }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    val rootFolder = stringResource(R.string.root_folder)
    val leftOut = stringResource(R.string.left_out)
    fun leave() { if (s.libraryFolders to s.libraryExcluded != before) app.remote.syncQuietly(s); nav.pop() }
    BackHandler { leave() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.library_folders), onBack = { leave() })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                item { Small(stringResource(R.string.library_folders_hint), Modifier.padding(horizontal = rowPadH).padding(top = 8.dp, bottom = 4.dp), maxLines = 10) }
                item { TextRow(stringResource(R.string.whole_drive), inverted = chosen.isEmpty()) { if (chosen.isNotEmpty()) app.prefs.setLibraryFolders(emptyList()) } }
                items(known, key = { it.path }) { f ->
                    val isChosen = f.path in chosen
                    val out = Folders.leftOut(excluded, f.path)
                    val outBy = excluded.firstOrNull { it != f.path && Folders.under(it, f.path) }
                    val part = Folders.partOf(chosen, f.path)
                    val count = pluralStringResource(R.plurals.n_books, f.count, f.count)
                    val secondary = when {
                        outBy != null -> count + " · " + leftOut + " · " + stringResource(R.string.in_folder, outBy)
                        out -> count + " · " + leftOut
                        part != null -> count + " · " + stringResource(R.string.in_folder, part.ifEmpty { rootFolder })
                        else -> count
                    }
                    TextRow(f.path, inverted = chosen.isNotEmpty() && Folders.inLibrary(chosen, f.path, excluded), secondary = secondary,
                        onLongPress = { folderMenu = f.path }) {
                        if (out) folderMenu = f.path
                        else if (isChosen) app.prefs.setLibraryFolders(chosen - f.path)
                        else if (part == null) app.prefs.setLibraryFolders(chosen + f.path)
                    }
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        folderMenu?.let { path ->
            val item = if (path in excluded) MenuItem(stringResource(R.string.take_back)) { app.prefs.setLibraryExcluded(excluded - path); folderMenu = null }
                else MenuItem(stringResource(R.string.leave_out), secondary = stringResource(R.string.leave_out_hint)) { app.prefs.setLibraryExcluded(excluded + path); folderMenu = null }
            TextMenu(path, listOf(item), onDismiss = { folderMenu = null })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A book of the shelf sent to the drive, to be a book of the library from then on.
// ---------------------------------------------------------------------------------------------

/** A book of the shelf may be sent when there is a library, it is not from it, and a sync of the drive would list its kind (not plain text). */
fun canSend(s: Settings, e: Entry): Boolean =
    s.libraryConfigured && e.format != BookFormat.TXT && e.source?.let { it.startsWith("http://") || it.startsWith("https://") } != true

/** The file name a book goes to the drive under: the one it was opened with when it was a book's, else its title, with the format's extension. */
fun sendName(e: Entry): String {
    val kept = e.sourceName?.takeIf { it.substringAfterLast('.', "").lowercase() in BOOK_EXTENSIONS }
    val ext = kept?.substringAfterLast('.') ?: e.format.name.lowercase()
    val stem = (kept?.substringBeforeLast('.') ?: e.title).replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().replace(Regex("\\s+"), " ").take(120).ifEmpty { "book" }
    return "$stem.$ext"
}

/**
 * Where in the library a book of the shelf goes: the folders of the drive that hold books (those
 * of the library, when only some folders make it), the one used last time first. A tap sends the
 * book there; it then stands on the shelf as a book of the library, and the drive's listing says so at once.
 */
@Composable
fun SendToLibraryScreen(nav: Nav, app: App, id: String) {
    val s by app.prefs.settings.collectAsState()
    val index by app.remote.index.collectAsState()
    val books by app.library.books.collectAsState()
    val entry = books.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<Throwable?>(null) }
    var done by remember { mutableStateOf<String?>(null) }      // the folder the book went to
    val rootFolder = stringResource(R.string.root_folder)
    val lastTime = stringResource(R.string.last_time)
    val counts = remember(index) { app.remote.knownFolders().associate { it.path to it.count } }
    val folders = remember(index, s.libraryFolders, s.libraryExcluded, s.sendFolder) {
        (counts.keys + s.libraryFolders + "")
            .filter { Folders.inLibrary(s.libraryFolders, it, s.libraryExcluded) && !Folders.under(TRANSCRIPTS_FOLDER, it) }
            .distinct().sortedWith(compareBy({ it != s.sendFolder }, { it.lowercase() }))
    }
    BackHandler { nav.pop() }
    if (entry == null) { nav.pop(); return }
    fun send(folder: String) {
        if (sending || done != null) return
        sending = true; failed = null
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { app.remote.send(s, folder, sendName(entry), app.library.file(entry)) } }
            sending = false
            r.onSuccess { b ->
                app.library.link(entry.id, b.ref, b.name)
                app.prefs.setSendFolder(folder)
                // the page it is at goes next to it right away, for the other devices
                app.highlights.place(entry.id)
                done = folder
            }
            r.onFailure { failed = it }
        }
    }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.send_to_library), onBack = { nav.pop() })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                item { T(entry.title, Modifier.padding(horizontal = rowPadH).padding(top = 8.dp), size = LocalTypo.current.title, maxLines = 2) }
                done?.let { f -> item { Small(stringResource(R.string.sent_to_library, entry.title, f.ifEmpty { rootFolder }), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 6) } }
                if (done == null) item { Small(stringResource(R.string.send_to_library_hint), Modifier.padding(horizontal = rowPadH).padding(top = 8.dp, bottom = 4.dp), maxLines = 12) }
                if (sending) item { Small(stringResource(R.string.sending_book), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 1) }
                failed?.let { e -> item { Small(if (e is Unauthorized) stringResource(R.string.wrong_login) else stringResource(R.string.send_failed, e.message ?: e.javaClass.simpleName), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 4) } }
                if (done == null) items(folders, key = { it }) { f ->
                    val count = counts[f]?.let { pluralStringResource(R.plurals.n_books, it, it) }
                    TextRow(f.ifEmpty { rootFolder }, secondary = listOfNotNull(count, if (f == s.sendFolder) lastTime else null).joinToString(" · ").ifEmpty { null }) { send(f) }
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Account rows, shared by the library's first screen and the settings.
// ---------------------------------------------------------------------------------------------

/** The three fields; `onEdit` names the one tapped ("url", "username", "password"). */
@Composable
fun LibraryAccountRows(s: Settings, onEdit: (String) -> Unit) {
    TextRow(s.url.ifBlank { stringResource(R.string.webdav_url) }, secondary = stringResource(R.string.webdav_url)) { onEdit("url") }
    TextRow(s.username.ifBlank { stringResource(R.string.username) }, secondary = stringResource(R.string.username)) { onEdit("username") }
    TextRow(if (s.password.isEmpty()) stringResource(R.string.password) else "••••••••", secondary = stringResource(R.string.password)) { onEdit("password") }
}

/** Where Reader's Notes keeps its notes, for the note of each book's highlights; the login is the library's unless another is given. */
@Composable
fun NotesAccountRows(s: Settings, onEdit: (String) -> Unit) {
    TextRow(s.notesUrl.ifBlank { stringResource(R.string.notes_folder) }, secondary = stringResource(R.string.notes_folder)) { onEdit("notes_url") }
    TextRow(s.notesUsername.ifBlank { stringResource(R.string.same_as_library) }, secondary = stringResource(R.string.notes_username)) { onEdit("notes_username") }
    TextRow(if (s.notesPassword.isEmpty()) stringResource(R.string.same_as_library) else "••••••••", secondary = stringResource(R.string.notes_password)) { onEdit("notes_password") }
}

/** The prompt for one field, as an overlay; the stored index is dropped when the drive changes. */
@Composable
fun LibraryAccountPrompt(app: App, s: Settings, field: String?, onClose: () -> Unit) {
    field ?: return
    val title = stringResource(when (field) {
        "url" -> R.string.webdav_url; "username" -> R.string.username
        "notes_url" -> R.string.notes_folder; "notes_username" -> R.string.notes_username; "notes_password" -> R.string.notes_password
        else -> R.string.password
    })
    val initial = when (field) { "url" -> s.url; "username" -> s.username; "notes_url" -> s.notesUrl; "notes_username" -> s.notesUsername; else -> "" }
    TextPrompt(title, initial, password = field.endsWith("password"), onDone = { v ->
        when (field) {
            "notes_url" -> app.prefs.setNotes(v, s.notesUsername, s.notesPassword)
            "notes_username" -> app.prefs.setNotes(s.notesUrl, v, s.notesPassword)
            "notes_password" -> app.prefs.setNotes(s.notesUrl, s.notesUsername, v)
            "url" -> app.prefs.setLibrary(v, s.username, s.password)
            "username" -> app.prefs.setLibrary(s.url, v, s.password)
            else -> app.prefs.setLibrary(s.url, s.username, v)
        }
        // A refused login stays on the screen until the account changes: with a new one the
        // scan is free to carry on by itself.
        app.remote.accountChanged()
        onClose()
    }, onCancel = onClose)
}

/** The export's state, held by the page: the share menu must lie over the whole page, not inside its scrolling rows. */
class CredentialsExport { var saved by mutableStateOf(false) }

@Composable
fun rememberCredentialsExport() = remember { CredentialsExport() }

/** After a save: "saved as …", with the share menu one tap away. */
@Composable
fun CredentialsExportMenu(export: CredentialsExport) {
    if (!export.saved) return
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share_export)
    TextMenu(stringResource(R.string.export_saved, Credentials.FILE_NAME), listOf(MenuItem(shareTitle) {
        CredentialsShare.share(context, Credentials.FILE_NAME, "application/json", shareTitle)
    }), onDismiss = { export.saved = false })
}

/** Export (when something is set) and import of the Reader's credentials file. */
@Composable
fun CredentialsRows(app: App, s: Settings, export: CredentialsExport) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    val notCredentials = stringResource(R.string.credentials_not_a_file)
    val nothingForUs = stringResource(R.string.credentials_nothing, stringResource(R.string.app_name))
    val imported = stringResource(R.string.credentials_imported)
    val importedFrom = stringResource(R.string.credentials_imported_from)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = try {
            val text = CredentialsShare.readText(context, uri)
            // the folder of Reader's Notes, when the file names it: for the notes of the books' highlights
            val notes = Credentials.readNotes(text)
            notes?.let { app.prefs.setNotes(it.url, it.username, it.password) }
            val got = try { Credentials.read(text) } catch (e: Credentials.NothingForUs) { if (notes == null) throw e else null }
            got?.account?.let { a ->
                app.prefs.setLibrary(a.url ?: s.url, a.username ?: s.username, a.password ?: s.password)
                app.remote.accountChanged()
            }
            if (got == null || got.fromFallback) importedFrom else imported
        } catch (e: Credentials.NotCredentials) { notCredentials
        } catch (e: Credentials.NothingForUs) { nothingForUs
        } catch (e: Exception) { e.message?.let { context.getString(R.string.credentials_unreadable, it) } ?: notCredentials }
    }
    // the export: saved where the person chooses, then offered to the share menu
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val cur = app.prefs.settings.value
        export.saved = CredentialsShare.save(context, uri, Credentials.FILE_NAME, Credentials.build(cur.url, cur.username, cur.password, Credentials.Notes(cur.notesUrl, cur.notesUsername, cur.notesPassword)))
    }
    if (s.libraryStarted) TextRow(stringResource(R.string.export_credentials), secondary = stringResource(R.string.export_credentials_hint)) {
        runCatching { save.launch(Credentials.FILE_NAME) }
    }
    TextRow(stringResource(R.string.import_credentials), secondary = message.ifBlank { null }) {
        message = ""
        pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
    }
}
