package com.freedomfighter.readersbooks.books

import java.text.Normalizer

/**
 * The issues of one magazine, told apart from those of another by the title of the issue
 * (dc:title), the date taken off: « 24 heures — Edition du mardi 3 novembre 2026 » and « 24 heures
 * — Edition du mercredi 4 novembre 2026 » are two issues of « 24 heures ». The title, not the
 * publisher: one publisher prints several papers (Tamedia: 24 heures, Tribune de Genève…), and a
 * pipeline that names its podcasts « Huberman Lab — <episode> — Édition du … » files them under
 * the show. The name is what comes before the first dash between spaces when what follows has a
 * figure in it (a date, a number); otherwise the title up to its date, if it has one; otherwise
 * the whole title. Only books seen to be magazines are grouped.
 */
object Magazines {
    private val DASHES = listOf(" — ", " – ", " - ")
    private const val MONTH = "\\p{L}{3,}\\.?"
    private val DATE = Regex(
        "\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}|\\b\\d{1,2}(er|\\.)?\\s+$MONTH\\s+\\d{4}|\\b$MONTH\\s+\\d{1,2}(st|nd|rd|th)?,?\\s+\\d{4}" +
            "|\\b$MONTH\\s+\\d{4}(?![-./]?\\d)|\\b(n[°ºo]\\.?|nr\\.?|no\\.?|#)\\s*\\d+",
        RegexOption.IGNORE_CASE
    )
    // what is left before a date: « Le Monde du », « Der Spiegel vom », « The Times of », « Edición del »
    private val TRAILING = Regex("[\\s,;:·|(\\-–—]+$|\\s+(du|de|des|del|de la|do|da|of|the|vom|von|am|le|la|el|edition|édition|edición|edição|ausgabe|issue|выпуск|от)$", RegexOption.IGNORE_CASE)
    private val WEEKDAY = Regex("\\s+(lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|monday|tuesday|wednesday|thursday|friday|saturday|sunday|montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)$", RegexOption.IGNORE_CASE)

    private val LEADING = Regex("^(du|de|des|del|do|da|of|vom|von|от)\\s+", RegexOption.IGNORE_CASE)

    /** The magazine's name in the title of one of its issues. */
    fun name(title: String): String {
        val t = title.trim()
        for (dash in DASHES) {
            val i = t.indexOf(dash)
            if (i > 0 && t.substring(i).any { it.isDigit() }) return t.substring(0, i).trim()
        }
        val date = DATE.find(t) ?: return t
        if (date.range.first == 0) return t
        var head = t.substring(0, date.range.first)
        while (true) {
            val next = head.replace(TRAILING, "").replace(WEEKDAY, "")
            if (next == head) break
            head = next
        }
        return head.trim().ifEmpty { t }
    }

    /** What issues of the same magazine share: its name in lower case, accents and spacing aside. */
    fun key(title: String): String =
        Normalizer.normalize(name(title).lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").trim()

    /** The title of the issue without the magazine's name: « Edition du mercredi 4 novembre 2026 ». */
    fun issue(title: String): String {
        val n = name(title)
        if (n == title.trim()) return title.trim()
        return title.trim().removePrefix(n).trimStart(' ', '—', '–', '-', ',', ':', '|', '·').replace(LEADING, "").ifEmpty { title.trim() }
    }

    /** When an issue came out, as far as the phone knows: the drive's date for it, else when it came onto the phone. */
    fun time(e: Entry): Long = if (e.sourceModified > 0L) e.sourceModified else e.added

    /** The issues of each magazine with two or more on the phone, by [key], newest first. */
    fun groups(entries: List<Entry>): Map<String, List<Entry>> =
        entries.filter { it.magazine }.groupBy { key(it.title) }.filter { (k, v) -> k.isNotEmpty() && v.size >= 2 }
            .mapValues { (_, v) -> v.sortedWith(compareByDescending<Entry> { time(it) }.thenByDescending { it.title }) }

    /** The issues of one magazine on the phone, newest first. */
    fun issues(entries: List<Entry>, key: String): List<Entry> =
        entries.filter { it.magazine && key(it.title) == key }.sortedWith(compareByDescending<Entry> { time(it) }.thenByDescending { it.title })
}
