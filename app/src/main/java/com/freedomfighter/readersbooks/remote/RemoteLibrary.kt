package com.freedomfighter.readersbooks.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import com.freedomfighter.readersbooks.data.FolderSort
import com.freedomfighter.readersbooks.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.IOException

/**
 * The drive's e-books as scanned so far. `complete` = the whole tree was walked; otherwise
 * `frontier` holds the folders still to list, so the scan carries on where it stopped.
 * `failed` = folders the server would not list after retries (their contents are missing).
 */
@Serializable
data class Index(
    val scannedAt: Long = 0L,
    val source: String = "",
    val books: List<RemoteBook> = emptyList(),
    val complete: Boolean = true,
    val frontier: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val folders: Int = 0
)

/** What one book of the library is: an issue of a newspaper or a magazine (`news`), a book, or not known yet (null, `tries` so far). Kept for that size and that change. */
@Serializable
data class Kind(val size: Long, val modified: Long, val news: Boolean?, val tries: Int = 0, val date: Long = 0L, val version: Int = 0)

/** 2: gatherings of articles count too, and issues carry their own date. */
const val KINDS_VERSION = 2

/** Where a scan is: folders listed, books found, folders that could not be read. */
data class Progress(val folders: Int, val books: Int, val failed: Int = 0)

/** One folder of the drive that holds books: how many, the latest change among them, and how many folders it spans when it stands for a top folder. */
data class Folder(val path: String, val count: Int, val modified: Long = 0L, val subfolders: Int = 1) { val name: String get() = path.substringAfterLast('/') }

/** Where Reader's Podcasts puts what was said in an episode, a small book each. */
const val TRANSCRIPTS_FOLDER = "transcriptions"
private const val MAX_FOLDERS = 20000
private const val PARALLEL = 3
private const val ATTEMPTS = 3

/**
 * The drive's e-books, kept on disk so the library opens at once. A sync walks the tree (the
 * chosen folders only, when some are chosen) and merges each folder as it is listed: what is
 * known stays on the screen, new books appear, books gone from the drive go, and the list never
 * empties while the drive is walked. Saved every two seconds, a sync that was stopped (lock
 * screen, network gone, error) resumes from the saved frontier instead of starting over. Asked
 * for by hand it runs in [ScanService] so a locked phone does not end it; at the opening of the
 * library it runs quietly in the app. Nothing is downloaded until a book is tapped.
 */
class RemoteLibrary(private val context: Context) {
    private val file = File(context.filesDir, "library-index.json")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _index = MutableStateFlow(runCatching { json.decodeFromString(Index.serializer(), file.readText()) }.getOrDefault(Index()))
    val index: StateFlow<Index> = _index
    private val _progress = MutableStateFlow<Progress?>(null)
    /** Non-null while a scan runs. */
    val progress: StateFlow<Progress?> = _progress
    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error
    private var job: Job? = null
    private var requested: Pair<Settings, Boolean>? = null
    private var chosen: List<String> = emptyList()
    private var excluded: List<String> = emptyList()

    /** Null when nothing usable is set. */
    fun remote(s: Settings): Remote? = runCatching {
        if (s.url.isNotBlank() && s.username.isNotBlank() && s.password.isNotEmpty()) WebDav(s.url, s.username, s.password) else null
    }.getOrNull()

    private fun sourceKey(s: Settings) = "dav:" + s.url.trim() + "@" + s.username.trim()

    /** True when the stored index was made with these very settings. */
    fun matches(s: Settings) = _index.value.source == sourceKey(s) && _index.value.scannedAt > 0L

    val scanning: Boolean get() = job?.isActive == true

    /** The books in the library: those of the chosen folders, or all of them, less the folders left out. */
    fun shown(s: Settings): List<RemoteBook> = _index.value.books.filter { Folders.inLibrary(s.libraryFolders, it.folder, s.libraryExcluded) }

    /** The folders of the library, in the chosen order: every folder, or the top folders only when the library is grouped by them. */
    fun folders(s: Settings): List<Folder> = Folders.sortFolders(Folders.grouped(shown(s), s.libraryTopFolders), s.folderSort)

    /** Every folder the drive was seen to hold books in, chosen or not: what the choice is made from. */
    fun knownFolders(): List<Folder> = Folders.sortFolders(Folders.grouped(_index.value.books, false), FolderSort.NAME)

