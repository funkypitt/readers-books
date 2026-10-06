package com.freedomfighter.readersbooks.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** E-book files a library scan keeps. No PDF; no TXT either, a drive is full of them. */
val BOOK_EXTENSIONS = setOf("epub", "mobi", "azw", "azw3", "fb2")

/** A book seen on the drive: not downloaded until tapped. */
@Serializable
data class RemoteBook(
    val name: String,
    /** Folder path from the root of the drive, "" for the root, "/" between levels, no trailing slash. */
    val folder: String,
    /** What fetches it: the file's URL (WebDAV) or id (share link). */
    val ref: String,
    val size: Long = 0L,
    /** Last change, epoch millis; 0 when the server did not say. */
    val modified: Long = 0L
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val title: String get() = name.substringBeforeLast('.').replace('_', ' ')
    val folderName: String get() = folder.substringAfterLast('/')
}

/** Where a scan is: folders seen so far, books found so far. */
data class Progress(val folders: Int, val books: Int)

/** Something that lists and fetches books: a WebDAV server or a kDrive share link. */
interface Remote {
    /** Walk the whole tree and return every e-book, folder by folder. */
    suspend fun scan(onProgress: (Progress) -> Unit): List<RemoteBook>
    suspend fun download(book: RemoteBook, dest: File)
}

private val http: OkHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).followRedirects(true).build()
}

/** Hidden folders and the server's own bins are never worth walking. */
private fun skipFolder(name: String) = name.startsWith(".") || name.equals("trash", true) || name.equals("lost+found", true)

private const val MAX_FOLDERS = 5000
private const val PARALLEL = 4

/** How many folders are walked at once, in both clients. */
private suspend fun <T> walk(root: T, list: suspend (T) -> Pair<List<T>, List<RemoteBook>>, onProgress: (Progress) -> Unit): List<RemoteBook> = coroutineScope {
    val found = ArrayList<RemoteBook>()
    var level = listOf(root)
    var folders = 0
    val gate = Semaphore(PARALLEL)
    while (level.isNotEmpty() && folders < MAX_FOLDERS) {
        val results = level.map { dir -> async(Dispatchers.IO) { gate.withPermit { ensureActive(); list(dir) } } }.awaitAll()
        val next = ArrayList<T>()
        for ((dirs, books) in results) { next += dirs; found += books }
        folders += level.size
        onProgress(Progress(folders, found.size))
        level = next
    }
    found
}

// ---------------------------------------------------------------------------------------------
// WebDAV (kDrive's WebDAV address with an application password, Nextcloud, any other server).
// ---------------------------------------------------------------------------------------------

class WebDav(url: String, private val username: String, private val password: String) : Remote {
    private val base: String = url.trim().let { if (it.endsWith("/")) it else "$it/" }
    private val auth = Credentials.basic(username, password, Charsets.UTF_8)

    private class Item(val href: String, val isDir: Boolean, val size: Long, val modified: Long)

    override suspend fun scan(onProgress: (Progress) -> Unit): List<RemoteBook> {
        // One request for the whole tree when the server allows it (many refuse or quietly cut at one level).
        val deep = runCatching { propfind(base, "infinity") }.getOrNull()
        if (deep != null && deep.any { it.isDir && relative(it.href).count { c -> c == '/' } >= 1 }) {
            onProgress(Progress(deep.count { it.isDir } + 1, 0))
            return deep.filter { !it.isDir }.mapNotNull { toBook(it) }.also { onProgress(Progress(deep.count { d -> d.isDir } + 1, it.size)) }
        }
        return walk(base, { dir ->
            val items = propfind(dir, "1")
            val dirs = items.filter { it.isDir && !skipFolder(it.href.trimEnd('/').substringAfterLast('/').decoded()) }.map { it.href }
            dirs to items.filter { !it.isDir }.mapNotNull { toBook(it) }
        }, onProgress)
    }

    private fun toBook(i: Item): RemoteBook? {
        val rel = relative(i.href)
        val name = rel.substringAfterLast('/').decoded()
        if (name.substringAfterLast('.', "").lowercase() !in BOOK_EXTENSIONS) return null
        val folder = rel.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.joinToString("/") { it.decoded() }
        if (folder.split('/').any { skipFolder(it) }) return null
        return RemoteBook(name, folder, i.href, i.size, i.modified)
    }

    /** The path of an absolute URL below the base, still percent-encoded. */
    private fun relative(href: String): String {
        val basePath = URI(base).rawPath.trimEnd('/')
        val path = runCatching { URI(href).rawPath }.getOrDefault(href)
        return path.removePrefix(basePath).trim('/')
    }

