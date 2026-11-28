package com.romflasher.tool.usb

import com.romflasher.tool.ui.tr

import android.hardware.usb.*
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ============================================================================
 *  FASTBOOT-OVER-USB SESSION  (rebuilt for reliability)
 * ============================================================================
 *  This is the low-level transport used by every fastboot operation in the app
 *  (Option 1 script flashing, Option 2 single-image flashing, EFS tools, ...).
 *
 *  Key design points:
 *    • Every USB write is split into small transfers with retries, because
 *      Android's synchronous bulkTransfer is unreliable for large buffers and
 *      can return -1 (or a short count) on many kernels.
 *    • Responses are read by accumulating data until a real fastboot token
 *      (OKAY / FAIL / DATA / INFO) appears, so intermediate INFO lines or a
 *      response split across USB packets no longer look like failures.
 *    • Images larger than max-download-size are streamed as multiple sparse
 *      bundles. A sparse image is bundled as-is; a raw image is wrapped in a
 *      synthetic sparse container. DONT_CARE chunks keep the block offsets
 *      correct between bundles (this is exactly what PC fastboot does).
 * ============================================================================
 */
class FastbootSession private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint
) : AutoCloseable {

    /**
     * Real-time USB Throughput & Speedometer (MB/s & ETA calculation).
     */
    private class ThroughputTracker(
        private val totalBytes: Long,
        private val onLog: (String) -> Unit,
        private val logIntervalMs: Long = 1500L
    ) {
        private val startTime = System.currentTimeMillis()
        private var lastLogTime = startTime
        private var lastSent = 0L

        fun update(sentBytes: Long) {
            val now = System.currentTimeMillis()
            if (now - lastLogTime >= logIntervalMs || sentBytes >= totalBytes) {
                val elapsedTotal = (now - startTime).coerceAtLeast(1)
                val elapsedInterval = (now - lastLogTime).coerceAtLeast(1)
                val intervalBytes = (sentBytes - lastSent).coerceAtLeast(0)

                val intervalSpeed = (intervalBytes.toDouble() / (1024.0 * 1024.0)) / (elapsedInterval / 1000.0)
                val avgSpeed = (sentBytes.toDouble() / (1024.0 * 1024.0)) / (elapsedTotal / 1000.0)
                val effectiveSpeed = if (intervalSpeed > 0.1) intervalSpeed else avgSpeed

                val pct = if (totalBytes > 0) ((sentBytes.toDouble() / totalBytes.toDouble()) * 100.0).coerceIn(0.0, 100.0) else 0.0
                val remBytes = (totalBytes - sentBytes).coerceAtLeast(0)
                val etaSec = if (effectiveSpeed > 0.05) (remBytes / (effectiveSpeed * 1024.0 * 1024.0)).toLong() else 0L
                val etaStr = if (etaSec > 0) "%02dm %02ds".format(etaSec / 60, etaSec % 60) else "finishing…"

                val sentMb = sentBytes / (1024 * 1024)
                val totalMb = totalBytes / (1024 * 1024)
                onLog("   ⚡ ${"%.1f".format(pct)}% ($sentMb/$totalMb MB) • Speed: ${"%.1f".format(effectiveSpeed)} MB/s • ETA: $etaStr")

                lastLogTime = now
                lastSent = sentBytes
            }
        }
    }

    companion object {
        /**
         * Size of a single USB bulk transfer. 16 KB is the sweet spot for
         * Android's synchronous bulkTransfer: many host controllers silently
         * fail (-1 / short count) on large buffers even though the endpoint is
         * healthy. Large transfers are split into these by [write].
         */
        private const val USB_WRITE_CHUNK = 16 * 1024

        /** Fail fast on a wedged endpoint so recovery isn't a 2-minute hang. */
        private const val WRITE_TIMEOUT_MS = 5000
        private const val WRITE_RETRIES = 6

        /**
         * Safety ceiling for a single download bundle (512 MB). 512 MB matches
         * AOSP standard sparse limits (-S 512M) and Xiaomi's official ROM generator
         * (flash_gen_crc_list.py: max_download_size = 512*1024*1024), matching
         * sparsecrclist.txt part boundaries and preventing "Requested download size
         * is more than max allowed" errors on Qualcomm bootloaders.
         */
        private const val MAX_BUNDLE_CAP = 512L * 1024 * 1024L

        /**
         * First-bundle size. Deliberately conservative: many devices advertise
         * a large max-download-size but the USB gadget stalls when the host
         * pushes the full amount in one burst. 64 MB is proven reliable across
         * Android OTG hosts; the cap is grown only after real successes.
         */
        private const val SAFE_INITIAL_CAP = 64L * 1024 * 1024L
        private const val MIN_BUNDLE_CAP = 16L * 1024 * 1024L

        /**
         * Largest bundle size proven safe on THIS device. Grows on success and
         * halves on a stall. [safeBundleCeiling] is set whenever a stall forces
         * a shrink so growth can never creep back up to a size that just failed.
         */
        @Volatile
        internal var provenBundleCap = 0L
        @Volatile
        internal var safeBundleCeiling = 0L
        @Volatile
        private var lastOpenedDeviceId = -1

        private const val SPARSE_MAGIC = 0xED26FF3A.toInt()
        private const val CHUNK_TYPE_RAW = 0xCAC1
        private const val CHUNK_TYPE_DONT_CARE = 0xCAC3

        private const val SPARSE_HEADER_SIZE = 28L
        private const val SPARSE_CHUNK_HEADER_SIZE = 12L

        /**
         * Per-download overhead reserved by AOSP's `move_chunks_up_to_len` and by
         * the MTK/Xiaomi CRC-list generator (`flash_gen_crc_list.py`):
         * 28-byte sparse header + up to two 12-byte DONT_CARE headers + 4-byte CRC.
         *
         * MUST stay 56 to reproduce the exact part boundaries the device's
         * `sparsecrclist.txt` was generated against, otherwise the bootloader
         * answers `failed to check sparse crc`.
         */
        private const val SPARSE_OVERHEAD = 28L + 2L * 12L + 4L

        private const val FASTBOOT_CLASS = 0xFF
        private const val FASTBOOT_SUBCLASS = 0x42
        private const val FASTBOOT_PROTOCOL = 0x03
        private const val ADB_PROTOCOL = 0x01

        private data class FastbootIfaceCandidate(
            val iface: UsbInterface,
            val inEp: UsbEndpoint,
            val outEp: UsbEndpoint,
            val priority: Int
        )

        fun open(usbManager: UsbManager, device: UsbDevice): FastbootSession? {
            if (device.deviceId != lastOpenedDeviceId) {
                lastOpenedDeviceId = device.deviceId
                provenBundleCap = 0L
                safeBundleCeiling = 0L
            }
            val connection = try {
                usbManager.openDevice(device)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            } ?: return null
            val candidates = mutableListOf<FastbootIfaceCandidate>()

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null

                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
                        if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
                    }
                }
                if (inEp != null && outEp != null) {
                    val cls = iface.interfaceClass
                    val sub = iface.interfaceSubclass
                    val proto = iface.interfaceProtocol
                    val priority = when {
                        cls == FASTBOOT_CLASS && sub == FASTBOOT_SUBCLASS && proto == FASTBOOT_PROTOCOL -> 0
                        cls == FASTBOOT_CLASS && sub == FASTBOOT_SUBCLASS && proto != ADB_PROTOCOL -> 1
                        cls == FASTBOOT_CLASS && sub != FASTBOOT_SUBCLASS -> 2
                        !(cls == FASTBOOT_CLASS && sub == FASTBOOT_SUBCLASS && proto == ADB_PROTOCOL) -> 3
                        else -> 4
                    }
                    candidates.add(FastbootIfaceCandidate(iface, inEp, outEp, priority))
                }
            }

            candidates.sortBy { it.priority }
            for (cand in candidates) {
                if (connection.claimInterface(cand.iface, true)) {
                    return FastbootSession(connection, cand.iface, cand.inEp, cand.outEp)
                }
            }
            try { connection.close() } catch (_: Exception) {}
            return null
        }

        /** True when [file] starts with the Android sparse-image magic. */
        private fun isSparseImage(file: File): Boolean {
            return try {
                FileInputStream(file).use { fis ->
                    val head = ByteArray(4)
                    if (fis.read(head) != 4) return false
                    val magic = (head[0].toInt() and 0xFF) or
                            ((head[1].toInt() and 0xFF) shl 8) or
                            ((head[2].toInt() and 0xFF) shl 16) or
                            ((head[3].toInt() and 0xFF) shl 24)
                    magic == SPARSE_MAGIC
                }
            } catch (_: Exception) {
                false
            }
        }

        private fun isSparseCompatiblePartition(partition: String): Boolean {
            val p = partition.lowercase().removeSuffix("_a").removeSuffix("_b").removeSuffix("_ab")
            return p in setOf(
                "super", "system", "vendor", "product", "system_ext", "odm",
                "userdata", "cust", "cache", "oem", "mi_ext"
            )
        }
    }

    // ------------------------------------------------------------------------
    //  Low-level USB transport
    // ------------------------------------------------------------------------

    /** Writes [len] bytes, splitting into small transfers and retrying failures. */
    private fun write(data: ByteArray, offset: Int, len: Int) {
        var sent = 0
        while (sent < len) {
            val chunk = minOf(USB_WRITE_CHUNK, len - sent)
            var written = -1
            var attempt = 0
            while (attempt < WRITE_RETRIES) {
                written = connection.bulkTransfer(outEndpoint, data, offset + sent, chunk, WRITE_TIMEOUT_MS)
                if (written > 0) break
                attempt++
                if (attempt < WRITE_RETRIES) {
                    // Only send CLEAR_FEATURE(ENDPOINT_HALT) on the second consecutive failure
                    // so a single transient timeout doesn't reset DATA0/DATA1 toggle.
                    if (attempt >= 2) {
                        clearEndpointHalt()
                    }
                    try { Thread.sleep(50L * attempt) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }
            }
            if (written <= 0) {
                throw IOException("USB bulk write failed ($sent/$len bytes sent, last transfer returned $written for ${chunk}b)")
            }
            sent += written
        }
    }

    /** Sends CLEAR_FEATURE(ENDPOINT_HALT) to the OUT endpoint to recover stalled transfers. */
    private fun clearEndpointHalt() {
        try {
            connection.controlTransfer(0x02, 0x01, 0x00, outEndpoint.address, ByteArray(0), 0, 100)
        } catch (_: Exception) {}
    }

    private fun write(command: String) {
        val bytes = command.toByteArray(Charsets.US_ASCII)
        write(bytes, 0, bytes.size)
    }

    /** Builds a 12-byte sparse DONT_CARE chunk header (no payload). */
    private fun dontCareBytes(blocks: Long): ByteArray {
        val dc = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        dc.putShort(CHUNK_TYPE_DONT_CARE.toShort())
        dc.putShort(0)
        dc.putInt(blocks.toInt())
        dc.putInt(12)
        dc.flip()
        return dc.array()
    }

    /**
     * Buffers the download payload and flushes it in USB-packet-aligned chunks.
     *
     * The target reads the entire download with ONE transport Read() that aborts
     * as soon as it sees a short USB packet (see AOSP ClientUsbTransport::Read,
     * `if (bytes_read_now < bytes_to_read) break;`). So a short packet is only
     * legal as the FINAL flush — sending the 28-byte sparse header or a 12-byte
     * chunk header as its own bulkTransfer aborts the device's read mid-download,
     * which is exactly the "endpoint stalls / no DATA response" symptom.
     *
     * AOSP avoids this the same way (SparseWriteCallback buffers into
     * TRANSPORT_CHUNK_SIZE and comments "Don't send a ZLP").
     */
    private inner class PayloadWriter {
        private val align: Int = outEndpoint.maxPacketSize.coerceAtLeast(64)
        private val bufSize: Int = (64 * 1024 / align) * align
        private val buf = ByteArray(bufSize)
        private var len = 0

        fun add(src: ByteArray, off: Int, n: Int) {
            var o = off
            var remaining = n
            while (remaining > 0) {
                val take = minOf(bufSize - len, remaining)
                System.arraycopy(src, o, buf, len, take)
                len += take
                o += take
                remaining -= take
                if (len == bufSize) {
                    write(buf, 0, len)   // bufSize is packet-aligned -> no short packet
                    len = 0
                }
            }
        }

        /** Flush the residual; this is the only place a short packet is allowed. */
        fun finish() {
            if (len > 0) {
                write(buf, 0, len)
                len = 0
            }
        }
    }

    private fun drainInEndpoint() {
        try {
            val drainBuf = ByteArray(256)
            var count = 0
            while (count < 32 && connection.bulkTransfer(inEndpoint, drainBuf, drainBuf.size, 5) > 0) {
                count++
            }
        } catch (_: Exception) {}
    }

    /**
     * Reads a full fastboot response. Accumulates USB packets until a terminal
     * response token (OKAY / FAIL / DATA) is seen or [timeoutMs] elapses.
     * Intermediate INFO lines are collected but do not end the read.
     */
    private fun readResponse(timeoutMs: Int): String {
        val buf = ByteArray(1024)
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).coerceIn(10L, 150L).toInt()
            val r = try {
                connection.bulkTransfer(inEndpoint, buf, buf.size, remaining)
            } catch (_: Exception) {
                -1
            }
            if (r > 0) {
                sb.append(String(buf, 0, r, Charsets.US_ASCII))
                // INFO lines can precede the terminal token, so look for complete OKAY / FAIL /
                // DATA<8-hex> at line start or un-delimited end of response.
                val idx = terminalTokenIndex(sb.toString())
                if (idx >= 0) {
                    return sb.toString()
                }
            }
        }
        return sb.toString().trimStart()
    }

    /**
     * True if [tok] appears at the start of any line, or as a fastboot protocol terminal
     * token at the end of concatenated un-delimited OEM responses.
     */
    private fun String.hasTokenAtLineStart(tok: String): Boolean {
        var i = 0
        while (i < length) {
            if (regionMatches(i, tok, 0, tok.length)) return true
            val nl = indexOf('\n', i)
            if (nl < 0) break
            i = nl + 1
        }
        if (tok == "OKAY" && endsWith("OKAY") && lastIndexOf("FAIL") < length - 4) return true
        return false
    }

    /** First index where a complete terminal fastboot token starts. */
    private fun terminalTokenIndex(s: String): Int {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == 'O' && s.startsWith("OKAY", i)) return i
            if (c == 'F' && s.startsWith("FAIL", i)) return i
            if (c == 'D' && s.startsWith("DATA", i) && s.length >= i + 12) {
                val hexPart = s.substring(i + 4, i + 12)
                if (hexPart.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return i
            }
            val nl = s.indexOf('\n', i)
            if (nl < 0) break
            i = nl + 1
        }
        // Fallback only for un-delimited OEM responses ending cleanly with OKAY or DATA<8-hex>
        if (s.endsWith("OKAY")) return s.length - 4
        if (s.length >= 12) {
            val dataIdx = s.length - 12
            if (s.startsWith("DATA", dataIdx)) {
                val hexPart = s.substring(dataIdx + 4)
                if (hexPart.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return dataIdx
            }
        }
        return -1
    }

    // ------------------------------------------------------------------------
    //  Fastboot commands
    // ------------------------------------------------------------------------

    fun executeGenericCommand(command: String): String? {
        return try {
            drainInEndpoint()
            write(command)
            val res = readResponse(10000)
            if (res.isEmpty()) null else res
        } catch (e: Exception) {
            null
        }
    }

    fun getVariable(varName: String): String? {
        val res = executeGenericCommand("getvar:$varName") ?: return null
        val idx = res.indexOf("OKAY")
        if (idx >= 0) {
            val before = res.substring(0, idx)
            // Device replies like "INFOproduct: star" or "product: star".
            val key = Regex.escape(varName.lowercase())
            val m = Regex("(?i)$key\\s*:?\\s*([^\\r\\n]+)").find(before)
            if (m != null) {
                val v = m.groupValues[1].trim()
                if (v.isNotEmpty() && !v.equals("unknown", ignoreCase = true)) return v
            }
            val after = res.substring(idx + 4).trim()
            if (after.isNotEmpty()) return after
        }
        return null
    }

    // ------------------------------------------------------------------------
    //  Flashing
    // ------------------------------------------------------------------------

    fun flashPartition(
        partition: String,
        file: File,
        onLog: (String) -> Unit
    ): Boolean = flashPartition(partition, file, onLog, null)

    /**
     * Flashes [file] into [partition], automatically choosing the right strategy:
     *  - fits in one download        -> single payload
     *  - larger & already sparse     -> sparse bundle streaming (offsets preserved)
     *  - larger & raw                -> synthetic sparse bundle streaming
     */
    fun flashPartition(
        partition: String,
        file: File,
        onLog: (String) -> Unit,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)?
    ): Boolean {
        if (!file.exists() || !file.isFile) {
            onLog("[ERROR] Image file not found: ${file.name}")
            return false
        }

        val fileSize = file.length()
        if (fileSize <= 0L) {
            onLog("[ERROR] Image file is empty: ${file.name}")
            return false
        }

        val maxDownload = getMaxDownloadSize()
        val sparse = isSparseImage(file)
        onLog("[FB] device max-download-size = ${formatMb(maxDownload)} (PC-style chunk target)")
        onLog("[FB] engine v5 · CRC-exact sparse parts (matches sparsecrclist.txt)")

        return try {
            when {
                // Fits in a single download: send it directly. A raw image this
                // small is accepted as-is by the bootloader.
                fileSize <= maxDownload -> {
                    onLog("[FLASH] $partition  (${formatMb(fileSize)}${if (sparse) ", sparse" else ""}, single download)")
                    if (flashSinglePayload(partition, file, fileSize, onLog, onProgress)) {
                        true
                    } else if (sparse || isSparseCompatiblePartition(partition)) {
                        onLog("[INFO] $partition single download rejected — falling back to sparse streaming")
                        flashStreamed(partition, file, maxDownload, sparse, onLog, onProgress)
                    } else {
                        false
                    }
                }
                // Larger than max-download-size: it MUST be streamed as sparse
                // bundles. Streaming raw bytes with `flash` is rejected by the
                // bootloader (this was the "super chunk 1 FAIL" bug).
                else -> {
                    onLog("[PC FASTBOOT] Flashing $partition (${formatMb(fileSize)}) via Auto-Chunk Stream Engine...")
                    flashStreamed(partition, file, maxDownload, sparse, onLog, onProgress)
                }
            }
        } catch (e: Exception) {
            onLog("[ERROR] Flashing $partition failed: ${e.message}")
            false
        }
    }

    /** Streams an image that is larger than max-download-size. A pre-sparse
     *  image is re-bundled chunk-for-chunk; a raw image is wrapped in a
     *  synthetic sparse container (exactly what PC fastboot does). */
    private fun flashStreamed(
        partition: String,
        file: File,
        maxDownload: Long,
        sparse: Boolean,
        onLog: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)?
    ): Boolean {
        return if (sparse) {
            flashSparseBundles(partition, file, maxDownload, onLog, onProgress)
        } else {
            flashRawAsSparseBundles(partition, file, maxDownload, onLog, onProgress)
        }
    }

    private fun formatMb(bytes: Long): String =
        "${"%.1f".format(bytes.toDouble() / (1024.0 * 1024.0))} MB"

    private fun getMaxDownloadSize(): Long {
        try {
            val res = executeGenericCommand("getvar:max-download-size")
            if (!res.isNullOrBlank()) {
                val hasHexPrefix = res.contains("0x", ignoreCase = true)
                val named = Regex("(?i)max-download-size\\s*:?\\s*(?:0x)?([0-9a-fA-F]+)").find(res)
                val rawVal = named?.groupValues?.get(1)
                var parsed = if (rawVal != null) {
                    if (hasHexPrefix || rawVal.any { it in 'a'..'f' || it in 'A'..'F' } || rawVal.length <= 8) {
                        rawVal.toLongOrNull(16) ?: rawVal.toLongOrNull(10)
                    } else {
                        rawVal.toLongOrNull(10) ?: rawVal.toLongOrNull(16)
                    }
                } else null

                // Form 2: value on the token line itself, e.g. "OKAY0x10000000".
                if (parsed == null) {
                    val ok = res.indexOf("OKAY")
                    if (ok >= 0) {
                        val tail = res.substring(ok + 4).trim().lowercase().removePrefix("0x").trim()
                        parsed = Regex("^([0-9a-f]+)").find(tail)?.groupValues?.get(1)?.toLongOrNull(16)
                    }
                }

                if (parsed != null && parsed >= 4L * 1024 * 1024) {
                    return minOf(parsed, MAX_BUNDLE_CAP)
                }
            }
        } catch (_: Exception) {}
        return minOf(512L * 1024 * 1024L, MAX_BUNDLE_CAP)
    }

    private fun sendDownloadCommandWithRetry(payloadSize: Long, onLog: (String) -> Unit): Boolean {
        val hexSize = String.format("%08x", payloadSize)
        val cmd = "download:$hexSize"
        for (attempt in 1..3) {
            drainInEndpoint()
            try {
                write(cmd)
            } catch (e: Exception) {
                if (attempt == 3) {
                    onLog("[ERROR] USB write failed sending download command: ${e.message}")
                    return false
                }
                Thread.sleep(50)
                continue
            }
            val resp = readResponse(8000)
            // Devices reply DATA<hexsize> and some older ones just OKAY; both mean
            // "ready to receive <payloadSize> bytes".
            if (resp.hasTokenAtLineStart("DATA") || resp.hasTokenAtLineStart("OKAY")) return true
            if (resp.hasTokenAtLineStart("FAIL")) {
                onLog("[ERROR] Device rejected download ($hexSize bytes): ${resp.trim()}")
                return false
            }
            if (attempt < 3) {
                try { Thread.sleep(50) } catch (_: Exception) {}
            }
        }
        onLog("[ERROR] No DATA response from fastboot for download:$hexSize")
        return false
    }

    private fun flashSinglePayload(
        partition: String,
        file: File,
        payloadSize: Long,
        onLog: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)?
    ): Boolean {
        if (!sendDownloadCommandWithRetry(payloadSize, onLog)) return false

        try {
            val tracker = if (payloadSize >= 4 * 1024 * 1024L) ThroughputTracker(payloadSize, onLog) else null
            FileInputStream(file).use { fis ->
                val buf = ByteArray(64 * 1024)
                val pw = PayloadWriter()
                var sent = 0L
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    pw.add(buf, 0, n)
                    sent += n
                    onProgress?.invoke(sent, payloadSize)
                    tracker?.update(sent)
                }
                pw.finish()
            }
        } catch (e: Exception) {
            onLog("[ERROR] USB streaming error: ${e.message}")
            return false
        }

        val ack = readResponse(60000)
        if (!ack.hasTokenAtLineStart("OKAY")) {
            onLog("[ERROR] Device did not accept the data: ${ack.trim().ifEmpty { "no response" }}")
            return false
        }

        write("flash:$partition")
        val resp = readResponse(300000)
        onLog("  → ${resp.trim().ifEmpty { "no response" }}")
        return resp.hasTokenAtLineStart("OKAY")
    }

    // ------------------------------------------------------------------------
    //  Temporary boot (fastboot boot)
    // ------------------------------------------------------------------------

    /**
     * Temporarily boots [image] with `fastboot boot` — the image is downloaded to
     * RAM and executed; nothing is written to flash. Used to bring up a custom
     * recovery without replacing the installed one.
     */
    fun bootImage(image: File, onLog: (String) -> Unit): Boolean {
        if (!image.exists() || !image.isFile) {
            onLog("[ERROR] Image file not found: ${image.name}")
            return false
        }
        val size = image.length()
        if (size <= 0L) {
            onLog("[ERROR] Image file is empty: ${image.name}")
            return false
        }
        val maxDownload = getMaxDownloadSize()
        if (size > maxDownload) {
            onLog(tr("[ERROR] Image ${formatMb(size)} > device max-download-size ${formatMb(maxDownload)} — the image must be smaller to boot.", "[ERROR] Image ${formatMb(size)} > device max-download-size ${formatMb(maxDownload)} — boot ke liye image choti honi chahiye."))
            return false
        }
        return try {
            onLog("[BOOT] ${image.name}  (${formatMb(size)}, temporary boot)")
            if (!sendDownloadCommandWithRetry(size, onLog)) return false

            FileInputStream(image).use { fis ->
                val buf = ByteArray(64 * 1024)
                val pw = PayloadWriter()
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    pw.add(buf, 0, n)
                }
                pw.finish()
            }

            val ack = readResponse(60000)
            if (!ack.hasTokenAtLineStart("OKAY")) {
                onLog("[ERROR] Device did not accept the boot image: ${ack.trim().ifEmpty { "no response" }}")
                return false
            }

            write("boot")
            // The bootloader usually replies OKAY and then immediately starts
            // booting (USB may drop before the reply is read), so only a FAIL
            // counts as an error — we verify the real result via recovery ADB.
            val resp = try { readResponse(90000) } catch (_: Exception) { "" }
            onLog("  → ${resp.trim().ifEmpty { "(device booting…)" }}")
            !resp.hasTokenAtLineStart("FAIL")
        } catch (e: Exception) {
            onLog("[ERROR] fastboot boot failed: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------------
    //  Sparse bundle streaming
    // ------------------------------------------------------------------------

    private class SparseChunk(
        var type: Int,
        var blocks: Long,
        var totalSize: Long,
        var dataOffset: Long
    )

    /** A chunk is a 12-byte header + `totalSize - 12` bytes of payload. */
    private val SparseChunk.dataLen: Long get() = totalSize - SPARSE_CHUNK_HEADER_SIZE

    private class SplitOutcome(
        val splitFlag: Boolean,
        val sparsePart: Int,
        val remainder: SparseChunk?,
        val newBackedSize: Long
    )

    /**
     * Faithful Kotlin port of `split_sparse_chunk()` from the ROM's own
     * `flash_gen_crc_list.py` (which mirrors AOSP `move_chunks_up_to_len`).
     *
     * Mutates [bck] down to the portion that stays in the current part and
     * returns the remainder (if the chunk had to be split) plus the updated
     * running part size. `sparsePart` tells the caller whether a new part starts
     * before (1) or after (2) adding [bck], or not at all (0).
     *
     * Matching these boundaries exactly is what makes the bootloader's per-part
     * `crc32(expanded data)` equal the values in `sparsecrclist.txt`.
     */
    private fun splitOneChunk(
        backedSize: Long,
        bck: SparseChunk,
        blockSize: Long,
        splitMaxLen: Long
    ): SplitOutcome {
        var backedReal = backedSize - SPARSE_CHUNK_HEADER_SIZE - 4L
        var overFlag = false

        if (backedReal + bck.dataLen <= splitMaxLen) {
            return SplitOutcome(false, 0, null, backedSize + bck.totalSize)
        } else if (backedReal >= (splitMaxLen * 7L) / 8L) {
            if (bck.totalSize <= splitMaxLen) {
                return SplitOutcome(
                    false, 1, null,
                    bck.totalSize + SPARSE_HEADER_SIZE + SPARSE_CHUNK_HEADER_SIZE
                )
            } else {
                overFlag = true
                backedReal = 0L
            }
        }

        val splitBytes = blockSize * ((splitMaxLen - backedReal) / blockSize)
        val remDataLen = bck.dataLen - splitBytes
        val remainder = SparseChunk(
            bck.type,
            (remDataLen + SPARSE_CHUNK_HEADER_SIZE) / blockSize,
            remDataLen + SPARSE_CHUNK_HEADER_SIZE,
            bck.dataOffset + splitBytes
        )

        bck.totalSize = splitBytes + SPARSE_CHUNK_HEADER_SIZE
        bck.blocks = bck.totalSize / blockSize

        return if (overFlag) {
            SplitOutcome(true, 1, remainder, bck.totalSize)
        } else {
            SplitOutcome(true, 2, remainder, SPARSE_CHUNK_HEADER_SIZE + SPARSE_HEADER_SIZE)
        }
    }

    /**
     * Splits a parsed sparse image into the exact download parts a PC fastboot
     * (and MTK's CRC-list generator) would use, each at most [maxDownload] bytes.
     *
     * Every part is a block-aligned run of (possibly truncated) source chunks;
     * the caller wraps each in a fresh sparse header with a leading/trailing
     * DONT_CARE so the device writes at the correct absolute offset.
     */
    private fun splitIntoFirmwareParts(
        chunks: List<SparseChunk>,
        blockSize: Long,
        maxDownload: Long
    ): List<List<SparseChunk>> {
        val splitMaxLen = maxDownload - SPARSE_OVERHEAD
        if (splitMaxLen <= 0L || blockSize <= 0L) return listOf(chunks)

        val parts = ArrayList<MutableList<SparseChunk>>()
        var current = ArrayList<SparseChunk>()
        parts.add(current)
        var backedSize = SPARSE_HEADER_SIZE

        for (orig in chunks) {
            var working = SparseChunk(orig.type, orig.blocks, orig.totalSize, orig.dataOffset)
            while (true) {
                val outcome = splitOneChunk(backedSize, working, blockSize, splitMaxLen)
                if (outcome.sparsePart == 1) {
                    current = ArrayList()
                    parts.add(current)
                }
                current.add(working)
                if (outcome.sparsePart == 2) {
                    current = ArrayList()
                    parts.add(current)
                }
                backedSize = outcome.newBackedSize
                if (!outcome.splitFlag) break
                working = outcome.remainder!!
            }
        }
        return parts.filter { it.isNotEmpty() }
    }

    /** Bundle cap for streamed flashes: previously proven safe value, else a
     *  conservative first-bundle size. Never above the device's advertised max
     *  and never above a size that already stalled this session. */
    private fun initBundleCap(maxDownload: Long): Long {
        if (provenBundleCap <= 0L) {
            provenBundleCap = minOf(maxDownload, MAX_BUNDLE_CAP, SAFE_INITIAL_CAP)
        }
        var cap = minOf(provenBundleCap, maxDownload, MAX_BUNDLE_CAP)
        if (safeBundleCeiling > 0L) cap = minOf(cap, safeBundleCeiling)
        return cap
    }

    /** Halves the proven cap and pins a ceiling so growth can't undo the shrink.
     *  Survives USB re-opens because it lives in the companion object. */
    private fun shrinkProvenCap(): Long {
        val prev = if (provenBundleCap > 0L) provenBundleCap else SAFE_INITIAL_CAP
        provenBundleCap = maxOf(prev / 2, MIN_BUNDLE_CAP)
        safeBundleCeiling = if (safeBundleCeiling > 0L) {
            minOf(safeBundleCeiling, provenBundleCap)
        } else {
            provenBundleCap
        }
        return provenBundleCap
    }

    /** Doubles the proven cap up to the device max, but never past a size that
     *  has already stalled (the ceiling set by [shrinkProvenCap]). */
    private fun growProvenCap(maxDownload: Long): Long {
        val prev = if (provenBundleCap > 0L) provenBundleCap else SAFE_INITIAL_CAP
        var next = minOf(prev * 2, maxDownload, MAX_BUNDLE_CAP)
        if (safeBundleCeiling > 0L) next = minOf(next, safeBundleCeiling)
        provenBundleCap = next
        return provenBundleCap
    }

    /** Streams an existing Android sparse image in max-download-size bundles. */
    private fun flashSparseBundles(
        partition: String,
        file: File,
        maxBundleSize: Long,
        onLog: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)?
    ): Boolean {
        val fileSize = file.length()
        val fis = FileInputStream(file)
        val channel = fis.channel

        try {
            val headerBuf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            readFully(channel, headerBuf, 28)
            headerBuf.flip()

            val magic = headerBuf.int
            if (magic != SPARSE_MAGIC) {
                onLog("[ERROR] Not a valid sparse image: ${file.name}")
                return false
            }

            val majorVer = headerBuf.short.toInt() and 0xFFFF
            val minorVer = headerBuf.short.toInt() and 0xFFFF
            val fileHeaderSize = headerBuf.short.toInt() and 0xFFFF
            val chunkHeaderSize = headerBuf.short.toInt() and 0xFFFF
            val blockSize = headerBuf.int.toLong() and 0xFFFFFFFFL
            // Total blocks of the FULL expanded image. Every streamed bundle must
            // declare this (not its own end) and pad the untouched regions with
            // DONT_CARE chunks — exactly what AOSP sparse_file_resparse emits.
            val fullBlocks = headerBuf.int.toLong() and 0xFFFFFFFFL
            val totalChunks = headerBuf.int.toLong() and 0xFFFFFFFFL

            if (blockSize <= 0 || totalChunks <= 0 || fullBlocks <= 0L) {
                onLog("[ERROR] Invalid sparse header in ${file.name}")
                return false
            }

            val chunks = ArrayList<SparseChunk>()
            var offset = fileHeaderSize.toLong()
            for (i in 0 until totalChunks) {
                val cBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                channel.position(offset)
                readFully(channel, cBuf, 12)
                cBuf.flip()
                val cType = cBuf.short.toInt() and 0xFFFF
                cBuf.short
                val cBlocks = cBuf.int.toLong() and 0xFFFFFFFFL
                val cTotal = cBuf.int.toLong() and 0xFFFFFFFFL
                chunks.add(SparseChunk(cType, cBlocks, cTotal, offset + chunkHeaderSize))
                if (cTotal < 12) {
                    onLog("[ERROR] Corrupt sparse chunk in ${file.name}")
                    return false
                }
                offset += cTotal
            }
            onLog("[SPARSE] ${file.name}: ${chunks.size} chunks, block size $blockSize")

            // Split the image into the exact same parts a PC fastboot (and the
            // ROM's own flash_gen_crc_list.py) would produce. CRC-checked
            // bootloaders (MTK/Xiaomi "sparsecrclist.txt") verify each received
            // part's crc32 of the *expanded* data, so the part boundaries must
            // match to the byte — otherwise: "failed to check sparse crc".
            val parts = splitIntoFirmwareParts(chunks, blockSize, maxBundleSize)
            onLog("[SPARSE] ${file.name}: split into ${parts.size} part(s) at max ${formatMb(maxBundleSize)}")

            val tracker = if (fileSize >= 4 * 1024 * 1024L) ThroughputTracker(fileSize, onLog) else null
            var startBlock = 0L
            var bundleIdx = 1
            var sentApprox = 0L

            for (part in parts) {
                val partBlocks = part.sumOf { it.blocks }
                val endBlock = startBlock + partBlocks
                val leadingBlocks = startBlock
                val trailingBlocks = (fullBlocks - endBlock).coerceAtLeast(0L)
                val totalChunkCount = part.size +
                        (if (leadingBlocks > 0L) 1 else 0) +
                        (if (trailingBlocks > 0L) 1 else 0)
                val partData = part.sumOf { it.dataLen }
                val payloadSize = SPARSE_HEADER_SIZE +
                        totalChunkCount * SPARSE_CHUNK_HEADER_SIZE + partData

                onLog("[SPARSE] Bundle $bundleIdx — ${formatMb(payloadSize)}, blocks $startBlock..${endBlock - 1} of $fullBlocks")
                if (!sendDownloadCommandWithRetry(payloadSize, onLog)) {
                    onLog("[ERROR] Bundle $bundleIdx download rejected by device.")
                    return false
                }

                try {
                    val pw = PayloadWriter()
                    // Sparse header: total_blks MUST be the FULL image size (like
                    // AOSP sparse_file_resparse). Declaring only this bundle's end
                    // makes the bootloader reject the image and stall the endpoint.
                    val header = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                    header.putInt(SPARSE_MAGIC)
                    header.putShort(majorVer.toShort())
                    header.putShort(minorVer.toShort())
                    header.putShort(28)
                    header.putShort(12)
                    header.putInt(blockSize.toInt())
                    header.putInt(fullBlocks.toInt())
                    header.putInt(totalChunkCount)
                    header.putInt(0)
                    header.flip()
                    pw.add(header.array(), 0, 28)

                    // Leading DONT_CARE: leave blocks written by earlier bundles.
                    if (leadingBlocks > 0L) {
                        val dc = dontCareBytes(leadingBlocks)
                        pw.add(dc, 0, 12)
                    }

                    val copyBuf = ByteArray(64 * 1024)
                    for (c in part) {
                        val cHead = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                        cHead.putShort(c.type.toShort())
                        cHead.putShort(0)
                        cHead.putInt(c.blocks.toInt())
                        cHead.putInt(c.totalSize.toInt())
                        cHead.flip()
                        pw.add(cHead.array(), 0, 12)

                        val dataLen = c.dataLen
                        if (dataLen > 0L) {
                            channel.position(c.dataOffset)
                            var remaining = dataLen
                            while (remaining > 0L) {
                                val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                                val bb = ByteBuffer.wrap(copyBuf, 0, toRead)
                                val read = channel.read(bb)
                                if (read <= 0) throw IOException("Unexpected EOF while reading sparse chunk")
                                pw.add(copyBuf, 0, read)
                                remaining -= read
                                sentApprox += read
                                onProgress?.invoke(sentApprox, fileSize)
                                tracker?.update(sentApprox)
                            }
                        }
                    }

                    // Trailing DONT_CARE: pad to the full image so total_blks equals
                    // the sum of every chunk size (required by the sparse format).
                    if (trailingBlocks > 0L) {
                        val dc = dontCareBytes(trailingBlocks)
                        pw.add(dc, 0, 12)
                    }

                    // Single aligned flush: the only short packet is the final one.
                    pw.finish()
                } catch (e: Exception) {
                    onLog("[ERROR] Bundle $bundleIdx write failed (${e.message}).")
                    return false
                }

                val ack = readResponse(60000)
                if (!ack.hasTokenAtLineStart("OKAY")) {
                    onLog("[ERROR] Device rejected bundle $bundleIdx data (${ack.trim()}).")
                    return false
                }

                write("flash:$partition")
                val resp = readResponse(300000)
                onLog("  → ${resp.trim().ifEmpty { "no response" }}")
                if (!resp.hasTokenAtLineStart("OKAY")) return false

                startBlock = endBlock
                bundleIdx++
            }

            onLog("[SUCCESS] $partition flashed in ${bundleIdx - 1} sparse part(s).")
            return true
        } finally {
            try { fis.close() } catch (_: Exception) {}
        }
    }

    /**
     * Streams a RAW (non-sparse) image larger than max-download-size by wrapping
     * it in synthetic Android sparse bundles — the same technique PC fastboot
     * uses. Every bundle contains:
     *
     *   • a 28-byte sparse header whose total_blks is the FULL image block count
     *     (matching AOSP sparse_file_resparse), so the bootloader accepts it;
     *   • an optional leading DONT_CARE gap chunk covering the blocks already
     *     written by earlier bundles (this preserves the absolute offsets);
     *   • one RAW chunk holding this bundle's slice of the image;
     *   • an optional trailing DONT_CARE chunk padding back up to the full image
     *     so the sum of chunk sizes equals total_blks.
     *
     * Streaming the raw bytes directly (the old behaviour) is rejected by the
     * bootloader with a bare "FAIL", which is exactly the super-image bug.
     */
    private fun flashRawAsSparseBundles(
        partition: String,
        file: File,
        maxBundleSize: Long,
        onLog: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)?
    ): Boolean {
        val fileSize = file.length()
        val blockSize = 4096L
        val totalBlocks = (fileSize + blockSize - 1L) / blockSize
        if (totalBlocks <= 0L) {
            onLog("[ERROR] Image is empty: ${file.name}")
            return false
        }

        // Sparse header (28) + leading DONT_CARE (12) + RAW chunk (12) + trailing DONT_CARE (12)
        val overhead = 28L + 12L + 12L + 12L
        val cap = initBundleCap(maxBundleSize)
        val deviceMax = minOf(maxBundleSize, cap)
        var blocksPerBundle = ((deviceMax - overhead) / blockSize).coerceAtLeast(1L)

        val estBundles = (totalBlocks + blocksPerBundle - 1L) / blocksPerBundle
        onLog("[PC FASTBOOT] Sending sparse '$partition' in ~$estBundles parts of ${formatMb(deviceMax)} " +
                "(image ${formatMb(fileSize)})...")

        val tracker = if (fileSize >= 4 * 1024 * 1024L) ThroughputTracker(fileSize, onLog) else null
        val fis = FileInputStream(file)
        val channel = fis.channel
        val copyBuf = ByteArray(512 * 1024)

        try {
            var startBlock = 0L
            var bundleIdx = 1

            while (startBlock < totalBlocks) {
                val bundleBlocks = minOf(blocksPerBundle, totalBlocks - startBlock)
                val dataBytes = bundleBlocks * blockSize
                val endBlock = startBlock + bundleBlocks
                val hasGap = startBlock > 0L
                val hasTail = endBlock < totalBlocks
                val totalChunks = 1 + (if (hasGap) 1 else 0) + (if (hasTail) 1 else 0)
                val payloadSize = 28L + totalChunks * 12L + dataBytes

                onLog("[CHUNK $bundleIdx] blocks $startBlock..${endBlock - 1} (${formatMb(payloadSize)})")

                if (!sendDownloadCommandWithRetry(payloadSize, onLog)) {
                    if (blocksPerBundle > 1L) {
                        onLog("[WARN] Device rejected sparse bundle. Shrinking to ${formatMb(shrinkProvenCap())}.")
                    }
                    onLog("[ERROR] Download rejected for $partition (block $startBlock)")
                    return false
                }

                // 28-byte sparse header. total_blks MUST be the FULL image size
                // (as AOSP sparse_file_resparse emits) or the bootloader rejects
                // the bundle and stalls the endpoint.
                val pw = PayloadWriter()
                val header = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                header.putInt(SPARSE_MAGIC)
                header.putShort(1)                                   // major version
                header.putShort(0)                                   // minor version
                header.putShort(28)                                  // file header size
                header.putShort(12)                                  // chunk header size
                header.putInt(blockSize.toInt())
                header.putInt(totalBlocks.toInt())                   // full image total blocks
                header.putInt(totalChunks)
                header.putInt(0)                                     // checksum
                header.flip()
                pw.add(header.array(), 0, 28)

                // Leading DONT_CARE: leave blocks written by earlier bundles.
                if (hasGap) {
                    val dc = dontCareBytes(startBlock)
                    pw.add(dc, 0, 12)
                }

                // RAW chunk header for this bundle's data.
                val rc = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                rc.putShort(CHUNK_TYPE_RAW.toShort())
                rc.putShort(0)
                rc.putInt(bundleBlocks.toInt())
                rc.putInt((12L + dataBytes).toInt())
                rc.flip()
                pw.add(rc.array(), 0, 12)

                // Stream the data. Non block-aligned tails are zero-padded so the
                // RAW chunk size always matches its declared block count.
                channel.position(startBlock * blockSize)
                var remaining = dataBytes
                while (remaining > 0L) {
                    val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                    val bb = ByteBuffer.wrap(copyBuf, 0, toRead)
                    val curPos = channel.position()
                    val read = channel.read(bb)
                    if (read > 0) {
                        pw.add(copyBuf, 0, read)
                        remaining -= read
                    } else if (curPos >= fileSize) {
                        // Non block-aligned image tail: zero-pad only past actual EOF
                        java.util.Arrays.fill(copyBuf, 0, toRead, 0)
                        pw.add(copyBuf, 0, toRead)
                        remaining -= toRead
                    } else {
                        throw IOException("Unexpected EOF reading ${file.name} at offset $curPos / $fileSize")
                    }
                    val written = dataBytes - remaining
                    val curSent = minOf(startBlock * blockSize + written, fileSize)
                    onProgress?.invoke(curSent, fileSize)
                    tracker?.update(curSent)
                }

                // Trailing DONT_CARE: pad to the full image so total_blks equals
                // the sum of all chunk sizes (required by the sparse format).
                if (hasTail) {
                    val dc = dontCareBytes(totalBlocks - endBlock)
                    pw.add(dc, 0, 12)
                }

                // Single aligned flush: the only short packet is the final one.
                pw.finish()

                val ack = readResponse(60000)
                if (!ack.hasTokenAtLineStart("OKAY")) {
                    onLog("[ERROR] Device rejected sparse data for $partition at block $startBlock: " +
                            ack.trim().ifEmpty { "no response" })
                    if (blocksPerBundle > 1L) shrinkProvenCap()
                    return false
                }

                write("flash:$partition")
                val resp = readResponse(300000)
                if (!resp.hasTokenAtLineStart("OKAY")) {
                    onLog("[ERROR] Flashing $partition failed at block $startBlock: ${resp.trim().ifEmpty { "no response" }}")
                    return false
                }
                onLog("[CHUNK $bundleIdx] Flashed → OKAY")

                startBlock += bundleBlocks
                bundleIdx++

                // Adaptively grow the bundle size (up to the device max).
                val grown = minOf(growProvenCap(maxBundleSize), maxBundleSize)
                blocksPerBundle = ((grown - overhead) / blockSize).coerceAtLeast(1L)
            }

            onLog("[SUCCESS] $partition successfully flashed in ${bundleIdx - 1} sparse bundle(s)!")
            return true
        } finally {
            try { fis.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(channel: java.nio.channels.FileChannel, buf: ByteBuffer, len: Int) {
        var read = 0
        while (read < len) {
            val n = channel.read(buf)
            if (n <= 0) throw IOException("Unexpected EOF reading image")
            read += n
        }
    }

    override fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {}
        try {
            connection.close()
        } catch (_: Exception) {}
    }
}

/**
 * Stateless convenience wrappers that open a short-lived session per call.
 * Prefer [FastbootSession] when issuing several commands.
 */
object FastbootProtocol {

    fun getVariable(usbManager: UsbManager, device: UsbDevice, varName: String): String? {
        val session = FastbootSession.open(usbManager, device) ?: return null
        return try {
            session.getVariable(varName)
        } finally {
            session.close()
        }
    }

    fun executeGenericCommand(
        usbManager: UsbManager,
        device: UsbDevice,
        command: String
    ): String? {
        val session = FastbootSession.open(usbManager, device) ?: return null
        return try {
            session.executeGenericCommand(command)
        } finally {
            session.close()
        }
    }
}
