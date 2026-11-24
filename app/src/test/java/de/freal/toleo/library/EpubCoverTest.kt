package de.freal.toleo.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EpubCoverTest {
    @get:Rule val tmp = TemporaryFolder()

    /** An EPUB whose package file sits at [opfPath], with [metadata] and [manifest] spliced in and extra [files]. */
    private fun epub(
        opfPath: String = "OEBPS/content.opf",
        metadata: String = "",
        manifest: String = "",
        guide: String = "",
        files: Map<String, String> = emptyMap(),
    ): File {
        val file = tmp.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                   <rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            put(
                opfPath,
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                   <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title>$metadata</metadata>
                   <manifest>$manifest</manifest><spine/>${if (guide.isEmpty()) "" else "<guide>$guide</guide>"}</package>""",
            )
            files.forEach { (name, text) -> put(name, text) }
        }
        return file
    }

    private fun cover(file: File) = ZipFile(file).use { EpubCover.coverEntry(it) }

    @Test fun epub3CoverImageProperty() {
        val f = epub(
            manifest = """<item id="c" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>""",
            files = mapOf("OEBPS/cover.jpg" to "jpg"),
        )
        assertEquals("OEBPS/cover.jpg", cover(f))
    }

    @Test fun epub2MetaResolvedAgainstThePackageFolder() {
        // The layout of the uploads the tolino cloud showed without a cover.
        val f = epub(
            metadata = """<meta name="cover" content="cover"/>""",
            manifest = """<item href="Images/cover.jpeg" id="cover" media-type="image/jpeg"/>""",
            files = mapOf("OEBPS/Images/cover.jpeg" to "jpg"),
        )
        assertEquals("OEBPS/Images/cover.jpeg", cover(f))
    }

    @Test fun packageFileAtTheRoot() {
        val f = epub(
            opfPath = "content.opf",
            metadata = """<meta name="cover" content="cover"/>""",
            manifest = """<item href="cover.jpeg" id="cover" media-type="image/jpeg"/>""",
            files = mapOf("cover.jpeg" to "jpg"),
        )
        assertEquals("cover.jpeg", cover(f))
    }

    @Test fun metaNamingAPageUsesTheImageOnIt() {
        val f = epub(
            metadata = """<meta name="cover" content="cpage"/>""",
            manifest = """<item id="cpage" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>""",
            files = mapOf(
                "OEBPS/Text/cover.xhtml" to """<html><body>&nbsp;<img alt="" src="../Images/Front%20Cover.png"/></body></html>""",
                "OEBPS/Images/Front Cover.png" to "png",
            ),
        )
        assertEquals("OEBPS/Images/Front Cover.png", cover(f))
    }

    @Test fun guideCoverPageWithSvgImage() {
        val f = epub(
            guide = """<reference type="cover" title="Cover" href="titlepage.xhtml"/>""",
            files = mapOf(
                "OEBPS/titlepage.xhtml" to """<svg><image width="600" height="800" xlink:href="img/front.jpg"/></svg>""",
                "OEBPS/img/front.jpg" to "jpg",
            ),
        )
        assertEquals("OEBPS/img/front.jpg", cover(f))
    }

    @Test fun fallsBackToAnImageNamedCover() {
        val f = epub(
            manifest = """<item id="i1" href="images/map.jpg" media-type="image/jpeg"/><item id="i2" href="images/Cover_9781250186935.jpg" media-type="image/jpeg"/>""",
            files = mapOf("OEBPS/images/map.jpg" to "jpg", "OEBPS/images/Cover_9781250186935.jpg" to "jpg"),
        )
        assertEquals("OEBPS/images/Cover_9781250186935.jpg", cover(f))
    }

    @Test fun nullWithoutACoverOrWhenTheDeclaredFileIsMissing() {
        assertNull(cover(epub(manifest = """<item id="i1" href="images/map.jpg" media-type="image/jpeg"/>""")))
        assertNull(cover(epub(manifest = """<item id="c" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>""")))
        assertNull(EpubCover.extract(tmp.newFile().apply { writeText("not a zip") }))
    }

    @Test fun resolvesRelativePaths() {
        assertEquals("OEBPS/a/b.jpg", EpubCover.resolve("OEBPS", "a/b.jpg"))
        assertEquals("Images/b.jpg", EpubCover.resolve("OEBPS/Text", "../../Images/b.jpg"))
        assertEquals("b.jpg", EpubCover.resolve("", "./b.jpg#frag"))
        assertNull(EpubCover.resolve("", "../escape.jpg"))
        assertNull(EpubCover.resolve("OEBPS", "https://example.com/c.jpg"))
    }
}
