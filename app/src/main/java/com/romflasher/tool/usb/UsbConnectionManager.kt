package com.romflasher.tool.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.romflasher.tool.util.DeviceNameHelper
import com.romflasher.tool.viewmodel.DeviceMode
import java.util.concurrent.ConcurrentHashMap

data class DeviceInfo(
    val isConnected: Boolean = false,
    val mode: DeviceMode = DeviceMode.DISCONNECTED,
    val model: String = "",
    val codeName: String = "",
    val usbDevice: UsbDevice? = null
)

class UsbConnectionManager(private val context: Context) {

    private val deviceIdentityCache = ConcurrentHashMap<Int, Pair<String, String>>()

    fun detectConnectedDevice(): DeviceInfo {
        // Re-acquire the service each time: after a device re-enumerates (ADB <->
        // fastboot), the cached proxy can keep handing back a stale device list
        // until a real detach/attach broadcast arrives.
        val manager = try {
            context.getSystemService(Context.USB_SERVICE) as UsbManager
        } catch (_: Exception) {
            return DeviceInfo()
        }
        val deviceList = try { manager.deviceList } catch (_: Exception) { null } ?: return DeviceInfo()
        if (deviceList.isEmpty()) {
            deviceIdentityCache.clear()
            return DeviceInfo()
        }

        // Clean up detached devices from cache
        deviceIdentityCache.keys.retainAll(deviceList.values.map { it.deviceId }.toSet())

        for (device in deviceList.values) {
            val mode = inspectDeviceModePassive(device)
            if (mode != DeviceMode.DISCONNECTED) {
                val (model, codename) = resolveDeviceIdentity(manager, device, mode)
                return DeviceInfo(
                    isConnected = true,
                    mode = mode,
                    model = model,
                    codeName = codename,
                    usbDevice = device
                )
            }
        }

        return DeviceInfo()
    }

    private fun inspectDeviceModePassive(device: UsbDevice): DeviceMode {
        val prodName = (device.productName ?: "").lowercase()
        val devName = (device.deviceName ?: "").lowercase()
        val manufName = (device.manufacturerName ?: "").lowercase()

        // 1. Fastboot Product Name / Device Name Inspection (Passive)
        if (prodName.contains("fastboot") || prodName.contains("bootloader") ||
            devName.contains("fastboot") || devName.contains("bootloader") ||
            manufName.contains("fastboot")
        ) {
            return DeviceMode.FASTBOOT
        }

        // 2. Standard fastboot VID/PID (Google VID 0x18D1 — used by almost every
        //    phone, including Xiaomi/POCO). Detect it even when the product string
        //    is not yet readable (i.e. before the app has USB permission).
        if (device.vendorId == FASTBOOT_VID && device.productId in FASTBOOT_PIDS) {
            return DeviceMode.FASTBOOT
        }

        // 3. USB Interface Descriptor Inspection (Passive - never opens/claims)
        var sawAdbInterface = false
        var sawFastbootInterface = false
        var hasBulkEndpoints = false

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val cls = iface.interfaceClass
            val subCls = iface.interfaceSubclass
            val proto = iface.interfaceProtocol

            // ADB Interface (Class 255, Subclass 66, Protocol 1)
            if (cls == 255 && subCls == 66 && proto == 1) {
                sawAdbInterface = true
            }

            // Fastboot Interface (Class 255, Subclass 66, Protocol 3)
            if (cls == 255 && subCls == 66 && proto == 3) {
                sawFastbootInterface = true
            }

            // Fallback Vendor-Specific ADB/Fastboot Bulk Endpoint Check (only Class 255, Subclass 66)
            if (cls == 255 && subCls == 66) {
                var inEp = false
                var outEp = false
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) inEp = true
                        if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = true
                    }
                }
                if (inEp && outEp) {
                    hasBulkEndpoints = true
                }
            }
        }

        if (sawFastbootInterface) return DeviceMode.FASTBOOT
        if (sawAdbInterface) return DeviceMode.ADB
        if (hasBulkEndpoints) return DeviceMode.ADB

        return DeviceMode.DISCONNECTED
    }

    fun cacheIdentity(deviceId: Int, model: String, codeName: String) {
        if (codeName.isNotBlank() && codeName != "fastboot_target" && codeName != "adb_target") {
            deviceIdentityCache[deviceId] = Pair(model, codeName)
        }
    }

    private fun resolveDeviceIdentity(manager: UsbManager, device: UsbDevice, mode: DeviceMode): Pair<String, String> {
        val cached = deviceIdentityCache[device.deviceId]
        if (cached != null && cached.second.isNotBlank() && cached.second != "fastboot_target" && cached.second != "adb_target") {
            return cached
        }

        // 100% Passive inspection only: never perform blocking USB hardware transactions here
        val raw = device.productName?.trim().orEmpty()
        val name = if (nameLooksUseful(raw)) {
            cleanProductName(raw)
        } else if (mode == DeviceMode.FASTBOOT) {
            "Fastboot Device"
        } else {
            "Android Device"
        }
        val codename = ""
        return Pair(name, codename)
    }

    private fun nameLooksUseful(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val lower = n.lowercase()
        if (lower == "fastboot" || lower == "android" || lower == "android fastboot") return false
        if (lower.startsWith("/dev/")) return false
        return true
    }

    private fun cleanProductName(raw: String): String =
        raw.replace(Regex("(?i)\\s*[_-]?\\b(adb|fastboot|composite|interface)\\b.*$"), "").trim()
            .ifEmpty { "Android Device" }

    private companion object {
        const val FASTBOOT_VID = 0x18D1
        val FASTBOOT_PIDS = setOf(0xD00D, 0x4EE0, 0x4EE2, 0x4EE7)
    }
}
