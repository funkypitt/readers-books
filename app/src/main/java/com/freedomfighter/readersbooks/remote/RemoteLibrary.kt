package com.freedomfighter.readersbooks.remote

import android.content.Context
import android.content.Intent
import android.os.Build
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

/** Where a scan is: folders listed, books found, folders that could not be read. */
data class Progress(val folders: Int, val books: Int, val failed: Int = 0)

/** One folder of the drive that holds books. */
data class Folder(val path: String, val count: Int) { val name: String get() = path.substringAfterLast('/') }

private const val MAX_FOLDERS = 20000
private const val PARALLEL = 3
private const val ATTEMPTS = 3

/**
 * The drive's e-books, kept on disk so the library opens at once. A scan walks the tree level
 * by level and saves after each level: stopped (lock screen, network gone, error), it resumes
 * from the saved frontier instead of starting over. It runs in [ScanService] so a locked phone
 * does not end it. Nothing is downloaded until a book is tapped.
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

    /** The settings' share link wins over the address; null when nothing usable is set. */
    fun remote(s: Settings): Remote? = runCatching {
        when {
            s.share.isNotBlank() -> KDriveShare(s.share)
            s.url.isNotBlank() && s.username.isNotBlank() && s.password.isNotEmpty() -> WebDav(s.url, s.username, s.password)
            else -> null
        }
    }.getOrNull()

    private fun sourceKey(s: Settings) = if (s.share.isNotBlank()) "share:" + s.share.trim() else "dav:" + s.url.trim() + "@" + s.username.trim()

    /** True when the stored index was made with these very settings. */
    fun matches(s: Settings) = _index.value.source == sourceKey(s) && _index.value.scannedAt > 0L

    val scanning: Boolean get() = job?.isActive == true

    fun folders(): List<Folder> = _index.value.books.groupBy { it.folder }.map { (p, b) -> Folder(p, b.size) }.sortedWith(compareBy({ it.path.lowercase() }))

    fun books(folder: String): List<RemoteBook> = _index.value.books.filter { it.folder == folder }

    /** Ask for a scan: from scratch, or carrying on from the saved frontier and the failed folders. */
    fun requestScan(s: Settings, fresh: Boolean) {
        if (scanning || remote(s) == null) return
        requested = s to (fresh || !matches(s))
        val intent = Intent(context, ScanService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

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
        val start = if (fresh) Index(source = sourceKey(s), complete = false) else _index.value
        _progress.value = Progress(start.folders, start.books.size, start.failed.size)
        job = scope.launch {
            try {
                walk(remote, start, fresh)
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

    private suspend fun walk(remote: Remote, start: Index, fresh: Boolean) = coroutineScope {
        var idx = start
        if (fresh) {
            // the whole tree at once, when the server allows it
            val all = remote.listAll()
            if (all != null) { save(idx.copy(books = all, complete = true, frontier = emptyList(), failed = emptyList(), folders = all.map { it.folder }.toSet().size, scannedAt = System.currentTimeMillis())); return@coroutineScope }
            idx = idx.copy(frontier = remote.roots())
        }
        // A pool of workers over one queue, so a slow folder holds nobody else back. Folders that
        // failed last time get another chance now.
        val books = ArrayList(idx.books)
        val queue = ArrayDeque((idx.frontier + idx.failed).distinct())
        val inFlight = HashSet<String>()
        val failed = ArrayList<String>()
        var folders = idx.folders
        var fatal: Throwable? = null
        var dirty = false
        val lock = Mutex()
        suspend fun snapshot(complete: Boolean): Index = lock.withLock {
            dirty = false
            idx.copy(books = books.toList(), frontier = if (complete) emptyList() else (queue + inFlight).toList(), failed = failed.toList(), folders = folders, scannedAt = System.currentTimeMillis(), complete = complete)
        }
        val workers = List(PARALLEL) {
            launch(Dispatchers.IO) {
                while (true) {
                    val f = lock.withLock {
                        if (fatal != null || folders + inFlight.size >= MAX_FOLDERS) null
                        else queue.removeFirstOrNull()?.also { inFlight += it } ?: if (inFlight.isEmpty()) null else ""
                    } ?: break
                    if (f.isEmpty()) { delay(200); continue }
                    val r = listWithRetries(remote, f)
                    lock.withLock {
                        inFlight -= f
                        r.onSuccess { queue += it.folders; books += it.books; folders++ }
                        // A refused login puts the folder back on the list: once the login is put
                        // right the scan carries on from it, instead of finding nothing left to do
                        // and calling the drive empty.
                        r.onFailure { e -> if (e is Unauthorized) { fatal = e; queue.addFirst(f) } else failed += f }
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

    /** Servers hiccup (timeouts, 429, 5xx): three tries with a pause; a refused login stops everything. */
    private suspend fun listWithRetries(remote: Remote, folder: String): Result<Listing> {
        var attempt = 0
        while (true) {
            try { return Result.success(remote.list(folder)) }
            catch (e: Unauthorized) { return Result.failure(e) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e !is IOException || ++attempt >= ATTEMPTS) return Result.failure(e)
                delay(2000L * attempt * attempt)
            }
        }
    }

    private suspend fun save(idx: Index) {
        _index.value = idx
        withContext(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(Index.serializer(), idx)) } }
    }

    fun cancel() { job?.cancel(); _progress.value = null }

    /** The account was just changed: what the drive answered to the old one no longer stands. */
    fun accountChanged() { _error.value = null }

    fun forget() { cancel(); _index.value = Index(); file.delete() }

    /** Fetch one book into a temporary file. Blocking network; call off the main thread. */
    suspend fun fetch(s: Settings, book: RemoteBook): File {
        val remote = remote(s) ?: throw IllegalStateException("no library")
        val tmp = File(context.cacheDir, "download.tmp")
        remote.download(book, tmp)
        return tmp
    }
}
