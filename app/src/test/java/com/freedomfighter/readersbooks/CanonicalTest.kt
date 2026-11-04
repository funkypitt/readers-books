package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.books.Block
import com.freedomfighter.readersbooks.books.BookParser
import com.freedomfighter.readersbooks.books.Look
import com.freedomfighter.readersbooks.books.Highlights
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The text of every chapter, as highlights count it, against a snapshot taken with an earlier
 * version: a highlight is a place in that text, on the phone, on the desktop and on the drive, so
 * a change of presentation must leave it byte for byte as it was.
 *
 * CANONICAL_BOOKS = folders (separated by « : ») searched for .epub .mobi .azw3 .fb2;
 * CANONICAL_SNAPSHOT = the snapshot file, one line per chapter: file name, chapter, length in
 * UTF-16 units, SHA-256 of the text in UTF-8 — the same file as the desktop's tests/canonical.py;
 * CANONICAL_WRITE=1 writes it instead of checking it.
 */
class CanonicalTest {
    private val extensions = listOf(".epub", ".mobi", ".azw3", ".fb2")

    private fun lines(f: File): List<String> {
        val book = try { BookParser.parse(f, BookParser.detect(f, f.name), f.name) } catch (e: Exception) {
            return listOf("${f.name}\t-\t-\t${e.javaClass.simpleName}")
        }
        val sha = MessageDigest.getInstance("SHA-256")
        return book.chapters.mapIndexed { i, c ->
            val text = Highlights.chapterText(c)
            val hex = sha.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            "${f.name}\t$i\t${text.length}\t$hex"
        }
    }

    /**
     * How every paragraph and picture of the books is set, one line each, for LOOKS_OUT: the
     * desktop writes the same lines (tests/canonical.py looks), so the two can be compared.
     */
    @Test
    fun looks_written_for_the_desktop_to_compare() {
        val roots = System.getenv("CANONICAL_BOOKS")?.takeIf { it.isNotBlank() }?.split(':') ?: return
        val out = File(System.getenv("LOOKS_OUT") ?: return)
        val files = roots.flatMap { r -> File(r).walk().filter { f -> f.isFile && extensions.any { f.name.lowercase().endsWith(it) } }.toList() }.sortedBy { it.name }
        out.bufferedWriter().use { w ->
            for (f in files) {
                val book = try { BookParser.parse(f, BookParser.detect(f, f.name), f.name) } catch (e: Exception) { continue }
                book.chapters.forEachIndexed { c, ch ->
                    var k = 0
                    for (b in ch.blocks) when (b) {
                        is Block.Image -> w.write("${f.name}\t$c\t${k++}\tI ${describe(b.look)}\n")
                        is Block.Text -> for (p in b.paragraphs) w.write("${f.name}\t$c\t${k++}\tP ${p.kind} ${describe(p.look)} ${p.styles?.joinToString(",") ?: "-"}\n")
                    }
                }
            }
        }
    }

    private fun f2(x: Float) = if (x.isNaN()) "-" else "%.2f".format(java.util.Locale.ROOT, x)

    private fun describe(l: Look?): String = if (l == null) "none" else listOf(
        l.align, f2(l.indent), f2(l.left), f2(l.right), f2(l.above), if (l.pageBreak) 1 else 0, if (l.rule) 1 else 0, f2(l.size), l.flags,
        l.level, l.marker, l.number, l.dropCap, l.soft?.joinToString(",") ?: "-", l.cells?.joinToString(",") ?: "-", l.table, f2(l.width), l.pixels
    ).joinToString("|")

    /** How long each book of PERF_BOOKS takes to read (the second of three readings, the JVM warm). */
    @Test
    fun time_to_read() {
        val roots = System.getenv("PERF_BOOKS")?.takeIf { it.isNotBlank() }?.split(':') ?: return
        for (f in roots.flatMap { r -> File(r).walk().filter { it.isFile && extensions.any { e -> it.name.lowercase().endsWith(e) } }.toList() }.sortedBy { it.name }) {
            val ms = (1..3).map { val t = System.nanoTime(); BookParser.parse(f, BookParser.detect(f, f.name), f.name); (System.nanoTime() - t) / 1_000_000 }
            println("== ${f.name}: ${ms[1]} ms (${ms.joinToString("/")})")
        }
    }

    @Test
    fun chapters_read_as_in_the_snapshot() {
        val roots = System.getenv("CANONICAL_BOOKS")?.takeIf { it.isNotBlank() }?.split(':') ?: return
        val snapshot = File(System.getenv("CANONICAL_SNAPSHOT") ?: return)
        val files = roots.flatMap { r -> File(r).walk().filter { f -> f.isFile && extensions.any { f.name.lowercase().endsWith(it) } }.toList() }
        val found = files.flatMap(::lines).sorted()
        if (System.getenv("CANONICAL_WRITE") == "1") {
            snapshot.writeText(found.joinToString("\n") + "\n")
            println("== ${found.size} chapters written")
            return
        }
        val names = found.map { it.substringBefore('\t') }.toSet()
        val wanted = snapshot.readLines().filter { it.isNotEmpty() && it.substringBefore('\t') in names }
        val missing = (wanted.toSet() - found.toSet()).sorted()
        val extra = (found.toSet() - wanted.toSet()).sorted()
        println("== ${names.size} books, ${found.size} chapters checked, ${missing.size + extra.size} differ")
        (missing.map { "  was  $it" } + extra.map { "  now  $it" }).take(20).forEach(::println)
        assertEquals(emptyList<String>(), missing + extra)
    }
}
