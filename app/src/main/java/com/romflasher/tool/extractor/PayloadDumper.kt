package com.romflasher.tool.extractor

import android.content.Context
import android.net.Uri
import com.rajmani7584.payloaddumper.models.PayloadDumper
import com.rajmani7584.payloaddumper.models.RustCallback
import com.romflasher.tool.viewmodel.PayloadPartitionInfo
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.apache.commons.compress.archivers.zip.ZipFile

object PayloadDumper {

    private const val CRAU_MAGIC = 0x43724155 // "CrAU"

    data class DumpProgress(
        val partitionName: String,
        val partitionProgress: Float,
        val overallProgress: Float,
        val partitionsDone: Int,
        val totalPartitions: Int
    )

    data class Extent(
        val startBlock: Long,
        val numBlocks: Long
    )

    data class InstallOperation(
        // AOSP update_metadata.proto InstallOperation.Type:
        // 0=REPLACE, 1=REPLACE_BZ, 6=ZERO, 7=DISCARD, 8=REPLACE_XZ.
        // (2=MOVE / 3=BSDIFF / 4=SOURCE_COPY / 5=SOURCE_BSDIFF need source-block data.)
        val type: Int,
        val dataOffset: Long,
        val dataLength: Long,
        val dstExtents: List<Extent>
    )

    data class PartitionUpdate(
        val name: String,
        val size: Long,
        val sha256: ByteArray? = null,
        val operations: List<InstallOperation>
    )

    internal fun calculateExpectedPartitionSize(name: String): Long {
        val lower = name.lowercase().trim()
        return when {
            lower.contains("vbmeta") -> 8 * 1024L // 8 KB
            lower == "dtbo" -> 16 * 1024 * 1024L // 16 MB
            lower.contains("boot") -> 64 * 1024 * 1024L // 64 MB
            lower == "recovery" -> 100 * 1024 * 1024L // 100 MB
            lower == "super" -> 4096L * 1024 * 1024L // 4 GB
            lower == "system" || lower == "system_a" || lower == "system_b" -> 2560L * 1024 * 1024L // 2.5 GB
            lower == "vendor" || lower == "vendor_a" || lower == "vendor_b" -> 1024L * 1024 * 1024L // 1 GB
            lower == "product" || lower == "product_a" || lower == "product_b" -> 1536L * 1024 * 1024L // 1.5 GB
            lower == "odm" || lower == "odm_a" || lower == "odm_b" -> 512L * 1024 * 1024L // 512 MB
            lower == "cust" -> 1024L * 1024 * 1024L // 1 GB
            lower == "modem" || lower == "radio" -> 128L * 1024 * 1024L // 128 MB
            else -> 64 * 1024 * 1024L
        }
    }

