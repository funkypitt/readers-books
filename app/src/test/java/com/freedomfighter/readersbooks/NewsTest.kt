package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.remote.News
import com.freedomfighter.readersbooks.remote.RemoteBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The news among the library's books, told by a few bytes of their contents page, as the drive would hand them. */
class NewsTest {
    private fun epub(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in files) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    /** Remote.readRange on bytes held here. */
    private fun reader(bytes: ByteArray) = News.Reader { start, length ->
        val from = (start ?: maxOf(0L, bytes.size.toLong() - length)).toInt()
        bytes.copyOfRange(from, minOf(bytes.size, from + length)) to bytes.size.toLong()
    }

    private fun opf(n: Int, nav: Boolean) = "<package><manifest>" +
        (if (nav) "<item id=\"nav\" href=\"toc.xhtml\" properties=\"nav\" media-type=\"application/xhtml+xml\"/>" else "") +
        (0 until n).joinToString("") { "<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" } +
        "</manifest><spine>" + (0 until n).joinToString("") { "<itemref idref=\"c$it\"/>" } + "</spine></package>"

    @Test fun anIssueIsToldByItsNav() = runBlocking {
        val bytes = epub(mapOf("mimetype" to "application/epub+zip", "OEBPS/content.opf" to opf(4, true),
            "OEBPS/toc.xhtml" to "<nav><ol><li><a href=\"c0.xhtml\"><span class=\"toc-cat\">Monde</span><span class=\"toc-title\">Un titre</span></a></li></ol></nav>") +
            (0 until 4).associate { "OEBPS/c$it.xhtml" to "<html><body><p>Texte.</p></body></html>" })
        assertEquals(true, News.isNews(reader(bytes)))
    }

    @Test fun anIssueIsToldByItsArticles() = runBlocking {
        val bytes = epub(mapOf("c.opf" to opf(8, false)) + (0 until 8).associate { "c$it.xhtml" to "<html><body><p class=\"category\">Monde</p><h1>Titre $it</h1></body></html>" })
        assertEquals(true, News.isNews(reader(bytes)))
    }

    @Test fun aBookIsNoNews() = runBlocking {
        val bytes = epub(mapOf("OEBPS/content.opf" to opf(6, true), "OEBPS/toc.xhtml" to "<nav><ol><li><a href=\"c0.xhtml\">Chapitre 1</a></li></ol></nav>") +
            (0 until 6).associate { "OEBPS/c$it.xhtml" to "<html><body><h1>Chapitre $it</h1><p>Il était une fois.</p></body></html>" })
        assertEquals(false, News.isNews(reader(bytes)))
    }

    @Test fun aNewslettersArticlesAreNewsTooWithTheIssuesOwnDate() = runBlocking {
        val opf = opf(3, true).replace("<package>", "<package><metadata><dc:date>2026-04-16</dc:date></metadata>")
        val bytes = epub(mapOf("OEBPS/content.opf" to opf,
            "OEBPS/toc.xhtml" to "<nav><ol><li><a href=\"c0.xhtml\"><span class=\"toc-title\">Selling the EU</span><span class=\"toc-author\">Thomas Fazi</span></a></li></ol></nav>") +
            (0 until 3).associate { "OEBPS/c$it.xhtml" to "<html><body><h1>Titre</h1></body></html>" })
        val (news, date) = News.kind(reader(bytes))
        assertEquals(true, news)
        assertEquals(java.time.LocalDate.of(2026, 4, 16).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), date)
    }

    @Test fun aPublishersContentsHeadingIsNoNews() = runBlocking {
        val bytes = epub(mapOf("OEBPS/content.opf" to opf(4, true),
            "OEBPS/toc.xhtml" to "<nav><h1 class=\"toc-title\">Contents</h1><ol><li><a href=\"c0.xhtml\">Chapter 1</a></li></ol></nav>") +
            (0 until 4).associate { "OEBPS/c$it.xhtml" to "<html><body><h1>Chapter $it</h1></body></html>" })
        assertEquals(false, News.isNews(reader(bytes)))
    }

    @Test fun aServerWithoutRangesSaysNothing() = runBlocking {
        assertNull(News.isNews { _, _ -> null })
    }

    @Test fun anIssueIsDatedByTheFilesCreation() {
        assertEquals(5L, News.date(RemoteBook("a.epub", "", "r", 1, modified = 9, created = 5)))
        assertEquals(9L, News.date(RemoteBook("a.epub", "", "r", 1, modified = 9)))
    }
}
