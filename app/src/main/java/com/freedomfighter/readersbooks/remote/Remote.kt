package com.freedomfighter.readersbooks.remote

import kotlinx.coroutines.Dispatchers
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
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
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
    /** What fetches it: the file's URL. */
    val ref: String,
    val size: Long = 0L,
    /** Last change, epoch millis; 0 when the server did not say. */
    val modified: Long = 0L,
    /** When the file was made on the drive, epoch millis; 0 when the server did not say. */
    val created: Long = 0L
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val title: String get() = name.substringBeforeLast('.').replace('_', ' ')
    val folderName: String get() = folder.substringAfterLast('/')
    /**
     * The author as far as the drive tells it: what precedes " - " in the file name
     * ("Author - Title.epub"), else the folder's name, where libraries file one author per folder.
     */
    val author: String get() = if (title.contains(" - ")) title.substringBefore(" - ").trim() else folderName
}

/** One folder's listing: the references of its sub-folders, and its books. */
class Listing(val folders: List<String>, val books: List<RemoteBook>)

/**
 * Something that lists and fetches books: a WebDAV server. A folder is
 * named by an opaque reference string, so a scan can be stopped and carried on later.
 */
interface Remote {
    /** Where a scan starts. May need the network. */
    suspend fun roots(): List<String>
    /** The whole tree in one request when the server allows it; null otherwise. */
    suspend fun listAll(): List<RemoteBook>? = null
    suspend fun list(folder: String): Listing
    suspend fun download(book: RemoteBook, dest: File)
    /** The address of a file named `name` in the folder at this path below the top of the drive. */
    fun fileRef(folder: String, name: String): String
    /** Whether something is at this address. */
    suspend fun exists(ref: String): Boolean
    /** Put a file on the drive at this address, in place of whatever was there. */
    suspend fun upload(ref: String, file: File)
    /** The reference of a folder named by its path below the top of the drive ("" = the top). */
    fun folderRef(path: String): String
    /** The path below the top of the drive of a folder's reference, decoded. */
    fun folderPath(ref: String): String
    /**
     * Some bytes of a file: `length` from `start`, or its last `length` when `start` is null; with
     * the file's size. Null when the server sends the whole file instead (no byte ranges).
     */
    suspend fun readRange(ref: String, start: Long?, length: Int, size: Long): Pair<ByteArray, Long>? = null
}

class Unauthorized : IOException("wrong login")

internal val http: OkHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).followRedirects(true).build()
}

/** Hidden folders and the server's own bins are never worth walking. */
internal fun skipFolder(name: String) = name.startsWith(".") || name.equals("trash", true) || name.equals("lost+found", true)

internal fun check(code: Int) {
    if (code == 401 || code == 403) throw Unauthorized()
    if (code != 207 && code !in 200..299) throw IOException("HTTP $code")
}

// ---------------------------------------------------------------------------------------------
// WebDAV (kDrive's WebDAV address with an application password, Nextcloud, any other server).
// ---------------------------------------------------------------------------------------------

class WebDav(url: String, private val username: String, private val password: String) : Remote {
    private val base: String = url.trim().let { if (it.endsWith("/")) it else "$it/" }
    private val auth = Credentials.basic(username, password, Charsets.UTF_8)

    private class Item(val href: String, val isDir: Boolean, val size: Long, val modified: Long, val created: Long = 0L)

    override suspend fun roots() = listOf(base)

    override fun folderRef(path: String): String =
        base + path.split('/').filter { it.isNotEmpty() }.joinToString("") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") + "/" }

    override fun fileRef(folder: String, name: String): String = folderRef(folder) + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    override fun folderPath(ref: String): String = relative(ref).split('/').filter { it.isNotEmpty() }.joinToString("/") { it.decoded() }

    /** One request for the whole tree. Many servers refuse it or quietly answer for one level only. */
    override suspend fun listAll(): List<RemoteBook>? = withContext(Dispatchers.IO) {
        val deep = runCatching { propfind(base, "infinity", http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()) }.getOrNull() ?: return@withContext null
        if (deep.none { it.isDir && relative(it.href).count { c -> c == '/' } >= 1 }) return@withContext null
        deep.filter { !it.isDir }.mapNotNull { toBook(it) }
    }

    override suspend fun list(folder: String): Listing = withContext(Dispatchers.IO) {
        val items = propfind(folder, "1", http)
        Listing(
            items.filter { it.isDir && !skipFolder(it.href.trimEnd('/').substringAfterLast('/').decoded()) }.map { it.href },
            items.filter { !it.isDir }.mapNotNull { toBook(it) }
        )
    }

