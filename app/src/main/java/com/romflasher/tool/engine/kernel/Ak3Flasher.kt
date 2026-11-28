package com.romflasher.tool.engine.kernel

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.romflasher.tool.engine.efs.EfsBackupRestoreEngine
import com.romflasher.tool.engine.efs.EfsBackupRestoreEngine.RootMode
import com.romflasher.tool.usb.AdbSideloadProtocol
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * ============================================================================
 *   KERNEL ZIP FLASHER  (AnyKernel3 / AK3)
 * ============================================================================
 * Flashes an AnyKernel3 kernel zip onto a rooted target phone over USB/ADB by
 * driving the kernel's OWN installer — the exact program a recovery would run —
 * from a root shell.  Re-implementing the boot-image repack by hand is fragile,
 * so we let the zip do what it was built to do.
 *
 * Only the pieces the AK3 backend needs BEFORE it can unzip itself travel from
 * the host: the zip's `tools/busybox` (used as the shell), the AK3
 * `update-binary`, and the original zip.  The backend then unpacks the rest into
 * `$AKHOME` and flashes the boot image.
 *
 * This does NOT handle raw `.img` images (use the Custom Image card) and does
 * NOT touch logical partitions such as `vendor_dlkm`.
 *
 * The on-device driver lives in `assets/kernel_flash_ak3.sh`.
 * ============================================================================
 */
object Ak3Flasher {

    private const val WORK_DIR = "/data/local/tmp/romflasher_kf"
    private const val AK3_BACKEND = "META-INF/com/google/android/update-binary"

