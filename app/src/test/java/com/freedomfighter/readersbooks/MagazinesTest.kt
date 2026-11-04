package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.books.Magazines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MagazinesTest {
    @Test
    fun the_issues_of_one_magazine_share_its_name() {
        // titles of the newspapers pipeline (dc:title), and of other makers of issues
        listOf(
            "24 heures — Edition du mardi 3 novembre 2026" to "24 heures",
            "24 heures — Edition du mercredi 4 novembre 2026" to "24 heures",
            "NZZ — Édition du vendredi 24 avril 2026" to "NZZ",
            "Courrier International — Édition du 2026-04-25" to "Courrier International",
            "The Economist — Apr 25th 2026" to "The Economist",
            "Informed with Aaron Siri | Substack — Édition du 02.11.2026" to "Informed with Aaron Siri | Substack",
            "Huberman Lab — Access the Sublime & Live Your Best Life | Robert Greene — Édition du mardi 3 novembre 2026" to "Huberman Lab",
            "Le Monde du 25 avril 2026" to "Le Monde",
            "Der Spiegel vom 3. November 2026" to "Der Spiegel",
            "The Guardian, Wednesday November 4, 2026" to "The Guardian",
            "Le Temps 2026-11-04" to "Le Temps",
            "La Revue N° 42" to "La Revue",
            "A book without a date" to "A book without a date"
        ).forEach { (title, name) -> assertEquals(title, name, Magazines.name(title)) }
        assertEquals(Magazines.key("24 heures — Edition du mardi 3 novembre 2026"), Magazines.key("24 Heures — Édition du jeudi 5 novembre 2026"))
        assertNotEquals(Magazines.key("24 heures — Edition du mardi 3 novembre 2026"), Magazines.key("NZZ — Édition du vendredi 24 avril 2026"))
    }

    @Test
    fun an_issue_is_named_by_what_follows_the_magazine() {
        assertEquals("Edition du mercredi 4 novembre 2026", Magazines.issue("24 heures — Edition du mercredi 4 novembre 2026"))
        assertEquals("25 avril 2026", Magazines.issue("Le Monde du 25 avril 2026"))
        assertEquals("A book without a date", Magazines.issue("A book without a date"))
    }
}
