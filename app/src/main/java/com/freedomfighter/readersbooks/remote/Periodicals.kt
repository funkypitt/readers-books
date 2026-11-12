package com.freedomfighter.readersbooks.remote

import com.freedomfighter.readersbooks.books.Magazines

/** A newspaper, a magazine, a newsletter or a channel: its name and its issues, the newest first. */
class Periodical(val key: String, val name: String, val issues: List<RemoteBook>, val latest: Long)

/**
 * The news by periodical: the issues whose file names carry the same periodical go together.
 * A file named « 2026-11-10-letemps.epub » belongs to « letemps »; when several files go on
 * differently after the same first word (« 2026-11-09-norwitz-how-to….epub », « 2026-11-02-norwitz-
 * could-this….epub »), that word is the periodical and the rest names the issue. A file without a
 * date in front is filed by its name with the date taken off, as the shelf does with titles.
 * The periodical is shown by the name its issues give it inside (« Le Temps », « Nick Norwitz MD
 * PhD »), once one of them was looked at; until then by what the file names share. Two ways of
 * naming the files of one periodical (same name inside) make one periodical.
 */
object Periodicals {
    private val DATED = Regex("^(\\d{4}-\\d{2}-\\d{2}|\\d{8})[-_ .]+(.+)$")
    private val BLACKLISTED = Regex("_blacklisted_.*\$")

    /** A file name without its extension, its leading date and what a pipeline adds behind: (dated, rest). */
    private fun rest(fileName: String): Pair<Boolean, String> {
        val stem = fileName.substringBeforeLast('.').replace(BLACKLISTED, "").trim()
        val m = DATED.find(stem) ?: return false to stem
        return true to m.groupValues[2].lowercase()
    }

    /** What the file name says of the periodical, for each file name given. */
    fun fileKeys(names: Collection<String>): Map<String, String> {
        val rests = names.associateWith { rest(it) }
        // the first word of dated names → how many different names go on after it
        val after = HashMap<String, HashSet<String>>()
        for ((dated, r) in rests.values) if (dated && '-' in r) after.getOrPut(r.substringBefore('-')) { HashSet() } += r
        return rests.mapValues { (_, v) ->
            val (dated, r) = v
            when {
                !dated -> Magazines.key(r.replace('_', ' '))
                '-' in r && (after[r.substringBefore('-')]?.size ?: 0) >= 2 -> r.substringBefore('-')
                else -> r
            }
        }
    }

    /**
     * The periodicals of these issues, the one with the latest issue first; in each, the newest
     * issue first. `title` = the title an issue carries inside, null or empty when not known.
     */
    fun group(news: List<RemoteBook>, title: (RemoteBook) -> String?, date: (RemoteBook) -> Long): List<Periodical> {
        val keys = fileKeys(news.map { it.name }.toSet())
        val newest = compareByDescending<RemoteBook> { date(it) }.thenByDescending { it.name }
        // by file name first; then the groups that carry the same name inside are one
        val named = news.groupBy { keys.getValue(it.name) }.map { (key, issues) ->
            val sorted = issues.sortedWith(newest)
            val inside = sorted.firstNotNullOfOrNull { b -> title(b)?.takeIf { it.isNotBlank() }?.let { Magazines.name(it) } }
            (inside ?: key.replace('_', ' ').replace('-', ' ')) to sorted
        }
        return named.groupBy { (name, _) -> Magazines.key(name.replace('’', '\'')).ifEmpty { name } }.map { (key, groups) ->
            val issues = groups.flatMap { it.second }.sortedWith(newest)
            val name = groups.maxByOrNull { g -> date(g.second.first()) }!!.first
            Periodical(key, name, issues, date(issues.first()))
        }.sortedWith(compareByDescending<Periodical> { it.latest }.thenBy { it.name.lowercase() })
    }

    private val DASHES = listOf(" — ", " – ", " - ")
    private val YEAR = Regex("\\b(19|20)\\d{2}\\b")

    /**
     * What names an issue in the list of its periodical: its title inside without the periodical's
     * name, and without the edition's date when something else names it (« How to… — Édition du
     * lundi 9 novembre 2026 » is « How to… »: the date is on the line below); else its file's name.
     */
    fun issueTitle(b: RemoteBook, title: String?): String {
        val issue = title?.takeIf { it.isNotBlank() }?.let { Magazines.issue(it) } ?: return b.title
        for (dash in DASHES) {
            val i = issue.lastIndexOf(dash)
            if (i > 0 && YEAR.containsMatchIn(issue.substring(i))) return issue.substring(0, i).trim()
        }
        return issue
    }
}
