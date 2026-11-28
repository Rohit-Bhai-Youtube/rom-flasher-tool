package com.romflasher.tool.engine.brickshield

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.romflasher.tool.usb.FastbootSession
import java.io.File

/**
 * 🛡️ Brick Shield Engine
 *
 * Prevents device hard-bricks:
 * 1. Strict Codename Verification: Blocks cross-device flashing completely.
 * 2. Anti-Rollback (ARB) & Downgrade Safety Gate: Warns before firmware downgrades.
 */
object BrickShieldEngine {

    data class ValidationResult(
        val isAllowed: Boolean,
        val isMismatch: Boolean,
        val targetDevice: String,
        val romDevice: String,
        val errorMessage: String? = null
    )

    data class ArbCheckResult(
        val isDowngrade: Boolean,
        val deviceArb: Int,
        val romArb: Int,
        val warningMessage: String? = null
    )

    /**
     * Queries target device codename from fastboot.
     */
    fun queryDeviceProduct(usbManager: UsbManager, device: UsbDevice): String {
        return try {
            val session = FastbootSession.open(usbManager, device) ?: return ""
            try {
                session.getVariable("product")?.trim()?.lowercase().orEmpty()
            } finally {
                try { session.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) { "" }
    }

    /**
     * Queries Anti-Rollback (ARB) level from fastboot.
     */
    fun queryDeviceArb(usbManager: UsbManager, device: UsbDevice): Int {
        return try {
            val session = FastbootSession.open(usbManager, device) ?: return 0
            try {
                session.getVariable("anti")?.trim()?.toIntOrNull() ?: 0
            } finally {
                try { session.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) { 0 }
    }

    /**
     * Detects codename from the ROM folder/file:
     * 1. Inspects `flash_all*.bat` / `flash_all*.sh` (`^product: *<codename>`) — exact truth even for unlisted phones.
     * 2. Falls back to strict word-boundary token matching (never substring `.contains()`).
     */
    fun detectRomCodename(romFileOrFolder: File): String {
        detectCodenameFromFlashScripts(romFileOrFolder)?.let { return it }

        val name = romFileOrFolder.nameWithoutExtension.lowercase()
        val tokens = Regex("[a-z0-9]+").findAll(name).map { it.value }.toSet()
        val knownCodenames = listOf(
            "marble", "rodin", "alioth", "vayu", "bhima", "munch", "sweet", "sweetin", "surya", "curtana",
            "joyeuse", "gram", "excalibur", "mojito", "sunny", "rainbow", "duchamp", "corot",
            "mondrian", "socrates", "vermeer", "manet", "ingres", "zorn", "shennong", "houji",
            "diting", "fuxi", "nuwa", "ishtar", "zeus", "cupid", "psyche", "pipa", "liuqin",
            "elish", "enuma", "nabu", "dagu", "garnet", "peridot", "zircon", "redwood",
            "miro", "onyx", "degas", "rothko", "aurora", "xuanyuan"
        )
        for (code in knownCodenames) {
            if (tokens.contains(code)) return code
        }
        return ""
    }

    /** Extracts the expected `product` codename from Xiaomi fastboot `flash_all*.bat/.sh` scripts. */
    private fun detectCodenameFromFlashScripts(romFileOrFolder: File): String? {
        val candidates = when {
            romFileOrFolder.isFile && (
                romFileOrFolder.extension.equals("bat", ignoreCase = true) ||
                    romFileOrFolder.extension.equals("sh", ignoreCase = true)
                ) -> listOf(romFileOrFolder)
            romFileOrFolder.isDirectory -> romFileOrFolder.listFiles { f ->
                f.isFile && (f.name.endsWith(".bat", ignoreCase = true) || f.name.endsWith(".sh", ignoreCase = true))
            }?.sortedBy { if (it.name.startsWith("flash_all", ignoreCase = true)) 0 else 1 } ?: emptyList()
            else -> emptyList()
        }

        val productPatterns = listOf(
            Regex("""(?i)\^?product:\s*\*?\s*([a-z0-9_]+)"""),
            Regex("""(?i)(?:current_device|target_device)\s*=\s*["']?([a-z0-9_]+)["']?""")
        )
        val ignored = setOf("all", "unknown", "android", "device", "fastboot", "product")

        for (script in candidates.take(6)) {
            try {
                val headLines = script.bufferedReader().use { reader ->
                    buildString {
                        repeat(40) {
                            val line = reader.readLine() ?: return@repeat
                            appendLine(line)
                        }
                    }
                }
                for (regex in productPatterns) {
                    val m = regex.find(headLines) ?: continue
                    val raw = m.groupValues[1].trim().lowercase()
                    if (raw.length >= 3 && raw !in ignored) return raw
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Strict check: Does ROM codename match target device?
     */
    fun validateDeviceStrict(targetCodename: String, romCodename: String): ValidationResult {
        if (targetCodename.isBlank() || romCodename.isBlank()) {
            return ValidationResult(isAllowed = true, isMismatch = false, targetCodename, romCodename)
        }

        val target = targetCodename.trim().lowercase()
        val rom = romCodename.trim().lowercase()

        // Strip common Xiaomi regional suffixes (`_in`, `_global`, `_eea`, etc.) cleanly
        // instead of broad startsWith matching.
        val regionalSuffixes = listOf("_global", "_eea", "_in", "_cn", "_tw", "_ru", "_id", "_tr", "_jp", "_kr", "_lm")
        fun stripRegion(s: String): String {
            for (sfx in regionalSuffixes) {
                if (s.endsWith(sfx) && s.length > sfx.length + 2) return s.removeSuffix(sfx)
            }
            return s
        }

        val baseTarget = stripRegion(target)
        val baseRom = stripRegion(rom)

        val matches = (target == rom) ||
            (baseTarget == baseRom) ||
            isCrossVariantCompatible(target, rom) ||
            isCrossVariantCompatible(baseTarget, baseRom)

        return if (!matches) {
            ValidationResult(
                isAllowed = false,
                isMismatch = true,
                targetDevice = target,
                romDevice = rom,
                errorMessage = "BRICK SHIELD ACTIVE: ROM mismatch detected!\n\nTarget Phone: '$target'\nSelected ROM: '$rom'\n\nFlashing is strictly blocked to protect your phone from permanent hard-brick (EDL lock)."
            )
        } else {
            ValidationResult(isAllowed = true, isMismatch = false, target, rom)
        }
    }

    /**
     * Checks if target phone has higher Anti-Rollback level than the ROM pack.
     */
    fun checkRollbackRisk(deviceArb: Int, romArb: Int): ArbCheckResult {
        if (deviceArb > 0 && romArb > 0 && deviceArb > romArb) {
            return ArbCheckResult(
                isDowngrade = true,
                deviceArb = deviceArb,
                romArb = romArb,
                warningMessage = "You are downgrading your Android version / firmware (Anti-Rollback level $deviceArb to $romArb). It is not safe! Would you like to continue anyway?"
            )
        }
        return ArbCheckResult(isDowngrade = false, deviceArb = deviceArb, romArb = romArb)
    }

    /**
     * Identical hardware variants (e.g. POCO X3 Pro global vs India).
     */
    private fun isCrossVariantCompatible(a: String, b: String): Boolean {
        val pairs = listOf(
            setOf("vayu", "bhima"),
            setOf("curtana", "joyeuse", "gram", "excalibur"),
            setOf("sweet", "sweetin"),
            setOf("mojito", "sunny"),
            setOf("marble", "marblein"),
            setOf("rodin", "rodinin")
        )
        return pairs.any { it.contains(a) && it.contains(b) }
    }
}
