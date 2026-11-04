package com.freedomfighter.readersbooks.books

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * When two copies on the shelf are the same book — a book opened from a file and the same book
 * fetched from the library later, so that the highlights made in the one go to the other.
 *
 * Two rules, either is enough:
 *  - the same bytes: the SHA-256 of the file (the usual case: the very file is on the drive);
 *  - for EPUBs, the same package identifier (the `dc:identifier` the OPF names as its
 *    unique-identifier) AND the same title: the drive's copy was rewritten (by a library tool
 *    that changed its metadata or cover), the book is still that one. The title too, because some
 *    tools stamp the same identifier on every book they make.
 * A title alone is never enough: two different books may share one.
 */
object BookIdentity {
    fun fingerprint(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** The EPUB's own identifier, or "" (another format, or none given). */
    fun epubId(file: File, format: BookFormat): String {
        if (format != BookFormat.EPUB) return ""
        return runCatching {
            ZipFile(file).use { zip ->
                val container = zip.getEntry("META-INF/container.xml")?.let { e -> zip.getInputStream(e).use { it.readBytes().decodeToString() } } ?: return ""
                val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: return ""
                val opf = zip.getEntry(opfPath)?.let { e -> zip.getInputStream(e).use { it.readBytes().decodeToString() } } ?: return ""
                packageId(opf)
            }
        }.getOrDefault("")
    }

    /** The identifier the package names as its own, read from the OPF's text. */
    fun packageId(opf: String): String {
        val pkg = Regex("<(?:\\w+:)?package\\b[^>]*>", RegexOption.IGNORE_CASE).find(opf)?.value ?: return ""
        val ref = Regex("unique-identifier\\s*=\\s*[\"']([^\"']+)[\"']").find(pkg)?.groupValues?.get(1) ?: return ""
        val tag = Regex("<dc:identifier\\b[^>]*\\bid\\s*=\\s*[\"']" + Regex.escape(ref) + "[\"'][^>]*>([^<]*)</dc:identifier>", RegexOption.IGNORE_CASE).find(opf)
        return tag?.groupValues?.get(1)?.trim() ?: ""
    }

    fun same(a: Entry, b: Entry): Boolean =
        (a.fingerprint.isNotEmpty() && a.fingerprint == b.fingerprint) ||
            (a.bookId.isNotEmpty() && a.bookId == b.bookId && a.title == b.title)
}
