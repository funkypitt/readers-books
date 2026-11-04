package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.books.Block
import com.freedomfighter.readersbooks.books.Book
import com.freedomfighter.readersbooks.books.BookFormat
import com.freedomfighter.readersbooks.books.BookParser
import com.freedomfighter.readersbooks.books.Css
import com.freedomfighter.readersbooks.books.Look
import com.freedomfighter.readersbooks.books.Highlights
import com.freedomfighter.readersbooks.books.HtmlText
import com.freedomfighter.readersbooks.books.Para
import com.freedomfighter.readersbooks.books.ParaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Italic and bold are runs beside the text, never in it: a highlight is a place in the text of
 * its chapter, counted the same by every version of the app and by the desktop. Each case here
 * compares the text with what the reader made of the same page before it knew of emphasis
 * ([Legacy], the earlier code word for word).
 */
class EmphasisTest {
    private fun same(html: String) {
        val now = HtmlText.toBlocks(html) { it }
        val before = Legacy.toBlocks(html) { it }
        assertEquals(describe(before), describe(now))
    }

    private fun describe(blocks: List<Block>) = blocks.joinToString("|") { b ->
        when (b) { is Block.Image -> "IMG:" + b.source; is Block.Text -> b.paragraphs.joinToString("¶") { it.kind.name + ":" + it.text } }
    }

    private fun runs(p: Para): List<Triple<String, Int, Int>> {
        val s = p.styles ?: return emptyList()
        return (s.indices step 3).map { Triple(p.text.substring(s[it], s[it + 1]), s[it + 2], s[it]) }
    }

    @Test
    fun italic_and_bold_are_runs_and_the_text_is_what_it_was() {
        val html = "<p>Il a dit <i>  oui </i>, puis <b>non</b> et <em>peut-<strong>être</strong></em>.</p>" +
            "<p><i>Tout en italique</i></p><h2>Un <i>titre</i></h2><p class=\"lead\">Le <b>chapô</b></p>"
        same(html)
        val paras = (HtmlText.toBlocks(html) { it }.single() as Block.Text).paragraphs
        assertEquals("Il a dit oui , puis non et peut-être.", paras[0].text)
        assertEquals(listOf(Triple("oui ", Para.ITALIC, 9), Triple("non", Para.BOLD, 20), Triple("peut-", Para.ITALIC, 27), Triple("être", Para.ITALIC or Para.BOLD, 32)), runs(paras[0]))
        assertEquals(listOf(Triple("Tout en italique", Para.ITALIC, 0)), runs(paras[1]))
        assertEquals(ParaKind.HEADING, paras[2].kind); assertEquals(listOf(Triple("titre", Para.ITALIC, 3)), runs(paras[2]))
        // a lead is in bold as a whole: its bold words have nothing more to set
        assertEquals(ParaKind.LEAD, paras[3].kind); assertEquals(Para.BOLD, paras[3].look!!.flags and Para.BOLD); assertEquals(emptyList<Triple<String, Int, Int>>(), runs(paras[3]))
    }

    @Test
    fun marks_at_the_edges_of_lines_and_paragraphs_change_nothing() {
        listOf(
            "<p><i> </i></p><p>a</p>",
            "<p>a <i>\n b</i>\n c</p>",
            "<p>un<br/><i>deux</i><br/> trois</p>",
            "<i><p>across</p><p>paragraphs</p></i><p>after</p>",
            "<p>x</p><b>\n\n</b><p>y</p>",
            "<p>&nbsp;<i>&nbsp;espace&nbsp;</i>&nbsp;</p>",
            "<h1><i>Titre</i></h1><p class=\"author\"><b>Par</b> X</p>",
            "<p>---</p><p><i>♦</i></p><p>fin</p>",
            "<p><b/>vide <b />et <i></i>rien</p>",
            "<p>un <img src=\"a.png\"/> <i>deux</i></p>",
            "<p>unclosed <i>italic</p><p>goes on</p>",
            "<p>stray </i> close</p>",
            "<P><I>UPPER</I> <B CLASS=\"x\">case</B></P>",
            "<p>\u0003 already there <i>x</i></p>",
            "<div><blockquote><em>cite</em> — <strong>auteur</strong></blockquote></div>"
        ).forEach { same(it) }
    }

    @Test
    fun a_paragraph_cut_in_two_keeps_its_runs() {
        val long = "<p>" + (1..3000).joinToString(" ") { if (it % 100 == 0) "<i>mot$it</i>" else "mot$it" } + "</p>"
        val blocks = HtmlText.toBlocks(long) { null }
        val p = (blocks.single() as Block.Text).paragraphs.single()
        val half = p.slice(5000, p.text.length)
        runs(half).forEach { (text, flags, _) -> assertEquals(Para.ITALIC, flags); assert(text.startsWith("mot")) { text } }
        assertEquals(runs(p).count { it.third >= 5000 }, runs(half).size)
    }

