package de.freal.toleo.position

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * A Tolino position string: `<href>#point(<path>:<offset>)`, e.g.
 * `OEBPS/chapter_0.html#point(/1/2/1/11/1:114)` (Adobe RMSDK bookmark syntax).
 */
data class TolinoPoint(val href: String, val steps: List<Int>, val offset: Int) {
    override fun toString() = "$href#point(/${steps.joinToString("/")}:$offset)"

    companion object {
        private val REGEX = Regex("""^(.*?)#point\((/[\d/]*?)(?::(\d+))?\)""")

        fun parse(s: String): TolinoPoint? {
            val m = REGEX.find(s.trim()) ?: return null
            val steps = m.groupValues[2].split('/').filter { it.isNotEmpty() }.map { it.toInt() }
            return TolinoPoint(m.groupValues[1], steps, m.groupValues[3].toIntOrNull() ?: 0)
        }

        /** The href part of any position string, with or without a `#point(...)`. */
        fun hrefOf(s: String): String = s.substringBefore('#')
    }
}

/**
 * Flat text model of one XHTML resource used to translate between Tolino point paths,
 * character offsets, progression and text quotes.
 *
 * Path semantics, derived from real positions written by tolino apps (2026-11-14):
 * `…Introduction.xhtml#point(/1/4/12/1:255)` and `…#point(/1/4/2/52/1:130)`. Each step is a
 * 1-based index into the node's DOM `childNodes`, counting elements and *all* text nodes,
 * including whitespace-only ones. In pretty-printed XHTML that puts `body` at 4
 * (html → [ws, head, ws, body]) and every element at an even index, with the final odd step
 * naming a text node. At document level only the root element counts (`/1` = html). The
 * offset is a character offset into the final text node. The older `/1/2/1/11/1:114` sample
 * from cweiske's docs fits too: that file had no whitespace between tags.
 */
class TextIndex(xhtml: String) {
    private val doc: Document = Jsoup.parse(xhtml, "", Parser.xmlParser())

    /** Body text nodes in document order. */
    private val nodes: List<TextNode>
    private val starts: IntArray
    private val nodeIndex: Map<TextNode, Int>
    val text: String

    // Whitespace-free projection for quote matching: selection.toString() and DOM text differ in whitespace.
    private val compact: String
    private val compactToFlat: IntArray

    init {
        val collected = mutableListOf<TextNode>()
        fun walk(n: Node) {
            if (n is Element && n.normalName() in SKIPPED) return
            if (n is TextNode && !n.isBlank) collected += n
            n.childNodes().forEach(::walk)
        }
        walk(doc)
        nodes = collected
        starts = IntArray(nodes.size)
        val sb = StringBuilder()
        nodes.forEachIndexed { i, n -> starts[i] = sb.length; sb.append(n.wholeText) }
        text = sb.toString()
        nodeIndex = nodes.withIndex().associate { it.value to it.index }

        val c = StringBuilder()
        val map = ArrayList<Int>(text.length)
        text.forEachIndexed { i, ch -> if (!ch.isWhitespace()) { c.append(ch); map += i } }
        compact = c.toString()
        compactToFlat = map.toIntArray()
    }

    val length: Int get() = text.length

    fun progressionOf(flat: Int): Double = if (length == 0) 0.0 else flat.coerceIn(0, length).toDouble() / length

    fun flatOf(progression: Double): Int = (progression.coerceIn(0.0, 1.0) * length).toInt()

    /** Resolves a point path to a flat character index, or null if the path doesn't exist. */
    fun resolve(steps: List<Int>, offset: Int): Int? {
        var current: Node = doc
        for (step in steps) {
            val children = significantChildren(current)
            val i = if (ONE_BASED) step - 1 else step
            current = children.getOrNull(i) ?: return null
        }
        return when (current) {
            // A whitespace-only text node isn't part of the flat text: snap to the next real text.
            is TextNode -> nodeIndex[current]?.let { starts[it] + offset.coerceIn(0, current.wholeText.length) }
                ?: firstTextAfter(current)
            else -> {
                // Element: offset counts through its descendant text.
                val inside = nodes.withIndex().filter { (_, n) -> n.hasAncestor(current) }
                if (inside.isEmpty()) firstTextAfter(current) else {
                    val first = starts[inside.first().index]
                    val last = inside.last().let { starts[it.index] + it.value.wholeText.length }
                    (first + offset).coerceAtMost(last)
                }
            }
        }
    }