    private fun toBook(i: Item): RemoteBook? {
        val rel = relative(i.href)
        val name = rel.substringAfterLast('/').decoded()
        if (name.substringAfterLast('.', "").lowercase() !in BOOK_EXTENSIONS) return null
        val folder = rel.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.joinToString("/") { it.decoded() }
        if (folder.split('/').any { skipFolder(it) }) return null
        return RemoteBook(name, folder, i.href, i.size, i.modified, i.created)
    }

    /** The path of an absolute URL below the base, still percent-encoded. */
    private fun relative(href: String): String {
        val basePath = URI(base).rawPath.trimEnd('/')
        val path = runCatching { URI(href).rawPath }.getOrDefault(href)
        return path.removePrefix(basePath).trim('/')
    }

    private fun String.decoded() = runCatching { URLDecoder.decode(replace("+", "%2B"), "UTF-8") }.getOrDefault(this)

    private fun propfind(url: String, depth: String, client: OkHttpClient): List<Item> {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:creationdate/></d:prop></d:propfind>"""
        val req = Request.Builder().url(url).method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
            .header("Authorization", auth).header("Depth", depth).build()
        client.newCall(req).execute().use { r ->
            check(r.code)
            val stream = r.body?.byteStream() ?: throw IOException("empty answer")
            return parse(stream, url)
        }
    }

    /** Streams the multistatus answer: a whole drive may be tens of megabytes. */
    private fun parse(stream: InputStream, requested: String): List<Item> {
        val out = ArrayList<Item>()
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(stream, null)
        var href: String? = null; var dir = false; var size = 0L; var modified = 0L; var created = 0L; var inResource = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "response" -> { href = null; dir = false; size = 0L; modified = 0L; created = 0L }
                    "href" -> href = p.nextText().trim()
                    "resourcetype" -> inResource = true
                    "collection" -> if (inResource) dir = true
                    "getcontentlength" -> size = p.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> modified = parseDate(p.nextText().trim())
                    "creationdate" -> created = parseIso(p.nextText().trim())
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "resourcetype" -> inResource = false
                    "response" -> href?.let { h ->
                        val full = runCatching { URI(requested).resolve(h).toString() }.getOrElse { if (h.startsWith("http")) h else URI(requested).let { u -> "${u.scheme}://${u.rawAuthority}" } + h }
                        if (full.trimEnd('/') != requested.trimEnd('/')) out += Item(full, dir, size, modified, created)
                    }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseDate(s: String): Long = runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0L)

    /** WebDAV's creationdate is ISO 8601; some servers send the RFC 1123 form anyway. */
    private fun parseIso(s: String): Long = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrElse { parseDate(s) }

    /**
     * With the file's size known, the end is asked for from where it starts: kDrive drops the
     * connection on a range from the end longer than the file. A connection the server closed
     * gets two more tries.
     */
    override suspend fun readRange(ref: String, start: Long?, length: Int, size: Long): Pair<ByteArray, Long>? = withContext(Dispatchers.IO) {
        var from = start
        var len = length.toLong()
        if (from == null && size > 0) from = maxOf(0L, size - len)
        if (from != null && size > 0) len = minOf(len, maxOf(1L, size - from))
        val spec = if (from == null) "bytes=-$len" else "bytes=$from-${from + len - 1}"
        var attempt = 0
        while (true) {
            try {
                val req = Request.Builder().url(ref).get().header("Authorization", auth).header("Range", spec).header("Accept-Encoding", "identity").build()
                http.newCall(req).execute().use { r ->
                    if (r.code == 416) return@withContext ByteArray(0) to 0L
                    check(r.code)
                    if (r.code != 206) return@withContext null
                    val total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: 0L
                    return@withContext (r.body?.bytes() ?: ByteArray(0)) to total
                }
            } catch (e: Unauthorized) {
                throw e
            } catch (e: IOException) {
                if (++attempt >= 3) throw e
                kotlinx.coroutines.delay(500L * attempt)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    override suspend fun download(book: RemoteBook, dest: File) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(book.ref).get().header("Authorization", auth).build()
            http.newCall(req).execute().use { r ->
                check(r.code)
                r.body?.byteStream()?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: throw IOException("empty answer")
            }
        }
    }

    override suspend fun exists(ref: String): Boolean = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(ref).head().header("Authorization", auth).build()).execute().use { r ->
            if (r.code == 404) return@withContext false
            check(r.code); true
        }
    }

    /** A book may be large and the line slow: the upload gets ten minutes. */
    override suspend fun upload(ref: String, file: File) {
        withContext(Dispatchers.IO) {
            val client = http.newBuilder().writeTimeout(10, TimeUnit.MINUTES).build()
            val req = Request.Builder().url(ref).put(file.asRequestBody("application/octet-stream".toMediaType())).header("Authorization", auth).build()
            client.newCall(req).execute().use { check(it.code) }
        }
    }
}
