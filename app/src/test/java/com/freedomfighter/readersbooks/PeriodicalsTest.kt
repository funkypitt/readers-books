package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.remote.Periodicals
import com.freedomfighter.readersbooks.remote.RemoteBook
import org.junit.Assert.assertEquals
import org.junit.Test

class PeriodicalsTest {
    private fun book(name: String, day: Long) = RemoteBook(name, "news", "ref/$name", modified = day)

    @Test
    fun file_names_tell_the_periodical() {
        val keys = Periodicals.fileKeys(listOf(
            "2026-11-10-letemps.epub", "2026-11-09-letemps.epub", "2026-09-03-jaxen-highwire.epub", "2026-09-10-jaxen-highwire.epub",
            "2026-11-09-norwitz-how-to-reverse-2-5-years-of-aging-in-15-days-qyy8fCuQ5_E.epub", "2026-06-01-norwitz-0a7q8ENyBOk.epub",
            "2026-06-02-foundmyfitness-DwtNC2A8gBk.epub", "2026-06-02-foundmyfitness-DwtNC2A8gBk_blacklisted_20260602_185017_speOzTugch.epub",
            "2026-06-03-foundmyfitness-rdcMvDvY4rU.epub", "2026-06-25-apollohealth-pa-20260531.epub", "2026-06-01-apollohealth-pa-20221101.epub",
            "2026-11-01-diplo.epub", "2026-06-24-mel_robbins-sNzswsEIJ-E.epub", "2026-06-25-mel_robbins-bwQkIWf7rhE.epub",
            "Le Temps 2026-11-04.epub", "Le Temps 2026-11-05.epub"))
        assertEquals("letemps", keys["2026-11-10-letemps.epub"])
        assertEquals("jaxen-highwire", keys["2026-09-03-jaxen-highwire.epub"])
        assertEquals("norwitz", keys["2026-11-09-norwitz-how-to-reverse-2-5-years-of-aging-in-15-days-qyy8fCuQ5_E.epub"])
        assertEquals("norwitz", keys["2026-06-01-norwitz-0a7q8ENyBOk.epub"])
        assertEquals("foundmyfitness", keys["2026-06-02-foundmyfitness-DwtNC2A8gBk_blacklisted_20260602_185017_speOzTugch.epub"])
        assertEquals("apollohealth", keys["2026-06-25-apollohealth-pa-20260531.epub"])
        assertEquals("diplo", keys["2026-11-01-diplo.epub"])
        assertEquals("mel_robbins", keys["2026-06-24-mel_robbins-sNzswsEIJ-E.epub"])
        assertEquals(keys["Le Temps 2026-11-04.epub"], keys["Le Temps 2026-11-05.epub"])
    }

    @Test
    fun periodicals_by_latest_issue_and_issues_newest_first() {
        val titles = mapOf(
            "2026-11-10-letemps.epub" to "Le Temps — Édition du 10.11.2026",
            "2026-11-09-norwitz-how-to-reverse-qyy8fCuQ5_E.epub" to "Nick Norwitz MD PhD — How to Reverse — Édition du lundi 9 novembre 2026",
            "2026-10-10-impertinent.epub" to "L’Impertinent — Samedi 10 octobre 2026",
            "2026-11-08-impertinent_hebdo.epub" to "L’Impertinent — Dimanche 8 novembre 2026")
        val news = listOf(book("2026-11-08-letemps.epub", 8), book("2026-11-10-letemps.epub", 10), book("2026-11-09-norwitz-how-to-reverse-qyy8fCuQ5_E.epub", 9),
            book("2026-11-02-norwitz-could-this-wJ72xlhdpoQ.epub", 2), book("2026-10-10-impertinent.epub", 1), book("2026-11-08-impertinent_hebdo.epub", 8),
            book("2026-11-11-24heures.epub", 11))
        val ps = Periodicals.group(news, { titles[it.name] }, { it.modified })
        assertEquals(listOf("24heures", "Le Temps", "Nick Norwitz MD PhD", "L’Impertinent"), ps.map { it.name })
        assertEquals(listOf("2026-11-10-letemps.epub", "2026-11-08-letemps.epub"), ps[1].issues.map { it.name })
        // two ways of naming the files, one name inside: one periodical
        assertEquals(2, ps[3].issues.size)
        assertEquals("How to Reverse", Periodicals.issueTitle(news[2], titles[news[2].name]))
        assertEquals("Édition du 10.11.2026", Periodicals.issueTitle(news[1], titles[news[1].name]))
        assertEquals("Town Hall — 2026-05-31", Periodicals.issueTitle(news[0], "Apollo Health Town Hall — Town Hall — 2026-05-31 — Édition du jeudi 25 juin 2026"))
        assertEquals("2026-11-02-norwitz-could-this-wJ72xlhdpoQ", Periodicals.issueTitle(news[3], null))
    }
}