    suspend fun inspectPayloadPartitions(
        inputFile: File,
        outputBaseDir: File,
        onLog: (String) -> Unit = {}
    ): List<com.romflasher.tool.viewmodel.PayloadPartitionInfo> {
        var payloadBinFile: File = inputFile

        if (inputFile.extension.equals("zip", ignoreCase = true)) {
            // Fast inspection: read manifest stream directly from the zip without huge 4GB-8GB disk extraction!
            val directManifest = tryReadManifestFromZip(inputFile)
            if (directManifest != null) {
                val partitions = parseProtobufManifest(directManifest)
                if (partitions.isNotEmpty()) {
                    return partitions.map { part ->
                        val totalBlocks = part.operations.flatMap { it.dstExtents }.sumOf { it.numBlocks }
                        val extentSize = totalBlocks * 4096L
                        val realSize = if (part.size > 0) part.size else if (extentSize > 0) extentSize else calculateExpectedPartitionSize(part.name)

                        com.romflasher.tool.viewmodel.PayloadPartitionInfo(
                            name = "${part.name}.img",
                            sizeBytes = realSize,
                            formattedSize = formatBytes(realSize)
                        )
                    }
                }
            }
            // Fallback to extraction if direct stream read was not possible
            val extractedPayload = findOrExtractPayloadBin(inputFile, outputBaseDir, onLog)
            if (extractedPayload != null && extractedPayload.exists()) {
                payloadBinFile = extractedPayload
            } else {
                throw IOException("No 'payload.bin' found inside '${inputFile.name}'. Ensure this is an AOSP Payload ROM zip.")
            }
        }

        if (!payloadBinFile.exists() || payloadBinFile.length() < 24) {
            throw IOException("Invalid or corrupted payload.bin file.")
        }

        // 1. Parse the partition manifest with the pure-Kotlin protobuf parser.
        // NOTE: getPartitionList() from libpayload-dumper-rust is intentionally NOT
        // used during inspection: a Rust panic inside it (e.g. unusual/new payload
        // variants) aborts the process via SIGABRT, which cannot be caught from
        // Kotlin and crashes the app during the "Inspecting payload.bin manifest"
        // loading screen. The native library is only invoked for the heavy
        // extraction below, where failures are caught and fall back gracefully.

        // 2. Parse Protobuf Manifest
        val raf = RandomAccessFile(payloadBinFile, "r")
        try {
            val magicBuf = ByteArray(4)
            raf.readFully(magicBuf)
            val magic = ByteBuffer.wrap(magicBuf).order(ByteOrder.BIG_ENDIAN).int
            if (magic != CRAU_MAGIC) {
                throw IOException("Invalid payload.bin header magic. Expected 'CrAU'.")
            }

            val version = raf.readLong()
            val manifestSize = raf.readLong()

            var metadataSigSize = 0
            if (version >= 2) {
                metadataSigSize = raf.readInt()
            }

            val manifestBytes = ByteArray(minOf(manifestSize, 15 * 1024 * 1024L).toInt())
            raf.readFully(manifestBytes)

            val partitions = parseProtobufManifest(manifestBytes)
            return partitions.map { part ->
                val totalBlocks = part.operations.flatMap { it.dstExtents }.sumOf { it.numBlocks }
                val extentSize = totalBlocks * 4096L
                val realSize = if (part.size > 0) part.size else if (extentSize > 0) extentSize else calculateExpectedPartitionSize(part.name)

                PayloadPartitionInfo(
                    name = "${part.name}.img",
                    sizeBytes = realSize,
                    formattedSize = formatBytes(realSize)
                )
            }
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    /** Writes up to [limit] bytes from [src] (or all when [limit] < 0) into the dst extents in order. */
    private fun writeStreamToExtents(
        outChannel: java.nio.channels.FileChannel,
        src: java.io.InputStream,
        extents: List<Extent>,
        buf: ByteArray,
        limit: Long
    ) {
        var remaining = if (limit < 0) Long.MAX_VALUE else limit
        for (ext in extents) {
            if (remaining <= 0) break
            var extRemaining = ext.numBlocks * 4096L
            outChannel.position(ext.startBlock * 4096L)
            while (extRemaining > 0 && remaining > 0) {
                val want = minOf(buf.size.toLong(), extRemaining, remaining).toInt()
                val read = src.read(buf, 0, want)
                if (read <= 0) return
                outChannel.write(ByteBuffer.wrap(buf, 0, read))
                extRemaining -= read
                remaining -= read
            }
        }
    }

    internal fun formatBytes(bytes: Long): String {
        return if (bytes >= 1024 * 1024 * 1024L) {
            "${String.format("%.2f", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))} GB"
        } else if (bytes >= 1024 * 1024L) {
            "${String.format("%.2f", bytes.toDouble() / (1024.0 * 1024.0))} MB"
        } else if (bytes >= 1024L) {
            "${String.format("%.2f", bytes.toDouble() / 1024.0)} KB"
        } else {
            "$bytes Bytes"
        }
    }

    /**
     * Parses the native getPartitionList() result:
     * "data:<version>|<patch>:<name>|<sizeBytes>|<sha256>,<name>|<sizeBytes>|<sha256>,...:<incremental>"
     *
     * @return list of (partitionName, sizeBytes). Empty when the text is an error
     *         or does not follow the expected native format.
     */
    private fun parseNativePartitionList(text: String): List<Pair<String, Long>> {
        if (text.isBlank() || text.startsWith("Error")) return emptyList()
        if (!text.startsWith("data:")) return emptyList()

        val payloadParts = text.removePrefix("data:").split(":")
        if (payloadParts.size < 2) return emptyList()

        return payloadParts[1].split(",").mapNotNull { row ->
            if (row.isBlank()) return@mapNotNull null
            val columns = row.split("|")
            if (columns.size >= 2 && columns[0].isNotBlank()) {
                columns[0] to (columns[1].toLongOrNull() ?: 0L)
            } else null
        }
    }

    suspend fun extractPayload(
        context: Context,
        inputFile: File,
        outputBaseDir: File,
        targetPartitions: Set<String> = emptySet(),
        onLog: (String) -> Unit,
        onProgress: (DumpProgress) -> Unit
    ): File {
        var payloadBinFile: File = inputFile

        if (inputFile.extension.equals("zip", ignoreCase = true)) {
            val extractedPayload = findOrExtractPayloadBin(inputFile, outputBaseDir, onLog)
            if (extractedPayload != null && extractedPayload.exists()) {
                payloadBinFile = extractedPayload
            } else {
                throw IOException("No 'payload.bin' found inside '${inputFile.name}'. Ensure this is an AOSP Payload ROM zip.")
            }
        }

        if (!payloadBinFile.exists() || payloadBinFile.length() < 24) {
            throw IOException("Invalid or corrupted payload.bin file.")
        }

        val outputFolder = File(outputBaseDir, "Payload_Extracted_${inputFile.nameWithoutExtension}")
        if (!outputFolder.exists()) {
            outputFolder.mkdirs()
        }

        // 1. Try Rust Native JNI Engine (`libpayload-dumper-rust.so`)
        var remainingTargets = targetPartitions
        try {
            val rustDumper = PayloadDumper()
            val listText = rustDumper.getPartitionList(payloadBinFile.absolutePath)
            val rustPartitions = parseNativePartitionList(listText)
            if (rustPartitions.isNotEmpty()) {
                val totalToExtract = if (targetPartitions.isNotEmpty()) {
                    rustPartitions.filter { (name, _) ->
                        val clean = if (name.endsWith(".img", ignoreCase = true)) name else "$name.img"
                        targetPartitions.contains(clean.lowercase()) || targetPartitions.contains(name.lowercase())
                    }
                } else {
                    rustPartitions.toList()
                }

                var doneCount = 0
                val totalCount = totalToExtract.size
                val failedPartitions = mutableListOf<String>()

                for ((name, sizeBytes) in totalToExtract) {
                    doneCount++
                    val rawName = name.removeSuffix(".img").removeSuffix(".IMG")
                    val cleanFileName = "$rawName.img"
                    val outFile = File(outputFolder, cleanFileName)
                    if (outFile.exists() && outFile.length() == 0L) {
                        try { outFile.delete() } catch (_: Exception) {}
                    }
                    onLog("Extracting [${doneCount}/$totalCount]: $cleanFileName (${formatBytes(sizeBytes)})...")

                    onProgress(
                        DumpProgress(
                            partitionName = cleanFileName,
                            partitionProgress = 0f,
                            overallProgress = if (totalCount > 0) (doneCount - 1).toFloat() / totalCount else 1f,
                            partitionsDone = doneCount - 1,
                            totalPartitions = totalCount
                        )
                    )

                    // Native extractPartition(path, partition, OUTPUT_FILE_PATH, callback)
                    // writes the final .img file directly at outputPath.
                    val callback = object : RustCallback {
                        override fun onProgressCallback(progress: Long) {
                            // Native reports a per-partition percentage 0..100.
                            val pct = (progress / 100f).coerceIn(0f, 1f)
                            onProgress(
                                DumpProgress(
                                    partitionName = cleanFileName,
                                    partitionProgress = pct,
                                    overallProgress = if (totalCount > 0) ((doneCount - 1) + pct) / totalCount else 1f,
                                    partitionsDone = doneCount - 1,
                                    totalPartitions = totalCount
                                )
                            )
                        }

                        override fun onVerifyCallback(status: Int) {
                            when (status) {
                                0 -> onLog("[VERIFY] Verifying SHA-256 of $cleanFileName...")
                                1 -> onLog("[VERIFY] $cleanFileName hash verified OK.")
                                else -> onLog("[VERIFY] $cleanFileName hash MISMATCH!")
                            }
                        }
                    }

                    val result = rustDumper.extractPartition(
                        payloadBinFile.absolutePath,
                        rawName,
                        outFile.absolutePath,
                        callback
                    )

                    if (outFile.exists() && outFile.length() > 0L) {
                        onLog("[SUCCESS] Extracted $cleanFileName (${formatBytes(outFile.length())})")
                    } else {
                        if (outFile.exists()) {
                            try { outFile.delete() } catch (_: Exception) {}
                        }
                        onLog("[RUST NOTE] Failed extracting $cleanFileName via native engine ($result) — queuing fallback...")
                        failedPartitions.add(rawName.lowercase())
                    }

                    onProgress(
                        DumpProgress(
                            partitionName = cleanFileName,
                            partitionProgress = 1f,
                            overallProgress = if (totalCount > 0) doneCount.toFloat() / totalCount else 1f,
                            partitionsDone = doneCount,
                            totalPartitions = totalCount
                        )
                    )
                }

                if (totalCount > 0 && failedPartitions.isEmpty()) {
                    return outputFolder
                }
                if (failedPartitions.isNotEmpty()) {
                    remainingTargets = failedPartitions.toSet()
                }
            }
            if (listText.startsWith("Error")) {
                onLog("[RUST NOTE] Rust extraction notice: $listText")
            }
        } catch (e: Throwable) {
            onLog("[RUST NOTE] Rust extraction fallback: ${e.message}")
        }

        // 2. Try PC CMD Native Executable Engine (`payload-dumper-go`) — only when full/missing run
        if (remainingTargets == targetPartitions) {
            val cmdSuccess = executeCmdPayloadDumper(
                context = context,
                payloadBinFile = payloadBinFile,
                outputFolder = outputFolder,
                targetPartitions = remainingTargets,
                onLog = onLog,
                onProgress = onProgress
            )

            if (cmdSuccess) {
                return outputFolder
            }
        }

        // 3. Fallback: Parse payload.bin Header & Execute Block Decompression Engine
        val raf = RandomAccessFile(payloadBinFile, "r")
        try {
            val magicBuf = ByteArray(4)
            raf.readFully(magicBuf)
            val magic = ByteBuffer.wrap(magicBuf).order(ByteOrder.BIG_ENDIAN).int
            if (magic != CRAU_MAGIC) {
                throw IOException("Invalid payload.bin magic header. Expected 'CrAU'.")
            }

            val version = raf.readLong()
            val manifestSize = raf.readLong()

            var metadataSigSize = 0
            if (version >= 2) {
                metadataSigSize = raf.readInt()
            }

            if (manifestSize <= 0L || manifestSize > 50L * 1024 * 1024L) {
                throw IOException("Invalid payload.bin manifest size: $manifestSize")
            }
            val manifestOffset = raf.channel.position()
            val payloadDataOffset = manifestOffset + manifestSize + metadataSigSize

            val manifestBytes = ByteArray(manifestSize.toInt())
            raf.readFully(manifestBytes)

            // Parse Protobuf DeltaArchiveManifest
            val partitions = parseProtobufManifest(manifestBytes)

            if (partitions.isEmpty()) {
                throw IOException("Could not parse partition manifest from payload.bin.")
            }

            val activePartitions = if (remainingTargets.isNotEmpty()) {
                partitions.filter {
                    remainingTargets.contains("${it.name}.img".lowercase()) ||
                        remainingTargets.contains(it.name.lowercase())
                }
            } else {
                partitions
            }
            if (activePartitions.isEmpty()) {
                throw IOException("No requested partitions could be matched or extracted from payload.bin.")
            }

            // PC-Grade Block Extent Flusher
            dumpPcGradePartitions(
                raf = raf,
                payloadDataOffset = payloadDataOffset,
                payloadFileSize = payloadBinFile.length(),
                partitions = activePartitions,
                outputFolder = outputFolder,
                onLog = onLog,
                onProgress = onProgress
            )

        } finally {
            try { raf.close() } catch (_: Exception) {}
        }

        val extractedNonEmpty = outputFolder.listFiles()?.count {
            it.isFile && it.name.endsWith(".img", ignoreCase = true) && it.length() > 0L
        } ?: 0
        if (extractedNonEmpty == 0) {
            throw IOException("No partition images were extracted.")
        }

        return outputFolder
    }

    // Executable Process Launcher (Runs PC CMD Command on Android ARM64)
    private fun executeCmdPayloadDumper(
        context: Context,
        payloadBinFile: File,
        outputFolder: File,
        targetPartitions: Set<String> = emptySet(),
        onLog: (String) -> Unit,
        onProgress: (DumpProgress) -> Unit
    ): Boolean {
        val binFile = File(context.filesDir, "payload-dumper-go")

        // Ensure binary permission
        if (binFile.exists()) {
            try {
                ProcessBuilder("chmod", "755", binFile.absolutePath).start().waitFor()
            } catch (_: Exception) {}

            try {
                val cmdArgs = mutableListOf(
                    binFile.absolutePath,
                    "-o", outputFolder.absolutePath
                )
                if (targetPartitions.isNotEmpty()) {
                    val joined = targetPartitions.joinToString(",") { it.removeSuffix(".img") }
                    cmdArgs.add("-p")
                    cmdArgs.add(joined)
                }
                cmdArgs.add(payloadBinFile.absolutePath)
                onLog("> ${cmdArgs.joinToString(" ")}")

                val pb = ProcessBuilder(cmdArgs)
                pb.redirectErrorStream(true)

                val process = pb.start()
                val reader = process.inputStream.bufferedReader()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    onLog(text)

                    if (text.contains("%")) {
                        val pctMatch = Regex("(\\d+)%").find(text)
                        if (pctMatch != null) {
                            val pct = pctMatch.groupValues[1].toFloatOrNull() ?: 0f
                            onProgress(
                                DumpProgress(
                                    partitionName = "PC CMD Extracting...",
                                    partitionProgress = (pct / 100f).coerceIn(0f, 1f),
                                    overallProgress = (pct / 100f).coerceIn(0f, 1f),
                                    partitionsDone = 0,
                                    totalPartitions = 100
                                )
                            )
                        }
                    }
                }

                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    onLog("[SUCCESS] PC CMD Command executed successfully with exit code 0!")
                    return true
                }
            } catch (e: Exception) {
                onLog("[CMD NOTE] Executable command notice: ${e.message}. Using internal engine...")
            }
        }
        return false
    }

