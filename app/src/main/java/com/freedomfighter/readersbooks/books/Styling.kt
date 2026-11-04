package com.freedomfighter.readersbooks.books

/**
 * What a style sheet makes of one element, for what the reader follows. Inherited: alignment,
 * indent, size, weight, slant, small capitals, case, monospace (code), line breaks kept, raised
 * or lowered, underline and strike, list marker. The element's own: margins (ems of the reader's
 * size), page breaks, float (a drop cap), width (a picture), a ::first-letter rule.
 */
internal class Style {
    var align = Look.AUTO
    var indent = Float.NaN
    var size = 1f
    var sizeSet = false
    var bold = false
    var weightSet = false
    var italic = false
    var smallCaps = false
    var transform = 0 // 1 upper, 2 lower, 3 capitalize
    var mono = false
    var pre = false
    var valign = 0 // 1 super, 2 sub
    var underline = false
    var strike = false
    var listType = -1 // -1 not set, 0 none, else a Look marker
    var mt = 0f; var mb = 0f; var ml = 0f; var mr = 0f
    var pt = 0f; var pb = 0f; var pl = 0f; var pr = 0f
    var breakBefore = false
    var breakAfter = false
    var floatLeft = false
    var width = Float.NaN
    var firstLetter = false
    var ownIndent = Float.NaN

    fun inherit(): Style = Style().also { s ->
        s.align = align; s.indent = indent; s.size = size; s.sizeSet = sizeSet; s.bold = bold; s.weightSet = weightSet
        s.italic = italic; s.smallCaps = smallCaps; s.transform = transform; s.mono = mono; s.pre = pre
        s.valign = valign; s.underline = underline; s.strike = strike; s.listType = listType
    }

    /** The [Para] flags of what this style sets. */
    val flags: Int get() = (if (italic) Para.ITALIC else 0) or (if (bold) Para.BOLD else 0) or (if (smallCaps) Para.SMALL_CAPS else 0) or
        (if (valign == 1) Para.SUPER else 0) or (if (valign == 2) Para.SUB else 0) or (if (underline) Para.UNDERLINE else 0) or
        (if (strike) Para.STRIKE else 0) or (if (mono) Para.MONO else 0) or
        (when (transform) { 1 -> Para.UPPER; 2 -> Para.LOWER; 3 -> Para.CAPITALIZE; else -> 0 })

    fun apply(name: String, value: String, parent: Style) {
        when (name) {
            "text-align" -> when (value) {
                "left", "start", "-webkit-left" -> align = Look.LEFT
                "center", "-webkit-center", "middle" -> align = Look.CENTER
                "right", "end", "-webkit-right" -> align = Look.RIGHT
                "justify" -> align = Look.JUSTIFY
            }
            "text-indent" -> Css.em(value)?.let { ownIndent = it }
            "margin", "padding" -> Css.sides(value)?.let { v ->
                val e = v.map { Css.em(it) }
                if (name == "margin") { e[0]?.let { mt = it }; e[1]?.let { mr = it }; e[2]?.let { mb = it }; e[3]?.let { ml = it } }
                else { e[0]?.let { pt = it }; e[1]?.let { pr = it }; e[2]?.let { pb = it }; e[3]?.let { pl = it } }
            }
            "margin-top" -> Css.em(value)?.let { mt = it }
            "margin-bottom" -> Css.em(value)?.let { mb = it }
            "margin-left" -> Css.em(value)?.let { ml = it }
            "margin-right" -> Css.em(value)?.let { mr = it }
            "padding-top" -> Css.em(value)?.let { pt = it }
            "padding-bottom" -> Css.em(value)?.let { pb = it }
            "padding-left" -> Css.em(value)?.let { pl = it }
            "padding-right" -> Css.em(value)?.let { pr = it }
            "font-size" -> {
                val f = Css.sizeWords[value] ?: when (value) {
                    "smaller" -> parent.size * 0.83f
                    "larger" -> parent.size * 1.2f
                    else -> Css.em(value, fontSize = true)?.let { if (value.endsWith("rem")) it else parent.size * it }
                }
                if (f != null && f > 0f) { size = f; sizeSet = true }
            }
            "font-weight" -> when {
                value == "bold" || value == "bolder" || (value.toIntOrNull() ?: 0) >= 600 -> { bold = true; weightSet = true }
                value == "normal" || value == "lighter" || (value.toIntOrNull() ?: 1000) < 600 -> { bold = false; weightSet = true }
            }
            "font-style" -> when (value) { "italic", "oblique" -> italic = true; "normal" -> italic = false }
            "font-variant", "font-variant-caps" -> when {
                value.contains("small-caps") -> smallCaps = true
                value == "normal" || value == "none" -> smallCaps = false
            }
            "text-transform" -> transform = when (value) { "uppercase" -> 1; "lowercase" -> 2; "capitalize" -> 3; "none" -> 0; else -> transform }
            "vertical-align" -> when (value) { "super" -> valign = 1; "sub" -> valign = 2; "baseline" -> valign = 0 }
            "text-decoration", "text-decoration-line" -> {
                if (value.contains("none")) { underline = false; strike = false }
                if (value.contains("underline")) underline = true
                if (value.contains("line-through")) strike = true
            }
            "white-space" -> when (value) { "pre", "pre-wrap", "pre-line", "break-spaces" -> pre = true; "normal", "nowrap" -> pre = false }
            "page-break-before", "break-before" -> if (value in BREAKS) breakBefore = true else if (value == "auto" || value == "avoid") breakBefore = false
            "page-break-after", "break-after" -> if (value in BREAKS) breakAfter = true else if (value == "auto" || value == "avoid") breakAfter = false
            "list-style-type", "list-style" -> for (w in value.split(Regex("\\s+"))) MARKERS[w]?.let { listType = it }
            "float" -> floatLeft = value == "left"
            "width" -> if (value.endsWith("%")) value.dropLast(1).trim().toFloatOrNull()?.let { if (it > 0f) width = minOf(it, 100f) / 100f }
        }
    }

