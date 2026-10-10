package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.data.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsTest {
    @Test fun roundTrip() {
        val json = Credentials.build("https://x.connect.kdrive.infomaniak.com/", "me", "p w")
        val got = Credentials.read(json)
        assertFalse(got.fromFallback)
        assertEquals("https://x.connect.kdrive.infomaniak.com/", got.account.url)
        assertEquals("me", got.account.username)
        assertEquals("p w", got.account.password)
    }

    /** A file that only names a share link says nothing a library can be made of. */
    @Test(expected = Credentials.NothingForUs::class) fun shareLinkOnly() {
        Credentials.read("""{"format":"readers-credentials","version":1,"readers-books":{"share":"https://kdrive.infomaniak.com/app/share/1/abc"}}""")
    }

    @Test fun magazineReaderSectionIsAFallback() {
        val got = Credentials.read("""{"format":"readers-credentials","version":1,"magazine-reader":{"url":"https://d/","username":"u","password":"p"}}""")
        assertTrue(got.fromFallback)
        assertEquals("https://d/", got.account.url)
    }

    @Test fun scannerServerIsAFallbackWithoutItsFolder() {
        val got = Credentials.read("""{"format":"readers-credentials","version":1,"readers-scanner":{"server":"https://d/","folder":"Scans","username":"u","password":"p"}}""")
        assertTrue(got.fromFallback)
        assertEquals("https://d/", got.account.url)
        assertEquals("u", got.account.username)
        assertEquals("p", got.account.password)
    }

    @Test fun ownSectionWinsOverFallbacks() {
        val got = Credentials.read("""{"format":"readers-credentials","version":1,"readers-scanner":{"server":"https://other/","username":"x","password":"y"},"readers-books":{"url":"https://mine/","username":"me"}}""")
        assertFalse(got.fromFallback)
        assertEquals("https://mine/", got.account.url)
    }

    @Test(expected = Credentials.NotCredentials::class) fun otherJson() { Credentials.read("""{"a":1}""") }
    @Test(expected = Credentials.NotCredentials::class) fun notJson() { Credentials.read("hello") }
    @Test(expected = Credentials.NothingForUs::class) fun nothingForUs() { Credentials.read("""{"format":"readers-credentials","version":1,"readers-tasks":{"url":"https://t/"}}""") }
}
