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
        return String(bytes, Charsets.UTF_8)
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

    /** True for an issue of a newspaper or a magazine, false for a book, null when the drive would not say. */
    suspend fun isNews(read: Reader): Boolean? {
        val entries = directory(read) ?: return null
        val opfName = entries.keys.firstOrNull { it.lowercase().endsWith(".opf") } ?: return null
        val opf = member(read, entries, opfName) ?: return null
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
        nav?.let { n -> member(read, entries, n)?.let { if ("toc-cat" in it && "toc-title" in it) return true } }
        val spine = ITEMREF.findAll(opf).mapNotNull { items[it.groupValues[1]] }.toList()
        if (spine.size < 3) return false
        var structured = 0
        for (k in 1..3) {
            val text = member(read, entries, spine[spine.size * k / 4]) ?: continue
            if (CATEGORY.containsMatchIn(text) && H1.containsMatchIn(text)) structured++
        }
        return structured >= 2
    }

    /** When an issue came out, as the drive knows it: the file's creation, else its last change. */
    fun date(b: RemoteBook): Long = if (b.created > 0) b.created else b.modified
}
