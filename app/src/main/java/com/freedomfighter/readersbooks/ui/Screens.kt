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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freedomfighter.readersbooks.App
import com.freedomfighter.readersbooks.R
import com.freedomfighter.readersbooks.books.Entry
import com.freedomfighter.readersbooks.data.FontChoice
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

private fun whenLabel(millis: Long): String {
    if (millis == 0L) return ""
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()); val today = LocalDate.now()
    return when (d.toLocalDate()) { today -> d.format(DateTimeFormatter.ofPattern("HH:mm")); today.minusDays(1) -> "yesterday"; else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yyyy")).lowercase() }
}

/** The shelf: one line per book, the one you were reading first. */
@Composable
fun ShelfScreen(nav: Nav, app: App) {
    val typo = LocalTypo.current
    val colors = LocalColors.current
    val books by app.library.books.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var bookMenu by remember { mutableStateOf<Entry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            nav.importing = true; nav.importFailed = false
            scope.launch {
                val r = withContext(Dispatchers.IO) { app.library.import(uri) }
                nav.importing = false; nav.importFailed = r.isFailure
                r.getOrNull()?.let { nav.push(Screen.Book(it.id)) }
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
                items(books, key = { it.id }) { b ->
                    Column(Modifier.fillMaxWidth().pressable(onClick = { nav.push(Screen.Book(b.id)) }, onLongPress = { bookMenu = b }).padding(horizontal = rowPadH, vertical = rowPadV * 0.7f)) {
                        T(b.title, size = typo.title, maxLines = 2)
                        Small(listOf(if (b.opened > 0L) "${b.progress}%" else stringResource(R.string.not_started), whenLabel(b.opened), b.format.name.lowercase()).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1)
                    }
                }
            }
            Rule()
            TextRow(stringResource(R.string.open_book), size = typo.title) { pick() }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(null, listOf(MenuItem(stringResource(R.string.open_book)) { pick() }), onDismiss = { menu = false }, footer = listOf(
            MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(colors.isDark) },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) }
        ))
        bookMenu?.let { b ->
            TextMenu(b.title, listOf(
                MenuItem(stringResource(R.string.read)) { nav.push(Screen.Book(b.id)) },
                MenuItem(stringResource(R.string.chapters)) { nav.push(Screen.Chapters(b.id)) },
                MenuItem(stringResource(R.string.remove)) { app.library.remove(b.id) }
            ), onDismiss = { bookMenu = null })
        }
    }
}

@Composable
fun SettingsScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val colors = LocalColors.current
    val current = if (s.readerSp > 0) s.readerSp else defaultReaderSp()
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.settings), onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                TextRow(if (colors.isDark) stringResource(R.string.theme_dark) else stringResource(R.string.theme_light), secondary = stringResource(R.string.colours)) { app.prefs.toggleTheme(colors.isDark) }
                TextRow("$current" + if (s.readerSp == 0) " · auto" else "", secondary = stringResource(R.string.reading_size)) { app.prefs.setReaderSp((current + 2).coerceAtMost(40)) }
                TextRow(stringResource(R.string.smaller_text), secondary = "$current → ${(current - 2).coerceAtLeast(12)}") { app.prefs.setReaderSp((current - 2).coerceAtLeast(12)) }
                TextRow(stringResource(R.string.auto_size), secondary = stringResource(R.string.auto_size_hint)) { app.prefs.setReaderSp(0) }
                TextRow(when (s.font) { FontChoice.SANS -> "sans-serif"; FontChoice.SERIF -> "serif"; FontChoice.MONO -> "mono" }, secondary = stringResource(R.string.font)) {
                    app.prefs.setFont(when (s.font) { FontChoice.SANS -> FontChoice.SERIF; FontChoice.SERIF -> FontChoice.MONO; FontChoice.MONO -> FontChoice.SANS })
                }
                TextRow(when (s.textSize) { TextSize.SMALL -> "S"; TextSize.MEDIUM -> "M"; TextSize.LARGE -> "L" }, secondary = stringResource(R.string.ui_size)) {
                    app.prefs.setTextSize(when (s.textSize) { TextSize.SMALL -> TextSize.MEDIUM; TextSize.MEDIUM -> TextSize.LARGE; TextSize.LARGE -> TextSize.SMALL })
                }
                TextRow(if (s.keepScreenOn) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.keep_screen_on)) { app.prefs.setKeepScreenOn(!s.keepScreenOn) }
                TextRow(if (s.haptics) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.haptics)) { app.prefs.setHaptics(!s.haptics) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(stringResource(R.string.app_name), secondary = stringResource(R.string.about)) { }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}
