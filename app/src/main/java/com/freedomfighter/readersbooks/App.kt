package com.freedomfighter.readersbooks

import android.app.Application
import com.freedomfighter.readersbooks.books.Library
import com.freedomfighter.readersbooks.data.Prefs

class App : Application() {
    // lazy: the content provider can be queried before Application.onCreate has run
    val prefs: Prefs by lazy { Prefs(this) }
    val library: Library by lazy { Library(this) }
    override fun onCreate() { super.onCreate(); prefs; library }
}
