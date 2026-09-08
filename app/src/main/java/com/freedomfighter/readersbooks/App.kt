package com.freedomfighter.readersbooks

import android.app.Application
import com.freedomfighter.readersbooks.books.Library
import com.freedomfighter.readersbooks.data.Prefs

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var library: Library
    override fun onCreate() { super.onCreate(); prefs = Prefs(this); library = Library(this) }
}