    @Test
    fun a_highlight_lands_on_the_same_words_with_emphasis() {
        // the place of « bonjour » in the chapter's text, with and without emphasis around it
        val plain = HtmlText.toBlocks("<p>Il dit bonjour à tous.</p><p>Puis il part.</p>") { null }
        val styled = HtmlText.toBlocks("<p>Il <i>dit</i> <b>bonjour</b> à <em>tous</em>.</p><p><strong>Puis</strong> il part.</p>") { null }
        val a = Highlights.chapterText(Book.Chapter("c", plain)); val b = Highlights.chapterText(Book.Chapter("c", styled))
        assertEquals(a, b)
        val start = a.indexOf("bonjour")
        assertEquals("bonjour", b.substring(start, start + 7))
        assertEquals(a.indexOf("part"), b.indexOf("part"))
        assertNull((plain.single() as Block.Text).paragraphs[0].styles)
    }

    /** The publisher's presentation (Look) beside the text: the same cases as the desktop's Layout tests. */
    private val layoutCases = listOf(
        "p { text-indent: 1.5em; text-align: justify; margin: 0 }" to "<p>Un</p><p>Deux\ntrois</p>",
        "ul { list-style: square } ol { list-style-type: lower-roman }" to
            "<ul><li>a</li><li>b<ul><li>c</li></ul></li></ul><ol start=\"3\"><li>x</li><li value=\"9\">y</li></ol>",
        "" to "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td><p>deux</p><p>lignes</p></td></tr></table><p>après</p>",
        "" to "<table><tr><td>A</td>\n<td>B</td></tr><tr><td colspan=\"2\">C</td></tr></table>",
        ".poem p { text-indent: -1em; margin-left: 1em }" to "<div class=\"poem\"><p>vers un</p><p>vers deux</p></div>",
        "" to "<pre>ligne un\n  ligne deux\n\nligne trois</pre><p>a<br/>b\nc</p>",
        "p.first::first-letter { font-size: 3em; float: left }" to "<p class=\"first\">«Lettrine» au début.</p>",
        ".drop-cap { font-size: 2.8em; float: left }" to "<p class=\"first\"><span class=\"drop-cap\">M</span>arie</p>",
        ".sc { font-variant: small-caps } .note { vertical-align: super }" to
            "<p>La <span class=\"sc\">Petite</span> note<sup>1</sup><span class=\"note\">2</span> H<sub>2</sub>O <u>u</u> <s>s</s> <code>c</code></p>",
        "" to "<p>avant</p><hr/><p>après</p><div class=\"separator\">---</div><p>fin</p>",
        ".saut { page-break-before: always } h1 { text-align: center }" to "<h1>Titre</h1><p class=\"saut\">page</p>",
        "" to "<figure><img src=\"a.png\" width=\"120\"/><figcaption>Légende</figcaption></figure><p class=\"caption\">x</p>",
        "p { color: red; float: right; font-size: 30px; position: absolute } @media x { p { margin: 9em } }" to
            "<p style=\"text-align:right; margin-left: 50%\">droite</p><p align=\"center\">centre</p>",
        "" to "<p>\u0007 un caractère de contrôle <i>x</i></p>",
        "a > b, [x] p, p:hover, #id p { font-size: 4em }" to "<p>rien</p>",
    )

    private fun styled(k: Int) = HtmlText.toBlocks(layoutCases[k].second, Css.parse(layoutCases[k].first)) { it }
    private fun paras(k: Int) = (styled(k).first { it is Block.Text } as Block.Text).paragraphs

    @Test
    fun the_text_is_what_it_was_whatever_the_style_sheet() {
        for ((css, html) in layoutCases) assertEquals(html, describe(Legacy.toBlocks(html) { it }), describe(HtmlText.toBlocks(html, Css.parse(css)) { it }))
    }

