package com.romflasher.tool.extractor

import android.content.Context
import android.net.Uri
import com.romflasher.tool.util.RomFlasherStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.LZMAInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel

object FastExtractor {

    private enum class ArchiveType {
        SEVEN_ZIP,
        ZIP,
        GZIP_TAR,
        TAR
    }

    private class CountingInputStream(private val wrapped: InputStream) : InputStream() {
        var bytesRead: Long = 0
            private set

        override fun read(): Int {
            val r = wrapped.read()
            if (r != -1) bytesRead++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = wrapped.read(b, off, len)
            if (r != -1) bytesRead += r
            return r
        }

        override fun close() {
            wrapped.close()
        }
    }

    private class BoundedInputStream(
        private val wrapped: InputStream,
        private var maxBytes: Long
    ) : InputStream() {

        override fun read(): Int {
            if (maxBytes <= 0) return -1
            val r = wrapped.read()
            if (r != -1) maxBytes--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (maxBytes <= 0) return -1
            val toRead = minOf(len.toLong(), maxBytes).toInt()
            val r = wrapped.read(b, off, toRead)
            if (r != -1) maxBytes -= r
            return r
        }

        override fun close() {
            // Do not close underlying ZipFile stream
        }
    }

    suspend fun extractArchive(
        context: Context,
        archiveUri: Uri,
        archiveName: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (currentBytes: Long, totalBytes: Long, currentFileName: String, filesDone: Int, totalFiles: Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val cleanName = archiveName.substringBeforeLast(".").ifEmpty { "Extracted_ROM" }
            .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        // Everything the app unpacks lives under Download/RomFlasher/Extracted/.
        val targetDir = File(RomFlasherStorage.extractedRoot(), cleanName)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val pfd = try { context.contentResolver.openFileDescriptor(archiveUri, "r") } catch (e: Exception) { null }
        val compressedFileSize = if (pfd != null && pfd.statSize > 0) pfd.statSize else 100_000_000L

        val archiveType = detectArchiveType(context, archiveUri, archiveName)

        val channel = if (pfd != null) {
            try { FileInputStream(pfd.fileDescriptor).channel } catch (e: Exception) { null }
        } else null

        var lastReportTime = 0L

        fun reportProgressThrottled(current: Long, total: Long, file: String, done: Int, totalCount: Int, force: Boolean = false) {
            // Cooperative cancellation: this helper is hit at least once per
            // buffer chunk / entry, so a cancel request lands within ~1 MB.
            if (isCancelled()) throw CancellationException("Extraction cancelled")
            val now = System.currentTimeMillis()
            if (force || now - lastReportTime > 80) {
                lastReportTime = now
                onProgress(current, total, file, done, totalCount)
            }
        }

        try {
            when (archiveType) {
                ArchiveType.SEVEN_ZIP -> {
                    if (channel != null) {
                        SevenZFile.builder().setSeekableByteChannel(channel).setMaxMemoryLimitKb(1024 * 1024).get().use { sevenZ ->
                            extractSevenZipEntries(sevenZ, targetDir, compressedFileSize, ::reportProgressThrottled)
                        }
                    } else {
                        val tempFile = File(context.externalCacheDir ?: context.cacheDir, "temp_7z_archive.tmp")
                        try {
                            copyStreamToFile(context, archiveUri, tempFile)
                            SevenZFile.builder().setFile(tempFile).setMaxMemoryLimitKb(1024 * 1024).get().use { sevenZ ->
                                extractSevenZipEntries(sevenZ, targetDir, compressedFileSize, ::reportProgressThrottled)
                            }
                        } finally {
                            if (tempFile.exists()) tempFile.delete()
                        }
                    }
                }

                ArchiveType.ZIP -> {
                    if (channel != null) {
                        ZipFile.builder().setSeekableByteChannel(channel).get().use { zipFile ->
                            extractZipFileEntries(channel, zipFile, targetDir, compressedFileSize, ::reportProgressThrottled)
                        }
                    } else {
                        val tempFile = File(context.externalCacheDir ?: context.cacheDir, "temp_zip_archive.tmp")
                        try {
                            copyStreamToFile(context, archiveUri, tempFile)
                            ZipFile.builder().setFile(tempFile).get().use { zipFile ->
                                extractZipFileEntries(null, zipFile, targetDir, compressedFileSize, ::reportProgressThrottled)
                            }
                        } finally {
                            if (tempFile.exists()) tempFile.delete()
                        }
                    }
                }

                ArchiveType.GZIP_TAR -> {
                    val rawIs = context.contentResolver.openInputStream(archiveUri)?.buffered(1024 * 1024)
                        ?: throw IllegalStateException("Could not open stream for TGZ")
                    val countingIs = CountingInputStream(rawIs)
                    countingIs.use { cis ->
                        GzipCompressorInputStream(cis).use { gzis ->
                            TarArchiveInputStream(gzis).use { tis ->
                                var entry = tis.nextEntry
                                var filesDone = 0
                                val buffer = ByteArray(1024 * 1024)
                                while (entry != null) {
                                    val fileName = entry.name.removePrefix("./")
                                    if (fileName.isNotEmpty() && !fileName.contains("__MACOSX") && !fileName.startsWith(".")) {
                                        safeResolve(targetDir, fileName)?.let { outputFile ->
                                            if (entry.isDirectory) {
                                                outputFile.mkdirs()
                                            } else {
                                                outputFile.parentFile?.mkdirs()
                                                FileOutputStream(outputFile).buffered(1024 * 1024).use { fos ->
                                                    var read: Int
                                                    while (tis.read(buffer).also { read = it } != -1) {
                                                        fos.write(buffer, 0, read)
                                                        reportProgressThrottled(cis.bytesRead, compressedFileSize, fileName, filesDone, 0)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    filesDone++
                                    reportProgressThrottled(cis.bytesRead, compressedFileSize, fileName, filesDone, 0)
                                    entry = tis.nextEntry
                                }
                            }
                        }
                    }
                }

                ArchiveType.TAR -> {
                    val rawIs = context.contentResolver.openInputStream(archiveUri)?.buffered(1024 * 1024)
                        ?: throw IllegalStateException("Could not open stream for TAR")
                    val countingIs = CountingInputStream(rawIs)
                    countingIs.use { cis ->
                        TarArchiveInputStream(cis).use { tis ->
                            var entry = tis.nextEntry
                            var filesDone = 0
                            val buffer = ByteArray(1024 * 1024)
                            while (entry != null) {
                                val fileName = entry.name.removePrefix("./")
                                if (fileName.isNotEmpty() && !fileName.contains("__MACOSX") && !fileName.startsWith(".")) {
                                    safeResolve(targetDir, fileName)?.let { outputFile ->
                                        if (entry.isDirectory) {
                                            outputFile.mkdirs()
                                        } else {
                                            outputFile.parentFile?.mkdirs()
                                            FileOutputStream(outputFile).buffered(1024 * 1024).use { fos ->
                                                var read: Int
                                                while (tis.read(buffer).also { read = it } != -1) {
                                                    fos.write(buffer, 0, read)
                                                    reportProgressThrottled(cis.bytesRead, compressedFileSize, fileName, filesDone, 0)
                                                }
                                            }
                                        }
                                    }
                                }
                                filesDone++
                                reportProgressThrottled(cis.bytesRead, compressedFileSize, fileName, filesDone, 0)
                                entry = tis.nextEntry
                            }
                        }
                    }
                }
            }

            reportProgressThrottled(compressedFileSize, compressedFileSize, "Extraction Complete", 0, 0, force = true)
        } finally {
            try { pfd?.close() } catch (e: Exception) {}
        }

        return@withContext targetDir
    }

    /**
     * Resolves an archive entry name under [targetDir], rejecting path-traversal (`../`) so a
     * malicious archive cannot write outside the extraction directory (Zip-Slip). Returns null
     * when the entry is unsafe.
     */
    private fun safeResolve(targetDir: File, entryName: String): File? {
        return try {
            val root = targetDir.canonicalFile
            val out = File(root, entryName).canonicalFile
            if (out.path == root.path || out.path.startsWith(root.path + File.separator)) out else null
        } catch (_: Exception) {
            null
        }
    }

    private fun copyStreamToFile(context: Context, uri: Uri, destFile: File) {
        if (destFile.exists()) destFile.delete()
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(2 * 1024 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalStateException("Could not open stream to copy URI")
    }

    private fun extractSevenZipEntries(
        sevenZ: SevenZFile,
        targetDir: File,
        compressedFileSize: Long,
        reportProgressThrottled: (Long, Long, String, Int, Int, Boolean) -> Unit
    ) {
        val entriesList = sevenZ.entries.toList()
        val totalFiles = entriesList.size
        val totalUncompressedBytes = entriesList.filter { !it.isDirectory }.sumOf { it.size }
        val effectiveTotalBytes = if (totalUncompressedBytes > 0) totalUncompressedBytes else compressedFileSize

        var filesDone = 0
        var currentBytes = 0L

        // Single shared 2MB buffer to eliminate GC pressure during heavy LZMA2 decompression
        val buffer = ByteArray(2 * 1024 * 1024)

        var entry = sevenZ.nextEntry
        while (entry != null) {
            val fileName = entry.name?.removePrefix("./")
            if (!fileName.isNullOrEmpty() && !entry.isDirectory && !fileName.contains("__MACOSX") && !fileName.startsWith(".")) {
                safeResolve(targetDir, fileName)?.let { outputFile ->
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { fos ->
                        var read: Int
                        while (sevenZ.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                            currentBytes += read
                            reportProgressThrottled(currentBytes, effectiveTotalBytes, fileName, filesDone, totalFiles, false)
                        }
                    }
                }
            } else if (fileName != null && entry.isDirectory) {
                safeResolve(targetDir, fileName)?.mkdirs()
            }
            filesDone++
            reportProgressThrottled(currentBytes, effectiveTotalBytes, fileName ?: "", filesDone, totalFiles, false)
            entry = sevenZ.nextEntry
        }
    }

    private fun extractZipFileEntries(
        channel: SeekableByteChannel?,
        zipFile: ZipFile,
        targetDir: File,
        compressedFileSize: Long,
        reportProgressThrottled: (Long, Long, String, Int, Int, Boolean) -> Unit
    ) {
        val entriesList = zipFile.entries.toList()
        val totalFiles = entriesList.size
        val totalUncompressedBytes = entriesList.filter { !it.isDirectory }.sumOf { if (it.size >= 0) it.size else 0L }
        val effectiveTotalBytes = if (totalUncompressedBytes > 0) totalUncompressedBytes else compressedFileSize

        var filesDone = 0
        var currentBytes = 0L

        // Single shared 2MB buffer to eliminate GC pressure
        val buffer = ByteArray(2 * 1024 * 1024)

        for (entry in entriesList) {
            val fileName = entry.name?.removePrefix("./") ?: continue
            if (fileName.isNotEmpty() && !fileName.contains("__MACOSX") && !fileName.startsWith(".")) {
                val outputFile = safeResolve(targetDir, fileName) ?: continue
                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    try {
                        getDecompressedZipStream(channel, zipFile, entry).use { zis ->
                            FileOutputStream(outputFile).use { fos ->
                                var read = 0
                                var entryBytesWritten = 0L
                                val expectedSize = if (entry.size >= 0) entry.size else Long.MAX_VALUE

                                try {
                                    while (entryBytesWritten < expectedSize && zis.read(buffer).also { read = it } != -1) {
                                        val bytesToWrite = if (entryBytesWritten + read > expectedSize) {
                                            (expectedSize - entryBytesWritten).toInt()
                                        } else read

                                        if (bytesToWrite > 0) {
                                            fos.write(buffer, 0, bytesToWrite)
                                            entryBytesWritten += bytesToWrite
                                            currentBytes += bytesToWrite
                                            reportProgressThrottled(currentBytes, effectiveTotalBytes, fileName, filesDone, totalFiles, false)
                                        }
                                        if (entryBytesWritten >= expectedSize) break
                                    }
                                } catch (e: Exception) {
                                    if (e !is java.util.concurrent.CancellationException && entry.size > 0 && entryBytesWritten >= entry.size) {
                                        // All uncompressed bytes written successfully!
                                    } else {
                                        throw e
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        try { outputFile.delete() } catch (_: Exception) {}
                        throw e
                    }
                }
            }
            filesDone++
            reportProgressThrottled(currentBytes, effectiveTotalBytes, fileName, filesDone, totalFiles, false)
        }
    }

    private fun getDecompressedZipStream(
        channel: SeekableByteChannel?,
        zipFile: ZipFile,
        entry: ZipArchiveEntry
    ): InputStream {
        return try {
            zipFile.getInputStream(entry)
        } catch (e: Exception) {
            if (entry.method == 14) {
                if (channel != null) {
                    val localHeaderOffset = entry.localHeaderOffset
                    if (localHeaderOffset >= 0) {
                        try {
                            channel.position(localHeaderOffset)
                            val headerBuf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                            var readTotal = 0
                            while (readTotal < 30) {
                                val r = channel.read(headerBuf)
                                if (r <= 0) break
                                readTotal += r
                            }

                            if (readTotal >= 30) {
                                headerBuf.flip()
                                val sig = headerBuf.int
                                if (sig == 0x04034b50) { // PK\x03\x04 Local Header Magic
                                    headerBuf.position(26)
                                    val fileNameLen = headerBuf.short.toInt() and 0xFFFF
                                    val extraLen = headerBuf.short.toInt() and 0xFFFF

                                    val payloadStart = localHeaderOffset + 30 + fileNameLen + extraLen
                                    channel.position(payloadStart)

                                    val lzmaHeaderBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                                    var lzmaHeaderRead = 0
                                    while (lzmaHeaderRead < 16) {
                                        val r = channel.read(lzmaHeaderBuf)
                                        if (r <= 0) break
                                        lzmaHeaderRead += r
                                    }

                                    if (lzmaHeaderRead >= 5) {
                                        lzmaHeaderBuf.flip()
                                        val b0 = lzmaHeaderBuf.get()
                                        lzmaHeaderBuf.get() // Skip minor version byte
                                        val b2 = lzmaHeaderBuf.get()
                                        val b3 = lzmaHeaderBuf.get()

                                        var propsByte: Byte = 0x5D
                                        var dictSize = 8 * 1024 * 1024
                                        var headerSize = 9

                                        if (b0 == 0x5D.toByte()) {
                                            propsByte = b0
                                            val d = lzmaHeaderBuf.int
                                            if (d in 4096..(512 * 1024 * 1024)) {
                                                dictSize = d
                                            }
                                            headerSize = 5
                                        } else if (b2 == 0x05.toByte() && b3 == 0x00.toByte()) {
                                            propsByte = lzmaHeaderBuf.get()
                                            val d = lzmaHeaderBuf.int
                                            if (d in 4096..(512 * 1024 * 1024)) {
                                                dictSize = d
                                            }
                                            headerSize = 9
                                        }

                                        val dataStart = payloadStart + headerSize
                                        channel.position(dataStart)

                                        val rawChannelStream = Channels.newInputStream(channel)
                                        val compressedDataLen = if (entry.compressedSize > headerSize) {
                                            entry.compressedSize - headerSize
                                        } else Long.MAX_VALUE

                                        val boundedStream = BoundedInputStream(rawChannelStream, compressedDataLen)
                                        val uncompressedSize = if (entry.size >= 0) entry.size else -1L

                                        return LZMAInputStream(boundedStream, uncompressedSize, propsByte, dictSize)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Fall through to raw stream fallback below
                        }
                    }
                }

                // Fallback via getRawInputStream
                val rawStream = zipFile.getRawInputStream(entry)
                    ?: throw e

                val compressedSize = if (entry.compressedSize > 0) entry.compressedSize else Long.MAX_VALUE
                val boundedRaw = BoundedInputStream(rawStream, compressedSize)

                try {
                    val versionHeader = ByteArray(2)
                    if (boundedRaw.read(versionHeader) != 2) throw e

                    val sizeHeader = ByteArray(2)
                    if (boundedRaw.read(sizeHeader) != 2) throw e
                    val propsSize = (sizeHeader[0].toInt() and 0xFF) or ((sizeHeader[1].toInt() and 0xFF) shl 8)

                    if (propsSize in 1..64) {
                        val props = ByteArray(propsSize)
                        if (boundedRaw.read(props) != propsSize) throw e

                        val propsByte = props[0]
                        var dictSize = 0
                        val maxProps = minOf(5, propsSize)
                        for (i in 1 until maxProps) {
                            dictSize = dictSize or ((props[i].toInt() and 0xFF) shl ((i - 1) * 8))
                        }
                        if (dictSize <= 0) dictSize = 8 * 1024 * 1024

                        val uncompressedSize = if (entry.size >= 0) entry.size else -1L
                        val remainingPayloadSize = if (entry.compressedSize > 0) (compressedSize - 4 - propsSize) else Long.MAX_VALUE
                        val payloadBounded = BoundedInputStream(boundedRaw, remainingPayloadSize)

                        LZMAInputStream(payloadBounded, uncompressedSize, propsByte, dictSize)
                    } else {
                        LZMAInputStream(boundedRaw)
                    }
                } catch (_: Exception) {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    private fun detectArchiveType(context: Context, uri: Uri, nameHint: String): ArchiveType {
        val header = ByteArray(16)
        val bytesRead = try {
            context.contentResolver.openInputStream(uri)?.use { it.read(header) } ?: -1
        } catch (e: Exception) {
            -1
        }

        if (bytesRead >= 2) {
            // 7z Header Signature: 37 7A BC AF 27 1C
            if (bytesRead >= 6 &&
                header[0] == 0x37.toByte() && header[1] == 0x7A.toByte() &&
                header[2] == 0xBC.toByte() && header[3] == 0xAF.toByte() &&
                header[4] == 0x27.toByte() && header[5] == 0x1C.toByte()) {
                return ArchiveType.SEVEN_ZIP
            }

            // Zip Header Signature: 50 4B 03 04 ("PK\x03\x04") or PK\x05\x06 or PK\x07\x08
            if (bytesRead >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
                return ArchiveType.ZIP
            }

            // Gzip Header Signature: 1F 8B
            if (header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) {
                return ArchiveType.GZIP_TAR
            }
        }

        val lower = nameHint.lowercase()
        return when {
            lower.endsWith(".7z") -> ArchiveType.SEVEN_ZIP
            lower.endsWith(".tgz") || lower.endsWith(".tar.gz") -> ArchiveType.GZIP_TAR
            lower.endsWith(".tar") -> ArchiveType.TAR
            else -> ArchiveType.ZIP
        }
    }

    fun findBatScripts(extractedDir: File): List<File> {
        if (!extractedDir.exists() || !extractedDir.isDirectory) return emptyList()

        // 1. Instant check: Check immediate directory first (depth 1 - 0ms lag)
        val immediate = extractedDir.listFiles { f ->
            f.isFile && isFlashScript(f)
        }?.toList().orEmpty()

        if (immediate.isNotEmpty()) {
            return sortScripts(immediate)
        }

        // 2. Check shallow depth 2 subfolders (skip heavy images/ folder and system junk)
        val scripts = mutableListOf<File>()
        val subDirs = extractedDir.listFiles { f ->
            f.isDirectory &&
                    !f.name.equals("images", ignoreCase = true) &&
                    !f.name.equals("__MACOSX", ignoreCase = true) &&
                    !f.name.equals("META-INF", ignoreCase = true) &&
                    !f.name.startsWith(".")
        } ?: emptyArray()

        for (dir in subDirs) {
            dir.listFiles { f ->
                f.isFile && isFlashScript(f)
            }?.let { scripts.addAll(it) }
        }

        return sortScripts(scripts)
    }

    private fun isFlashScript(file: File): Boolean {
        val ext = file.extension
        return ext.equals("bat", ignoreCase = true) ||
               ext.equals("cmd", ignoreCase = true) ||
               ext.equals("sh", ignoreCase = true)
    }

    private fun sortScripts(list: List<File>): List<File> {
        return list.sortedWith { f1, f2 ->
            val name1 = f1.name.lowercase()
            val name2 = f2.name.lowercase()
            when {
                name1.startsWith("flash_all") && !name2.startsWith("flash_all") -> -1
                !name1.startsWith("flash_all") && name2.startsWith("flash_all") -> 1
                else -> name1.compareTo(name2)
            }
        }
    }
}
