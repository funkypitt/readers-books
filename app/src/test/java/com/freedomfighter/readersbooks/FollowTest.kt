package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.remote.Follow
import com.freedomfighter.readersbooks.remote.Followed
import com.freedomfighter.readersbooks.remote.RemoteBook
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class FollowTest {
    private val day = 24 * 3600_000L
    private val t0 = 1_793_000_000_000L - 1_793_000_000_000L % day + 6 * 3600_000L     // a morning, UTC
    private fun book(name: String, folder: String, at: Long) = RemoteBook(name, folder, "http://d/$folder/$name", 1000, at)
    private val books = listOf(
        book("2026-11-02-24heures.epub", "journaux", t0 - 2 * day), book("2026-11-03-24heures.epub", "journaux", t0 - day),
        book("2026-11-04-24heures.epub", "journaux", t0), book("2026-11-04-nzz.epub", "journaux", t0 + 3600_000L),
        book("2026-11-04-courrier.epub", "journaux/hebdos", t0 + 7200_000L), book("Maigret.epub", "Books", t0 + day)
    )

    @Test
    fun a_folder_followed_gives_its_latest_day_at_once() {
        val seen = Follow.firstSeen(books, "journaux", ZoneOffset.UTC)
        assertEquals(setOf("2026-11-02-24heures.epub", "2026-11-03-24heures.epub"), seen.map { it.substringAfterLast('/') }.toSet())
        val f = Followed("journaux", seen)
        // newest first, the folders inside it too, nothing from elsewhere
        assertEquals(listOf("2026-11-04-courrier.epub", "2026-11-04-nzz.epub", "2026-11-04-24heures.epub"), Follow.fresh(books, f, emptySet()).map { it.name })
        // one on the phone already is not new
        assertEquals(2, Follow.fresh(books, f, setOf("http://d/journaux/2026-11-04-nzz.epub")).size)
    }

    @Test
    fun what_was_fetched_or_removed_stays_seen_while_the_drive_has_it() {
        val f = Followed("journaux", setOf("http://d/journaux/2026-11-04-24heures.epub", "http://d/journaux/gone.epub"))
        assertEquals(setOf("http://d/journaux/2026-11-04-24heures.epub"), Follow.prune(f, books).seen)
        assertEquals(4, Follow.fresh(books, f, emptySet()).size)
        // a folder whose books have no date: all of them are new
        assertEquals(emptySet<String>(), Follow.firstSeen(books.map { it.copy(modified = 0L) }, "journaux", ZoneOffset.UTC))
    }
}
