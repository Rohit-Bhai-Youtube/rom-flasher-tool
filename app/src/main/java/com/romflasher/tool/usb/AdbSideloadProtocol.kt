package com.romflasher.tool.usb

import android.content.Context
import android.hardware.usb.*
import android.os.Environment
import android.util.Base64
import com.romflasher.tool.AppContext
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.BitSet
import javax.crypto.Cipher

/**
 * ============================================================================
 *  ADB OVER USB — NATIVE "sideload-host" IMPLEMENTATION
 * ============================================================================
 *
 * This mirrors what `adb sideload rom.zip` does on a PC, but talks to the
 * target phone directly over USB-OTG (no PC, no root, no helper daemon).
 *
 * Wire protocol (identical to AOSP `adb/client/commandline.cpp`):
 *
 *   1. ADB handshake  : CNXN  ->  (optional AUTH)  ->  CNXN reply
 *   2. Open service   : OPEN  "sideload-host:<totalBytes>:<blockBytes>"  ->  OKAY
 *   3. The device then repeatedly sends an 8-byte ASCII request:
 *         - eight decimal digits  = the block number it wants, or
 *         - "DONEDONE"            = transfer finished successfully, or
 *         - "FAILFAIL"            = transfer failed
 *   4. For each block request we stream back exactly that block of the zip
 *      (the last block may be partial). "DONEDONE" ends the session.
 *
 * Notes:
 *  - Recovery's `minadbd` does NOT require authentication, but we implement
 *    the AUTH exchange anyway so the same engine also works against a normal
 *    (authorized) `adbd` and never hangs if a device asks for a key.
 *  - Every low level transfer has a timeout and the whole session is bounded,
 *    so a silent/misconfigured device surfaces a clear error instead of an
 *    endless spinner.
 */