    companion object {
        val BREAKS = setOf("always", "page", "left", "right", "recto", "verso")
        val MARKERS = mapOf(
            "none" to 0, "disc" to Look.DISC, "circle" to Look.CIRCLE, "square" to Look.SQUARE,
            "decimal" to Look.DECIMAL, "decimal-leading-zero" to Look.DECIMAL,
            "lower-alpha" to Look.LOWER_ALPHA, "lower-latin" to Look.LOWER_ALPHA, "upper-alpha" to Look.UPPER_ALPHA, "upper-latin" to Look.UPPER_ALPHA,
            "lower-roman" to Look.LOWER_ROMAN, "upper-roman" to Look.UPPER_ROMAN
        )
    }
}

/**
 * The elements of one page, as their tags open and close, with the style of each: the stack the
 * reader keeps while it takes the tags out of the text. Node 0 is the page itself.
 */
internal class Tree(private val sheet: Css.Sheet?, private val fb2: Boolean) {
    class Node(val tag: String, val classes: List<String>, val inline: List<Css.Decl>?, val parent: Int, val attrs: Map<String, String>) {
        var counter = 0      // a list's items so far, a table's rows, a row's columns
        var ordinal = 0      // an item's number, a row's index, a cell's column
        var span = 1         // the columns a cell takes
    }

    val nodes = arrayListOf(Node("", emptyList(), null, -1, emptyMap()))
    private val styles = ArrayList<Style?>().apply { add(Style()) }
    private data class Alike(val parent: Int, val tag: String, val classes: List<String>, val inline: List<String>?, val align: String?)
    private val alike = HashMap<Alike, Style>()
    var current = 0

