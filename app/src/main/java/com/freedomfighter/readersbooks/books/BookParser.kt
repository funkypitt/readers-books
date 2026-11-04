package com.freedomfighter.readersbooks.books

import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.zip.ZipFile

enum class BookFormat { EPUB, MOBI, FB2, TXT }

class UnsupportedBookException(message: String) : Exception(message)

/**
 * Minimal readers for the common non-PDF formats: EPUB (zip + OPF spine), MOBI (PalmDoc
 * compression), FB2 (XML) and plain text. The aim is a stable text extraction, not fidelity.
 */
object BookParser {

    fun detect(file: File, nameHint: String): BookFormat {
        val head = ByteArray(68)
        RandomAccessFile(file, "r").use { it.read(head) }
        if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return BookFormat.EPUB
        if (String(head, 60, 8, Charsets.ISO_8859_1) == "BOOKMOBI") return BookFormat.MOBI
        val ext = nameHint.substringAfterLast('.', "").lowercase()
        if (ext == "fb2") return BookFormat.FB2
        val start = String(head, Charsets.ISO_8859_1)
        if (start.contains("<?xml") || start.contains("<FictionBook")) return BookFormat.FB2
        return when (ext) {
            "epub" -> BookFormat.EPUB
            "mobi", "prc", "azw", "azw3" -> BookFormat.MOBI
            else -> BookFormat.TXT
        }
    }

    fun parse(file: File, format: BookFormat, fallbackTitle: String): Book {
        val book = when (format) {
            BookFormat.EPUB -> parseEpub(file, fallbackTitle)
            BookFormat.MOBI -> parseMobi(file, fallbackTitle)
            BookFormat.FB2 -> parseFb2(file.readText(), fallbackTitle)
            BookFormat.TXT -> parseTxt(file.readText(), fallbackTitle)
        }
        if (book.chapters.isEmpty()) throw UnsupportedBookException("no text found")
        val (chapters, firstPart) = splitLongChapters(book.chapters)
        val magazine = book.magazine?.let { m ->
            Magazine(m.articles.map { a -> Magazine.Article(firstPart[a.chapter], a.category, a.title, a.author, a.image) })
        }
        return Book(book.title, chapters, magazine)
    }

    // ---- EPUB ------------------------------------------------------------------------

