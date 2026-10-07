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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freedomfighter.readersbooks.App
import com.freedomfighter.readersbooks.R
import com.freedomfighter.readersbooks.data.AllBooksSort
import com.freedomfighter.readersbooks.data.Credentials
import com.freedomfighter.readersbooks.data.CredentialsShare
import com.freedomfighter.readersbooks.data.LibrarySort
import com.freedomfighter.readersbooks.data.Settings
import com.freedomfighter.readersbooks.remote.RemoteBook
import com.freedomfighter.readersbooks.remote.Unauthorized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// ---------------------------------------------------------------------------------------------
// The library: a drive's e-books listed by folder or all at once, fetched one by one when tapped.
// ---------------------------------------------------------------------------------------------

@Composable
private fun errorText(e: Throwable): String = when (e) {
    is Unauthorized -> stringResource(R.string.wrong_login)
    else -> stringResource(R.string.library_error, e.message ?: e.javaClass.simpleName)
}

private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1_000_000L -> "${(bytes / 1000).coerceAtLeast(1)} KB"
    else -> "%.1f MB".format(bytes / 1e6)
}

private fun dateLabel(millis: Long): String = if (millis == 0L) "" else
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Folders of the drive that hold books; the setup rows when no drive is set yet. */
@Composable
fun LibraryScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val index by app.remote.index.collectAsState()
    val progress by app.remote.progress.collectAsState()
    val error by app.remote.error.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // the scan's notification (Android 13+); refused, the scan still runs, only unseen
    var pendingScan by remember { mutableStateOf<Boolean?>(null) }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pendingScan?.let { app.remote.requestScan(s, it) }; pendingScan = null }
    fun scan(fresh: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingScan = fresh; askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else app.remote.requestScan(s, fresh)
    }
    BackHandler { nav.pop() }
    // a drive just set up, or set up differently, is walked at once; a walk cut short carries on
    LaunchedEffect(s.share, s.url, s.username, s.password) {
        if (!s.libraryConfigured || progress != null) return@LaunchedEffect
        if (!app.remote.matches(s)) scan(true)
        else if (!index.complete && error == null) scan(false)
    }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.library), onBack = { nav.pop() }, trailing = if (s.libraryConfigured) "⋯" else null, onTrailing = { menu = true })
            if (!s.libraryConfigured) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Small(stringResource(R.string.library_hint), Modifier.padding(horizontal = rowPadH).padding(top = 16.dp, bottom = 4.dp), maxLines = 12)
                    LibraryAccountRows(s) { prompt = it }
                    Rule(Modifier.padding(vertical = 8.dp))
                    CredentialsRows(app, s)
                }
            } else {
                val folders = remember(index) { app.remote.folders() }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                    progress?.let { p -> item { Small(stringResource(R.string.scanning, p.folders, p.books), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 2) } }
                    error?.let { e -> item { Small(errorText(e), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 4) } }
                    if (progress == null && !index.complete && index.scannedAt > 0L) item {
                        TextRow(stringResource(R.string.continue_scan), secondary = stringResource(R.string.scan_interrupted, index.folders, index.books.size)) { scan(false) }
                    }
                    if (progress == null && index.complete && index.failed.isNotEmpty()) item {
                        TextRow(stringResource(R.string.continue_scan), secondary = stringResource(R.string.folders_unread, index.failed.size)) { scan(false) }
                    }
                    if (folders.isEmpty() && progress == null && error == null && index.complete && index.scannedAt > 0L) item { Small(stringResource(R.string.no_books_found), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 4) }
                    if (index.books.isNotEmpty()) item { TextRow(stringResource(R.string.all_books), secondary = pluralStringResource(R.plurals.n_books, index.books.size, index.books.size)) { nav.push(Screen.AllBooks) } }
                    items(folders, key = { it.path }) { f ->
                        TextRow(f.path.ifEmpty { stringResource(R.string.root_folder) }, secondary = listOf(pluralStringResource(R.plurals.n_books, f.count, f.count)).joinToString()) { nav.push(Screen.Folder(f.path)) }
                    }
                    if (index.scannedAt > 0L && progress == null && index.complete) item { Small(stringResource(R.string.scanned_at, dateLabel(index.scannedAt)), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 1) }
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(null, listOfNotNull(
            if (progress == null && (!index.complete || index.failed.isNotEmpty())) MenuItem(stringResource(R.string.continue_scan)) { scan(false) } else null,
            if (progress == null) MenuItem(stringResource(R.string.scan_again)) { scan(true) } else MenuItem(stringResource(R.string.stop_scan)) { app.remote.cancel() },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) }
        ), onDismiss = { menu = false })
        LibraryAccountPrompt(app, s, prompt) { prompt = null }
    }
}