    private val attrRe = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>\"']+))")
    private val tagName = Regex("^</?\\s*([a-zA-Z][\\w:-]*)")

    /**
     * A tag as it was in the page (from its last « < », like the reader's tag stripping). Returns
     * the name of an element without inside (br, hr…), which the caller places itself.
     */
    fun tag(raw: String): String? {
        val t = raw.substring(raw.lastIndexOf('<'))
        if (t.startsWith("<!") || t.startsWith("<?")) return null
        val name = tagName.find(t)?.groupValues?.get(1)?.substringAfterLast(':')?.lowercase() ?: return null
        if (t.startsWith("</")) { close(name); return null }
        if (name in VOID) return name
        if (t.endsWith("/>")) return null
        open(name, attributes(t))
        return null
    }

    fun attributes(t: String): Map<String, String> {
        val out = HashMap<String, String>()
        val body = t.substring(1).dropWhile { !it.isWhitespace() && it != '>' && it != '/' }
        for (m in attrRe.findAll(body)) {
            val v = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            out[m.groupValues[1].substringAfterLast(':').lowercase()] = v
        }
        return out
    }

    /** A node for an element that has no inside (a picture), under the current one. */
    fun leaf(name: String, attrs: Map<String, String>): Int {
        nodes += node(name, attrs, current)
        styles += null
        return nodes.size - 1
    }

    private fun node(name: String, attrs: Map<String, String>, parent: Int): Node {
        val classes = attrs["class"]?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
        val inline = attrs["style"]?.let { Css.declarations(it.lowercase()) }
        return Node(name, classes, inline, parent, attrs)
    }

    private fun nearest(from: Int, names: Set<String>, stop: Set<String> = emptySet()): Int {
        var n = from
        while (n > 0) { val t = nodes[n].tag; if (t in names) return n; if (t in stop) return -1; n = nodes[n].parent }
        return -1
    }

    private fun open(name: String, attrs: Map<String, String>) {
        // what HTML closes by itself: a paragraph before a block, an item before the next
        if (name in BLOCK) nearest(current, setOf("p"), BLOCK - "p").takeIf { it > 0 }?.let { current = nodes[it].parent }
        when (name) {
            "li" -> nearest(current, setOf("li"), setOf("ul", "ol")).takeIf { it > 0 }?.let { current = nodes[it].parent }
            "td", "th" -> nearest(current, setOf("td", "th"), setOf("tr", "table")).takeIf { it > 0 }?.let { current = nodes[it].parent }
            "tr" -> nearest(current, setOf("tr"), setOf("table")).takeIf { it > 0 }?.let { current = nodes[it].parent }
            "dt", "dd" -> nearest(current, setOf("dt", "dd"), setOf("dl")).takeIf { it > 0 }?.let { current = nodes[it].parent }
        }
        val n = node(name, attrs, current)
        when (name) {
            "ol", "ul" -> { n.ordinal = attrs["start"]?.trim()?.toIntOrNull() ?: 1; n.counter = n.ordinal - 1 }
            "li" -> nearest(current, setOf("ul", "ol")).takeIf { it > 0 }?.let { l ->
                val list = nodes[l]
                list.counter = attrs["value"]?.trim()?.toIntOrNull() ?: (list.counter + 1)
                n.ordinal = list.counter
            }
            "tr" -> nearest(current, setOf("table")).takeIf { it > 0 }?.let { t -> n.ordinal = nodes[t].counter++ }
            "td", "th" -> nearest(current, setOf("tr"), setOf("table")).takeIf { it > 0 }?.let { r ->
                val row = nodes[r]
                n.ordinal = row.counter
                n.span = (attrs["colspan"]?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 20)
                row.counter += n.span
            }
        }
        nodes += n
        styles += null
        current = nodes.size - 1
    }

    private fun close(name: String) {
        var n = current
        while (n > 0 && nodes[n].tag != name) n = nodes[n].parent
        if (n > 0) current = nodes[n].parent
    }

    // ---- styles --------------------------------------------------------------------------

    fun style(n: Int): Style {
        styles[n]?.let { return it }
        // parents first, without deep recursion
        val pending = ArrayList<Int>()
        var p = n
        while (p > 0 && styles[p] == null) { pending += p; p = nodes[p].parent }
        for (k in pending.indices.reversed()) {
            // siblings alike are styled alike: no rule here looks at anything but the ancestors
            val node = nodes[pending[k]]
            val key = Alike(node.parent, node.tag, node.classes, node.inline?.map { it.name + ":" + it.value + it.important }, node.attrs["align"])
            styles[pending[k]] = alike.getOrPut(key) { compute(pending[k]) }
        }
        return styles[n]!!
    }

    private fun compute(n: Int): Style {
        val node = nodes[n]
        val parent = styles[node.parent]!!
        val s = parent.inherit()
        // ua < presentational attributes < author by specificity, then order < inline; !important above all
        class D(val priority: Int, val order: Int, val decl: Css.Decl)
        val all = ArrayList<D>()
        ua(n).forEachIndexed { i, d -> all += D(-1, i, d) }
        node.attrs["align"]?.let { a -> all += D(0, -1, Css.Decl("text-align", a.trim().lowercase(), false)) }
        if (sheet != null && !sheet.isEmpty) {
            val seen = HashSet<Css.Rule>()
            fun from(key: String) {
                for (r in sheet.byKey[key] ?: return) if (seen.add(r) && matches(r.selector, n)) {
                    if (r.selector.firstLetter) { if (bigLetter(r.decls)) s.firstLetter = true; continue }
                    for (d in r.decls) all += D(r.selector.specificity + (if (d.important) 100_000 else 0), r.order, d)
                }
            }
            from(node.tag)
            for (c in node.classes) from(".$c")
        }
        node.inline?.forEach { d -> all += D(50_000 + (if (d.important) 100_000 else 0), 0, d) }
        all.sortWith(compareBy<D> { it.priority }.thenBy { it.order })
        if (node.tag in MONO) s.mono = true
        for (d in all) s.apply(d.decl.name, d.decl.value, parent)
        // lengths in ems are the element's own ems: the margins, and the indent its paragraphs inherit
        if (!s.ownIndent.isNaN()) s.indent = s.ownIndent * s.size
        s.mt *= s.size; s.mb *= s.size; s.ml *= s.size; s.mr *= s.size
        s.pt *= s.size; s.pb *= s.size; s.pl *= s.size; s.pr *= s.size
        return s
    }

    private fun bigLetter(decls: List<Css.Decl>): Boolean = decls.any { d ->
        (d.name == "float" && d.value == "left") ||
            (d.name == "font-size" && ((Css.sizeWords[d.value] ?: Css.em(d.value, fontSize = true)) ?: 0f) >= 1.5f)
    }

    private fun matches(sel: Css.Selector, n: Int): Boolean {
        if (!compoundMatches(sel.parts[0], n)) return false
        var cur = n
        for (k in 1 until sel.parts.size) {
            if (sel.child[k - 1]) {
                cur = nodes[cur].parent
                if (cur <= 0 || !compoundMatches(sel.parts[k], cur)) return false
            } else {
                cur = nodes[cur].parent
                while (cur > 0 && !compoundMatches(sel.parts[k], cur)) cur = nodes[cur].parent
                if (cur <= 0) return false
            }
        }
        return true
    }

    private fun compoundMatches(c: Css.Compound, n: Int): Boolean {
        val node = nodes[n]
        if (c.tag != null && c.tag != node.tag) return false
        for (k in c.classes) if (k !in node.classes) return false
        return true
    }

    /** What every reader does with these elements when the book says nothing. */
    private fun ua(n: Int): List<Css.Decl> {
        val node = nodes[n]
        val out = ArrayList<Css.Decl>()
        fun d(name: String, value: String) { out += Css.Decl(name, value, false) }
        when (node.tag) {
            "h1" -> { d("font-size", "2em"); d("font-weight", "bold") }
            "h2" -> { d("font-size", "1.5em"); d("font-weight", "bold") }
            "h3" -> { d("font-size", "1.17em"); d("font-weight", "bold") }
            "h4" -> d("font-weight", "bold")
            "h5" -> { d("font-size", "0.83em"); d("font-weight", "bold") }
            "h6" -> { d("font-size", "0.67em"); d("font-weight", "bold") }
            "b", "strong" -> d("font-weight", "bold")
            "i", "em", "var", "dfn", "emphasis" -> d("font-style", "italic")
            "u", "ins" -> d("text-decoration", "underline")
            "s", "strike", "del", "strikethrough" -> d("text-decoration", "line-through")
            "sup" -> d("vertical-align", "super")
            "sub" -> d("vertical-align", "sub")
            "pre" -> d("white-space", "pre")
            "small" -> d("font-size", "smaller")
            "big" -> d("font-size", "larger")
            "center" -> d("text-align", "center")
            "th" -> { d("font-weight", "bold"); d("text-align", "center") }
            "caption" -> d("text-align", "center")
            "figcaption" -> { d("text-align", "center"); d("font-size", "0.85em") }
            "blockquote" -> d("margin", "0.5em 1.5em")
            "ul", "ol" -> {
                d("padding-left", "1.5em")
                val depth = generateSequence(node.parent) { nodes[it].parent.takeIf { p -> p > 0 } }.count { nodes[it].tag == "ul" || nodes[it].tag == "ol" }
                d("list-style-type", if (node.tag == "ol") "decimal" else when (depth) { 0 -> "disc"; 1 -> "circle"; else -> "square" })
            }
            "dd" -> d("margin-left", "1.5em")
            "figure" -> d("margin", "0.5em 0")
            "cite" -> if (fb2) d("margin", "0.5em 1.5em") else d("font-style", "italic")
            "title" -> if (fb2) { d("text-align", "center"); d("font-weight", "bold"); d("font-size", "1.3em") }
            "subtitle" -> if (fb2) { d("text-align", "center"); d("font-weight", "bold") }
            "epigraph" -> if (fb2) { d("margin-left", "2em"); d("font-style", "italic") }
            "text-author" -> if (fb2) d("text-align", "right")
            "poem" -> if (fb2) d("margin-left", "2em")
            "stanza" -> if (fb2) d("margin-bottom", "0.5em")
            "v" -> if (fb2) d("text-indent", "0")
        }
        if (node.classes.any { CAPTION.containsMatchIn(it.lowercase()) }) d("text-align", "center")
        return out
    }

    // ---- shapes the reader needs ------------------------------------------------------------

    fun isBlock(n: Int) = n == 0 || nodes[n].tag in BLOCK
    fun isMono(n: Int): Boolean { var p = n; while (p > 0) { if (nodes[p].tag in MONO) return true; p = nodes[p].parent }; return false }

    /** The element a paragraph that begins in `n` belongs to: the nearest block. */
    fun blockOf(n: Int): Int { var p = n; while (p > 0 && nodes[p].tag !in BLOCK) p = nodes[p].parent; return p }

    fun within(n: Int, ancestor: Int): Boolean { var p = n; while (p > 0) { if (p == ancestor) return true; p = nodes[p].parent }; return ancestor == 0 }

    fun chain(n: Int): IntArray { val out = ArrayList<Int>(); var p = n; while (p > 0) { out += p; p = nodes[p].parent }; return out.toIntArray() }

    fun cellOf(n: Int): Int { var p = n; while (p > 0) { val t = nodes[p].tag; if (t == "td" || t == "th") return p; if (t == "table") return -1; p = nodes[p].parent }; return -1 }
    fun tableOf(n: Int): Int { var p = n; while (p > 0) { if (nodes[p].tag == "table") return p; p = nodes[p].parent }; return -1 }
    fun rowOf(n: Int): Int { var p = n; while (p > 0) { if (nodes[p].tag == "tr") return p; if (nodes[p].tag == "table") return -1; p = nodes[p].parent }; return -1 }
    fun isHeader(cell: Int): Boolean = nodes[cell].tag == "th" || nearest(cell, setOf("thead"), setOf("table")) > 0

    fun isDropCap(n: Int, block: Int): Boolean {
        val node = nodes[n]
        if (node.classes.any { DROP_CAP.matches(it.lowercase()) }) return true
        val s = style(n)
        return s.floatLeft && s.size >= style(block).size * 1.5f
    }

    companion object {
        val VOID = setOf("br", "hr", "img", "col", "meta", "link", "input", "wbr", "source", "area", "base", "param", "track", "embed", "image", "empty-line")
        val BLOCK = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "dl", "dt", "dd", "blockquote", "pre", "section", "article",
            "aside", "header", "footer", "nav", "figure", "figcaption", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption",
            "center", "body", "html", "main", "address", "hgroup", "details", "summary", "form", "fieldset",
            "title", "subtitle", "epigraph", "poem", "stanza", "v", "cite", "text-author", "annotation"
        )
        /** What is not a margin of the text: the page's own, and a table's parts (the grid has its own). */
        val NO_MARGIN = setOf("html", "body", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption")
        /** No space above or below: the page's own margins (the reader's), a table's inner parts (the grid's). */
        val PAGE = setOf("html", "body", "thead", "tbody", "tfoot", "tr", "td", "th", "caption")
        val MONO = setOf("code", "kbd", "samp", "tt", "pre")
        val CAPTION = Regex("caption|legend")
        val DROP_CAP = Regex("drop-?cap.*|dropcap.*|lettrine.*|initiale?|first-?letter|big-?initial")
    }
}
