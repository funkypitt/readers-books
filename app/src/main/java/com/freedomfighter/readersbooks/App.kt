package com.freedomfighter.readersbooks

import android.app.Application
import com.freedomfighter.readersbooks.books.Library
import com.freedomfighter.readersbooks.data.CredentialsShare
import com.freedomfighter.readersbooks.data.Prefs
import com.freedomfighter.readersbooks.remote.HighlightSync
import com.freedomfighter.readersbooks.remote.RemoteLibrary

class App : Application() {
    // lazy: the content provider can be queried before Application.onCreate has run
    val prefs: Prefs by lazy { Prefs(this) }
    val library: Library by lazy { Library(this) }
    val remote: RemoteLibrary by lazy { RemoteLibrary(this) }
    val highlights: HighlightSync by lazy { HighlightSync(this) }
    override fun onCreate() { super.onCreate(); prefs; library; CredentialsShare.cleanUp(this); highlights.shelf() }
}