/** The books of one folder, by name or by date. Tap fetches the book and opens it. */
@Composable
fun FolderScreen(nav: Nav, app: App, path: String) {
    val s by app.prefs.settings.collectAsState()
    val index by app.remote.index.collectAsState()
    val books = remember(index, s.librarySort) {
        val b = app.remote.books(path)
        when (s.librarySort) {
            LibrarySort.NAME -> b.sortedBy { it.name.lowercase() }
            LibrarySort.NEWEST -> b.sortedWith(compareByDescending<RemoteBook> { it.modified }.thenBy { it.name.lowercase() })
            LibrarySort.OLDEST -> b.sortedWith(compareBy<RemoteBook> { it.modified }.thenBy { it.name.lowercase() })
        }
    }
    BookList(nav, app, path.substringAfterLast('/').ifEmpty { stringResource(R.string.root_folder) }, books,
        order = stringResource(when (s.librarySort) { LibrarySort.NAME -> R.string.sort_name; LibrarySort.NEWEST -> R.string.sort_newest; LibrarySort.OLDEST -> R.string.sort_oldest }),
        orders = listOf(
            MenuItem(stringResource(R.string.sort_name)) { app.prefs.setLibrarySort(LibrarySort.NAME) },
            MenuItem(stringResource(R.string.sort_newest)) { app.prefs.setLibrarySort(LibrarySort.NEWEST) },
            MenuItem(stringResource(R.string.sort_oldest)) { app.prefs.setLibrarySort(LibrarySort.OLDEST) }
        ))
}

/**
 * Every book of the drive in one list, the folders left out: by last change (newest first), by
 * name, by author, or by when this app last opened them. The folder's name stays on the second line.
 */
@Composable
fun AllBooksScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val index by app.remote.index.collectAsState()
    val shelf by app.library.books.collectAsState()
    // when each book was last opened here, by its reference on the drive
    val opened = remember(shelf) { shelf.filter { it.source != null }.associate { it.source!! to maxOf(it.opened, it.added) } }
    val books = remember(index, opened, s.allBooksSort) {
        val b = index.books
        when (s.allBooksSort) {
            AllBooksSort.NEWEST -> b.sortedWith(compareByDescending<RemoteBook> { it.modified }.thenBy { it.name.lowercase() })
            AllBooksSort.NAME -> b.sortedBy { it.name.lowercase() }
            // books whose author is not known go last, by name
            AllBooksSort.AUTHOR -> b.sortedWith(compareBy<RemoteBook>({ it.author.isEmpty() }, { it.author.lowercase() }, { it.title.lowercase() }))
            AllBooksSort.OPENED -> b.sortedWith(compareByDescending<RemoteBook> { opened[it.ref] ?: 0L }.thenBy { it.name.lowercase() })
        }
    }
    BookList(nav, app, stringResource(R.string.all_books), books, showFolder = true,
        order = stringResource(when (s.allBooksSort) { AllBooksSort.NEWEST -> R.string.sort_newest; AllBooksSort.NAME -> R.string.sort_name; AllBooksSort.AUTHOR -> R.string.sort_author; AllBooksSort.OPENED -> R.string.sort_opened }),
        orders = listOf(
            MenuItem(stringResource(R.string.sort_newest)) { app.prefs.setAllBooksSort(AllBooksSort.NEWEST) },
            MenuItem(stringResource(R.string.sort_name)) { app.prefs.setAllBooksSort(AllBooksSort.NAME) },
            MenuItem(stringResource(R.string.sort_author)) { app.prefs.setAllBooksSort(AllBooksSort.AUTHOR) },
            MenuItem(stringResource(R.string.sort_opened)) { app.prefs.setAllBooksSort(AllBooksSort.OPENED) }
        ),
        opened = if (s.allBooksSort == AllBooksSort.OPENED) opened else null)
}

/**
 * A list of the drive's books under a title, with the current order named above it and the
 * orders offered in the ⋯ menu. Tap fetches the book (once) and opens it. `opened` adds when
 * this app last opened each book to its line.
 */
