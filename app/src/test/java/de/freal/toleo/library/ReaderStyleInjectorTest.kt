package de.freal.toleo.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderStyleInjectorTest {
    private fun inject(s: String) = String(ReaderStyleInjector.inject(s.toByteArray()))

    @Test
    fun insertsBeforeHeadEnd() {
        assertEquals(
            "<html><head><title>x</title>${ReaderStyleInjector.STYLE}</head><body/></html>",
            inject("<html><head><title>x</title></head><body/></html>"),
        )
    }

    @Test
    fun matchesUpperCaseTag() {
        assertEquals("<HEAD>${ReaderStyleInjector.STYLE}</HEAD>", inject("<HEAD></HEAD>"))
    }

    @Test
    fun leavesDocumentsWithoutHeadAlone() {
        assertEquals("<html><body>hi</body></html>", inject("<html><body>hi</body></html>"))
    }

    @Test
    fun injectsOnlyOnce() {
        val once = ReaderStyleInjector.inject("<head></head>".toByteArray())
        assertEquals(String(once), String(ReaderStyleInjector.inject(once)))
    }

    @Test
    fun keepsNonAsciiBytes() {
        val html = "<head></head><body>Grüße – „x“</body>"
        assertEquals("<head>${ReaderStyleInjector.STYLE}</head><body>Grüße – „x“</body>", inject(html))
    }
}