    /** The books of one folder, alone or with everything inside it; left-out folders stay out. */
    fun books(s: Settings, folder: String, subfolders: Boolean = false): List<RemoteBook> =
        shown(s).filter { if (subfolders) Folders.under(folder, it.folder) else it.folder == folder }

    /** True when a folder holds books in folders inside it. */
    fun hasSubfolders(s: Settings, folder: String): Boolean = shown(s).any { it.folder != folder && Folders.under(folder, it.folder) }

    /**
     * Ask for a sync by hand, in the foreground service: from scratch when `fresh` (or when the
     * account changed), else carrying on an unfinished walk, else the whole library again.
     */
    fun requestScan(s: Settings, fresh: Boolean) {
        if (scanning || remote(s) == null) return
        requested = s to (fresh || !matches(s))
        val intent = Intent(context, ScanService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    /** The sync after the folders of the library were chosen again: the same walk, quietly in the app, without a notification. */
    fun syncQuietly(s: Settings) { if (!scanning && matches(s)) scan(s, false) }

    /** Called by the service once it is in the foreground. Returns the job to wait for. */
    internal fun runRequested(): Job? {
        val (s, fresh) = requested ?: return null
        requested = null
        return scan(s, fresh)
    }

    private fun scan(s: Settings, fresh: Boolean): Job? {
        if (scanning) return job
        val remote = remote(s) ?: return null
        _error.value = null
        chosen = s.libraryFolders
        excluded = s.libraryExcluded
        val idx = _index.value
        // from scratch; or an unfinished walk carried on; or the whole library walked again
        val start = if (fresh) Index(source = sourceKey(s), complete = false)
            else if (!idx.complete) idx
            else idx.copy(complete = false, frontier = emptyList(), folders = 0)
        val fromTop = fresh || idx.complete
        _progress.value = Progress(start.folders, start.books.size, start.failed.size)
        job = scope.launch {
            try {
                walk(remote, start, fromTop)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _error.value = e
            } finally {
                _progress.value = null
            }
        }
        return job
    }

    private suspend fun walk(remote: Remote, start: Index, fromTop: Boolean) = coroutineScope {
        var idx = start
        val roots = Folders.roots(chosen).map { remote.folderRef(it) }
        if (fromTop) {
            // the whole tree at once, when the server allows it and the whole drive is wanted
            val all = if (chosen.isEmpty()) remote.listAll() else null
            if (all != null) { save(idx.copy(books = all, complete = true, frontier = emptyList(), failed = emptyList(), folders = all.map { it.folder }.toSet().size, scannedAt = System.currentTimeMillis())); return@coroutineScope }
            idx = idx.copy(frontier = roots)
        }
        // A pool of workers over one queue, so a slow folder holds nobody else back. Folders that
        // failed last time get another chance now. Each folder listed is merged into the books
        // known so far, so the library stays whole on the screen while the drive is walked.
        // The queue goes by what the last syncs found: the folders that held books first (they
        // are asked for at once, without waiting for the walk to reach them), then the folders
        // on the way to them, then the rest of the drive, where new books are only looked for.
        val books = ArrayList(idx.books)
        val queue = Walk(idx.books.map { it.folder }.filter { Folders.inLibrary(chosen, it, excluded) }.toSet()) { remote.folderPath(it) }
        (idx.frontier + idx.failed).forEach { queue.add(it) }
        val seeded = if (fromTop) queue.known.map { remote.folderRef(it) }.onEach { queue.add(it) }.toSet() else emptySet()
        val inFlight = HashSet<String>()
        val failed = ArrayList<String>()
        var folders = idx.folders
        var fatal: Throwable? = null
        var dirty = false
        val lock = Mutex()
        suspend fun snapshot(complete: Boolean): Index = lock.withLock {
            dirty = false
            idx.copy(books = books.toList(), frontier = if (complete) emptyList() else (queue.all() + inFlight).toList(), failed = failed.toList(), folders = folders, scannedAt = System.currentTimeMillis(), complete = complete)
        }
        val workers = List(PARALLEL) {
            launch(Dispatchers.IO) {
                while (true) {
                    val f = lock.withLock {
                        if (fatal != null || folders + inFlight.size >= MAX_FOLDERS) null
                        else queue.next()?.also { inFlight += it } ?: if (inFlight.isEmpty()) null else ""
                    } ?: break
                    if (f.isEmpty()) { delay(200); continue }
                    val r = listWithRetries(remote, f)
                    lock.withLock {
                        inFlight -= f
                        r.onSuccess {
                            // a folder left out is not walked; its books stay known, so it can be taken back
                            it.folders.filter { d -> !Folders.leftOut(excluded, remote.folderPath(d)) }.forEach { d -> queue.add(d) }; folders++
                            val merged = Folders.merge(books, remote.folderPath(f), it.folders.map { d -> remote.folderPath(d) }, it.books)
                            books.clear(); books += merged
                        }
                        // A refused login puts the folder back on the list: once the login is put
                        // right the scan carries on from it, instead of finding nothing left to do
                        // and calling the drive empty. A chosen folder the drive no longer has
                        // (or no transcripts folder yet) is simply empty.
                        r.onFailure { e ->
                            if (e is Unauthorized) { fatal = e; queue.retry(f) }
                            else if ((f in roots || f in seeded) && e.message == "HTTP 404") { val p = remote.folderPath(f); books.removeAll { Folders.under(p, it.folder) } }
                            else failed += f
                        }
                        dirty = true
                        _progress.value = Progress(folders, books.size, failed.size)
                    }
                }
            }
        }
        // the checkpoint: every two seconds while anything changed, and once more at the end
        val saver = launch {
            while (isActive) {
                delay(2000)
                if (lock.withLock { dirty }) save(snapshot(false))
            }
        }
        workers.joinAll()
        saver.cancel()
        fatal?.let { save(snapshot(false)); throw it }
        save(snapshot(true))
    }

    /**
     * The folders still to list, by what the last syncs found: those that held books, then those
     * on the way to them, then the others; each folder once (by its path: the drive's own address
     * and the one made from a path may be written differently).
     */
    internal class Walk(val known: Set<String>, private val pathOf: (String) -> String) {
        private val onTheWay: Set<String> = known.flatMap { f -> f.split('/').indices.map { i -> f.split('/').take(i).joinToString("/") } }.toSet()
        private val ranks = List(3) { ArrayDeque<String>() }
        private val seen = HashSet<String>()

        fun rank(path: String) = when (path) { in known -> 0; in onTheWay -> 1; else -> 2 }

        fun add(ref: String) {
            val path = pathOf(ref)
            if (seen.add(path)) ranks[rank(path)].addLast(ref)
        }

        /** Back first in line (a refused login: the sync carries on from it). */
        fun retry(ref: String) { seen.add(pathOf(ref)); ranks[0].addFirst(ref) }

        fun next(): String? = ranks.firstOrNull { it.isNotEmpty() }?.removeFirst()

        fun all(): List<String> = ranks.flatten()
    }

    /** Servers hiccup (timeouts, 429, 5xx): three tries with a pause; a refused login stops everything. */
    private suspend fun listWithRetries(remote: Remote, folder: String): Result<Listing> {
        var attempt = 0
        while (true) {
            try { return Result.success(remote.list(folder)) }
            catch (e: Unauthorized) { return Result.failure(e) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("ReadersBooks", "folder $folder: $e")
                // a folder that is not there will not appear by waiting
                if (e !is IOException || e.message == "HTTP 404" || ++attempt >= ATTEMPTS) return Result.failure(e)
                delay(2000L * attempt * attempt)
            }
        }
    }

    private suspend fun save(idx: Index) {
        _index.value = idx
        withContext(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(Index.serializer(), idx)) } }
    }

