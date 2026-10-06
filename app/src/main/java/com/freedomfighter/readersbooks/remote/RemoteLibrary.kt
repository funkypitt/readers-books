package com.freedomfighter.readersbooks.remote

import android.content.Context
import com.freedomfighter.readersbooks.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Index(val scannedAt: Long = 0L, val source: String = "", val books: List<RemoteBook> = emptyList())

/** One folder of the drive that holds books. */
data class Folder(val path: String, val count: Int) { val name: String get() = path.substringAfterLast('/') }

/**
 * The drive's e-books as last scanned, kept on disk so the library opens at once; a scan walks
 * the whole drive again. Nothing is downloaded until a book is tapped.
 */
class RemoteLibrary(private val context: Context) {
    private val file = File(context.filesDir, "library-index.json")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _index = MutableStateFlow(runCatching { json.decodeFromString(Index.serializer(), file.readText()) }.getOrDefault(Index()))
    val index: StateFlow<Index> = _index
    private val _progress = MutableStateFlow<Progress?>(null)
    /** Non-null while a scan runs. */
    val progress: StateFlow<Progress?> = _progress
    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error
    private var job: Job? = null

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

    fun folders(): List<Folder> = _index.value.books.groupBy { it.folder }.map { (p, b) -> Folder(p, b.size) }.sortedWith(compareBy({ it.path.lowercase() }))

    fun books(folder: String): List<RemoteBook> = _index.value.books.filter { it.folder == folder }

    fun scan(s: Settings) {
        if (job?.isActive == true) return
        val remote = remote(s) ?: return
        _error.value = null
        _progress.value = Progress(0, 0)
        job = scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { remote.scan { p -> _progress.value = p } } }
            r.onSuccess { books ->
                val idx = Index(System.currentTimeMillis(), sourceKey(s), books.sortedBy { it.name.lowercase() })
                _index.value = idx
                withContext(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(Index.serializer(), idx)) } }
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) _error.value = it }
            _progress.value = null
        }
    }

    fun cancel() { job?.cancel(); _progress.value = null }

    fun forget() { cancel(); _index.value = Index(); file.delete() }

    /** Fetch one book into a temporary file. Blocking network; call off the main thread. */
    suspend fun fetch(s: Settings, book: RemoteBook): File {
        val remote = remote(s) ?: throw IllegalStateException("no library")
        val tmp = File(context.cacheDir, "download.tmp")
        remote.download(book, tmp)
        return tmp
    }
}
