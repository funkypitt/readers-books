package com.freedomfighter.readersbooks

import com.freedomfighter.readersbooks.books.Quotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QuotesTest {
    @Test
    fun attributes_between_single_quotes_are_read_as_the_others() {
        assertEquals("<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>",
            Quotes.double("<rootfile full-path='OEBPS/content.opf' media-type='application/oebps-package+xml'/>"))
        assertEquals("<item id=\"nav\" href=\"nav.xhtml\" properties=\"nav\"/>", Quotes.double("<item id='nav' href='nav.xhtml' properties='nav'/>"))
        // the text is never touched, nor an apostrophe inside a double-quoted value, nor a value that holds a double quote
        assertEquals("<p class=\"a b\" id = \"x\">l'été <img src=\"a.jpg\" alt=\"it's a='b' here\"/> <a href=\"x.html#y\" title='say \"hi\"'>d'un</a> x='y'</p>",
            Quotes.double("<p class='a b' id = 'x'>l'été <img src='a.jpg' alt=\"it's a='b' here\"/> <a href='x.html#y' title='say \"hi\"'>d'un</a> x='y'</p>"))
        val plain = "<p class=\"a\">rien à changer</p>"
        assertSame(plain, Quotes.double(plain))
    }
}