    @Volatile private var refreshing = false

    /**
     * One folder of the drive looked at again, without walking the rest: the one Reader's Podcasts
     * sends its transcripts to, so that a transcript made this morning is in the library when it is
     * opened, with no scan to ask for. In the background; what cannot be read leaves things as they are.
     */
    fun refreshFolder(s: Settings, name: String = TRANSCRIPTS_FOLDER) {
        if (scanning || refreshing || relisting.isLocked || !matches(s) || !_index.value.complete || Folders.leftOut(s.libraryExcluded, name)) return
        scope.launch { relist(s, name) }
    }

    /**
     * One folder listed again with the folders inside it, and merged into the library: its books
     * replaced by what the drive has now. While a sync runs, the sync brings them; for another
     * account than the index's, nothing is done. Failure leaves the index as it was.
     */
    suspend fun relist(s: Settings, name: String): Result<Unit> {
        val remote = remote(s) ?: return Result.failure(IllegalStateException("no library"))
        if (scanning || !matches(s)) return Result.success(Unit)
        return relisting.withLock { relistNow(remote, s, name) }
    }

    private val relisting = Mutex()

    private suspend fun relistNow(remote: Remote, s: Settings, name: String): Result<Unit> {
        refreshing = true
        val source = _index.value.source
        try {
            return withContext(Dispatchers.IO) {
                val top = remote.folderRef(name)
                val found = ArrayList<RemoteBook>()
                val queue = ArrayDeque(listOf(top))
                var gone = false
                while (queue.isNotEmpty() && found.size < MAX_FOLDERS) {
                    val folder = queue.removeFirst()
                    val listing = try { remote.list(folder) } catch (e: IOException) {
                        if (folder == top && e.message == "HTTP 404") { gone = true; null } else return@withContext Result.failure(e)
                    } ?: break
                    // a folder left out stays out
                    queue += listing.folders.filter { d -> !Folders.leftOut(s.libraryExcluded, remote.folderPath(d)) }; found += listing.books
                }
                if (scanning || _index.value.source != source) return@withContext Result.success(Unit)
                val idx = _index.value
                val merged = idx.books.filter { !Folders.under(name, it.folder) || Folders.leftOut(s.libraryExcluded, it.folder) } + (if (gone) emptyList() else found)
                if (merged.toSet() != idx.books.toSet()) save(idx.copy(books = merged))
                Result.success(Unit)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) { return Result.failure(e)
        } finally { refreshing = false }
    }

    fun cancel() { job?.cancel(); _progress.value = null }

    /** The account was just changed: what the drive answered to the old one no longer stands. */
    fun accountChanged() { _error.value = null }

    fun forget() { cancel(); _index.value = Index(); file.delete(); _kinds.value = emptyMap(); kindsFile.delete() }

    // ---- news: which books are issues of a newspaper or a magazine ----

    private val kindsFile = File(context.filesDir, "library-kinds.json")
    private val kindsSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Kind.serializer())
    private val _kinds: MutableStateFlow<Map<String, Kind>> = MutableStateFlow(
        runCatching { json.decodeFromString(kindsSerializer, kindsFile.readText()) }.getOrDefault(emptyMap()))
    /** Book address → what it is. */
    val kinds: StateFlow<Map<String, Kind>> = _kinds
    private val _sorting = MutableStateFlow<Pair<Int, Int>?>(null)
    /** (done, of) while the books are being told apart. */
    val sorting: StateFlow<Pair<Int, Int>?> = _sorting
    private var sortJob: Job? = null

