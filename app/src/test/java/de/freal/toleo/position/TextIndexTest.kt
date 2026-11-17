package de.freal.toleo.position

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TextIndexTest {
    private val xhtml = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>Chapter</title></head>
          <body>
            <div>
              <h1>One</h1>
              <p>First paragraph with <em>emphasis</em> inside.</p>
              <p>Second paragraph, quite a bit longer than the first one.</p>
            </div>
          </body>
        </html>
    """.trimIndent()

    @Test fun parsesPointStrings() {
        val p = TolinoPoint.parse("OEBPS/caterina-di-montebasso-das-relikt_0.html#point(/1/2/1/11/1:114)")!!
        assertEquals("OEBPS/caterina-di-montebasso-das-relikt_0.html", p.href)
        assertEquals(listOf(1, 2, 1, 11, 1), p.steps)
        assertEquals(114, p.offset)
        assertEquals("OEBPS/caterina-di-montebasso-das-relikt_0.html#point(/1/2/1/11/1:114)", p.toString())
        assertNull(TolinoPoint.parse("OEBPS/x.html"))
    }

    @Test fun headTextIsExcluded() {
        val idx = TextIndex(xhtml)
        assert(!idx.text.contains("Chapter"))
        assert(idx.text.startsWith("One"))
    }

    @Test fun resolvesPathIntoTextNode() {
        val idx = TextIndex(xhtml)
        // Whitespace text nodes count: html(1) > [ws, head, ws, body](4) > [ws, div](2)
        // > [ws, h1, ws, p, ws, p](6) > text(1), offset 7 -> "paragraph, ..."
        val flat = idx.resolve(listOf(1, 4, 2, 6, 1), 7)!!
        assertEquals("paragraph,", idx.slice(flat, flat + 10))
    }

    @Test fun pathRoundTrips() {
        val idx = TextIndex(xhtml)
        for (flat in listOf(0, 5, 20, 30, idx.length - 3)) {
            val (steps, offset) = idx.pathFor(flat)!!
            assertEquals("flat $flat via $steps:$offset", flat, idx.resolve(steps, offset))
        }
    }

    @Test fun pathIntoInlineElement() {
        val idx = TextIndex(xhtml)
        val flat = idx.text.indexOf("emphasis")
        val (steps, offset) = idx.pathFor(flat)!!
        // first p is at 4; inside it: [text, em, text] -> em = 2 -> its text = 1
        assertEquals(listOf(1, 4, 2, 4, 2, 1), steps)
        assertEquals(0, offset)
    }

    /** Same shape as a real tolino position: `…#point(/1/4/12/1:255)` — element steps are even. */
    @Test fun matchesRealTolinoPathShape() {
        val paras = (1..8).joinToString("\n") { "    <p>Paragraph number $it with some text in it.</p>" }
        val doc = "<?xml version=\"1.0\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\">\n  <head><title>t</title></head>\n  <body>\n$paras\n  </body>\n</html>"
        val idx = TextIndex(doc)
        // body children: [ws, p1, ws, p2, …] -> p6 is at index 12
        val flat = idx.resolve(listOf(1, 4, 12, 1), 10)!!
        assertEquals("number 6", idx.slice(flat, flat + 8))
        val (steps, offset) = idx.pathFor(flat)!!
        assertEquals(listOf(1, 4, 12, 1), steps)
        assertEquals(10, offset)
    }

    @Test fun minifiedDocumentStillMatchesOldSample() {
        // cweiske's /1/2/1/11/1 sample comes from a file without whitespace between tags.
        val ps = (1..11).joinToString("") { "<p>p$it text</p>" }
        val idx = TextIndex("<html><head><title>t</title></head><body><div>$ps</div></body></html>")
        val flat = idx.resolve(listOf(1, 2, 1, 11, 1), 0)!!
        assertEquals("p11", idx.slice(flat, flat + 3))
    }

    @Test fun findsQuoteIgnoringWhitespaceAndUsingContext() {
        val idx = TextIndex(xhtml)
        val range = idx.findQuote("paragraph", before = "Second ", after = ", quite", nearFlat = null)
        assertNotNull(range)
        assertEquals(idx.text.indexOf("paragraph, quite"), range!!.first)
        // Selection text spanning nodes with different whitespace still matches.
        val across = idx.findQuote("with emphasis   inside", null, null, null)
        assertEquals("with emphasis inside", idx.slice(across!!.first, across.last))
    }

    @Test fun elementPathFallsBackToItsText() {
        val idx = TextIndex(xhtml)
        // Point at the h1 element itself with offset 1.
        assertEquals(1, idx.resolve(listOf(1, 4, 2, 2), 1))
    }
}
