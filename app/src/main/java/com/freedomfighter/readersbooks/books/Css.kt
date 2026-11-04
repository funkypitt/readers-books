package com.freedomfighter.readersbooks.books

/**
 * The little of CSS a reader with its own font, size, margins and colours follows: the
 * publisher's alignment, indents, spacing, sizes of headings and small print, quotations, lists,
 * verse, small capitals, superscripts, rules, drop caps, page breaks and tables. Selectors are
 * kept when simple — an element, a class, an element with classes, and these in a chain of
 * descendants or children; any other rule is left out. The desktop has the same code
 * (readers_books.py, « CSS »), so a book is set alike on both.
 */
object Css {
    class Compound(val tag: String?, val classes: List<String>)

    /** `parts` right to left; `child[i]` = parts[i] is a child (not only a descendant) of parts[i + 1]. */
    class Selector(val parts: List<Compound>, val child: BooleanArray, val firstLetter: Boolean) {
        val specificity: Int = parts.sumOf { it.classes.size * 10 + (if (it.tag != null) 1 else 0) }
    }

    class Decl(val name: String, val value: String, val important: Boolean)

    class Rule(val selector: Selector, val decls: List<Decl>, val order: Int)

    private val comment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val compound = Regex("^([a-zA-Z][\\w-]*)?((?:\\.[\\w-]+)*)$")

    /** The rules of a style sheet; `order` numbers them after those already read. */
    fun parse(text: String, firstOrder: Int = 0): List<Rule> {
        val css = comment.replace(text, " ")
        val rules = ArrayList<Rule>()
        var order = firstOrder
        var i = 0
        val n = css.length
        while (i < n) {
            val c = css[i]
            if (c.isWhitespace() || c == ';' || c == '}') { i++; continue }
            if (c == '@') {
                // an at-rule: up to its « ; », or its whole block (media queries, fonts, pages)
                var j = i
                while (j < n && css[j] != ';' && css[j] != '{') j++
                if (j < n && css[j] == '{') j = skipBlock(css, j) else j++
                i = j
                continue
            }
            val open = css.indexOf('{', i)
            if (open < 0) break
            val close = css.indexOf('}', open)
            val end = if (close < 0) n else close
            val decls = declarations(css.substring(open + 1, end))
            if (decls.isNotEmpty()) for (sel in css.substring(i, open).split(',')) selector(sel)?.let { rules += Rule(it, decls, order++) }
            i = end + 1
        }
        return rules
    }

    private fun skipBlock(css: String, open: Int): Int {
        var depth = 0
        var j = open
        while (j < css.length) {
            if (css[j] == '{') depth++ else if (css[j] == '}') { depth--; if (depth == 0) return j + 1 }
            j++
        }
        return css.length
    }

    fun declarations(body: String): List<Decl> {
        val out = ArrayList<Decl>()
        for (d in body.split(';')) {
            val colon = d.indexOf(':')
            if (colon <= 0) continue
            val name = d.substring(0, colon).trim().lowercase()
            var value = d.substring(colon + 1).trim()
            val important = value.endsWith("!important", ignoreCase = true)
            if (important) value = value.dropLast(10).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) out += Decl(name, value.lowercase(), important)
        }
        return out
    }

    private fun selector(text: String): Selector? {
        var t = text.trim()
        if (t.isEmpty()) return null
        var firstLetter = false
        for (p in listOf("::first-letter", ":first-letter")) if (t.endsWith(p, ignoreCase = true)) { firstLetter = true; t = t.dropLast(p.length).trimEnd(); break }
        if (t.isEmpty() || t.any { it in "[]*+~:#()\"'@\\" }) return null
        val parts = ArrayList<Compound>()
        val child = ArrayList<Boolean>()
        var pendingChild = false
        for (tok in t.replace(">", " > ").split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            if (tok == ">") { if (parts.isEmpty()) return null; pendingChild = true; continue }
            val m = compound.matchEntire(tok) ?: return null
            val tag = m.groupValues[1].takeIf { it.isNotEmpty() }?.lowercase()
            val classes = m.groupValues[2].split('.').filter { it.isNotEmpty() }
            if (tag == null && classes.isEmpty()) return null
            parts += Compound(tag, classes)
            child += pendingChild
            pendingChild = false
        }
        if (parts.isEmpty() || pendingChild) return null
        // right to left; child[i] tells how parts[i] hangs on parts[i + 1]
        val rl = parts.reversed()
        val flags = BooleanArray(rl.size)
        for (k in 0 until rl.size - 1) flags[k] = child[rl.size - 1 - k]
        return Selector(rl, flags, firstLetter)
    }

    /** Rules indexed by the element or first class of their rightmost part. */
    class Sheet(rules: List<Rule>) {
        val byKey = HashMap<String, ArrayList<Rule>>()
        val isEmpty = rules.isEmpty()
        init {
            for (r in rules) {
                val last = r.selector.parts[0]
                val key = if (last.classes.isNotEmpty()) "." + last.classes[0] else last.tag!!
                byKey.getOrPut(key) { ArrayList() } += r
            }
        }
    }

    // ---- values --------------------------------------------------------------------------

    /** A percentage of the text's width, as ems of a page about twenty ems wide. */
    private const val EM_PER_PERCENT = 0.2f
    private val length = Regex("^([+-]?(?:\\d+\\.?\\d*|\\.\\d+))(em|rem|%|px|pt|ex|ch)?$")

    /** A length in ems; null for what is not one (auto, cm, a word). `px`/`pt` at 16 px / 12 pt the em. */
    fun em(value: String, fontSize: Boolean = false): Float? {
        val v = value.trim()
        if (v == "0") return 0f
        val m = length.matchEntire(v) ?: return null
        val x = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues[2]) {
            "em", "rem" -> x
            "%" -> if (fontSize) x / 100f else x * EM_PER_PERCENT
            "ex", "ch" -> x * 0.5f
            "px" -> if (fontSize) null else x / 16f
            "pt" -> if (fontSize) null else x / 12f
            "" -> if (x == 0f) 0f else null
            else -> null
        }
    }

    /** The 1 to 4 values of a margin or padding shorthand, as top, right, bottom, left. */
    fun sides(value: String): Array<String>? {
        val v = value.trim().split(Regex("\\s+"))
        return when (v.size) {
            1 -> arrayOf(v[0], v[0], v[0], v[0])
            2 -> arrayOf(v[0], v[1], v[0], v[1])
            3 -> arrayOf(v[0], v[1], v[2], v[1])
            4 -> arrayOf(v[0], v[1], v[2], v[3])
            else -> null
        }
    }

    val sizeWords = mapOf(
        "xx-small" to 0.6f, "x-small" to 0.75f, "small" to 0.89f, "medium" to 1f,
        "large" to 1.2f, "x-large" to 1.5f, "xx-large" to 2f, "xxx-large" to 3f
    )
}