    @Test
    fun what_the_book_sets() {
        paras(0).let { assertEquals(listOf(Look.JUSTIFY, 1.5f, listOf(4)), listOf(it[0].look!!.align, it[0].look!!.indent, it[1].look!!.soft!!.toList())) }
        assertEquals(listOf(Triple("a", Look.SQUARE, 1), Triple("b", Look.SQUARE, 2), Triple("c", Look.SQUARE, 1), Triple("x", Look.LOWER_ROMAN, 3), Triple("y", Look.LOWER_ROMAN, 9)),
            paras(1).map { Triple(it.text, it.look!!.marker, it.look!!.number) })
        assertEquals(listOf(1.5f, 1.5f, 3f, 1.5f, 1.5f), paras(1).map { it.look!!.left })
        paras(2).let { p ->
            assertEquals("AB", p[0].text)
            assertEquals(listOf(0, 1, 0, 0, 1, 1, 1, 2, 0, 1, 1, 1), p[0].look!!.cells!!.toList())
            assertEquals(listOf(listOf(0, 1, 1, 0, 1, 0), listOf(0, 4, 1, 1, 1, 0), listOf(0, 6, 1, 1, 1, 0)), p.subList(1, 4).map { it.look!!.cells!!.toList() })
            assertNull(p[4].look!!.cells)
        }
        assertEquals(listOf(0, 2), paras(3)[1].look!!.cells!!.toList().subList(3, 5))
        assertEquals(listOf(1f to -1f, 1f to -1f), paras(4).map { it.look!!.left to it.look!!.indent })
        paras(5).let { p ->
            assertEquals(listOf("ligne un\nligne deux", "ligne trois", "a\nb\nc"), p.map { it.text })
            assertNull(p[0].look!!.soft); assertEquals(Para.MONO, p[0].look!!.flags and Para.MONO); assertEquals(listOf(3), p[2].look!!.soft!!.toList())
        }
        assertEquals(2, paras(6)[0].look!!.dropCap)
        assertEquals(1, paras(7)[0].look!!.dropCap)
        paras(8)[0].let { p ->
            val r = p.styles!!
            assertEquals(listOf("Petite" to Para.SMALL_CAPS, "12" to Para.SUPER, "2" to Para.SUB, "u" to Para.UNDERLINE, "s" to Para.STRIKE, "c" to Para.MONO),
                (r.indices step 3).map { p.text.substring(r[it], r[it + 1]) to (r[it + 2] and Para.FLAGS) })
        }
        assertEquals(listOf("avant" to false, "après" to true, "fin" to true), paras(9).map { it.text to it.look!!.rule })
        assertEquals(listOf(listOf<Any>(Look.CENTER, 1, false, 1.6f), listOf<Any>(Look.AUTO, 0, true, 1f)),
            paras(10).map { listOf<Any>(it.look!!.align, it.look!!.level, it.look!!.pageBreak, it.look!!.size) })
        styled(11).let { b ->
            val img = b[0] as Block.Image
            assertEquals(120 to Look.AUTO, img.look!!.pixels to img.look!!.align)
            assertEquals(listOf(Look.CENTER to 0.85f, Look.CENTER to 0.8f), (b[1] as Block.Text).paragraphs.map { it.look!!.align to it.look!!.size })
        }
        assertEquals(listOf(listOf<Any>(Look.RIGHT, 4f, 1f, 0), listOf<Any>(Look.CENTER, 0f, 1f, 0)),
            paras(12).map { listOf<Any>(it.look!!.align, it.look!!.left, it.look!!.size, it.look!!.flags) })
        assertNull(paras(13)[0].look)
        assertEquals(1f, paras(14)[0].look!!.size)
    }

    /** Every page of the EPUBs in FIXTURES (the magazines, and any book put there): the same text as before. */
    @Test
    fun fixtures_read_the_same() {
        val dirs = listOfNotNull(System.getProperty("fixtures")?.takeIf { it.isNotBlank() }, System.getenv("EMPHASIS_BOOKS")).map(::File)
        for (dir in dirs) for (f in dir.listFiles { x -> x.name.endsWith(".epub") }!!.sorted()) {
            var pages = 0; var styled = 0
            ZipFile(f).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith("html", true) || it.name.endsWith(".htm", true) }.forEach { e ->
                    val html = zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                    same(html); pages++
                    styled += HtmlText.toBlocks(html) { it }.filterIsInstance<Block.Text>().sumOf { b -> b.paragraphs.count { it.styles != null } }
                }
            }
            val book = BookParser.parse(f, BookFormat.EPUB, f.name)
            println("== ${f.name}: $pages pages read the same, $styled paragraphs with emphasis, ${book.chapters.size} chapters")
        }
    }
}

/** The earlier HTML → text, word for word: what every highlight made so far was counted in. */
private object Legacy {
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

    /**
     * Paragraphs with their kind, and the images between them. `image` maps an `src` to the
     * source the reader can load, or null to drop it. Markers are single control characters
     * placed before tags are stripped, so the split into paragraphs stays the same as before.
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
        s = lineBreak.replace(s, "\n")
        s = paragraphBreak.replace(s, "\n\n")
        s = anyTag.replace(s, "")
        s = HtmlText.decode(s)
        s = s.replace(Regex("[ \\t\\r\\u00A0\\u2007\\u202F]+"), " ")
        val blocks = ArrayList<Block>()
        var paras = ArrayList<Para>()
        s.split(Regex("\n\\s*\n")).forEach { raw ->
            val para = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (para.isEmpty()) return@forEach
            if (para.startsWith(IMG)) {
                if (paras.isNotEmpty()) { blocks += Block.Text(paras); paras = ArrayList() }
                blocks += Block.Image(para.substring(1).substringBefore('\n').trim())
                return@forEach
            }
            var kind = ParaKind.NORMAL
            var text = para
            while (text.isNotEmpty() && text[0] == MARK && text.length > 1) {
                kind = when (text[1]) { 'H' -> ParaKind.HEADING; 'L' -> ParaKind.LEAD; 'S' -> ParaKind.SMALL; else -> kind }
                text = text.substring(2).trimStart()
            }
            text = text.replace(Regex("$MARK[HLS]"), "")
            if (text == "---" || text == "♦") return@forEach // the pipeline's separators
            if (text.isNotEmpty()) paras += Para(text, kind)
        }
        if (paras.isNotEmpty()) blocks += Block.Text(paras)
        return blocks
    }

}