@Composable
private fun BookList(nav: Nav, app: App, title: String, books: List<RemoteBook>, order: String, orders: List<MenuItem>, showFolder: Boolean = false, opened: Map<String, Long>? = null) {
    val s by app.prefs.settings.collectAsState()
    val shelf by app.library.books.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<Throwable?>(null) }
    val unsupported = stringResource(R.string.reader_unsupported)
    val rootFolder = stringResource(R.string.root_folder)
    val yesterday = stringResource(R.string.yesterday)
    fun open(b: RemoteBook) {
        app.library.bySource(b.ref)?.let { e -> nav.push(if (e.magazine) Screen.Chapters(e.id) else Screen.Book(e.id)); return }
        if (fetching != null) return
        fetching = b.ref; failed = null
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { val f = app.remote.fetch(s, b); app.library.importFile(f, b.name, b.ref).getOrThrow() } }
            fetching = null
            r.onSuccess { e -> nav.push(if (e.magazine) Screen.Chapters(e.id) else Screen.Book(e.id)) }
            r.onFailure { failed = it }
        }
    }
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(title, onBack = { nav.pop() }, trailing = "⋯", onTrailing = { menu = true })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                item { Small(order, Modifier.padding(horizontal = rowPadH).padding(top = 8.dp, bottom = 4.dp), maxLines = 1) }
                failed?.let { e -> item { Small(if (e is Unauthorized) stringResource(R.string.wrong_login) else stringResource(R.string.download_failed, e.message ?: unsupported), Modifier.padding(horizontal = rowPadH, vertical = rowPadV), maxLines = 4) } }
                items(books, key = { it.ref }) { b ->
                    val onShelf = shelf.any { it.source == b.ref }
                    val openedLabel = opened?.get(b.ref)?.let { stringResource(R.string.opened_on, whenLabel(it, yesterday)) } ?: ""
                    val line = listOf(
                        if (fetching == b.ref) stringResource(R.string.downloading) else if (onShelf) stringResource(R.string.on_shelf) else "",
                        openedLabel, if (showFolder) b.folderName.ifEmpty { rootFolder } else "",
                        dateLabel(b.modified), b.extension, sizeLabel(b.size)
                    ).filter { it.isNotEmpty() }.joinToString(" · ")
                    Column(Modifier.fillMaxWidth().noRippleClickable { open(b) }.padding(horizontal = rowPadH, vertical = rowPadV * 0.7f)) {
                        T(b.title, size = LocalTypo.current.title, maxLines = 2)
                        Small(line, maxLines = 1)
                    }
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(stringResource(R.string.order), orders, onDismiss = { menu = false })
    }
}

// ---------------------------------------------------------------------------------------------
// Account rows, shared by the library's first screen and the settings.
// ---------------------------------------------------------------------------------------------

/** The four fields; `onEdit` names the one tapped ("share", "url", "username", "password"). */
@Composable
fun LibraryAccountRows(s: Settings, onEdit: (String) -> Unit) {
    TextRow(s.share.ifBlank { stringResource(R.string.share_link) }, secondary = stringResource(R.string.share_link)) { onEdit("share") }
    TextRow(s.url.ifBlank { stringResource(R.string.webdav_url) }, secondary = stringResource(R.string.webdav_url)) { onEdit("url") }
    TextRow(s.username.ifBlank { stringResource(R.string.username) }, secondary = stringResource(R.string.username)) { onEdit("username") }
    TextRow(if (s.password.isEmpty()) stringResource(R.string.password) else "••••••••", secondary = stringResource(R.string.password)) { onEdit("password") }
}

/** The prompt for one field, as an overlay; the stored index is dropped when the drive changes. */
@Composable
fun LibraryAccountPrompt(app: App, s: Settings, field: String?, onClose: () -> Unit) {
    field ?: return
    val title = stringResource(when (field) { "share" -> R.string.share_link; "url" -> R.string.webdav_url; "username" -> R.string.username; else -> R.string.password })
    val initial = when (field) { "share" -> s.share; "url" -> s.url; "username" -> s.username; else -> "" }
    TextPrompt(title, initial, password = field == "password", onDone = { v ->
        when (field) {
            "share" -> app.prefs.setLibrary(v, s.url, s.username, s.password)
            "url" -> app.prefs.setLibrary(s.share, v, s.username, s.password)
            "username" -> app.prefs.setLibrary(s.share, s.url, v, s.password)
            else -> app.prefs.setLibrary(s.share, s.url, s.username, v)
        }
        onClose()
    }, onCancel = onClose)
}

/** Export (when something is set) and import of the Reader's credentials file. */
@Composable
fun CredentialsRows(app: App, s: Settings) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    val notCredentials = stringResource(R.string.credentials_not_a_file)
    val nothingForUs = stringResource(R.string.credentials_nothing, stringResource(R.string.app_name))
    val imported = stringResource(R.string.credentials_imported)
    val importedFrom = stringResource(R.string.credentials_imported_from)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = try {
            val got = Credentials.read(CredentialsShare.readText(context, uri))
            val a = got.account
            // a file that names a drive replaces the whole account: a link and an address never both apply
            if (a.share != null) app.prefs.setLibrary(a.share, "", "", "")
            else app.prefs.setLibrary("", a.url ?: s.url, a.username ?: s.username, a.password ?: s.password)
            if (got.fromFallback) importedFrom else imported
        } catch (e: Credentials.NotCredentials) { notCredentials
        } catch (e: Credentials.NothingForUs) { nothingForUs
        } catch (e: Exception) { e.message?.let { context.getString(R.string.credentials_unreadable, it) } ?: notCredentials }
    }
    val shareTitle = stringResource(R.string.export_credentials)
    if (s.libraryStarted) TextRow(shareTitle, secondary = stringResource(R.string.export_credentials_hint)) {
        CredentialsShare.share(context, Credentials.build(s.share, s.url, s.username, s.password), shareTitle)
    }
    TextRow(stringResource(R.string.import_credentials), secondary = message.ifBlank { null }) {
        message = ""
        pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
    }
}
