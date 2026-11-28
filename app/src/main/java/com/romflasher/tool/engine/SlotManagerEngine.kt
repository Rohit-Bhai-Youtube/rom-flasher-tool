package com.romflasher.tool.engine

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.romflasher.tool.usb.AdbSideloadProtocol
import com.romflasher.tool.usb.FastbootSession
import com.romflasher.tool.viewmodel.DeviceMode

/**
 * Reads and (in fastboot mode) switches the active A/B slot of the connected target.
 *
 *  - **Fastboot** : `getvar current-slot`, `getvar slot-count`, `getvar slot-successful:_a/_b`
 *                   and `set_active:<slot>`.
 *  - **ADB**      : read-only via `getprop ro.boot.slot_suffix` (slot switching needs fastboot).
 */
object SlotManagerEngine {

    data class SlotInfo(
        val currentSlot: String = "",
        val slotCount: Int = 0,
        val successA: String? = null,
        val successB: String? = null
    )

    suspend fun read(usbManager: UsbManager, usbDevice: UsbDevice, mode: DeviceMode): SlotInfo {
        return when (mode) {
            DeviceMode.FASTBOOT -> readFastboot(usbManager, usbDevice)
            DeviceMode.ADB -> readAdb(usbManager, usbDevice)
            else -> SlotInfo()
        }
    }

    private fun readFastboot(usbManager: UsbManager, usbDevice: UsbDevice): SlotInfo {
        val session = try {
            FastbootSession.open(usbManager, usbDevice)
        } catch (_: Exception) {
            null
        } ?: return SlotInfo()

        return try {
            val current = session.getVariable("current-slot")?.trim().orEmpty()
            val count = session.getVariable("slot-count")?.trim()?.toIntOrNull() ?: 0
            val okA = session.getVariable("slot-successful:_a")?.trim()
            val okB = session.getVariable("slot-successful:_b")?.trim()
            SlotInfo(
                currentSlot = normalizeSlot(current),
                slotCount = count,
                successA = okA,
                successB = okB
            )
        } catch (_: Exception) {
            SlotInfo()
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    private fun readAdb(usbManager: UsbManager, usbDevice: UsbDevice): SlotInfo {
        val adb = try {
            AdbSideloadProtocol.open(usbManager, usbDevice)
        } catch (_: Exception) {
            null
        } ?: return SlotInfo()

        return try {
            if (!adb.ensureConnected()) return SlotInfo()
            val suffix = adb.executeAdbShellCommand("getprop ro.boot.slot_suffix")?.trim().orEmpty()
            SlotInfo(currentSlot = normalizeSlot(suffix), slotCount = if (suffix.isNotEmpty()) 2 else 0)
        } catch (_: Exception) {
            SlotInfo()
        } finally {
            try { adb.close() } catch (_: Exception) {}
        }
    }

    suspend fun setActiveSlot(
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        slot: String,
        onLog: (String) -> Unit
    ): Boolean {
        val target = normalizeSlot(slot)
        if (target.isEmpty()) {
            onLog("[ERROR] Invalid slot: '$slot'")
            return false
        }
        val session = try {
            FastbootSession.open(usbManager, usbDevice)
        } catch (_: Exception) {
            null
        }
        if (session == null) {
            onLog("[ERROR] Could not open a Fastboot session. Switch the phone to fastboot mode first.")
            return false
        }
        val fastbootSlot = target.removePrefix("_")
        return try {
            onLog("> fastboot set_active:$fastbootSlot")
            var res = session.executeGenericCommand("set_active:$fastbootSlot")
            if (res != null && res.trim().uppercase().startsWith("FAIL")) {
                // Fallback attempt with prefixed slot if bootloader expects _a
                val alt = session.executeGenericCommand("set_active:$target")
                if (alt == null || !alt.trim().uppercase().startsWith("FAIL")) {
                    res = alt
                }
            }
            onLog("Fastboot Status: ${res ?: "NO RESPONSE"}")
            val ok = res != null && !res.trim().uppercase().startsWith("FAIL")
            if (ok) onLog("[SUCCESS] Active slot set to ${fastbootSlot.uppercase()}")
            else onLog("[ERROR] Bootloader rejected or did not respond to set_active")
            ok
        } catch (e: Exception) {
            onLog("[ERROR] set_active failed: ${e.message}")
            false
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    private fun normalizeSlot(raw: String): String {
        val s = raw.trim().lowercase()
        return when {
            s == "a" || s == "_a" -> "_a"
            s == "b" || s == "_b" -> "_b"
            else -> ""
        }
    }
}