    /** Builds the point path (steps, offset) for a flat character index. */
    fun pathFor(flat: Int): Pair<List<Int>, Int>? {
        if (nodes.isEmpty()) return null
        val f = flat.coerceIn(0, length)
        var i = starts.binarySearch(f).let { if (it >= 0) it else -it - 2 }.coerceIn(0, nodes.lastIndex)
        // At a node boundary prefer the start of the next node over the end of the previous one.
        if (i < nodes.lastIndex && f == starts[i] + nodes[i].wholeText.length) i++
        val node = nodes[i]
        val offset = (f - starts[i]).coerceIn(0, node.wholeText.length)
        val steps = ArrayDeque<Int>()
        var n: Node = node
        while (true) {
            val parent = n.parentNode() ?: break
            val idx = significantChildren(parent).indexOf(n)
            if (idx < 0) return null
            steps.addFirst(if (ONE_BASED) idx + 1 else idx)
            if (parent is Document) break
            n = parent
        }
        return steps.toList() to offset
    }

    fun slice(from: Int, to: Int): String = text.substring(from.coerceIn(0, length), to.coerceIn(0, length))

    /**
     * Finds the flat range of [highlight], disambiguated by surrounding text and closeness to [nearFlat].
     */
    fun findQuote(highlight: String, before: String?, after: String?, nearFlat: Int?): IntRange? {
        val q = highlight.filterNot { it.isWhitespace() }
        if (q.isEmpty()) return null
        val b = before?.filterNot { it.isWhitespace() }?.takeLast(32).orEmpty()
        val a = after?.filterNot { it.isWhitespace() }?.take(32).orEmpty()
        var best: Int? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var from = 0
        while (true) {
            val at = compact.indexOf(q, from)
            if (at < 0) break
            var score = 0.0
            if (b.isNotEmpty() && compact.regionMatches(at - b.length, b, 0, b.length)) score += 2
            if (a.isNotEmpty() && compact.regionMatches(at + q.length, a, 0, a.length)) score += 2
            if (nearFlat != null && length > 0) score -= kotlin.math.abs(compactToFlat[at] - nearFlat).toDouble() / length
            if (score > bestScore) { bestScore = score; best = at }
            from = at + 1
        }
        val start = best ?: return null
        return compactToFlat[start]..(compactToFlat[start + q.length - 1] + 1)
    }

    private fun firstTextAfter(element: Node): Int? {
        // Next text node in document order that isn't inside the element.
        var n: Node? = element
        while (n != null) {
            var sib = n.nextSibling()
            while (sib != null) {
                val t = firstText(sib)
                if (t != null) return nodeIndex[t]?.let { starts[it] }
                sib = sib.nextSibling()
            }
            n = n.parentNode()
        }
        return length
    }

    private fun firstText(n: Node): TextNode? {
        if (n is TextNode && nodeIndex.containsKey(n)) return n
        n.childNodes().forEach { c -> firstText(c)?.let { return it } }
        return null
    }

    private fun Node.hasAncestor(a: Node): Boolean {
        var p = parentNode()
        while (p != null) { if (p === a) return true; p = p.parentNode() }
        return false
    }

    companion object {
        private const val ONE_BASED = true
        private val SKIPPED = setOf("head", "script", "style")

        /** Children that count as path steps (comments, doctype and XML declarations don't). */
        fun significantChildren(n: Node): List<Node> =
            if (n is Document) n.childNodes().filterIsInstance<Element>()
            else n.childNodes().filter { it is Element || it is TextNode }
    }
}
