package com.freedomfighter.readersbooks.remote

import com.freedomfighter.readersbooks.App
import com.freedomfighter.readersbooks.books.Entry
import com.freedomfighter.readersbooks.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Books of the drive fetched onto the shelf, one after the other: the one tapped to be read, and
 * those asked for from a list to be kept on the phone, which come in the background. One copy
 * per book of the drive: a book there already is not fetched again unless asked to be.
 */
class Downloads(private val app: App) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val one = Mutex()
    private val _pending = MutableStateFlow<List<String>>(emptyList())
    /** The books waiting for their turn or being fetched, by their reference on the drive. */
    val pending: StateFlow<List<String>> = _pending
    private val _failed = MutableStateFlow<Pair<RemoteBook, Throwable>?>(null)
    /** The last background fetch that did not go through, until seen. */
    val failed: StateFlow<Pair<RemoteBook, Throwable>?> = _failed
    fun seen() { _failed.value = null }

    /**
     * The book on the shelf: the copy there, or the one fetched now (`again` fetches even then,
     * for a copy newer on the drive; the place in the book is kept). `keep` marks it as kept on the phone.
     */
    suspend fun fetch(s: Settings, book: RemoteBook, keep: Boolean = false, again: Boolean = false): Result<Entry> {
        if (!again) app.library.bySource(book.ref)?.let { e ->
            if (keep && !e.kept) app.library.setKept(e.id, true)
            return Result.success(app.library.get(e.id) ?: e)
        }
        _pending.update { it + book.ref }
        try {
            return one.withLock {
                withContext(Dispatchers.IO) { runCatching { app.library.importFile(app.remote.fetch(s, book), book.name, book.ref, book.modified, keep).getOrThrow() } }
            }
        } finally { _pending.update { it - book.ref } }
    }

    /** Asked for from a list: fetched in the background, to be kept on the phone (or fetched again, for the drive's newer copy). */
    fun keep(s: Settings, book: RemoteBook, again: Boolean = false) {
        if (book.ref in _pending.value) return
        scope.launch { fetch(s, book, keep = if (again) app.library.bySource(book.ref)?.kept == true else true, again = again).onFailure { _failed.value = book to it } }
    }

    /** True when the drive's copy of a book of the shelf changed after it was fetched. */
    fun newer(entry: Entry, book: RemoteBook): Boolean = entry.sourceModified > 0L && book.modified > entry.sourceModified
}