    private fun String.decoded() = runCatching { URLDecoder.decode(replace("+", "%2B"), "UTF-8") }.getOrDefault(this)

    private fun propfind(url: String, depth: String): List<Item> {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val req = Request.Builder().url(url).method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
            .header("Authorization", auth).header("Depth", depth).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw Unauthorized()
            if (r.code != 207 && !r.isSuccessful) throw IOException("HTTP ${r.code}")
            val xml = r.body?.string() ?: throw IOException("empty answer")
            return parse(xml, url)
        }
    }

    private fun parse(xml: String, requested: String): List<Item> {
        val out = ArrayList<Item>()
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(xml.reader())
        var href: String? = null; var dir = false; var size = 0L; var modified = 0L; var inResource = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "response" -> { href = null; dir = false; size = 0L; modified = 0L }
                    "href" -> href = p.nextText().trim()
                    "resourcetype" -> inResource = true
                    "collection" -> if (inResource) dir = true
                    "getcontentlength" -> size = p.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> modified = parseDate(p.nextText().trim())
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "resourcetype" -> inResource = false
                    "response" -> href?.let { h ->
                        val full = runCatching { URI(requested).resolve(h).toString() }.getOrElse { if (h.startsWith("http")) h else URI(requested).let { u -> "${u.scheme}://${u.rawAuthority}" } + h }
                        if (full.trimEnd('/') != requested.trimEnd('/')) out += Item(full, dir, size, modified)
                    }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseDate(s: String): Long = runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0L)

    override suspend fun download(book: RemoteBook, dest: File) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(book.ref).get().header("Authorization", auth).build()
            http.newCall(req).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw Unauthorized()
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body?.byteStream()?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: throw IOException("empty answer")
            }
        }
    }
}

class Unauthorized : IOException("wrong login")

// ---------------------------------------------------------------------------------------------
// A public kDrive share link (https://kdrive.infomaniak.com/app/share/<drive>/<uuid>): no login.
// ---------------------------------------------------------------------------------------------

class KDriveShare(link: String) : Remote {
    private val driveId: String
    private val uuid: String
    private var rootId: Long = -1
    private val json = Json { ignoreUnknownKeys = true }

    init {
        val m = Regex("""/app/share/(\d+)/([a-z0-9-]+)""").find(link.trim()) ?: throw IllegalArgumentException("not a kDrive share link")
        driveId = m.groupValues[1]; uuid = m.groupValues[2]
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw Unauthorized()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: throw IOException("empty answer")
        }
    }

    private fun root(): Long {
        if (rootId >= 0) return rootId
        val data = json.parseToJsonElement(get("$BASE/2/app/$driveId/share/$uuid/init")).jsonObject["data"]?.jsonObject ?: throw IOException("no data")
        rootId = data["file_id"]?.jsonPrimitive?.longOrNull ?: throw IOException("no file id")
        return rootId
    }

    private class Dir(val id: Long, val path: String)

    override suspend fun scan(onProgress: (Progress) -> Unit): List<RemoteBook> {
        val top = Dir(withContext(Dispatchers.IO) { root() }, "")
        return walk(top, { dir ->
            val dirs = ArrayList<Dir>(); val books = ArrayList<RemoteBook>()
            var cursor: String? = null
            do {
                val url = "$BASE/3/app/$driveId/share/$uuid/files/${dir.id}/files?limit=200" + (cursor?.let { "&cursor=$it" } ?: "")
                val rootObj = json.parseToJsonElement(get(url)).jsonObject
                for (e in rootObj["data"]?.jsonArray ?: break) {
                    val o = e.jsonObject
                    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val id = o["id"]?.jsonPrimitive?.longOrNull ?: continue
                    if (o["type"]?.jsonPrimitive?.contentOrNull == "dir") { if (!skipFolder(name)) dirs += Dir(id, if (dir.path.isEmpty()) name else dir.path + "/" + name) }
                    else if (name.substringAfterLast('.', "").lowercase() in BOOK_EXTENSIONS)
                        books += RemoteBook(name, dir.path, id.toString(), o["size"]?.jsonPrimitive?.longOrNull ?: 0L, (o["last_modified_at"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000)
                }
                cursor = if (rootObj["has_more"]?.jsonPrimitive?.booleanOrNull == true) rootObj["cursor"]?.jsonPrimitive?.contentOrNull else null
            } while (cursor != null)
            dirs to books
        }, onProgress)
    }

    override suspend fun download(book: RemoteBook, dest: File) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$BASE/2/app/$driveId/share/$uuid/files/${book.ref}/download").get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body?.byteStream()?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: throw IOException("empty answer")
            }
        }
    }

    companion object { private const val BASE = "https://kdrive.infomaniak.com" }
}
