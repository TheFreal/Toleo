package de.freal.toleo.library

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** The few EPUB metadata fields that can be edited before an upload. [authors] is one free-text line. */
data class EpubMeta(val title: String, val authors: String, val publisher: String)

/** Reads and rewrites the Dublin Core metadata in an EPUB's package (OPF) file. */
object EpubMetadata {
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val OPF = "http://www.idpf.org/2007/opf"
    private const val CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"

    /** Null if the file isn't a readable EPUB. */
    fun read(file: File): EpubMeta? = runCatching {
        ZipFile(file).use { zip ->
            val opfPath = opfPath(zip)
            val metadata = metadataElement(parse(zip.read(opfPath)))
            EpubMeta(
                title = texts(metadata, "title").firstOrNull().orEmpty(),
                authors = texts(metadata, "creator").joinToString(", "),
                publisher = texts(metadata, "publisher").firstOrNull().orEmpty(),
            )
        }
    }.getOrNull()

    /**
     * Writes a copy of [src] to [dest] with the fields that differ between [original] and [edits]
     * changed. Everything else is copied byte for byte, and the `mimetype` entry stays first and stored.
     */
    fun write(src: File, dest: File, original: EpubMeta, edits: EpubMeta) {
        ZipFile(src).use { zip ->
            val opfPath = opfPath(zip)
            val doc = parse(zip.read(opfPath))
            val metadata = metadataElement(doc)
            if (edits.title != original.title) setSingle(doc, metadata, "title", edits.title.trim())
            if (edits.authors != original.authors) setAuthors(doc, metadata, edits.authors.trim())
            if (edits.publisher != original.publisher) setSingle(doc, metadata, "publisher", edits.publisher.trim())
            val newOpf = serialize(doc)

            ZipOutputStream(dest.outputStream().buffered()).use { out ->
                for (entry in zip.entries().asSequence()) {
                    val copy = ZipEntry(entry.name).apply { time = entry.time }
                    when {
                        entry.name == opfPath -> {
                            out.putNextEntry(copy)
                            out.write(newOpf)
                        }
                        entry.isDirectory -> out.putNextEntry(copy)
                        else -> {
                            if (entry.method == ZipEntry.STORED) {
                                copy.method = ZipEntry.STORED
                                copy.size = entry.size
                                copy.compressedSize = entry.size
                                copy.crc = entry.crc
                            }
                            out.putNextEntry(copy)
                            zip.getInputStream(entry).use { it.copyTo(out) }
                        }
                    }
                    out.closeEntry()
                }
            }
        }
    }

    internal fun ZipFile.read(path: String): ByteArray =
        getInputStream(getEntry(path) ?: error("Missing $path")).use { it.readBytes() }

    internal fun opfPath(zip: ZipFile): String {
        val container = parse(zip.read("META-INF/container.xml"))
        val root = container.getElementsByTagNameNS(CONTAINER, "rootfile").item(0) as? Element
            ?: error("No rootfile in container.xml")
        return root.getAttribute("full-path").ifBlank { error("Empty rootfile path") }
    }

    private fun metadataElement(doc: Document): Element =
        doc.getElementsByTagNameNS(OPF, "metadata").item(0) as? Element ?: error("No metadata in package file")

    private fun texts(metadata: Element, name: String): List<String> {
        val nodes = metadata.getElementsByTagNameNS(DC, name)
        return (0 until nodes.length).map { nodes.item(it).textContent.trim() }.filter { it.isNotEmpty() }
    }

    private fun setSingle(doc: Document, metadata: Element, name: String, value: String) {
        val nodes = metadata.getElementsByTagNameNS(DC, name)
        when {
            value.isEmpty() -> removeAll(metadata, name)
            nodes.length > 0 -> nodes.item(0).textContent = value
            else -> metadata.appendChild(newElement(doc, metadata, name, value))
        }
    }

    /** One `dc:creator` with the text as typed; blank removes the authors. */
    private fun setAuthors(doc: Document, metadata: Element, value: String) {
        removeAll(metadata, "creator")
        if (value.isNotEmpty()) metadata.appendChild(newElement(doc, metadata, "creator", value))
    }

    private fun removeAll(metadata: Element, name: String) {
        val nodes = metadata.getElementsByTagNameNS(DC, name)
        // The list is live, so collect before removing.
        (0 until nodes.length).map { nodes.item(it) }.forEach { it.parentNode.removeChild(it) }
    }

    private fun newElement(doc: Document, metadata: Element, name: String, value: String): Element {
        val prefix = metadata.lookupPrefix(DC) ?: "dc"
        return doc.createElementNS(DC, "$prefix:$name").apply { textContent = value }
    }

    /** Namespace-aware parse that never fetches external DTDs or entities (the file is untrusted). */
    internal fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(ByteArrayInputStream(bytes))
    }

    private fun serialize(doc: Document): ByteArray {
        val factory = TransformerFactory.newInstance().apply {
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }
        val transformer = factory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }
        return ByteArrayOutputStream().also { transformer.transform(DOMSource(doc), StreamResult(it)) }.toByteArray()
    }
}
