package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.remote.RemoteLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

/** The order a sync lists the drive in: the folders that held books, then the way to them, then the rest; each once. */
class WalkTest {
    @Test fun theFoldersWithBooksComeFirstThenTheWayToThemThenTheRest() {
        val w = RemoteLibrary.Walk(setOf("Books/Maigret", "newspapers/journaux")) { ref -> ref.removePrefix("dav:") }
        listOf("dav:", "dav:Photos", "dav:Books", "dav:Photos/2020", "dav:newspapers/journaux", "dav:Books/Maigret", "dav:newspapers").forEach { w.add(it) }
        w.add("dav:Books/Maigret")          // met again on the way down: listed once
        val order = generateSequence { w.next() }.toList()
        assertEquals(listOf("dav:newspapers/journaux", "dav:Books/Maigret", "dav:", "dav:Books", "dav:newspapers", "dav:Photos", "dav:Photos/2020"), order)
    }

    @Test fun aFolderRefusedForTheLoginIsFirstInLine() {
        val w = RemoteLibrary.Walk(emptySet()) { it }
        w.add("a"); w.add("b"); w.retry("c")
        assertEquals("c", w.next())
    }
}