    /**
     * Streams `payload.bin` directly from a SAF [Uri] (raw `.bin` or inside a `.zip`) into
     * [destFile] without duplicating the rest of a multi-GB OTA zip. Uses seekable channel access
     * when available so `payload.bin` is located immediately via the central directory.
     */
    internal fun extractPayloadBinFromUri(
        context: Context,
        uri: Uri,
        isZip: Boolean,
        destFile: File,
        onLog: (String) -> Unit = {}
    ): File {
        destFile.parentFile?.mkdirs()
        if (destFile.exists()) destFile.delete()

        val buf = ByteArray(1024 * 1024)
        var extracted = false

        if (isZip) {
            // Fast seekable ZIP64 read via ParcelFileDescriptor FileChannel
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        ZipFile.builder().setSeekableByteChannel(channel).get().use { zf ->
                            val entry = zf.getEntry("payload.bin") ?: zf.entries.asSequence().firstOrNull {
                                it.name.endsWith("payload.bin", ignoreCase = true)
                            }
                            if (entry != null) {
                                onLog("[INFO] Streaming payload.bin from ZIP (${formatBytes(entry.size)})...")
                                zf.getInputStream(entry).use { input ->
                                    FileOutputStream(destFile).buffered(1024 * 1024).use { out ->
                                        var r: Int
                                        while (input.read(buf).also { r = it } != -1) {
                                            out.write(buf, 0, r)
                                        }
                                        out.flush()
                                    }
                                }
                                extracted = destFile.exists() && destFile.length() >= 24
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                if (destFile.exists()) destFile.delete()
            }

            // Streaming fallback for non-seekable SAF providers
            if (!extracted) {
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    ZipArchiveInputStream(raw.buffered(1024 * 1024), "UTF-8", true, true).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.endsWith("payload.bin", ignoreCase = true)) {
                                onLog("[INFO] Streaming payload.bin from ZIP stream...")
                                FileOutputStream(destFile).buffered(1024 * 1024).use { out ->
                                    var r: Int
                                    while (zis.read(buf).also { r = it } != -1) {
                                        out.write(buf, 0, r)
                                    }
                                    out.flush()
                                }
                                extracted = destFile.exists() && destFile.length() >= 24
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).buffered(1024 * 1024).use { out ->
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        out.write(buf, 0, r)
                    }
                    out.flush()
                }
                extracted = destFile.exists() && destFile.length() >= 24
            }
        }

