package com.freedomfighter.readersbooks.remote

import java.util.zip.Inflater

/**
 * News: the newspapers' and magazines' issues among the library's books, told by their contents
 * page as the reader tells a magazine (a section and a title per article in the nav; or articles
 * that start with a section and a headline), read on the drive without fetching the book: the end
 * of the EPUB (its zip directory), then its package and its nav, a few kilobytes each. Rule for
 * rule the desktop app's epub_is_news.
 */
object News {
    /** `read(start, length)` as [Remote.readRange]; start null = the file's last `length` bytes. */
    fun interface Reader { suspend fun read(start: Long?, length: Int): Pair<ByteArray, Long>? }

    private class Entry(val method: Int, val compressed: Long, val offset: Long)

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

    private suspend fun directory(read: Reader, tailSize: Int = 65536): Map<String, Entry>? {
        val (tail, total) = read.read(null, tailSize) ?: return null
        var at = -1
        for (i in tail.size - 22 downTo 0) if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte()) { at = i; break }
        if (at < 0) return null
        val cdSize = u32(tail, at + 12)
        val cdOffset = u32(tail, at + 16)
        val tailStart = (if (total > 0) total else tail.size.toLong()) - tail.size
        val cd = if (cdOffset >= tailStart) {
            val from = (cdOffset - tailStart).toInt()
            tail.copyOfRange(from, minOf(tail.size, from + cdSize.toInt()))
        } else read.read(cdOffset, cdSize.toInt())?.first ?: return null
        val out = HashMap<String, Entry>()
        var i = 0
        while (i + 46 <= cd.size && cd[i] == 0x50.toByte() && cd[i + 1] == 0x4b.toByte() && cd[i + 2] == 1.toByte() && cd[i + 3] == 2.toByte()) {
            val method = u16(cd, i + 10)
            val comp = u32(cd, i + 20)
            val n = u16(cd, i + 28)
            val x = u16(cd, i + 30)
            val c = u16(cd, i + 32)
            val offset = u32(cd, i + 42)
            if (i + 46 + n > cd.size) break
            out[String(cd, i + 46, n, Charsets.UTF_8)] = Entry(method, comp, offset)
            i += 46 + n + x + c
        }
        return out
    }

    private suspend fun member(read: Reader, entries: Map<String, Entry>, wanted: String, limit: Long = 400_000): String? {
        val name = if (wanted in entries) wanted else entries.keys.firstOrNull { it.equals(wanted, ignoreCase = true) } ?: return null
        val e = entries.getValue(name)
        if (e.compressed > limit) return null
        val head = read.read(e.offset, (30 + 1024 + e.compressed).toInt())?.first ?: return null
        if (head.size < 30 || head[0] != 0x50.toByte() || head[1] != 0x4b.toByte() || head[2] != 3.toByte() || head[3] != 4.toByte()) return null
        val n = u16(head, 26)
        val x = u16(head, 28)
        val from = 30 + n + x
        val data = head.copyOfRange(minOf(from, head.size), minOf(head.size, from + e.compressed.toInt()))
        val bytes = when (e.method) {
            0 -> data
            8 -> runCatching {
                val inf = Inflater(true)
                inf.setInput(data)
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384)
                while (!inf.finished()) {
                    val k = inf.inflate(buf)
                    if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    out.write(buf, 0, k)
                }
                inf.end()
                out.toByteArray()
            }.getOrNull() ?: return null
            else -> return null
        }
        return com.freedomfighter.readersbooks.books.Quotes.double(String(bytes, Charsets.UTF_8))
    }

    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (seg in path.split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += seg
        }
        return parts.joinToString("/")
    }

    private val ITEM = Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ID = Regex("\\bid=\"([^\"]+)\"")
    private val HREF = Regex("\\bhref=\"([^\"]+)\"")
    private val NAV = Regex("properties=\"[^\"]*\\bnav\\b")
    private val ITEMREF = Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"")
    private val CATEGORY = Regex("<p\\b[^>]*class=\"[^\"]*\\bcategory\\b", RegexOption.IGNORE_CASE)
    private val H1 = Regex("<h1\\b", RegexOption.IGNORE_CASE)

    /** True for an issue or a gathering of articles, false for a book, null when the drive would not say. */
    suspend fun isNews(read: Reader): Boolean? = kind(read).news

    /** What [kind] found: news or not (null: the drive would not say), the issue's date (0: none), its title (null: not read). */
    data class Told(val news: Boolean?, val date: Long, val title: String? = null)

    private val DC_TITLE = Regex("<dc:title[^>]*>([^<]*)")
    private val DC_DATE = Regex("<dc:date[^>]*>\\s*(\\d{4})-(\\d{2})-(\\d{2})")
    private val TOC_TITLE = Regex("<span\\b[^>]*class=\"[^\"]*\\btoc-title\\b")

    /**
     * (news, issue date): news = true for an issue of a newspaper or a magazine, or a gathering
     * of articles (a newsletter's, a channel's summaries: a contents page of titles, with or
     * without their authors); false for a book; null when the drive would not say. The issue's
     * own date (dc:date) in milliseconds, 0 when it has none, and its title (dc:title), "" when
     * it has none.
     */
    suspend fun kind(read: Reader): Told {
        val entries = directory(read) ?: return Told(null, 0L)
        val opfName = entries.keys.firstOrNull { it.lowercase().endsWith(".opf") } ?: return Told(null, 0L)
        val opf = member(read, entries, opfName) ?: return Told(null, 0L)
        val title = DC_TITLE.find(opf)?.groupValues?.get(1)?.let { unescape(it).trim() } ?: ""
        val date = DC_DATE.find(opf)?.let { m -> runCatching {
            java.time.LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
        val base = if ('/' in opfName) opfName.substringBeforeLast('/') + "/" else ""
        val items = HashMap<String, String>()
        var nav: String? = null
        for (m in ITEM.findAll(opf)) {
            val tag = m.value
            val href = HREF.find(tag)?.groupValues?.get(1) ?: continue
            val path = normalize(base + (runCatching { java.net.URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }.getOrDefault(href)))
            ID.find(tag)?.let { items[it.groupValues[1]] = path }
            if (NAV.containsMatchIn(tag)) nav = path
        }
        // one title per article (a publisher may call its contents' own heading "toc-title")
        nav?.let { n -> member(read, entries, n)?.let { if (TOC_TITLE.containsMatchIn(it)) return Told(true, date, title) } }
        val spine = ITEMREF.findAll(opf).mapNotNull { items[it.groupValues[1]] }.toList()
        if (spine.size < 3) return Told(false, date, title)
        var structured = 0
        for (k in 1..3) {
            val text = member(read, entries, spine[spine.size * k / 4]) ?: continue
            if (CATEGORY.containsMatchIn(text) && H1.containsMatchIn(text)) structured++
        }
        return Told(structured >= 2, date, title)
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")

    /** When an issue came out, as the drive knows it: the file's creation, else its last change. */
    fun date(b: RemoteBook): Long = if (b.created > 0) b.created else b.modified

    /** Its own date when it says (a file the drive made again keeps its issue's date), else the drive's. */
    fun date(b: RemoteBook, issue: Long): Long = if (issue > 0) issue else date(b)
}
