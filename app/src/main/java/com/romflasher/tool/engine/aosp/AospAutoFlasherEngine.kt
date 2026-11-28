package com.romflasher.tool.engine.aosp

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.romflasher.tool.engine.fastboot.FastbootRomFlasher
import com.romflasher.tool.engine.feedback.AudioHapticEngine
import com.romflasher.tool.extractor.PayloadDumper
import com.romflasher.tool.usb.AdbSideloadProtocol
import com.romflasher.tool.usb.FastbootProtocol
import com.romflasher.tool.usb.FastbootSession
import com.romflasher.tool.usb.UsbConnectionManager
import com.romflasher.tool.util.RomFlasherStorage
import com.romflasher.tool.viewmodel.DeviceMode
import kotlinx.coroutines.delay
import java.io.File

/**
 * ============================================================================
 *  ⚡ AOSP AUTO FLASHER ENGINE — ROM FLASHER TOOL
 * ============================================================================
 *  1-Click Automatic Flashing Pipeline for AOSP Custom ROMs:
 *   1. Auto-detect target mode (ADB -> auto reboot to Fastboot).
 *   2. Targeted extraction of boot, vendor_boot, init_boot from payload.bin.
 *   3. Saved under: Download/RomFlasher/Auto_Flasher/<ROM_Name>/
 *   4. Flash boot images to Both Slots (A & B) + set active slot A.
 *   5. Clean format data: fastboot erase userdata & metadata.
 *   6. BCB Injection: write --sideload to misc partition & reboot.
 *   7. Auto-detect Recovery Sideload mode & stream adb sideload <rom.zip>.
 * ============================================================================
 */
object AospAutoFlasherEngine {

