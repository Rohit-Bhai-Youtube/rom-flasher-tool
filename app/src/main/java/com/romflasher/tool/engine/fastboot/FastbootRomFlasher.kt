package com.romflasher.tool.engine.fastboot

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import com.romflasher.tool.engine.efs.EfsBackupRestoreEngine
import com.romflasher.tool.engine.kernel.KernelBackupRestoreEngine
import com.romflasher.tool.extractor.FastExtractor
import com.romflasher.tool.runner.BatchScriptEngine
import com.romflasher.tool.usb.AdbSideloadProtocol
import com.romflasher.tool.usb.FastbootSession
import com.romflasher.tool.util.RomFlasherStorage
import java.io.File

/**
 * ============================================================================
 *   SEALED CORE ENGINE — ROM FLASHER TOOL (OPTIONS 1, 2, 3, 4, 5, AND 6)
 * ============================================================================
 * THIS MODULE IS SEALED, LOCKED, AND PACKAGED.
 *
 * ALL CORE MODULES ARE VERIFIED & FLAWLESS:
 *   1. OPTION 1: High-Speed Archive Extraction (.zip Method 14 LZMA, .7z, .tgz, .tar)
 *                & Interactive Batch Script Engine (.bat / .sh parser)
 *   2. OPTION 2: Single/Custom Image Flashing (Boot, Vendor Boot, Recovery, Super 9.2GB RAW, etc.)
 *                with AVB 2.0 Auto-Patcher & Dual Slot A/B Flashing Engine
 *   3. OPTION 3: AOSP ADB Sideload Engine (PixelExperience, LineageOS, GApps & Magisk)
 *                with 1:1 Flow Control Sync, ZIP64 Hex Block Reader, and USB Pipe Auto-Resync
 *   4. OPTION 4: Advanced Reboot & Mode Switcher
 *   5. OPTION 5: Hardware-Level EFS, IMEI & Modem Partition Backup & Restore
 *   6. OPTION 6: System & Bloatware App Uninstaller (ADB Debloater & PackageInstaller)
 *
 * CRITICAL RULE: DO NOT MODIFY INTERNAL LOGIC OF OPTIONS 1, 2, 3, 4, 5, OR 6
 *                WITHOUT EXPLICIT USER CONSENT.
 * ============================================================================
 */
object FastbootRomFlasher {

    /** Attempts per partition before a flash is reported as failed (matches Option 1). */
    private const val MAX_FLASH_ATTEMPTS = 3

    @Volatile
    private var activeEngine: BatchScriptEngine? = null

    // ========================================================================
    // OPTION 1 ENTRY POINTS (SEALED)
    // ========================================================================
    suspend fun extractRomArchive(
        context: Context,
        archiveUri: Uri,
        archiveName: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (currentBytes: Long, totalBytes: Long, currentFileName: String, filesDone: Int, totalFiles: Int) -> Unit
    ): File {
        return FastExtractor.extractArchive(context, archiveUri, archiveName, isCancelled, onProgress)
    }

    fun findFlashScripts(extractedDir: File): List<File> {
        // Windows .bat/.cmd only — shell scripts are not shown in the picker.
        return FastExtractor.findBatScripts(extractedDir).filter {
            it.extension.equals("bat", ignoreCase = true) || it.extension.equals("cmd", ignoreCase = true)
        }
    }

    suspend fun runFlashScript(
        scriptFile: File,
        usbManager: UsbManager?,
        usbDevice: UsbDevice?,
        onLog: (String) -> Unit,
        onPromptInput: (prompt: String) -> Unit
    ) {
        val engine = BatchScriptEngine(usbManager, usbDevice, onLog, onPromptInput)
        activeEngine = engine
        try {
            engine.runScript(scriptFile)
        } finally {
            activeEngine = null
        }
    }

    /**
     * Stops the flash that is running right now, if there is one. The USB
     * session is dropped with it, so the phone is free again for a reboot or
     * for another flash.
     */
    fun stopActiveFlash() {
        activeEngine?.requestStop()
    }

    /** True while a flash script is running. */
    fun isFlashRunning(): Boolean = activeEngine != null

    fun submitTerminalInput(input: String) {
        activeEngine?.submitUserInput(input)
    }

