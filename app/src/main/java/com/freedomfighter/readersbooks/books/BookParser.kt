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

            // The publisher's style sheets, each read once; the rules a page links to, in its order.
            val sheets = HashMap<String, List<Css.Rule>>()
            fun styleSheets(html: String, chapterDir: String): List<Css.Rule> {
                val out = ArrayList<Css.Rule>()
                for (m in linkTag.findAll(html)) {
                    val tag = m.value
                    if (!(attr(tag, "rel") ?: "").contains("stylesheet", true) && !(attr(tag, "type") ?: "").contains("css", true)) continue
                    val href = attr(tag, "href") ?: continue
                    val p = resolve(chapterDir, href)
                    val rules = sheets.getOrPut(p) { read(p)?.let { Css.parse(it) } ?: emptyList() }
                    for (r in rules) out += Css.Rule(r.selector, r.decls, out.size)
                }
                return out
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
                val blocks = HtmlText.toBlocks(html, styleSheets(html, chapterDir)) { src ->
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

    private val linkTag = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)

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
            val blocks = HtmlText.toBlocks(s, fb2 = true) { null }
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

    // The book's presentation: every tag left after the steps above is followed by a mark and
    // its number (two characters of the private use area), and every picture is preceded by one;
    // the marks go out with italic and bold, before the text is touched, so that the text is the
    // same character for character, and each character knows the element it was in.
    private const val TAG_MARK = '\u0007'
    private const val PICTURE_MARK = '\u0008'
    private const val PUA = 0xE000
    private const val PUA_SIZE = 6400
    private val styleBlock = Regex("<style\\b[^>]*>(.*?)</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private fun number(k: Int): String = "${(PUA + k / PUA_SIZE).toChar()}${(PUA + k % PUA_SIZE).toChar()}"

    /** The rules of the `<style>` blocks of a page. */
    fun pageRules(html: String, firstOrder: Int): List<Css.Rule> {
        val out = ArrayList<Css.Rule>()
        for (m in styleBlock.findAll(html)) out += Css.parse(m.groupValues[1].replace("<!--", " ").replace("-->", " "), firstOrder + out.size)
        return out
    }

    /**
     * Paragraphs with their kind, and the images between them. `image` maps an `src` to the
     * source the reader can load, or null to drop it. Markers are single control characters
     * placed before tags are stripped, so the split into paragraphs stays the same as before.
     * Words in italic or bold keep it as runs beside the text ([Para.styles]), and each paragraph
     * how the book sets it ([Para.look], from `rules`, the page's own style blocks and the
     * style attributes): the text itself is exactly what it is without them, so a place in it is
     * the same with or without. `fb2` sets FictionBook's own elements (poems, epigraphs…).
     */
    fun toBlocks(html: String, rules: List<Css.Rule> = emptyList(), fb2: Boolean = false, image: (String) -> String?): List<Block> {
        // a page that carries these characters itself is read as it is, without presentation
        val styled = html.none { it == TAG_MARK || it == PICTURE_MARK }
        val tree = if (styled) Tree(Css.Sheet(rules + pageRules(html, rules.size)), fb2) else null
        val pictures = ArrayList<String>()
        var s = html
        s = comments.replace(s, "")
        s = svgImage.replace(s) { m -> "<img src=\"${m.groupValues[1]}\"/>" }
        s = img.replace(s) { m ->
            image(m.groupValues[1])?.let {
                if (styled) { pictures += m.value; "\n\n" + PICTURE_MARK + number(pictures.size - 1) + IMG + it + "\n\n" } else "\n\n$IMG$it\n\n"
            } ?: " "
        }
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
        val tags = ArrayList<String>()
        if (styled) s = anyTag.replace(s) { m -> tags += m.value; m.value + TAG_MARK + number(tags.size - 1) }
        s = lineBreak.replace(s, "\n")
        s = paragraphBreak.replace(s, "\n\n")
        s = anyTag.replace(s, "")
        s = decode(s)
        // the marks out: what is left is the text without them, and what each character is set in
        val plain = StringBuilder(s.length)
        val setIn = ByteArray(s.length)
        val nodeAt = if (styled) IntArray(s.length) else null
        val hardAt = HashSet<Int>()          // line breaks of the page (<br>), not of its source
        val rulesAt = ArrayList<Int>()       // <hr>, before the character at
        val gapsAt = ArrayList<Int>()        // FictionBook's <empty-line/>
        val pictureNode = HashMap<Int, Int>()
        var italic = 0; var bold = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (tree != null && (c == TAG_MARK || c == PICTURE_MARK) && i + 2 < s.length) {
                val k = (s[i + 1].code - PUA) * PUA_SIZE + (s[i + 2].code - PUA)
                if (c == TAG_MARK) tags.getOrNull(k)?.let { raw ->
                    when (tree.tag(raw)) {
                        "br" -> if (plain.isNotEmpty() && plain[plain.length - 1] == '\n') hardAt += plain.length - 1
                        "hr" -> rulesAt += plain.length
                        "empty-line" -> gapsAt += plain.length
                    }
                } else pictures.getOrNull(k)?.let { raw -> pictureNode[plain.length] = tree.leaf("img", tree.attributes(raw)) }
                i += 3
                continue
            }
            i++
            if (marked) when (c) {
                ITALIC_ON -> { italic++; continue }
                ITALIC_OFF -> { if (italic > 0) italic--; continue }
                BOLD_ON -> { bold++; continue }
                BOLD_OFF -> { if (bold > 0) bold--; continue }
            }
            setIn[plain.length] = ((if (italic > 0) Para.ITALIC else 0) or (if (bold > 0) Para.BOLD else 0)).toByte()
            if (nodeAt != null) nodeAt[plain.length] = tree!!.current
            plain.append(c)
        }
        // From here on every step is the plain text's, each character remembering where it came from.
        val text = Traced.collapse(plain, spaces)
        val look = if (tree != null && nodeAt != null) Presenter(tree, nodeAt, setIn, hardAt, rulesAt, gapsAt) else null
        val blocks = ArrayList<Block>()
        var paras = ArrayList<Para>()
        var from = 0
        val raws = ArrayList<Traced>()
        for (m in blankLine.findAll(text.chars)) { raws += text.slice(from, m.range.first); from = m.range.last + 1 }
        raws += text.slice(from, text.chars.length)
        for (raw in raws) {
            val para = raw.lines(look?.let { l -> { at: Int -> l.hard(at) } })
            if (para.chars.isEmpty()) continue
            if (para.chars.startsWith(IMG)) {
                if (paras.isNotEmpty()) { blocks += Block.Text(paras); paras = ArrayList() }
                blocks += Block.Image(para.chars.substring(1).substringBefore('\n').trim(), look?.picture(pictureNode[para.at[0]], para.at[0]))
                continue
            }
            var kind = ParaKind.NORMAL
            var t = para
            while (t.chars.isNotEmpty() && t.chars[0] == MARK && t.chars.length > 1) {
                kind = when (t.chars[1]) { 'H' -> ParaKind.HEADING; 'L' -> ParaKind.LEAD; 'S' -> ParaKind.SMALL; else -> kind }
                t = t.slice(2, t.chars.length).trimStart()
            }
            t = t.without(kindMark)
            if (t.chars == "---" || t.chars == "♦") { if (t.chars == "---") look?.ruleNext(); continue } // the pipeline's separators
            if (t.chars.isEmpty()) continue
            if (look == null) paras += Para(t.chars, kind, t.styles { setIn[it].toInt() })
            else look.paragraph(t, kind).let { (styles, l) -> paras += Para(t.chars, kind, styles, l) }
        }
        if (paras.isNotEmpty()) blocks += Block.Text(paras)
        return blocks
    }

    /**
     * Makes the [Look] of each paragraph and picture of a page, in their order: what each one
     * opens and closes of the page's elements gives the space before it, its page break, the
     * marker of a list item.
     */
    private class Presenter(
        val tree: Tree, val nodeAt: IntArray, val setIn: ByteArray,
        val hardAt: Set<Int>, val rulesAt: List<Int>, val gapsAt: List<Int>
    ) {
        private var before: Set<Int> = emptySet()
        private var lastAt = -1
        private var pendingRule = false
        private var bodySeen = false

        /** A line break that stays one: a <br>, or one of a block that keeps its lines (pre). */
        fun hard(at: Int): Boolean = at in hardAt || tree.style(nodeAt[at]).pre

        fun ruleNext() { pendingRule = true }

        private class Opening(val above: Float, val pageBreak: Boolean, val rule: Boolean, val opened: Set<Int>)

        private fun opening(node: Int, at: Int): Opening {
            val chain = tree.chain(node).toHashSet()
            val opened = chain - before
            val closed = before - chain
            var above = 0f
            var pageBreak = false
            for (n in opened) {
                val st = tree.style(n)
                if (st.breakBefore) pageBreak = true
                if (tree.isBlock(n) && tree.nodes[n].tag !in Tree.PAGE) above = maxOf(above, st.mt + st.pt)
            }
            for (n in closed) {
                val st = tree.style(n)
                if (st.breakAfter) pageBreak = true
                if (tree.isBlock(n) && tree.nodes[n].tag !in Tree.PAGE) above = maxOf(above, st.mb + st.pb)
            }
            if (gapsAt.any { it in (lastAt + 1)..at }) above = maxOf(above, 1f)
            val rule = pendingRule || rulesAt.any { it in (lastAt + 1)..at }
            pendingRule = false
            before = chain
            lastAt = at
            return Opening(above.coerceIn(0f, MAX_ABOVE), pageBreak, rule, opened)
        }

        /** Left and right margins: those of the blocks the paragraph is in. */
        private fun margins(block: Int): Pair<Float, Float> {
            var left = 0f; var right = 0f
            var n = block
            while (n > 0) {
                val node = tree.nodes[n]
                if (tree.isBlock(n) && node.tag !in Tree.NO_MARGIN) {
                    val st = tree.style(n)
                    left += (st.ml + st.pl).coerceIn(0f, MAX_SIDE)
                    right += (st.mr + st.pr).coerceIn(0f, MAX_SIDE)
                }
                n = node.parent
            }
            return left.coerceAtMost(MAX_LEFT) to right.coerceAtMost(MAX_RIGHT)
        }

        fun picture(node: Int?, at: Int): Look? {
            node ?: return null
            val o = opening(node, at)
            val st = tree.style(node)
            val attrs = tree.nodes[node].attrs
            val w = attrs["width"]?.trim()
            val width = if (!st.width.isNaN()) st.width else w?.takeIf { it.endsWith("%") }?.dropLast(1)?.toFloatOrNull()?.let { (it / 100f).coerceIn(0.05f, 1f) } ?: Float.NaN
            val pixels = w?.takeIf { !it.endsWith("%") }?.removeSuffix("px")?.toFloatOrNull()?.toInt() ?: 0
            val align = tree.style(tree.blockOf(tree.nodes[node].parent)).align
            return Look(align = align, above = o.above, pageBreak = o.pageBreak, rule = o.rule, width = width, pixels = pixels.coerceAtLeast(0))
        }

        fun paragraph(t: Traced, kind: ParaKind): Pair<IntArray?, Look> {
            val first = t.at[0]
            val node = nodeAt[first]
            val o = opening(node, first)
            val block = tree.blockOf(node)
            val bs = tree.style(block)
            val (left, right) = margins(block)
            var marker = 0; var number = 0; var level = 0
            var listMarker = 0; var listFrom = 0; var listTo = 0; var listLeft = 0f
            var n = block
            while (n > 0) {
                val tag = tree.nodes[n].tag
                if (tag == "li" && marker == 0 && n in o.opened) { marker = tree.style(n).listType.coerceAtLeast(0); number = tree.nodes[n].ordinal }
                if (tag == "li" && listMarker == 0) {
                    // the list this item is in: its kind of marker, its numbers, the margin it stands in
                    val list = tree.nodes[n].parent.takeIf { it > 0 && tree.nodes[it].tag.let { t -> t == "ul" || t == "ol" } }
                    listMarker = tree.style(n).listType.coerceAtLeast(0)
                    if (list != null && listMarker > 0) {
                        listFrom = tree.nodes[list].ordinal; listTo = maxOf(tree.nodes[list].counter, listFrom)
                        listLeft = margins(tree.nodes[list].parent).first
                    } else listMarker = 0
                }
                if (level == 0 && tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6') level = tag[1] - '0'
                n = tree.nodes[n].parent
            }
            val size = (if (kind == ParaKind.SMALL && !bs.sizeSet) 0.8f else bs.size).coerceIn(MIN_SIZE, MAX_SIZE)
            val blockFlags = bs.flags or (if (kind == ParaKind.LEAD && !bs.weightSet) Para.BOLD else 0)
            val soft = t.chars.indices.filter { t.chars[it] == '\n' && t.at[it] == -1 }.takeIf { it.isNotEmpty() }?.toIntArray()
            val styles = t.styles { at ->
                val st = tree.style(nodeAt[at])
                val f = (setIn[at].toInt() or st.flags) and blockFlags.inv()
                val ratio = st.size.coerceIn(MIN_SIZE, MAX_SIZE) / size
                if (f and (Para.SUPER or Para.SUB) == 0 && (ratio < 0.97f || ratio > 1.03f)) f or (Math.round(ratio * 100f) shl Para.SIZE_SHIFT) else f
            }
            val look = Look(
                align = bs.align, indent = bs.indent.let { if (it.isNaN()) it else it.coerceIn(-MAX_INDENT, MAX_INDENT) },
                left = left, right = right, above = o.above, pageBreak = o.pageBreak, rule = o.rule,
                size = size, flags = blockFlags, level = level, marker = marker, number = number,
                dropCap = dropCap(t, node, block, bs), soft = soft, cells = cells(t, node), table = maxOf(0, tree.tableOf(node)),
                listMarker = listMarker, listFrom = listFrom, listTo = listTo, listLeft = listLeft
            )
            return styles to look
        }

        private fun dropCap(t: Traced, node: Int, block: Int, bs: Style): Int {
            var big = -1
            var p = node
            while (p > 0 && p != block) { if (tree.isDropCap(p, block)) big = p; p = tree.nodes[p].parent }
            if (big > 0) {
                var k = 0
                while (k < t.chars.length && t.at[k] >= 0 && tree.within(nodeAt[t.at[k]], big)) k++
                return if (k in 1..3 && k < t.chars.length && t.chars.substring(0, k).none { it.isWhitespace() }) k else 0
            }
            if (!bs.firstLetter) return 0
            var k = 0
            while (k < 2 && k < t.chars.length && !t.chars[k].isLetterOrDigit() && !t.chars[k].isWhitespace()) k++
            return if (k < t.chars.length - 1 && t.chars[k].isLetterOrDigit()) k + 1 else 0
        }

        /** A table's row: where each cell's text is in the paragraph. */
        private fun cells(t: Traced, node: Int): IntArray? {
            if (tree.tableOf(node) <= 0) return null
            val n = t.chars.length
            val cellAt = IntArray(n) { if (t.at[it] >= 0) tree.cellOf(nodeAt[t.at[it]]) else -2 }
            for (k in 0 until n) if (cellAt[k] == -2) cellAt[k] = if (k > 0 && k + 1 < n && cellAt[k - 1] >= 0 && cellAt[k - 1] == cellAt[k + 1]) cellAt[k - 1] else -1
            val out = ArrayList<Int>()
            var k = 0
            while (k < n) {
                val c = cellAt[k]
                var e = k + 1
                while (e < n && cellAt[e] == c) e++
                if (c > 0) {
                    val row = tree.rowOf(c)
                    out += k; out += e; out += if (row > 0) tree.nodes[row].ordinal else 0
                    out += tree.nodes[c].ordinal; out += tree.nodes[c].span; out += if (tree.isHeader(c)) 1 else 0
                }
                k = e
            }
            return if (out.isEmpty()) null else out.toIntArray()
        }
    }

    private const val MIN_SIZE = 0.8f
    private const val MAX_SIZE = 1.6f
    private const val MAX_ABOVE = 2.5f
    private const val MAX_INDENT = 3f
    private const val MAX_SIDE = 4f
    private const val MAX_LEFT = 8f
    private const val MAX_RIGHT = 4f

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

        /**
         * Its lines trimmed, the empty ones dropped, joined again by line breaks. A line break put
         * in between is -1, or -2 when `hard` says the one it stands for stays a line break.
         */
        fun lines(hard: ((Int) -> Boolean)? = null): Traced {
            val parts = ArrayList<Traced>()
            val joins = ArrayList<Int>()
            var gapHard = false
            var from = 0
            for (i in 0..chars.length) if (i == chars.length || chars[i] == '\n') {
                slice(from, i).trim().takeIf { it.chars.isNotEmpty() }?.let { if (parts.isNotEmpty()) joins += if (gapHard) -2 else -1; parts += it; gapHard = false }
                if (i < chars.length && hard != null && parts.isNotEmpty() && at[i] >= 0 && hard(at[i])) gapHard = true
                from = i + 1
            }
            if (parts.size == 1) return parts[0]
            val sb = StringBuilder(); val at = ArrayList<Int>()
            parts.forEachIndexed { k, p -> if (k > 0) { sb.append('\n'); at += joins[k - 1] }; sb.append(p.chars); p.at.forEach { at += it } }
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

        /** The runs of italic, bold and the rest, from what each character of the source was set in. */
        fun styles(setIn: (Int) -> Int): IntArray? {
            var runs: ArrayList<Int>? = null
            var i = 0
            while (i < chars.length) {
                val f = if (at[i] >= 0) setIn(at[i]) else 0
                var j = i + 1
                while (j < chars.length && (if (at[j] >= 0) setIn(at[j]) else f) == f) j++
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