    private fun kindOf(b: RemoteBook, kinds: Map<String, Kind> = _kinds.value): Kind? =
        kinds[b.ref]?.takeIf { it.version == KINDS_VERSION && it.size == b.size && it.modified == b.modified }

    /** When an issue came out: its own date, else the drive's. */
    fun issueDate(b: RemoteBook, kinds: Map<String, Kind> = _kinds.value): Long = News.date(b, kindOf(b, kinds)?.date ?: 0L)

    /** The library's newspapers, magazines and gatherings of articles, the newest issue first. */
    fun news(s: Settings, kinds: Map<String, Kind> = _kinds.value): List<RemoteBook> =
        shown(s).filter { kindOf(it, kinds)?.news == true }
            .sortedWith(compareByDescending<RemoteBook> { issueDate(it, kinds) }.thenByDescending { News.date(it) })

    /** The EPUBs not looked at yet (or changed since), and those the drive would not answer for, three times at most. */
    fun unsorted(s: Settings): List<RemoteBook> = shown(s).filter { b ->
        b.extension == "epub" && kindOf(b).let { k -> k == null || (k.news == null && k.tries < ATTEMPTS) }
    }

    /**
     * Tells the news from the books, quietly in the app, a few kilobytes read per book, the
     * latest first (that is where the news is). Books on the phone are told by what the reader
     * already found (`known`: address → magazine).
     */
    private val sortQueue = ArrayDeque<RemoteBook>()
    private val sortBusy = HashSet<String>()
    private val sortLock = Mutex()

