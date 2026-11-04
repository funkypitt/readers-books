package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.books.Block
import com.freedomfighter.readersbooks.books.Book
import com.freedomfighter.readersbooks.books.BookFormat
import com.freedomfighter.readersbooks.books.BookParser
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
        assertEquals(ParaKind.LEAD, paras[3].kind); assertEquals(listOf(Triple("chapô", Para.BOLD, 3)), runs(paras[3]))
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
