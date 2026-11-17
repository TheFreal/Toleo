package de.freal.toleo.library

import de.freal.toleo.db.DrmStatus
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** Detects Adobe ADEPT / Readium LCP protection. Font obfuscation alone is not DRM. */
object DrmInspector {
    fun inspect(file: File): DrmStatus = runCatching {
        if (file.extension.equals("pdf", ignoreCase = true)) return inspectPdf(file)
        ZipFile(file).use { zip ->
            when {
                zip.getEntry("META-INF/license.lcpl") != null -> DrmStatus.LCP
                zip.getEntry("META-INF/rights.xml") != null && zip.getEntry("META-INF/encryption.xml") != null -> DrmStatus.ADOBE
                else -> DrmStatus.NONE
            }
        }
    }.getOrDefault(DrmStatus.UNKNOWN)

    private fun inspectPdf(file: File): DrmStatus {
        // Adobe-protected PDFs name the EBX handler in the encryption dictionary, usually near the end.
        RandomAccessFile(file, "r").use { raf ->
            val n = minOf(raf.length(), 256 * 1024L).toInt()
            val buf = ByteArray(n)
            raf.seek(raf.length() - n)
            raf.readFully(buf)
            val tail = String(buf, Charsets.ISO_8859_1)
            return if ("EBX_HANDLER" in tail || "ADEPT" in tail) DrmStatus.ADOBE else DrmStatus.NONE
        }
    }
}