    suspend fun runAutoFlasher(
        context: Context,
        usbManager: UsbManager,
        initialDevice: UsbDevice?,
        zipFile: File,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        if (!zipFile.exists() || zipFile.length() < 1024 * 1024) {
            onLog("[ERROR] Selected ROM zip file is missing or invalid: ${zipFile.name}")
            return false
        }

        onLog("=============================================")
        onLog("  ⚡ AOSP AUTO FLASHER — 1-CLICK PIPELINE   ")
        onLog("=============================================")
        onLog("ROM Package : ${zipFile.name}")
        onLog("ROM Size    : ${zipFile.length() / (1024 * 1024)} MB")
        onLog("Path        : ${zipFile.absolutePath}")
        onLog("---------------------------------------------")

        val connManager = UsbConnectionManager(context)

        // --------------------------------------------------------------------
        // PHASE 1: Target Device Detection & Transition to Fastboot
        // --------------------------------------------------------------------
        onLog("[STEP 1/6] Detecting target device mode...")
        var currentDev = initialDevice ?: connManager.detectConnectedDevice().usbDevice
        var currentMode = if (currentDev != null) inspectDeviceMode(currentDev) else DeviceMode.DISCONNECTED

        if (currentMode == DeviceMode.DISCONNECTED) {
            onLog("[WAIT] Waiting for target phone connection via OTG...")
            var waitCount = 0
            while (currentMode == DeviceMode.DISCONNECTED && waitCount < 15) {
                if (isCancelled()) return false
                delay(1000)
                waitCount++
                currentDev = connManager.detectConnectedDevice().usbDevice
                if (currentDev != null) {
                    currentMode = inspectDeviceMode(currentDev)
                }
            }
            if (currentMode == DeviceMode.DISCONNECTED || currentDev == null) {
                onLog("[ERROR] No target phone connected! Please connect phone via OTG in Fastboot or ADB mode.")
                return false
            }
        }

        if (currentMode == DeviceMode.ADB) {
            onLog("[DETECTED] Target is in ADB mode (Android/System booted).")
            onLog("→ Sending command: adb reboot bootloader...")
            var rebootSent = false
            val adbDev = currentDev
            if (adbDev == null) {
                onLog("[ERROR] Target phone reference lost.")
                return false
            }
            try {
                val adb = AdbSideloadProtocol.open(usbManager, adbDev)
                if (adb != null) {
                    try {
                        rebootSent = adb.executeAdbRebootService("bootloader") { l -> onLog("  [ADB] $l") }
                        if (!rebootSent) {
                            rebootSent = adb.executeAdbShellCommand("reboot bootloader") != null
                        }
                    } finally {
                        adb.close()
                    }
                }
            } catch (e: Exception) {
                onLog("[ADB NOTE] ${e.message}")
            }

            if (!rebootSent) {
                onLog("[!] Could not auto-reboot via ADB. Please put target phone into Fastboot mode manually.")
            } else {
                onLog("[OK] Reboot command sent! Waiting for Fastboot re-enumeration (up to 30s)...")
            }

            // Wait for device to re-enumerate in Fastboot
            var fbDev: UsbDevice? = null
            for (i in 1..30) {
                if (isCancelled()) return false
                delay(1000)
                val info = connManager.detectConnectedDevice()
                if (info.isConnected && info.mode == DeviceMode.FASTBOOT && info.usbDevice != null) {
                    fbDev = info.usbDevice
                    break
                }
            }

            if (fbDev == null) {
                onLog("[ERROR] Timed out waiting for Fastboot mode. Ensure phone is in Fastboot mode and try again.")
                return false
            }
            currentDev = fbDev
            currentMode = DeviceMode.FASTBOOT
            onLog("[SUCCESS] Target phone connected in FASTBOOT mode!")
        } else if (currentMode == DeviceMode.FASTBOOT) {
            onLog("[SUCCESS] Target phone connected in FASTBOOT mode!")
        }

        if (isCancelled()) return false

        // --------------------------------------------------------------------
        // PHASE 2: Targeted Boot Partition Extraction
        // --------------------------------------------------------------------
        onLog("---------------------------------------------")
        onLog("[STEP 2/6] Extracting core boot partitions from payload.bin...")
        val autoFlasherDir = RomFlasherStorage.child("Auto_Flasher")
        val romOutputDir = File(autoFlasherDir, zipFile.nameWithoutExtension).apply {
            if (!exists()) mkdirs()
        }
        onLog("Destination Folder: ${romOutputDir.absolutePath}")

        val targetPartitions = setOf(
            "boot", "boot.img",
            "vendor_boot", "vendor_boot.img",
            "init_boot", "init_boot.img"
        )

        var extractedDir: File? = null
        try {
            extractedDir = PayloadDumper.extractPayload(
                context = context,
                inputFile = zipFile,
                outputBaseDir = romOutputDir,
                targetPartitions = targetPartitions,
                onLog = { l -> onLog("  $l") },
                onProgress = { p ->
                    // Keep terminal responsive with progress
                }
            )
        } catch (e: Exception) {
            onLog("[ERROR] Payload extraction failed: ${e.message}")
            return false
        }

        val searchDirs = listOfNotNull(extractedDir, romOutputDir, File(romOutputDir, "Payload_Extracted_${zipFile.nameWithoutExtension}"))

        val bootImg = findImageFile(searchDirs, "boot.img")
        val vendorBootImg = findImageFile(searchDirs, "vendor_boot.img")
        val initBootImg = findImageFile(searchDirs, "init_boot.img")

        if (bootImg == null) {
            onLog("[ERROR] 'boot.img' not found inside payload.bin! Extraction cannot proceed.")
            return false
        }

        onLog("Extracted partitions ready for flashing:")
        onLog("  ✓ boot.img: ${formatMb(bootImg.length())}")
        if (vendorBootImg != null) onLog("  ✓ vendor_boot.img: ${formatMb(vendorBootImg.length())}")
        if (initBootImg != null) onLog("  ✓ init_boot.img: ${formatMb(initBootImg.length())}")

        if (isCancelled()) return false

        // --------------------------------------------------------------------
        // PHASE 3: Flash Extracted Images to Both Slots (A & B)
        // --------------------------------------------------------------------
        onLog("---------------------------------------------")
        onLog("[STEP 3/6] Flashing core images to BOTH Slots (Slot A & Slot B)...")

        // Re-acquire fresh fastboot device
        val activeFbDev = connManager.detectConnectedDevice().usbDevice ?: currentDev
        if (activeFbDev == null) {
            onLog("[ERROR] Target phone in Fastboot mode not found.")
            return false
        }
        var session: FastbootSession? = FastbootSession.open(usbManager, activeFbDev)

        if (session == null) {
            onLog("[ERROR] Could not open Fastboot USB session. Please check OTG cable.")
            return false
        }

        try {
            val hasSlotVar = session.getVariable("has-slot:boot")?.trim()
            val slotCount = session.getVariable("slot-count")?.trim()?.toIntOrNull()
            val currentSlot = session.getVariable("current-slot")?.trim()
            val isAbDevice = when {
                hasSlotVar.equals("no", ignoreCase = true) -> false
                slotCount != null && slotCount < 2 -> false
                hasSlotVar.equals("yes", ignoreCase = true) || (slotCount != null && slotCount >= 2) -> true
                !currentSlot.isNullOrBlank() -> true
                else -> true
            }

            suspend fun flashTarget(partition: String, image: File): Boolean {
                if (isCancelled()) return false
                onLog("→ Flashing $partition (${image.name})...")
                val flashed = session.flashPartition(partition, image) { l -> onLog("  $l") }
                if (!flashed) {
                    onLog("[ERROR] Failed to flash partition '$partition'! Halting before data wipe to keep device recoverable.")
                }
                return flashed
            }

            if (isAbDevice) {
                if (!flashTarget("boot_a", bootImg)) return false
                delay(100)
                if (!flashTarget("boot_b", bootImg)) return false

                if (vendorBootImg != null) {
                    delay(100)
                    if (!flashTarget("vendor_boot_a", vendorBootImg)) return false
                    delay(100)
                    if (!flashTarget("vendor_boot_b", vendorBootImg)) return false
                }

                if (initBootImg != null) {
                    delay(100)
                    if (!flashTarget("init_boot_a", initBootImg)) return false
                    delay(100)
                    if (!flashTarget("init_boot_b", initBootImg)) return false
                }

                onLog("→ Setting active slot to A (set_active:a)...")
                val slotRes = session.executeGenericCommand("set_active:a")
                if (slotRes == null || slotRes.contains("FAIL", ignoreCase = true)) {
                    onLog("[ERROR] set_active:a failed (${slotRes ?: "NO RESPONSE"}). Aborting before userdata wipe.")
                    return false
                }
                onLog("  → set_active:a: ${slotRes.trim()}")
            } else {
                if (!flashTarget("boot", bootImg)) return false
                if (vendorBootImg != null) {
                    delay(100)
                    if (!flashTarget("vendor_boot", vendorBootImg)) return false
                }
                if (initBootImg != null) {
                    delay(100)
                    if (!flashTarget("init_boot", initBootImg)) return false
                }
            }
            onLog("[SUCCESS] All core boot images flashed and verified!")

            if (isCancelled()) return false

            // ----------------------------------------------------------------
            // PHASE 4: Clean Format Data (Erase Userdata & Metadata)
            // ----------------------------------------------------------------
            onLog("---------------------------------------------")
            onLog("[STEP 4/6] Wiping userdata & metadata for Clean Flash...")
            val eraseUserdataRes = session.executeGenericCommand("erase:userdata")
            if (eraseUserdataRes == null || eraseUserdataRes.contains("FAIL", ignoreCase = true)) {
                onLog("  [WARN] erase userdata: ${eraseUserdataRes ?: "NO RESPONSE"}")
            } else {
                onLog("  → erase userdata: ${eraseUserdataRes.trim()}")
            }

            val eraseMetadataRes = session.executeGenericCommand("erase:metadata")
            if (eraseMetadataRes == null || eraseMetadataRes.contains("FAIL", ignoreCase = true)) {
                onLog("  [WARN] erase metadata: ${eraseMetadataRes ?: "NO RESPONSE"}")
            } else {
                onLog("  → erase metadata: ${eraseMetadataRes.trim()}")
            }

            if (isCancelled()) return false

            // ----------------------------------------------------------------
            // PHASE 5: BCB Injection for Direct Sideload Mode
            // ----------------------------------------------------------------
            onLog("---------------------------------------------")
            onLog("[STEP 5/6] Injecting BCB Direct Sideload command into misc partition...")
            val miscFile = File(romOutputDir, "misc_sideload.bin")
            try {
                val bcb = ByteArray(2048)
                val cmdBytes = "boot-recovery".toByteArray(Charsets.US_ASCII)
                System.arraycopy(cmdBytes, 0, bcb, 0, minOf(cmdBytes.size, 32))

                val recBytes = "recovery\n--sideload_auto_reboot\n".toByteArray(Charsets.US_ASCII)
                System.arraycopy(recBytes, 0, bcb, 64, minOf(recBytes.size, 768))

                miscFile.writeBytes(bcb)
                val miscFlashed = session.flashPartition("misc", miscFile) { l -> onLog("  $l") }
                if (miscFlashed) {
                    onLog("[SUCCESS] BCB Direct Sideload flag injected into misc partition!")
                } else {
                    onLog("[NOTE] misc partition write skipped/protected by bootloader.")
                }
            } catch (e: Exception) {
                onLog("[NOTE] BCB injection notice: ${e.message}")
            }

            onLog("→ Rebooting target phone to Recovery...")
            session.executeGenericCommand("reboot-recovery")
        } finally {
            try { session.close() } catch (_: Exception) {}
        }

        if (isCancelled()) return false

        // --------------------------------------------------------------------
        // PHASE 6: Smart Auto-Sideload Listener & Streaming
        // --------------------------------------------------------------------
        onLog("---------------------------------------------")
        onLog("[STEP 6/6] Target phone rebooting into Recovery Sideload Mode...")
        onLog("💡 App is actively watching USB port for ADB Sideload connection...")
        onLog("   (Phone screen will boot directly into Sideload Mode. If it shows menu, tap 'Apply update' -> 'Apply from ADB')")

        var sideloadDevice: UsbDevice? = null
        val maxWaitSeconds = 50
        for (sec in 1..maxWaitSeconds) {
            if (isCancelled()) return false
            delay(1000)

            val devList = try { usbManager.deviceList.values } catch (_: Exception) { emptyList() }
            for (dev in devList) {
                val mode = inspectDeviceMode(dev)
                if (mode == DeviceMode.ADB || isSideloadDescriptor(dev)) {
                    // Test if we can open sideload protocol
                    try {
                        val testProto = AdbSideloadProtocol.open(usbManager, dev)
                        if (testProto != null) {
                            testProto.close()
                            sideloadDevice = dev
                            break
                        }
                    } catch (_: Exception) {}
                }
            }

            if (sideloadDevice != null) {
                break
            }

            if (sec % 5 == 0) {
                onLog("  ... listening for Sideload USB handoff (${sec}s / ${maxWaitSeconds}s)")
            }
        }

        if (sideloadDevice == null) {
            onLog("[ERROR] Timed out waiting for ADB Sideload. On phone screen, tap 'Apply update' -> 'Apply from ADB', then click SIDELOAD button.")
            return false
        }

        onLog("[CONNECTED] ADB Sideload connection established!")
        onLog("🚀 Streaming AOSP ROM Zip (${zipFile.name})...")
        onLog("DO NOT DISCONNECT CABLE DURING SIDELOAD TRANSFER!")

        val sideloadSuccess = FastbootRomFlasher.sideloadPackage(
            usbManager = usbManager,
            usbDevice = sideloadDevice,
            packageFile = zipFile,
            onLog = { l -> onLog(l) }
        )

        if (sideloadSuccess) {
            onLog("---------------------------------------------")
            onLog("→ Triggering automatic reboot to system (No-Touch)...")
            try {
                delay(1200)
                val currentDevs = usbManager.deviceList.values
                val targetDev = currentDevs.firstOrNull {
                    it.deviceId == sideloadDevice.deviceId ||
                        (it.vendorId == sideloadDevice.vendorId && it.productId == sideloadDevice.productId)
                }
                if (targetDev != null) {
                    val p = AdbSideloadProtocol.open(usbManager, targetDev)
                    if (p != null) {
                        try {
                            p.executeAdbRebootService("") { l -> onLog("  [REBOOT] $l") }
                        } catch (_: Exception) {}
                        finally {
                            try { p.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}

            onLog("=============================================")
            onLog("[SUCCESS] 🎉 AOSP ROM FLASHED SUCCESSFULLY!")
            onLog("Target phone will now reboot into the new system automatically.")
            onLog("Clean up temporary files in: ${romOutputDir.name}")
            onLog("=============================================")
            AudioHapticEngine.speakCompletion(
                englishText = "Sir, your ROM has been flashed successfully, sir!",
                hinglishText = "Bhai, ROM successfully flash ho gayi hai!"
            )
            return true
        } else {
            onLog("[ERROR] Sideload transfer interrupted. Please verify connection and retry.")
            return false
        }
    }

    private fun findImageFile(dirs: List<File>, fileName: String): File? {
        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val direct = File(dir, fileName)
            if (direct.exists() && direct.length() > 0) return direct
            val sub = dir.listFiles()?.firstOrNull { it.name.equals(fileName, ignoreCase = true) && it.length() > 0 }
            if (sub != null) return sub
        }
        return null
    }

    private fun inspectDeviceMode(device: UsbDevice): DeviceMode {
        val prodName = (device.productName ?: "").lowercase()
        val devName = (device.deviceName ?: "").lowercase()
        val manufName = (device.manufacturerName ?: "").lowercase()

        if (prodName.contains("fastboot") || prodName.contains("bootloader") ||
            devName.contains("fastboot") || devName.contains("bootloader") ||
            manufName.contains("fastboot")
        ) {
            return DeviceMode.FASTBOOT
        }

        var sawAdbInterface = false
        var sawFastbootInterface = false

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val cls = iface.interfaceClass
            val subCls = iface.interfaceSubclass
            val proto = iface.interfaceProtocol

            if (cls == 255 && subCls == 66 && proto == 1) {
                sawAdbInterface = true
            }
            if (cls == 255 && (proto == 3 || (subCls == 66 && proto == 3))) {
                sawFastbootInterface = true
            }
        }

        if (sawFastbootInterface) return DeviceMode.FASTBOOT
        if (sawAdbInterface) return DeviceMode.ADB
        return if (device.interfaceCount > 0) DeviceMode.ADB else DeviceMode.DISCONNECTED
    }

    private fun isSideloadDescriptor(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 255 && iface.interfaceSubclass == 66) {
                return true
            }
        }
        return false
    }

    private fun formatMb(bytes: Long): String =
        "${"%.1f".format(bytes.toDouble() / (1024.0 * 1024.0))} MB"
}