        if (!extracted || !destFile.exists() || destFile.length() < 24) {
            if (destFile.exists()) destFile.delete()
            throw IOException("Could not stream payload.bin from the selected file.")
        }
        return destFile
    }

    internal fun tryReadManifestFromZip(zipFile: File): ByteArray? {
        // Method A: Built-in Java ZipFile (instantaneous on Android ART)
        try {
            java.util.zip.ZipFile(zipFile).use { jzf ->
                val entry = jzf.getEntry("payload.bin") ?: jzf.entries().asSequence().firstOrNull {
                    it.name.endsWith("payload.bin", ignoreCase = true)
                }
                if (entry != null) {
                    jzf.getInputStream(entry).use { stream ->
                        val bytes = readManifestFromPayloadStream(stream)
                        if (bytes != null) return bytes
                    }
                }
            }
        } catch (_: Exception) {}

        // Method B: Apache Commons Compress ZipFile (Zip64 compatible)
        try {
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(zipFile).get().use { zf ->
                val entry = zf.getEntry("payload.bin") ?: zf.entries.asSequence().firstOrNull {
                    it.name.endsWith("payload.bin", ignoreCase = true)
                } ?: return null

                zf.getInputStream(entry).use { stream ->
                    val bytes = readManifestFromPayloadStream(stream)
                    if (bytes != null) return bytes
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PayloadDumper", "Failed reading manifest from zip: ${e.message}", e)
        }
        return null
    }

    internal fun tryReadManifestFromChannel(channel: java.nio.channels.SeekableByteChannel): ByteArray? {
        try {
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setSeekableByteChannel(channel).get().use { zf ->
                val entry = zf.getEntry("payload.bin") ?: zf.entries.asSequence().firstOrNull {
                    it.name.endsWith("payload.bin", ignoreCase = true)
                } ?: return null

                zf.getInputStream(entry).use { stream ->
                    return readManifestFromPayloadStream(stream)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PayloadDumper", "Failed reading manifest from channel: ${e.message}", e)
        }
        return null
    }

    internal fun readManifestFromPayloadStream(stream: InputStream): ByteArray? {
        try {
            val dis = java.io.DataInputStream(stream)
            val magicBuf = ByteArray(4)
            dis.readFully(magicBuf)
            val magic = ByteBuffer.wrap(magicBuf).order(ByteOrder.BIG_ENDIAN).int
            if (magic != CRAU_MAGIC) return null

            val version = dis.readLong()
            val manifestSize = dis.readLong()
            if (manifestSize <= 0 || manifestSize > 50 * 1024 * 1024L) return null

            if (version >= 2) {
                dis.readInt() // metadataSigSize
            }

            val manifestBytes = ByteArray(manifestSize.toInt())
            dis.readFully(manifestBytes)
            return manifestBytes
        } catch (_: Exception) {
            return null
        }
    }

    private fun findOrExtractPayloadBin(zipFile: File, outputBaseDir: File, onLog: (String) -> Unit = {}): File? {
        val cachePayload = File(outputBaseDir, "temp_payload_${zipFile.nameWithoutExtension}.bin")

        try {
            ZipFile.builder().setFile(zipFile).get().use { zf ->
                val entry = zf.getEntry("payload.bin") ?: zf.entries.asSequence().firstOrNull {
                    it.name.endsWith("payload.bin", ignoreCase = true)
                } ?: return null

                val expectedSize = entry.size
                if (cachePayload.exists() && expectedSize > 0 && cachePayload.length() == expectedSize) {
                    return cachePayload
                }

                if (cachePayload.exists()) {
                    cachePayload.delete()
                }

                zf.getInputStream(entry).use { input ->
                    FileOutputStream(cachePayload).buffered(1024 * 1024).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }

                if (cachePayload.exists() && cachePayload.length() > 1000) {
                    return cachePayload
                }
            }
        } catch (_: Exception) {}
        return null
    }

    // Protobuf Wire-Format Manifest Parser
    internal fun parseProtobufManifest(manifestBytes: ByteArray): List<PartitionUpdate> {
        val partitions = mutableListOf<PartitionUpdate>()
        val buf = ByteBuffer.wrap(manifestBytes)

        try {
            while (buf.hasRemaining()) {
                val key = readVarint(buf) ?: break
                val fieldNumber = (key ushr 3).toInt()
                val wireType = (key and 0x07).toInt()

                if (fieldNumber == 13 && wireType == 2) { // partitions field (tag 13)
                    val len = readVarint(buf)?.toInt() ?: break
                    if (buf.remaining() >= len) {
                        val pBytes = ByteArray(len)
                        buf.get(pBytes)
                        val partition = parsePartitionUpdate(pBytes)
                        if (partition != null) {
                            partitions.add(partition)
                        }
                    } else break
                } else {
                    skipField(buf, wireType)
                }
            }
        } catch (_: Exception) {}

        return partitions
    }

    private fun parsePartitionUpdate(pBytes: ByteArray): PartitionUpdate? {
        val buf = ByteBuffer.wrap(pBytes)
        var name = ""
        var size = 0L
        var sha256: ByteArray? = null
        val operations = mutableListOf<InstallOperation>()

        try {
            while (buf.hasRemaining()) {
                val key = readVarint(buf) ?: break
                val fieldNumber = (key ushr 3).toInt()
                val wireType = (key and 0x07).toInt()

                when {
                    fieldNumber == 1 && wireType == 2 -> { // partition_name (Tag 1)
                        val len = readVarint(buf)?.toInt() ?: break
                        val subLen = minOf(len, buf.remaining())
                        if (subLen > 0) {
                            val strBytes = ByteArray(subLen)
                            buf.get(strBytes)
                            name = String(strBytes, Charsets.UTF_8)
                        }
                    }
                    fieldNumber == 7 && wireType == 2 -> { // new_partition_info (Tag 7 in AOSP DeltaArchiveManifest)
                        val len = readVarint(buf)?.toInt() ?: break
                        val subLen = minOf(len, buf.remaining())
                        if (subLen > 0) {
                            val infoBytes = ByteArray(subLen)
                            buf.get(infoBytes)
                            val info = parsePartitionInfo(infoBytes)
                            if (info.size > 0) size = info.size
                            if (info.sha256 != null) sha256 = info.sha256
                        }
                    }
                    (fieldNumber == 4 || fieldNumber == 5) && wireType == 2 -> { // legacy PartitionInfo fallback
                        val len = readVarint(buf)?.toInt() ?: break
                        val subLen = minOf(len, buf.remaining())
                        if (subLen > 0 && size == 0L) {
                            val infoBytes = ByteArray(subLen)
                            buf.get(infoBytes)
                            val info = parsePartitionInfo(infoBytes)
                            if (info.size > 0) size = info.size
                            if (info.sha256 != null) sha256 = info.sha256
                        }
                    }
                    (fieldNumber == 8 || fieldNumber == 6) && wireType == 2 -> { // operations (Tag 8 in AOSP, Tag 6 in legacy)
                        val len = readVarint(buf)?.toInt() ?: break
                        val subLen = minOf(len, buf.remaining())
                        if (subLen > 0) {
                            val opBytes = ByteArray(subLen)
                            buf.get(opBytes)
                            val op = parseInstallOperation(opBytes)
                            operations.add(op)
                        }
                    }
                    else -> skipField(buf, wireType)
                }
            }
        } catch (_: Exception) {}

        return if (name.isNotEmpty()) PartitionUpdate(name, size, sha256, operations) else null
    }

    private data class ParsedPartitionInfo(val size: Long, val sha256: ByteArray?)

    /**
     * Parses a PartitionInfo message:
     *   field 1 (varint) = size
     *   field 3 (bytes)  = sha256_hash (32 bytes)
     */
    private fun parsePartitionInfo(infoBytes: ByteArray): ParsedPartitionInfo {
        val buf = ByteBuffer.wrap(infoBytes)
        var size = 0L
        var sha256: ByteArray? = null
        try {
            while (buf.hasRemaining()) {
                val key = readVarint(buf) ?: break
                val fieldNumber = (key ushr 3).toInt()
                val wireType = (key and 0x07).toInt()

                when {
                    fieldNumber == 1 && wireType == 0 -> size = readVarint(buf) ?: 0L
                    fieldNumber == 3 && wireType == 2 -> {
                        val len = readVarint(buf)?.toInt() ?: break
                        val subLen = minOf(len, buf.remaining())
                        if (subLen > 0) {
                            val bytes = ByteArray(subLen)
                            buf.get(bytes)
                            if (bytes.size == 32) sha256 = bytes
                        }
                    }
                    else -> skipField(buf, wireType)
                }
            }
        } catch (_: Exception) {}
        return ParsedPartitionInfo(size, sha256)
    }

    private fun parseInstallOperation(opBytes: ByteArray): InstallOperation {
        val buf = ByteBuffer.wrap(opBytes)
        var type = 0
        var dataOffset = 0L
        var dataLength = 0L
        val extents = mutableListOf<Extent>()

        try {
            while (buf.hasRemaining()) {
                val key = readVarint(buf) ?: break
                val fieldNumber = (key ushr 3).toInt()
                val wireType = (key and 0x07).toInt()

                when {
                    fieldNumber == 1 && wireType == 0 -> type = (readVarint(buf) ?: 0L).toInt()
                    fieldNumber == 2 && wireType == 0 -> dataOffset = readVarint(buf) ?: 0L
                    fieldNumber == 3 && wireType == 0 -> dataLength = readVarint(buf) ?: 0L
                    fieldNumber == 6 && wireType == 2 -> { // dst_extents
                        val len = readVarint(buf)?.toInt() ?: break
                        if (buf.remaining() >= len) {
                            val extBytes = ByteArray(len)
                            buf.get(extBytes)
                            val ext = parseExtent(extBytes)
                            extents.add(ext)
                        }
                    }
                    else -> skipField(buf, wireType)
                }
            }
        } catch (_: Exception) {}

        return InstallOperation(type, dataOffset, dataLength, extents)
    }

    private fun parseExtent(extBytes: ByteArray): Extent {
        val buf = ByteBuffer.wrap(extBytes)
        var startBlock = 0L
        var numBlocks = 0L

        try {
            while (buf.hasRemaining()) {
                val key = readVarint(buf) ?: break
                val fieldNumber = (key ushr 3).toInt()
                val wireType = (key and 0x07).toInt()

                when {
                    fieldNumber == 1 && wireType == 0 -> startBlock = readVarint(buf) ?: 0L
                    fieldNumber == 2 && wireType == 0 -> numBlocks = readVarint(buf) ?: 0L
                    else -> skipField(buf, wireType)
                }
            }
        } catch (_: Exception) {}

        return Extent(startBlock, numBlocks)
    }

    private fun readVarint(buf: ByteBuffer): Long? {
        var result = 0L
        var shift = 0
        while (buf.hasRemaining()) {
            val b = buf.get().toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }

    private fun skipField(buf: ByteBuffer, wireType: Int) {
        when (wireType) {
            0 -> readVarint(buf)
            1 -> if (buf.remaining() >= 8) buf.position(buf.position() + 8) else buf.position(buf.limit())
            2 -> {
                val rawLen = readVarint(buf) ?: 0L
                if (rawLen < 0L || rawLen > buf.remaining()) {
                    buf.position(buf.limit())
                } else {
                    buf.position(buf.position() + rawLen.toInt())
                }
            }
            5 -> if (buf.remaining() >= 4) buf.position(buf.position() + 4) else buf.position(buf.limit())
            else -> buf.position(buf.limit())
        }
    }

    // Bounded InputStream helper for RandomAccessFile
    private class BoundedInputStream(
        private val raf: RandomAccessFile,
        startPos: Long,
        private val length: Long
    ) : InputStream() {
        private var currentPos = startPos
        private val endPos = startPos + length

        override fun read(): Int {
            if (currentPos >= endPos) return -1
            synchronized(raf) {
                raf.seek(currentPos)
                val b = raf.read()
                if (b != -1) currentPos++
                return b
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (currentPos >= endPos) return -1
            val maxRead = minOf(len.toLong(), endPos - currentPos).toInt()
            if (maxRead <= 0) return -1
            synchronized(raf) {
                raf.seek(currentPos)
                val bytesRead = raf.read(b, off, maxRead)
                if (bytesRead > 0) {
                    currentPos += bytesRead
                }
                return bytesRead
            }
        }
    }

    // PC-Grade Decompression & Block Extent Flusher
    private fun dumpPcGradePartitions(
        raf: RandomAccessFile,
        payloadDataOffset: Long,
        payloadFileSize: Long,
        partitions: List<PartitionUpdate>,
        outputFolder: File,
        onLog: (String) -> Unit,
        onProgress: (DumpProgress) -> Unit
    ) {
        val numPartitions = partitions.size
        var currentPartIdx = 0
        var fallbackOffset = payloadDataOffset

        val buf = ByteArray(256 * 1024)

        for (part in partitions) {
            currentPartIdx++
            val outFile = File(outputFolder, "${part.name}.img")
            onLog("Dumping partition [${currentPartIdx}/$numPartitions]: ${part.name}.img")

            onProgress(
                DumpProgress(
                    partitionName = "${part.name}.img",
                    partitionProgress = 0f,
                    overallProgress = currentPartIdx.toFloat() / numPartitions.toFloat(),
                    partitionsDone = currentPartIdx - 1,
                    totalPartitions = numPartitions
                )
            )

            FileOutputStream(outFile).use { fos ->
                val outChannel = fos.channel
                val ops = part.operations
                val totalOps = ops.size

                if (totalOps > 0) {
                    var opIdx = 0
                    for (op in ops) {
                        opIdx++
                        when (op.type) {
                            0 -> { // REPLACE (uncompressed) -> raw bytes fill the dst extents in order
                                if (op.dataLength > 0) {
                                    val srcPos = payloadDataOffset + op.dataOffset
                                    synchronized(raf) {
                                        raf.channel.position(srcPos)
                                        var remaining = op.dataLength
                                        for (ext in op.dstExtents) {
                                            if (remaining <= 0) break
                                            var extRemaining = ext.numBlocks * 4096L
                                            outChannel.position(ext.startBlock * 4096L)
                                            while (extRemaining > 0 && remaining > 0) {
                                                val toRead = minOf(buf.size.toLong(), extRemaining, remaining).toInt()
                                                val bytesRead = raf.channel.read(ByteBuffer.wrap(buf, 0, toRead))
                                                if (bytesRead <= 0) { remaining = 0; break }
                                                outChannel.write(ByteBuffer.wrap(buf, 0, bytesRead))
                                                extRemaining -= bytesRead
                                                remaining -= bytesRead
                                            }
                                        }
                                    }
                                }
                            }
                            1 -> { // REPLACE_BZ (bzip2)
                                if (op.dataLength > 0) {
                                    val srcPos = payloadDataOffset + op.dataOffset
                                    try {
                                        BZip2CompressorInputStream(BoundedInputStream(raf, srcPos, op.dataLength)).use { bz ->
                                            writeStreamToExtents(outChannel, bz, op.dstExtents, buf, -1L)
                                        }
                                    } catch (e: Exception) {
                                        throw IOException("BZIP2 decompression failed on ${part.name}: ${e.message}", e)
                                    }
                                }
                            }
                            6, 7 -> { // ZERO / DISCARD -> zero-fill every dst extent
                                for (ext in op.dstExtents) {
                                    if (ext.numBlocks <= 0) continue
                                    outChannel.position(ext.startBlock * 4096L)
                                    var remaining = ext.numBlocks * 4096L
                                    val zeroBuf = ByteArray(minOf(buf.size.toLong(), remaining).toInt())
                                    while (remaining > 0) {
                                        val toWrite = minOf(zeroBuf.size.toLong(), remaining).toInt()
                                        outChannel.write(ByteBuffer.wrap(zeroBuf, 0, toWrite))
                                        remaining -= toWrite
                                    }
                                }
                            }
                            8 -> { // REPLACE_XZ (XZ / LZMA)
                                if (op.dataLength > 0) {
                                    val srcPos = payloadDataOffset + op.dataOffset
                                    try {
                                        XZInputStream(BoundedInputStream(raf, srcPos, op.dataLength)).use { xz ->
                                            writeStreamToExtents(outChannel, xz, op.dstExtents, buf, -1L)
                                        }
                                    } catch (e: Exception) {
                                        throw IOException("XZ decompression failed on ${part.name}: ${e.message}", e)
                                    }
                                }
                            }
                            else -> {
                                throw IOException(
                                    "Partition '${part.name}' uses incremental OTA delta op (type ${op.type}); " +
                                        "a full ROM payload.bin is required."
                                )
                            }
                        }

                        if (opIdx % 5 == 0 || opIdx == totalOps) {
                            val partFrac = opIdx.toFloat() / totalOps.toFloat()
                            onProgress(
                                DumpProgress(
                                    partitionName = "${part.name}.img",
                                    partitionProgress = partFrac,
                                    overallProgress = (((currentPartIdx - 1) + partFrac) / numPartitions.toFloat()).coerceIn(0f, 1f),
                                    partitionsDone = currentPartIdx - 1,
                                    totalPartitions = numPartitions
                                )
                            )
                        }
                    }

                    // Truncate to exact true decompressed size
                    if (part.size > 0) {
                        outChannel.truncate(part.size)
                    }

                } else {
                    // Contiguous stream fallback
                    val targetSize = if (part.size > 0) part.size else (64 * 1024 * 1024L)
                    synchronized(raf) {
                        raf.channel.position(fallbackOffset)
                        outChannel.position(0L)

                        var written = 0L
                        val toWriteTotal = minOf(targetSize, payloadFileSize - fallbackOffset)

                        while (written < toWriteTotal) {
                            val toRead = minOf(buf.size.toLong(), toWriteTotal - written).toInt()
                            val bBuf = ByteBuffer.wrap(buf, 0, toRead)
                            val bytesRead = raf.channel.read(bBuf)
                            if (bytesRead <= 0) break

                            outChannel.write(ByteBuffer.wrap(buf, 0, bytesRead))
                            written += bytesRead
                        }

                        fallbackOffset += written
                    }
                }
            }

            onLog("Extracted: ${part.name}.img (${outFile.length() / (1024 * 1024)} MB)")

            onProgress(
                DumpProgress(
                    partitionName = "${part.name}.img",
                    partitionProgress = 1f,
                    overallProgress = currentPartIdx.toFloat() / numPartitions.toFloat(),
                    partitionsDone = currentPartIdx,
                    totalPartitions = numPartitions
                )
            )
        }
    }

    // ========================================================================
    //  SHA-256 VERIFICATION of already extracted partition images
    // ========================================================================

    data class VerifyResult(
        val checked: Int,
        val ok: Int,
        val failed: Int,
        val skipped: Int
    ) {
        fun summary(): String = when {
            checked == 0 -> "No extractable images with a known hash were found."
            failed == 0 -> "$ok/$checked image(s) verified OK (SHA-256 matches the ROM)."
            else -> "$failed of $checked image(s) FAILED hash check. Re-extract the ROM."
        }
    }

    /**
     * Reads the manifest of [inputFile] (zip or payload.bin) and compares the SHA-256 of
     * every `<partition>.img` inside [outputFolder] against the value stored in the ROM.
     */
    suspend fun verifyExtractedImages(
        inputFile: File,
        outputBaseDir: File,
        outputFolder: File,
        onLog: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): VerifyResult {
        var payloadBinFile = inputFile
        if (inputFile.extension.equals("zip", ignoreCase = true)) {
            val extracted = findOrExtractPayloadBin(inputFile, outputBaseDir, onLog)
            if (extracted != null && extracted.exists()) {
                payloadBinFile = extracted
            } else {
                onLog("[VERIFY] Could not locate payload.bin inside the zip.")
                return VerifyResult(0, 0, 0, 0)
            }
        }
        if (!payloadBinFile.exists() || payloadBinFile.length() < 24) {
            onLog("[VERIFY] Invalid payload.bin for verification.")
            return VerifyResult(0, 0, 0, 0)
        }

        val partitions: List<PartitionUpdate>
        RandomAccessFile(payloadBinFile, "r").use { raf ->
            val magicBuf = ByteArray(4)
            raf.readFully(magicBuf)
            val magic = ByteBuffer.wrap(magicBuf).order(ByteOrder.BIG_ENDIAN).int
            if (magic != CRAU_MAGIC) {
                onLog("[VERIFY] Not a valid payload.bin (bad magic).")
                return VerifyResult(0, 0, 0, 0)
            }
            val version = raf.readLong()
            val manifestSize = raf.readLong()
            if (version >= 2) raf.readInt()
            val manifestBytes = ByteArray(minOf(manifestSize, 15 * 1024 * 1024L).toInt())
            raf.readFully(manifestBytes)
            partitions = parseProtobufManifest(manifestBytes)
        }

        val expected = partitions.filter { it.sha256 != null }
        if (expected.isEmpty()) {
            onLog("[VERIFY] This payload does not carry per-partition SHA-256 hashes.")
            return VerifyResult(0, 0, 0, 0)
        }

        onLog("[VERIFY] Verifying ${expected.size} partition image(s) with SHA-256...")
        var checked = 0
        var ok = 0
        var failed = 0
        var skipped = 0
        expected.forEachIndexed { index, part ->
            val img = File(outputFolder, "${part.name}.img")
            if (!img.exists() || img.length() <= 0) {
                skipped++
            } else {
                checked++
                val digest = sha256Of(img)
                val matches = digest != null && digest.contentEquals(part.sha256)
                if (matches) {
                    ok++
                    onLog("[VERIFY] OK    ${part.name}.img")
                } else {
                    failed++
                    onLog("[VERIFY] FAIL  ${part.name}.img  (hash mismatch)")
                }
            }
            onProgress((index + 1).toFloat() / expected.size.toFloat())
        }
        onProgress(1f)
        val result = VerifyResult(checked, ok, failed, skipped)
        onLog("[VERIFY] ${result.summary()}")
        return result
    }

    private fun sha256Of(file: File): ByteArray? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1024 * 1024).use { input ->
                val buffer = ByteArray(1024 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest()
        } catch (_: Exception) {
            null
        }
    }
}
