package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.books.Block
import com.freedomfighter.readersbooks.books.BookFormat
import com.freedomfighter.readersbooks.books.BookParser
import org.junit.Test
import java.io.File

class ParserTest {
    @Test
    fun fixtures() {
        val dir = File(System.getProperty("fixtures") ?: return)
        dir.listFiles { f -> f.name.endsWith(".epub") }!!.sorted().forEach { f ->
            val b = BookParser.parse(f, BookFormat.EPUB, f.name)
            val images = b.chapters.sumOf { c -> c.blocks.count { it is Block.Image } }
            println("== ${f.name}: '${b.title}' chapters=${b.chapters.size} images=$images magazine=${b.magazine?.articles?.size}")
            b.magazine?.articles?.take(3)?.forEach { a -> println("   [${a.chapter}] ${a.category} | ${a.title.take(50)} | ${a.author?.take(30)} | ${a.image}") }
            b.chapters.firstOrNull()?.blocks?.take(6)?.forEach { bl ->
                when (bl) { is Block.Image -> println("   IMG ${bl.source}"); is Block.Text -> bl.paragraphs.take(5).forEach { println("   ${it.kind} ${it.text.take(70)}") } }
            }
        }
    }
}