    private fun parseEpub(file: File, fallbackTitle: String): Book {
        ZipFile(file).use { zip ->
            fun read(path: String): String? {
                val entry = zip.getEntry(path) ?: zip.getEntry(path.removePrefix("/")) ?: return null
                return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val container = read("META-INF/container.xml") ?: throw UnsupportedBookException("not an EPUB")
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
                ?: throw UnsupportedBookException("no OPF")
            val opf = read(opfPath) ?: throw UnsupportedBookException("OPF missing")
            val dir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

            val items = HashMap<String, Pair<String, String>>() // id -> (href, media-type)
            Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
                val tag = m.value
                val id = attr(tag, "id") ?: return@forEach
                val href = attr(tag, "href") ?: return@forEach
                items[id] = Pair(href, attr(tag, "media-type") ?: "")
            }
            val spine = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)
                .mapNotNull { attr(it.value, "idref") }.toList()
            val title = Regex("<dc:title[^>]*>(.*?)</dc:title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(opf)?.groupValues?.get(1)?.let { HtmlText.decode(it).trim() }?.ifBlank { null } ?: fallbackTitle

            // Chapter titles from the NCX (EPUB 2) or the nav document (EPUB 3), keyed by href.
            val titles = HashMap<String, String>()
            val ncxHref = items.values.firstOrNull { it.second.contains("dtbncx") }?.first
                ?: items.values.firstOrNull { it.first.endsWith(".ncx", true) }?.first
            ncxHref?.let { read(resolve(dir, it)) }?.let { ncx ->
                Regex("<navPoint\\b.*?</navPoint>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(ncx).forEach { np ->
                    val text = Regex("<text>(.*?)</text>", RegexOption.DOT_MATCHES_ALL).find(np.value)?.groupValues?.get(1)
                    val src = Regex("<content\\b[^>]*src=\"([^\"]+)\"").find(np.value)?.groupValues?.get(1)
                    if (text != null && src != null) titles.putIfAbsent(normalize(resolve(dir, src)), HtmlText.decode(text).trim())
                }
            }
            val navHref = Regex("<item\\b[^>]*properties=\"[^\"]*\\bnav\\b[^\"]*\"[^>]*>", RegexOption.IGNORE_CASE)
                .find(opf)?.value?.let { attr(it, "href") }
            navHref?.let { read(resolve(dir, it)) }?.let { nav ->
                Regex("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(nav).forEach { a ->
                    val target = normalize(resolve(dir + navHref.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }, a.groupValues[1]))
                    titles.putIfAbsent(target, HtmlText.strip(a.groupValues[2]).trim())
                }
            }

            val chapters = ArrayList<Book.Chapter>()
            val chapterByPath = HashMap<String, Int>()
            val htmlByChapter = ArrayList<String>()
            spine.forEachIndexed { index, idref ->
                val (href, type) = items[idref] ?: return@forEachIndexed
                if (type.isNotEmpty() && !type.contains("html") && !type.contains("xml")) return@forEachIndexed
                val path = resolve(dir, href)
                val html = read(path) ?: return@forEachIndexed
                val chapterDir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                val blocks = HtmlText.toBlocks(html) { src ->
                    val p = resolve(chapterDir, src)
                    if (zip.getEntry(p) != null) p else null
                }
                if (blocks.isEmpty()) return@forEachIndexed
                val chapterTitle = titles[normalize(path)] ?: HtmlText.heading(html) ?: "${index + 1}"
                chapterByPath[normalize(path)] = chapters.size
                htmlByChapter += html
                chapters += Book.Chapter(chapterTitle, blocks)
            }

            // A magazine from the newspapers pipeline: the nav carries category, title and author
            // per article (signature A), or else every article page starts with them (signature B).
            val nav = navHref?.let { read(resolve(dir, it)) }
            val navDir = navHref?.let { dir + it.substringBeforeLast('/', "").let { d -> if (d.isEmpty()) "" else "$d/" } } ?: dir
            val magazine = parseMagazine(nav, navDir, chapters, chapterByPath, htmlByChapter)
            if (magazine != null) {
                // Only the articles are chapters: the title page and the contents page go.
                val keep = magazine.articles.map { it.chapter }.distinct().sorted()
                val remap = HashMap<Int, Int>(); keep.forEachIndexed { i, c -> remap[c] = i }
                val articles = magazine.articles.map { a -> Magazine.Article(remap.getValue(a.chapter), a.category, a.title, a.author, a.image) }
                // the article's title is the chapter's title (the nav link text also holds category and author)
                val titleOf = HashMap<Int, String>(); magazine.articles.forEach { titleOf.putIfAbsent(it.chapter, it.title) }
                return Book(title, keep.map { c -> Book.Chapter(titleOf[c] ?: chapters[c].title, chapters[c].blocks) }, Magazine(articles))
            }
            return Book(title, chapters)
        }
    }

    private fun parseMagazine(
        nav: String?, navDir: String, chapters: List<Book.Chapter>, chapterByPath: Map<String, Int>, htmlByChapter: List<String>
    ): Magazine? {
        fun span(html: String, cls: String): String? =
            Regex("<span\\b[^>]*class=\"[^\"]*\\b$cls\\b[^\"]*\"[^>]*>(.*?)</span>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.get(1)?.let { HtmlText.strip(it).trim() }?.ifBlank { null }
        fun para(html: String, cls: String): String? =
            Regex("<p\\b[^>]*class=\"[^\"]*\\b$cls\\b[^\"]*\"[^>]*>(.*?)</p>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.get(1)?.let { HtmlText.strip(it).trim() }?.ifBlank { null }
        fun cover(chapter: Int): String? = chapters[chapter].blocks.firstOrNull { it is Block.Image }?.let { (it as Block.Image).source }

        val articles = ArrayList<Magazine.Article>()
        if (nav != null && nav.contains("toc-cat") && nav.contains("toc-title")) {
            Regex("<li\\b[^>]*>(.*?)</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(nav).forEach { li ->
                val href = Regex("<a\\b[^>]*href=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(li.value)?.groupValues?.get(1) ?: return@forEach
                val chapter = chapterByPath[normalize(resolve(navDir, href))] ?: return@forEach
                val title = span(li.value, "toc-title") ?: return@forEach
                articles += Magazine.Article(chapter, span(li.value, "toc-cat") ?: "", title, span(li.value, "toc-author"), cover(chapter))
            }
            if (articles.isNotEmpty()) return Magazine(articles)
        }
        var structured = 0
        htmlByChapter.forEachIndexed { i, html ->
            val category = para(html, "category") ?: return@forEachIndexed
            val title = HtmlText.heading(html) ?: return@forEachIndexed
            structured++
            articles += Magazine.Article(i, category, title, para(html, "author"), cover(i))
        }
        return if (chapters.isNotEmpty() && structured * 2 >= chapters.size) Magazine(articles) else null
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            ?: Regex("\\b$name\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    private fun resolve(dir: String, href: String): String {
        val clean = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = ArrayList<String>()
        (dir + clean).split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun normalize(path: String) = path.substringBefore('#').lowercase()

    // ---- MOBI (PalmDoc) --------------------------------------------------------------

    private fun parseMobi(file: File, fallbackTitle: String): Book {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes)
        val recordCount = buf.getShort(76).toInt() and 0xFFFF
        if (recordCount < 2) throw UnsupportedBookException("empty MOBI")
        val offsets = IntArray(recordCount + 1) { i -> if (i < recordCount) buf.getInt(78 + 8 * i) else bytes.size }
        val r0 = offsets[0]
        val compression = buf.getShort(r0).toInt() and 0xFFFF
        val textRecords = buf.getShort(r0 + 8).toInt() and 0xFFFF
        val encryption = buf.getShort(r0 + 12).toInt() and 0xFFFF
        if (encryption != 0) throw UnsupportedBookException("DRM")
        if (compression != 1 && compression != 2) throw UnsupportedBookException("HUFF/CDIC compression")
        var charset: Charset = Charsets.UTF_8
        var extraFlags = 0
        var title = String(bytes, 0, 32, Charsets.ISO_8859_1).trimEnd('\u0000').ifBlank { fallbackTitle }
        if (bytes.size > r0 + 20 && String(bytes, r0 + 16, 4, Charsets.ISO_8859_1) == "MOBI") {
            val headerLength = buf.getInt(r0 + 20)
            val encoding = buf.getInt(r0 + 28)
            if (encoding == 1252) charset = Charset.forName("windows-1252")
            if (headerLength >= 0xE4) extraFlags = buf.getShort(r0 + 0xF2).toInt() and 0xFFFF
            val exthFlag = buf.getInt(r0 + 0x80)
            if (exthFlag and 0x40 != 0) {
                val exth = r0 + 16 + headerLength
                if (String(bytes, exth, 4, Charsets.ISO_8859_1) == "EXTH") {
                    val count = buf.getInt(exth + 8)
                    var p = exth + 12
                    repeat(count) {
                        val type = buf.getInt(p); val len = buf.getInt(p + 4)
                        if (type == 503 && len > 8) title = String(bytes, p + 8, len - 8, charset)
                        p += len
                    }
                }
            }
        }
        val out = java.io.ByteArrayOutputStream()
        for (i in 1..minOf(textRecords, recordCount - 1)) {
            var end = offsets[i + 1]
            val start = offsets[i]
            // Strip trailing entries (bits 1..15), then the multibyte trailer (bit 0).
            for (bit in 15 downTo 1) if (extraFlags and (1 shl bit) != 0) {
                var size = 0; var shift = 0; var p = end - 1
                while (p >= start) {
                    val b = bytes[p].toInt() and 0xFF
                    size = size or ((b and 0x7F) shl shift); shift += 7
                    if (b and 0x80 != 0 || shift > 28 || p == start) break
                    p--
                }
                end -= size
            }
            if (extraFlags and 1 != 0) end -= ((bytes[end - 1].toInt() and 3) + 1)
            if (end <= start) continue
            val rec = bytes.copyOfRange(start, end)
            out.write(if (compression == 2) palmDocDecompress(rec) else rec)
        }
        val html = String(out.toByteArray(), charset)
        val sections = html.split(Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE))
        val chapters = sections.mapIndexedNotNull { i, s ->
            val blocks = HtmlText.toBlocks(s) { null }
            if (blocks.isEmpty()) null else Book.Chapter(HtmlText.heading(s) ?: "${i + 1}", blocks)
        }
        return Book(title, chapters)
    }

    private fun palmDocDecompress(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size * 2)
        val buf = ArrayList<Byte>(data.size * 2)
        var i = 0
        fun emit(b: Byte) { buf.add(b) }
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++
            when {
                c == 0 -> emit(0)
                c in 1..8 -> { repeat(c) { if (i < data.size) emit(data[i++]) } }
                c <= 0x7F -> emit(c.toByte())
                c <= 0xBF -> {
                    if (i >= data.size) break
                    val v = (c shl 8) or (data[i].toInt() and 0xFF); i++
                    val distance = (v shr 3) and 0x7FF
                    val length = (v and 7) + 3
                    val startIdx = buf.size - distance
                    if (startIdx < 0) continue
                    repeat(length) { buf.add(buf[startIdx + it]) }
                }
                else -> { emit(' '.code.toByte()); emit((c xor 0x80).toByte()) }
            }
        }
        out.write(buf.toByteArray())
        return out.toByteArray()
    }

    // ---- FB2 / TXT ---------------------------------------------------------------------

    private fun parseFb2(xml: String, fallbackTitle: String): Book {
        val noBinary = Regex("<binary\\b.*?</binary>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(xml, "")
        val title = Regex("<book-title>(.*?)</book-title>", RegexOption.DOT_MATCHES_ALL).find(noBinary)?.groupValues?.get(1)
            ?.let { HtmlText.decode(it).trim() }?.ifBlank { null } ?: fallbackTitle
        val body = Regex("<body\\b.*?</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(noBinary)
            .joinToString("\n") { it.value }.ifEmpty { noBinary }
        val sections = body.split(Regex("<section\\b[^>]*>", RegexOption.IGNORE_CASE))
        val chapters = sections.mapIndexedNotNull { i, s ->
            val blocks = HtmlText.toBlocks(s) { null }
            if (blocks.isEmpty()) null
            else {
                val t = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)?.let { HtmlText.strip(it).trim() }
                Book.Chapter(t?.ifBlank { null } ?: "${i + 1}", blocks)
            }
        }
        return Book(title, chapters)
    }

    private fun parseTxt(text: String, fallbackTitle: String): Book {
        val paragraphs = text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        return Book(fallbackTitle, listOf(Book.Chapter(fallbackTitle, listOf(Block.Text(paragraphs.map { Para(it) })))))
    }

    /**
     * Keep chapters under ~12k characters so a single text measurement stays quick. Also returns,
     * for every original chapter, the index of its first part.
     */
    private fun splitLongChapters(chapters: List<Book.Chapter>, max: Int = 12_000): Pair<List<Book.Chapter>, IntArray> {
        val out = ArrayList<Book.Chapter>()
        val firstPart = IntArray(chapters.size)
        chapters.forEachIndexed { ci, ch ->
            firstPart[ci] = out.size
            if (ch.length <= max) { out += ch; return@forEachIndexed }
            var n = 1
            var blocks = ArrayList<Block>()
            var part = ArrayList<Para>()
            var size = 0
            fun flushText() { if (part.isNotEmpty()) { blocks += Block.Text(part); part = ArrayList() } }
            fun flushPart() {
                flushText()
                if (blocks.isNotEmpty()) { out += Book.Chapter(if (n == 1) ch.title else "${ch.title} · $n", blocks); n++ }
                blocks = ArrayList(); size = 0
            }
            for (block in ch.blocks) {
                if (block is Block.Image) { flushText(); blocks += block; size += 1; continue }
                for (p in (block as Block.Text).paragraphs) {
                    if (size + p.text.length > max && (part.isNotEmpty() || blocks.isNotEmpty())) flushPart()
                    // A single monstrous paragraph is cut hard.
                    if (p.text.length > max) {
                        var o = 0
                        while (p.text.length - o > max) {
                            val cut = p.text.substring(o).lastIndexOf(' ', max).let { if (it < max / 2) max else it }
                            part += p.slice(o, o + cut); o += cut
                            while (o < p.text.length && p.text[o].isWhitespace()) o++
                            flushPart()
                        }
                        if (o < p.text.length) { part += p.slice(o, p.text.length); size += p.text.length - o }
                    } else { part += p; size += p.text.length + 1 }
                }
            }
            flushPart()
        }
        return Pair(out, firstPart)
    }
}

/** HTML/XHTML → paragraphs of plain text. Regex based on purpose: tolerant of sloppy markup. */
object HtmlText {
    private val dropBlocks = Regex("<(script|style|head|svg|math)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val lineBreak = Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val paragraphBreak = Regex(
        "</(p|div|h[1-6]|li|tr|blockquote|section|article|title|subtitle|dd|dt|pre|table|figcaption|aside|header|footer|epigraph|stanza|v)>" +
            "|<(p|div|h[1-6]|li|tr|blockquote|section|article|dd|dt|pre|table|figcaption|aside|header|footer)\\b[^>]*>" +
            "|<empty-line\\s*/?>|<hr\\b[^>]*/?>",
        RegexOption.IGNORE_CASE
    )
    private val anyTag = Regex("<[^>]+>")
    private val headingRe = Regex("<h[1-3]\\b[^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val svgImage = Regex("<image\\b[^>]*?(?:xlink:)?href=\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE)
    private val img = Regex("<img\\b[^>]*?src=\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE)
    private val headingOpen = Regex("<h[1-6]\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val classed = Regex("<(p|div)\\b[^>]*class=\"([^\"]*)\"[^>]*>", RegexOption.IGNORE_CASE)
    private const val MARK = '\u0001'
    private const val IMG = "\u0002"

    // Italic and bold: a mark where each begins and ends, put in place of the tag and taken out
    // again before the text is cut into paragraphs, each character keeping what it was set in.
    private val emphasis = Regex("<(/?)(i|em|b|strong)(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private const val ITALIC_ON = '\u0003'
    private const val ITALIC_OFF = '\u0004'
    private const val BOLD_ON = '\u0005'
    private const val BOLD_OFF = '\u0006'
    private val spaces = Regex("[ \\t\\r\\u00A0\\u2007\\u202F]+")
    private val blankLine = Regex("\n\\s*\n")
    private val kindMark = Regex("$MARK[HLS]")

    /**
     * Paragraphs with their kind, and the images between them. `image` maps an `src` to the
     * source the reader can load, or null to drop it. Markers are single control characters
     * placed before tags are stripped, so the split into paragraphs stays the same as before.
     * Words in italic or bold keep it as runs beside the text ([Para.styles]): the text itself
     * is exactly what it is without them, so a place in it is the same with or without.
     */
    fun toBlocks(html: String, image: (String) -> String?): List<Block> {
        var s = html
        s = comments.replace(s, "")
        s = svgImage.replace(s) { m -> "<img src=\"${m.groupValues[1]}\"/>" }
        s = img.replace(s) { m -> image(m.groupValues[1])?.let { "\n\n$IMG$it\n\n" } ?: " " }
        s = dropBlocks.replace(s, " ")
        s = headingOpen.replace(s) { m -> m.value + MARK + "H" }
        s = classed.replace(s) { m ->
            val c = m.groupValues[2].lowercase()
            when {
                Regex("\\blead\\b|\\bchapo\\b|\\bstandfirst\\b").containsMatchIn(c) -> m.value + MARK + "L"
                Regex("\\bcategory\\b|\\bauthor\\b|\\bbyline\\b|caption|\\bcredit\\b|\\bseparator\\b|\\bfootnote\\b").containsMatchIn(c) -> m.value + MARK + "S"
                else -> m.value
            }
        }
        // a text that carries these control characters itself is left without emphasis, as it is
        val marked = s.none { it in ITALIC_ON..BOLD_OFF }
        if (marked) s = emphasis.replace(s) { m ->
            val italic = m.groupValues[2].lowercase().let { it == "i" || it == "em" }
            if (m.groupValues[3].trimEnd().endsWith("/")) "" // <b/>: nothing to set
            else if (m.groupValues[1].isEmpty()) (if (italic) ITALIC_ON else BOLD_ON).toString() else (if (italic) ITALIC_OFF else BOLD_OFF).toString()
        }
        s = lineBreak.replace(s, "\n")
        s = paragraphBreak.replace(s, "\n\n")
        s = anyTag.replace(s, "")
        s = decode(s)
        // the marks out: what is left is the text without them, and what each character is set in
        val plain = StringBuilder(s.length)
        val setIn = ByteArray(s.length)
        var italic = 0; var bold = 0
        for (c in s) {
            if (marked) when (c) {
                ITALIC_ON -> { italic++; continue }
                ITALIC_OFF -> { if (italic > 0) italic--; continue }
                BOLD_ON -> { bold++; continue }
                BOLD_OFF -> { if (bold > 0) bold--; continue }
            }
            setIn[plain.length] = ((if (italic > 0) Para.ITALIC else 0) or (if (bold > 0) Para.BOLD else 0)).toByte()
            plain.append(c)
        }
        // From here on every step is the plain text's, each character remembering where it came from.
        val text = Traced.collapse(plain, spaces)
        val blocks = ArrayList<Block>()
        var paras = ArrayList<Para>()
        var from = 0
        val raws = ArrayList<Traced>()
        for (m in blankLine.findAll(text.chars)) { raws += text.slice(from, m.range.first); from = m.range.last + 1 }
        raws += text.slice(from, text.chars.length)
        for (raw in raws) {
            val para = raw.lines()
            if (para.chars.isEmpty()) continue
            if (para.chars.startsWith(IMG)) {
                if (paras.isNotEmpty()) { blocks += Block.Text(paras); paras = ArrayList() }
                blocks += Block.Image(para.chars.substring(1).substringBefore('\n').trim())
                continue
            }
            var kind = ParaKind.NORMAL
            var t = para
            while (t.chars.isNotEmpty() && t.chars[0] == MARK && t.chars.length > 1) {
                kind = when (t.chars[1]) { 'H' -> ParaKind.HEADING; 'L' -> ParaKind.LEAD; 'S' -> ParaKind.SMALL; else -> kind }
                t = t.slice(2, t.chars.length).trimStart()
            }
            t = t.without(kindMark)
            if (t.chars == "---" || t.chars == "♦") continue // the pipeline's separators
            if (t.chars.isNotEmpty()) paras += Para(t.chars, kind, t.styles(setIn))
        }
        if (paras.isNotEmpty()) blocks += Block.Text(paras)
        return blocks
    }

    /**
     * A piece of text with, for each of its characters, its place in the text it was cut from
     * (-1 for a line break put in between): the steps that make paragraphs, done here so that
     * each character keeps track of whether it was in italic or bold.
     */
    private class Traced(val chars: String, val at: IntArray) {
        fun slice(from: Int, to: Int) = Traced(chars.substring(from, to), at.copyOfRange(from, to))

        fun trimStart(): Traced { var i = 0; while (i < chars.length && chars[i].isWhitespace()) i++; return slice(i, chars.length) }

        fun trim(): Traced {
            var i = 0; var j = chars.length
            while (i < j && chars[i].isWhitespace()) i++
            while (j > i && chars[j - 1].isWhitespace()) j--
            return slice(i, j)
        }

        /** Its lines trimmed, the empty ones dropped, joined again by line breaks. */
        fun lines(): Traced {
            val parts = ArrayList<Traced>()
            var from = 0
            for (i in 0..chars.length) if (i == chars.length || chars[i] == '\n') { slice(from, i).trim().takeIf { it.chars.isNotEmpty() }?.let { parts += it }; from = i + 1 }
            if (parts.size == 1) return parts[0]
            val sb = StringBuilder(); val at = ArrayList<Int>()
            parts.forEachIndexed { k, p -> if (k > 0) { sb.append('\n'); at += -1 }; sb.append(p.chars); p.at.forEach { at += it } }
            return Traced(sb.toString(), at.toIntArray())
        }

        fun without(re: Regex): Traced {
            if (!re.containsMatchIn(chars)) return this
            val sb = StringBuilder(); val keep = ArrayList<Int>()
            var from = 0
            for (m in re.findAll(chars)) { for (i in from until m.range.first) { sb.append(chars[i]); keep += at[i] }; from = m.range.last + 1 }
            for (i in from until chars.length) { sb.append(chars[i]); keep += at[i] }
            return Traced(sb.toString(), keep.toIntArray())
        }

        /** The runs of italic and bold, from what each character of the source was set in. */
        fun styles(setIn: ByteArray): IntArray? {
            var runs: ArrayList<Int>? = null
            var i = 0
            while (i < chars.length) {
                val f = if (at[i] >= 0) setIn[at[i]].toInt() else 0
                var j = i + 1
                while (j < chars.length && (if (at[j] >= 0) setIn[at[j]].toInt() else f) == f) j++
                if (f != 0) { val r = runs ?: ArrayList<Int>().also { runs = it }; r += i; r += j; r += f }
                i = j
            }
            return runs?.toIntArray()
        }

        companion object {
            /** Every run of these spaces made one plain space, as the text always was. */
            fun collapse(text: CharSequence, spaces: Regex): Traced {
                val sb = StringBuilder(text.length); val at = IntArray(text.length)
                var n = 0; var from = 0
                for (m in spaces.findAll(text)) {
                    for (i in from until m.range.first) { sb.append(text[i]); at[n++] = i }
                    sb.append(' '); at[n++] = m.range.first; from = m.range.last + 1
                }
                for (i in from until text.length) { sb.append(text[i]); at[n++] = i }
                return Traced(sb.toString(), at.copyOf(n))
            }
        }
    }

    /** Plain paragraphs, for callers that only want text. */
    fun toParagraphs(html: String): List<String> = toBlocks(html) { null }.filterIsInstance<Block.Text>().flatMap { b -> b.paragraphs.map { it.text } }

    fun strip(html: String): String = decode(anyTag.replace(html, "")).replace(Regex("[\\u0001\\u0002]"), "").replace(Regex("\\s+"), " ")

    fun heading(html: String): String? =
        headingRe.find(html)?.groupValues?.get(1)?.let { strip(it).trim() }?.takeIf { it.isNotBlank() && it.length < 120 }

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "laquo" to "«", "raquo" to "»", "shy" to "", "copy" to "©", "reg" to "®", "deg" to "°", "middot" to "·",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "agrave" to "à", "acirc" to "â", "ccedil" to "ç", "ugrave" to "ù",
        "ucirc" to "û", "ocirc" to "ô", "icirc" to "î", "iuml" to "ï", "euml" to "ë", "auml" to "ä", "ouml" to "ö", "uuml" to "ü",
        "szlig" to "ß", "ntilde" to "ñ", "oacute" to "ó", "iacute" to "í", "uacute" to "ú", "aacute" to "á", "atilde" to "ã", "otilde" to "õ",
        "Eacute" to "É", "Agrave" to "À", "Ccedil" to "Ç", "times" to "×", "euro" to "€", "pound" to "£", "sect" to "§"
    )
    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

    fun decode(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { cp -> String(Character.toChars(cp)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { cp -> String(Character.toChars(cp)) } ?: m.value
            else -> named[e] ?: m.value
        }
    }
}