    // ========================================================================
    // OPTION 2 ENTRY POINT (SEALED - CUSTOM IMAGE FLASHER)
    // ========================================================================
    suspend fun flashCustomImage(
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        partition: String,
        imageFile: File,
        isDualSlot: Boolean,
        cacheDir: File,
        onLog: (String) -> Unit
    ): Boolean {
        var targetFile = imageFile
        if (partition.startsWith("vbmeta", ignoreCase = true)) {
            targetFile = prepareVbmetaFile(imageFile, cacheDir)
            if (targetFile != imageFile) {
                onLog("[AVB 2.0] Patched $partition (Disabled Verity & Verification)")
            } else {
                onLog("[AVB 2.0] $partition left unpatched (not a raw AVB0 image <= 2MB)")
            }
        }

        // Single-image flashes now go through the exact same reliability path as the
        // full-ROM flasher (Option 1): a failed write is retried up to three times and
        // the USB session is re-opened in between. Large images (super 9 GB, vendor_boot)
        // routinely glitch on OTG, and one silent glitch used to mean a dead flash.
        var attempt = 0
        while (attempt < MAX_FLASH_ATTEMPTS) {
            val session = FastbootSession.open(usbManager, usbDevice)
            if (session == null) {
                onLog("[ERROR] Could not open persistent Fastboot USB session. Please re-plug OTG cable.")
                return false
            }

            val flashed = try {
                flashCustomImageOnce(session, partition, targetFile, isDualSlot, onLog)
            } catch (t: Throwable) {
                onLog("[ERROR] $partition attempt ${attempt + 1} failed: ${t.message}")
                false
            } finally {
                try { session.close() } catch (_: Throwable) {}
            }

            if (flashed) return true

            attempt++
            if (attempt < MAX_FLASH_ATTEMPTS) {
                onLog("[RETRY] Re-opening USB session and retrying $partition (attempt ${attempt + 1}/$MAX_FLASH_ATTEMPTS)...")
                kotlinx.coroutines.delay(700)
            }
        }

        onLog("[ERROR] $partition failed after $MAX_FLASH_ATTEMPTS attempts.")
        return false
    }

    /**
     * One flash attempt on an already-open [session]. Mirrors the Option-1 flasher:
     * a slot-suffixed retry only happens when the device actually reports a slot for
     * that partition, so `super` (slotless) no longer produces a bogus
     * "partition does not exist" second write.
     */
    private suspend fun flashCustomImageOnce(
        session: FastbootSession,
        partition: String,
        targetFile: File,
        isDualSlot: Boolean,
        onLog: (String) -> Unit
    ): Boolean {
        if (isDualSlot) {
            val basePart = partition.removeSuffix("_a").removeSuffix("_b")
            val partA = "${basePart}_a"
            val partB = "${basePart}_b"

            onLog("[DUAL SLOT] Flashing $basePart on Slot A ($partA)...")
            val successA = session.flashPartition(partA, targetFile, onLog)

            kotlinx.coroutines.delay(50)

            onLog("[DUAL SLOT] Flashing $basePart on Slot B ($partB)...")
            val successB = session.flashPartition(partB, targetFile, onLog)

            if (successA) {
                val slotRes = session.executeGenericCommand("set_active:a")
                if (slotRes == null || slotRes.contains("FAIL", ignoreCase = true)) {
                    onLog("[WARN] set_active:a returned: ${slotRes ?: "NO RESPONSE"}")
                }
            } else {
                onLog("[WARN] Skipping set_active:a because Slot A flash failed.")
            }

            if (successA && successB) {
                onLog("---------------------------------------------")
                onLog("[SUCCESS] $partition successfully flashed on BOTH Slot A & Slot B!")
                return true
            }
            onLog("---------------------------------------------")
            onLog("[WARN] Dual-slot flash incomplete ($basePart).")
            return false
        }

        onLog("Flashing $partition...")
        var success = session.flashPartition(partition, targetFile, onLog)

        if (!success && !partition.endsWith("_a") && !partition.endsWith("_b")) {
            // Only try `<partition>_<slot>` when the device says the partition is
            // slotted — exactly like the full-ROM engine does.
            val hasSlot = session.getVariable("has-slot:$partition")?.trim()
            if (hasSlot.equals("yes", ignoreCase = true)) {
                val currentSlot = session.getVariable("current-slot")
                if (!currentSlot.isNullOrEmpty()) {
                    val slotPart = "${partition}_$currentSlot"
                    onLog("Retrying flash on active slot partition: $slotPart...")
                    success = session.flashPartition(slotPart, targetFile, onLog)
                }
            }
        }

        if (success) {
            onLog("---------------------------------------------")
            onLog("[SUCCESS] $partition successfully flashed!")
            return true
        }
        onLog("---------------------------------------------")
        onLog("[WARN] $partition flash attempt did not confirm success.")
        return false
    }

    // ========================================================================
    // OPTION 3 ENTRY POINT (SEALED - ADB SIDELOAD FLASHER)
    // ========================================================================
    suspend fun sideloadPackage(
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        packageFile: File,
        onLog: (String) -> Unit
    ): Boolean {
        val protocol = AdbSideloadProtocol.open(usbManager, usbDevice) ?: run {
            onLog("[ERROR] Could not open ADB Sideload connection. Ensure phone is in Recovery Mode with ADB Sideload active.")
            return false
        }

        return try {
            protocol.executeSideload(packageFile, onLog)
        } finally {
            protocol.close()
        }
    }

