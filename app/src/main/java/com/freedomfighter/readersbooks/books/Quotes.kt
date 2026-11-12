package com.freedomfighter.readersbooks.books

/**
 * The attributes of every tag between double quotes. XML allows single quotes as well
 * (id='nav', src='a.jpg'), and some makers of EPUBs write them: read as they are, the package,
 * the contents page and the pictures of such a book would not be found. Only attributes change,
 * never the text. Rule for rule the desktop app's double_quotes.
 */
object Quotes {
    private val TAG = Regex("<[A-Za-z][^<>]*>")
    // a double-quoted value is matched too, and left alone: an apostrophe inside it is not an attribute's
    private val ATTR = Regex("(\\s[\\w:.-]+\\s*=\\s*)(?:\"([^\"]*)\"|'([^'\"]*)')")

    fun double(xml: String): String {
        if ('\'' !in xml) return xml
        return TAG.replace(xml) { tag ->
            if ('\'' !in tag.value) tag.value
            else ATTR.replace(tag.value) { a -> if (a.groups[3] == null) a.value else a.groupValues[1] + "\"" + a.groupValues[3] + "\"" }
        }
    }
}