    fun sortBooks(s: Settings, known: Map<String, Boolean>) {
        val todo = unsorted(s)
        if (todo.isEmpty()) return
        val told = todo.filter { it.ref in known }
        if (told.isNotEmpty()) {
            _kinds.value = _kinds.value + told.associate { it.ref to Kind(it.size, it.modified, known.getValue(it.ref), version = KINDS_VERSION) }
            saveKinds()
        }
        val rest = todo.filter { it.ref !in known }.sortedByDescending { News.date(it) }
        if (rest.isEmpty()) return
        if (sortJob?.isActive == true) {
            // books new to the library go before what the first pass has still to look at
            scope.launch {
                sortLock.withLock {
                    val queued = sortQueue.map { it.ref }.toSet()
                    val new = rest.filter { it.ref !in queued && it.ref !in sortBusy }
                    new.asReversed().forEach { sortQueue.addFirst(it) }
                    _sorting.value?.let { (d, n) -> _sorting.value = d to n + new.size }
                }
            }
            return
        }
        val remote = remote(s) ?: return
        sortJob = scope.launch {
            val lock = sortLock
            val queue = sortQueue
            lock.withLock { queue.clear(); queue.addAll(rest) }
            var done = 0
            _sorting.value = 0 to rest.size
            try {
                (1..PARALLEL).map {
                    launch {
                        while (isActive) {
                            val b = lock.withLock { queue.removeFirstOrNull()?.also { sortBusy += it.ref } } ?: break
                            val (news, date) = try {
                                News.kind { start, length -> remote.readRange(b.ref, start, length, b.size) }
                            } catch (e: Unauthorized) { lock.withLock { queue.clear(); sortBusy -= b.ref }; break
                            } catch (e: CancellationException) { throw e
                            } catch (e: Exception) { null to 0L }
                            lock.withLock {
                                sortBusy -= b.ref
                                val old = _kinds.value[b.ref]
                                val tries = if (news == null) (if (old != null && old.modified == b.modified) old.tries + 1 else 1) else 0
                                _kinds.value = _kinds.value + (b.ref to Kind(b.size, b.modified, news, tries, date, KINDS_VERSION))
                                done++
                                _sorting.value = done to maxOf(_sorting.value?.second ?: 0, done)
                                if (done % 25 == 0) saveKinds()
                            }
                        }
                    }
                }.joinAll()
            } finally {
                _sorting.value = null
                saveKinds()
            }
        }
    }

    private fun saveKinds() {
        runCatching {
            val text = json.encodeToString(kindsSerializer, _kinds.value)
            val tmp = File(kindsFile.path + ".part")
            tmp.writeText(text)
            tmp.renameTo(kindsFile)
        }
    }

    /** Fetch one book into a temporary file. Blocking network; call off the main thread. */
    suspend fun fetch(s: Settings, book: RemoteBook): File {
        val remote = remote(s) ?: throw IllegalStateException("no library")
        val tmp = File(context.cacheDir, "download.tmp")
        remote.download(book, tmp)
        return tmp
    }

    /**
     * A book of the shelf sent to the drive, into this folder of the library, under this file
     * name — or the next free one, when a file of that name is there already: nothing on the
     * drive is ever written over. Listed in the library at once, as the drive names it, with no
     * sync to wait for. Blocking network; call off the main thread.
     */
    suspend fun send(s: Settings, folder: String, name: String, file: File): RemoteBook {
        val remote = remote(s) ?: throw IllegalStateException("no library")
        val stem = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        var chosen = name
        for (n in 2..20) {
            if (!remote.exists(remote.fileRef(folder, chosen))) break
            chosen = "$stem ($n)" + (if (ext.isEmpty()) "" else ".$ext")
        }
        val ref = remote.fileRef(folder, chosen)
        remote.upload(ref, file)
        // the drive's own listing of the folder, so the book is known by the address a sync would give it
        val listed = runCatching { remote.list(remote.folderRef(folder)).books.firstOrNull { it.name == chosen } }.getOrNull()
        val book = listed ?: RemoteBook(chosen, folder, ref, file.length(), System.currentTimeMillis())
        if (matches(s) || _index.value.scannedAt == 0L) {
            val idx = _index.value
            save(idx.copy(source = sourceKey(s), books = idx.books.filter { it.ref != book.ref && !(it.folder == folder && it.name == chosen) } + book))
        }
        return book
    }
}
