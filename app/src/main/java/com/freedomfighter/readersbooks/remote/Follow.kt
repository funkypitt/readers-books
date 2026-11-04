package com.freedomfighter.readersbooks.remote

import com.freedomfighter.readersbooks.App
import com.freedomfighter.readersbooks.books.Entry
import com.freedomfighter.readersbooks.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * A folder of the drive followed for its new books (the day's paper, this week's magazine).
 * `seen` = the books of the folder that are no longer new: there when it was first followed
 * (all but the latest day's), fetched since, or removed from the phone after being read.
 */
@Serializable
data class Followed(val folder: String, val seen: Set<String> = emptySet(), val checkedAt: Long = 0L)

@Serializable
private data class FollowState(val folders: List<Followed> = emptyList())

/** Where the fetching of a followed folder's new books is. `total` = 0 while the folder is being looked at. */
data class Fetching(val done: Int, val total: Int)

object Follow {
    /**
     * What counts as seen when a folder is first followed: everything in it but the books of its
     * latest day, which are new at once — following a folder of daily papers gives today's.
     */
    fun firstSeen(books: List<RemoteBook>, folder: String, zone: ZoneId = ZoneId.systemDefault()): Set<String> {
        val inside = books.filter { Folders.under(folder, it.folder) }
        val newest = inside.maxOfOrNull { it.modified } ?: return emptySet()
        if (newest <= 0L) return emptySet()
        val day = Instant.ofEpochMilli(newest).atZone(zone).toLocalDate()
        return inside.filter { it.modified <= 0L || Instant.ofEpochMilli(it.modified).atZone(zone).toLocalDate() != day }.map { it.ref }.toSet()
    }

    /** The new books of a followed folder (and of the folders inside it), newest first: neither seen nor on the phone. */
    fun fresh(books: List<RemoteBook>, f: Followed, onPhone: Set<String>): List<RemoteBook> =
        books.filter { Folders.under(f.folder, it.folder) && it.ref !in f.seen && it.ref !in onPhone }
            .sortedWith(compareByDescending<RemoteBook> { it.modified }.thenByDescending { it.name.lowercase() })

    /** What is still worth remembering as seen: the books the folder holds now. */
    fun prune(f: Followed, books: List<RemoteBook>): Followed {
        val there = books.filter { Folders.under(f.folder, it.folder) }.map { it.ref }.toSet()
        return f.copy(seen = f.seen.filter { it in there }.toSet())
    }
}

/**
 * The followed folders, kept in `follow.json`. Nothing is fetched by itself: a tap on a followed
 * folder lists that folder again (and nothing else), then fetches its new books one after the
 * other, in the background so that leaving the screen does not stop them.
 */
class Following(private val app: App) {
    private val file = File(app.filesDir, "follow.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _folders = MutableStateFlow(runCatching { json.decodeFromString(FollowState.serializer(), file.readText()).folders }.getOrDefault(emptyList()))
    val folders: StateFlow<List<Followed>> = _folders
    private val _fetching = MutableStateFlow<Map<String, Fetching>>(emptyMap())
    val fetching: StateFlow<Map<String, Fetching>> = _fetching
    private val _failed = MutableStateFlow<Map<String, Throwable>>(emptyMap())
    /** Why the last tap on a folder did not bring everything, until the next one. */
    val failed: StateFlow<Map<String, Throwable>> = _failed
    private val running = HashMap<String, Deferred<Result<List<Entry>>>>()

    @Synchronized
    private fun update(change: (List<Followed>) -> List<Followed>) {
        val next = change(_folders.value)
        _folders.value = next
        runCatching { file.writeText(json.encodeToString(FollowState.serializer(), FollowState(next))) }
    }

    fun isFollowed(folder: String) = _folders.value.any { it.folder == folder }

    fun follow(folder: String) {
        if (folder.isEmpty() || isFollowed(folder)) return
        val books = app.remote.index.value.books
        update { it + Followed(folder, Follow.firstSeen(books, folder)) }
    }

    fun unfollow(folder: String) = update { l -> l.filter { it.folder != folder } }

    /** A book of the library left the phone: it does not come back as new. */
    fun removed(e: Entry) {
        val ref = e.source ?: return
        if (_folders.value.none { ref !in it.seen }) return
        update { l -> l.map { it.copy(seen = it.seen + ref) } }
    }

    /** The new books of a followed folder in the last listing of the drive: no network. */
    fun fresh(f: Followed, books: List<RemoteBook>, shelf: List<Entry>): List<RemoteBook> =
        Follow.fresh(books, f, shelf.mapNotNull { it.source }.toSet())

    /** All the drive's forgotten: so are the folders it had. */
    fun forget() = update { emptyList() }

    /**
     * One tap: the folder listed again, then its new books fetched, oldest first. The result is
     * the books fetched; a failure part way keeps those already on the phone.
     */
    fun fetch(s: Settings, folder: String): Deferred<Result<List<Entry>>> = synchronized(running) {
        running[folder]?.takeIf { it.isActive }?.let { return it }
        scope.async {
            _failed.update { it - folder }
            _fetching.update { it + (folder to Fetching(0, 0)) }
            val got = ArrayList<Entry>()
            try {
                app.remote.relist(s, folder).onFailure { e -> _failed.update { it + (folder to e) }; return@async Result.failure(e) }
                val f = _folders.value.firstOrNull { it.folder == folder } ?: return@async Result.success(got)
                val books = app.remote.index.value.books
                val todo = Follow.fresh(books, f, app.library.books.value.mapNotNull { it.source }.toSet()).reversed()
                _fetching.update { it + (folder to Fetching(0, todo.size)) }
                for (b in todo) {
                    val r = app.downloads.fetch(s, b)
                    r.onSuccess { e -> got += e; update { l -> l.map { if (it.folder == folder) it.copy(seen = it.seen + b.ref) else it } } }
                    r.onFailure { e -> _failed.update { it + (folder to e) }; return@async if (got.isEmpty()) Result.failure(e) else Result.success(got) }
                    _fetching.update { it + (folder to Fetching(got.size, todo.size)) }
                }
                update { l -> l.map { if (it.folder == folder) Follow.prune(it, app.remote.index.value.books).copy(checkedAt = System.currentTimeMillis()) else it } }
                Result.success(got)
            } finally { _fetching.update { it - folder } }
        }.also { running[folder] = it }
    }
}