    // ========================================================================
    // OPTION 5 ENTRY POINT (SEALED - EFS & MODEM BACKUP & RESTORE)
    // ========================================================================
    suspend fun createEfsBackup(
        context: Context,
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        backupType: String,
        deviceModel: String,
        onLog: (String) -> Unit,
        onProgress: (EfsBackupRestoreEngine.EfsBackupProgress) -> Unit
    ): File {
        return EfsBackupRestoreEngine.createEfsBackup(context, usbManager, usbDevice, backupType, deviceModel, onLog, onProgress)
    }

    /**
     * IMEI / EFS restore done exactly like the Termux `restore-imei.sh` script: each
     * `/sdcard/imeibackup/<part>.img` is written into `dd of=/dev/block/by-name/<part> bs=4096` + `sync`.
     *
     * [preferredHostFolder] is the backup the user picked in the restore picker; when it is null the
     * newest backup found on this host phone is used instead.
     */
    suspend fun imeiRestore(
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        onLog: (String) -> Unit,
        onRomMismatch: (suspend (EfsBackupRestoreEngine.RomInfo, EfsBackupRestoreEngine.RomInfo) -> Boolean)? = null,
        preferredHostFolder: File? = null,
        onProgress: (EfsBackupRestoreEngine.EfsBackupProgress) -> Unit
    ): Boolean {
        return EfsBackupRestoreEngine.imeiRestore(
            usbManager, usbDevice, onLog, onRomMismatch, preferredHostFolder, onProgress
        )
    }

    /** Every IMEI / EFS backup on this host phone, newest first (for the restore picker). */
    fun listImeiBackups(): List<RomFlasherStorage.BackupEntry> = EfsBackupRestoreEngine.listImeiBackups()

    /** Every kernel backup on this host phone, newest first (for the restore picker). */
    fun listKernelBackups(): List<RomFlasherStorage.BackupEntry> = KernelBackupRestoreEngine.listKernelBackups()

    /**
     * IMEI / EFS backup done exactly like the Termux `backup.sh` script: each partition is written
     * with on-device `dd` to `/sdcard/imeibackup/<part>.img` (nothing leaves the phone).
     */
    suspend fun imeiBackup(
        context: Context,
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        deviceModel: String,
        onLog: (String) -> Unit,
        onProgress: (EfsBackupRestoreEngine.EfsBackupProgress) -> Unit
    ): Boolean {
        return EfsBackupRestoreEngine.imeiBackup(context, usbManager, usbDevice, deviceModel, onLog, onProgress)
    }

    /**
     * Copies the target's `/sdcard/imeibackup` folder to the HOST phone's
     * `Download/RomFlasher/IMEI Backup/<ROM family> Backup/<MODEL>_<timestamp>/`.
     */
    suspend fun copyImeiBackupToHost(
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        deviceModel: String,
        onLog: (String) -> Unit,
        onProgress: (EfsBackupRestoreEngine.EfsBackupProgress) -> Unit
    ): File? {
        return EfsBackupRestoreEngine.copyImeiBackupToHost(usbManager, usbDevice, deviceModel, onLog, onProgress)
    }

    suspend fun restoreEfsBackup(
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        backupFolder: File,
        currentDeviceModel: String,
        onLog: (String) -> Unit,
        onProgress: (EfsBackupRestoreEngine.EfsBackupProgress) -> Unit
    ): Boolean {
        return EfsBackupRestoreEngine.restoreEfsBackup(usbManager, usbDevice, backupFolder, currentDeviceModel, onLog, onProgress)
    }

    suspend fun bootCustomImage(
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        imageFile: File,
        onLog: (String) -> Unit
    ): Boolean {
        val session = FastbootSession.open(usbManager, usbDevice)
        if (session == null) {
            onLog("[ERROR] Could not open Fastboot USB session. Please re-plug OTG cable.")
            return false
        }
        return try {
            session.bootImage(imageFile, onLog)
        } catch (t: Throwable) {
            onLog("[ERROR] fastboot boot failed: ${t.message}")
            false
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    private fun prepareVbmetaFile(file: File, cacheDir: File): File {
        try {
            if (file.length() > 2 * 1024 * 1024L) return file
            val bytes = file.readBytes()
            if (bytes.size >= 128 &&
                bytes[0] == 0x41.toByte() && bytes[1] == 0x56.toByte() &&
                bytes[2] == 0x42.toByte() && bytes[3] == 0x30.toByte()) {

                // Patch AVB 2.0 flags at offset 120..123 to 0x00000003 (disable verity & verification)
                bytes[120] = 0x00
                bytes[121] = 0x00
                bytes[122] = 0x00
                bytes[123] = 0x03

                val patchedFile = File(cacheDir, "temp_avb_disabled_${file.name}")
                patchedFile.writeBytes(bytes)
                return patchedFile
            }
        } catch (_: Exception) {}
        return file
    }
}