    /**
     * Validates and flashes the AnyKernel3 [zipFile] onto the target.  Returns true
     * when the on-device installer reports success.  Every step is reported through
     * [onLog].
     */
    suspend fun flashZip(
        context: Context,
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        zipFile: File,
        onLog: (String) -> Unit
    ): Boolean {
        onLog("=== KERNEL ZIP FLASH (AnyKernel3) ===")
        onLog("[INFO] Zip: ${zipFile.name} (${zipFile.length() / (1024 * 1024)} MB)")

        if (!zipFile.exists() || zipFile.length() <= 0L) {
            onLog("[ERROR] The selected kernel zip is missing or empty.")
            return false
        }

        // ---- Unpack the three tiny pieces the backend needs, on the host.
        val hostUpdateBinary = File(context.cacheDir, "ak3_update_binary")
        val hostBusybox = File(context.cacheDir, "ak3_busybox")
        val hostScript = File(context.cacheDir, "kernel_flash_ak3.sh")
        var hasBusybox = false

        try {
            ZipFile(zipFile).use { z ->
                if (z.getEntry("anykernel.sh") == null) {
                    onLog("[ERROR] This is not an AnyKernel3 kernel zip (no 'anykernel.sh' inside).")
                    onLog("[NOTE] Use the Custom Image card for raw .img files.")
                    return false
                }
                val ub = z.getEntry(AK3_BACKEND)
                if (ub == null) {
                    onLog("[ERROR] '$AK3_BACKEND' is missing from the zip.")
                    return false
                }
                if (!extractEntry(z, ub, hostUpdateBinary)) {
                    onLog("[ERROR] Could not read the AK3 backend out of the zip.")
                    return false
                }
                val bb = findBusyboxEntry(z)
                if (bb != null && extractEntry(z, bb, hostBusybox)) {
                    hasBusybox = true
                    onLog("[INFO] Using the zip's shell: ${bb.name}")
                } else {
                    onLog("[WARN] No busybox in the zip — the phone's own shell will be used.")
                }
            }
        } catch (e: Exception) {
            onLog("[ERROR] Could not open the kernel zip: ${e.message}")
            return false
        }

        try {
            context.assets.open("kernel_flash_ak3.sh").use { input ->
                FileOutputStream(hostScript).use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            onLog("[ERROR] Could not read the bundled flash driver: ${e.message}")
            return false
        }

        // ---- Root session on the target.
        val root = EfsBackupRestoreEngine.openRootAdbSession(usbManager, usbDevice, onLog) ?: return false
        val adb = root.session
        try {
            val targetAbi = try {
                adb.executeAdbShellCommand("getprop ro.product.cpu.abi")?.trim().orEmpty()
            } catch (_: Exception) { "" }
            if (targetAbi.isNotEmpty()) {
                onLog("[INFO] Target device ABI: $targetAbi")
                try {
                    ZipFile(zipFile).use { z ->
                        val bb = findBusyboxEntry(z, targetAbi)
                        if (bb != null && extractEntry(z, bb, hostBusybox)) {
                            hasBusybox = true
                            onLog("[INFO] Selected matching shell for $targetAbi: ${bb.name}")
                        }
                    }
                } catch (_: Exception) {}
            }

            onLog("[INFO] Preparing the installer on the phone…")
            try {
                EfsBackupRestoreEngine.runAsRoot(adb, root.rootMode, "rm -rf $WORK_DIR")
            } catch (_: Exception) {}
            try { adb.executeAdbShellCommand("mkdir -p $WORK_DIR && chmod 777 $WORK_DIR") } catch (_: Exception) {}

            var ok = push(adb, zipFile, "$WORK_DIR/pack.zip", "kernel zip", onLog)
            if (ok) ok = push(adb, hostUpdateBinary, "$WORK_DIR/update-binary", "AK3 backend", onLog)
            if (ok) ok = push(adb, hostScript, "$WORK_DIR/kernel_flash_ak3.sh", "flash driver", onLog)
            if (ok && hasBusybox) {
                // A missing/failed busybox push is not fatal — the phone shell can be used.
                if (!push(adb, hostBusybox, "$WORK_DIR/busybox", "shell", onLog)) {
                    onLog("[WARN] Bundled shell did not upload — using the phone's shell instead.")
                    hasBusybox = false
                }
            }
            if (!ok) {
                onLog("[ERROR] Could not upload the installer to the phone.")
                return false
            }

            onLog("[INFO] Running the AnyKernel3 installer on the phone…")
            val bbArg = if (hasBusybox) "$WORK_DIR/busybox" else "none"
            val inner =
                "sh $WORK_DIR/kernel_flash_ak3.sh $WORK_DIR $WORK_DIR/pack.zip $WORK_DIR/update-binary $bbArg"
            val command = if (rootModeIsSu(root.rootMode)) EfsBackupRestoreEngine.wrapSuCommand(inner) else inner

            val raw = try {
                adb.executeAdbShellRaw(command, 32 * 1024 * 1024, 900_000)
            } catch (_: Exception) {
                null
            }
            val output = raw?.let { String(it, Charsets.UTF_8) } ?: ""

            var returnCode = -1
            output.lineSequence().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("KF_RC=") ->
                        returnCode = trimmed.removePrefix("KF_RC=").trim().toIntOrNull() ?: -1
                    trimmed == "ui_print" -> { /* AK3 emits a blank ui_print after every line */ }
                    trimmed.startsWith("ui_print ") -> onLog(trimmed.removePrefix("ui_print "))
                    trimmed.isNotEmpty() -> onLog(trimmed)
                }
            }

            onLog("=============================================")
            if (returnCode == 0) {
                onLog("[COMPLETED] Kernel zip installed successfully.")
                onLog("=============================================")
                return true
            }
            if (output.isBlank()) {
                onLog("[ERROR] The installer produced no output (it never ran).")
            } else {
                onLog("[ERROR] The AnyKernel3 installer failed (exit code $returnCode).")
                onLog("[NOTE] Make sure the zip matches this phone's model/codename and Android version.")
            }
            onLog("=============================================")
            return false
        } catch (e: Exception) {
            onLog("[ERROR] Kernel zip flash failed: ${e.message}")
            return false
        } finally {
            try {
                EfsBackupRestoreEngine.runAsRoot(adb, root.rootMode, "rm -rf $WORK_DIR")
            } catch (_: Exception) {}
            try {
                File(context.cacheDir, "ak3_update_binary").delete()
                File(context.cacheDir, "ak3_busybox").delete()
                File(context.cacheDir, "kernel_flash_ak3.sh").delete()
            } catch (_: Exception) {}
            try { adb.close() } catch (_: Exception) {}
        }
    }

    // --------------------------------------------------------------- helpers

    private fun rootModeIsSu(mode: RootMode): Boolean = mode == RootMode.SU

    /** Pushes [from] to [remote], logging the outcome.  Returns false on failure. */
    private fun push(
        adb: AdbSideloadProtocol,
        from: File,
        remote: String,
        label: String,
        onLog: (String) -> Unit
    ): Boolean {
        val ok = try {
            adb.pushFile(from, remote) { msg -> onLog("[push] $msg") }
        } catch (_: Exception) {
            false
        }
        if (ok) onLog("[OK] Uploaded $label (${from.length() / 1024} KB).")
        else onLog("[!] Failed to upload $label.")
        return ok
    }

    private fun extractEntry(z: ZipFile, e: ZipEntry, out: File): Boolean = try {
        z.getInputStream(e).use { input -> FileOutputStream(out).use { o -> input.copyTo(o) } }
        out.exists() && out.length() > 0L
    } catch (_: Exception) {
        false
    }

    /**
     * Finds the AK3 shell inside the zip.  AnyKernel3 ships it as `tools/busybox`
     * (or `tools/<arch>/busybox`); when several are present the one matching the
     * device ABI wins.
     */
    private fun findBusyboxEntry(z: ZipFile, targetAbi: String = ""): ZipEntry? {
        val candidates = z.entries().asSequence()
            .filter { !it.isDirectory }
            .filter { e ->
                val n = e.name.replace('\\', '/').lowercase()
                n == "busybox" || n.endsWith("/busybox")
            }
            .toList()
        if (candidates.isEmpty()) return null

        val abiLower = targetAbi.lowercase()
        val pref = when {
            abiLower.contains("arm64") || abiLower.contains("aarch64") -> listOf("arm64", "aarch64")
            abiLower.contains("armeabi") || abiLower.contains("arm") -> listOf("arm", "armeabi")
            abiLower.contains("x86_64") -> listOf("x86_64")
            abiLower.contains("x86") -> listOf("x86")
            Build.SUPPORTED_ABIS.any { it.contains("arm64") } -> listOf("arm64", "aarch64")
            Build.SUPPORTED_ABIS.any { it.contains("armeabi") || it == "arm" } -> listOf("arm", "armeabi")
            Build.SUPPORTED_ABIS.any { it.contains("x86_64") } -> listOf("x86_64")
            else -> emptyList()
        }
        for (a in pref) {
            candidates.firstOrNull { it.name.lowercase().contains(a) }?.let { return it }
        }
        return candidates.firstOrNull { it.name.startsWith("tools/") } ?: candidates.first()
    }
}
