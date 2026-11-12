package com.freedomfighter.readersbooks

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.freedomfighter.readersbooks.ui.AllBooksScreen
import com.freedomfighter.readersbooks.ui.NewsScreen
import com.freedomfighter.readersbooks.ui.PeriodicalScreen
import com.freedomfighter.readersbooks.ui.SearchScreen
import com.freedomfighter.readersbooks.ui.BookChaptersScreen
import com.freedomfighter.readersbooks.ui.BookScreen
import com.freedomfighter.readersbooks.ui.FolderScreen
import com.freedomfighter.readersbooks.ui.SendToLibraryScreen
import com.freedomfighter.readersbooks.ui.LibraryFoldersScreen
import com.freedomfighter.readersbooks.ui.LibraryScreen
import com.freedomfighter.readersbooks.ui.LocalColors
import com.freedomfighter.readersbooks.ui.Nav
import com.freedomfighter.readersbooks.ui.ReaderTheme
import com.freedomfighter.readersbooks.ui.Screen
import com.freedomfighter.readersbooks.ui.SettingsScreen
import com.freedomfighter.readersbooks.ui.ShelfScreen
import com.freedomfighter.readersbooks.ui.homeFor
import com.freedomfighter.readersbooks.ui.openBook
import com.freedomfighter.readersbooks.ui.IssuesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var nav: Nav

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as App
        nav = Nav(homeFor(app.prefs.settings.value))
        handle(intent)
        setContent {
            val settings by app.prefs.settings.collectAsState()
            // a drive set up makes the library the home screen; forgotten, the shelf is again
            LaunchedEffect(settings.libraryConfigured) { nav.rehome(homeFor(settings)) }
            ReaderTheme(settings) {
                Bars()
                when (val s = nav.current) {
                    Screen.Shelf -> ShelfScreen(nav, app)
                    is Screen.Book -> BookScreen(nav, app, s.id)
                    is Screen.Chapters -> BookChaptersScreen(nav, app, s.id)
                    Screen.Settings -> SettingsScreen(nav, app)
                    Screen.Library -> LibraryScreen(nav, app)
                    Screen.LibraryFolders -> LibraryFoldersScreen(nav, app)
                    is Screen.Folder -> FolderScreen(nav, app, s.path, s.group)
                    is Screen.Periodical -> PeriodicalScreen(nav, app, s.key)
                    Screen.AllBooks -> AllBooksScreen(nav, app)
                    Screen.News -> NewsScreen(nav, app)
                    Screen.Search -> SearchScreen(nav, app)
                    is Screen.SendToLibrary -> SendToLibraryScreen(nav, app, s.id)
                    is Screen.Issues -> IssuesScreen(nav, app, s.magazine, s.folder)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    /** A book opened with or shared to this app lands on the shelf and opens. */
    private fun handle(intent: Intent?) {
        // our own provider URI (from the launcher's tile): straight to that book's page
        val own = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && own?.authority == "com.freedomfighter.readersbooks") {
            own.lastPathSegment?.let { id -> if ((application as App).library.get(id) != null) { nav.home(); nav.push(Screen.Book(id)) } }
            intent.action = null; return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return
        intent?.action = null
        val app = application as App
        nav.importing = true
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { app.library.import(uri!!) }
            nav.importing = false
            nav.importFailed = r.isFailure
            r.getOrNull()?.let { nav.home(); nav.openBook(it) }
        }
    }

    @Composable
    private fun Bars() {
        val view = LocalView.current
        val colors = LocalColors.current
        LaunchedEffect(colors.isDark) {
            val c = WindowInsetsControllerCompat(window, view)
            c.isAppearanceLightStatusBars = !colors.isDark
            c.isAppearanceLightNavigationBars = !colors.isDark
        }
    }
}
