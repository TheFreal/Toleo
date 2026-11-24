package de.freal.toleo.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EpubMetadataTest {
    @get:Rule val tmp = TemporaryFolder()

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    private fun opf(metadata: String) = """<?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$metadata</metadata>
          <manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest>
          <spine><itemref idref="c"/></spine>
        </package>"""

    private val fullMetadata = """
        <dc:identifier id="id">urn:uuid:1</dc:identifier>
        <dc:title>Old Title</dc:title>
        <dc:creator>Jane Doe</dc:creator>
        <dc:creator>John Roe</dc:creator>
        <dc:publisher>Old House</dc:publisher>
        <dc:language>en</dc:language>"""

    /** An EPUB with `mimetype` stored first, as the format requires. */
    private fun epub(metadata: String = fullMetadata): File {
        val file = tmp.newFile("book.epub")
        ZipOutputStream(file.outputStream()).use { out ->
            val mime = "application/epub+zip".toByteArray()
            out.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            })
            out.write(mime)
            out.closeEntry()
            for ((name, text) in listOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(metadata),
                "OEBPS/c.xhtml" to "<html><body><p>Hello</p></body></html>",
            )) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test fun readsTitleAuthorsAndPublisher() {
        val meta = EpubMetadata.read(epub())!!
        assertEquals("Old Title", meta.title)
        assertEquals("Jane Doe, John Roe", meta.authors)
        assertEquals("Old House", meta.publisher)
    }

    @Test fun returnsNullForANonEpub() {
        assertNull(EpubMetadata.read(tmp.newFile("junk.epub").apply { writeText("not a zip") }))
    }

    @Test fun writesOnlyChangedFieldsAndKeepsTheRest() {
        val src = epub()
        val original = EpubMetadata.read(src)!!
        val dest = tmp.newFile("edited.epub")
        EpubMetadata.write(src, dest, original, original.copy(title = "New Title"))

        val read = EpubMetadata.read(dest)!!
        assertEquals("New Title", read.title)
        // Both creators survive because the author field wasn't touched.
        assertEquals("Jane Doe, John Roe", read.authors)
        assertEquals("Old House", read.publisher)
        ZipFile(dest).use { zip ->
            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).reader().readText()
            assertTrue("language and identifier kept", "<dc:language>en</dc:language>" in opf && "urn:uuid:1" in opf)
            assertEquals("<html><body><p>Hello</p></body></html>", zip.getInputStream(zip.getEntry("OEBPS/c.xhtml")).reader().readText())
        }
    }

    @Test fun replacesAuthorsWithOneLineAndCanClearThem() {
        val src = epub()
        val original = EpubMetadata.read(src)!!
        val one = tmp.newFile("one.epub")
        EpubMetadata.write(src, one, original, original.copy(authors = "Doe, Jane & Roe, John"))
        assertEquals("Doe, Jane & Roe, John", EpubMetadata.read(one)!!.authors)

        val none = tmp.newFile("none.epub")
        EpubMetadata.write(src, none, original, original.copy(authors = "", publisher = ""))
        val cleared = EpubMetadata.read(none)!!
        assertEquals("", cleared.authors)
        assertEquals("", cleared.publisher)
        assertEquals("Old Title", cleared.title)
    }

    @Test fun addsFieldsTheBookDidNotHave() {
        val src = epub("""<dc:identifier id="id">urn:uuid:1</dc:identifier><dc:title>Bare</dc:title>""")
        val original = EpubMetadata.read(src)!!
        assertEquals("", original.authors)
        val dest = tmp.newFile("added.epub")
        EpubMetadata.write(src, dest, original, original.copy(authors = "New Author", publisher = "New House"))
        val read = EpubMetadata.read(dest)!!
        assertEquals("New Author", read.authors)
        assertEquals("New House", read.publisher)
    }

    @Test fun keepsMimetypeFirstAndStored() {
        val src = epub()
        val original = EpubMetadata.read(src)!!
        val dest = tmp.newFile("order.epub")
        EpubMetadata.write(src, dest, original, original.copy(title = "X"))
        ZipFile(dest).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertNotNull(zip.getEntry("META-INF/container.xml"))
            assertEquals("application/epub+zip", zip.getInputStream(first).reader().readText())
        }
    }
}