class AdbSideloadProtocol private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint
) : AutoCloseable {

    companion object {
        // ADB packet commands (little-endian ASCII, i.e. "CNXN" -> 0x4E584E43)
        private const val A_CNXN = 0x4E584E43 // "CNXN"
        private const val A_OPEN = 0x4E45504F // "OPEN"
        private const val A_OKAY = 0x59414B4F // "OKAY"
        private const val A_WRTE = 0x45545257 // "WRTE"
        private const val A_CLSE = 0x45534C43 // "CLSE"
        private const val A_AUTH = 0x48545541 // "AUTH"

        private const val ADB_AUTH_TOKEN = 1
        private const val ADB_AUTH_SIGNATURE = 2
        private const val ADB_AUTH_RSAPUBLICKEY = 3

        private const val A_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 1024 * 1024
        private const val SIDELOAD_BLOCK_SIZE = 64 * 1024

        // ---- ADB "sync" service (the reliable file-push protocol `adb push` uses) ----
        // Message ids are the little-endian ASCII tags from AOSP file_sync_protocol.h.
        private const val SYNC_ID_SEND = 0x444E4553 // "SEND"
        private const val SYNC_ID_RECV = 0x56434552 // "RECV"
        private const val SYNC_ID_DATA = 0x41544144 // "DATA"
        private const val SYNC_ID_DONE = 0x454E4F44 // "DONE"
        private const val SYNC_ID_OKAY = 0x59414B4F // "OKAY"
        private const val SYNC_ID_FAIL = 0x4C494146 // "FAIL"
        private const val SYNC_DATA_MAX = 64 * 1024

        // Android's synchronous bulkTransfer is unreliable for big buffers and can return -1 on
        // some kernels.  We *prefer* a large transfer (fewer USB round-trips = faster) but
        // automatically drop to a small, always-safe size the first time a big transfer fails, and
        // remember what actually worked for the rest of the connection.
        private const val USB_CHUNK_PREFERRED = 64 * 1024
        private const val USB_CHUNK_MIN = 16 * 1024
        private const val USB_WRITE_TIMEOUT_MS = 60000
        private const val USB_WRITE_RETRIES = 4

        // The same unreliability applies to *reads*: a single bulkTransfer of a whole ADB payload
        // (up to 1 MiB) can return -1, which truncated large pulls. Reads are split too.
        private const val USB_READ_TIMEOUT_MS = 30000
        private const val USB_READ_RETRIES = 6

        /** AOSP minadbd termination markers sent instead of a block number. */
        private const val MINADBD_EXIT_SUCCESS = "DONEDONE"
        private const val MINADBD_EXIT_FAILURE = "FAILFAIL"

        private const val PUBLIC_KEY_NAME = "romflasher-tool@android"

        // Persistent ADB host key.  A *stable* key is what lets the target phone remember this
        // host: after the user taps "Always allow from this computer" once, adbd stores the key
        // and future reconnects authenticate silently (no "Allow USB debugging?" popup).
        private const val KEY_PREFS = "romflasher_adb_host_key"
        private const val PREF_PRIVATE = "rsa_private_pkcs8"
        private const val PREF_PUBLIC = "rsa_public_x509"

        private val VALID_COMMANDS = setOf(A_CNXN, A_OPEN, A_OKAY, A_WRTE, A_CLSE, A_AUTH)

        @Volatile
        private var sharedKeyPair: KeyPair? = null

        /**
         * Returns the persisted ADB host key pair, generating + storing it on first use.
         * Falls back to a fresh ephemeral key only if app storage is unavailable.
         */
        internal fun getSharedRsaKeyPair(): KeyPair {
            sharedKeyPair?.let { return it }
            synchronized(AdbSideloadProtocol::class.java) {
                sharedKeyPair?.let { return it }
                val context = AppContext.context
                val loaded = context?.let { loadStoredKeyPair(it) }
                val keyPair = loaded ?: run {
                    val fresh = createFreshRsaKeyPair()
                    context?.let { saveStoredKeyPair(it, fresh) }
                    fresh
                }
                sharedKeyPair = keyPair
                return keyPair
            }
        }

        private fun loadStoredKeyPair(context: Context): KeyPair? {
            // 1) app-private prefs (fast, always available)
            val fromPrefs = try {
                val prefs = context.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
                val privB64 = prefs.getString(PREF_PRIVATE, null)
                val pubB64 = prefs.getString(PREF_PUBLIC, null)
                if (privB64 != null && pubB64 != null) decodeKeyPair(privB64, pubB64) else null
            } catch (_: Exception) {
                null
            }
            if (fromPrefs != null) return fromPrefs

            // 2) One-time migration from legacy public Downloads file (if an older build wrote it):
            //    import into app-private prefs and immediately remove the plaintext public copy.
            return try {
                val f = legacyMirrorKeyFile()
                if (!f.exists()) return null
                val lines = f.readText().split('\n')
                if (lines.size < 2) return null
                val pair = decodeKeyPair(lines[0].trim(), lines[1].trim()) ?: return null
                saveStoredKeyPair(context, pair)
                try { f.delete() } catch (_: Exception) {}
                pair
            } catch (_: Exception) {
                null
            }
        }

        private fun decodeKeyPair(privB64: String, pubB64: String): KeyPair? {
            return try {
                val factory = KeyFactory.getInstance("RSA")
                val privateKey = factory.generatePrivate(
                    PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP))
                )
                val publicKey = factory.generatePublic(
                    X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP))
                )
                KeyPair(publicKey, privateKey)
            } catch (_: Exception) {
                null
            }
        }

        private fun legacyMirrorKeyFile(): File = File(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ROMFlasher"
            ),
            "adb_host_key.txt"
        )

        private fun saveStoredKeyPair(context: Context, keyPair: KeyPair) {
            val privB64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
            val pubB64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
            try {
                val prefs = context.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(PREF_PRIVATE, privB64)
                    .putString(PREF_PUBLIC, pubB64)
                    .apply()
            } catch (_: Exception) {
            }
            // Security: NEVER write private RSA material into shared Downloads storage.
            try {
                val legacy = legacyMirrorKeyFile()
                if (legacy.exists()) legacy.delete()
            } catch (_: Exception) {
            }
        }

        private fun createFreshRsaKeyPair(): KeyPair {
            return try {
                val kpg = KeyPairGenerator.getInstance("RSA")
                kpg.initialize(2048)
                kpg.generateKeyPair()
            } catch (_: Exception) {
                KeyPairGenerator.getInstance("RSA").generateKeyPair()
            }
        }

        /**
         * Signs an ADB challenge token exactly as AOSP adb does:
         * In native AOSP adb, RSA_sign(NID_sha1, token, 20, ...) is invoked.
         * OpenSSL does NOT re-hash the 20-byte token; it prepends the SHA-1 ASN.1 DigestInfo
         * prefix and applies PKCS#1 v1.5 padding.
         *
         * Standard Java `Signature.getInstance("SHA1withRSA")` would hash the token again,
         * causing double-hashing so adbd's RSA_verify() failed on reconnect and the target
         * phone re-prompted "Allow USB debugging?" every time.
         *
         * By prepending the standard 15-byte SHA-1 DigestInfo prefix and signing via NONEwithRSA
         * (or Cipher with PKCS1Padding), the signature matches adbd's RSA_verify perfectly.
         * When the user taps "Always allow from this computer" once, adbd stores the public key
         * and future connections authenticate silently without ever prompting again!
         */
        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30.toByte(), 0x21.toByte(),
            0x30.toByte(), 0x09.toByte(),
            0x06.toByte(), 0x05.toByte(),
            0x2b.toByte(), 0x0e.toByte(), 0x03.toByte(), 0x02.toByte(), 0x1a.toByte(),
            0x05.toByte(), 0x00.toByte(),
            0x04.toByte(), 0x14.toByte()
        )

        internal fun signAdbToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
            val payload = SHA1_DIGEST_INFO_PREFIX + token
            return try {
                val sig = Signature.getInstance("NONEwithRSA")
                sig.initSign(privateKey)
                sig.update(payload)
                sig.sign()
            } catch (_: Exception) {
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.ENCRYPT_MODE, privateKey)
                cipher.doFinal(payload)
            }
        }

        fun open(usbManager: UsbManager, device: UsbDevice): AdbSideloadProtocol? {
            val connection = try {
                usbManager.openDevice(device)
            } catch (_: Exception) {
                null
            } ?: return null

            // PASS 1: true ADB interface (Class 0xFF / Subclass 0x42 / Protocol 0x01)
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val isAdbClass = (iface.interfaceClass == 255 || iface.interfaceClass == 0xFF)
                val isAdbSubclass = (iface.interfaceSubclass == 66 || iface.interfaceSubclass == 0x42)
                val isFastboot = (iface.interfaceProtocol == 3 || iface.interfaceProtocol == 0x03)

                if (isAdbClass && isAdbSubclass && !isFastboot) {
                    endpointsOf(iface)?.let { (inEp, outEp) ->
                        try {
                            if (connection.claimInterface(iface, true)) {
                                return AdbSideloadProtocol(connection, iface, inEp, outEp)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            // PASS 2: any vendor (0xFF) bulk interface (reject Fastboot protocol 0x03)
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val isFastboot = (iface.interfaceProtocol == 3 || iface.interfaceProtocol == 0x03)
                if ((iface.interfaceClass == 255 || iface.interfaceClass == 0xFF) && !isFastboot) {
                    endpointsOf(iface)?.let { (inEp, outEp) ->
                        try {
                            if (connection.claimInterface(iface, true)) {
                                return AdbSideloadProtocol(connection, iface, inEp, outEp)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            // PASS 3: any interface that exposes a bulk IN + bulk OUT pair (reject Fastboot)
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val isFastboot = (iface.interfaceProtocol == 3 || iface.interfaceProtocol == 0x03)
                if (isFastboot) continue
                endpointsOf(iface)?.let { (inEp, outEp) ->
                    try {
                        if (connection.claimInterface(iface, true)) {
                            return AdbSideloadProtocol(connection, iface, inEp, outEp)
                        }
                    } catch (_: Exception) {}
                }
            }

            try { connection.close() } catch (_: Exception) {}
            return null
        }

        private fun endpointsOf(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
                }
            }
            return if (inEp != null && outEp != null) Pair(inEp, outEp) else null
        }

        /** Diagnostic: explains why [open] would fail (missing USB permission vs interface claim). */
        fun describeOpenFailure(usbManager: UsbManager, device: UsbDevice): String {
            val hasPerm = try {
                usbManager.hasPermission(device)
            } catch (_: Exception) {
                false
            }
            if (!hasPerm) return "USB permission not granted"

            val connection = try {
                usbManager.openDevice(device)
            } catch (e: Exception) {
                null
            } ?: return "openDevice() returned null"

            val sb = StringBuilder("interface claim failed: ")
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val claimed = try {
                    connection.claimInterface(iface, true)
                } catch (_: Exception) {
                    false
                }
                sb.append(
                    "{#$i cls=${iface.interfaceClass} sub=${iface.interfaceSubclass} " +
                        "proto=${iface.interfaceProtocol} eps=${iface.endpointCount} claim=$claimed} "
                )
                if (claimed) {
                    try { connection.releaseInterface(iface) } catch (_: Exception) {}
                }
            }
            try { connection.close() } catch (_: Exception) {}
            return sb.toString().trim()
        }
    }

    // ------------------------------------------------------------------------
    //  Session state
    // ------------------------------------------------------------------------

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val payload: ByteArray
    )

    @Volatile private var localId = 1
    @Volatile private var remoteId = 0
    private var maxPayload = SIDELOAD_BLOCK_SIZE
    private var deviceBanner = ""

    @Volatile private var streamClosed = false

    /** True once the ADB transport (CNXN + optional RSA auth) is online for this session. */
    @Volatile private var adbConnected = false

    /** Human-readable reason for the last failed [ensureConnected] attempt (for diagnostics). */
    @Volatile var lastConnectError: String = ""
        private set

    /** Incrementing channel id so several services can be opened on the same transport. */
    private var channelIdCounter = 1

    private val packetQueue = ArrayDeque<AdbPacket>()
    private val authKeyPair: KeyPair by lazy { getSharedRsaKeyPair() }

    // Largest USB transfer size that has actually worked on this connection. Starts optimistic
    // (fewer round-trips = faster) and auto-degrades to the safe minimum the first time a
    // bulkTransfer fails, so a picky kernel can never break a transfer.
    private var usbWriteChunk = USB_CHUNK_PREFERRED
    private var usbReadChunk = USB_CHUNK_PREFERRED

    // Bytes received from a WRTE packet but not consumed yet (rare; keeps the byte stream exact).
    // A plain ByteArray instead of ArrayDeque<Byte> so we never box a single byte.
    private var leftoverBuf = ByteArray(0)
    private var leftoverLen = 0

    // ------------------------------------------------------------------------
    //  Low level packet I/O
    // ------------------------------------------------------------------------

    private fun buildAdbHeader(cmd: Int, arg0: Int, arg1: Int, dataLen: Int, crc: Int): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(dataLen)
        buf.putInt(crc)
        buf.putInt(cmd xor -0x1)
        return buf.array()
    }

    private fun calculateCrc(data: ByteArray, len: Int): Int = calculateCrc(data, 0, len)

    private fun calculateCrc(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        for (i in offset until offset + len) {
            sum += data[i].toInt() and 0xFF
        }
        return sum
    }

    /** Reads one whole ADB packet, with a byte-shift resync if the header magic is wrong. */
    private fun readNextAdbPacket(timeoutMs: Int = 30000): AdbPacket? {
        val headerBuf = ByteArray(24)

        // Fully read the 24 byte header (USB may deliver it in pieces).
        var got = 0
        val start = System.currentTimeMillis()
        var hStall = 0
        while (got < 24) {
            val remain = (timeoutMs - (System.currentTimeMillis() - start).toInt()).coerceAtLeast(1)
            val r = connection.bulkTransfer(inEndpoint, headerBuf, got, 24 - got, remain)
            if (r <= 0) {
                if (++hStall > USB_READ_RETRIES) break
                try { Thread.sleep(20L * hStall) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                }
                continue
            }
            hStall = 0
            got += r
        }
        if (got < 24) return null

        var buf = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
        var cmd = buf.int
        var arg0 = buf.int
        var arg1 = buf.int
        var dataLen = buf.int
        buf.int // crc
        var magic = buf.int

        if (magic != (cmd xor -0x1) || cmd !in VALID_COMMANDS) {
            val resyncBuf = ByteArray(maxOf(inEndpoint.maxPacketSize, 512))
            var synced = false
            var syncCount = 0
            while (syncCount < 2048 && !synced) {
                val n = connection.bulkTransfer(inEndpoint, resyncBuf, resyncBuf.size, 200)
                if (n <= 0) break
                for (i in 0 until n) {
                    System.arraycopy(headerBuf, 1, headerBuf, 0, 23)
                    headerBuf[23] = resyncBuf[i]

                    buf = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
                    cmd = buf.int
                    arg0 = buf.int
                    arg1 = buf.int
                    dataLen = buf.int
                    buf.int // crc
                    magic = buf.int

                    syncCount++
                    if (magic == (cmd xor -0x1) && cmd in VALID_COMMANDS) {
                        synced = true
                        break
                    }
                    if (syncCount >= 2048) break
                }
            }
            if (!synced) return null
        }

        if (dataLen < 0 || dataLen > MAX_PAYLOAD + 4096) return null

        val payload = if (dataLen > 0) {
            val pBuf = ByteArray(dataLen)
            var pRead = 0
            var stall = 0
            while (pRead < dataLen) {
                val want = minOf(usbReadChunk, dataLen - pRead)
                val r = connection.bulkTransfer(inEndpoint, pBuf, pRead, want, USB_READ_TIMEOUT_MS)
                if (r <= 0) {
                    // A big buffer can return -1 on some kernels: shrink to the safe minimum
                    // and retry immediately before counting this as a real stall.
                    if (usbReadChunk > USB_CHUNK_MIN) {
                        usbReadChunk = USB_CHUNK_MIN
                        continue
                    }
                    // A read split into small URBs can still hiccup; retry a few times.
                    if (++stall > USB_READ_RETRIES) break
                    try { Thread.sleep(20L * stall) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); break
                    }
                    continue
                }
                stall = 0
                pRead += r
            }
            if (pRead < dataLen) return null
            pBuf
        } else ByteArray(0)

        return AdbPacket(cmd, arg0, arg1, dataLen, payload)
    }

    /** Next packet, preferring anything already buffered (e.g. a request read while acking). */
    private fun nextPacket(timeoutMs: Int): AdbPacket? {
        packetQueue.removeFirstOrNull()?.let { return it }
        return readNextAdbPacket(timeoutMs)
    }

    /**
     * Writes [len] bytes of [data] to the OUT endpoint.
     *
     * Android's synchronous [android.hardware.usb.UsbDeviceConnection.bulkTransfer]
     * is unreliable for large buffers (it can return -1 on many kernels), and a
     * recovery that pauses to write a partition can NAK the bus for a while. So we
     * split every write into small USB transfers, give each one a generous timeout
     * and retry a few times before giving up. Because a bulk endpoint is just a
     * byte stream, splitting one ADB packet across several URBs is transparent to
     * the device.
     *
     * @return the number of bytes actually written (== [len] on success).
     */
    private fun writeAll(data: ByteArray, offset: Int, len: Int): Int {
        var sent = 0
        while (sent < len) {
            val chunk = minOf(usbWriteChunk, len - sent)
            var written = connection.bulkTransfer(outEndpoint, data, offset + sent, chunk, USB_WRITE_TIMEOUT_MS)
            if (written <= 0) {
                // A big buffer can return -1 on some kernels: shrink to the safe minimum
                // and retry immediately before counting this as a real stall.
                if (usbWriteChunk > USB_CHUNK_MIN) {
                    usbWriteChunk = USB_CHUNK_MIN
                    continue
                }
                var attempt = 1
                while (attempt <= USB_WRITE_RETRIES && written <= 0) {
                    try {
                        Thread.sleep(50L * attempt)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return sent
                    }
                    written = connection.bulkTransfer(outEndpoint, data, offset + sent, chunk, USB_WRITE_TIMEOUT_MS)
                    attempt++
                }
            }
            if (written <= 0) return sent
            sent += written
        }
        return sent
    }

    private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray?): Boolean =
        sendPacket(cmd, arg0, arg1, payload, 0, payload?.size ?: 0)

    private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, data: ByteArray?, offset: Int, len: Int): Boolean {
        val crc = if (data != null && len > 0) calculateCrc(data, offset, len) else 0
        val header = buildAdbHeader(cmd, arg0, arg1, len, crc)
        if (writeAll(header, 0, header.size) != header.size) return false
        if (data != null && len > 0 && writeAll(data, offset, len) != len) return false
        return true
    }

    private fun sendClose(): Boolean =
        sendPacket(A_CLSE, localId, remoteId, null)

    // ------------------------------------------------------------------------
    //  ADB handshake (CNXN + optional AUTH)
    // ------------------------------------------------------------------------

    private fun connectAdbSession(): Boolean {
        val banner = "host::\u0000".toByteArray(Charsets.US_ASCII)
        if (!sendPacket(A_CNXN, A_VERSION, MAX_PAYLOAD, banner)) return false

        var sentSignature = false
        var sentPublicKey = false
        var attempts = 0
        while (attempts < 8) {
            attempts++
            val pkt = nextPacket(5000) ?: continue

            when (pkt.command) {
                A_CNXN -> {
                    deviceBanner = String(pkt.payload, Charsets.UTF_8).trim('\u0000', ' ', '\r', '\n')
                    val advertised = pkt.arg1
                    maxPayload = if (advertised in 4096..(1024 * 1024)) advertised else SIDELOAD_BLOCK_SIZE
                    return true
                }
                A_AUTH -> {
                    when (pkt.arg0) {
                        ADB_AUTH_TOKEN -> {
                            when {
                                !sentSignature -> { sentSignature = true; performAuthSign(pkt.payload) }
                                !sentPublicKey -> { sentPublicKey = true; performAuthPublicKey() }
                                else -> return false
                            }
                        }
                        else -> {
                            if (!sentPublicKey) { sentPublicKey = true; performAuthPublicKey() } else return false
                        }
                    }
                }
                A_CLSE -> return false
                else -> { /* ignore stray packets during handshake */ }
            }
        }
        return false
    }

    private fun openAdbService(service: String): Boolean {
        val dest = service.toByteArray(Charsets.US_ASCII)
        if (!sendPacket(A_OPEN, localId, 0, dest)) return false

        var attempts = 0
        while (attempts < 8) {
            attempts++
            val pkt = nextPacket(5000) ?: continue
            when (pkt.command) {
                A_OKAY -> {
                    remoteId = pkt.arg0
                    return true
                }
                A_CLSE -> return false
                A_WRTE -> sendPacket(A_OKAY, localId, pkt.arg0, null)
                else -> { /* ignore */ }
            }
        }
        return false
    }

    private fun performAuthSign(token: ByteArray): Boolean {
        return try {
            val signature = signAdbToken(authKeyPair.private, token)
            sendPacket(A_AUTH, ADB_AUTH_SIGNATURE, 0, signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun performAuthPublicKey(): Boolean {
        return try {
            val blob = androidEncodedPublicKey(authKeyPair)
            val encoded = Base64.encodeToString(blob, Base64.NO_WRAP)
            val payload = (encoded + " " + PUBLIC_KEY_NAME).toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
            sendPacket(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, payload)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Encodes the RSA public key in the Android "RSAPublicKey" precomputed format
     * (524 bytes), which is what an `adbkey.pub` file contains before base64.
     */
    private fun androidEncodedPublicKey(keyPair: KeyPair): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        val e = pub.publicExponent

        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(two32).modInverse(two32).negate().mod(two32)
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n) // r^2 mod n, r = 2^2048

        val words = 2048 / 32 // 64
        val blob = ByteArray(4 + 4 + words * 4 + words * 4 + 4) // 524 bytes

        putLittleEndianInt(blob, 0, words.toLong())
        putLittleEndianInt(blob, 4, n0inv.toLong())
        copyLittleEndian(n, words * 4).copyInto(blob, 8)
        copyLittleEndian(rr, words * 4).copyInto(blob, 8 + words * 4)
        putLittleEndianInt(blob, 8 + words * 8, e.toLong())
        return blob
    }

    private fun putLittleEndianInt(dst: ByteArray, offset: Int, value: Long) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value shr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value shr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun copyLittleEndian(value: BigInteger, size: Int): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until size) {
            val bi = bigEndian.size - 1 - i
            out[i] = if (bi >= 0) bigEndian[bi] else 0
        }
        return out
    }

    // ------------------------------------------------------------------------
    //  ADB stream layer (a service behaves like a byte stream)
    // ------------------------------------------------------------------------

    /** Reads exactly [n] bytes from the service stream, acknowledging WRTE packets on the way. */
    private fun streamReadExactly(n: Int, timeoutMs: Int, ackLocalId: Int = localId): ByteArray? {
        val out = ByteArray(n)
        return if (streamReadInto(out, 0, n, timeoutMs, ackLocalId)) out else null
    }

    /**
     * Same as [streamReadExactly] but fills a caller-owned buffer, so a long transfer can reuse one
     * array instead of allocating a fresh 64 KB block for every packet (less GC → steadier speed).
     */
    private fun streamReadInto(dst: ByteArray, off: Int, n: Int, timeoutMs: Int, ackLocalId: Int): Boolean {
        var have = 0

        // Drain anything left over from a previous packet first.
        if (leftoverLen > 0) {
            val take = minOf(n, leftoverLen)
            System.arraycopy(leftoverBuf, 0, dst, off, take)
            if (take < leftoverLen) System.arraycopy(leftoverBuf, take, leftoverBuf, 0, leftoverLen - take)
            leftoverLen -= take
            have = take
        }
        if (have == n) return true

        val deadline = System.currentTimeMillis() + timeoutMs
        while (have < n) {
            val remain = (deadline - System.currentTimeMillis()).toInt()
            if (remain <= 0) {
                leftoverPushBack(dst, off, have)
                return false
            }

            val pkt = nextPacket(remain)
            if (pkt == null) {
                leftoverPushBack(dst, off, have)
                return false
            }

            when (pkt.command) {
                A_WRTE -> {
                    // Acknowledge the device's write so it can send the next one.
                    sendPacket(A_OKAY, ackLocalId, pkt.arg0, null)
                    val payload = pkt.payload
                    val space = n - have
                    if (payload.size <= space) {
                        System.arraycopy(payload, 0, dst, off + have, payload.size)
                        have += payload.size
                    } else {
                        System.arraycopy(payload, 0, dst, off + have, space)
                        leftoverAppend(payload, space, payload.size - space)
                        have = n
                    }
                }
                A_CLSE -> {
                    streamClosed = true
                    leftoverPushBack(dst, off, have)
                    return false
                }
                else -> { /* OKAY / AUTH / CNXN: nothing to consume */ }
            }
        }
        return true
    }

    /** Puts [len] already-consumed bytes back at the front of the stream so a retry can re-read them. */
    private fun leftoverPushBack(src: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val merged = ByteArray(len + leftoverLen)
        System.arraycopy(src, off, merged, 0, len)
        if (leftoverLen > 0) System.arraycopy(leftoverBuf, 0, merged, len, leftoverLen)
        leftoverBuf = merged
        leftoverLen = merged.size
    }

    /** Appends bytes that arrived but were not asked for yet (rare; keeps the stream byte-exact). */
    private fun leftoverAppend(src: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val need = leftoverLen + len
        if (need > leftoverBuf.size) {
            leftoverBuf = leftoverBuf.copyOf(maxOf(need, leftoverBuf.size * 2, 64 * 1024))
        }
        System.arraycopy(src, off, leftoverBuf, leftoverLen, len)
        leftoverLen = need
    }

    /**
     * Writes [len] bytes of [data] to the service stream, splitting into <= maxPayload WRTE packets.
     *
     * @return the number of payload bytes successfully written (== [len] on success).
     */
    private fun streamWrite(data: ByteArray, len: Int): Int {
        var offset = 0
        while (offset < len) {
            val chunk = minOf(maxPayload, len - offset)
            val crc = calculateCrc(data, offset, chunk)
            val header = buildAdbHeader(A_WRTE, localId, remoteId, chunk, crc)
            if (writeAll(header, 0, header.size) != header.size) return offset
            val written = writeAll(data, offset, chunk)
            if (written < chunk) return offset + written
            offset += chunk
        }
        return offset
    }

    // ------------------------------------------------------------------------
    //  PUBLIC API — Sideload
    // ------------------------------------------------------------------------

    fun executeSideload(zipFile: File, onLog: (String) -> Unit): Boolean {
        val fileSize = zipFile.length()
        if (fileSize <= 0L) {
            onLog("[ERROR] Package file is empty or unreadable: ${zipFile.name}")
            return false
        }

        val blockSize = SIDELOAD_BLOCK_SIZE.toLong()
        val totalBlocks = (fileSize + blockSize - 1) / blockSize

        onLog("Initializing AOSP ADB Sideload (sideload-host protocol)...")
        onLog("Package: ${zipFile.name}  (${fileSize / (1024 * 1024)} MB • $totalBlocks blocks)")

        var fis: FileInputStream? = null
        try {
            fis = FileInputStream(zipFile)
            val channel = fis.channel

            // 1. Connect.
            if (!connectAdbSession()) {
                onLog("[ERROR] No ADB response from the target device.")
                onLog("        → Phone must be in RECOVERY → select 'Apply update from ADB'.")
                onLog("        → Connect it with a working USB-OTG cable, then retry.")
                return false
            }
            onLog("[ADB] Connected. Device: ${deviceBanner.ifBlank { "(recovery / minadbd)" }}")

            // 2. Open the sideload service.
            if (!openAdbService("sideload-host:$fileSize:$blockSize")) {
                onLog("[ERROR] The device refused the 'sideload-host' service.")
                onLog("        → Make sure Recovery shows 'Now send the package you want to apply'.")
                onLog("        → Some stock recoveries only accept their own signed updates.")
                onLog("        → A custom recovery (TWRP / OrangeFox) works best.")
                return false
            }
            onLog("[ADB] Sideload channel open (remote id $remoteId). Serving package data...")
            onLog("[NOTE] If the phone asks to 'Install anyway?' after verification, confirm with the volume/power keys.")

            // 3. Serve blocks until the device says it is done.
            return serveSideloadBlocks(channel, fileSize, blockSize, totalBlocks, onLog)
        } catch (e: Exception) {
            onLog("[ERROR] Sideload failed: ${e.message}")
            return false
        } finally {
            try { fis?.close() } catch (_: Exception) {}
        }
    }

    private fun serveSideloadBlocks(
        channel: FileChannel,
        fileSize: Long,
        blockSize: Long,
        totalBlocks: Long,
        onLog: (String) -> Unit
    ): Boolean {
        val block = ByteArray(blockSize.toInt())
        val served = BitSet(totalBlocks.toInt().coerceAtLeast(1))

        var xfer = 0L
        var uniqueServed = 0
        var lastPercent = -1
        var receivedAnyBlock = false
        var installPhaseLogged = false
        val startTime = System.currentTimeMillis()
        var lastSpeedLogTime = startTime
        var lastSpeedBytes = 0L

        var timeouts = 0
        // The very first request should arrive quickly; later pauses (writing
        // partitions) are normal, so the wait grows once data is flowing.
        var readTimeout = 20000

        while (true) {
            if (streamClosed) {
                if (receivedAnyBlock && uniqueServed >= totalBlocks) {
                    onLog("[SUCCESS] Device closed the sideload channel. Full ROM package delivered ($uniqueServed/$totalBlocks blocks).")
                    return true
                }
                if (receivedAnyBlock) {
                    onLog("[ERROR] Device closed sideload channel early ($uniqueServed/$totalBlocks blocks sent). Verification failed or cancelled.")
                } else {
                    onLog("[ERROR] Device closed the sideload channel before any data was sent.")
                }
                return false
            }

            val cmdBytes = streamReadExactly(8, readTimeout)
            if (cmdBytes == null) {
                timeouts++

                if (!receivedAnyBlock) {
                    if (timeouts >= 3) {
                        onLog("[ERROR] Device never requested any package data.")
                        onLog("        → Is the phone really in Recovery → 'Apply update from ADB'?")
                        onLog("        → Try re-selecting that option, then press FLASH again.")
                        return false
                    }
                    onLog("[ADB] Waiting for recovery to request data... (${timeouts}/3)")
                    continue
                }

                // Data has already flowed: the device is busy verifying / writing.
                if (uniqueServed >= totalBlocks && !installPhaseLogged) {
                    installPhaseLogged = true
                    onLog("[STEP 2/2] All package blocks served. Recovery is verifying & installing (this can take a few minutes)...")
                } else if (timeouts % 4 == 0) {
                    onLog("[ADB] Waiting for the device to continue... keep the cable connected.")
                }
                if (timeouts >= 40) { // ~80 minutes at 120s per read
                    onLog("[ERROR] Timed out waiting for the device to finish.")
                    return false
                }
                readTimeout = 120000
                continue
            }

            timeouts = 0
            val token = String(cmdBytes, Charsets.US_ASCII).trim('\u0000', ' ', '\r', '\n')

            when (token) {
                MINADBD_EXIT_SUCCESS -> {
                    sendClose()
                    onLog("[SUCCESS] Device reported the update finished (DONEDONE).")
                    onLog("        Check the phone screen for the final result.")
                    return true
                }
                MINADBD_EXIT_FAILURE -> {
                    onLog("[ERROR] Device reported the transfer failed (FAILFAIL).")
                    onLog("        → The recovery rejected the package (signature / verification / storage).")
                    return false
                }
            }

            val blockIndex = token.toLongOrNull(10) ?: token.removePrefix("block-").trim().toLongOrNull(10)
            if (blockIndex == null) {
                // Not a block number: surface whatever the recovery said.
                if (token.isNotEmpty()) onLog("[RECOVERY] $token")
                continue
            }

            if (blockIndex < 0L || blockIndex >= totalBlocks) {
                onLog("[ERROR] Device requested block $blockIndex which is outside the package ($totalBlocks blocks).")
                return false
            }

            val offset = blockIndex * blockSize
            val toSend = minOf(blockSize, fileSize - offset).toInt()
            channel.position(offset)
            val buf = ByteBuffer.wrap(block, 0, toSend)
            var read = 0
            while (buf.hasRemaining()) {
                val r = channel.read(buf)
                if (r <= 0) break
                read += r
            }
            if (read < toSend) {
                onLog("[ERROR] Failed to read block $blockIndex from the package file.")
                return false
            }

            val written = streamWrite(block, toSend)
            if (written < toSend) {
                onLog("[ERROR] USB write failed while sending block $blockIndex ($written/$toSend bytes).")
                onLog("[HINT] The USB link stalled or the cable/OTG adapter dropped. " +
                    "Try a different USB cable/adapter and keep the phone in 'Apply update from ADB'.")
                return false
            }

            receivedAnyBlock = true
            readTimeout = 60000
            xfer += toSend
            if (!served.get(blockIndex.toInt())) {
                served.set(blockIndex.toInt())
                uniqueServed++
            }

            val percent = ((uniqueServed.toLong() * 100L) / totalBlocks).toInt().coerceIn(0, 100)
            val now = System.currentTimeMillis()
            if (percent != lastPercent || (now - lastSpeedLogTime >= 2000L && xfer > lastSpeedBytes)) {
                lastPercent = percent
                val elapsed = (now - startTime).coerceAtLeast(1)
                val speedMbS = (xfer.toDouble() / (1024.0 * 1024.0)) / (elapsed / 1000.0)
                val remainingBlocks = (totalBlocks - uniqueServed).coerceAtLeast(0)
                val remainingBytes = remainingBlocks * blockSize
                val etaSec = if (speedMbS > 0.05) (remainingBytes / (speedMbS * 1024.0 * 1024.0)).toLong() else 0L
                val etaStr = if (etaSec > 0) "%02dm %02ds".format(etaSec / 60, etaSec % 60) else "finishing…"
                onLog("[STEP 1/2] Transfer: $percent% (${xfer / (1024 * 1024)} MB sent • $uniqueServed/$totalBlocks blocks) • ⚡ ${"%.1f".format(speedMbS)} MB/s • ⏱ ETA: $etaStr")
                lastSpeedLogTime = now
                lastSpeedBytes = xfer
            }
            if (percent >= 100 && !installPhaseLogged) {
                installPhaseLogged = true
                onLog("[STEP 2/2] All package blocks served. Waiting for recovery to verify & install...")
            }
        }
    }

    // ------------------------------------------------------------------------
    //  PUBLIC API — Reboot / Shell (kept for the Advanced Reboot feature)
    // ------------------------------------------------------------------------

    private fun performAdbAuthHandshake(keyPair: KeyPair, token: ByteArray): Boolean {
        try {
            val signatureBytes = signAdbToken(keyPair.private, token)

            val authSigHeader = buildAdbHeader(A_AUTH, ADB_AUTH_SIGNATURE, 0, signatureBytes.size, calculateCrc(signatureBytes, signatureBytes.size))
            connection.bulkTransfer(outEndpoint, authSigHeader, authSigHeader.size, 5000)
            connection.bulkTransfer(outEndpoint, signatureBytes, signatureBytes.size, 5000)

            var pkt = readNextAdbPacket(5000)

            if (pkt != null && pkt.command == A_AUTH) {
                // Send the public key exactly like AOSP `adbkey.pub`: base64 of the Android
                // 524-byte RSAPublicKey followed by " name" + NUL.  (Sending raw X.509 here
                // made adbd unable to parse/store the key, so "always allow" never stuck.)
                val blob = androidEncodedPublicKey(keyPair)
                val encoded = Base64.encodeToString(blob, Base64.NO_WRAP)
                val pubPayload = (encoded + " " + PUBLIC_KEY_NAME).toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

                val authKeyHeader = buildAdbHeader(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pubPayload.size, calculateCrc(pubPayload, pubPayload.size))
                connection.bulkTransfer(outEndpoint, authKeyHeader, authKeyHeader.size, 5000)
                connection.bulkTransfer(outEndpoint, pubPayload, pubPayload.size, 5000)

                // Recovery (TWRP/OrangeFox) shows an authorize prompt here, so give the user a
                // full minute to tap "Allow" instead of timing out after 10s.
                pkt = readNextAdbPacket(60000)
            }

            return pkt != null && pkt.command == A_CNXN
        } catch (_: Exception) {
            return false
        }
    }

    // Deep Core ADB Reboot Engine (Tries Native Reboot Service + Shell Reboot Service)
    fun executeAdbRebootService(targetMode: String, onLog: ((String) -> Unit)? = null): Boolean {
        try {
            val keyPair = getSharedRsaKeyPair()

            val shownTarget = if (targetMode.isBlank()) "system" else targetMode.lowercase()
            onLog?.invoke("> adb reboot $shownTarget")
            onLog?.invoke("👉 Tip: tap 'Always allow from this computer' once — ROM Flasher remembers the key after that.")

            // Stale bytes left over from the previous command (e.g. the APK install) desync
            // this handshake, so the `reboot:` OPEN below was sent into the void and the phone
            // stayed in Android. Drain first, then retry the CNXN a few times.
            var packet: AdbPacket? = null
            for (attempt in 0 until 3) {
                try { drainInput() } catch (_: Exception) {}

                val cnxnStr = "host::\u0000".toByteArray(Charsets.US_ASCII)
                val cnxnHeader = buildAdbHeader(A_CNXN, 0x01000000, 1048576, cnxnStr.size, calculateCrc(cnxnStr, cnxnStr.size))
                connection.bulkTransfer(outEndpoint, cnxnHeader, cnxnHeader.size, 5000)
                connection.bulkTransfer(outEndpoint, cnxnStr, cnxnStr.size, 5000)

                packet = readNextAdbPacket(8000)
                if (packet != null) break
            }

            if (packet != null && packet.command == A_AUTH && packet.arg0 == ADB_AUTH_TOKEN) {
                val authed = performAdbAuthHandshake(keyPair, packet.payload)
                if (!authed) {
                    onLog?.invoke("[WARN] ADB auth handshake was not acknowledged.")
                }
            }

            val cleanTarget = when {
                targetMode.contains("bootloader", ignoreCase = true) || targetMode.contains("fastboot", ignoreCase = true) -> "bootloader"
                targetMode.contains("recovery", ignoreCase = true) -> "recovery"
                else -> ""
            }

            // Burst 1: Send AOSP Native Reboot Service (`reboot:bootloader\u0000` or `reboot:recovery\u0000`)
            val localId = 1
            val dest1 = "reboot:$cleanTarget\u0000".toByteArray(Charsets.US_ASCII)
            val openHeader1 = buildAdbHeader(A_OPEN, localId, 0, dest1.size, calculateCrc(dest1, dest1.size))
            connection.bulkTransfer(outEndpoint, openHeader1, openHeader1.size, 3000)
            connection.bulkTransfer(outEndpoint, dest1, dest1.size, 3000)

            var openPacket = readNextAdbPacket(3000)
            if (openPacket != null && (openPacket.command == A_OKAY || openPacket.command == A_CLSE)) {
                return true
            }

            // Burst 2: Send AOSP Shell Reboot Service (`shell:reboot bootloader\u0000`)
            val dest2 = "shell:reboot $cleanTarget\u0000".toByteArray(Charsets.US_ASCII)
            val openHeader2 = buildAdbHeader(A_OPEN, localId + 1, 0, dest2.size, calculateCrc(dest2, dest2.size))
            connection.bulkTransfer(outEndpoint, openHeader2, openHeader2.size, 3000)
            connection.bulkTransfer(outEndpoint, dest2, dest2.size, 3000)

            openPacket = readNextAdbPacket(3000)
            if (openPacket != null && (openPacket.command == A_OKAY || openPacket.command == A_CLSE)) {
                return true
            }

            // Burst 3: Send AOSP Powerctl Reboot Service (`shell:svc power reboot bootloader\u0000`)
            val dest3 = "shell:svc power reboot $cleanTarget\u0000".toByteArray(Charsets.US_ASCII)
            val openHeader3 = buildAdbHeader(A_OPEN, localId + 2, 0, dest3.size, calculateCrc(dest3, dest3.size))
            connection.bulkTransfer(outEndpoint, openHeader3, openHeader3.size, 3000)
            connection.bulkTransfer(outEndpoint, dest3, dest3.size, 3000)

            openPacket = readNextAdbPacket(3000)
            return openPacket != null && (openPacket.command == A_OKAY || openPacket.command == A_CLSE)

        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Brings the ADB transport online exactly once (CNXN + optional RSA auth).  Reusing a single
     * transport avoids re-triggering the "Allow USB debugging" prompt for every command.
     */
    fun ensureConnected(): Boolean {
        if (adbConnected) return true
        synchronized(this) {
            if (adbConnected) return true
            val keyPair = getSharedRsaKeyPair()
            var lastError = "no ADB handshake"

            // Recovery's adbd frequently ignores the very first CNXN right after the USB
            // interface is claimed, so retry the handshake a few times before giving up.
            for (attempt in 0 until 4) {
                try {
                    drainInput()

                    val cnxnStr = "host::\u0000".toByteArray(Charsets.US_ASCII)
                    val cnxnHeader = buildAdbHeader(A_CNXN, 0x01000000, 1048576, cnxnStr.size, calculateCrc(cnxnStr, cnxnStr.size))
                    connection.bulkTransfer(outEndpoint, cnxnHeader, cnxnHeader.size, 5000)
                    connection.bulkTransfer(outEndpoint, cnxnStr, cnxnStr.size, 5000)

                    val packet = readNextAdbPacket(8000)
                    when {
                        packet == null -> {
                            lastError = "device did not answer CNXN (no ADB handshake)"
                        }
                        packet.command == A_CNXN -> {
                            adbConnected = true
                            lastConnectError = ""
                            return true
                        }
                        packet.command == A_AUTH -> {
                            val authed = performAdbAuthHandshake(keyPair, packet.payload)
                            adbConnected = authed
                            lastConnectError = if (authed) "" else "ADB authorization failed (tap Allow on the device)"
                            // Auth failure needs the user, so don't spin retrying.
                            return authed
                        }
                        else -> {
                            lastError = "unexpected ADB reply 0x" + Integer.toHexString(packet.command)
                        }
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "ADB connect exception"
                }
                if (attempt < 3) {
                    try {
                        Thread.sleep(400)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            lastConnectError = lastError
            return false
        }
    }

    /**
     * Re-arms the ADB transport on the *same* USB connection.  Recovery's adbd sometimes drops the
     * transport after a large transfer; tearing the whole USB device down and re-opening it has
     * proven to crash the USB stack, so instead we just forget the connection state and let the
     * next command perform a fresh CNXN handshake over the still-open interface.
     */
    fun resetAdbTransport() {
        synchronized(this) {
            adbConnected = false
            try {
                leftoverLen = 0
                packetQueue.clear()
            } catch (_: Exception) {
            }
        }
    }

    /** Discards any stale bytes left on the IN endpoint (e.g. from a previous session). */
    private fun drainInput() {
        try {
            val tmp = ByteArray(4096)
            var guard = 0
            while (guard < 8) {
                val r = connection.bulkTransfer(inEndpoint, tmp, tmp.size, 50)
                if (r <= 0) break
                guard++
            }
        } catch (_: Exception) {
        }
    }

    fun executeAdbShellCommand(commandStr: String): String? {
        val bytes = runAdbServiceRaw("shell:$commandStr", 8 * 1024 * 1024, 8000) ?: return null
        return String(bytes, Charsets.UTF_8).trim()
    }

    // ------------------------------------------------------------------------
    //  Raw service helpers (EFS / partition backup over `exec-out` / `shell`)
    // ------------------------------------------------------------------------

    /**
     * Opens an arbitrary ADB service on the (already connected) transport and captures the RAW
     * bytes it writes to stdout until it closes.  Used to pull raw partition data off a phone that
     * is running a root shell (TWRP / OrangeFox / rooted Android).
     */
    private fun runAdbServiceRaw(service: String, maxBytes: Int, idleTimeoutMs: Int): ByteArray? {
        if (!ensureConnected()) return null
        return try {
            val lId = channelIdCounter++

            val dest = "$service\u0000".toByteArray(Charsets.US_ASCII)
            val openHeader = buildAdbHeader(A_OPEN, lId, 0, dest.size, calculateCrc(dest, dest.size))
            if (writeAll(openHeader, 0, openHeader.size) != openHeader.size) return null
            if (writeAll(dest, 0, dest.size) != dest.size) return null

            // Wait for the OKAY that belongs to *this* channel.  Packets for other (earlier)
            // channels must be ignored, otherwise their stdout leaks into this command's output.
            var rId = -1
            val openDeadline = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < openDeadline) {
                val remain = (openDeadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                val pkt = readNextAdbPacket(remain) ?: break
                when (pkt.command) {
                    A_OKAY -> {
                        if (pkt.arg1 == lId) { rId = pkt.arg0; break }
                    }
                    A_CLSE -> {
                        if (pkt.arg1 == lId) return null
                    }
                    A_WRTE -> {
                        ackWrite(lId, pkt.arg0)
                    }
                    else -> { /* ignore stray CNXN/AUTH */ }
                }
            }
            if (rId < 0) return null

            val out = java.io.ByteArrayOutputStream()
            var deviceClosed = false
            while (out.size() < maxBytes) {
                val pkt = readNextAdbPacket(idleTimeoutMs) ?: break
                when (pkt.command) {
                    A_CLSE -> {
                        if (pkt.arg1 == lId) {
                            deviceClosed = true
                            break
                        }
                    }
                    A_WRTE -> {
                        ackWrite(lId, pkt.arg0)
                        if (pkt.arg1 == lId && pkt.payload.isNotEmpty()) out.write(pkt.payload)
                    }
                    else -> { /* ignore stray OKAY / CNXN inside the stream */ }
                }
            }

            // Only close the channel ourselves if the device hasn't already done so.
            if (!deviceClosed && rId > 0) {
                sendPacket(A_CLSE, lId, rId, null)
            }
            if (!deviceClosed) {
                // The stream ended without a clean device close (timeout, short read or maxBytes).
                // Any bytes still in flight would desync the next command, so re-arm the transport.
                resetAdbTransport()
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /** Acknowledges a device WRTE on our channel [lId]; [deviceId] is the device's channel id. */
    private fun ackWrite(lId: Int, deviceId: Int) {
        try {
            val okay = buildAdbHeader(A_OKAY, lId, deviceId, 0, 0)
            connection.bulkTransfer(outEndpoint, okay, okay.size, 3000)
        } catch (_: Exception) {
        }
    }

    /** Runs [command] via adb `exec-out` (raw pipe, no PTY) and returns the raw stdout bytes. */
    fun executeAdbExecOut(
        command: String,
        maxBytes: Int = 64 * 1024 * 1024,
        idleTimeoutMs: Int = 30000
    ): ByteArray? = runAdbServiceRaw("exec:$command", maxBytes, idleTimeoutMs)

    /** Runs [command] via adb `shell` (PTY) and returns the raw stdout bytes. */
    fun executeAdbShellRaw(
        command: String,
        maxBytes: Int = 64 * 1024 * 1024,
        idleTimeoutMs: Int = 30000
    ): ByteArray? = runAdbServiceRaw("shell:$command", maxBytes, idleTimeoutMs)

    // ------------------------------------------------------------------------
    //  PUBLIC API — Reliable file PUSH over adb `sync:` (exactly `adb push`)
    // ------------------------------------------------------------------------

    /**
     * Pushes [localFile] to [remotePath] using the ADB **sync** protocol — the same mechanism
     * `adb push` uses.  Unlike piping the bytes into `dd` (which loses the trailing block when
     * the channel closes and silently corrupts APKs / images), sync creates/truncates the remote
     * file, streams DATA blocks and finishes with a DONE that the device acknowledges, so the
     * copy on the phone is byte-for-byte identical.
     */
    fun pushFile(
        localFile: File,
        remotePath: String,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null
    ): Boolean {
        if (!localFile.exists() || localFile.length() <= 0L) {
            onLog?.invoke("[!] local file missing/empty: ${localFile.name}")
            return false
        }
        if (!ensureConnected()) {
            onLog?.invoke("[!] ADB connect fail (${lastConnectError})")
            return false
        }
        var fis: FileInputStream? = null
        try {
            val lId = channelIdCounter++
            val rId = openRawChannel(lId, "sync:")
            if (rId < 0) {
                onLog?.invoke("[!] 'sync' service open fail")
                return false
            }

            // 1) SEND  <path_len>  "<remotePath>,<mode>"
            val spec = "$remotePath,0644".toByteArray(Charsets.UTF_8)
            if (spec.size > 1024) {
                onLog?.invoke("[!] remote path too long")
                return false
            }
            val sendMsg = ByteArray(8 + spec.size)
            writeLeInt(sendMsg, 0, SYNC_ID_SEND)
            writeLeInt(sendMsg, 4, spec.size)
            System.arraycopy(spec, 0, sendMsg, 8, spec.size)
            if (!writeStreamAcked(lId, rId, sendMsg)) {
                onLog?.invoke("[!] SEND rejected by device")
                return false
            }

            // 2) DATA blocks
            fis = FileInputStream(localFile)
            val buf = ByteArray(SYNC_DATA_MAX)
            // Reused across every block so a long push doesn't allocate 64 KB per block.
            val dataMsg = ByteArray(8 + SYNC_DATA_MAX)
            val totalBytes = localFile.length()
            var total = 0L
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                writeLeInt(dataMsg, 0, SYNC_ID_DATA)
                writeLeInt(dataMsg, 4, read)
                System.arraycopy(buf, 0, dataMsg, 8, read)
                if (!writeStreamAcked(lId, rId, dataMsg, len = 8 + read)) {
                    onLog?.invoke("[!] transfer stalled at ${total / 1024} KB")
                    return false
                }
                total += read
                onProgress?.invoke(total, totalBytes)
            }

            // 3) DONE <mtime>
            val doneMsg = ByteArray(8)
            writeLeInt(doneMsg, 0, SYNC_ID_DONE)
            writeLeInt(doneMsg, 4, (localFile.lastModified() / 1000L).toInt())
            if (!writeStreamAcked(lId, rId, doneMsg)) {
                onLog?.invoke("[!] DONE rejected by device")
                return false
            }

            // 4) Status: 8 bytes = <id> <msgLen>
            val status = streamReadExactly(8, 30000, lId)
            if (status == null) {
                onLog?.invoke("[!] no 'sync' response from device")
                return false
            }
            val id = readLeInt(status, 0)
            val msgLen = readLeInt(status, 4)
            try { sendPacket(A_CLSE, lId, rId, null) } catch (_: Exception) {}
            if (id == SYNC_ID_OKAY) {
                onLog?.invoke("[OK] pushed ${total / 1024} KB → $remotePath")
                return true
            }
            if (id == SYNC_ID_FAIL) {
                val msg = if (msgLen in 1..4096) {
                    streamReadExactly(msgLen, 5000, lId)?.toString(Charsets.UTF_8)
                } else null
                onLog?.invoke("[!] push FAIL: ${msg ?: "unknown"}")
                return false
            }
            onLog?.invoke("[!] unexpected 'sync' reply 0x" + Integer.toHexString(id))
            return false
        } catch (e: Exception) {
            onLog?.invoke("[!] push exception: ${e.message}")
            return false
        } finally {
            try { fis?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Pulls [remotePath] off the device using the ADB `sync` RECV protocol (the same one
     * `adb pull` uses) and writes it to [localFile]. Works for arbitrarily large files
     * because the bytes stream straight to disk - no in-memory copy like `exec-out cat`.
     *
     * @return true on a clean transfer (device sent DONE).
     */
    fun pullFile(remotePath: String, localFile: File, onProgress: ((Long) -> Unit)? = null): Boolean {
        if (!ensureConnected()) return false
        val lId = channelIdCounter++
        val rId = openRawChannel(lId, "sync:")
        if (rId < 0) return false

        // 1) RECV <path_len> <path>
        val spec = remotePath.toByteArray(Charsets.UTF_8)
        if (spec.size > 1024) {
            try { sendPacket(A_CLSE, lId, rId, null) } catch (_: Exception) {}
            return false
        }
        val recvMsg = ByteArray(8 + spec.size)
        writeLeInt(recvMsg, 0, SYNC_ID_RECV)
        writeLeInt(recvMsg, 4, spec.size)
        System.arraycopy(spec, 0, recvMsg, 8, spec.size)
        if (!writeStreamAcked(lId, rId, recvMsg)) {
            try { sendPacket(A_CLSE, lId, rId, null) } catch (_: Exception) {}
            return false
        }

        localFile.parentFile?.mkdirs()
        val out = try {
            java.io.FileOutputStream(localFile)
        } catch (_: Exception) {
            try { sendPacket(A_CLSE, lId, rId, null) } catch (_: Exception) {}
            return false
        }

        var ok = false
        var total = 0L
        // Reused for the whole file so a long pull doesn't allocate 64 KB per block.
        val buf = ByteArray(SYNC_DATA_MAX)
        try {
            loop@ while (true) {
                val hdr = streamReadExactly(8, 30000, lId) ?: break@loop
                val id = readLeInt(hdr, 0)
                val size = readLeInt(hdr, 4)
                when (id) {
                    SYNC_ID_DATA -> {
                        var remaining = size
                        while (remaining > 0) {
                            val chunk = minOf(remaining, buf.size)
                            if (!streamReadInto(buf, 0, chunk, 30000, lId)) break@loop
                            out.write(buf, 0, chunk)
                            remaining -= chunk
                            total += chunk.toLong()
                        }
                        onProgress?.invoke(total)
                    }
                    SYNC_ID_DONE -> { ok = true; break@loop }
                    SYNC_ID_FAIL -> {
                        if (size in 1..4096) streamReadExactly(size, 5000, lId)
                        break@loop
                    }
                    else -> break@loop
                }
            }
        } catch (_: Exception) {
            ok = false
        } finally {
            try { out.close() } catch (_: Exception) {}
            try { sendPacket(A_CLSE, lId, rId, null) } catch (_: Exception) {}
        }
        if (!ok) {
            try { localFile.delete() } catch (_: Exception) {}
        }
        return ok
    }

    /** Opens [service] on the current transport and returns the device channel id, or -1. */
    private fun openRawChannel(lId: Int, service: String): Int {
        val dest = "$service\u0000".toByteArray(Charsets.US_ASCII)
        val openHeader = buildAdbHeader(A_OPEN, lId, 0, dest.size, calculateCrc(dest, dest.size))
        if (writeAll(openHeader, 0, openHeader.size) != openHeader.size) return -1
        if (writeAll(dest, 0, dest.size) != dest.size) return -1

        var rId = -1
        val openDeadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < openDeadline) {
            val remain = (openDeadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val pkt = readNextAdbPacket(remain) ?: break
            when (pkt.command) {
                A_OKAY -> if (pkt.arg1 == lId) { rId = pkt.arg0; break }
                A_CLSE -> if (pkt.arg1 == lId) return -1
                A_WRTE -> ackWrite(lId, pkt.arg0)
                else -> { /* ignore stray packets */ }
            }
        }
        return rId
    }

    /**
     * Writes [len] bytes (defaults to the whole array) of [data] to stream [lId] in <= maxPayload
     * WRTE packets, waiting for the device's OKAY after every packet (ADB is stop-and-wait, so this
     * is required flow control).
     */
    private fun writeStreamAcked(
        lId: Int,
        rId: Int,
        data: ByteArray,
        len: Int = data.size,
        idleTimeoutMs: Int = 30000
    ): Boolean {
        var offset = 0
        while (offset < len) {
            val chunk = minOf(maxPayload, len - offset)
            val crc = calculateCrc(data, offset, chunk)
            val header = buildAdbHeader(A_WRTE, lId, rId, chunk, crc)
            if (writeAll(header, 0, header.size) != header.size) return false
            if (writeAll(data, offset, chunk) != chunk) return false

            var acked = false
            val deadline = System.currentTimeMillis() + idleTimeoutMs
            while (!acked && System.currentTimeMillis() < deadline) {
                val remain = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                val pkt = readNextAdbPacket(remain) ?: break
                when (pkt.command) {
                    A_OKAY -> if (pkt.arg1 == lId) acked = true
                    A_WRTE -> {
                        ackWrite(lId, pkt.arg0)
                        leftoverAppend(pkt.payload, 0, pkt.payload.size)
                    }
                    A_CLSE -> if (pkt.arg1 == lId) return false
                    else -> { /* ignore */ }
                }
            }
            if (!acked) return false
            offset += chunk
        }
        return true
    }

    private fun writeLeInt(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readLeInt(src: ByteArray, offset: Int): Int {
        return (src[offset].toInt() and 0xFF) or
            ((src[offset + 1].toInt() and 0xFF) shl 8) or
            ((src[offset + 2].toInt() and 0xFF) shl 16) or
            ((src[offset + 3].toInt() and 0xFF) shl 24)
    }

    override fun close() {
        try {
            if (remoteId != 0) sendClose()
        } catch (_: Exception) {}
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {}
        try {
            connection.close()
        } catch (_: Exception) {}
    }
}
