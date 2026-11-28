package com.romflasher.tool.viewmodel

import com.romflasher.tool.engine.vbmeta.VbmetaDisablerEngine
import com.romflasher.tool.engine.gsi.GsiFlasherEngine

import com.romflasher.tool.ui.tr

import android.app.PendingIntent

import android.content.Context

import android.content.Intent

import android.hardware.usb.UsbDevice

import android.hardware.usb.UsbManager

import android.net.Uri

import android.os.Build

import android.os.Environment

import android.provider.DocumentsContract

import android.provider.OpenableColumns

import androidx.lifecycle.ViewModel

import androidx.lifecycle.viewModelScope

import com.romflasher.tool.engine.DeviceInfoEngine

import com.romflasher.tool.engine.SlotManagerEngine

import com.romflasher.tool.engine.appinstall.AppInstallerEngine

import com.romflasher.tool.engine.brickshield.BrickShieldEngine

import com.romflasher.tool.engine.feedback.AudioHapticEngine

import com.romflasher.tool.engine.debloat.AppUninstallerEngine

import com.romflasher.tool.engine.efs.EfsBackupRestoreEngine

import com.romflasher.tool.engine.filemanager.FileManagerEngine

import com.romflasher.tool.engine.fastboot.FastbootRomFlasher

import com.romflasher.tool.engine.kernel.Ak3Flasher

import com.romflasher.tool.engine.kernel.KernelBackupRestoreEngine

import com.romflasher.tool.extractor.PayloadDumper

import com.romflasher.tool.root.AvbVariant

import com.romflasher.tool.root.RecoveryVariant

import com.romflasher.tool.root.RootFileRequest

import com.romflasher.tool.root.XiaomiDevice
import com.romflasher.tool.root.XiaomiRegion
import com.romflasher.tool.root.XiaomiRomHelper
import com.romflasher.tool.root.XiaomiRomVersion
import com.romflasher.tool.root.RootHelper

import com.romflasher.tool.root.RootKind

import com.romflasher.tool.util.NotificationHelper

import com.romflasher.tool.util.RomFlasherStorage

import com.romflasher.tool.usb.AdbSideloadProtocol

import com.romflasher.tool.util.DeviceNameHelper

import com.romflasher.tool.usb.DeviceInfo

import com.romflasher.tool.usb.FastbootProtocol

import com.romflasher.tool.usb.FastbootSession

import com.romflasher.tool.usb.UsbConnectionManager

import java.io.File

import java.io.FileInputStream

import java.io.FileOutputStream

import java.io.RandomAccessFile

import kotlin.coroutines.coroutineContext

import kotlinx.coroutines.CompletableDeferred

import kotlinx.coroutines.CancellationException

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.Job

import kotlinx.coroutines.delay

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.update

import kotlinx.coroutines.isActive

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import kotlinx.coroutines.withTimeoutOrNull

enum class DeviceMode { DISCONNECTED, ADB, FASTBOOT }

data class PayloadPartitionInfo(

    val name: String,

    val sizeBytes: Long,

    val formattedSize: String

)

data class UiState(

    val isConnected: Boolean = false,

    val deviceMode: DeviceMode = DeviceMode.DISCONNECTED,

    val deviceModel: String = "",

    val deviceCodeName: String = "",

    val isExtracting: Boolean = false,

    val extractionProgress: Float = 0f,

    val extractingFileName: String = "",

    val currentExtractingFile: String = "",

    val filesDoneCount: Int = 0,

    val totalFilesCount: Int = 0,

    val foundBatFiles: List<File> = emptyList(),

    val showBatDialog: Boolean = false,

    val showTerminal: Boolean = false,

    val terminalLogs: List<String> = emptyList(),

    val isWaitingForInput: Boolean = false,

    val inputPrompt: String = "",

    val errorMessage: String? = null,

    /** True while a ROM flash script is running. */

    val isFlashingRom: Boolean = false,

    // Option 2 Custom Image Flasher State

    val selectedPartition: String = "boot",

    val isDualSlotChecked: Boolean = true,

    val selectedImageFile: File? = null,

    val isPreparingCustomImageFile: Boolean = false,

    val isFlashingCustomImage: Boolean = false,

    // Option 3 ADB Sideload Flasher State

    val selectedSideloadFile: File? = null,

    val showBrickShieldMismatchDialog: Boolean = false,

    val brickShieldMismatchMessage: String = "",

    val showArbDowngradeDialog: Boolean = false,

    val arbDowngradeMessage: String = "",

    val isPreparingSideloadFile: Boolean = false,

    val isSideloadingPackage: Boolean = false,

    val isAutoFlashingAosp: Boolean = false,

    // Option 5 EFS / IMEI Backup & Restore State

    val isBackingUpEfs: Boolean = false,

    val isRestoringEfs: Boolean = false,

    val isCopyingEfsBackup: Boolean = false,

    // Option 7 Payload Dumper State

    val isPayloadDumperScreenOpen: Boolean = false,

    val selectedPayloadZipFile: File? = null,

    val selectedPayloadSourceUri: Uri? = null,

    val selectedPayloadSizeBytes: Long = 0L,

    val isParsingPayloadManifest: Boolean = false,

    val detectedPayloadPartitions: List<PayloadPartitionInfo> = emptyList(),

    val selectedPayloadPartitions: Set<String> = emptySet(),

    val isDumpingPayload: Boolean = false,

    val payloadDumpPartitionName: String = "",

    val payloadDumpOverallProgress: Float = 0f,

    val payloadDumpPartitionsDone: Int = 0,

    val payloadDumpTotalPartitions: Int = 0,

    val payloadVerifyAfterExtract: Boolean = true,

    val isVerifyingPayload: Boolean = false,

    val payloadVerifySummary: String = "",

    // Full Device Info dialog

    val showDeviceInfoDialog: Boolean = false,

    val isLoadingDeviceInfo: Boolean = false,

    val deviceInfoLines: List<String> = emptyList(),

    // A/B Slot manager

    val currentSlot: String = "",

    val slotCount: Int = 0,

    val slotSuccessA: String? = null,

    val slotSuccessB: String? = null,

    val isLoadingSlot: Boolean = false,

    val isSettingSlot: Boolean = false,

    // Upgraded Debloater dialog

    val showDebloaterDialog: Boolean = false,

    val isLoadingDebloater: Boolean = false,

    val isDebloaterBusy: Boolean = false,

    val debloaterApps: List<AppUninstallerEngine.DebloatApp> = emptyList(),

    val debloaterSelected: Set<String> = emptySet(),

    val debloaterFilter: String = "",

    val debloaterCategory: String = "All",

    val debloaterSizes: Map<String, Long> = emptyMap(),

    // Root wizard (KernelSU / Magisk guided rooting)

    val showRootWizard: Boolean = false,

    val rootKind: RootKind? = null,

    val rootLogs: List<String> = emptyList(),

    val rootBusy: Boolean = false,

    val rootStep: String = "",

    val rootPrompt: String? = null,

    val rootChoices: List<String> = emptyList(),

    val rootFileRequest: RootFileRequest? = null,

    val rootFilePreparing: Boolean = false,

    val rootFinished: Boolean = false,

    // Zero-Touch Direct Boot Patcher (Live Exploit & Cloud Micro-Dump)
    val showZeroTouchRootDialog: Boolean = false,
    val zeroTouchIsUninstall: Boolean = false,
    val zeroTouchLogs: List<String> = emptyList(),
    val zeroTouchBusy: Boolean = false,
    val zeroTouchStep: String = "",
    val zeroTouchFinished: Boolean = false,
    val zeroTouchProgress: Float = 0f,
    val zeroTouchAwaitingFile: Boolean = false,
    val zeroTouchFilePrompt: String? = null,

    // Xiaomi Boot / Init_Boot Downloader
    val showXiaomiBootDownloader: Boolean = false,
    val xiaomiSearchQuery: String = "",
    val xiaomiSelectedDevice: XiaomiDevice? = null,
    val xiaomiSelectedRegion: XiaomiRegion? = null,
    val xiaomiRomVersions: List<XiaomiRomVersion> = emptyList(),
    val xiaomiRomVersionsLoading: Boolean = false,
    val xiaomiDownloading: Boolean = false,
    val xiaomiDownloadProgress: Float = 0f,
    val xiaomiDownloadStatus: String = "",
    val xiaomiDownloadedFiles: List<File> = emptyList(),
    val xiaomiDownloaderError: String? = null,

    // Kernel Flasher (backup / restore / AnyKernel3 zip flash)

    val showKernelFlasher: Boolean = false,

    val kernelLogs: List<String> = emptyList(),

    val kernelBusy: Boolean = false,

    val kernelZipName: String = "",

    val kernelZipPath: String = "",

    val kernelAutoBackup: Boolean = true,

    val kernelRebootSeconds: Int? = null,

    val showKernelRootPrompt: Boolean = false,

    val showKernelBootloopPrompt: Boolean = false,

    /** Human label of the kernel backup waiting for the boot-loop answer. */

    val kernelBootloopBackupLabel: String = "",

    // App Installer (APK / APKS / XAPK / APKM / APKX ... any app file -> target)

    val showAppInstaller: Boolean = false,

    val appInstallerLogs: List<String> = emptyList(),

    val appInstallerBusy: Boolean = false,

    val appInstallerFileName: String = "",

    val appInstallerFilePath: String = "",

    // File Manager (browse + push/pull any file between host and target).

    // Selection is a LIST: the picker lets you tick many files at once and then

    // upload / download / delete the whole batch in one go.

    val showFileManager: Boolean = false,

    val fileManagerPath: String = "/sdcard",

    val fileManagerEntries: List<FileManagerEngine.Entry> = emptyList(),

    val fileManagerLoading: Boolean = false,

    val fileManagerBusy: Boolean = false,

    val fileManagerSelected: List<String> = emptyList(),

    val fileManagerLogs: List<String> = emptyList(),

    val fileManagerProgress: Float = 0f,

    val fileManagerProgressLabel: String = "",

    // ROM & restore compatibility verification (backup ROM != current ROM on restore)

    val showRomMismatchDialog: Boolean = false,

    val romMismatchBackup: String = "",

    val romMismatchCurrent: String = "",

    // Restore picker: user chooses WHICH backup to restore instead of blindly taking the newest

    val showRestorePicker: Boolean = false,

    val restorePickerKind: String = RomFlasherStorage.KIND_IMEI,

    val restorePickerEntries: List<RomFlasherStorage.BackupEntry> = emptyList(),

    val restorePickerScanned: Boolean = false,

    val selectedGsiFile: File? = null,
    val selectedCustomVbmetaFile: File? = null,
    val gsiDisableVbmeta: Boolean = true,
    val gsiWipeUserdata: Boolean = true,
    val gsiFreeSuperSpace: Boolean = true,
    val gsiRebootFastbootd: Boolean = true,
    val isFlashingGsi: Boolean = false

)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())

    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var usbManager: UsbManager? = null

    private var usbConnManager: UsbConnectionManager? = null

    private var currentConnectedDevice: UsbDevice? = null

    private var appContext: Context? = null

    // Progress notification (shown when the app is backgrounded during a long op)

    private var progressNotificationActive = false

    private var lastNotificationAt = 0L

    private var usbPollingJob: Job? = null

    // Cooperative cancel flag for ROM archive extraction (set by cancelExtraction).

    @Volatile

    private var extractionCancelled = false

    /**

     * The ROM flash that is running right now. It is kept so the flash can be

     * stopped: closing the console window or pressing a reboot button stops

     * the script instead of leaving it running in the background.

     */

            private fun showToast(msg: String) {
        val ctx = appContext ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    private var flashJob: Job? = null
    private var customImageJob: Job? = null
    private var sideloadJob: Job? = null
    private var gsiJob: Job? = null
    private var aospJob: Job? = null
    @Volatile private var aospCancelRequested: Boolean = false
    private var efsJob: Job? = null
    private var efsRestoreJob: Job? = null
    private var payloadDumpJob: Job? = null
    private var payloadVerifyJob: Job? = null
    private var appInstallJob: Job? = null
    private var debloaterJob: Job? = null
    private var xiaomiDownloadJob: Job? = null

    /** True while a flash script is running. */

    private var flashRunning = false

    /** Bumped for every flash, so a stale job cannot clear a newer one. */

    private var flashGeneration = 0

    /** USB device ids we've already popped a permission dialog for (avoids spam). */

    private val permissionRequestedIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    private val queryingFastbootIdentityIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    fun pauseUsbPolling() {

        try {

            usbPollingJob?.cancel()

            usbPollingJob = null

        } catch (_: Exception) {}

    }

    fun resumeUsbPolling() {

        startUsbAutoRefresh()

    }

    fun initUsbManager(context: Context) {

        usbConnManager = UsbConnectionManager(context)

        appContext = context.applicationContext

        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        purgeOldFlashResumeFile(context)

        refreshUsbDevices()

        startUsbAutoRefresh()

    }

    /**

     * One-off cleanup: the flash-resume bookkeeping of an older build is not

     * used any more, so the file it lived in is wiped from the phone.

     */

    private fun purgeOldFlashResumeFile(context: Context) {

        try {

            context.getSharedPreferences("romflasher_flash_resume", Context.MODE_PRIVATE)

                .edit().clear().apply()

        } catch (_: Exception) {

        }

    }

    // Live USB OTG Background Poller

    fun startUsbAutoRefresh() {

        if (usbPollingJob?.isActive == true) return

        usbPollingJob = viewModelScope.launch(Dispatchers.IO) {

            while (isActive) {

                val detected = usbConnManager?.detectConnectedDevice()

                if (detected != null && detected.isConnected) {

                    // Detection does NOT need USB permission, and asking for it

                    // from inside this loop was the main source of the popup

                    // storm: every mode switch re-enumerates the phone as a new

                    // USB device, and this loop asked again each time, roughly

                    // once a second. Permission is now asked in exactly one

                    // place - MainActivity - and only when a device really has

                    // none. `ensureUsbPermission` covers the "must have it right

                    // now" case before a real operation.

                    applyDetectedDevice(detected)

                } else {

                    // Device gone (re-enumerating / unplugged): forget identity and

                    // allow a fresh permission dialog for the next attach.

                    if (_uiState.value.isConnected || currentConnectedDevice != null) {

                        currentConnectedDevice = null

                        _uiState.update {

                            it.copy(

                                isConnected = false,

                                deviceMode = DeviceMode.DISCONNECTED,

                                deviceModel = "",

                                deviceCodeName = ""

                            )

                        }

                    }

                    if (permissionRequestedIds.isNotEmpty()) permissionRequestedIds.clear()

                    if (queryingFastbootIdentityIds.isNotEmpty()) queryingFastbootIdentityIds.clear()

                }

                delay(1000)

            }

        }

    }

    /**

     * Updates the device UI from a fresh detection. Compares a full descriptor

     * signature (not just the id/mode) so a re-enumerated device — or one whose

     * name only becomes readable after the permission grant — refreshes live

     * without needing a cable replug.

     */

    private fun applyDetectedDevice(detected: DeviceInfo) {

        val dev = detected.usbDevice

        val changed = currentConnectedDevice?.deviceId != dev?.deviceId ||

            _uiState.value.deviceMode != detected.mode ||

            _uiState.value.deviceModel != detected.model ||

            _uiState.value.deviceCodeName != detected.codeName

        if (changed) {

            currentConnectedDevice = dev

            _uiState.update {

                it.copy(

                    isConnected = true,

                    deviceMode = detected.mode,

                    deviceModel = detected.model,

                    deviceCodeName = detected.codeName

                )

            }

            if (detected.mode == DeviceMode.FASTBOOT && (detected.codeName.isBlank() || detected.codeName == "fastboot_target")) {

                resolveFastbootIdentityAsync(dev)

            }

        }

    }

    fun refreshUsbDevices() {

        // Synchronous on purpose: callers read `currentConnectedDevice` on the very next

        // line, so the old fire-and-forget coroutine handed them a stale/null device right

        // after a mode switch. Detection is a cheap UsbManager query, safe to run inline.

        // Re-acquire the manager too: after a mode switch the old proxy can keep a stale

        // device list around until a real detach/attach.

        try {

            appContext?.getSystemService(Context.USB_SERVICE)?.let { usbManager = it as UsbManager }

        } catch (_: Exception) {}

        val detected = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

        if (detected != null && detected.isConnected) {

            // No permission request here. This function runs from every UI event -

            // mode switches, button presses, attach broadcasts - and asking from

            // inside it produced a second, independent stream of system dialogs

            // (the ViewModel kept its own "already asked" set, separate from the

            // Activity's). MainActivity.requestUsbPermissionIfNeeded() is the one

            // place that asks, and it only asks when a device truly has no

            // permission yet.

            currentConnectedDevice = detected.usbDevice

            _uiState.update {

                it.copy(

                    isConnected = true,

                    deviceMode = detected.mode,

                    deviceModel = detected.model,

                    deviceCodeName = detected.codeName

                )

            }

            if (detected.mode == DeviceMode.FASTBOOT && (detected.codeName.isBlank() || detected.codeName == "fastboot_target")) {

                resolveFastbootIdentityAsync(detected.usbDevice)

            }

        } else {

            currentConnectedDevice = null

            permissionRequestedIds.clear()

            queryingFastbootIdentityIds.clear()

            _uiState.update {

                it.copy(

                    isConnected = false,

                    deviceMode = DeviceMode.DISCONNECTED,

                    deviceModel = "",

                    deviceCodeName = ""

                )

            }

        }

    }

    private fun resolveFastbootIdentityAsync(dev: UsbDevice?) {

        if (dev == null) return

        val mgr = usbManager ?: return

        if (!mgr.hasPermission(dev)) return

        if (!queryingFastbootIdentityIds.add(dev.deviceId)) return

        viewModelScope.launch(Dispatchers.IO) {

            val prod = try {

                kotlinx.coroutines.withTimeoutOrNull(2000L) {

                    FastbootProtocol.getVariable(mgr, dev, "product")?.trim()?.lowercase().orEmpty()

                } ?: ""

            } catch (_: Exception) { "" }

            if (prod.isNotBlank() && prod != "fastboot_target" && prod != "unknown") {

                val model = DeviceNameHelper.getDeviceModel(prod)

                usbConnManager?.cacheIdentity(dev.deviceId, model, prod)

                withContext(Dispatchers.Main) {

                    if (currentConnectedDevice?.deviceId == dev.deviceId && _uiState.value.deviceMode == DeviceMode.FASTBOOT) {

                        _uiState.update {

                            it.copy(

                                deviceModel = model,

                                deviceCodeName = prod

                            )

                        }

                    }

                }

            } else {

                queryingFastbootIdentityIds.remove(dev.deviceId)

            }

        }

    }

    /** Requests USB permission once per device id, without blocking the caller. */

    // ============================================================ Device Info

    fun requestDeviceInfo() {

        val manager = usbManager

        var targetDevice = currentConnectedDevice

        var mode = _uiState.value.deviceMode

        if (targetDevice == null) {

            val detected = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

            if (detected != null && detected.isConnected) {

                targetDevice = detected.usbDevice

                mode = detected.mode

            }

        }

        _uiState.update {

            it.copy(

                showDeviceInfoDialog = true,

                isLoadingDeviceInfo = true,

                deviceInfoLines = emptyList()

            )

        }

        val device = targetDevice

        val targetManager = manager

        viewModelScope.launch(Dispatchers.IO) {

            val lines = if (targetManager == null || device == null) {

                listOf(

                    "================== DEVICE INFO ==================",

                    "",

                    "No device connected.",

                    "Connect a phone over USB-OTG in ADB or Fastboot mode and tap the info button again."

                )

            } else {

                try {

                    DeviceInfoEngine.collect(targetManager, device, mode)

                } catch (e: Exception) {

                    listOf("[ERROR] ${e.message}")

                }

            }

            withContext(Dispatchers.Main) {

                _uiState.update {

                    it.copy(isLoadingDeviceInfo = false, deviceInfoLines = lines)

                }

            }

        }

    }

    fun closeDeviceInfoDialog() {

        _uiState.update { it.copy(showDeviceInfoDialog = false) }

    }

    // ============================================================ Slot Manager

    fun refreshSlotInfo() {

        val manager = usbManager

        var targetDevice = currentConnectedDevice

        var mode = _uiState.value.deviceMode

        if (targetDevice == null) {

            val detected = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

            if (detected != null && detected.isConnected) {

                targetDevice = detected.usbDevice

                mode = detected.mode

            }

        }

        if (manager == null || targetDevice == null) {

            _uiState.update { it.copy(isLoadingSlot = false) }

            return

        }

        val device = targetDevice

        _uiState.update { it.copy(isLoadingSlot = true) }

        viewModelScope.launch(Dispatchers.IO) {

            val info = try {

                SlotManagerEngine.read(manager, device, mode)

            } catch (_: Exception) {

                SlotManagerEngine.SlotInfo()

            }

            withContext(Dispatchers.Main) {

                _uiState.update {

                    it.copy(

                        currentSlot = info.currentSlot,

                        slotCount = info.slotCount,

                        slotSuccessA = info.successA,

                        slotSuccessB = info.successB,

                        isLoadingSlot = false

                    )

                }

            }

        }

    }

    fun setActiveSlot(slot: String) {

        val manager = usbManager

        val device = currentConnectedDevice

        if (manager == null || device == null) {

            addTerminalLog("[ERROR] No device connected for slot change.")

            return

        }

        _uiState.update { it.copy(isSettingSlot = true) }

        viewModelScope.launch(Dispatchers.IO) {

            val ok = try {

                SlotManagerEngine.setActiveSlot(manager, device, slot) { msg -> addTerminalLog(msg) }

            } catch (e: Exception) {

                addTerminalLog("[ERROR] set_active failed: ${e.message}")

                false

            }

            withContext(Dispatchers.Main) {

                if (ok) addTerminalLog("[INFO] Slot changed. Reboot to boot from the new slot.")

                _uiState.update { it.copy(isSettingSlot = false) }

                if (ok) refreshSlotInfo()

            }

        }

    }

    // ============================================================ Debloater

    fun openDebloater() {

        _uiState.update {

            it.copy(showDebloaterDialog = true, debloaterSelected = emptySet())

        }

        refreshDebloaterApps()

    }

    fun closeDebloater() {
        try { debloaterJob?.cancel() } catch (_: Exception) {}
        debloaterJob = null
        _uiState.update {
            it.copy(
                showDebloaterDialog = false,
                isDebloaterBusy = false,
                isLoadingDebloater = false
            )
        }
    }

    fun refreshDebloaterApps() {

        val manager = usbManager

        var target = currentConnectedDevice

        if (target == null) {

            val detected = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

            if (detected != null && detected.isConnected) {

                target = detected.usbDevice

            }

        }

        val ctx = appContext

        if (ctx == null) return

        val device = target

        _uiState.update { it.copy(isLoadingDebloater = true) }

        viewModelScope.launch(Dispatchers.IO) {

            val apps = try {

                AppUninstallerEngine.listInstalledApps(ctx, manager, device) { }

            } catch (_: Exception) {

                emptyList()

            }

            withContext(Dispatchers.Main) {

                _uiState.update {

                    it.copy(

                        debloaterApps = apps,

                        isLoadingDebloater = false,

                        isDebloaterBusy = false

                    )

                }

            }

            // Auto-load APK sizes so every app shows its size next to its name.

            if (apps.isNotEmpty()) {

                loadDebloaterSizes()

            }

        }

    }

    fun setDebloaterFilter(value: String) {

        _uiState.update { it.copy(debloaterFilter = value) }

    }

    fun toggleDebloaterApp(packageName: String) {

        _uiState.update {

            val sel = it.debloaterSelected.toMutableSet()

            if (!sel.add(packageName)) sel.remove(packageName)

            it.copy(debloaterSelected = sel)

        }

    }

    fun clearDebloaterSelection() {

        _uiState.update { it.copy(debloaterSelected = emptySet()) }

    }

    fun setDebloaterCategory(value: String) {

        _uiState.update { it.copy(debloaterCategory = value) }

    }

    /** Reads APK sizes from the target phone (optional; can be slow on big lists). */

    fun loadDebloaterSizes() {

        val manager = usbManager

        var target = currentConnectedDevice

        if (target == null) {

            val detected = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

            if (detected != null && detected.isConnected) {

                target = detected.usbDevice

            }

        }

        val ctx = appContext ?: return

        val device = target

        viewModelScope.launch(Dispatchers.IO) {

            val sizes = try {

                AppUninstallerEngine.loadPackageSizes(ctx, manager, device) { msg -> addTerminalLog(msg) }

            } catch (_: Exception) {

                emptyMap()

            }

            withContext(Dispatchers.Main) {

                _uiState.update {

                    it.copy(debloaterSizes = if (sizes.isEmpty()) it.debloaterSizes else sizes)

                }

            }

        }

    }

    // ============================================================ App Installer

    // Skip the "this file is 9% smaller" prompts / MIUI USB-install verification.

    private fun appLog(message: String) {

        _uiState.update { st -> st.copy(appInstallerLogs = (st.appInstallerLogs + message).takeLast(600)) }

    }

    fun closeAppInstaller() {
        try { appInstallJob?.cancel() } catch (_: Exception) {}
        appInstallJob = null
        _uiState.update {
            it.copy(
                showAppInstaller = false,
                appInstallerBusy = false
            )
        }
    }

    /** Stores the user-picked app file (APK / APKS / XAPK / APKM / ...) into the cache. */

    fun setAppInstallerFile(context: Context, uri: Uri) {

        viewModelScope.launch(Dispatchers.IO) {

            val file = copyUriToCache(context, uri, "app_install")

            if (file == null) {

                _uiState.update {

                    it.copy(

                        appInstallerFileName = "",

                        appInstallerFilePath = "",

                        appInstallerLogs = listOf(tr("[ERROR] Could not read the file - choose it again.", "[ERROR] File read nahi hui - dobara choose karo."))

                    )

                }

                return@launch

            }

            _uiState.update {

                it.copy(

                    appInstallerFileName = file.name,

                    appInstallerFilePath = file.absolutePath,

                    appInstallerLogs = listOf(

                        "[i] Selected: ${file.name} (${fmtSize(file.length())})",

                        tr("[i] Tap INSTALL — the app will be pushed and installed on the target.", "[i] INSTALL dabao — target par push + install hoga.")

                    )

                )

            }

        }

    }

    /** Forgets the selected app file (and deletes its cached copy) so a new one can be chosen. */

    fun clearAppInstallerFile() {

        if (_uiState.value.appInstallerBusy) return

        val path = _uiState.value.appInstallerFilePath

        if (!path.isNullOrBlank()) {

            try {

                val cached = File(path)

                val cacheRoot = appContext?.cacheDir?.absolutePath

                if (cacheRoot != null && cached.absolutePath.startsWith(cacheRoot) && cached.exists()) {

                    cached.delete()

                }

            } catch (_: Exception) { }

        }

        _uiState.update {

            it.copy(

                appInstallerFileName = "",

                appInstallerFilePath = "",

                appInstallerLogs = if (it.appInstallerLogs.isEmpty()) it.appInstallerLogs

                    else it.appInstallerLogs + "[i] Removed selected file."

            )

        }

    }

    /** Extracts (if needed) and installs the selected app file on the target phone. */

    fun installSelectedApp() {

        val ctx = appContext ?: return

        val path = _uiState.value.appInstallerFilePath

        if (path.isNullOrBlank()) {

            _uiState.update { it.copy(showAppInstaller = true, appInstallerLogs = listOf(tr("[ERROR] First select a file with 'CHOOSE APP FILE'.", "[ERROR] Pehle 'CHOOSE APP FILE' se file select karo."))) }

            return

        }

        if (_uiState.value.appInstallerBusy) return

        val container = File(path)

        _uiState.update {

            it.copy(

                showAppInstaller = true,

                appInstallerBusy = true,

                appInstallerLogs = listOf(tr("[i] Preparing ${container.name}…", "[i] ${container.name} taiyaar kar rahe hain…"))

            )

        }

        appInstallJob = viewModelScope.launch(Dispatchers.IO) {

            try {

                val payload = AppInstallerEngine.prepare(ctx, container, ctx.cacheDir) { msg -> appLog(msg) }

                if (payload == null) {

                    appLog(tr("[ERROR] Could not read the file - pick a valid APK/APKS/XAPK/APKM.", "[ERROR] File ko samajh nahi paaye - valid APK/APKS/XAPK/APKM chuno."))

                    return@launch

                }

                pushAndInstall(payload)

            } catch (e: Exception) {

                appLog("[ERROR] Install exception: ${e.message}")

            } finally {

                _uiState.update { it.copy(appInstallerBusy = false) }

            }

        }

    }

    private suspend fun pushAndInstall(payload: AppInstallerEngine.Payload): Boolean {

        if (rootCandidateDevices().isEmpty()) {

            appLog(tr("[ERROR] No target phone found on USB. Plug in the OTG cable + turn USB debugging ON.", "[ERROR] Koi target phone USB par nahi mila. OTG cable + USB debugging ON karo."))

            return false

        }

        appLog(tr("→ Connecting to the target over ADB…", "→ Target ADB se connect kar rahe hain…"))

        val probe = rootAdbShell("echo RF-READY")

        if (probe == null || !probe.contains("RF-READY")) {

            appLog(tr("[ERROR] Cannot connect to the target over ADB.", "[ERROR] Target ADB se connect nahi ho raha."))

            appLog(tr("[i] If the target shows 'Allow USB debugging?', tap ALLOW, then tap INSTALL.", "[i] Target par 'Allow USB debugging?' popup ho to ALLOW dabao, phir INSTALL dabao."))

            return false

        }

        appLog("[OK] Target connected.")

        val remoteDir = "/data/local/tmp/rf_apk"

        rootAdbShell("rm -rf $remoteDir && mkdir -p $remoteDir")

        val remotePaths = ArrayList<String>()

        payload.apks.forEachIndexed { idx, apk ->

            val flat = apk.name.replace(Regex("[^A-Za-z0-9._-]"), "_")

            val remote = "$remoteDir/${idx}_$flat"

            appLog("→ push ${apk.name} (${fmtSize(apk.length())}) → $remote")

            var pushed = rootAdbPush(apk, remote) { msg -> appLog(msg) }

            if (!pushed) pushed = rootAdbPush(apk, remote) { msg -> appLog(msg) }

            if (!pushed) {

                appLog(tr("[ERROR] APK push failed: ${apk.name}", "[ERROR] APK push fail hua: ${apk.name}"))

                return false

            }

            remotePaths.add(remote)

        }

        var result = trySessionInstall(remotePaths)

        if (!result.ok) {

            appLog("[!] Pehla install try fail: ${result.message}")

            appLog(tr("[i] Relaxing the verifier and retrying…", "[i] Verifier relax karke dobara try kar rahe hain…"))

            relaxInstallGuards()

            result = trySessionInstall(remotePaths)

        }

        if (!result.ok) {

            appLog(buildInstallFailureHint(result.message))

            if (remotePaths.size == 1) {

                appLog(tr("[i] Sending it to the target's Downloads and opening the installer…", "[i] Target ke Downloads me bhej kar installer khol rahe hain…"))

                val dl = rootPushToDownloads(payload.apks[0], payload.apks[0].name)

                if (dl != null) {

                    rootAdbShell("am start -a android.intent.action.VIEW -d file://$dl -t application/vnd.android.package-archive")

                    appLog(tr("[i] Installer opened on the target — tap 'Install'.", "[i] Target par installer khul gaya — 'Install' dabao."))

                }

            }

            return false

        }

        if (payload.obbFiles.isNotEmpty()) {

            appLog(tr("→ Copying OBB files…", "→ OBB files copy kar rahe hain…"))

            payload.obbFiles.forEach { (rel, local) ->

                val dest = "/sdcard/$rel"

                rootAdbShell("mkdir -p ${FileManagerEngine.shellQuote(dest.substringBeforeLast('/'))}")

                if (rootAdbPush(local, dest) { msg -> appLog(msg) }) appLog("[OK] OBB → $dest") else appLog("[!] OBB push fail: $rel")

            }

        }

        appLog(tr("[SUCCESS] App installed on the target ✔", "[SUCCESS] App target par install ho gayi ✔"))

        return true

    }

    private data class InstallResult(val ok: Boolean, val message: String)

    /** Installs via the `pm install-session` API (splits + single APKs). */

    private fun trySessionInstall(remotePaths: List<String>): InstallResult {

        var lastMessage = ""

        // Single APK: plain `pm install` is the most compatible path.

        if (remotePaths.size == 1) {

            val single = rootAdbShell("pm install -r -t -d ${remotePaths[0]}")?.trim().orEmpty()

            appLog("> pm install -r -t -d")

            if (single.isNotEmpty()) appLog(single)

            if (single.contains("Success", ignoreCase = true)) return InstallResult(true, single)

            lastMessage = single

        }

        // Split sets (and single-APK fallback): the install-session API.

        // Try a few flag combinations - older MIUI/ColorOS builds reject some flags.

        val variants = listOf(

            "-r -t -d -g",

            "-r -t -g",

            "-r -t",

            "-r -t --user 0"

        )

        for (flags in variants) {

            val create = rootAdbShell("pm install-create $flags")?.trim().orEmpty()

            appLog("> pm install-create $flags")

            if (create.isNotEmpty()) appLog(create)

            val session = Regex("\\[(\\d+)]").find(create)?.groupValues?.getOrNull(1)

            if (session == null) {

                lastMessage = create.ifBlank { lastMessage.ifBlank { tr("install-create did not return a session id", "install-create ne session id nahi diya") } }

                continue

            }

            var writeFailed: String? = null

            for (remote in remotePaths) {

                val size = rootAdbShell("stat -c %s $remote")?.trim()?.toLongOrNull() ?: 0L

                val splitName = remote.substringAfterLast('/')

                val write = rootAdbShell("pm install-write -S $size $session $splitName $remote")?.trim().orEmpty()

                if (!write.contains("Success", ignoreCase = true)) {

                    writeFailed = write.ifBlank { "install-write fail ($splitName)" }

                    break

                }

            }

            if (writeFailed != null) {

                rootAdbShell("pm install-abandon $session")

                lastMessage = writeFailed

                continue

            }

            val commit = rootAdbShell("pm install-commit $session")?.trim().orEmpty()

            appLog("> pm install-commit $session")

            if (commit.isNotEmpty()) appLog(commit)

            if (commit.contains("Success", ignoreCase = true)) return InstallResult(true, commit)

            lastMessage = commit.ifBlank { "install-commit fail" }

        }

        return InstallResult(false, lastMessage.ifBlank { "install fail" })

    }

    private fun relaxInstallGuards() {

        rootAdbShell("settings put global package_verifier_enable 0")

        rootAdbShell("settings put global verifier_verify_adb_installs 0")

        rootAdbShell("settings put global package_verifier_user_consent -1")

        rootAdbShell("settings put global upload_apk_enable 0")

        // MIUI / ColorOS specific: silence the "confirm ADB install" dialog when allowed.

        rootAdbShell("settings put global adb_install_need_confirm 0")

        rootAdbShell("settings put secure install_non_market_apps 1")

    }

    private fun buildInstallFailureHint(msg: String): String = when {

        msg.contains("USER_RESTRICTED", true) || msg.contains("SecurityException", true) ->

            tr("[!] The target blocked the install. In Developer Options turn ON 'Install via USB' + 'USB debugging (Security settings)', then tap INSTALL again.", "[!] Target ne install block kiya. Developer Options me 'Install via USB' + 'USB debugging (Security settings)' ON karo, phir dobara INSTALL dabao.")

        msg.contains("INSUFFICIENT_STORAGE", true) ->

            tr("[!] The target is low on storage - free up some space.", "[!] Target me storage kam hai - jagah khali karo.")

        msg.contains("SIGNATURE", true) || msg.contains("UPDATE_INCOMPATIBLE", true) ->

            tr("[!] The old app has a different signature — uninstall the old app first (use the Debloater).", "[!] Purani app ka signature alag hai — pehle purani app uninstall karo (Debloater se).")

        msg.contains("NO_MATCHING_ABIS", true) ->

            tr("[!] This APK is not built for the target's CPU architecture.", "[!] Ye APK target ki CPU architecture ke liye nahi hai.")

        msg.contains("no such file", true) ->

            tr("[!] The pushed file was not found on the target - check the cable/connection.", "[!] Push hui file target par nahi mili - cable/connection check karo.")

        else ->

            tr("[!] Install failed: ${msg.ifBlank { "unknown" }} — turn ON 'Install via USB' and try again.", "[!] Install fail: ${msg.ifBlank { "unknown" }} — 'Install via USB' ON karke dobara try karo.")

    }

    // ============================================================ File Manager

    private fun fmLog(message: String) {

        _uiState.update { st -> st.copy(fileManagerLogs = (st.fileManagerLogs + message).takeLast(600)) }

    }

    fun openFileManager() {

        _uiState.update {

            it.copy(

                showFileManager = true,

                fileManagerPath = FileManagerEngine.HOME,

                fileManagerSelected = emptyList(),

                fileManagerLogs = listOf(tr("[i] Browse the target phone's files, or upload / download.", "[i] Target phone ki files browse karo, ya upload/download karo."))

            )

        }

        refreshFileManager()

    }

    fun closeFileManager() {

        if (!_uiState.value.fileManagerBusy) {

            _uiState.update { it.copy(showFileManager = false) }

        }

    }

    fun fileManagerNavigate(path: String) {

        _uiState.update { it.copy(fileManagerPath = path, fileManagerSelected = emptyList()) }

        refreshFileManager()

    }

    fun fileManagerUp() {

        fileManagerNavigate(FileManagerEngine.parentOf(_uiState.value.fileManagerPath))

    }

    fun fileManagerHome() {

        fileManagerNavigate(FileManagerEngine.HOME)

    }

    /**

     * Ticks / unticks one file. This is the multi-select toggle — tapping a file

     * no longer replaces the selection, it joins or leaves it, so a whole batch

     * can be built up and then uploaded, downloaded or deleted at once.

     */

    fun fileManagerSelect(name: String?) {

        if (name.isNullOrBlank()) {

            _uiState.update { it.copy(fileManagerSelected = emptyList()) }

            return

        }

        _uiState.update { st ->

            st.copy(

                fileManagerSelected = if (st.fileManagerSelected.contains(name)) {

                    st.fileManagerSelected - name

                } else {

                    st.fileManagerSelected + name

                }

            )

        }

    }

    /** Ticks every file in the open folder (folders are skipped - they are navigated, not transferred). */

    fun fileManagerSelectAll() {

        _uiState.update { st ->

            st.copy(

                fileManagerSelected = st.fileManagerEntries

                    .filter { !it.isDirectory }

                    .map { it.name }

            )

        }

    }

    fun fileManagerClearSelection() {

        _uiState.update { it.copy(fileManagerSelected = emptyList()) }

    }

    fun refreshFileManager() {

        val path = _uiState.value.fileManagerPath

        _uiState.update { it.copy(fileManagerLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {

            val out = rootAdbShell(FileManagerEngine.listCommand(path))

            val entries = FileManagerEngine.parseListing(out)

            _uiState.update { it.copy(fileManagerEntries = entries, fileManagerLoading = false) }

            if (out == null) {

                fmLog(tr("[ERROR] No file list came back from the target - check the ADB connection.", "[ERROR] Target se list nahi aayi - ADB connection check karo."))

            } else if (entries.isEmpty()) {

                fmLog("[i] Folder is empty: $path")

            }

        }

    }

    /** Pushes host-picked files into the folder currently open on the target. */

    fun uploadFilesToTarget(context: Context, uris: List<Uri>) {

        if (uris.isEmpty() || _uiState.value.fileManagerBusy) return

        val dir = _uiState.value.fileManagerPath

        val total = uris.size

        _uiState.update {

            it.copy(

                fileManagerBusy = true,

                fileManagerProgress = 0f,

                fileManagerProgressLabel = tr("Preparing the upload…", "Upload tayyar ho raha hai…")

            )

        }

        fmLog("→ Starting upload ($total files) → $dir")

        viewModelScope.launch(Dispatchers.IO) {

            var done = 0

            var failed = 0

            try {

                for ((index, uri) in uris.withIndex()) {

                    val tag = "[${index + 1}/$total]"

                    val file = copyUriToCache(context, uri, "fmupload")

                    if (file == null) {

                        failed++

                        fmLog("$tag [ERROR] Could not read the file.")

                        continue

                    }

                    val fileSize = file.length()

                    val remote = FileManagerEngine.join(dir, file.name)

                    fmLog("$tag → push ${file.name} (${fmtSize(fileSize)}) → $remote")

                    var lastLogged = 0L

                    var lastUiMs = 0L

                    val ok = rootAdbPush(file, remote, onProgress = { sent, totalBytes ->

                        val t = if (totalBytes > 0) totalBytes else fileSize

                        val finished = t > 0 && sent >= t

                        // Throttle the progress UI to ~10 Hz so a fast link doesn't recompute and

                        // recompose hundreds of times a second; always push the final value.

                        val now = System.currentTimeMillis()

                        if (finished || now - lastUiMs >= 100L) {

                            lastUiMs = now

                            val frac = if (t > 0) (sent.toFloat() / t).coerceIn(0f, 1f) else 0f
                            val pct = (frac * 100).toInt()
                            val sentStr = fmtSize(sent)
                            val totalStr = if (t > 0) fmtSize(t) else "?"
                            val cName = compactFmFileName(file.name, 16)

                            _uiState.update {

                                it.copy(

                                    fileManagerProgress = frac,

                                    fileManagerProgressLabel = "$tag Upload: $cName ($sentStr / $totalStr)"

                                )

                            }

                        }

                        if (sent - lastLogged >= 2L * 1024 * 1024 || finished) {

                            lastLogged = sent

                            fmLog("$tag [i] ...${fmtSize(sent)} / ${if (t > 0) fmtSize(t) else "?"}")

                        }

                    }) { msg -> fmLog("$tag $msg" ) }

                    if (ok) {

                        done++

                        _uiState.update { it.copy(fileManagerProgress = 1f) }

                        fmLog("$tag [SUCCESS] Uploaded: $remote")

                    } else {

                        failed++

                        fmLog("$tag [ERROR] Upload failed.")

                    }

                    // The cache copy is only needed for the push - drop it right away so a

                    // 20-file batch doesn't park 20 copies of the user's data in the cache.

                    try { file.delete() } catch (_: Exception) {}

                }

            } catch (e: Exception) {

                fmLog("[ERROR] Upload exception: ${e.message}")

            } finally {

                val summary = when {

                    failed == 0 -> tr("[SUCCESS] Upload done: $done/$total file(s).", "[SUCCESS] Upload ho gaya: $done/$total file(s).")

                    done == 0 -> tr("[ERROR] Upload failed for all $total file(s).", "[ERROR] $total file(s) upload fail ho gayi.")

                    else -> tr("[!] Upload done: $done ok, $failed failed.", "[!] Upload: $done ok, $failed fail.")

                }

                fmLog(summary)

                _uiState.update {

                    it.copy(fileManagerBusy = false, fileManagerProgress = 0f, fileManagerProgressLabel = "")

                }

                refreshFileManager()

            }

        }

    }

    /** Downloads the selected target files into the host phone's ROMFlasher folder. */

    fun downloadSelectedFiles() {

        if (_uiState.value.fileManagerBusy) return

        val names = _uiState.value.fileManagerSelected

        if (names.isEmpty()) {

            fmLog(tr("[!] First tick at least one file from the list (not a folder).", "[!] Pehle list me se file tick karo (folder nahi)."))

            return

        }

        val dir = _uiState.value.fileManagerPath

        val total = names.size

        _uiState.update {

            it.copy(

                fileManagerBusy = true,

                fileManagerProgress = 0f,

                fileManagerProgressLabel = tr("Starting the downloads…", "Downloads shuru ho rahe hain…")

            )

        }

        fmLog("→ Download ($total files): $dir")

        viewModelScope.launch(Dispatchers.IO) {

            val outDir = RomFlasherStorage.fileManagerRoot()

            outDir.mkdirs()

            var done = 0

            var failed = 0

            try {

                for ((index, name) in names.withIndex()) {

                    val tag = "[${index + 1}/$total]"

                    val remote = FileManagerEngine.join(dir, name)

                    val local = uniqueLocalFile(outDir, name)

                    fmLog("$tag → Download: $remote")

                    val totalSize = rootAdbShell("stat -c %s ${FileManagerEngine.shellQuote(remote)}")

                        ?.trim()?.toLongOrNull() ?: 0L

                    var lastLogged = 0L

                    var lastUiMs = 0L

                    val ok = rootAdbPull(remote, local) { bytes ->

                        val finished = totalSize > 0L && bytes >= totalSize

                        // Throttle the progress UI to ~10 Hz; always push the final value.

                        val now = System.currentTimeMillis()

                        if (finished || now - lastUiMs >= 100L) {

                            lastUiMs = now

                            val frac = if (totalSize > 0L) (bytes.toFloat() / totalSize).coerceIn(0f, 1f) else 0f
                            val pct = (frac * 100).toInt()
                            val sentStr = fmtSize(bytes)
                            val totalStr = if (totalSize > 0) fmtSize(totalSize) else "?"
                            val cName = compactFmFileName(name, 16)

                            _uiState.update {

                                it.copy(

                                    fileManagerProgress = frac,

                                    fileManagerProgressLabel = "$tag Download: $cName ($sentStr / $totalStr)"

                                )

                            }

                        }

                        if (bytes - lastLogged >= 2L * 1024 * 1024 || finished) {

                            lastLogged = bytes

                            fmLog("$tag [i] ...${fmtSize(bytes)} / ${if (totalSize > 0) fmtSize(totalSize) else "?"}")

                        }

                    }

                    if (ok) {

                        done++

                        _uiState.update { it.copy(fileManagerProgress = 1f) }

                        fmLog("$tag [SUCCESS] Save: ${local.absolutePath} (${fmtSize(local.length())})")

                    } else {

                        failed++

                        fmLog("$tag [ERROR] Download failed.")

                    }

                }

            } catch (e: Exception) {

                fmLog("[ERROR] Download exception: ${e.message}")

            } finally {

                val summary = when {

                    failed == 0 -> tr("[SUCCESS] Download done: $done/$total file(s).", "[SUCCESS] Download ho gaya: $done/$total file(s).")

                    done == 0 -> tr("[ERROR] Download failed for all $total file(s).", "[ERROR] $total file(s) download fail ho gayi.")

                    else -> tr("[!] Download done: $done ok, $failed failed.", "[!] Download: $done ok, $failed fail.")

                }

                fmLog(summary)

                _uiState.update {

                    it.copy(fileManagerBusy = false, fileManagerProgress = 0f, fileManagerProgressLabel = "")

                }

                refreshFileManager()

            }

        }

    }

    /**

     * Picks a non-clobbering name for an incoming file. Downloading two files with

     * the same name (or re-downloading) must not silently overwrite what is already

     * on the host phone.

     */

    private fun uniqueLocalFile(dir: File, name: String): File {

        val base = name.substringBeforeLast('.')

        val ext = name.substringAfterLast('.', missingDelimiterValue = "")

        val suffix = if (ext.isEmpty()) "" else ".$ext"

        var candidate = File(dir, name)

        var n = 1

        while (candidate.exists()) {

            candidate = File(dir, "${base} (${n})$suffix")

            n++

        }

        return candidate

    }

    fun fileManagerNewFolder(name: String) {

        val clean = name.trim()

        if (clean.isEmpty()) return

        if (clean.contains('/') || clean.contains('\\')) {

            fmLog(tr("[ERROR] A folder name cannot contain '/'.", "[ERROR] Folder name me '/' nahi ho sakta."))

            return

        }

        val remote = FileManagerEngine.join(_uiState.value.fileManagerPath, clean)

        viewModelScope.launch(Dispatchers.IO) {

            val out = rootAdbShell("mkdir -p ${FileManagerEngine.shellQuote(remote)} && echo RF-DONE")

            fmLog(if (out?.contains("RF-DONE") == true) tr("[SUCCESS] Folder created: $remote", "[SUCCESS] Folder bana: $remote") else tr("[ERROR] Folder was not created.", "[ERROR] Folder nahi bana."))

            refreshFileManager()

        }

    }

    /** Deletes the ticked files/folders on the target. */

    fun fileManagerDeleteSelected() {

        if (_uiState.value.fileManagerBusy) return

        val names = _uiState.value.fileManagerSelected

        if (names.isEmpty()) return

        val dir = _uiState.value.fileManagerPath

        val total = names.size

        _uiState.update { it.copy(fileManagerBusy = true) }

        viewModelScope.launch(Dispatchers.IO) {

            var done = 0

            var failed = 0

            try {

                for ((index, name) in names.withIndex()) {

                    val tag = "[${index + 1}/$total]"

                    val remote = FileManagerEngine.join(dir, name)

                    fmLog("$tag → delete $remote")

                    val out = rootAdbShell("rm -rf ${FileManagerEngine.shellQuote(remote)} && echo RF-DELETED")

                    if (out?.contains("RF-DELETED") == true) {

                        done++

                        fmLog("$tag [SUCCESS] Deleted.")

                    } else {

                        failed++

                        fmLog("$tag [ERROR] Delete failed.")

                    }

                }

            } catch (e: Exception) {

                fmLog("[ERROR] Delete exception: ${e.message}")

            } finally {

                val summary = when {

                    failed == 0 -> tr("[SUCCESS] Deleted $done/$total.", "[SUCCESS] $done/$total delete ho gaye.")

                    done == 0 -> tr("[ERROR] Delete failed for all $total.", "[ERROR] $total delete fail ho gaye.")

                    else -> tr("[!] Deleted $done, failed $failed.", "[!] $done delete, $failed fail.")

                }

                fmLog(summary)

                _uiState.update {

                    it.copy(fileManagerBusy = false, fileManagerSelected = emptyList())

                }

                refreshFileManager()

            }

        }

    }

    fun applyDebloaterAction(actionName: String) {

        val state = _uiState.value

        val selected = state.debloaterSelected.toList()

        if (selected.isEmpty()) return

        val action = when (actionName) {

            "UNINSTALL" -> AppUninstallerEngine.DebloatAction.UNINSTALL

            "DISABLE" -> AppUninstallerEngine.DebloatAction.DISABLE

            else -> AppUninstallerEngine.DebloatAction.ENABLE

        }

        val ctx = appContext

        val manager = usbManager

        var device = currentConnectedDevice

        if (device == null) {

            val detected = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

            if (detected != null && detected.isConnected) device = detected.usbDevice

        }

        val target = device

        _uiState.update {

            it.copy(

                isDebloaterBusy = true,

                terminalLogs = it.terminalLogs + listOf(

                    "$action on ${selected.size} package(s):",

                    "---------------------------------------------"

                )

            )

        }

        if (ctx == null) {

            _uiState.update { it.copy(isDebloaterBusy = false) }

            return

        }

        debloaterJob = viewModelScope.launch(Dispatchers.IO) {

            try {

                AppUninstallerEngine.applyBatchAction(ctx, manager, target, selected, action) { msg ->

                    addTerminalLog(msg)

                }

            } catch (e: Exception) {

                addTerminalLog("[ERROR] Batch debloat failed: ${e.message}")

            } finally {

                withContext(Dispatchers.Main) {

                    _uiState.update {

                        it.copy(debloaterSelected = emptySet(), isDebloaterBusy = false)

                    }

                }

                refreshDebloaterApps()

            }

        }

    }

    /**

     * Unified Smart Auto-ROM Detection & Auto-Transition Engine:

     * 1. Inspects the selected archive (.zip, .tgz, .tar, .7z) in < 0.5s.

     * 2. Detects if it is an AOSP Recovery ROM (payload.bin / updater-script)

     *    OR a Custom / Port / Fastboot ROM (flash_all.bat / images/ / factory archive).

     * 3. For Custom / Fastboot ROMs: If target phone is in Android / ADB mode,

     *    automatically issues 'adb reboot bootloader' so phone transitions to Fastboot,

     *    then launches the Fastboot flashing pipeline.

     * 4. For AOSP Recovery ROMs: Launches the 1-Click AOSP Auto Flasher pipeline

     *    (boot/vendor_boot/init_boot flash -> wipe data -> recovery sideload -> auto-reboot).

     */

    fun handleAutoRomArchiveSelected(context: Context, archiveUri: Uri, archiveName: String) {

        viewModelScope.launch(Dispatchers.IO) {

            _uiState.update {

                it.copy(

                    showTerminal = true,

                    terminalLogs = listOf(

                        "⚡ ROM FLASHER — SMART AUTO ROM ENGINE",

                        "Selected Package: " + archiveName,

                        "Analyzing ROM structure (Fastboot vs AOSP)...",

                        "---------------------------------------------"

                    ),

                    errorMessage = null

                )

            }

            val lowerName = archiveName.lowercase()

            var detectedAsAosp = false

            var detectedAsFastboot = false

            if (lowerName.endsWith(".tgz") || lowerName.endsWith(".tar") || lowerName.endsWith(".7z")) {

                detectedAsFastboot = true

            } else if (lowerName.endsWith(".zip")) {

                try {

                    context.contentResolver.openInputStream(archiveUri)?.use { rawStream ->

                        java.util.zip.ZipInputStream(java.io.BufferedInputStream(rawStream)).use { zis ->

                            var entry = zis.nextEntry

                            var checkedCount = 0

                            while (entry != null && checkedCount < 160) {

                                val name = entry.name.lowercase().replace('\\', '/')

                                if (name.endsWith("flash_all.bat") || name.endsWith("flash_all.sh") || name.startsWith("images/")) {

                                    detectedAsFastboot = true

                                    break

                                }

                                if (name == "payload.bin" || name.endsWith("/payload.bin")) {

                                    detectedAsAosp = true

                                }

                                if (name.contains("meta-inf/com/google/android/update")) {

                                    detectedAsAosp = true

                                }

                                checkedCount++

                                entry = zis.nextEntry

                            }

                        }

                    }

                } catch (e: Exception) {

                    addTerminalLog("[NOTE] Quick zip index scan: " + (e.message ?: ""))

                }

            }

            val isAosp = detectedAsAosp && !detectedAsFastboot

            if (isAosp) {

                addTerminalLog("[AUTO-DETECT] ROM Type: AOSP Recovery ROM (payload.bin detected)")

                addTerminalLog("[AUTO-DETECT] Pipeline: 1-Click AOSP Auto Flasher Engine")

                addTerminalLog("Preparing package for extraction and sideload streaming...")

                try {

                    val baseDir = context.getExternalFilesDir("sideload") ?: File(context.cacheDir, "sideload")

                    if (!baseDir.exists()) baseDir.mkdirs()

                    val cacheFile = File(baseDir, archiveName)

                    context.contentResolver.openInputStream(archiveUri)?.use { input ->

                        cacheFile.outputStream().use { output ->

                            input.copyTo(output)

                        }

                    }

                    _uiState.update { it.copy(selectedSideloadFile = cacheFile) }

                    addTerminalLog("[OK] Package cached successfully.")

                    withContext(Dispatchers.Main) {

                        startAospAutoFlasher()

                    }

                } catch (e: Exception) {

                    addTerminalLog("[ERROR] Failed to prepare AOSP package: " + (e.message ?: ""))

                }

            } else {

                addTerminalLog("[AUTO-DETECT] ROM Type: Custom / Port / Fastboot ROM")

                addTerminalLog("[AUTO-DETECT] Pipeline: Fastboot ROM Flasher Engine")

                addTerminalLog("Checking target device mode...")

                // Auto-reboot ADB -> Fastboot if connected in ADB mode!

                refreshUsbDevices()

                val manager = usbManager ?: (context.getSystemService(Context.USB_SERVICE) as? UsbManager)

                val currentInfo = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

                val isAdb = (_uiState.value.deviceMode == DeviceMode.ADB) || (currentInfo?.mode == DeviceMode.ADB)

                if (isAdb && manager != null) {

                    addTerminalLog("---------------------------------------------")

                    addTerminalLog("[DETECTED] Target is in ADB mode (Android booted).")

                    addTerminalLog("→ Sending command: adb reboot bootloader...")

                    var rebootSent = false

                    for (dev in rootCandidateDevices()) {

                        if (!ensureUsbPermission(dev)) continue

                        val proto = try { AdbSideloadProtocol.open(manager, dev) } catch (_: Exception) { null } ?: continue

                        try {

                            try { rebootSent = proto.executeAdbRebootService("bootloader") } catch (_: Exception) {}

                            if (!rebootSent) {

                                try { if (proto.executeAdbShellCommand("reboot bootloader") != null) rebootSent = true } catch (_: Exception) {}

                            }

                            if (rebootSent) {

                                currentConnectedDevice = null

                                permissionRequestedIds.clear()

                                break

                            }

                        } finally {

                            try { proto.close() } catch (_: Exception) {}

                        }

                    }

                    if (rebootSent) {

                        addTerminalLog("[OK] Reboot command sent! Phone entering Fastboot mode...")

                    } else {

                        addTerminalLog("[!] Could not auto-reboot via ADB. Please switch phone to Fastboot manually.")

                    }

                } else if (_uiState.value.deviceMode == DeviceMode.FASTBOOT) {

                    addTerminalLog("[OK] Target phone is already verified in FASTBOOT mode.")

                }

                startExtractingArchive(context, archiveUri, archiveName)

            }

        }

    }

    fun startExtractingArchive(context: Context, archiveUri: Uri, archiveName: String) {

        extractionCancelled = false

        viewModelScope.launch(Dispatchers.IO) {

            _uiState.update {

                it.copy(

                    isExtracting = true,

                    extractionProgress = 0f,

                    extractingFileName = archiveName,

                    currentExtractingFile = "Initializing extraction...",

                    filesDoneCount = 0,

                    totalFilesCount = 0,

                    errorMessage = null

                )

            }

            try {

                addTerminalLog("[EXTRACTING] Unpacking ROM archive: " + archiveName)

                var lastLoggedPercent = -1

                var lastLogTime = 0L

                val extractedDir = FastbootRomFlasher.extractRomArchive(

                    context = context,

                    archiveUri = archiveUri,

                    archiveName = archiveName,

                    isCancelled = { extractionCancelled }

                ) { currentBytes, totalBytes, currentFile, filesDone, totalFiles ->

                    val frac = if (totalBytes > 0) (currentBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

                    _uiState.update {

                        it.copy(

                            isExtracting = true,

                            extractionProgress = frac,

                            currentExtractingFile = currentFile,

                            filesDoneCount = filesDone,

                            totalFilesCount = totalFiles

                        )

                    }

                    val pct = (frac * 100).toInt()

                    val now = System.currentTimeMillis()

                    if (pct != lastLoggedPercent && (pct % 5 == 0 || now - lastLogTime >= 1500L)) {

                        lastLoggedPercent = pct

                        lastLogTime = now

                        val mbDone = currentBytes / (1024 * 1024)

                        val mbTotal = totalBytes / (1024 * 1024)

                        val filePart = if (currentFile.isNotBlank()) " -> " + currentFile else ""

                        val sizePart = if (mbTotal > 0) " [" + mbDone + " MB / " + mbTotal + " MB]" else ""

                        addTerminalLog("[EXTRACTING] " + pct + "%" + sizePart + filePart)

                    }

                }

                addTerminalLog("[SUCCESS] Extraction completed successfully (100%).")

                addTerminalLog("[SCAN] Scanning for flash scripts (.bat)...")

                _uiState.update {

                    it.copy(

                        isExtracting = false,

                        extractionProgress = 1f,

                        currentExtractingFile = ""

                    )

                }

                scanExtractedFolder(extractedDir)

            } catch (e: CancellationException) {

                addTerminalLog("[EXTRACTING] Extraction cancelled by user.")

                _uiState.update {

                    it.copy(

                        isExtracting = false,

                        extractionProgress = 0f,

                        currentExtractingFile = "",

                        extractingFileName = ""

                    )

                }

            } catch (e: Exception) {

                e.printStackTrace()

                addTerminalLog("[ERROR] Extraction failed: " + (e.localizedMessage ?: (e.message ?: "")))

                _uiState.update {

                    it.copy(

                        isExtracting = false,

                        errorMessage = "Extraction failed: ${e.localizedMessage ?: e.message}"

                    )

                }

            }

        }

    }

    /** Requests the running archive extraction to stop at the next checkpoint. */

    fun cancelExtraction() {

        extractionCancelled = true

        _uiState.update {

            it.copy(

                isExtracting = false,

                extractionProgress = 0f,

                currentExtractingFile = "",

                extractingFileName = ""

            )

        }

    }

    fun scanExtractedFolder(folder: File) {

        viewModelScope.launch(Dispatchers.IO) {

            if (!folder.exists()) {

                _uiState.update {

                    it.copy(

                        errorMessage = "Selected folder does not exist: ${folder.path}"

                    )

                }

                return@launch

            }

            val batList = FastbootRomFlasher.findFlashScripts(folder)

            if (batList.isNotEmpty()) {

                addTerminalLog("[READY] Found flash script(s): " + batList.joinToString { it.name })

            }

            _uiState.update {

                it.copy(

                    foundBatFiles = batList,

                    showBatDialog = batList.isNotEmpty(),

                    showTerminal = if (batList.isNotEmpty()) false else it.showTerminal,

                    errorMessage = if (batList.isEmpty()) "No .bat flash script found in '${folder.name}'" else null

                )

            }

        }

    }

    var pendingFlashAction: (() -> Unit)? = null

    fun dismissBrickShieldDialog() {

        _uiState.update { it.copy(showBrickShieldMismatchDialog = false, brickShieldMismatchMessage = "") }

    }

    fun confirmArbDowngradeAndFlash() {

        _uiState.update { it.copy(showArbDowngradeDialog = false, arbDowngradeMessage = "") }

        val act = pendingFlashAction

        pendingFlashAction = null

        act?.invoke()

    }

    fun dismissArbDowngradeDialog() {

        pendingFlashAction = null

        _uiState.update { it.copy(showArbDowngradeDialog = false, arbDowngradeMessage = "") }

    }

    /**

     * Ensures target device is in Fastboot mode before flashing ROMs or partition images.

     * If the target is currently in ADB (Android/Recovery) mode, it automatically triggers

     * `adb reboot bootloader` and waits for Fastboot re-enumeration.

     * Returns the pair of (UsbManager, UsbDevice) in Fastboot mode, or null if timed out/failed.

     */

    suspend fun ensureFastbootModeReady(onLog: (String) -> Unit): Pair<UsbManager, UsbDevice>? {

        val manager = usbManager ?: run {

            onLog("[ERROR] USB Manager not available.")

            return null

        }

        // 1. Check if already in Fastboot

        if (kernelFastbootReady()) {

            val dev = currentConnectedDevice

            if (dev != null && ensureUsbPermission(dev)) {

                onLog(tr("[OK] Target phone verified in FASTBOOT mode.", "[OK] Target phone FASTBOOT mode me verified hai."))

                return Pair(manager, dev)

            }

        }

        // 2. If in ADB mode, automatically reboot to bootloader

        val currentInfo = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

        val isAdb = (_uiState.value.deviceMode == DeviceMode.ADB) || (currentInfo?.mode == DeviceMode.ADB)

        if (isAdb) {

            onLog("---------------------------------------------")

            onLog(tr("[AUTO-PREPARE] Target phone is in Android / ADB mode.", "[AUTO-PREPARE] Target phone Android / ADB mode me hai."))

            onLog(tr("[AUTO-PREPARE] Flashing requires Fastboot mode.", "[AUTO-PREPARE] Flashing ke liye Fastboot mode zaroori hai."))

            onLog(tr("→ Sending command: adb reboot bootloader...", "→ Command bhej rahe hain: adb reboot bootloader..."))

            var rebootSent = false

            for (dev in rootCandidateDevices()) {

                if (!ensureUsbPermission(dev)) continue

                val proto = try { AdbSideloadProtocol.open(manager, dev) } catch (_: Exception) { null } ?: continue

                try {

                    try { rebootSent = proto.executeAdbRebootService("bootloader") } catch (_: Exception) {}

                    if (!rebootSent) {

                        try { if (proto.executeAdbShellCommand("reboot bootloader") != null) rebootSent = true } catch (_: Exception) {}

                    }

                    if (!rebootSent) {

                        try { if (proto.executeAdbShellCommand("svc power reboot bootloader") != null) rebootSent = true } catch (_: Exception) {}

                    }

                    if (rebootSent) {

                        currentConnectedDevice = null

                        permissionRequestedIds.clear()

                        break

                    }

                } finally {

                    try { proto.close() } catch (_: Exception) {}

                }

            }

            if (rebootSent) {

                onLog(tr("[OK] Reboot command sent! Waiting for target to enter Fastboot mode (up to 45s)...", "[OK] Reboot command chali gayi! Phone ke Fastboot me aane ka wait (45s tak)..."))

            } else {

                onLog(tr("[!] Could not auto-reboot via ADB. Please put target phone into Fastboot manually (Power + Vol Down).", "[!] ADB se auto-reboot nahi ho paaya. Kripya phone ko manually Fastboot me daalein (Power + Vol Down)."))

            }

        } else {

            onLog(tr("[WAIT] Waiting for target phone connection in Fastboot mode...", "[WAIT] Fastboot mode me target phone connect hone ka wait..."))

        }

        // 3. Wait up to 50 seconds for target phone to re-enumerate in Fastboot

        val started = System.currentTimeMillis()

        val deadline = started + 50_000L

        var lastHintAt = started

        while (System.currentTimeMillis() < deadline) {

            val detected = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

            if (detected != null && detected.isConnected && detected.mode == DeviceMode.FASTBOOT && detected.usbDevice != null) {

                val fbDev = detected.usbDevice

                if (ensureUsbPermission(fbDev)) {

                    currentConnectedDevice = fbDev

                    _uiState.update { it.copy(isConnected = true, deviceMode = DeviceMode.FASTBOOT) }

                    delay(1200) // Allow endpoints to settle

                    onLog(tr("[SUCCESS] Target phone detected in FASTBOOT mode! Proceeding with flash...", "[SUCCESS] Target phone FASTBOOT mode me connect ho gaya! Flashing chalu ho rahi hai..."))

                    onLog("---------------------------------------------")

                    return Pair(manager, fbDev)

                }

            }

            val now = System.currentTimeMillis()

            if (now - lastHintAt >= 8000) {

                lastHintAt = now

                val waited = (now - started) / 1000

                onLog(tr("[WAIT] Waiting for Fastboot mode... (${waited}s)", "[WAIT] Fastboot mode ka wait... (${waited}s)"))

                onLog(tr("[HINT] If phone does not enter Fastboot, turn phone OFF and hold Power + Volume Down.", "[HINT] Agar phone Fastboot me na aaye, toh phone OFF karke Power + Volume Down dabao."))

            }

            delay(1000)

        }

        onLog(tr("[ERROR] Target phone did not enter Fastboot mode in time. Flashing aborted.", "[ERROR] Phone Fastboot mode me nahi aaya. Flashing cancel ho gayi."))

        return null

    }

    fun executeBatScript(batFile: File) {

        // 1. Instantly open terminal and dismiss dialog with 0ms delay!

        stopFlash(silent = true)

        _uiState.update {

            it.copy(

                showBatDialog = false,

                showTerminal = true,

                isFlashingRom = true,

                terminalLogs = listOf(

                    "Starting execution of: ${batFile.name}",

                    "Preparing target device..."

                ),

                isWaitingForInput = false,

                inputPrompt = ""

            )

        }

        beginProgressNotification("Flashing ROM: ${batFile.name}")

        val generation = ++flashGeneration

        flashRunning = true

        val job = viewModelScope.launch(Dispatchers.IO) {

            try {

                // Step 1: Ensure Fastboot mode is ready (auto-reboot ADB -> Fastboot if needed)

                val fbPair = ensureFastbootModeReady { msg -> addTerminalLog(msg) }

                if (fbPair == null) {

                    addTerminalLog("[ERROR] Flash aborted: Target phone must be in Fastboot mode.")

                    endProgressNotification("Flash aborted", "Phone not in Fastboot")

                    return@launch

                }

                val (freshManager, freshDevice) = fbPair

                // Step 2: Now that target is guaranteed in Fastboot, validate BrickShield!

                val targetCode = if (_uiState.value.deviceCodeName.isNotBlank() && _uiState.value.deviceCodeName != "fastboot_target") {

                    _uiState.value.deviceCodeName

                } else {

                    BrickShieldEngine.queryDeviceProduct(freshManager, freshDevice)

                }

                val romCode = BrickShieldEngine.detectRomCodename(batFile.parentFile ?: batFile)

                val validation = BrickShieldEngine.validateDeviceStrict(targetCode, romCode)

                if (!validation.isAllowed) {

                    AudioHapticEngine.playErrorHaptic()

                    addTerminalLog("[ERROR] ${validation.errorMessage ?: "Device mismatch detected! Flashing blocked."}")

                    _uiState.update {

                        it.copy(

                            showBrickShieldMismatchDialog = true,

                            brickShieldMismatchMessage = validation.errorMessage ?: "Device mismatch detected! Flashing blocked."

                        )

                    }

                    return@launch

                }

                // Step 3: Run the flash script

                FastbootRomFlasher.runFlashScript(

                    scriptFile = batFile,

                    usbManager = freshManager,

                    usbDevice = freshDevice,

                    onLog = { msg -> addTerminalLog(msg) },

                    onPromptInput = { prompt ->

                        _uiState.update { it.copy(isWaitingForInput = true, inputPrompt = prompt) }

                    }

                )

                endProgressNotification("Flash complete", batFile.name)

                AudioHapticEngine.speakCompletion(

                    englishText = "Sir, your ROM has been flashed successfully, sir!",

                    hinglishText = "Bhai, ROM successfully flash ho gayi hai!"

                )

            } catch (e: CancellationException) {

                addTerminalLog("[FLASH] Flash stopped.")

            } catch (e: Exception) {

                addTerminalLog("[ERROR] Flash failed: ${e.message}")

                endProgressNotification("Flash failed", e.message ?: batFile.name)

            } finally {

                if (flashGeneration == generation) {

                    flashRunning = false

                    flashJob = null

                }

                _uiState.update {

                    it.copy(isWaitingForInput = false, inputPrompt = "", isFlashingRom = false)

                }

            }

        }

        flashJob = job

    }

    /**

     * Stops the ROM flash that is running, if there is one.

     *

     * The script is cut short at the next safe point and the USB session is

     * dropped, so the phone is left alone and a reboot can run straight

     * afterwards.

     */

    fun stopFlash(silent: Boolean = false): Job? {

        val job = flashJob

        val hasAnyActiveFlash = flashRunning || job != null ||
            aospJob?.isActive == true || gsiJob?.isActive == true ||
            sideloadJob?.isActive == true || customImageJob?.isActive == true

        if (!hasAnyActiveFlash) {

            if (!silent) addTerminalLog("[FLASH] There is no flash running.")

            return null

        }

        flashGeneration++

        flashRunning = false
        aospCancelRequested = true

        flashJob = null

        if (!silent) addTerminalLog("[FLASH] Stopping the flash ...")

        FastbootRomFlasher.stopActiveFlash()

        job?.cancel()
        try { aospJob?.cancel() } catch (_: Exception) {}
        aospJob = null
        try { gsiJob?.cancel() } catch (_: Exception) {}
        gsiJob = null
        try { sideloadJob?.cancel() } catch (_: Exception) {}
        sideloadJob = null
        try { customImageJob?.cancel() } catch (_: Exception) {}
        customImageJob = null

        _uiState.update {
            it.copy(
                isWaitingForInput = false,
                inputPrompt = "",
                isFlashingRom = false,
                isAutoFlashingAosp = false,
                isFlashingGsi = false,
                isSideloadingPackage = false,
                isFlashingCustomImage = false
            )
        }

        endProgressNotification("Flash stopped", "Stopped by the user")

        if (!silent) addTerminalLog("[FLASH] Flash stopped by the user.")

        return job

    }

    // Option 2 Helper Actions

    fun selectPartition(partition: String) {

        _uiState.update { it.copy(selectedPartition = partition) }

    }

    fun setDualSlotChecked(checked: Boolean) {

        _uiState.update { it.copy(isDualSlotChecked = checked) }

    }

    fun processSelectedCustomImageUri(context: Context, uri: Uri) {

        viewModelScope.launch(Dispatchers.IO) {

            _uiState.update { it.copy(isPreparingCustomImageFile = true) }

            try {

                val fileName = getFileNameFromUri(context, uri)

                // Stage in the app's *external* files dir, not the internal cache:

                // a super image is 9 GB and would simply never fit in the internal

                // cache partition (this is the same reason the sideload flow uses it).

                val baseDir = context.getExternalFilesDir("customimage")

                    ?: File(context.cacheDir, "customimage")

                if (!baseDir.exists()) baseDir.mkdirs()

                val cacheFile = File(baseDir, fileName)

                // Best-effort free-space check; never block when the size is unknown.

                val needed = try {

                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L

                } catch (_: Exception) {

                    -1L

                }

                val availableBytes = try {

                    val stat = android.os.StatFs(baseDir.absolutePath)

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {

                        stat.availableBytes

                    } else {

                        @Suppress("DEPRECATION")

                        stat.availableBlocks.toLong() * stat.blockSize.toLong()

                    }

                } catch (_: Exception) {

                    -1L

                }

                if (needed > 0 && availableBytes > 0 && availableBytes < needed) {

                    throw IllegalStateException(

                        "Not enough free space (need ${needed / (1024 * 1024)} MB, " +

                            "only ${availableBytes / (1024 * 1024)} MB available)."

                    )

                }

                val stream = context.contentResolver.openInputStream(uri)

                    ?: throw IllegalStateException("Could not open the selected file.")

                stream.use { input ->

                    cacheFile.outputStream().use { output ->

                        input.copyTo(output)

                    }

                }

                _uiState.update {

                    it.copy(

                        selectedImageFile = cacheFile,

                        isPreparingCustomImageFile = false

                    )

                }

            } catch (e: Exception) {

                _uiState.update {

                    it.copy(

                        isPreparingCustomImageFile = false,

                        errorMessage = "Could not open image file: ${e.message}"

                    )

                }

            }

        }

    }

    fun clearSelectedImageFile() {

        _uiState.update { it.copy(selectedImageFile = null) }

    }

    fun bootCustomPartitionImage(context: Context) {

        val state = _uiState.value

        val file = state.selectedImageFile ?: return

        _uiState.update {

            it.copy(

                showTerminal = true,

                terminalLogs = listOf(

                    "Initiating Fastboot Boot Mode (Temporary)...",

                    "Image File: ${file.name} (${file.length() / (1024 * 1024)} MB)",

                    "Mode: fastboot boot (No partition overwritten)",

                    "---------------------------------------------"

                ),

                isFlashingCustomImage = true

            )

        }

        refreshUsbDevices()

        val device = currentConnectedDevice

        val manager = usbManager

        if (manager != null && device != null && !manager.hasPermission(device)) {

            addTerminalLog("[ACTION REQUIRED] USB permission is required to talk to the phone.")

            addTerminalLog("→ A system popup should appear; tap OK, then press BOOT again.")

            try {
                permissionPendingIntent(device)?.let { manager.requestPermission(device, it) }
            } catch (_: Exception) {}

            _uiState.update { it.copy(isFlashingCustomImage = false) }

            return

        }

        viewModelScope.launch(Dispatchers.IO) {

            val fbPair = ensureFastbootModeReady { msg -> addTerminalLog(msg) }

            if (fbPair == null) {

                addTerminalLog("[ERROR] Custom image boot aborted: Target phone must be in Fastboot mode.")

                _uiState.update { it.copy(isFlashingCustomImage = false) }

                return@launch

            }

            val (freshManager, freshDevice) = fbPair

            try {

                FastbootRomFlasher.bootCustomImage(

                    usbManager = freshManager,

                    usbDevice = freshDevice,

                    imageFile = file,

                    onLog = { log -> addTerminalLog(log) }

                )

            } catch (e: Exception) {

                addTerminalLog("[ERROR] Custom image boot failed: ${e.message}")

            } finally {

                _uiState.update { it.copy(isFlashingCustomImage = false) }

            }

        }

    }

    fun flashCustomPartitionImage(context: Context) {

        val state = _uiState.value

        val file = state.selectedImageFile ?: return

        val partition = state.selectedPartition

        val isDualSlot = state.isDualSlotChecked

        _uiState.update {

            it.copy(

                showTerminal = true,

                terminalLogs = listOf(

                    "Initiating Single Image Flash Mode...",

                    "Target Partition: $partition",

                    "Dual Slot (A/B): $isDualSlot",

                    "Image File: ${file.name} (${file.length() / (1024 * 1024)} MB)",

                    "---------------------------------------------"

                ),

                isFlashingCustomImage = true

            )

        }

        refreshUsbDevices()

        val device = currentConnectedDevice

        val manager = usbManager

        // Same USB-permission handling as every other engine flow: without it the

        // session just fails with a confusing "could not open" error.

        if (manager != null && device != null && !manager.hasPermission(device)) {

            addTerminalLog("[ACTION REQUIRED] USB permission is required to talk to the phone.")

            addTerminalLog("→ A system popup should appear (or unlock the phone); tap OK, then press FLASH again.")

            try {
                permissionPendingIntent(device)?.let { manager.requestPermission(device, it) }
            } catch (_: Exception) {
            }

            _uiState.update { it.copy(isFlashingCustomImage = false) }

            return

        }

        customImageJob = viewModelScope.launch(Dispatchers.IO) {

            val fbPair = ensureFastbootModeReady { msg -> addTerminalLog(msg) }

            if (fbPair == null) {

                addTerminalLog("[ERROR] Custom image flash aborted: Target phone must be in Fastboot mode.")

                _uiState.update { it.copy(isFlashingCustomImage = false) }

                return@launch

            }

            val (freshManager, freshDevice) = fbPair

            try {

                FastbootRomFlasher.flashCustomImage(

                    usbManager = freshManager,

                    usbDevice = freshDevice,

                    partition = partition,

                    imageFile = file,

                    isDualSlot = isDualSlot,

                    cacheDir = context.cacheDir,

                    onLog = { log -> addTerminalLog(log) }

                )

            } catch (e: Exception) {

                addTerminalLog("[ERROR] Custom image flash failed: ${e.message}")

            } finally {

                _uiState.update { it.copy(isFlashingCustomImage = false) }

            }

        }

    }

    /**

     * Executes arbitrary user commands with full ADB & Fastboot engine support.

     * Smartly binds selected image files for flash commands (e.g. `fastboot flash empty vbmeta`).

     */

    fun executeCustomCommandLine(rawCommandLine: String, context: Context) {

        val trimmedCmd = rawCommandLine.trim()

        if (trimmedCmd.isBlank()) return

        _uiState.update {

            it.copy(

                showTerminal = true,

                terminalLogs = it.terminalLogs + listOf("> $trimmedCmd")

            )

        }

        viewModelScope.launch(Dispatchers.IO) {

            processCustomCommandLine(trimmedCmd, context)

        }

    }

    private suspend fun processCustomCommandLine(rawCmd: String, context: Context) {

        val trimmed = rawCmd.trim()

        val lower = trimmed.lowercase()

        // 1. Device check / devices listing (matches PC ADB / Fastboot 1:1 output)

        if (lower == "adb devices" || lower == "adb devices -l") {

            addTerminalLog("List of devices attached")

            val currentInfo = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

            val isAdb = (_uiState.value.deviceMode == DeviceMode.ADB) || (currentInfo?.mode == DeviceMode.ADB)

            if (isAdb) {

                val serial = currentInfo?.usbDevice?.serialNumber?.ifBlank { null }

                    ?: currentConnectedDevice?.serialNumber?.ifBlank { null }

                    ?: _uiState.value.deviceCodeName.ifBlank { null }

                    ?: "romflasher_adb_device"

                addTerminalLog("$serial\tdevice")

            } else if (_uiState.value.deviceMode == DeviceMode.FASTBOOT || kernelFastbootReady()) {

                addTerminalLog("(no devices attached in ADB mode — target device is in FASTBOOT mode)")

            } else {

                addTerminalLog("(no devices attached — check USB/OTG cable connection)")

            }

            return

        }

        if (lower == "fastboot devices" || lower == "fastboot devices -l") {

            val currentInfo = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

            val isFb = (_uiState.value.deviceMode == DeviceMode.FASTBOOT) || (currentInfo?.mode == DeviceMode.FASTBOOT) || kernelFastbootReady()

            if (isFb) {

                val serial = currentInfo?.usbDevice?.serialNumber?.ifBlank { null }

                    ?: currentConnectedDevice?.serialNumber?.ifBlank { null }

                    ?: _uiState.value.deviceCodeName.ifBlank { null }

                    ?: "romflasher_fastboot_device"

                addTerminalLog("$serial\tfastboot")

            } else if (_uiState.value.deviceMode == DeviceMode.ADB) {

                addTerminalLog("(no devices attached in Fastboot mode — target device is in ADB mode)")

                addTerminalLog("[TIP] Run 'adb reboot bootloader' to switch to Fastboot mode.")

            } else {

                addTerminalLog("(no devices attached — check USB/OTG cable connection)")

            }

            return

        }

        if (lower == "devices") {

            addTerminalLog("--- Connected Devices Check ---")

            val currentInfo = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

            if (_uiState.value.deviceMode == DeviceMode.FASTBOOT || kernelFastbootReady()) {

                val serial = currentInfo?.usbDevice?.serialNumber ?: currentConnectedDevice?.serialNumber ?: "fastboot_device"

                addTerminalLog("$serial\tfastboot")

            } else if (_uiState.value.deviceMode == DeviceMode.ADB) {

                val serial = currentInfo?.usbDevice?.serialNumber ?: currentConnectedDevice?.serialNumber ?: "adb_device"

                addTerminalLog("$serial\tdevice")

            } else {

                addTerminalLog("No devices detected.")

            }

            return

        }

        // 2. ADB commands

        if (lower.startsWith("adb ") || lower == "adb") {

            if (lower.startsWith("adb reboot")) {

                val parts = trimmed.split("\\s+".toRegex())

                val target = if (parts.size >= 3) parts[2] else ""

                addTerminalLog("[ADB] Rebooting device${if (target.isNotBlank()) " to $target" else ""}...")

                val mgr = usbManager ?: run {

                    addTerminalLog("[ERROR] USB Manager not available.")

                    return

                }

                var rebootSuccess = false

                for (dev in rootCandidateDevices()) {

                    if (!ensureUsbPermission(dev)) continue

                    val proto = try { AdbSideloadProtocol.open(mgr, dev) } catch (_: Exception) { null } ?: continue

                    try {

                        rebootSuccess = proto.executeAdbRebootService(target) { log -> addTerminalLog(log) }

                        if (!rebootSuccess && target.isNotBlank()) {

                            rebootSuccess = proto.executeAdbShellCommand("reboot $target") != null

                        }

                    } catch (e: Exception) {

                        addTerminalLog("[ERROR] ${e.message}")

                    } finally {

                        try { proto.close() } catch (_: Exception) {}

                    }

                    if (rebootSuccess) break

                }

                if (rebootSuccess) {

                    addTerminalLog("[OK] Reboot command sent successfully.")

                } else {

                    addTerminalLog("[ERROR] Failed to send adb reboot. Make sure target phone is in ADB mode with USB Debugging enabled.")

                }

                return

            }

            if (lower.startsWith("adb shell ")) {

                val shellCommand = trimmed.substring(10).trim()

                addTerminalLog("[ADB SHELL] Executing: $shellCommand")

                val output = rootAdbShell(shellCommand)

                if (output != null) {

                    output.lines().forEach { line -> addTerminalLog(line) }

                    addTerminalLog("[OK] Command completed.")

                } else {

                    addTerminalLog("[ERROR] Failed to execute ADB shell command or device not in ADB mode.")

                }

                return

            }

            val sub = trimmed.removePrefix("adb").trim()

            if (sub.isNotBlank()) {

                val output = rootAdbShell(sub)

                if (output != null) {

                    output.lines().forEach { addTerminalLog(it) }

                } else {

                    addTerminalLog("[ADB] Command finished.")

                }

            }

            return

        }

        // 3. Fastboot commands & Flashing commands

        val tokens = trimmed.split("\\s+".toRegex()).toMutableList()

        val isExplicitFastboot = tokens.firstOrNull()?.equals("fastboot", ignoreCase = true) == true

        if (isExplicitFastboot) {

            tokens.removeAt(0)

        }

        val isFlashCmd = tokens.any { it.equals("flash", ignoreCase = true) }

        if (isFlashCmd) {

            val fbPair = ensureFastbootModeReady { msg -> addTerminalLog(msg) }

            if (fbPair == null) {

                addTerminalLog("[ERROR] Fastboot mode not ready. Connect device in Fastboot mode.")

                return

            }

            val (freshManager, freshDevice) = fbPair

            // Filter out flags like --disable-verity, --disable-verification

            tokens.removeAll { it.startsWith("--disable") || it.startsWith("-S") }

            // Locate flash index

            val flashIdx = tokens.indexOfFirst { it.equals("flash", ignoreCase = true) }

            val argsAfterFlash = if (flashIdx >= 0 && flashIdx < tokens.size - 1) {

                tokens.subList(flashIdx + 1, tokens.size).toMutableList()

            } else {

                mutableListOf()

            }

            // Determine image file

            var fileToFlash: File? = null

            if (argsAfterFlash.isNotEmpty()) {

                val lastArg = argsAfterFlash.last()

                val candidateFile = File(lastArg)

                if (lastArg.endsWith(".img", ignoreCase = true) && candidateFile.exists()) {

                    fileToFlash = candidateFile

                    argsAfterFlash.removeAt(argsAfterFlash.lastIndex)

                } else if (lastArg.equals("<selected.img>", ignoreCase = true) ||

                           lastArg.equals("<image>", ignoreCase = true) ||

                           lastArg.equals("<file>", ignoreCase = true)) {

                    argsAfterFlash.removeAt(argsAfterFlash.lastIndex)

                    fileToFlash = _uiState.value.selectedImageFile

                }

            }

            if (fileToFlash == null) {

                fileToFlash = _uiState.value.selectedImageFile

            }

            if (fileToFlash == null) {

                addTerminalLog("[ERROR] No .img file selected! Please select an image using 'SELECT IMAGE (.img)' below.")

                return

            }

            // Determine partition name

            val knownPartitions = listOf(

                "vbmeta", "vbmeta_system", "vbmeta_vendor", "boot", "init_boot",

                "vendor_boot", "recovery", "super", "dtbo", "system", "vendor",

                "product", "odm", "cust", "userdata", "modem", "persist"

            )

            val targetPartition = when {

                argsAfterFlash.any { it.equals("vbmeta", ignoreCase = true) } -> "vbmeta"

                argsAfterFlash.any { knownPartitions.contains(it.lowercase()) } -> {

                    argsAfterFlash.first { knownPartitions.contains(it.lowercase()) }.lowercase()

                }

                argsAfterFlash.isNotEmpty() -> argsAfterFlash.last().lowercase()

                else -> _uiState.value.selectedPartition

            }

            addTerminalLog("Initiating Single Image Flash Mode...")

            addTerminalLog("Target Partition: $targetPartition")

            addTerminalLog("Dual Slot (A/B): ${_uiState.value.isDualSlotChecked}")

            addTerminalLog("Image File: ${fileToFlash.name} (${fileToFlash.length() / (1024 * 1024)} MB)")

            addTerminalLog("---------------------------------------------")

            try {

                FastbootRomFlasher.flashCustomImage(

                    usbManager = freshManager,

                    usbDevice = freshDevice,

                    partition = targetPartition,

                    imageFile = fileToFlash,

                    isDualSlot = _uiState.value.isDualSlotChecked,

                    cacheDir = context.cacheDir,

                    onLog = { log -> addTerminalLog(log) }

                )

            } catch (e: Exception) {

                addTerminalLog("[ERROR] Flashing failed: ${e.message}")

            }

            return

        }

        // Other Fastboot commands (reboot, getvar, erase, set_active, oem, etc.)

        val fbPair = ensureFastbootModeReady { msg -> addTerminalLog(msg) }

        if (fbPair == null) {

            addTerminalLog("[ERROR] Fastboot mode not ready. Connect device in Fastboot mode.")

            return

        }

        val (freshManager, freshDevice) = fbPair

        val fastbootCommand = if (isExplicitFastboot) {

            trimmed.substring(8).trim()

        } else {

            trimmed

        }

        val fbLower = fastbootCommand.lowercase()

        try {

            when {

                fbLower == "reboot" -> {

                    addTerminalLog("Rebooting device...")

                    val res = FastbootProtocol.executeGenericCommand(freshManager, freshDevice, "reboot")

                    addTerminalLog(res ?: "Rebooting... OKAY")

                }

                fbLower == "reboot bootloader" || fbLower == "reboot-bootloader" -> {

                    addTerminalLog("Rebooting to bootloader...")

                    val res = FastbootProtocol.executeGenericCommand(freshManager, freshDevice, "reboot-bootloader")

                    addTerminalLog(res ?: "Rebooting to bootloader... OKAY")

                }

                fbLower == "reboot recovery" || fbLower == "reboot-recovery" -> {

                    addTerminalLog("Rebooting to recovery...")

                    val res = FastbootProtocol.executeGenericCommand(freshManager, freshDevice, "reboot-recovery")

                    addTerminalLog(res ?: "Rebooting to recovery... OKAY")

                }

                fbLower == "reboot fastboot" || fbLower == "reboot-fastboot" -> {

                    addTerminalLog("Rebooting to fastbootd...")

                    val res = FastbootProtocol.executeGenericCommand(freshManager, freshDevice, "reboot-fastboot")

                    addTerminalLog(res ?: "Rebooting to fastbootd... OKAY")

                }

                fbLower.startsWith("getvar ") -> {

                    val varName = fastbootCommand.substring(7).trim()

                    val res = FastbootProtocol.executeGenericCommand(freshManager, freshDevice, "getvar:$varName")

                    res?.lines()?.forEach { addTerminalLog(it) } ?: addTerminalLog("OKAY")

                }

                fbLower.startsWith("erase ") -> {

                    val part = fastbootCommand.substring(6).trim()

                    addTerminalLog("Erasing '$part'...")

                    val res = FastbootProtocol.executeGenericCommand(freshManager, freshDevice, "erase:$part")

                    res?.lines()?.forEach { addTerminalLog(it) } ?: addTerminalLog("OKAY")

                }

                fbLower.startsWith("set_active ") -> {

                    val slot = fastbootCommand.substring(11).trim()

                    addTerminalLog("Setting active slot to '$slot'...")

                    val res = FastbootProtocol.executeGenericCommand(freshManager, freshDevice, "set_active:$slot")

                    res?.lines()?.forEach { addTerminalLog(it) } ?: addTerminalLog("OKAY")

                }

                else -> {

                    addTerminalLog("[FASTBOOT] Sending: $fastbootCommand")

                    val res = FastbootProtocol.executeGenericCommand(freshManager, freshDevice, fastbootCommand)

                    if (res != null) {

                        res.lines().forEach { addTerminalLog(it) }

                    } else {

                        addTerminalLog("[FASTBOOT] Command finished.")

                    }

                }

            }

        } catch (e: Exception) {

            addTerminalLog("[ERROR] Command failed: ${e.message}")

        }

    }

    // Option 3 Helper Actions (ADB Sideload)

    fun processSelectedSideloadUri(context: Context, uri: Uri) {

        viewModelScope.launch(Dispatchers.IO) {

            _uiState.update { it.copy(isPreparingSideloadFile = true) }

            try {

                val fileName = getFileNameFromUri(context, uri)

                // Prefer the app-specific external dir: a multi-GB ROM will very

                // often not fit inside the small internal cache partition.

                val baseDir = context.getExternalFilesDir("sideload")

                    ?: File(context.cacheDir, "sideload")

                if (!baseDir.exists()) baseDir.mkdirs()

                val cacheFile = File(baseDir, fileName)

                // Best-effort free space check. NOTE: File.usableSpace() is

                // unreliable on Android (it can report 0 even with lots of free

                // space), so use StatFs and never block when the value is unknown.

                val needed = try {

                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L

                } catch (_: Exception) {

                    -1L

                }

                val availableBytes = try {

                    val stat = android.os.StatFs(baseDir.absolutePath)

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {

                        stat.availableBytes

                    } else {

                        @Suppress("DEPRECATION")

                        stat.availableBlocks.toLong() * stat.blockSize.toLong()

                    }

                } catch (_: Exception) {

                    -1L

                }

                if (needed > 0 && availableBytes > 0 && availableBytes < needed) {

                    throw IllegalStateException(

                        "Not enough free space (need ${needed / (1024 * 1024)} MB, " +

                            "only ${availableBytes / (1024 * 1024)} MB available)."

                    )

                }

                val stream = context.contentResolver.openInputStream(uri)

                    ?: throw IllegalStateException("Could not open the selected file.")

                stream.use { input ->

                    cacheFile.outputStream().use { output ->

                        input.copyTo(output)

                    }

                }

                _uiState.update {

                    it.copy(

                        selectedSideloadFile = cacheFile,

                        isPreparingSideloadFile = false

                    )

                }

            } catch (e: Exception) {

                _uiState.update {

                    it.copy(

                        isPreparingSideloadFile = false,

                        errorMessage = "Failed to load sideload package: ${e.message}"

                    )

                }

            }

        }

    }

    fun clearSelectedSideloadFile() {

        _uiState.update { it.copy(selectedSideloadFile = null) }

    }

    fun flashSideloadPackage() {

        val state = _uiState.value

        val file = state.selectedSideloadFile ?: return

        _uiState.update {

            it.copy(

                showTerminal = true,

                terminalLogs = listOf(

                    "Initiating ADB Sideload Mode...",

                    "Package File: ${file.name} (${file.length() / (1024 * 1024)} MB)",

                    "---------------------------------------------"

                ),

                isSideloadingPackage = true

            )

        }

        refreshUsbDevices()

        val device = currentConnectedDevice

        val manager = usbManager

        sideloadJob = viewModelScope.launch(Dispatchers.IO) {

            if (manager == null || device == null) {

                addTerminalLog("[ERROR] Target phone not connected in Recovery mode. Connect phone via OTG in ADB Sideload mode.")

                _uiState.update { it.copy(isSideloadingPackage = false) }

                return@launch

            }

            if (!file.exists()) {

                addTerminalLog("[ERROR] Sideload package is missing. Please select the ROM zip again.")

                _uiState.update { it.copy(isSideloadingPackage = false, selectedSideloadFile = null) }

                return@launch

            }

            if (!manager.hasPermission(device)) {

                addTerminalLog("[ACTION REQUIRED] USB permission is required to talk to the phone.")

                addTerminalLog("→ A system popup should appear (or unlock the phone); tap OK, then press FLASH again.")

                _uiState.update { it.copy(isSideloadingPackage = false) }

                return@launch

            }

            try {

                val success = FastbootRomFlasher.sideloadPackage(

                    usbManager = manager,

                    usbDevice = device,

                    packageFile = file,

                    onLog = { log -> addTerminalLog(log) }

                )

                if (success) {

                    AudioHapticEngine.speakCompletion(

                        englishText = "Sir, your package has been sideloaded successfully, sir!",

                        hinglishText = "Bhai, sideload complete ho gaya hai!"

                    )

                    // Reclaim the (possibly multi-GB) temporary package after a clean transfer.

                    try { file.delete() } catch (_: Exception) {}

                    _uiState.update { it.copy(isSideloadingPackage = false, selectedSideloadFile = null) }

                } else {

                    _uiState.update { it.copy(isSideloadingPackage = false) }

                }

            } catch (e: Exception) {

                addTerminalLog("[ERROR] ADB sideload failed: ${e.message}")

                _uiState.update { it.copy(isSideloadingPackage = false) }

            }

        }

    }

    // ------------------------------------------------------------------------
    // 1-CLICK VBMETA & DM-VERITY DISABLER
    // ------------------------------------------------------------------------
    fun disableVbmetaVerity() {
        AudioHapticEngine.playClickHaptic()
        val manager = usbManager
        val device = currentConnectedDevice
        if (manager == null || device == null) {
            addTerminalLog("[!] No USB device attached. Please connect phone via OTG.")
            AudioHapticEngine.playErrorHaptic()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isFlashingRom = true) }
            val session = try { FastbootSession.open(manager, device) } catch (_: Exception) { null }
            if (session == null) {
                addTerminalLog("[ERROR] Failed to open FastbootSession. Ensure device is in Fastboot.")
                AudioHapticEngine.playErrorHaptic()
                _uiState.update { it.copy(isFlashingRom = false) }
                return@launch
            }

            try {
                val customVbmeta = _uiState.value.selectedCustomVbmetaFile
                val ok = VbmetaDisablerEngine.disableVbmeta(
                    session = session,
                    customVbmeta = customVbmeta,
                    dualSlot = true,
                    cacheDir = appContext?.cacheDir ?: RomFlasherStorage.child("Cache"),
                    onLog = { addTerminalLog(it) }
                )
                if (ok) {
                    AudioHapticEngine.speakCompletion(
                        englishText = "VBMeta verity disabled successfully, sir!",
                        hinglishText = "VBMeta verity disable ho gaya hai bhai!"
                    )
                } else {
                    AudioHapticEngine.playErrorHaptic()
                }
            } finally {
                session.close()
                _uiState.update { it.copy(isFlashingRom = false) }
            }
        }
    }

    // ------------------------------------------------------------------------
    // 1-CLICK GSI FLASHER
    // ------------------------------------------------------------------------
    fun processSelectedGsiUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(showTerminal = true) }
                addTerminalLog("[i] Inspecting selected GSI file...")

                var originalName = "system.img"
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) originalName = name
                        }
                    }
                } catch (_: Exception) {}

                val lowerName = originalName.lowercase()
                val isCompressedExtension = lowerName.endsWith(".xz") || lowerName.endsWith(".zip") ||
                        lowerName.endsWith(".gz") || lowerName.endsWith(".7z") ||
                        lowerName.endsWith(".rar") || lowerName.endsWith(".tar")

                // Check magic bytes before copying gigabytes of data!
                val headerBytes = ByteArray(8)
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.read(headerBytes) }
                } catch (_: Exception) {}

                val isXzMagic = headerBytes.size >= 6 &&
                        headerBytes[0] == 0xFD.toByte() && headerBytes[1] == 0x37.toByte() &&
                        headerBytes[2] == 0x7A.toByte() && headerBytes[3] == 0x58.toByte() &&
                        headerBytes[4] == 0x5A.toByte() && headerBytes[5] == 0x00.toByte()
                val isZipMagic = headerBytes.size >= 4 &&
                        headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte() &&
                        headerBytes[2] == 0x03.toByte() && headerBytes[3] == 0x04.toByte()
                val isGzMagic = headerBytes.size >= 2 &&
                        headerBytes[0] == 0x1F.toByte() && headerBytes[1] == 0x8B.toByte()

                if (isCompressedExtension || isXzMagic || isZipMagic || isGzMagic) {
                    addTerminalLog("==========================================")
                    addTerminalLog("❌ [ERROR] COMPRESSED ARCHIVE: $originalName")
                    addTerminalLog("==========================================")
                    addTerminalLog("⚠️ GSI cannot be flashed in compressed (.xz/.zip) format!")
                    addTerminalLog("👉 Flashing a compressed archive will CORRUPT the system partition.")
                    addTerminalLog("ℹ️ Please extract the archive using ZArchiver or 7-Zip app first.")
                    addTerminalLog("👉 Select the uncompressed raw .img file (e.g. system.img).")
                    addTerminalLog("------------------------------------------")
                    addTerminalLog("Hinglish: Kripya ZArchiver se extract karke raw .img file select kijiye!")
                    AudioHapticEngine.playErrorHaptic()
                    _uiState.update { it.copy(selectedGsiFile = null) }
                    return@launch
                }

                val cacheDir = context.cacheDir
                val tempFile = File(cacheDir, "gsi_selected.img")
                addTerminalLog("[i] Reading uncompressed GSI image: $originalName...")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Verify file header after loading
                val isSparse = try {
                    val buf = ByteArray(4)
                    FileInputStream(tempFile).use { it.read(buf) }
                    buf[0] == 0x3a.toByte() && buf[1] == 0xff.toByte() && buf[2] == 0x26.toByte() && buf[3] == 0xed.toByte()
                } catch (_: Exception) { false }

                val sizeMb = tempFile.length() / (1024 * 1024)
                addTerminalLog("[OK] GSI Image Loaded: $originalName ($sizeMb MB)")
                addTerminalLog("   → Format: ${if (isSparse) "Android Sparse Image" else "Raw Ext4/EROFS Filesystem"}")
                addTerminalLog("   🔒 [VERIFIED] Valid uncompressed disk image ready for flashing.")

                _uiState.update { it.copy(selectedGsiFile = tempFile) }
                AudioHapticEngine.playClickHaptic()
            } catch (e: Exception) {
                addTerminalLog("[ERROR] Failed to load GSI Image: ${e.message}")
                AudioHapticEngine.playErrorHaptic()
                _uiState.update { it.copy(selectedGsiFile = null) }
            }
        }
    }

    fun clearGsiFile() {
        _uiState.update { it.copy(selectedGsiFile = null) }
    }

    fun processSelectedCustomVbmetaUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(showTerminal = true) }
                addTerminalLog("[i] Reading custom stock VBMeta from URI...")
                val cacheDir = context.cacheDir
                val tempFile = File(cacheDir, "custom_stock_vbmeta.img")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                _uiState.update { it.copy(selectedCustomVbmetaFile = tempFile) }
                addTerminalLog("[OK] Custom Stock VBMeta Loaded: ${tempFile.name} (${tempFile.length()} bytes)")
                addTerminalLog("   🔒 Will patch verification flags on genuine stock VBMeta (100% safe for MediaTek/Samsung).")
                AudioHapticEngine.playClickHaptic()
            } catch (e: Exception) {
                addTerminalLog("[ERROR] Failed to load custom VBMeta: ${e.message}")
                AudioHapticEngine.playErrorHaptic()
            }
        }
    }

    fun clearCustomVbmetaFile() {
        _uiState.update { it.copy(selectedCustomVbmetaFile = null) }
    }

    fun setGsiDisableVbmeta(checked: Boolean) {
        _uiState.update { it.copy(gsiDisableVbmeta = checked) }
    }

    fun setGsiWipeUserdata(checked: Boolean) {
        _uiState.update { it.copy(gsiWipeUserdata = checked) }
    }

    fun setGsiFreeSuperSpace(checked: Boolean) {
        _uiState.update { it.copy(gsiFreeSuperSpace = checked) }
    }

    fun setGsiRebootFastbootd(checked: Boolean) {
        _uiState.update { it.copy(gsiRebootFastbootd = checked) }
    }

    fun rebootToFastbootd() {
        val mode = if (_uiState.value.deviceMode == DeviceMode.ADB) "ADB" else "FASTBOOT"
        executeRebootCommand(mode, "reboot-fastboot")
    }

    fun flashGsi() {
        AudioHapticEngine.playClickHaptic()
        val gsiFile = _uiState.value.selectedGsiFile
        if (gsiFile == null || !gsiFile.exists()) {
            addTerminalLog("[!] No GSI Image selected. Please pick an uncompressed .img file.")
            AudioHapticEngine.playErrorHaptic()
            return
        }

        val manager = usbManager
        val device = currentConnectedDevice
        if (manager == null || device == null) {
            addTerminalLog("[!] No USB device attached. Please connect phone via OTG.")
            AudioHapticEngine.playErrorHaptic()
            return
        }

        gsiJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isFlashingRom = true, isFlashingGsi = true) }
            var session = try { FastbootSession.open(manager, device) } catch (_: Exception) { null }
            if (session == null) {
                addTerminalLog("[ERROR] Failed to open FastbootSession. Ensure device is in Fastboot.")
                AudioHapticEngine.playErrorHaptic()
                _uiState.update { it.copy(isFlashingRom = false, isFlashingGsi = false) }
                return@launch
            }

            try {
                // Check if device is in userspace fastbootd
                val isUserspace = session.getVariable("is-userspace")?.trim()?.lowercase() == "yes"
                if (!isUserspace && _uiState.value.gsiRebootFastbootd) {
                    addTerminalLog("==========================================")
                    addTerminalLog(tr("📱 BOOTLOADER FASTBOOT DETECTED", "📱 BOOTLOADER FASTBOOT DETECTED"))
                    addTerminalLog("==========================================")
                    addTerminalLog(tr("→ Android 10+ requires FASTBOOTD mode for dynamic 'system' partition.", 
                                      "→ Dynamic system ke liye Fastbootd mode zaroori hai."))
                    addTerminalLog(tr("🔄 Automatically rebooting target phone into Fastbootd...", 
                                      "🔄 Target phone ko Fastbootd mode me reboot kiya ja raha hai..."))
                    
                    session.executeGenericCommand("reboot-fastboot") ?: session.executeGenericCommand("reboot fastboot")
                    try { session.close() } catch (_: Exception) {}

                    addTerminalLog(tr("⏳ Waiting for phone to boot into Fastbootd (up to 45s)...", 
                                      "⏳ Fastbootd connection ka intezaar (45s tak)..."))

                    val inFastbootd = waitForDevice(DeviceMode.FASTBOOT, 45000)
                    if (!inFastbootd) {
                        addTerminalLog(tr("[!] Fastbootd not detected within 45s. Please connect OTG cable properly.", 
                                          "[!] Fastbootd connect nahi hua. OTG cable check karein."))
                        AudioHapticEngine.playErrorHaptic()
                        return@launch
                    }

                    delay(2000)
                    val fastbootdDev = currentConnectedDevice ?: rootCandidateDevices().firstOrNull()
                    if (fastbootdDev == null) {
                        addTerminalLog(tr("[ERROR] Fastbootd device not found after reboot.", "[ERROR] Fastbootd device nahi mila."))
                        AudioHapticEngine.playErrorHaptic()
                        return@launch
                    }

                    if (!ensureUsbPermission(fastbootdDev)) {
                        addTerminalLog(tr("[ERROR] USB permission denied for Fastbootd device.", "[ERROR] Fastbootd device ke liye USB permission nahi mili."))
                        AudioHapticEngine.playErrorHaptic()
                        return@launch
                    }

                    val freshSession = try { FastbootSession.open(manager, fastbootdDev) } catch (_: Exception) { null }
                    if (freshSession == null) {
                        addTerminalLog(tr("[ERROR] Failed to open Fastbootd session. Check USB permission.", "[ERROR] Fastbootd session open nahi ho paya."))
                        AudioHapticEngine.playErrorHaptic()
                        return@launch
                    }

                    session = freshSession
                    addTerminalLog(tr("[OK] Connected to FASTBOOTD (Userspace)! Resuming GSI flash...", "[OK] FASTBOOTD mode connect ho gaya! GSI flash shuru ho rahi hai..."))
                }

                val ok = GsiFlasherEngine.flashGsi(
                    session = session,
                    gsiFile = gsiFile,
                    customVbmeta = _uiState.value.selectedCustomVbmetaFile,
                    disableVbmeta = _uiState.value.gsiDisableVbmeta,
                    wipeUserdata = _uiState.value.gsiWipeUserdata,
                    freeSuperSpace = _uiState.value.gsiFreeSuperSpace,
                    cacheDir = appContext?.cacheDir ?: RomFlasherStorage.child("Cache"),
                    onLog = { addTerminalLog(it) }
                )
                if (ok) {
                    AudioHapticEngine.speakCompletion(
                        englishText = "GSI flashed successfully, sir!",
                        hinglishText = "GSI successfully flash ho gaya hai bhai!"
                    )
                } else {
                    AudioHapticEngine.playErrorHaptic()
                }
            } finally {
                try { session?.close() } catch (_: Exception) {}
                _uiState.update { it.copy(isFlashingRom = false, isFlashingGsi = false) }
            }
        }
    }

    // Option 2 AOSP Auto Flasher Action

    fun startAospAutoFlasher() {

        val state = _uiState.value

        val file = state.selectedSideloadFile ?: return

        if (state.isAutoFlashingAosp || state.isSideloadingPackage) return

        _uiState.update {

            it.copy(

                showTerminal = true,

                terminalLogs = listOf(

                    "Starting ⚡ AOSP Auto Flasher Pipeline...",

                    "Selected Package: ${file.name} (${file.length() / (1024 * 1024)} MB)",

                    "---------------------------------------------"

                ),

                isAutoFlashingAosp = true

            )

        }

        refreshUsbDevices()

        val device = currentConnectedDevice

        val context = appContext ?: com.romflasher.tool.AppContext.context

        if (context == null) {

            addTerminalLog("[ERROR] Application context unavailable.")

            _uiState.update { it.copy(isAutoFlashingAosp = false) }

            return

        }

        val manager = usbManager ?: context.getSystemService(android.content.Context.USB_SERVICE) as? android.hardware.usb.UsbManager

        aospCancelRequested = false
        aospJob = viewModelScope.launch(Dispatchers.IO) {

            try {

                if (manager == null) {

                    addTerminalLog("[ERROR] USB Manager unavailable.")

                    return@launch

                }

                val ok = com.romflasher.tool.engine.aosp.AospAutoFlasherEngine.runAutoFlasher(

                    context = context,

                    usbManager = manager,

                    initialDevice = device,

                    zipFile = file,

                    onLog = { l -> addTerminalLog(l) },
                    isCancelled = { aospCancelRequested || aospJob?.isActive != true }
                )

                if (ok) {

                    addTerminalLog("[AOSP AUTO FLASHER] Process completed successfully.")

                } else {

                    addTerminalLog("[AOSP AUTO FLASHER] Process aborted or encountered an error.")

                }

            } catch (e: Exception) {

                addTerminalLog("[ERROR] Auto Flasher error: ${e.message}")

            } finally {

                _uiState.update { it.copy(isAutoFlashingAosp = false) }

            }

        }

    }

    // Option 4 Helper Actions (Advanced Reboot & Mode Switcher)

    fun executeRebootCommand(modeTarget: String, command: String) {

        val cleanCmd = command.lowercase().trim()

        val isAdbMode = modeTarget.equals("ADB", ignoreCase = true)

        // What the user actually wants the target to boot into.

        val rebootTarget = when {

            cleanCmd.contains("edl") || cleanCmd.contains("brom") || cleanCmd.contains("mtk") -> "edl"

            // "reboot-fastboot" means FastbootD (userspace fastboot); `adb reboot fastboot`

            // is what enters it, so the ADB/local fallbacks must target "fastboot" — not the

            // bootloader. Plain "reboot bootloader" is handled on the next line.

            cleanCmd.contains("fastboot") -> "fastboot"

            cleanCmd.contains("bootloader") -> "bootloader"

            cleanCmd.contains("recovery") -> "recovery"

            else -> "system"

        }

        showToast("Rebooting device to $rebootTarget...")
        _uiState.update {

            it.copy(

                terminalLogs = listOf(

                    "Initiating Advanced Reboot Action...",

                    "Target Mode: $modeTarget",

                    "Command: $command",

                    "---------------------------------------------"

                )

            )

        }

        refreshUsbDevices()

        val device = currentConnectedDevice

        val manager = usbManager

        viewModelScope.launch(Dispatchers.IO) {

            var rebootSuccess = false

            // A flash that is still running holds the phone's USB port. It is

            // stopped here, so the reboot gets the port to itself and the

            // cancelled flash script cannot carry on in the background.

            val stoppedFlash = stopFlash(silent = true)

            if (stoppedFlash != null) {

                addTerminalLog("[FLASH] A flash was still running - it was stopped so the reboot could run.")

                withTimeoutOrNull(3000) { stoppedFlash.join() }

                delay(400)

            }

            // Choose the transport order from the button the user pressed:

            //   * "FASTBOOT -> ..." => the target is already in the bootloader -> fastboot first.

            //   * "ADB -> ..."      => the target is booted into Android      -> ADB first.

            // This is what lets "Fastboot -> System" boot directly instead of first asking the

            // still-running Android for USB-debugging permission.

            if (isAdbMode) {

                rebootSuccess = tryAdbReboot(manager, rebootTarget)

            }

            if (!rebootSuccess && manager != null && device != null) {

                rebootSuccess = tryFastbootReboot(manager, device, cleanCmd)

            }

            // If the target is actually booted into Android, fall back to ADB.

            if (!rebootSuccess && !isAdbMode) {

                rebootSuccess = tryAdbReboot(manager, rebootTarget)

            }

            // Last resort: try a local shell reboot (only works if this phone is the target

            // and is rooted).

            if (!rebootSuccess) {

                try {

                    addTerminalLog("> reboot $rebootTarget")

                    val p1 = Runtime.getRuntime().exec(arrayOf("sh", "-c", "reboot $rebootTarget"))

                    val code1 = p1.waitFor()

                    val p2 = Runtime.getRuntime().exec(arrayOf("sh", "-c", "svc power reboot $rebootTarget"))

                    val code2 = p2.waitFor()

                    // Only claim success when a shell actually accepted the command (root).

                    if (code1 == 0 || code2 == 0) {

                        addTerminalLog("[SUCCESS] Local reboot command issued!")

                        rebootSuccess = true

                    } else {

                        addTerminalLog("[WARN] Local reboot was rejected (root required on this phone).")

                    }

                } catch (e: Exception) {

                    addTerminalLog("[WARN] Local reboot unavailable: ${e.message}")

                }

            }

            if (rebootSuccess) {

                addTerminalLog("=============================================")

                addTerminalLog("[SUCCESS] Target device is rebooting to $rebootTarget!")
                showToast("Target device rebooting to $rebootTarget!")

                addTerminalLog("=============================================")

            } else {

                addTerminalLog("[ERROR] Could not issue reboot command. Connect phone via OTG cable in ADB or Fastboot mode.")
                showToast("Reboot failed. Connect phone via OTG in ADB/Fastboot mode.")

            }

            try { Thread.sleep(2000) } catch (_: Exception) {}

            refreshUsbDevices()

        }

    }

    /**

     * Sends an ADB reboot.  Uses the native `reboot:` service first, then the `shell:reboot`

     * service.  Because [AdbSideloadProtocol] now persists a single host RSA key, after the

     * user taps "Always allow from this computer" once the target stops showing the popup.

     */

    private fun tryAdbReboot(manager: UsbManager?, rebootTarget: String): Boolean {

        if (manager == null) return false

        for (dev in manager.deviceList.values.toList()) {

            if (!manager.hasPermission(dev)) {

                addTerminalLog("[ACTION REQUIRED] USB Permission requested on screen! Tap OK on your phone screen to allow USB access.")

            }

            val adbProtocol = AdbSideloadProtocol.open(manager, dev) ?: continue

            try {

                // Native AOSP reboot service: an empty argument means "boot to system".

                val serviceArg = if (rebootTarget == "system") "" else rebootTarget

                var ok = adbProtocol.executeAdbRebootService(serviceArg) { log -> addTerminalLog(log) }

                if (!ok) {

                    val shellCmd = if (rebootTarget == "system") "reboot" else "reboot $rebootTarget"

                    ok = adbProtocol.executeAdbShellCommand(shellCmd) != null

                }

                if (ok) {

                    addTerminalLog("[SUCCESS] ADB reboot service triggered!")

                    return true

                }

            } catch (e: Exception) {

                addTerminalLog("[ADB NOTE] ${e.message}")

            } finally {

                adbProtocol.close()

            }

        }

        return false

    }

    /** Sends a raw fastboot command (e.g. `reboot`, `reboot-recovery`) to a bootloader device. */

    private fun tryFastbootReboot(manager: UsbManager, device: UsbDevice, command: String): Boolean {

        val fastbootSession = FastbootSession.open(manager, device) ?: return false

        return try {

            addTerminalLog("> fastboot $command")

            val res = fastbootSession.executeGenericCommand(command)

            addTerminalLog("Fastboot Status: ${res ?: "OKAY"}")

            if (res != null && res.trim().uppercase().startsWith("FAIL")) {

                addTerminalLog("[FASTBOOT NOTE] Command rejected by bootloader: ${res.trim()}")

                false

            } else {

                addTerminalLog("[SUCCESS] Fastboot reboot command executed!")

                true

            }

        } catch (e: Exception) {

            addTerminalLog("[FASTBOOT NOTE] ${e.message}")

            false

        } finally {

            fastbootSession.close()

        }

    }

    // Option 5 Helper Actions (EFS / IMEI Backup & Restore)

    fun startEfsBackup(context: Context, backupType: String) {

        val state = _uiState.value

        val modelName = if (state.deviceModel.isNotBlank()) state.deviceModel else "AndroidDevice"

        _uiState.update {

            it.copy(

                showTerminal = true,

                terminalLogs = listOf(

                    "Initiating Hardware EFS & IMEI Backup ($backupType Mode)...",

                    "Connected Device: $modelName [${state.deviceCodeName}]",

                    "---------------------------------------------"

                ),

                isBackingUpEfs = true

            )

        }

        refreshUsbDevices()

        val device = currentConnectedDevice

        val manager = usbManager

        if (manager != null && device != null && !manager.hasPermission(device)) {

            addTerminalLog("[ACTION REQUIRED] USB permission is required to talk to the phone.")

            addTerminalLog("→ A system popup should appear; tap OK, then press BACKUP again.")

            try {

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

                } else {

                    PendingIntent.FLAG_UPDATE_CURRENT

                }

                val pi = PendingIntent.getBroadcast(

                    context, 0,

                    Intent("com.romflasher.tool.USB_PERMISSION"), flags

                )

                manager.requestPermission(device, pi)

            } catch (_: Exception) {

            }

            _uiState.update { it.copy(isBackingUpEfs = false) }

            return

        }

        efsJob = viewModelScope.launch(Dispatchers.IO) {

            if (manager == null || device == null) {

                addTerminalLog("[ERROR] Target phone not connected over OTG cable.")

                _uiState.update { it.copy(isBackingUpEfs = false) }

                return@launch

            }

            try {

                val success = FastbootRomFlasher.imeiBackup(

                    context = context,

                    usbManager = manager,

                    usbDevice = device,

                    deviceModel = modelName,

                    onLog = { log -> addTerminalLog(log) }

                ) { }

                _uiState.update { it.copy(isBackingUpEfs = false) }

                if (success) {

                    AudioHapticEngine.speakCompletion(

                        englishText = "IMEI and EFS backup completed successfully!",

                        hinglishText = "Bhai, IMEI aur EFS backup complete ho gaya hai!"

                    )

                    addTerminalLog("[INFO] Backup images are stored on the phone at /sdcard/imeibackup")

                } else {

                    addTerminalLog("[ERROR] IMEI backup did not complete. Check the log above.")

                }

            } catch (e: Exception) {

                e.printStackTrace()

                addTerminalLog("[ERROR] IMEI Backup failed: ${e.message}")

                _uiState.update {

                    it.copy(

                        isBackingUpEfs = false,

                        errorMessage = "IMEI Backup failed: ${e.message}"

                    )

                }

            }

        }

    }

    /**

     * Copies the `/sdcard/imeibackup` folder from the connected target phone to this host phone's

     * `Download/RomFlasher/IMEI Backup/<ROM family> Backup/`, so the IMEI backup is stored off the

     * target device.

     */

    fun startEfsBackupFileCopy() {

        val state = _uiState.value

        val modelName = if (state.deviceModel.isNotBlank()) state.deviceModel else "AndroidDevice"

        _uiState.update {

            it.copy(

                showTerminal = true,

                terminalLogs = listOf(

                    "Pulling IMEI backup files from the phone...",

                    "Source: /sdcard/imeibackup",

                    "Connected Device: $modelName [${state.deviceCodeName}]",

                    "---------------------------------------------"

                ),

                isCopyingEfsBackup = true

            )

        }

        refreshUsbDevices()

        val device = currentConnectedDevice

        val manager = usbManager

        if (manager != null && device != null && !manager.hasPermission(device)) {

            addTerminalLog("[ACTION REQUIRED] USB permission is required to talk to the phone.")

            addTerminalLog("→ A system popup should appear; tap OK, then press BACKUP FILES again.")

            _uiState.update { it.copy(isCopyingEfsBackup = false) }

            return

        }

        viewModelScope.launch(Dispatchers.IO) {

            if (manager == null || device == null) {

                addTerminalLog("[ERROR] Target phone not connected over OTG cable.")

                _uiState.update { it.copy(isCopyingEfsBackup = false) }

                return@launch

            }

            try {

                val folder = FastbootRomFlasher.copyImeiBackupToHost(

                    usbManager = manager,

                    usbDevice = device,

                    deviceModel = modelName,

                    onLog = { log -> addTerminalLog(log) }

                ) { }

                _uiState.update { it.copy(isCopyingEfsBackup = false) }

                if (folder != null) {

                    addTerminalLog("[INFO] Backup files saved on this phone: ${folder.absolutePath}")

                } else {

                    addTerminalLog("[ERROR] No backup files were copied. Check the log above.")

                }

            } catch (e: Exception) {

                e.printStackTrace()

                addTerminalLog("[ERROR] Pulling IMEI backup files failed: ${e.message}")

                _uiState.update {

                    it.copy(

                        isCopyingEfsBackup = false,

                        errorMessage = "Pulling IMEI backup files failed: ${e.message}"

                    )

                }

            }

        }

    }

    /**

     * Restores IMEI / EFS.  [hostFolder] is the backup the user picked in the restore picker; when it

     * is null (e.g. the picker was skipped) the newest backup on this host phone is used.

     */

    fun startEfsRestore(hostFolder: File? = null) {

        val state = _uiState.value

        val modelName = if (state.deviceModel.isNotBlank()) state.deviceModel else "AndroidDevice"

        _uiState.update {

            it.copy(

                showTerminal = true,

                terminalLogs = listOf(

                    "Initiating IMEI / EFS Restore (on-device dd, like restore-imei.sh)...",

                    if (hostFolder != null) "Backup: ${hostFolder.name}" else "Source: newest host backup",

                    "Target Device: $modelName",

                    "---------------------------------------------"

                ),

                isRestoringEfs = true

            )

        }

        refreshUsbDevices()

        val device = currentConnectedDevice

        val manager = usbManager

        efsRestoreJob = viewModelScope.launch(Dispatchers.IO) {

            if (manager == null || device == null) {

                addTerminalLog("[ERROR] Target phone not connected over OTG cable.")

                _uiState.update { it.copy(isRestoringEfs = false) }

                return@launch

            }

            try {

                FastbootRomFlasher.imeiRestore(

                    usbManager = manager,

                    usbDevice = device,

                    onLog = { log -> addTerminalLog(log) },

                    onRomMismatch = { backup, current -> confirmRomMismatch(backup, current) },

                    preferredHostFolder = hostFolder

                ) { }

                _uiState.update { it.copy(isRestoringEfs = false) }

            } catch (e: Exception) {

                e.printStackTrace()

                addTerminalLog("[ERROR] IMEI Restore failed: ${e.message}")

                _uiState.update {

                    it.copy(

                        isRestoringEfs = false,

                        errorMessage = "IMEI Restore failed: ${e.message}"

                    )

                }

            }

        }

    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String {

        var name = "custom_image.img"

        try {

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->

                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                if (nameIndex != -1 && cursor.moveToFirst()) {

                    name = cursor.getString(nameIndex)

                }

            }

        } catch (_: Exception) {}

        return name

    }

    private fun addTerminalLog(message: String) {

        _uiState.update {

            val updatedList = (it.terminalLogs + message).takeLast(300)

            it.copy(terminalLogs = updatedList)

        }

        if (progressNotificationActive) {

            val now = System.currentTimeMillis()

            if (now - lastNotificationAt >= 1000L) {

                lastNotificationAt = now

                appContext?.let { ctx ->

                    NotificationHelper.update(ctx, "ROM Flasher - in progress", message.take(180))

                }

            }

        }

    }

    /** Starts mirroring progress into a system notification (survives backgrounding). */

    private fun beginProgressNotification(title: String) {
        progressNotificationActive = true
        lastNotificationAt = 0L
        appContext?.let { ctx ->
            com.romflasher.tool.service.FlashingForegroundService.start(ctx, title)
            NotificationHelper.update(ctx, title, "Starting...")
        }
    }

    /** Finishes the progress notification with a result summary. */
    private fun endProgressNotification(title: String, summary: String) {
        progressNotificationActive = false
        appContext?.let { ctx ->
            com.romflasher.tool.service.FlashingForegroundService.stop(ctx)
            runCatching { NotificationHelper.finish(ctx, title, summary) }
        }
    }

    fun sendTerminalInput(text: String) {

        _uiState.update { it.copy(isWaitingForInput = false, inputPrompt = "") }

        FastbootRomFlasher.submitTerminalInput(text)

    }

    /**

     * Closes the console window. When a flash is running the window is the

     * flash's console, so closing it stops the flash - the script does not

     * carry on behind the user's back.

     */

    fun closeTerminal() {
        if (flashRunning || flashJob != null) {
            stopFlash()
        }
        try { customImageJob?.cancel() } catch (_: Exception) {}
        customImageJob = null
        try { sideloadJob?.cancel() } catch (_: Exception) {}
        sideloadJob = null
        try { gsiJob?.cancel() } catch (_: Exception) {}
        gsiJob = null
        try { aospJob?.cancel() } catch (_: Exception) {}
        aospJob = null
        try { efsJob?.cancel() } catch (_: Exception) {}
        efsJob = null
        try { efsRestoreJob?.cancel() } catch (_: Exception) {}
        efsRestoreJob = null

        addTerminalLog(tr("[ABORTED] Process stopped by user (Console closed).", "[ABORTED] Process band kiya gaya (Console band kiya)."))

        _uiState.update {
            it.copy(
                showTerminal = false,
                isFlashingRom = false,
                isFlashingGsi = false,
                isSideloadingPackage = false,
                isAutoFlashingAosp = false,
                isFlashingCustomImage = false,
                isBackingUpEfs = false,
                isRestoringEfs = false,
                isExtracting = false,
                isWaitingForInput = false
            )
        }
    }

    fun dismissBatDialog() {

        _uiState.update { it.copy(showBatDialog = false) }

    }

    fun dismissError() {

        _uiState.update { it.copy(errorMessage = null) }

    }

    fun openPayloadDumperScreen() {

        _uiState.update { it.copy(isPayloadDumperScreenOpen = true) }

    }

    fun closePayloadDumperScreen() {
        try { payloadDumpJob?.cancel() } catch (_: Exception) {}
        payloadDumpJob = null
        try { payloadVerifyJob?.cancel() } catch (_: Exception) {}
        payloadVerifyJob = null
        _uiState.update {
            it.copy(
                isPayloadDumperScreenOpen = false,
                isDumpingPayload = false,
                isVerifyingPayload = false,
                isParsingPayloadManifest = false
            )
        }
    }

    fun togglePayloadPartitionSelection(name: String) {

        _uiState.update { state ->

            val current = state.selectedPayloadPartitions.toMutableSet()

            if (current.contains(name)) {

                current.remove(name)

            } else {

                current.add(name)

            }

            state.copy(selectedPayloadPartitions = current)

        }

    }

    fun selectAllPayloadPartitions() {

        _uiState.update { state ->

            val allNames = state.detectedPayloadPartitions.map { it.name }.toSet()

            state.copy(selectedPayloadPartitions = allNames)

        }

    }

    fun deselectAllPayloadPartitions() {

        _uiState.update { state ->

            state.copy(selectedPayloadPartitions = emptySet())

        }

    }

    fun setPayloadVerifyAfterExtract(value: Boolean) {

        _uiState.update { it.copy(payloadVerifyAfterExtract = value) }

    }

    /** Runs SHA-256 verification on already-extracted partition images. */

    fun startPayloadVerify() {

        val state = _uiState.value

        val file = state.selectedPayloadZipFile ?: return
        val sourceUri = state.selectedPayloadSourceUri

        _uiState.update {

            it.copy(isVerifyingPayload = true, payloadVerifySummary = "")

        }

        payloadVerifyJob = viewModelScope.launch(Dispatchers.IO) {

            var tempSafPayload: File? = null
            try {

                val outputBaseDir = RomFlasherStorage.extractedRoot()

                val outputFolder = File(outputBaseDir, "Payload_Extracted_${file.nameWithoutExtension}")

                addTerminalLog("=============================================")

                addTerminalLog("SHA-256 verification started for: ${file.name}")

                val verifyInput = if (file.exists() && file.length() >= 24) {
                    file
                } else {
                    val ctx = appContext ?: throw java.io.IOException("App context unavailable for SAF read.")
                    val uri = sourceUri ?: throw java.io.IOException("Selected payload file is no longer accessible.")
                    val safeName = file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val tempFile = File(outputBaseDir, "temp_payload_saf_${safeName}.bin")
                    PayloadDumper.extractPayloadBinFromUri(
                        context = ctx,
                        uri = uri,
                        isZip = file.extension.equals("zip", ignoreCase = true),
                        destFile = tempFile,
                        onLog = { log -> addTerminalLog(log) }
                    ).also { tempSafPayload = it }
                }

                val result = PayloadDumper.verifyExtractedImages(

                    inputFile = verifyInput,

                    outputBaseDir = outputBaseDir,

                    outputFolder = outputFolder,

                    onLog = { log -> addTerminalLog(log) }

                )

                _uiState.update { it.copy(payloadVerifySummary = result.summary(), isVerifyingPayload = false) }

            } catch (e: Exception) {

                addTerminalLog("[ERROR] Verification failed: ${e.message}")

                _uiState.update { it.copy(isVerifyingPayload = false, payloadVerifySummary = "Verification failed: ${e.message}") }

            } finally {
                tempSafPayload?.let { try { it.delete() } catch (_: Exception) {} }
            }

        }

    }

    private fun getFileFromUri(context: Context, uri: Uri): File? {

        if (uri.scheme == "file") {

            uri.path?.let { p ->

                val f = File(p)

                if (f.exists()) return f

            }

        }

        try {

            if (DocumentsContract.isDocumentUri(context, uri)) {

                val docId = DocumentsContract.getDocumentId(uri)

                if (docId.startsWith("raw:")) {

                    val rawFile = File(docId.substring(4))

                    if (rawFile.exists()) return rawFile

                }

                val split = docId.split(":")

                if (split.size >= 2) {

                    val type = split[0]

                    val path = split[1]

                    if ("primary".equals(type, ignoreCase = true)) {

                        val primaryFile = File(Environment.getExternalStorageDirectory(), path)

                        if (primaryFile.exists()) return primaryFile

                    }

                }

            }

        } catch (_: Exception) {}

        try {

            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->

                val columnIndex = cursor.getColumnIndex("_data")

                if (columnIndex != -1 && cursor.moveToFirst()) {

                    val path = cursor.getString(columnIndex)

                    if (!path.isNullOrEmpty()) {

                        val f = File(path)

                        if (f.exists()) return f

                    }

                }

            }

        } catch (_: Exception) {}

        return null

    }

    // Option 7 Helper Actions (Payload Dumper)

    fun processSelectedPayloadZipUri(context: Context, uri: Uri) {

        viewModelScope.launch(Dispatchers.IO) {

            _uiState.update {

                it.copy(

                    isParsingPayloadManifest = true,

                    isPayloadDumperScreenOpen = true,

                    detectedPayloadPartitions = emptyList(),

                    selectedPayloadPartitions = emptySet()

                )

            }

            try {

                val fileName = getFileNameFromUri(context, uri)
                val storageFolder = RomFlasherStorage.extractedRoot()
                storageFolder.mkdirs()

                // Purge any stale cached payload files from previous runs
                storageFolder.listFiles()?.forEach { oldFile ->
                    if (oldFile.name.startsWith("temp_payload_")) {
                        try { oldFile.delete() } catch (_: Exception) {}
                    }
                }

                // 1. Check if the file is accessible as a local File (direct or inside Download folder)
                val directFile = getFileFromUri(context, uri) ?: run {
                    val inDownload = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                    if (inDownload.exists() && inDownload.length() > 1000) inDownload else null
                } ?: run {
                    val inRomFlasher = File(RomFlasherStorage.romsRoot(), fileName)
                    if (inRomFlasher.exists() && inRomFlasher.length() > 1000) inRomFlasher else null
                }

                val uriSizeBytes = try {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
                    } ?: 0L
                } catch (_: Exception) { 0L }

                val detectedList: List<PayloadPartitionInfo>
                val targetFile: File
                val sourceUriToKeep: Uri?
                val finalSizeBytes: Long

                if (directFile != null && directFile.exists() && directFile.length() > 1000) {
                    targetFile = directFile
                    sourceUriToKeep = null
                    finalSizeBytes = directFile.length()
                    detectedList = PayloadDumper.inspectPayloadPartitions(targetFile, storageFolder)
                } else {
                    // FAST ZERO-COPY DIRECT STREAM INSPECTION via ContentResolver ParcelFileDescriptor:
                    // Never copy 4GB - 8GB archive into internal flash storage just to inspect the partition list!
                    var directManifest: ByteArray? = null
                    var fdStatSize = 0L
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        fdStatSize = maxOf(0L, pfd.statSize)
                        FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                            directManifest = if (fileName.endsWith(".zip", ignoreCase = true)) {
                                PayloadDumper.tryReadManifestFromChannel(channel)
                            } else {
                                FileInputStream(pfd.fileDescriptor).use { stream ->
                                    PayloadDumper.readManifestFromPayloadStream(stream)
                                }
                            }
                        }
                    }

                    if (directManifest != null) {
                        val partitions = PayloadDumper.parseProtobufManifest(directManifest!!)
                        detectedList = partitions.map { part ->
                            val totalBlocks = part.operations.flatMap { it.dstExtents }.sumOf { it.numBlocks }
                            val extentSize = totalBlocks * 4096L
                            val realSize = if (part.size > 0) part.size else if (extentSize > 0) extentSize else PayloadDumper.calculateExpectedPartitionSize(part.name)
                            PayloadPartitionInfo(
                                name = "${part.name}.img",
                                sizeBytes = realSize,
                                formattedSize = PayloadDumper.formatBytes(realSize)
                            )
                        }
                        targetFile = File(context.cacheDir, fileName)
                        sourceUriToKeep = uri
                        finalSizeBytes = if (uriSizeBytes > 0L) uriSizeBytes else fdStatSize
                    } else {
                        // Fallback: If streaming from channel failed, copy archive
                        val destinationFile = File(storageFolder, "selected_$fileName")
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            FileInputStream(pfd.fileDescriptor).channel.use { inChannel ->
                                FileOutputStream(destinationFile).channel.use { outChannel ->
                                    inChannel.transferTo(0, inChannel.size(), outChannel)
                                }
                            }
                        }
                        targetFile = destinationFile
                        sourceUriToKeep = null
                        finalSizeBytes = destinationFile.length()
                        detectedList = PayloadDumper.inspectPayloadPartitions(targetFile, storageFolder)
                    }
                }

                val allNames = detectedList.map { it.name }.toSet()

                _uiState.update {

                    it.copy(

                        selectedPayloadZipFile = targetFile,

                        selectedPayloadSourceUri = sourceUriToKeep,

                        selectedPayloadSizeBytes = finalSizeBytes,

                        detectedPayloadPartitions = detectedList,

                        selectedPayloadPartitions = allNames,

                        isParsingPayloadManifest = false

                    )

                }

            } catch (e: Exception) {

                _uiState.update {

                    it.copy(

                        isParsingPayloadManifest = false,

                        errorMessage = "Failed to inspect payload.bin: ${e.message}"

                    )

                }

            }

        }

    }

    fun clearSelectedPayloadZipFile() {

        _uiState.update {

            it.copy(

                selectedPayloadZipFile = null,

                selectedPayloadSourceUri = null,

                selectedPayloadSizeBytes = 0L,

                detectedPayloadPartitions = emptyList(),

                selectedPayloadPartitions = emptySet()

            )

        }

    }

    fun startPayloadDump(context: Context) {

        val state = _uiState.value

        val file = state.selectedPayloadZipFile ?: return
        val sourceUri = state.selectedPayloadSourceUri
        val displaySizeMb = (state.selectedPayloadSizeBytes.takeIf { it > 0L } ?: file.length()) / (1024 * 1024)

        val targetPartitions = state.selectedPayloadPartitions

        _uiState.update {

            it.copy(

                isPayloadDumperScreenOpen = true,

                terminalLogs = listOf(

                    "Initiating Selective Payload Extraction...",

                    "Source File: ${file.name} ($displaySizeMb MB)",

                    "Target Partitions (${targetPartitions.size}): ${targetPartitions.joinToString(", ")}",

                    "Output Directory: Download/RomFlasher/Extracted/",

                    "---------------------------------------------"

                ),

                isDumpingPayload = true,

                payloadDumpPartitionName = "Initializing...",

                payloadDumpOverallProgress = 0f,

                payloadDumpPartitionsDone = 0,

                payloadDumpTotalPartitions = targetPartitions.size,

                payloadVerifySummary = ""

            )

        }

        beginProgressNotification("Extracting partitions: ${file.name}")

        payloadDumpJob = viewModelScope.launch(Dispatchers.IO) {

            var tempSafPayload: File? = null
            try {

                val outputBaseDir = RomFlasherStorage.extractedRoot()

                outputBaseDir.mkdirs()

                val actualInputFile = if (file.exists() && file.length() >= 24) {
                    file
                } else {
                    val uri = sourceUri ?: throw java.io.IOException("Selected payload file could not be read.")
                    val safeName = file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val tempFile = File(outputBaseDir, "temp_payload_saf_${safeName}.bin")
                    PayloadDumper.extractPayloadBinFromUri(
                        context = context,
                        uri = uri,
                        isZip = file.extension.equals("zip", ignoreCase = true),
                        destFile = tempFile,
                        onLog = { log -> addTerminalLog(log) }
                    ).also { tempSafPayload = it }
                }

                val targetOutputFolder = File(outputBaseDir, "Payload_Extracted_${file.nameWithoutExtension}")
                val rawExtractedDir = PayloadDumper.extractPayload(

                    context = context,

                    inputFile = actualInputFile,

                    outputBaseDir = outputBaseDir,

                    targetPartitions = targetPartitions,

                    onLog = { log -> addTerminalLog(log) },

                    onProgress = { progress ->

                        _uiState.update {

                            it.copy(

                                payloadDumpPartitionName = progress.partitionName,

                                payloadDumpOverallProgress = progress.overallProgress,

                                payloadDumpPartitionsDone = progress.partitionsDone,

                                payloadDumpTotalPartitions = progress.totalPartitions

                            )

                        }

                    }

                )

                // Keep consistent output folder name based on original selected ROM name even when SAF streamed to temp file
                val extractedDir = if (tempSafPayload != null && rawExtractedDir != targetOutputFolder) {
                    if (targetOutputFolder.exists()) targetOutputFolder.deleteRecursively()
                    if (rawExtractedDir.renameTo(targetOutputFolder)) targetOutputFolder else rawExtractedDir
                } else {
                    rawExtractedDir
                }

                addTerminalLog("=============================================")

                addTerminalLog("[SUCCESS] Selective Payload Extraction Completed!")

                addTerminalLog("Output folder: ${extractedDir.absolutePath}")

                addTerminalLog("=============================================")

                if (_uiState.value.payloadVerifyAfterExtract) {

                    _uiState.update { it.copy(isVerifyingPayload = true) }

                    val result = PayloadDumper.verifyExtractedImages(

                        inputFile = actualInputFile,

                        outputBaseDir = outputBaseDir,

                        outputFolder = extractedDir,

                        onLog = { log -> addTerminalLog(log) }

                    )

                    _uiState.update { it.copy(payloadVerifySummary = result.summary(), isVerifyingPayload = false) }

                }

            } catch (e: Exception) {

                e.printStackTrace()

                addTerminalLog("[ERROR] Extraction failed: ${e.message}")

                _uiState.update {

                    it.copy(

                        errorMessage = "Payload extraction failed: ${e.message}"

                    )

                }

            } finally {

                tempSafPayload?.let { try { it.delete() } catch (_: Exception) {} }

                _uiState.update { it.copy(isDumpingPayload = false, isVerifyingPayload = false) }

                endProgressNotification("Extraction finished", file.name)

            }

        }

    }

    // ============================================================ Root Wizard

    // Guided KernelSU / Magisk rooting: download the root APK on the host,

    // install it on the target over ADB, obtain init_boot.img, hand it to the

    // phone for manual patching, pull the patched image back, then flash it to

    // both slots in fastboot. Steps that need a human are gated on a YES/NO

    // prompt inside the console dialog.

    private var rootJob: Job? = null
    private var zeroTouchJob: Job? = null

    private var rootPromptDeferred: CompletableDeferred<Boolean>? = null

    private var rootFileDeferred: CompletableDeferred<File?>? = null

    private var rootChoiceDeferred: CompletableDeferred<Int?>? = null

    /** Package name read from the user's own APK (only used for [RootKind.SELF]). */

    private var rootCustomPackage: String? = null

    fun openRootWizard() {
        openZeroTouchDialog()
    }

    fun closeRootWizard() {
        closeZeroTouchDialog()
    }

    fun openZeroTouchDialog(isUninstall: Boolean = false) {
        _uiState.update {
            it.copy(
                showZeroTouchRootDialog = true,
                zeroTouchIsUninstall = isUninstall,
                zeroTouchLogs = if (isUninstall) listOf(
                    "==================================================",
                    "🗑️ UNIVERSAL ROOT UNINSTALLER",
                    "Target: Removing Magisk / KernelSU / APatch / SukiSU",
                    "=================================================="
                ) else listOf(
                    "==================================================",
                    "⚡ ROM FLASHER ZERO-TOUCH DIRECT ROOT ENGINE",
                    "Ready to probe device and execute zero-touch root.",
                    "=================================================="
                ),
                zeroTouchBusy = false,
                zeroTouchFinished = false,
                zeroTouchStep = "Ready",
                zeroTouchProgress = 0f
            )
        }
    }

    private var zeroTouchFileDeferred: CompletableDeferred<File?>? = null

    fun provideZeroTouchFileUri(context: Context, uri: Uri?, fallbackName: String) {
        if (uri == null) {
            zeroTouchFileDeferred?.complete(null)
            zeroTouchFileDeferred = null
            _uiState.update { it.copy(zeroTouchAwaitingFile = false, zeroTouchFilePrompt = null) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val file = try {
                val rawName = queryDisplayName(context, uri).ifBlank { fallbackName }
                val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val out = File(context.cacheDir, safeName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                if (out.exists() && out.length() > 0L) out else null
            } catch (_: Exception) {
                null
            }
            _uiState.update { it.copy(zeroTouchAwaitingFile = false, zeroTouchFilePrompt = null) }
            zeroTouchFileDeferred?.complete(file)
            zeroTouchFileDeferred = null
        }
    }

    fun closeZeroTouchDialog() {
        try { zeroTouchJob?.cancel() } catch (_: Exception) {}
        zeroTouchJob = null
        rootChoiceDeferred?.complete(null)
        rootChoiceDeferred = null
        rootFileDeferred?.complete(null)
        rootFileDeferred = null
        zeroTouchFileDeferred?.complete(null)
        zeroTouchFileDeferred = null
        _uiState.update {
            it.copy(
                showZeroTouchRootDialog = false,
                zeroTouchIsUninstall = false,
                zeroTouchBusy = false,
                zeroTouchAwaitingFile = false,
                zeroTouchFilePrompt = null,
                rootPrompt = null,
                rootChoices = emptyList()
            )
        }
    }

    private fun rootScanStockBootCandidates(codename: String, requiresInitBoot: Boolean): List<File> {
        val searchDirs = listOf(
            RomFlasherStorage.child("boot/init_boot"),
            RomFlasherStorage.downloadsDir(),
            RomFlasherStorage.rootFilesRoot(),
            RomFlasherStorage.extractedRoot(),
            File(RomFlasherStorage.downloadsDir(), "RomFlasher")
        ).filter { it.exists() && it.isDirectory }

        val candidates = mutableListOf<File>()

        for (dir in searchDirs) {
            val files = try {
                dir.walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".img", ignoreCase = true) }.toList()
            } catch (_: Exception) {
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".img", ignoreCase = true) } ?: emptyList()
            }
            for (f in files) {
                val name = f.name.lowercase(java.util.Locale.ROOT)
                val path = f.absolutePath.lowercase(java.util.Locale.ROOT)
                if (name.contains("patched") || name.contains("magisk") || name.contains("ksu") ||
                    name.contains("orangefox") || name.contains("twrp") || name.contains("recovery") ||
                    name.contains("vendor") || name.contains("dtbo")) {
                    continue
                }
                val len = f.length()
                if (len < 4L * 1024 * 1024 || len > 256L * 1024 * 1024) continue

                val isInit = name.contains("init_boot") || path.contains("init_boot")
                if (requiresInitBoot) {
                    if (isInit) candidates.add(f)
                } else {
                    // Strictly non-init_boot for devices that only use boot.img
                    if (!isInit && name.contains("boot")) candidates.add(f)
                }
            }
        }

        // Auto-check target phone /sdcard/Download/ via ADB if host storage has no candidates
        if (candidates.isEmpty()) {
            try {
                val targetLs = rootAdbShell("ls -la /sdcard/Download/ 2>/dev/null")
                if (!targetLs.isNullOrBlank()) {
                    val matchingLine = targetLs.lines().firstOrNull { line ->
                        val lower = line.lowercase()
                        lower.endsWith(".img") && !lower.contains("patched") &&
                        (if (requiresInitBoot) lower.contains("init_boot") else (!lower.contains("init_boot") && lower.contains("boot")))
                    }
                    if (matchingLine != null) {
                        val fileName = matchingLine.split("\\s+".toRegex()).lastOrNull()?.trim()
                        if (!fileName.isNullOrBlank()) {
                            val hostDest = File(RomFlasherStorage.downloadsDir(), fileName)
                            val pulled = rootAdbPull("/sdcard/Download/$fileName", hostDest)
                            if (pulled && hostDest.exists() && hostDest.length() > 0) {
                                candidates.add(hostDest)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return candidates.distinctBy { it.absolutePath }
    }

    private fun fileMatchesKernel(file: File, deviceKernelVer: String): Boolean {
        if (deviceKernelVer.isBlank() || deviceKernelVer == "unknown") return true
        val devMajorMinor = Regex("""^(\d+\.\d+)""").find(deviceKernelVer)?.groupValues?.get(1) ?: return true
        return try {
            val maxBytes = minOf(file.length(), 8L * 1024 * 1024).toInt()
            val buf = ByteArray(maxBytes)
            var readLen = 0
            java.io.FileInputStream(file).use { fis ->
                readLen = fis.read(buf, 0, maxBytes)
            }
            if (readLen <= 0) return true
            val ascii = String(buf, 0, readLen, Charsets.ISO_8859_1)

            // Foreign kernel rejection: if candidate contains 6.6, 5.15, 5.10, 4.19, 4.14 etc. that doesn't match device
            val foreignKernels = listOf("6.6.", "5.15.", "5.10.", "5.4.", "4.19.", "4.14.", "4.9.")
                .filter { it != "$devMajorMinor." }
            for (fk in foreignKernels) {
                if (ascii.contains(fk)) {
                    return false
                }
            }
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun rootPickBestStockBoot(
        candidates: List<File>,
        codename: String,
        requiresInitBoot: Boolean = false,
        kernelVer: String = "",
        buildVer: String = "",
        isCustomRom: Boolean = false
    ): File? {
        if (candidates.isEmpty()) return null
        val targetName = if (requiresInitBoot) "init_boot" else "boot"

        // Step 1: Enforce partition type strictly (init_boot vs boot)
        var valid = candidates.filter { f ->
            val name = f.name.lowercase(java.util.Locale.ROOT)
            val path = f.absolutePath.lowercase(java.util.Locale.ROOT)
            val isInit = name.contains("init_boot") || path.contains("init_boot")
            if (requiresInitBoot) isInit else (!isInit && name.contains("boot"))
        }
        if (valid.isEmpty()) return null

        // Step 1.5: If phone is running a Custom ROM (AOSP/Lineage/Infinity), strictly REJECT all official HyperOS/MIUI images
        // Flashing HyperOS ramdisk/kernel onto an AOSP ROM causes instant bootloops.
        if (isCustomRom) {
            valid = valid.filter { file ->
                val p = file.absolutePath.lowercase(java.util.Locale.ROOT)
                val n = file.name.lowercase(java.util.Locale.ROOT)
                val isHyperOsFile = p.contains("boot/init_boot") ||
                                    p.contains("hyperos") || p.contains("miui") ||
                                    n.contains("hyperos") || n.contains("miui") ||
                                    Regex("""(os\d+\.|v\d+\.)""").containsMatchIn(p) ||
                                    Regex("""(os\d+\.|v\d+\.)""").containsMatchIn(n)
                !isHyperOsFile
            }
            if (valid.isEmpty()) return null
        }

        // Step 2: Binary Kernel Version Inspection for boot.img (reject foreign kernels)
        if (!requiresInitBoot && kernelVer.isNotBlank() && kernelVer != "unknown") {
            val kernelFiltered = valid.filter { fileMatchesKernel(it, kernelVer) }
            if (kernelFiltered.isNotEmpty()) {
                valid = kernelFiltered
            } else {
                // All candidates contained conflicting foreign kernels (e.g. 6.6 on a 4.14 phone)
                return null
            }
        }

        if (valid.size == 1) return valid.first()

        // Step 3: Multi-attribute scoring
        return valid.maxByOrNull { file ->
            var score = 0L
            val name = file.name.lowercase(java.util.Locale.ROOT)
            val path = file.absolutePath.lowercase(java.util.Locale.ROOT)

            // Exact match for ROM build version (e.g. OS1.0.17 or V14.0.5) - only on stock ROMs
            if (!isCustomRom && buildVer.isNotBlank() && (path.contains(buildVer.lowercase(java.util.Locale.ROOT)) || name.contains(buildVer.lowercase(java.util.Locale.ROOT)))) {
                score += 3000
            }
            // Codename match
            if (codename.isNotBlank() && codename != "unknown") {
                if (name.contains(codename) || path.contains(codename)) score += 1500
            }
            // Directory matching official RomFlasher structure (only on stock ROMs)
            if (!isCustomRom && path.contains("romflasher") && path.contains("boot/init_boot")) {
                score += 1000
            }
            if (name == "${targetName}_stock.img") score += 500
            if (name == "$targetName.img") score += 200

            score * 1000000000L + (file.lastModified() / 1000)
        }
    }

    fun startZeroTouchEngine(targetRoot: String) {
        if (_uiState.value.zeroTouchBusy) return
        zeroTouchJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    zeroTouchIsUninstall = false,
                    zeroTouchBusy = true,
                    zeroTouchFinished = false,
                    zeroTouchStep = "Stage 1/4 · Probing Device & Kernel LPE",
                    zeroTouchProgress = 0.15f,
                    zeroTouchLogs = listOf(
                        "==================================================",
                        "⚡ ZERO-TOUCH DIRECT BOOT PATCHER (10-SEC ROOT)",
                        "Selected Root Solution: $targetRoot",
                        "=================================================="
                    )
                )
            }

            fun ztLog(msg: String) {
                _uiState.update { it.copy(zeroTouchLogs = it.zeroTouchLogs + msg) }
            }

            try {
                // STAGE 1: Check Target Device
                ztLog("⚡ STAGE 1/4: Probing Target Device & Kernel Vector…")
                val rawModel = rootAdbShell("getprop ro.product.model")?.trim()
                val rawCodename = rootAdbShell("getprop ro.product.device")?.trim()
                val rawKernel = rootAdbShell("uname -r")?.trim()

                val isAdbOnline = !rawModel.isNullOrBlank() || !rawCodename.isNullOrBlank() || !rawKernel.isNullOrBlank()
                if (!isAdbOnline) {
                    ztLog("[ERROR] No target Android device detected via USB/OTG ADB!")
                    ztLog("[!] Connect target phone with USB-OTG cable and enable USB Debugging.")
                    ztLog("[STOP] Execution halted safely. Connect phone and try again.")
                    _uiState.update { it.copy(zeroTouchBusy = false, zeroTouchFinished = true) }
                    return@launch
                }

                val rawBrand = rootAdbShell("getprop ro.product.brand")?.trim() ?: "unknown"
                val rawManufacturer = rootAdbShell("getprop ro.product.manufacturer")?.trim() ?: "unknown"
                val rawBuildVer = rootAdbShell("getprop ro.build.version.incremental")?.trim() ?: ""
                val rawRegion = rootAdbShell("getprop ro.miui.region")?.trim() ?: "IN"

                val model = rawModel ?: "Android Device"
                val codename = rawCodename?.lowercase() ?: "unknown"
                val kernelVer = rawKernel ?: "unknown"
                val secPatch = rootAdbShell("getprop ro.build.version.security_patch")?.trim() ?: "unknown"
                val androidVer = rootAdbShell("getprop ro.build.version.release")?.trim() ?: "14"

                // Accurate ROM Profile: Official Xiaomi HyperOS/MIUI vs Custom AOSP ROM (InfinityX, Lineage, etc.)
                val miuiVer = rootAdbShell("getprop ro.miui.ui.version.name")?.trim() ?: ""
                val displayId = rootAdbShell("getprop ro.build.display.id")?.trim() ?: ""
                val buildFlavor = rootAdbShell("getprop ro.build.flavor")?.trim() ?: ""
                val isOfficialXiaomiRom = miuiVer.isNotBlank() &&
                    (rawBuildVer.contains("OS", ignoreCase = true) || rawBuildVer.contains("V1", ignoreCase = true) || rawBuildVer.contains("V8", ignoreCase = true))
                val isCustomRom = !isOfficialXiaomiRom ||
                    listOf("aosp", "lineage", "infinity", "evolution", "crdroid", "pixel", "derpfest", "paranoid", "havoc")
                        .any { displayId.contains(it, ignoreCase = true) || buildFlavor.contains(it, ignoreCase = true) }

                val romProfile = if (isCustomRom) {
                    "Custom AOSP ROM (${displayId.ifBlank { buildFlavor.ifBlank { "AOSP Based" } }})"
                } else {
                    "Official Xiaomi HyperOS/MIUI ($rawBuildVer)"
                }

                ztLog("[i] Target: $model ($codename) · Android $androidVer")
                ztLog("[i] Kernel: $kernelVer")
                ztLog("[i] Security Patch: $secPatch")
                ztLog("[i] OS Profile: $romProfile")

                // Kernel Architecture & GKI Analysis
                val kernelMajorMinor = Regex("""^(\d+)\.(\d+)""").find(kernelVer)?.destructured
                val kMajor = kernelMajorMinor?.component1()?.toIntOrNull() ?: 0
                val kMinor = kernelMajorMinor?.component2()?.toIntOrNull() ?: 0
                val isGkiKernel = (kMajor > 5) || (kMajor == 5 && kMinor >= 10)

                if (isGkiKernel) {
                    ztLog("[+] Architecture: Modern GKI Kernel ($kernelVer)")
                    ztLog("[+] Extraction Engine: Automated Multi-Tier Engine (Live RAM / Server / Recovery)")
                } else {
                    ztLog("[i] Architecture: Legacy Kernel ($kernelVer) · Native Boot Ramdisk")
                }

                _uiState.update { it.copy(zeroTouchProgress = 0.35f, zeroTouchStep = "Stage 2/4 · Direct init_boot/boot Sourcing") }
                ztLog("--------------------------------------------------")
                ztLog("⚡ STAGE 2/4: Zero-Touch Sourcing Stock Boot Image…")

                // Android & Kernel Partition Matrix:
                // Google introduced 'init_boot' ONLY on devices launching with GKI Kernel 5.10+ (Android 13+).
                // Legacy kernel devices (Kernel 4.14, 4.19, etc. e.g. Snapdragon 860 on Xiaomi Pad 5)
                // NEVER have init_boot; their ramdisk is inside boot.img regardless of Android version!
                val partTest = rootAdbShell("ls -d /dev/block/by-name/init_boot* /dev/block/bootdevice/by-name/init_boot* /dev/block/platform/*/by-name/init_boot* 2>/dev/null")?.trim()
                val hasInitBootBlock = !partTest.isNullOrBlank() && !partTest.contains("No such file")
                val rawSdk = rootAdbShell("getprop ro.build.version.sdk")?.trim()?.toIntOrNull() ?: 0
                val hasInitBoot = isGkiKernel && (hasInitBootBlock || rawSdk >= 33)

                val kind = when (targetRoot) {
                    "Magisk" -> RootKind.MAGISK
                    "APatch" -> RootKind.APATCH
                    "SukiSU Ultra", "SukiSU" -> RootKind.SUKISU
                    else -> RootKind.KERNELSU
                }
                // KernelSU, SukiSU Ultra & Magisk: Ramdisk-based roots (patch init_boot on GKI devices, boot on legacy)
                // APatch: Kernel-based root (MUST patch boot image containing kernel binary)
                val isRamdiskRoot = (kind == RootKind.MAGISK || kind == RootKind.KERNELSU || kind == RootKind.SUKISU)
                val requiresInitBoot = (isRamdiskRoot && hasInitBoot)
                val targetPartition = if (requiresInitBoot) "init_boot" else "boot"

                if (hasInitBoot) {
                    ztLog("[+] Device partition layout: init_boot partition active (GKI Android ${if (rawSdk >= 36) "16+" else if (rawSdk >= 35) "15" else if (rawSdk >= 34) "14" else "13+"})")
                } else {
                    ztLog("[+] Device partition layout: boot partition active (Legacy Kernel / Non-GKI)")
                }
                ztLog("[i] Engine Target Partition: $targetPartition (${if (requiresInitBoot) "Ramdisk Init for ${kind.label}" else "Linux Kernel & Ramdisk for ${kind.label}"})")

                // Step 2.1: Check local storage candidates first
                val localCandidates = rootScanStockBootCandidates(codename, requiresInitBoot = requiresInitBoot)
                var stockBootFile: File? = null

                if (localCandidates.isNotEmpty()) {
                    if (localCandidates.size > 1) {
                        ztLog("[i] Candidate stock $targetPartition images (${localCandidates.size} files):")
                        localCandidates.take(5).forEachIndexed { idx, f ->
                            val dateStr = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US).format(java.util.Date(f.lastModified()))
                            ztLog("  [${idx + 1}] ${f.name} (${f.length() / 1024} KB · $dateStr)")
                        }
                    }
                    val best = rootPickBestStockBoot(
                        localCandidates,
                        codename,
                        requiresInitBoot = requiresInitBoot,
                        kernelVer = kernelVer,
                        buildVer = rawBuildVer,
                        isCustomRom = isCustomRom
                    )
                    if (best != null) {
                        stockBootFile = best
                        ztLog("[OK] Auto-selected verified stock image: ${best.name} (${best.length() / 1024} KB)")
                    } else if (isCustomRom) {
                        ztLog("[i] Discarded local official HyperOS stock files (prevents bootloop on Custom AOSP ROM).")
                    }
                }

                // Step 2.2: GhostLock-style in-memory live extraction on every device (never skipped!)
                if (stockBootFile == null) {
                    ztLog("→ Probing live RAM / userspace partition extraction ($targetPartition)…")
                    val liveBytes = rootExtractPartitionLiveDirect(targetPartition)
                    if (liveBytes != null && rootImageLooksValid(liveBytes)) {
                        stockBootFile = saveRootBytes("${targetPartition}_stock.img", liveBytes)
                        ztLog("[OK] Live extracted $targetPartition from device RAM: ${stockBootFile?.name} (${(stockBootFile?.length() ?: 0L) / 1024} KB)")
                    } else {
                        ztLog("[i] Live RAM extraction: unprivileged (UID != 0). Moving to firmware repository sourcing…")
                    }
                }

                // Online server ROM matching removed as requested - stock boot is sourced locally or via device/recovery

                                // Step 2.4: Target storage scan (/sdcard/Download/) if not found locally
                if (stockBootFile == null && _uiState.value.deviceMode != DeviceMode.FASTBOOT) {
                    ztLog("→ Scanning target device storage (/sdcard/Download/) for stock $targetPartition.img…")
                    val targetLs = rootAdbShell("ls -la /sdcard/Download/ 2>/dev/null")
                    if (!targetLs.isNullOrBlank()) {
                        val matchingLine = targetLs.lines().firstOrNull { line ->
                            val lower = line.lowercase()
                            lower.endsWith(".img") && !lower.contains("patched") &&
                                (if (targetPartition == "init_boot") lower.contains("init_boot") else (!lower.contains("init_boot") && lower.contains("boot")))
                        }
                        if (matchingLine != null) {
                            val fileName = matchingLine.split("\\s+".toRegex()).lastOrNull()?.trim()
                            if (!fileName.isNullOrBlank()) {
                                ztLog("→ Found candidate image on target device: $fileName. Pulling…")
                                val tempDest = File(appContext?.cacheDir, fileName)
                                if (rootAdbPull("/sdcard/Download/$fileName", tempDest) && tempDest.exists()) {
                                    val b = tempDest.readBytes()
                                    if (rootImageLooksValid(b)) {
                                        stockBootFile = saveRootBytes("${targetPartition}_stock.img", b)
                                        ztLog("[OK] Pulled verified stock image from target storage: $fileName (${b.size / 1024} KB)")
                                    }
                                }
                            }
                        }
                    }
                }

                if (stockBootFile == null || !stockBootFile.exists() || stockBootFile.length() <= 0L) {
                    ztLog("[!] Automated $targetPartition extraction unavailable for this device model.")
                    val targetName = "$targetPartition.img"
                    ztLog("→ Please select your stock $targetName from storage.")
                    ztLog("→ Tap '[📁 SELECT ${targetName.uppercase()}]' button below…")

                    val deferred = CompletableDeferred<File?>()
                    zeroTouchFileDeferred = deferred
                    _uiState.update {
                        it.copy(
                            zeroTouchAwaitingFile = true,
                            zeroTouchFilePrompt = targetName
                        )
                    }

                    val picked = deferred.await()
                    _uiState.update {
                        it.copy(
                            zeroTouchAwaitingFile = false,
                            zeroTouchFilePrompt = null
                        )
                    }

                    if (picked != null && picked.exists() && picked.length() > 0L) {
                        stockBootFile = picked
                        ztLog("[OK] Custom stock boot selected: ${picked.name} (${picked.length() / 1024} KB)")
                    } else {
                        ztLog("[STOP] No file selected. Zero-Touch aborted safely.")
                        _uiState.update { it.copy(zeroTouchBusy = false, zeroTouchFinished = true) }
                        return@launch
                    }
                }

                ztLog("[OK] Stock boot sourced: ${stockBootFile.name} (${stockBootFile.length() / 1024} KB)")

                // STAGE 3: IN-APP EMBEDDED PATCHING
                _uiState.update { it.copy(zeroTouchProgress = 0.65f, zeroTouchStep = "Stage 3/4 · In-App Native Patching") }
                ztLog("--------------------------------------------------")
                ztLog("⚡ STAGE 3/4: In-App Direct Patching ($targetRoot)…")

                

                ztLog("→ Fetching latest $targetRoot patch engine…")
                val apkFile = RootHelper.downloadLatestApk(kind, rootDownloadDir()) { msg -> ztLog(msg) }
                if (apkFile == null) {
                    ztLog("[ERROR] Failed to obtain $targetRoot package.")
                    _uiState.update { it.copy(zeroTouchBusy = false, zeroTouchFinished = true) }
                    return@launch
                }

                ztLog("→ Auto-installing $targetRoot Manager on target…")
                rootAdbInstall(apkFile)

                val stockForPatch = rootTrimStockForPatching(stockBootFile)
                ztLog("→ Running embedded patcher in /data/local/tmp/rf_patch/…")
                val patchedFile = rootExecuteInAppPatch(kind, stockForPatch, apkFile) { msg -> ztLog(msg) }

                if (patchedFile == null || !patchedFile.exists() || patchedFile.length() <= 0L) {
                    ztLog("[ERROR] Patch generation failed.")
                    _uiState.update { it.copy(zeroTouchBusy = false, zeroTouchFinished = true) }
                    return@launch
                }

                ztLog("[OK] Patched boot image generated: ${patchedFile.name} (${patchedFile.length() / 1024} KB)")

                // STAGE 4: FASTBOOT FLASHING
                _uiState.update { it.copy(zeroTouchProgress = 0.85f, zeroTouchStep = "Stage 4/4 · Fastboot Flashing & Auto-Reboot") }
                ztLog("--------------------------------------------------")
                ztLog("⚡ STAGE 4/4: Dual-Slot Fastboot Flashing…")

                ztLog("→ adb reboot bootloader")
                rootAdbReboot("bootloader")
                ztLog("→ Waiting for Fastboot handshake (90s)…")

                val inFastboot = waitForDevice(DeviceMode.FASTBOOT, 90000)
                if (!inFastboot) {
                    ztLog("[ERROR] Fastboot mode connection timed out.")
                    _uiState.update { it.copy(zeroTouchBusy = false, zeroTouchFinished = true) }
                    return@launch
                }

                val partitionName = targetPartition
                val partitions = rootFlashTargets(partitionName)

                var allOk = false
                for (p in partitions) {
                    ztLog("[i] Flashing $p…")
                    val ok = rootFastbootFlash(p, rootFitImageToPartition(patchedFile, p)) { msg -> ztLog(msg) }
                    if (ok) {
                        ztLog("[OK] $p flashed successfully.")
                        allOk = true
                    }
                }

                if (!allOk) {
                    allOk = rootFastbootFlash(partitionName, patchedFile) { msg -> ztLog(msg) }
                }

                if (allOk) {
                    ztLog("→ fastboot reboot")
                    rootFastbootCommand("reboot")
                    _uiState.update { it.copy(zeroTouchProgress = 1.0f, zeroTouchStep = "Completed Successfully!") }
                    ztLog("==================================================")
                    ztLog("🎉 ZERO-TOUCH ROOT COMPLETED SUCCESSFULLY!")
                    ztLog("Phone is now rebooting into system as fully rooted.")
                    ztLog("==================================================")
                } else {
                    ztLog("[ERROR] Flashing could not be completed.")
                }

            } catch (e: Exception) {
                ztLog("[ERROR] Zero-touch engine error: ${e.message}")
            } finally {
                _uiState.update { it.copy(zeroTouchBusy = false, zeroTouchFinished = true) }
            }
        }
    }

    // Xiaomi Boot / Init_Boot Downloader Controller
    fun openXiaomiBootDownloader() {
        val connectedCodename = _uiState.value.deviceCodeName.ifBlank { null }
        val preselected = if (!connectedCodename.isNullOrBlank()) {
            XiaomiRomHelper.findDeviceByCodename(connectedCodename)
        } else null

        _uiState.update {
            it.copy(
                showXiaomiBootDownloader = true,
                xiaomiSearchQuery = "",
                xiaomiSelectedDevice = preselected,
                xiaomiSelectedRegion = null,
                xiaomiRomVersions = emptyList(),
                xiaomiRomVersionsLoading = false,
                xiaomiDownloading = false,
                xiaomiDownloadProgress = 0f,
                xiaomiDownloadStatus = "",
                xiaomiDownloadedFiles = emptyList(),
                xiaomiDownloaderError = null
            )
        }
    }

    fun closeXiaomiBootDownloader() {
        try { xiaomiDownloadJob?.cancel() } catch (_: Exception) {}
        xiaomiDownloadJob = null
        _uiState.update {
            it.copy(
                showXiaomiBootDownloader = false,
                xiaomiDownloading = false
            )
        }
    }

    fun setXiaomiSearchQuery(query: String) {
        _uiState.update { it.copy(xiaomiSearchQuery = query) }
    }

    fun selectXiaomiDevice(device: XiaomiDevice) {
        _uiState.update {
            it.copy(
                xiaomiSelectedDevice = device,
                xiaomiSelectedRegion = null,
                xiaomiRomVersions = emptyList(),
                xiaomiRomVersionsLoading = false,
                xiaomiDownloaderError = null
            )
        }
    }

    fun clearXiaomiSelectedDevice() {
        _uiState.update {
            it.copy(
                xiaomiSelectedDevice = null,
                xiaomiSelectedRegion = null,
                xiaomiRomVersions = emptyList(),
                xiaomiRomVersionsLoading = false,
                xiaomiDownloaderError = null
            )
        }
    }

    fun clearXiaomiSelectedRegion() {
        _uiState.update {
            it.copy(
                xiaomiSelectedRegion = null,
                xiaomiRomVersions = emptyList(),
                xiaomiRomVersionsLoading = false,
                xiaomiDownloaderError = null
            )
        }
    }

    fun selectXiaomiRegion(region: XiaomiRegion) {
        val device = _uiState.value.xiaomiSelectedDevice ?: return
        _uiState.update {
            it.copy(
                xiaomiSelectedRegion = region,
                xiaomiRomVersionsLoading = true,
                xiaomiRomVersions = emptyList(),
                xiaomiDownloaderError = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val versions = XiaomiRomHelper.fetchRomVersions(device.codename, region.code)
            _uiState.update {
                it.copy(
                    xiaomiRomVersionsLoading = false,
                    xiaomiRomVersions = versions,
                    xiaomiDownloaderError = if (versions.isEmpty()) "No official builds found for ${device.marketingName} in ${region.name}." else null
                )
            }
        }
    }

    fun startXiaomiBootDownload(version: XiaomiRomVersion) {
        if (_uiState.value.xiaomiDownloading) return
        _uiState.update {
            it.copy(
                xiaomiDownloading = true,
                xiaomiDownloadProgress = 0.05f,
                xiaomiDownloadStatus = "Connecting to Xiaomi servers…",
                xiaomiDownloadedFiles = emptyList(),
                xiaomiDownloaderError = null
            )
        }
        xiaomiDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // User requirement: device codename + rom version in folder name to eliminate cross-device confusion
                val device = _uiState.value.xiaomiSelectedDevice
                val devicePrefix = device?.codename?.ifBlank { null } ?: "xiaomi"
                val folderName = "${devicePrefix}_${version.version}"
                val rootBootDir = File(RomFlasherStorage.child("boot/init_boot"), folderName)
                val res = XiaomiRomHelper.downloadBootImages(version, rootBootDir) { prog, status ->
                    _uiState.update {
                        it.copy(
                            xiaomiDownloadProgress = prog,
                            xiaomiDownloadStatus = status
                        )
                    }
                }
                res.onSuccess { files ->
                    _uiState.update {
                        it.copy(
                            xiaomiDownloading = false,
                            xiaomiDownloadProgress = 1.0f,
                            xiaomiDownloadStatus = "Download Complete! Saved ${files.size} image(s).",
                            xiaomiDownloadedFiles = files,
                            xiaomiDownloaderError = null
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            xiaomiDownloading = false,
                            xiaomiDownloaderError = err.message ?: "Download failed"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        xiaomiDownloading = false,
                        xiaomiDownloaderError = e.message ?: "Extraction failed"
                    )
                }
            }
        }
    }

    fun useDownloadedBootForRoot(file: File) {
        closeXiaomiBootDownloader()
        // If file picker was awaiting in zero-touch, complete it
        zeroTouchFileDeferred?.complete(file)
        zeroTouchFileDeferred = null
        // Set as custom image candidate and open Zero-Touch
        _uiState.update {
            it.copy(
                selectedImageFile = file,
                selectedPartition = if (file.name.contains("init_boot", ignoreCase = true)) "init_boot" else "boot"
            )
        }
        openZeroTouchDialog()
    }

    fun startUninstallRootEngine() {
        if (_uiState.value.zeroTouchBusy) return
        openZeroTouchDialog(isUninstall = true)
        zeroTouchJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    zeroTouchBusy = true,
                    zeroTouchFinished = false,
                    zeroTouchStep = "Stage 1/3 · Probing Target Device",
                    zeroTouchProgress = 0.15f,
                    zeroTouchLogs = listOf(
                        "==================================================",
                        "🗑️ UNIVERSAL ROOT UNINSTALLER",
                        "Target: Removing Magisk / KernelSU / APatch / SukiSU",
                        "=================================================="
                    )
                )
            }

            fun ztLog(msg: String) {
                _uiState.update { it.copy(zeroTouchLogs = it.zeroTouchLogs + msg) }
            }

            try {
                // Dual-Mode Connection Detection: FASTBOOT vs ADB
                val detected = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }
                val isAlreadyFastboot = (_uiState.value.deviceMode == DeviceMode.FASTBOOT) ||
                    (detected?.isConnected == true && detected.mode == DeviceMode.FASTBOOT) ||
                    kernelFastbootReady() ||
                    !rootFastbootVar("product").isNullOrBlank()

                val codename: String
                val hasInitBoot: Boolean
                val isCustomRom: Boolean
                val rawBuildVer: String
                val kernelVer: String

                if (isAlreadyFastboot) {
                    ztLog("⚡ STAGE 1/3: Target Device in FASTBOOT Mode Detected!")
                    var fbProduct = rootFastbootVarRetry("product")?.trim()?.lowercase() ?: ""
                    if (fbProduct.isBlank() || fbProduct == "unknown" || fbProduct == "fastboot_target") {
                        fbProduct = _uiState.value.deviceCodeName.trim().lowercase()
                            .ifBlank { detected?.codeName?.trim()?.lowercase() ?: "" }
                            .ifBlank { "nabu" }
                    }
                    codename = fbProduct
                    val currentSlot = rootFastbootVarRetry("current-slot")?.trim() ?: ""
                    val initBootSize = rootPartitionSize("init_boot") ?: rootPartitionSize("init_boot_a")
                    hasInitBoot = if (codename == "nabu") false else (initBootSize != null && initBootSize > 0L)
                    isCustomRom = false
                    rawBuildVer = ""
                    kernelVer = ""

                    ztLog("[+] Active Connection: FASTBOOT Mode")
                    ztLog("[i] Fastboot Target: $codename")
                    if (currentSlot.isNotBlank()) ztLog("[i] Active Slot: _$currentSlot")
                    ztLog("[i] Partition Architecture: ${if (hasInitBoot) "GKI (init_boot + boot)" else "Legacy (boot only)"}")
                    ztLog("[i] Target is in Bootloader mode: Skipping ADB app uninstallation, proceeding directly to clean stock restore.")
                } else {
                    // STAGE 1: PROBE TARGET DEVICE & UNINSTALL ROOT MANAGERS (ADB MODE)
                    ztLog("⚡ STAGE 1/3: Probing Target Device & Cleaning Root Apps…")
                    val adbCodename = rootAdbShell("getprop ro.product.device")?.trim() ?: "unknown"
                    val rawSdk = rootAdbShell("getprop ro.build.version.sdk")?.trim()?.toIntOrNull() ?: 0
                    val rawIncremental = rootAdbShell("getprop ro.build.version.incremental")?.trim() ?: ""
                    val rawKernel = rootAdbShell("uname -r")?.trim() ?: "unknown"
                    val kernelMajorMinor = Regex("""^(\d+)\.(\d+)""").find(rawKernel)?.destructured
                    val kMajor = kernelMajorMinor?.component1()?.toIntOrNull() ?: 0
                    val kMinor = kernelMajorMinor?.component2()?.toIntOrNull() ?: 0
                    val isGkiKernel = (kMajor > 5) || (kMajor == 5 && kMinor >= 10)

                    val partTest = rootAdbShell("ls -d /dev/block/by-name/init_boot* /dev/block/bootdevice/by-name/init_boot* /dev/block/platform/*/by-name/init_boot* 2>/dev/null")?.trim()
                    val hasInitBootBlock = !partTest.isNullOrBlank() && !partTest.contains("No such file")
                    hasInitBoot = isGkiKernel && (hasInitBootBlock || rawSdk >= 33)

                    // Accurate ROM Profile & Port ROM Detection
                    val miuiVer = rootAdbShell("getprop ro.miui.ui.version.name")?.trim() ?: ""
                    val displayId = rootAdbShell("getprop ro.build.display.id")?.trim() ?: ""
                    val buildFlavor = rootAdbShell("getprop ro.build.flavor")?.trim() ?: ""
                    val isOfficialXiaomiRom = miuiVer.isNotBlank() &&
                        (rawIncremental.contains("OS", ignoreCase = true) || rawIncremental.contains("V1", ignoreCase = true) || rawIncremental.contains("V8", ignoreCase = true))
                    isCustomRom = !isOfficialXiaomiRom ||
                        listOf("aosp", "lineage", "infinity", "evolution", "crdroid", "pixel", "derpfest", "paranoid", "havoc")
                            .any { displayId.contains(it, ignoreCase = true) || buildFlavor.contains(it, ignoreCase = true) }

                    codename = adbCodename
                    rawBuildVer = rawIncremental
                    kernelVer = rawKernel

                    val romProfile = if (isCustomRom) {
                        "Custom/Port ROM (${displayId.ifBlank { buildFlavor.ifBlank { "AOSP/Port Based" } }})"
                    } else {
                        "Official Xiaomi HyperOS/MIUI ($rawBuildVer)"
                    }

                    ztLog("[i] Target Device: $codename · Android ${if (rawSdk >= 36) "16" else if (rawSdk >= 35) "15" else if (rawSdk >= 34) "14" else if (rawSdk >= 33) "13" else "$rawSdk"}")
                    ztLog("[i] Partition Architecture: ${if (hasInitBoot) "GKI (init_boot + boot)" else "Legacy (boot only)"}")
                    ztLog("[i] OS Profile: $romProfile")

                    // Uninstall known root manager packages
                    val rootPackages = listOf(
                        "com.topjohnwu.magisk" to "Magisk",
                        "me.weishu.kernelsu" to "KernelSU",
                        "me.bmax.apatch" to "APatch",
                        "com.sukisu.ultra" to "SukiSU Ultra",
                        "io.github.a13xp0p0v.kpatch" to "KPatch",
                        "com.android.shell.su" to "SU Helper"
                    )

                    var uninstalledCount = 0
                    for ((pkg, name) in rootPackages) {
                        val installed = rootAdbShell("pm list packages $pkg")?.contains(pkg) == true
                        if (installed) {
                            ztLog("→ Uninstalling $name Manager ($pkg)…")
                            rootAdbShell("pm uninstall $pkg")
                            ztLog("[OK] $name uninstalled successfully.")
                            uninstalledCount++
                        }
                    }
                    if (uninstalledCount == 0) {
                        ztLog("[i] No third-party root manager APKs found on system.")
                    }

                    // Clean root temp / adb folders if shell has access
                    rootAdbShell("rm -rf /data/local/tmp/rf_patch /data/local/tmp/ksud* /data/local/tmp/kptools* 2>/dev/null")
                }

                // STAGE 2: SOURCE CLEAN STOCK BOOT / INIT_BOOT IMAGES
                _uiState.update { it.copy(zeroTouchProgress = 0.45f, zeroTouchStep = "Stage 2/3 · Sourcing Clean Stock Images") }
                ztLog("--------------------------------------------------")
                ztLog("⚡ STAGE 2/3: Sourcing Clean Stock Images…")
                ztLog("[*] Scanning Host Storage (/sdcard/Download/)…")

                // If GKI: need clean stock init_boot (and optionally boot)
                val localCandidates = rootScanStockBootCandidates(codename, requiresInitBoot = hasInitBoot)
                var stockInitBootFile: File? = if (hasInitBoot) rootPickBestStockBoot(
                    localCandidates,
                    codename,
                    requiresInitBoot = true,
                    kernelVer = kernelVer,
                    buildVer = rawBuildVer,
                    isCustomRom = isCustomRom
                ) else null
                var stockBootFile: File? = rootPickBestStockBoot(
                    rootScanStockBootCandidates(codename, requiresInitBoot = false),
                    codename,
                    requiresInitBoot = false,
                    kernelVer = kernelVer,
                    buildVer = rawBuildVer,
                    isCustomRom = isCustomRom
                )

                if (isCustomRom && stockInitBootFile == null && stockBootFile == null && localCandidates.isNotEmpty()) {
                    ztLog("[i] Discarded official HyperOS stock files (prevents bootloop on Custom/Port ROM).")
                }

                if (hasInitBoot && stockInitBootFile != null) {
                    ztLog("[OK] Sourced stock init_boot: ${stockInitBootFile.name} (${stockInitBootFile.length() / 1024} KB)")
                }
                if (stockBootFile != null) {
                    ztLog("[OK] Sourced stock boot: ${stockBootFile.name} (${stockBootFile.length() / 1024} KB)")
                }

                // If stock file not found, prompt user to select it with 1-click picker
                val primaryStock = if (hasInitBoot) (stockInitBootFile ?: stockBootFile) else stockBootFile
                var resolvedStock: File? = primaryStock

                if (resolvedStock == null || !resolvedStock.exists() || resolvedStock.length() <= 0L) {
                    val promptName = if (hasInitBoot) "clean stock init_boot.img" else "clean stock boot.img"
                    ztLog("[!] Clean $promptName not found in storage.")
                    if (isCustomRom) {
                        ztLog("[!] Note: Custom/Port ROM detected. Official HyperOS images are blocked to prevent bootloop.")
                        ztLog("→ Please select the clean $promptName from your ROM zip.")
                    } else {
                        ztLog("→ Please select your clean $promptName or download it via [📥 DOWNLOAD BOOT/INIT_BOOT].")
                    }
                    ztLog("→ Tap '[📁 SELECT STOCK IMAGE]' button below…")

                    val deferred = CompletableDeferred<File?>()
                    zeroTouchFileDeferred = deferred
                    _uiState.update {
                        it.copy(
                            zeroTouchAwaitingFile = true,
                            zeroTouchFilePrompt = if (hasInitBoot) "init_boot.img" else "boot.img"
                        )
                    }

                    val picked = deferred.await()
                    _uiState.update {
                        it.copy(
                            zeroTouchAwaitingFile = false,
                            zeroTouchFilePrompt = null
                        )
                    }

                    if (picked != null && picked.exists() && picked.length() > 0L) {
                        resolvedStock = picked
                        ztLog("[OK] Stock image selected: ${picked.name} (${picked.length() / 1024} KB)")
                    } else {
                        ztLog("[STOP] No stock image selected. Uninstallation aborted safely.")
                        _uiState.update { it.copy(zeroTouchBusy = false, zeroTouchFinished = true) }
                        return@launch
                    }
                }

                // STAGE 3: FASTBOOT RESTORE FLASHING
                _uiState.update { it.copy(zeroTouchProgress = 0.75f, zeroTouchStep = "Stage 3/3 · Fastboot Dual-Slot Stock Flashing") }
                ztLog("--------------------------------------------------")
                ztLog("⚡ STAGE 3/3: Dual-Slot Fastboot Flashing Clean Stock…")

                if (!isAlreadyFastboot) {
                    ztLog("→ adb reboot bootloader")
                    rootAdbReboot("bootloader")
                    ztLog("→ Waiting for Fastboot handshake (90s)…")

                    val inFastboot = waitForDevice(DeviceMode.FASTBOOT, 90000)
                    if (!inFastboot) {
                        ztLog("[ERROR] Fastboot mode connection timed out.")
                        _uiState.update { it.copy(zeroTouchBusy = false, zeroTouchFinished = true) }
                        return@launch
                    }
                } else {
                    ztLog("[+] Device already in Fastboot mode. Proceeding directly to flash clean stock…")
                }

                var flashOk = false
                val isPickedInitBoot = resolvedStock.name.contains("init_boot", ignoreCase = true)
                val flashPart = if (isPickedInitBoot) "init_boot" else (if (hasInitBoot && stockInitBootFile != null) "init_boot" else "boot")
                val stockToFlash = if (flashPart == "init_boot") (stockInitBootFile ?: resolvedStock) else (stockBootFile ?: resolvedStock)

                val targets = rootFlashTargets(flashPart)
                for (p in targets) {
                    ztLog("[i] Flashing clean stock to $p…")
                    val ok = rootFastbootFlash(p, stockToFlash) { msg -> ztLog(msg) }
                    if (ok) {
                        ztLog("[OK] $p flashed clean stock successfully.")
                        flashOk = true
                    }
                }

                // Also if we have clean stock boot and this is a GKI device, restore boot partition to clean stock as well
                if (hasInitBoot && stockBootFile != null && stockBootFile.exists() && flashPart != "boot") {
                    val bootTargets = rootFlashTargets("boot")
                    for (bp in bootTargets) {
                        ztLog("[i] Flashing clean stock to $bp…")
                        rootFastbootFlash(bp, stockBootFile) { msg -> ztLog(msg) }
                    }
                }

                if (flashOk) {
                    ztLog("→ fastboot reboot")
                    rootFastbootCommand("reboot")
                    _uiState.update { it.copy(zeroTouchProgress = 1.0f, zeroTouchStep = "Root Uninstalled Successfully!") }
                    ztLog("==================================================")
                    ztLog("🎉 ROOT COMPLETELY UNINSTALLED & RESTORED!")
                    ztLog("Clean stock boot/init_boot restored. Device is rebooting into system.")
                    ztLog("==================================================")
                } else {
                    ztLog("[ERROR] Flashing clean stock could not be completed.")
                }

            } catch (e: Exception) {
                ztLog("[ERROR] Uninstall root error: ${e.message}")
            } finally {
                _uiState.update { it.copy(zeroTouchBusy = false, zeroTouchFinished = true) }
            }
        }
    }

    fun chooseRootKind(kind: RootKind) {

        if (_uiState.value.rootBusy) return

        rootCustomPackage = null

        _uiState.update {

            it.copy(

                rootKind = kind,

                rootFinished = false,

                rootLogs = listOf(

                    "Root method: ${kind.label}",

                    "============================================="

                )

            )

        }

        startRootFlow(kind)

    }

    fun answerRootPrompt(yes: Boolean) {

        rootPromptDeferred?.complete(yes)

    }

    fun answerRootChoice(index: Int) {

        rootChoiceDeferred?.complete(index)

    }

    /**

     * Copies the file the user just picked for the root wizard (root APK / init_boot.img /

     * patched .img) into the app cache **off the main thread**.

     *

     * Doing this copy synchronously in the activity-result callback froze the wizard the

     * moment a large APK was chosen, because the whole file was streamed on the UI thread.

     * Here we flip [UiState.rootFilePreparing] on so the dialog can show a loading state,

     * do the copy on [Dispatchers.IO], then hand the finished file back to the awaiting step.

     */

    fun provideRootFileUri(context: Context, uri: Uri?, fallbackName: String) {

        if (uri == null) {

            rootFileDeferred?.complete(null)

            return

        }

        _uiState.update { it.copy(rootFilePreparing = true) }

        viewModelScope.launch(Dispatchers.IO) {

            val file = try {

                val rawName = queryDisplayName(context, uri).ifBlank { fallbackName }

                val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")

                val out = File(context.cacheDir, safeName)

                context.contentResolver.openInputStream(uri)?.use { input ->

                    FileOutputStream(out).use { output -> input.copyTo(output) }

                }

                if (out.exists() && out.length() > 0L) out else null

            } catch (_: Exception) {

                null

            }

            _uiState.update { it.copy(rootFilePreparing = false) }

            rootFileDeferred?.complete(file)

        }

    }

    /** Best-effort display name for a picked content [uri]; falls back to the caller's name. */

    private fun queryDisplayName(context: Context, uri: Uri): String {

        return try {

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->

                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) ?: "" else ""

            } ?: ""

        } catch (_: Exception) {

            ""

        }

    }

    // ------------------------------------------- Restore ROM compatibility

    // Before an IMEI/EFS or kernel restore, the backup's ROM is compared with the

    // phone's current ROM. On a mismatch the user must confirm before anything is written.

    private var romMismatchDeferred: CompletableDeferred<Boolean>? = null

    fun proceedRomMismatch() {

        romMismatchDeferred?.complete(true)

    }

    fun cancelRomMismatch() {

        romMismatchDeferred?.complete(false)

    }

    /**

     * Shows the ROM-mismatch confirmation dialog and suspends until the user chooses.

     * Returns true = restore anyway, false = cancel.

     */

    private suspend fun confirmRomMismatch(

        backup: EfsBackupRestoreEngine.RomInfo,

        current: EfsBackupRestoreEngine.RomInfo

    ): Boolean {

        val deferred = CompletableDeferred<Boolean>()

        romMismatchDeferred = deferred

        _uiState.update {

            it.copy(

                showRomMismatchDialog = true,

                romMismatchBackup = backup.shortLabel(),

                romMismatchCurrent = current.shortLabel()

            )

        }

        return try {

            deferred.await()

        } finally {

            if (romMismatchDeferred === deferred) romMismatchDeferred = null

            _uiState.update { it.copy(showRomMismatchDialog = false) }

        }

    }

    // ------------------------------------------------------- Restore picker

    // Restoring the wrong IMEI / kernel backup can brick a phone, so the user always

    // picks WHICH backup to restore from a clearly labelled list — ROM family, ROM name,

    // phone model and date — instead of the app silently grabbing whatever it finds.

    /** Opens the picker with every IMEI / EFS backup found on this host phone. */

    fun openImeiRestorePicker() = openRestorePicker(

        kind = RomFlasherStorage.KIND_IMEI,

        scan = { FastbootRomFlasher.listImeiBackups() }

    )

    /** Opens the picker with every kernel backup found on this host phone. */

    fun openKernelRestorePicker() = openRestorePicker(

        kind = RomFlasherStorage.KIND_KERNEL,

        scan = { FastbootRomFlasher.listKernelBackups() }

    )

    /** Re-scans the backup folders while the picker is open. */

    fun refreshRestorePicker() {

        val kind = _uiState.value.restorePickerKind

        if (kind == RomFlasherStorage.KIND_KERNEL) openKernelRestorePicker() else openImeiRestorePicker()

    }

    /**

     * Shows the restore dialog immediately ("dhoonda ja raha hai…") and fills the list from a

     * background thread, so scanning a big backup tree can never freeze the UI.

     */

    private fun openRestorePicker(

        kind: String,

        scan: suspend () -> List<RomFlasherStorage.BackupEntry>

    ) {

        _uiState.update {

            it.copy(

                showRestorePicker = true,

                restorePickerKind = kind,

                restorePickerEntries = emptyList(),

                restorePickerScanned = false

            )

        }

        viewModelScope.launch(Dispatchers.IO) {

            val entries = try { scan() } catch (_: Exception) { emptyList() }

            _uiState.update {

                if (it.showRestorePicker && it.restorePickerKind == kind) {

                    it.copy(restorePickerEntries = entries, restorePickerScanned = true)

                } else {

                    it

                }

            }

        }

    }

    fun dismissRestorePicker() {

        _uiState.update {

            it.copy(

                showRestorePicker = false,

                restorePickerEntries = emptyList(),

                restorePickerScanned = false

            )

        }

    }

    /** User picked a backup: close the picker and run the matching restore on that folder. */

    fun confirmRestorePick(entry: RomFlasherStorage.BackupEntry) {

        val kind = _uiState.value.restorePickerKind

        dismissRestorePicker()

        if (kind == RomFlasherStorage.KIND_KERNEL) {

            // A boot-looping phone often cannot stay up long enough for the rooted ADB restore,

            // so ask first and offer the bootloader (fastboot) path when the answer is YES.

            pendingKernelRestoreDir = entry.dir

            _uiState.update {

                it.copy(

                    showKernelBootloopPrompt = true,

                    kernelBootloopBackupLabel = entry.summaryText()

                )

            }

        } else {

            startEfsRestore(entry.dir)

        }

    }

    /** Boot-loop question dismissed without an answer: drop the pending pick. */

    fun dismissKernelBootloopPrompt() {

        pendingKernelRestoreDir = null

        _uiState.update { it.copy(showKernelBootloopPrompt = false) }

    }

    /** "Yes, my phone boot-looped" -> flash the picked backup over fastboot from the bootloader. */

    fun kernelBootloopYes() {

        val folder = pendingKernelRestoreDir

        pendingKernelRestoreDir = null

        _uiState.update { it.copy(showKernelBootloopPrompt = false) }

        if (folder == null) return

        startKernelBootloopRestore(folder)

    }

    /** "No, it did not boot-loop" -> the classic rooted ADB restore. */

    fun kernelBootloopNo() {

        val folder = pendingKernelRestoreDir

        pendingKernelRestoreDir = null

        _uiState.update { it.copy(showKernelBootloopPrompt = false) }

        if (folder == null) return

        startKernelRestore(folder)

    }

    // ------------------------------------------------------- Kernel Flasher

    // Backup / restore of the physical kernel partitions and flashing of an

    // AnyKernel3 `.zip`.  Everything runs host -> target over USB/ADB with root;

    // the Root Wizard is offered automatically when the phone is not rooted.

    private var kernelJob: Job? = null

    private var kernelRebootJob: Job? = null

    /** Kernel backup folder picked in the restore picker, waiting for the boot-loop answer. */

    private var pendingKernelRestoreDir: File? = null

    private fun kernelLog(message: String) {

        _uiState.update { st -> st.copy(kernelLogs = (st.kernelLogs + message).takeLast(800)) }

    }

    /** Toggles the "back up the current kernel before flashing" safety step. */

    fun setKernelAutoBackup(enabled: Boolean) {

        _uiState.update { it.copy(kernelAutoBackup = enabled) }

    }

    /** Caches the user-picked kernel `.zip` on the host so it can be uploaded later. */

    fun setKernelZip(context: Context, uri: Uri) {

        viewModelScope.launch(Dispatchers.IO) {

            try {

                val name = getFileNameFromUri(context, uri)

                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "kernel.zip" }

                val out = File(context.cacheDir, "kernel_zip_$safe")

                context.contentResolver.openInputStream(uri)?.use { input ->

                    FileOutputStream(out).use { output -> input.copyTo(output) }

                }

                if (out.exists() && out.length() > 0L) {

                    _uiState.update { it.copy(kernelZipName = name, kernelZipPath = out.absolutePath) }

                } else {

                    _uiState.update { it.copy(errorMessage = "Could not read the selected kernel zip.") }

                }

            } catch (e: Exception) {

                _uiState.update { it.copy(errorMessage = "Could not open the kernel zip: ${e.message}") }

            }

        }

    }

    fun closeKernelFlasher() {

        try { kernelJob?.cancel() } catch (_: Exception) {}

        kernelJob = null

        try { kernelRebootJob?.cancel() } catch (_: Exception) {}

        kernelRebootJob = null

        pendingKernelRestoreDir = null

        _uiState.update {

            it.copy(

                showKernelFlasher = false,

                kernelBusy = false,

                kernelRebootSeconds = null,

                showKernelRootPrompt = false,

                showKernelBootloopPrompt = false

            )

        }

    }

    /** Hand off to the guided rooting wizard when the target is not rooted yet. */

    fun openRootWizardFromKernel() {

        _uiState.update { it.copy(showKernelRootPrompt = false) }

        closeKernelFlasher()

        openRootWizard()

    }

    fun startKernelBackup() = launchKernelAction(

        intro = listOf(

            "ROM Flasher - Kernel Backup",

            "---------------------------------------------"

        )

    ) { manager, device ->

        val folder = KernelBackupRestoreEngine.backupKernel(

            usbManager = manager,

            usbDevice = device,

            deviceModel = _uiState.value.deviceModel,

            onLog = ::kernelLog,

            onProgress = { }

        )

        if (folder != null) {

            kernelLog("[OK] Backup folder: ${folder.absolutePath}")

            AudioHapticEngine.speakCompletion(

                englishText = "Sir, kernel backup created successfully, sir!",

                hinglishText = "Bhai, kernel backup ban gaya hai!"

            )

        }

    }

    /** Restores a kernel backup; [hostFolder] is the picker's choice (null = newest backup). */

    fun startKernelRestore(hostFolder: File? = null) {

        launchKernelAction(

            intro = listOf(

                "ROM Flasher - Kernel Restore",

                if (hostFolder != null) "Backup: ${hostFolder.name}" else "Backup: newest available",

                "---------------------------------------------"

            )

        ) { manager, device ->

            val ok = KernelBackupRestoreEngine.restoreKernel(

                manager, device, ::kernelLog,

                { backup, current -> confirmRomMismatch(backup, current) },

                hostFolder

            ) { }

            if (ok) {

            AudioHapticEngine.speakCompletion(

                englishText = "Sir, kernel restored successfully, sir!",

                hinglishText = "Bhai, kernel restore ho gaya hai!"

            )

            startKernelRebootCountdown()

        }

        }

    }

    // --------------------------------------- Kernel restore (boot-loop / fastboot path)

    // A boot-looping phone may never stay switched on long enough for the rooted ADB restore to

    // finish, so this path sends it to the bootloader and flashes every backed-up image with

    // fastboot, one by one. No root is required for this — only an unlocked bootloader.

    /** Opens the kernel console and runs the bootloader (fastboot) restore for [hostFolder]. */

    fun startKernelBootloopRestore(hostFolder: File) {

        if (_uiState.value.kernelBusy) return

        try { kernelRebootJob?.cancel() } catch (_: Exception) {}

        kernelRebootJob = null

        try { kernelJob?.cancel() } catch (_: Exception) {}

        _uiState.update {

            it.copy(

                showKernelFlasher = true,

                kernelLogs = listOf(

                    "ROM Flasher - Kernel Restore (bootloader / fastboot)",

                    "Backup: ${hostFolder.name}",

                    "---------------------------------------------"

                ),

                kernelBusy = true,

                kernelRebootSeconds = null,

                showKernelRootPrompt = false

            )

        }

        kernelJob = viewModelScope.launch(Dispatchers.IO) {

            try {

                runKernelBootloopRestore(hostFolder)

            } catch (e: Exception) {

                kernelLog("[ERROR] ${e.message}")

            } finally {

                _uiState.update { it.copy(kernelBusy = false) }

            }

        }

    }

    private suspend fun runKernelBootloopRestore(hostFolder: File) {

        val images = hostFolder.listFiles { f -> f.isFile && f.name.endsWith(".img", ignoreCase = true) }

            ?.sortedBy { it.name }

            ?: emptyList()

        if (images.isEmpty()) {

            kernelLog(tr("[ERROR] This backup folder has no kernel images (.img).", "[ERROR] Is backup folder me koi kernel image (.img) nahi hai."))

            return

        }

        kernelLog(tr("[i] Boot-loop mode: every image is flashed in the bootloader with fastboot (no root needed).", "[i] Boot-loop mode: har image bootloader me fastboot se flash hogi (root ki zaroorat nahi)."))

        kernelLog("[INFO] Backup: ${hostFolder.absolutePath} (${images.size} images)")

        // 1) Get the phone into the bootloader. If it is already there, skip the reboot.

        if (!kernelFastbootReady()) {

            val manager = usbManager

            if (manager != null) {

                kernelLog(tr("[i] Sending the target to the bootloader (adb reboot bootloader)…", "[i] Target ko bootloader me bhej rahe hain (adb reboot bootloader)…"))

                if (!kernelRebootToBootloader(manager)) {

                    kernelLog(tr("[!] Could not reboot over ADB — open the bootloader on the phone now.", "[!] ADB se reboot nahi ho paaya — ab phone par bootloader kholo."))

                }

            }

        }

        if (!kernelWaitForFastboot(150_000)) {

            kernelLog(tr("[X] The fastboot / bootloader device was not detected.", "[X] Fastboot / bootloader device detect nahi hua."))

            kernelLog(tr("[ACTION] Switch the phone OFF, then hold Power + Volume Down (the exact keys vary by phone) to open the bootloader, and press RESTORE again.", "[ACTION] Phone OFF karo, phir Power + Volume Down (keys phone ke hisaab se badal sakti hain) dabaakar bootloader kholo, aur RESTORE dobara dabao."))

            return

        }

        kernelLog(tr("[OK] Bootloader (fastboot) detected.", "[OK] Bootloader (fastboot) detect ho gaya."))

        // 2) Flash each backup image, one by one.

        val total = images.size

        var ok = 0

        var failed = 0

        for ((idx, img) in images.withIndex()) {

            // Closing the console cancels the job; stop cleanly BETWEEN partitions

            // (never mid-flash: the fastboot write below is a blocking call that

            // finishes the current partition first).

            if (!coroutineContext.isActive) {

                kernelLog(tr("[i] Restore cancelled — remaining images were not flashed.", "[i] Restore cancel kar diya — baaki images flash nahi hui."))

                return

            }

            val part = img.nameWithoutExtension

            kernelLog("---------------------------------------------")

            kernelLog(tr("[${idx + 1}/$total] Flashing $part …", "[${idx + 1}/$total] $part flash kar rahe hain …"))

            if (fastbootFlashWithRetry(part, img)) {

                ok++

                kernelLog(tr("[SUCCESS] $part restored.", "[SUCCESS] $part restore ho gaya."))

            } else {

                failed++

                kernelLog(tr("[ERROR] $part could not be flashed.", "[ERROR] $part flash nahi ho paaya."))

            }

        }

        kernelLog("=============================================")

        if (ok > 0) {

            kernelLog(tr("[COMPLETED] Kernel restore finished ($ok partitions written).", "[COMPLETED] Kernel restore pura hua ($ok partition likhe gaye)."))

        } else {

            kernelLog(tr("[ERROR] Nothing was restored.", "[ERROR] Kuch bhi restore nahi hua."))

        }

        kernelLog("=============================================")

        // 3) Boot the phone back to the system so it can start normally.

        if (ok > 0) {

            kernelLog(tr("[i] Rebooting the phone to the system…", "[i] Phone ko system me reboot kar rahe hain…"))

            kernelFastbootReboot()

            kernelLog(tr("[OK] Reboot command sent.", "[OK] Reboot command bhej diya."))

        }

        if (failed > 0) {

            kernelLog(tr("[WARN] $failed partition(s) did not flash — you can run the restore again.", "[WARN] $failed partition flash nahi hue — restore dobara chala sakte ho."))

        }

    }

    /** True when a device is currently visible in bootloader / fastboot mode. */

    private fun kernelFastbootReady(): Boolean {

        val detected = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

        if (detected != null && detected.isConnected && detected.mode == DeviceMode.FASTBOOT) {

            currentConnectedDevice = detected.usbDevice

            // Called from a wait loop - asking for permission here meant one

            // system dialog per poll. The actual flash path calls

            // ensureUsbPermission() before it touches the device.

            _uiState.update { it.copy(isConnected = true, deviceMode = detected.mode) }

            return true

        }

        return false

    }

    /** Polls for a bootloader / fastboot device, logging hints to the kernel console. */

    private suspend fun kernelWaitForFastboot(timeoutMs: Long): Boolean {

        val started = System.currentTimeMillis()

        val deadline = started + timeoutMs

        var lastHintAt = started

        while (System.currentTimeMillis() < deadline) {

            if (kernelFastbootReady()) return true

            val now = System.currentTimeMillis()

            if (now - lastHintAt >= 8000) {

                lastHintAt = now

                val waited = (now - started) / 1000

                kernelLog(tr("[i] Waiting for the bootloader (fastboot)… (${waited}s)", "[i] Bootloader (fastboot) ka wait… (${waited}s)"))

                kernelLog(tr("[i] If it is stuck: turn the phone OFF, then Power + Volume Down to open the bootloader.", "[i] Agar ruka hai: phone OFF karo, phir Power + Volume Down se bootloader kholo."))

            }

            delay(1000)

        }

        return false

    }

    /** Best-effort `adb reboot bootloader`; logs to the kernel console. */

    private fun kernelRebootToBootloader(manager: UsbManager): Boolean {

        for (dev in rootCandidateDevices()) {

            if (!ensureUsbPermission(dev)) continue

            val proto = try { AdbSideloadProtocol.open(manager, dev) } catch (_: Exception) { null } ?: continue

            try {

                kernelLog("> adb reboot bootloader")

                var ok = false

                try { ok = proto.executeAdbRebootService("bootloader") } catch (_: Exception) {}

                if (!ok) {

                    try { if (proto.executeAdbShellCommand("reboot bootloader") != null) ok = true } catch (_: Exception) {}

                }

                if (!ok) {

                    try { if (proto.executeAdbShellCommand("svc power reboot bootloader") != null) ok = true } catch (_: Exception) {}

                }

                if (ok) {

                    currentConnectedDevice = null

                    permissionRequestedIds.clear()

                    return true

                }

            } catch (_: Exception) {

            } finally {

                try { proto.close() } catch (_: Exception) {}

            }

        }

        return false

    }

    /** Flashes one backup image in the bootloader, retrying a couple of times. */

    private fun fastbootFlashWithRetry(partition: String, image: File, attempts: Int = 3): Boolean {

        for (attempt in 1..attempts) {

            if (rootFastbootFlash(partition, image) { kernelLog(it) }) return true

            if (attempt < attempts) {

                kernelLog(tr("[i] Retrying $partition (${attempt + 1}/$attempts)…", "[i] $partition dobara try (${attempt + 1}/$attempts)…"))

                try { Thread.sleep(1200) } catch (_: Exception) {}

            }

        }

        return false

    }

    /** Sends `fastboot reboot` (best effort — the device disconnects mid-command). */

    private fun kernelFastbootReboot() {

        val manager = usbManager ?: return

        for (dev in rootCandidateDevices()) {

            if (!ensureUsbPermission(dev)) continue

            val session = try { FastbootSession.open(manager, dev) } catch (_: Exception) { null } ?: continue

            try {

                kernelLog("> fastboot reboot")

                session.executeGenericCommand("reboot")

            } catch (_: Exception) {

            } finally {

                try { session.close() } catch (_: Exception) {}

            }

            return

        }

    }

    fun flashKernelZip() = launchKernelAction(

        intro = listOf(

            "ROM Flasher - Kernel ZIP Flash",

            "---------------------------------------------"

        )

    ) { manager, device ->

        val zip = _uiState.value.kernelZipPath.takeIf { it.isNotBlank() }?.let { File(it) }

        if (zip == null || !zip.exists()) {

            kernelLog(tr("[ERROR] First select a kernel .zip.", "[ERROR] Pehle ek kernel .zip select karo."))

            return@launchKernelAction

        }

        if (_uiState.value.kernelAutoBackup) {

            kernelLog(tr("[i] Auto-backing up the current kernel before flashing…", "[i] Flash se pehle current kernel ka auto-backup le raha hoon…"))

            val folder = KernelBackupRestoreEngine.backupKernel(

                manager, device, _uiState.value.deviceModel, ::kernelLog

            ) { }

            if (folder == null) {

                kernelLog(tr("[WARN] Auto-backup failed — still trying to flash.", "[WARN] Auto-backup fail hua — phir bhi flash try kar raha hoon."))

            } else {

                kernelLog("[OK] Auto-backup: ${folder.absolutePath}")

            }

        }

        val ctx = appContext

        if (ctx == null) {

            kernelLog("[ERROR] Internal error: context missing.")

            return@launchKernelAction

        }

        val ok = Ak3Flasher.flashZip(ctx, manager, device, zip, ::kernelLog)

        if (ok) startKernelRebootCountdown()

    }

    fun cancelKernelReboot() {

        try { kernelRebootJob?.cancel() } catch (_: Exception) {}

        kernelRebootJob = null

        _uiState.update { it.copy(kernelRebootSeconds = null) }

        kernelLog(tr("[i] Auto-reboot cancelled. Reboot the phone manually to apply the kernel.", "[i] Auto-reboot cancel kiya. Kernel apply karne ke liye phone ko manually reboot karo."))

    }

    fun rebootNow() {

        try { kernelRebootJob?.cancel() } catch (_: Exception) {}

        kernelRebootJob = null

        _uiState.update { it.copy(kernelRebootSeconds = null) }

        viewModelScope.launch(Dispatchers.IO) { doKernelReboot() }

    }

    private fun startKernelRebootCountdown() {

        try { kernelRebootJob?.cancel() } catch (_: Exception) {}

        _uiState.update { it.copy(kernelRebootSeconds = 5) }

        kernelRebootJob = viewModelScope.launch(Dispatchers.IO) {

            try {

                for (s in 5 downTo 1) {

                    _uiState.update { it.copy(kernelRebootSeconds = s) }

                    delay(1000)

                }

                _uiState.update { it.copy(kernelRebootSeconds = null) }

                doKernelReboot()

            } catch (_: Exception) {

            }

        }

    }

    private suspend fun doKernelReboot() {

        val manager = usbManager

        val device = kernelAdbDevice()

        if (manager == null || device == null) {

            kernelLog(tr("[!] Reboot skipped: the phone is not connected. Reboot manually.", "[!] Reboot skip: phone connected nahi hai. Manually reboot karo."))

            return

        }

        kernelLog("[i] Rebooting target phone…")

        val ok = KernelBackupRestoreEngine.rebootTarget(manager, device, ::kernelLog)

        if (!ok) kernelLog(tr("[!] Reboot command sent. If the phone does not restart, do it manually.", "[!] Reboot command bhej diya. Agar phone restart na ho to manually karo."))

    }

    /**

     * Opens the console, resolves the target, gates on root and then runs [work].

     * If the phone is not rooted, shows the root prompt instead of running.

     */

    private fun launchKernelAction(

        intro: List<String>,

        work: suspend (UsbManager, UsbDevice) -> Unit

    ) {

        if (_uiState.value.kernelBusy) return

        try { kernelRebootJob?.cancel() } catch (_: Exception) {}

        kernelRebootJob = null

        try { kernelJob?.cancel() } catch (_: Exception) {}

        _uiState.update {

            it.copy(

                showKernelFlasher = true,

                kernelLogs = intro,

                kernelBusy = true,

                kernelRebootSeconds = null,

                showKernelRootPrompt = false

            )

        }

        kernelJob = viewModelScope.launch(Dispatchers.IO) {

            try {

                val manager = usbManager

                val device = kernelAdbDevice()

                if (manager == null || device == null) {

                    kernelLog(tr("[ERROR] No target phone connected over USB.", "[ERROR] Koi target phone USB se connected nahi hai."))

                    return@launch

                }

                if (!ensureUsbPermission(device)) {

                    kernelLog(tr("[!] USB permission not granted — tap 'Allow' and try again.", "[!] USB permission nahi mili — 'Allow' dabao aur dobara try karo."))

                    return@launch

                }

                kernelLog("[i] Root check…")

                when (KernelBackupRestoreEngine.checkRoot(manager, device, ::kernelLog)) {

                    KernelBackupRestoreEngine.RootCheck.ROOTED -> work(manager, device)

                    KernelBackupRestoreEngine.RootCheck.NOT_ROOTED -> {

                        kernelLog(tr("[!] The target is not rooted (KernelSU/Magisk required).", "[!] Target rooted nahi hai (KernelSU/Magisk chahiye)."))

                        _uiState.update { it.copy(showKernelRootPrompt = true) }

                    }

                    KernelBackupRestoreEngine.RootCheck.UNREACHABLE -> {

                        kernelLog(tr("[!] Root check failed — turn ON the phone's USB debugging, reseat the cable, and try again.", "[!] Root check nahi ho paaya — phone ka USB debugging ON karo, cable theek se lagao, aur dobara try karo."))

                    }

                }

            } catch (e: Exception) {

                kernelLog("[ERROR] ${e.message}")

            } finally {

                _uiState.update { it.copy(kernelBusy = false) }

            }

        }

    }

    private fun kernelAdbDevice(): UsbDevice? {

        val fresh = rootCandidateDevices()

        val current = currentConnectedDevice

        return when {

            current != null -> fresh.firstOrNull { it.deviceId == current.deviceId } ?: current

            else -> fresh.firstOrNull()

        }

    }

    // The in-app stock ROM downloader lived here. It was removed in round 14:

    // ROM fetching now happens on the website (romflasher.in/downloader.html),

    // which is the only place that can keep a device index current, and the app

    // went back to doing what it is built for - flashing a phone over USB.

    // ------------------------------------------------------------- internals

    private fun rootLog(message: String) {

        _uiState.update { st -> st.copy(rootLogs = (st.rootLogs + message).takeLast(600)) }

    }

    private fun rootStep(label: String) {

        _uiState.update { it.copy(rootStep = label) }

        rootLog("")

        rootLog("• $label")

    }

    private suspend fun rootAsk(question: String): Boolean {

        rootLog("? $question")

        _uiState.update { it.copy(rootPrompt = question) }

        val deferred = CompletableDeferred<Boolean>()

        rootPromptDeferred = deferred

        return try {

            deferred.await()

        } finally {

            if (rootPromptDeferred === deferred) rootPromptDeferred = null

            _uiState.update { it.copy(rootPrompt = null) }

        }

    }

    private suspend fun rootChoose(question: String, choices: List<String>): Int? {

        rootLog("? $question")

        _uiState.update { it.copy(rootPrompt = question, rootChoices = choices) }

        val deferred = CompletableDeferred<Int?>()

        rootChoiceDeferred = deferred

        return try {

            deferred.await()

        } finally {

            if (rootChoiceDeferred === deferred) rootChoiceDeferred = null

            _uiState.update { it.copy(rootPrompt = null, rootChoices = emptyList()) }

        }

    }

    private suspend fun rootAwaitFile(request: RootFileRequest, prompt: String): File? {

        rootLog("> $prompt")

        _uiState.update { it.copy(rootPrompt = prompt, rootFileRequest = request) }

        val deferred = CompletableDeferred<File?>()

        rootFileDeferred = deferred

        return try {

            deferred.await()

        } finally {

            if (rootFileDeferred === deferred) rootFileDeferred = null

            _uiState.update { it.copy(rootPrompt = null, rootFileRequest = null) }

        }

    }

    private fun startRootFlow(kind: RootKind) {

        rootJob?.cancel()

        _uiState.update { it.copy(rootBusy = true, rootFinished = false) }

        rootJob = viewModelScope.launch(Dispatchers.IO) {

            try {

                runRootSteps(kind)

            } catch (e: kotlinx.coroutines.CancellationException) {

                throw e

            } catch (e: Exception) {

                rootLog("[ERROR] ${e.message ?: e.javaClass.simpleName}")

            } finally {

                _uiState.update { it.copy(rootBusy = false, rootStep = "") }

            }

        }

    }

        private suspend fun runRootSteps(kind: RootKind) {

        rootLog("=============================================")

        rootLog("⚡ ROM FLASHER 1-CLICK AUTO ROOT ENGINE")

        rootLog("Target Root Solution: ${kind.label}")

        rootLog("=============================================")

        // STEP 1: obtain root APK (download for KSU/Magisk, user-picked for SELF)

        rootStep("Step 1/5 · Obtaining Root Package (${kind.label})")

        var apkFile: File? = if (kind == RootKind.SELF) {

            rootLog(tr("Select your own root app APK (SukiSU Ultra / APatch / KernelSU fork …).", "Apna root app ka APK select karo (SukiSU Ultra / APatch / KernelSU fork …)."))

            rootAwaitFile(RootFileRequest.ROOT_APK, tr("select your own root app APK", "apna root app ka APK select karo"))

        } else {

            rootLog(tr("Downloading latest ${kind.label} APK from GitHub…", "GitHub se latest ${kind.label} APK download kar rahe hain…"))

            val downloaded = try {

                RootHelper.downloadLatestApk(kind, rootDownloadDir()) { msg -> rootLog(msg) }

            } catch (e: Exception) {

                rootLog("[ERROR] ${e.message}")

                null

            }

            if (downloaded != null) {

                downloaded

            } else {

                rootLog(tr("[!] Auto-download failed. Select the APK manually.", "[!] Auto-download fail hua. APK manually select karein."))

                rootAwaitFile(RootFileRequest.ROOT_APK, tr("select the APK file", "APK file select karo"))

            }

        }

        if (apkFile == null) {

            rootLog(tr("[STOP] Cannot continue without the root APK.", "[STOP] Root APK ke bina aage nahi badh sakte."))

            return

        }

        rootLog("[OK] Root package ready: ${apkFile.name} (${apkFile.length() / 1048576} MB)")

        // STEP 2: install root manager on target phone

        rootStep("Step 2/5 · Installing Root Manager on target phone")

        rootLog(tr("Installing ${apkFile.name} on target phone…", "Target phone par ${apkFile.name} install kar rahe hain…"))

        val installed = rootAdbInstall(apkFile)

        if (installed) {

            rootLog("[OK] ${kind.label} Manager app installed.")

        } else {

            rootLog("[i] Shell install restricted - sending APK to target Downloads…")

            rootPushToDownloads(apkFile, apkFile.name)

        }

        // STEP 3: obtain stock init_boot.img (or boot.img)

        rootStep("Step 3/5 · Sourcing stock init_boot / boot image")

        val initBoot = rootObtainInitBoot()

        if (initBoot == null) {

            rootLog(tr("[STOP] Rooting is not possible without init_boot.img.", "[STOP] init_boot.img ke bina rooting nahi ho sakti."))

            return

        }

        rootLog("[OK] Stock image sourced: ${initBoot.name} (${initBoot.length() / 1024} KB)")

        val stockForPatch = rootTrimStockForPatching(initBoot)

        // STEP 4: 🪄 1-Click In-App Auto-Patching (ZERO USER INTERACTION!)

        rootStep("Step 4/5 · 🪄 In-App Auto Patching with ${kind.label} Engine")

        rootLog("🪄 Starting automated in-app patcher engine…")

        rootLog("→ Processing image: ${stockForPatch.name}")

        var patchedFile: File? = rootExecuteInAppPatch(kind, stockForPatch, apkFile)

        if (patchedFile == null || !patchedFile.exists() || patchedFile.length() <= 0L) {

            rootLog(tr("[!] Automated direct patch fallback to manual selection.", "[!] Automated direct patch nahi hua - manual selection fallback."))

            patchedFile = rootAwaitFile(RootFileRequest.PATCHED_IMG, tr("select patched .img", "patched .img select karo"))

        }

        val finalPatched = patchedFile

        if (finalPatched == null || !finalPatched.exists() || finalPatched.length() <= 0L) {

            rootLog(tr("[STOP] Cannot flash without the patched image.", "[STOP] Patched image ke bina flash nahi ho sakta."))

            return

        }

        rootLog("[OK] Patched image ready: ${finalPatched.name} (${finalPatched.length() / 1024} KB)")

        // STEP 5: reboot to fastboot & flash

        rootStep("Step 5/5 · Fastboot Flashing & Auto-Reboot")

        rootLog("→ adb reboot bootloader")

        rootAdbReboot("bootloader")

        rootLog("→ Waiting for Fastboot mode connection (90s)…")

        var inFastboot = waitForDevice(DeviceMode.FASTBOOT, 90000)

        if (!inFastboot) {

            rootLog(tr("[ACTION] Fastboot not detected. Put the target phone into bootloader.", "[ACTION] Fastboot detect nahi hua. Target phone ko bootloader me daalo."))

            inFastboot = rootAsk(tr("Is Fastboot ready? (YES = flash)", "Fastboot ready hai? (YES = flash karo)"))

        }

        if (!inFastboot) {

            rootLog(tr("[STOP] Cannot flash without Fastboot.", "[STOP] Fastboot ke bina flash nahi ho sakta."))

            return

        }

        rootLog("[OK] Fastboot detected.")

        val partitionName = if (stockForPatch.name.contains("boot", ignoreCase = true) && !stockForPatch.name.contains("init_boot", ignoreCase = true)) "boot" else "init_boot"

        val partitions = rootFlashTargets(partitionName)

        rootLog("Target partitions: ${partitions.joinToString(", ")}")

        var allOk = false

        for (partition in partitions) {

            rootLog("[i] $partition → flashing ${finalPatched.name} (${finalPatched.length()} B)")

            rootLog("→ fastboot flash $partition")

            val ok = rootFastbootFlash(partition, rootFitImageToPartition(finalPatched, partition)) { msg -> rootLog(msg) }

            rootLog(if (ok) "[OK] $partition flashed successfully." else "[ERROR] $partition flash failed.")

            if (ok) { allOk = true }

        }

        if (!allOk) {

            rootLog("→ fastboot flash $partitionName")

            allOk = rootFastbootFlash(partitionName, finalPatched) { msg -> rootLog(msg) }

        }

        rootLog("=============================================")

        if (allOk) {

            rootLog("→ fastboot reboot")

            rootFastbootCommand("reboot")

            rootLog("🎉 CONGRATULATIONS! The device was rooted successfully (${kind.label}).")

            rootLog(tr("Device is now rebooting into system as fully rooted!", "Phone ab restart hokar fully rooted boot hoga!"))

        } else {

            rootLog(tr("[ERROR] Flashing could not be verified. Try again in Fastboot mode.", "[ERROR] Flashing verify nahi hui. Fastboot mode me dobara try karein."))

        }

        rootLog("=============================================")

        _uiState.update { it.copy(rootFinished = true) }

    }

    /**

     * Extracts Magisk/KernelSU patcher binaries and runs the official patcher

     * over ADB inside /data/local/tmp/ on the target device with zero user interaction.

     */

            private suspend fun rootExecuteInAppPatch(
        kind: RootKind,
        stockBoot: File,
        apkFile: File,
        log: (String) -> Unit = { rootLog(it) }
    ): File? {
        log("→ Extracting patcher binaries from ${apkFile.name}…")
        rootLog("→ Extracting patcher binaries from ${apkFile.name}…")
        val patchDir = File(appContext?.cacheDir, "rf_patch_tools")
        patchDir.deleteRecursively()
        patchDir.mkdirs()

        val remoteDir = "/data/local/tmp/rf_patch"
        var adbReady = false
        for (i in 1..8) {
            val res = rootAdbShell("rm -rf $remoteDir && mkdir -p $remoteDir && echo RF_READY")
            if (res?.contains("RF_READY") == true) {
                adbReady = true
                break
            }
            delay(1500)
        }
        if (!adbReady) {
            rootAdbShell("mkdir -p $remoteDir")
        }

        var detectedEngine = "MAGISK"
        try {
            java.util.zip.ZipFile(apkFile).use { zip ->
                val hasKsud = zip.entries().asSequence().any { it.name.endsWith("libksud.so") }
                val hasKptools = zip.entries().asSequence().any { it.name.endsWith("libkptools.so") }
                val hasBootPatch = zip.entries().asSequence().any { it.name.endsWith("boot_patch.sh") }

                detectedEngine = when (kind) {
                    RootKind.APATCH -> "APATCH"
                    RootKind.SUKISU -> "SUKISU"
                    RootKind.KERNELSU -> "KSU"
                    RootKind.MAGISK -> "MAGISK"
                    else -> when {
                        hasKptools -> "APATCH"
                        hasKsud -> "KSU"
                        hasBootPatch -> "MAGISK"
                        else -> "MAGISK"
                    }
                }

                log("[i] Detected Root Engine: $detectedEngine (for ${apkFile.name})")
                rootLog("[i] Detected Root Engine: $detectedEngine (for ${apkFile.name})")

                if (detectedEngine == "KSU" || detectedEngine == "SUKISU") {
                    val ksudEntry = zip.getEntry("lib/arm64-v8a/libksud.so")
                        ?: zip.entries().asSequence().firstOrNull {
                            it.name.endsWith("libksud.so") || (it.name.startsWith("lib/arm64-v8a/") && it.size > 2000000L)
                        }
                    if (ksudEntry != null) {
                        val ksudFile = File(patchDir, "ksud")
                        zip.getInputStream(ksudEntry).use { input ->
                            ksudFile.outputStream().use { out -> input.copyTo(out) }
                        }
                        log("→ Pushing ksud binary to target…")
                        rootLog("→ Pushing ksud binary to target…")
                        rootAdbPush(ksudFile, "$remoteDir/ksud")
                    }

                    // Push magiskboot if available so we can auto-restore if stockBoot was previously Magisk-patched
                    val magiskApk = rootDownloadDir().listFiles()?.firstOrNull { it.name.startsWith("Magisk") && it.name.endsWith(".apk") }
                    if (magiskApk != null) {
                        try {
                            java.util.zip.ZipFile(magiskApk).use { mZip ->
                                val mbEntry = mZip.getEntry("lib/arm64-v8a/libmagiskboot.so")
                                    ?: mZip.entries().asSequence().firstOrNull { it.name.endsWith("libmagiskboot.so") }
                                if (mbEntry != null) {
                                    val mbFile = File(patchDir, "magiskboot")
                                    mZip.getInputStream(mbEntry).use { input ->
                                        mbFile.outputStream().use { out -> input.copyTo(out) }
                                    }
                                    rootAdbPush(mbFile, "$remoteDir/magiskboot")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } else if (detectedEngine == "APATCH") {
                    val kptoolsEntry = zip.getEntry("lib/arm64-v8a/libkptools.so")
                        ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("libkptools.so") || it.name.endsWith("kptools") }
                    if (kptoolsEntry != null) {
                        val kptoolsFile = File(patchDir, "kptools")
                        zip.getInputStream(kptoolsEntry).use { input ->
                            kptoolsFile.outputStream().use { out -> input.copyTo(out) }
                        }
                        log("→ Pushing kptools binary to target…")
                        rootLog("→ Pushing kptools binary to target…")
                        rootAdbPush(kptoolsFile, "$remoteDir/kptools")
                    }
                    val kpimgEntry = zip.entries().asSequence().firstOrNull { it.name.endsWith("kpimg") }
                    if (kpimgEntry != null) {
                        val kpimgFile = File(patchDir, "kpimg")
                        zip.getInputStream(kpimgEntry).use { input ->
                            kpimgFile.outputStream().use { out -> input.copyTo(out) }
                        }
                        log("→ Pushing kpimg to target…")
                        rootLog("→ Pushing kpimg to target…")
                        rootAdbPush(kpimgFile, "$remoteDir/kpimg")
                    }
                    val bpEntry = zip.getEntry("assets/boot_patch.sh")
                    if (bpEntry != null) {
                        val bpFile = File(patchDir, "boot_patch.sh")
                        zip.getInputStream(bpEntry).use { input ->
                            bpFile.outputStream().use { out -> input.copyTo(out) }
                        }
                        rootAdbPush(bpFile, "$remoteDir/boot_patch.sh")
                    }
                    val utilEntry = zip.getEntry("assets/util_functions.sh")
                    if (utilEntry != null) {
                        val utilFile = File(patchDir, "util_functions.sh")
                        zip.getInputStream(utilEntry).use { input ->
                            utilFile.outputStream().use { out -> input.copyTo(out) }
                        }
                        rootAdbPush(utilFile, "$remoteDir/util_functions.sh")
                    }
                } else {
                    val entriesToExtract = mapOf(
                        "assets/boot_patch.sh" to "boot_patch.sh",
                        "assets/util_functions.sh" to "util_functions.sh",
                        "lib/arm64-v8a/libmagiskboot.so" to "magiskboot",
                        "lib/arm64-v8a/libmagiskinit.so" to "magiskinit",
                        "lib/arm64-v8a/libmagisk.so" to "magisk",
                        "lib/arm64-v8a/libinit-ld.so" to "init-ld",
                        "lib/arm64-v8a/libbusybox.so" to "busybox",
                        "lib/arm64-v8a/libmagiskpolicy.so" to "magiskpolicy",
                        "lib/arm64-v8a/libbootctl.so" to "bootctl"
                    )
                    for ((zipPath, targetName) in entriesToExtract) {
                        val entry = zip.getEntry(zipPath) ?: continue
                        val outFile = File(patchDir, targetName)
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { out -> input.copyTo(out) }
                        }
                        rootAdbPush(outFile, "$remoteDir/$targetName")
                    }
                }
            }
        } catch (e: Exception) {
            log("[ERROR] Failed to extract patch tools from APK: ${e.message}")
            rootLog("[ERROR] Failed to extract patch tools from APK: ${e.message}")
        }

        log("→ Pushing stock ${stockBoot.name} to target patcher…")
        rootLog("→ Pushing stock ${stockBoot.name} to target patcher…")
        var pushed = rootAdbPush(stockBoot, "$remoteDir/stock_boot.img")
        if (!pushed) {
            delay(1500)
            pushed = rootAdbPush(stockBoot, "$remoteDir/stock_boot.img")
        }
        if (!pushed) {
            log("[ERROR] Failed to push stock boot image to patcher.")
            rootLog("[ERROR] Failed to push stock boot image to patcher.")
            return null
        }

        rootAdbShell("chmod -R 755 $remoteDir")

        log("→ Running automated patch engine on target ($detectedEngine)…")
        rootLog("→ Running automated patch engine on target ($detectedEngine)…")
        if (detectedEngine == "KSU" || detectedEngine == "SUKISU") {
            var patchOut = rootAdbShell("cd $remoteDir && ./ksud boot-patch -b stock_boot.img -o . --out-name new-boot.img --allow-shell")
            patchOut?.lines()?.forEach { 
                log("[KSU] $it")
                rootLog("[KSU] $it") 
            }

            if (patchOut?.contains("Cannot work with Magisk patched image") == true) {
                log("[!] Image was Magisk-patched. Auto-restoring clean stock ramdisk…")
                rootLog(tr("[!] Image was Magisk-patched. Auto-restoring clean stock ramdisk…", "[!] Image Magisk se patched tha. Clean stock ramdisk auto-restore kar rahe hain…"))
                rootAdbShell("cd $remoteDir && ./magiskboot unpack stock_boot.img && ./magiskboot cpio ramdisk.cpio restore && ./magiskboot repack stock_boot.img clean_stock.img && mv -f clean_stock.img stock_boot.img && ./magiskboot cleanup")
                patchOut = rootAdbShell("cd $remoteDir && ./ksud boot-patch -b stock_boot.img")
                patchOut?.lines()?.forEach { 
                    log("[KSU] $it")
                    rootLog("[KSU] $it") 
                }
            }
        } else if (detectedEngine == "APATCH") {
            val patchCmd = "cd $remoteDir && if [ -f boot_patch.sh ]; then /system/bin/sh boot_patch.sh romflasher stock_boot.img; else ./kptools unpack stock_boot.img && mv -f kernel kernel.ori 2>/dev/null && ./kptools -p -i kernel.ori -S romflasher -k kpimg -o kernel && ./kptools repack stock_boot.img; fi"
            val patchOut = rootAdbShell(patchCmd)
            patchOut?.lines()?.forEach { 
                log("[$detectedEngine] $it")
                rootLog("[$detectedEngine] $it") 
            }
        } else {
            val patchOut = rootAdbShell("cd $remoteDir && cp -f stock_boot.img input.img && KEEPVERITY=true KEEPFORCEENCRYPT=true PATCHVBMETAFLAG=false /system/bin/sh boot_patch.sh input.img")
            patchOut?.lines()?.forEach { 
                log("[MAGISK] $it")
                rootLog("[MAGISK] $it") 
            }
            val hasFatalError = patchOut?.contains("! Unable to patch ramdisk") == true ||
                patchOut?.contains("! Unable to repack boot image") == true ||
                patchOut?.contains("! Boot image patched by unsupported") == true ||
                patchOut?.contains("! Please restore back to stock") == true ||
                patchOut?.contains("! Error") == true
            if (hasFatalError) {
                log("[ERROR] Magisk reported fatal error during patching. Aborting flash to prevent bootloop.")
                rootLog("[ERROR] Magisk reported fatal error during patching. Aborting flash to prevent bootloop.")
                return null
            }
        }

        val filesList = rootAdbShell("ls $remoteDir/new-boot.img $remoteDir/*patched*.img $remoteDir/kernelsu_patched*.img 2>/dev/null")?.trim().orEmpty()
        val remotePatchedFile = filesList.lines().firstOrNull { it.isNotBlank() }?.trim()

        if (remotePatchedFile.isNullOrBlank()) {
            log("[ERROR] Patched image was not produced by the patcher.")
            rootLog("[ERROR] Patched image was not produced by the patcher.")
            return null
        }

        log("[OK] Patched image detected on target: $remotePatchedFile")
        rootLog("[OK] Patched image detected on target: $remotePatchedFile")
        val localPatched = File(rootDownloadDir(), "patched_${stockBoot.name}")
        if (localPatched.exists()) localPatched.delete()

        log("→ Pulling patched image to host…")
        rootLog("→ Pulling patched image to host…")
        val pulled = rootAdbPull(remotePatchedFile, localPatched)
        if (!pulled || !localPatched.exists() || localPatched.length() <= 0L) {
            val bytes = rootAdbExecOut("cat $remotePatchedFile")
            if (bytes != null && bytes.isNotEmpty()) {
                localPatched.writeBytes(bytes)
            }
        }

        rootAdbShell("rm -rf $remoteDir")

        if (localPatched.exists() && localPatched.length() > 0L) {
            log("[OK] Patched image ready on host (${localPatched.length() / 1024} KB).")
            rootLog("[OK] Patched image ready on host (${localPatched.length() / 1024} KB).")
            return localPatched
        }
        return null
    }

private fun rootLogPatchInstructions(kind: RootKind) {

        rootLog("=============================================")

        when (kind) {

            RootKind.KERNELSU -> {

                rootLog(tr("Open KernelSU on the target phone:", "Target phone par KernelSU kholo:"))

                rootLog(tr("1) Tap 'Not installed' / the key icon.", "1) 'Not installed' / key icon par tap karo."))

                rootLog("2) Install → 'Select and Patch a File'.")

                rootLog(tr("3) Downloads → select init_boot.img.", "3) Downloads → init_boot.img select karo."))

                rootLog(tr("4) Tap the top-right arrow (Next).", "4) Top-right arrow (Next) tap karo."))

                rootLog("5) Output: Downloads/kernelsu_patched_*.img")

            }

            RootKind.MAGISK -> {

                rootLog(tr("Open Magisk on the target phone:", "Target phone par Magisk kholo:"))

                rootLog(tr("1) Tap 'Install' next to Magisk at the top.", "1) Top me Magisk 'Install' par tap karo."))

                rootLog("2) 'Select and Patch a File'.")

                rootLog(tr("3) Downloads → select init_boot.img.", "3) Downloads → init_boot.img select karo."))

                rootLog(tr("4) Tap LET'S GO.", "4) LET'S GO tap karo."))

                rootLog("5) Output: Downloads/magisk_patched-*.img")

            }

            RootKind.APATCH -> {
                rootLog(tr("Open APatch on the target phone:", "Target phone par APatch kholo:"))
                rootLog(tr("Enter SuperKey: romflasher", "SuperKey daalo: romflasher"))
                rootLog(tr("APatch is now active!", "APatch ab active hai!"))
            }
            RootKind.SUKISU -> {
                rootLog(tr("Open SukiSU Ultra on the target phone (com.sukisu.ultra):", "Target phone par SukiSU Ultra kholo (com.sukisu.ultra):"))
                rootLog(tr("SukiSU Ultra with SUSFS & KernelPatch is now active!", "SukiSU Ultra with SUSFS & KernelPatch ab active hai!"))
            }
            RootKind.SELF -> {

                rootLog(tr("Open your root app on the target phone (the one just installed):", "Target phone par apna root app kholo (jo abhi install kiya):"))

                rootLog(tr("1) In the app, tap 'Install' / the key icon.", "1) App me 'Install' / key icon par tap karo."))

                rootLog(tr("2) Tap 'Select and Patch a File'.", "2) 'Select and Patch a File' chuno."))

                rootLog(tr("3) Downloads → select init_boot.img.", "3) Downloads → init_boot.img select karo."))

                rootLog(tr("4) Tap Next / LET'S GO.", "4) Aage badho (Next / LET'S GO) tap karo."))

                rootLog("5) Output: Downloads/*_patched*.img")

            }

        }

        rootLog("=============================================")

    }

    /**

     * Launches the root app (KernelSU / Magisk) on the target so the user only has to tap

     * "Install → Select and Patch a File → init_boot.img". KernelSU ships under a few package

     * names across its forks, so we probe the installed list and open whichever is present.

     */

    private fun rootOpenRootApp(kind: RootKind) {

        // KernelSU ships under a few package names across its forks, so match them with a single

        // `pm list packages | grep` (one ADB round-trip) and open whichever is installed.

        val pattern = when (kind) {

            RootKind.KERNELSU -> "com.rifsxd.ksunext|me.weishu.kernelsu"

            RootKind.MAGISK -> "com.topjohnwu.magisk"
            RootKind.APATCH -> "me.bmax.apatch"
            RootKind.SUKISU -> "com.sukisu.ultra" 

            RootKind.SELF -> rootCustomPackage

                ?.takeIf { it.isNotBlank() }

                ?.let { Regex.escape(it) }

                .orEmpty()

        }

        if (pattern.isEmpty()) {

            rootLog(tr("[i] Root app package not detected - open it manually on the target.", "[i] Root app package detect nahi hua - target par manually kholo."))

            return

        }

        val pkg = rootAdbShell("pm list packages | grep -E '$pattern' | head -n 1")

            ?.trim()?.removePrefix("package:")?.trim()

            .orEmpty()

        if (pkg.isEmpty() || !pkg.contains('.')) {

            rootLog(tr("[i] ${kind.label} app not detected on the target - open it manually.", "[i] ${kind.label} app target par detect nahi hua - target par manually kholo."))

            return

        }

        rootLog(tr("→ Opening the ${kind.label} app on the target…", "→ ${kind.label} app target par khol rahe hain…"))

        // Wake the screen, then launch the launcher activity in the same round-trip.

        val monkey = rootAdbShell("input keyevent KEYCODE_WAKEUP; monkey -p $pkg -c android.intent.category.LAUNCHER 1")

        if (monkey != null &&

            !monkey.contains("No activities found", ignoreCase = true) &&

            !monkey.contains("aborted", ignoreCase = true)

        ) {

            rootLog(tr("[OK] ${kind.label} opened - tap 'Install'.", "[OK] ${kind.label} khul gaya - 'Install' par tap karo."))

            return

        }

        // Fallback: resolve the launcher activity explicitly and start it.

        val component = rootAdbShell("cmd package resolve-activity --brief $pkg | tail -n 1")?.trim()

        if (!component.isNullOrBlank() && component.contains("/")) {

            rootAdbShell("am start -n $component")

            rootLog(tr("[OK] ${kind.label} opened - tap 'Install'.", "[OK] ${kind.label} khul gaya - 'Install' par tap karo."))

        } else {

            rootLog(tr("[i] ${kind.label} auto-open failed - open the app manually on the target.", "[i] ${kind.label} auto-open fail - target par manually app kholo."))

        }

    }

    /** Reads the launcher package name from a locally stored APK (host-side PackageManager). */

    private fun rootPackageFromApk(apk: File): String? = try {

        appContext?.packageManager?.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName

    } catch (_: Exception) {

        null

    }

    private suspend fun rootObtainInitBoot(): File? {

        // ---- Identify the target so we can pick the right recovery route.

        rootLog(tr("Checking the target device…", "Target device check kar rahe hain…"))

        val codename = rootAdbShell("getprop ro.product.device")?.trim()?.lowercase()

        val model = rootAdbShell("getprop ro.product.model")?.trim()

        if (codename.isNullOrBlank()) {

            rootLog(tr("[i] Target codename not detected.", "[i] Target codename detect nahi hua."))

        } else {

            rootLog("[i] Target device: ${model?.takeIf { it.isNotBlank() } ?: "unknown"} (codename: $codename)")

        }

        // ---- Recovery-assisted extraction (we are here to root, so always

        //      pull a clean stock init_boot via a temporarily booted recovery).

        val recovered: ByteArray? = if (codename == "rodin") {

            rootInitBootViaRodin()

        } else {

            val recovery = rootRecoveryForGeneric(codename, model)

            if (recovery != null) {

                val idx = rootChoose(

                    tr("Which partition should recovery be flashed to? (based on the device structure)", "Recovery kis partition me flash karein? (device structure ke hisaab se)"),

                    listOf("vendor_boot (Xiaomi / MTK Android 13+)", "recovery", "boot")

                ) ?: 0

                val partition = listOf("vendor_boot", "recovery", "boot")[idx]

                rootBootRecoveryAndExtract(recovery, partition)

            } else null

        }

        if (recovered != null) {

            saveRootBytes("init_boot_stock.img", recovered)?.let { f ->

                rootLog("[OK] Recovery se init_boot extract (${recovered.size / 1024} KB).")

                return f

            }

        } else {

            rootLog(tr("[!] init_boot was not extracted from recovery.", "[!] Recovery se init_boot extract nahi hua."))

        }

        // ---- Last resort: user picks the stock image manually.

        rootLog(tr("[i] Select a stock init_boot.img from storage (from your ROM/payload).", "[i] Aap storage se stock init_boot.img select karo (ROM/payload se)."))

        val picked = rootAwaitFile(RootFileRequest.INIT_BOOT_IMG, tr("select init_boot.img", "init_boot.img select karo"))

        if (picked != null) rootLog("[OK] Selected: ${picked.name}")

        return picked

    }

    /**

     * POCO X7 Pro ("rodin"): ask which ROM family is installed, download the matching

     * OrangeFox recovery and FLASH it to `vendor_boot`, then boot recovery to read

     * init_boot right off the device. Tries the AVB-disabled build first, then AVB-enabled.

     */

    private suspend fun rootInitBootViaRodin(): ByteArray? {

        rootLog("=============================================")

        rootLog("[i] Detected: POCO X7 Pro (rodin).")

        rootLog(tr("[i] We will FLASH OrangeFox recovery into vendor_boot to extract init_boot.", "[i] OrangeFox recovery ko vendor_boot me FLASH karke init_boot nikalenge."))

        rootLog(tr("[i] Note: vendor_boot will be replaced — you can restore it later from a stock ROM.", "[i] Note: vendor_boot replace hoga — baad me stock ROM se wapas kar sakte ho."))

        val idx = rootChoose(

            "Which ROM is currently installed on your phone?",

            listOf(

                "HyperOS / Custom Modded ROM",

                "AOSP Based ROM",

                "Skip (I will provide init_boot myself)"

            )

        ) ?: return null

        if (idx != 0 && idx != 1) {

            rootLog("[i] Skipped recovery route.")

            return null

        }

        val variant = if (idx == 0) RecoveryVariant.HOS else RecoveryVariant.AOSP

        for (avb in listOf(AvbVariant.DISABLED, AvbVariant.ENABLED)) {

            rootLog(tr("→ Trying the ${variant.label} · ${avb.assetTag} recovery download…", "→ ${variant.label} · ${avb.assetTag} recovery download try kar rahe hain…"))

            val recovery = RootHelper.downloadRodinRecovery(variant, avb, rootDownloadDir()) { msg -> rootLog(msg) }

            if (recovery == null) continue

            val bytes = rootBootRecoveryAndExtract(recovery, "vendor_boot")

            if (bytes != null) return bytes

            rootLog(tr("[i] No init_boot found in ${avb.assetTag} - trying another variant.", "[i] ${avb.assetTag} se init_boot nahi mila - dusra variant try karenge."))

        }

        rootLog(tr("[!] The auto recovery route failed.", "[!] Auto recovery route fail hua."))

        if (rootAsk(tr("Do you have a recovery .img? (YES = select it)", "Kya tere paas koi recovery .img hai? (YES = select karo)"))) {

            val rec = rootAwaitFile(RootFileRequest.RECOVERY_IMG, tr("select the recovery .img", "recovery .img select karo"))

            if (rec != null) return rootBootRecoveryAndExtract(rec, "vendor_boot")

        }

        return null

    }

    /**

     * Any other device: report what it is, ask whether a TWRP/OrangeFox .img is

     * available, and let the user pick it. Without one we fall back to a manual

     * init_boot.img selection.

     */

    private suspend fun rootRecoveryForGeneric(codename: String?, model: String?): File? {

        rootLog("=============================================")

        rootLog("[i] Your phone: ${model?.takeIf { it.isNotBlank() } ?: "unknown"} (${codename?.takeIf { it.isNotBlank() } ?: "?"})")

        rootLog(tr("[i] OrangeFox auto-download is not available for this model.", "[i] Is model ke liye OrangeFox auto-download available nahi hai."))

        if (!rootAsk("Do you have TWRP / OrangeFox recovery (.img) for this phone?")) {

            rootLog(tr("[i] No recovery - you will have to select a stock init_boot.img manually.", "[i] Recovery nahi hai - stock init_boot.img manually select karni hogi."))

            return null

        }

        return rootAwaitFile(RootFileRequest.RECOVERY_IMG, tr("select the recovery .img", "recovery .img select karo"))

    }

    /**

     * Picks the partition names to attempt, most-likely first.

     *

     * Xiaomi/MTK bootloaders are inconsistent: some expose only a **non-suffixed** name

     * (e.g. `init_boot`, which the bootloader maps to the active slot — this is what PC

     * `fastboot flash init_boot …` uses) while others expose only **slot-suffixed** names

     * (e.g. rodin's `vendor_boot_a`, where plain `vendor_boot` answers "partition does not

     * exist"). `getvar partition-size` decides; unknown sizes fall back to the PC default

     * (non-suffixed name first, then the current slot, then the other slot).

     *

     * A name MTK reports with partition-size 0 is NOT real — flashing it fails with

     * "data size is larger than partition size" / "sparse image size span overflow".

     */

    private fun rootFlashTargets(base: String): List<String> {

        val name = base.trim().removeSuffix("_a").removeSuffix("_b")

        val slot = rootFastbootVarRetry("current-slot")?.trim()?.lowercase()?.takeIf { it == "a" || it == "b" }

        val baseReal = (rootPartitionSize(name) ?: 0L) > 0L

        val slotReal = slot?.let { (rootPartitionSize("${name}_$it") ?: 0L) > 0L } ?: false

        // LinkedHashSet keeps the most likely name first while de-duplicating.

        val out = LinkedHashSet<String>()

        // Whatever the bootloader confirms is real wins the first attempt.

        if (baseReal) out += name

        if (slotReal) out += "${name}_$slot"

        if (slot != null) {

            // A/B device: slot-suffixed names are what rodin actually exposes; the plain

            // name is the PC-fastboot fallback (some bootloaders resolve the slot for it).

            out += "${name}_$slot"

            out += name

            out += "${name}_${if (slot == "a") "b" else "a"}"

        } else {

            // Non-A/B (or current-slot unreadable): plain name first, then both suffixes.

            out += name

            out += "${name}_a"

            out += "${name}_b"

        }

        return out.toList()

    }

    /** `getvar` with a couple of retries — MTK occasionally drops a getvar reply mid-session. */

    private fun rootFastbootVarRetry(name: String, attempts: Int = 3): String? {

        repeat(attempts) {

            val v = rootFastbootVar(name)

            if (!v.isNullOrBlank()) return v

        }

        return null

    }

    /** Partition size in bytes from `getvar partition-size`, or null when unreported. */

    private fun rootPartitionSize(name: String): Long? {

        val raw = rootFastbootVarRetry("partition-size:$name")?.trim() ?: return null

        if (raw.isEmpty()) return null

        Regex("(?i)0x([0-9a-f]+)").find(raw)?.let { return it.groupValues[1].toLongOrNull(16) }

        // fastboot always reports sizes in hex (also without the 0x prefix).

        return Regex("^[0-9a-fA-F]+").find(raw)?.value?.toLongOrNull(16)

    }

    /**

     * Guarantees [image] fits [partition].

     *

     * A boot image rebuilt by Magisk/KernelSU can end up slightly larger than its partition.

     * Such an image normally carries a trailing 64-byte AVB footer that PC fastboot **skips**

     * ("Warning: skip copying init_boot image avb footer"); we reproduce that here. If there is

     * no AVB footer the image is simply truncated to the partition size (trailing bytes beyond

     * the partition are unusable anyway) so the bootloader accepts the download.

     */

    private fun rootFitImageToPartition(image: File, partition: String): File {

        val pSize = rootPartitionSize(partition) ?: 0L

        if (pSize <= 0L || image.length() <= pSize) return image

        val footerSize = readAvbFooterOriginalSize(image)

        val target = if (footerSize != null && footerSize in 1..pSize) footerSize else pSize

        return try {

            val dir = image.parentFile ?: rootDownloadDir()

            dir.mkdirs()

            val out = File(dir, "fit_$target-${image.name}")

            RandomAccessFile(image, "r").use { raf ->

                FileOutputStream(out).use { fos ->

                    val buf = ByteArray(512 * 1024)

                    var remaining = target

                    while (remaining > 0) {

                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())

                        if (n <= 0) break

                        fos.write(buf, 0, n)

                        remaining -= n

                    }

                }

            }

            val how = if (footerSize != null && footerSize in 1..pSize) "AVB footer strip" else "partition size tak trim"

            rootLog("[i] $partition: image ${image.length()} B > partition $pSize B ($how) → ${out.length()} B.")

            out

        } catch (_: Exception) {

            image

        }

    }

    /** Reads `original_image_size` from a trailing 64-byte AVB footer, or null when absent. */

    private fun readAvbFooterOriginalSize(image: File): Long? = try {

        if (image.length() < 64) null

        else RandomAccessFile(image, "r").use { raf ->

            raf.seek(image.length() - 64)

            val b = ByteArray(64)

            raf.readFully(b)

            if (b[0] != 'A'.code.toByte() || b[1] != 'V'.code.toByte() ||

                b[2] != 'B'.code.toByte() || b[3] != 'f'.code.toByte()

            ) null

            else {

                var size = 0L

                for (i in 0 until 8) size = size or ((b[12 + i].toLong() and 0xFF) shl (8 * i))

                size

            }

        }

    } catch (_: Exception) {

        null

    }

    /**

     * Strips a trailing AVB footer from a stock partition dump before the user patches it,

     * so the patcher (Magisk/KernelSU) starts from the real image and its output still fits

     * the partition. Returns the original file when there is no AVB footer to strip.

     */

    private fun rootTrimStockForPatching(image: File): File {

        val content = readAvbFooterOriginalSize(image) ?: return image

        if (content <= 0L || content >= image.length()) return image

        return try {

            val dir = image.parentFile ?: rootDownloadDir()

            dir.mkdirs()

            val out = File(dir, "stock_${image.name}")

            RandomAccessFile(image, "r").use { raf ->

                FileOutputStream(out).use { fos ->

                    val buf = ByteArray(512 * 1024)

                    var remaining = content

                    while (remaining > 0) {

                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())

                        if (n <= 0) break

                        fos.write(buf, 0, n)

                        remaining -= n

                    }

                }

            }

            rootLog("[i] Stock AVB footer strip: ${image.length()} B → ${out.length()} B (headroom for patching).")

            out

        } catch (_: Exception) {

            image

        }

    }

    /**

     * FLASHES [recovery] into the resolved recovery partition (`fastboot flash vendor_boot_a <img>`

     * on rodin) and then boots the target into recovery, waits for recovery ADB, reads init_boot

     * back and reboots the target to system.

     *

     * NOTE: unlike `fastboot boot`, this REPLACES the stock image on that partition — the

     * stock vendor_boot/recovery can be restored later from the stock ROM.

     */

    private suspend fun rootBootRecoveryAndExtract(recovery: File, partition: String = "vendor_boot", targetPartition: String = "init_boot"): ByteArray? {

        rootLog(tr("→ Sending the target to fastboot (adb reboot bootloader)…", "→ Target ko fastboot me bhej rahe hain (adb reboot bootloader)…"))

        rootAdbReboot("bootloader")

        rootLog("→ Waiting for fastboot interface (120s)…")

        if (!waitForDevice(DeviceMode.FASTBOOT, 120000)) {

            rootLog(tr("[!] Fastboot not detected.", "[!] Fastboot detect nahi hua."))

            if (!rootAsk(tr("Put the target into bootloader/fastboot, then tap YES.", "Target ko bootloader/fastboot me daal do, phir YES dabao."))) return null

            if (!waitForDevice(DeviceMode.FASTBOOT, 120000)) {

                rootLog(tr("[ERROR] Still no Fastboot.", "[ERROR] Fastboot abhi bhi nahi mila."))

                return null

            }

        }

        rootLog("[OK] Fastboot detected.")

        val partitionOrder = rootFlashTargets(partition)

        rootLog(tr("[i] FLASHing recovery (the stock image will be replaced).", "[i] Recovery ko FLASH kar rahe hain (stock image replace hoga)."))

        rootLog("[i] Partition candidates: ${partitionOrder.joinToString(", ")}")

        var flashed = false

        for (p in partitionOrder) {

            rootLog("[i] $p → image ${recovery.length()} B / partition ${rootPartitionSize(p)?.let { "$it B" } ?: "?"}")

            rootLog("→ fastboot flash $p ${recovery.name}")

            if (rootFastbootFlash(p, rootFitImageToPartition(recovery, p)) { msg -> rootLog(msg) }) { flashed = true; break }

            rootLog("[!] $p flash fail - agla candidate try.")

        }

        if (!flashed) {

            rootLog(tr("[!] Recovery flash failed - now trying 'fastboot boot' (temporary)...", "[!] Recovery flash fail hua - ab 'fastboot boot' (temporary) try kar rahe hain..."))

            if (!rootFastbootBoot(recovery) { msg -> rootLog(msg) }) {

                rootLog(tr("[!] 'fastboot boot' also failed (recovery did not boot).", "[!] fastboot boot bhi fail hua (recovery boot nahi hui)."))

                return null

            }

        } else {

            rootLog(tr("[OK] Recovery flashed. Now booting into recovery...", "[OK] Recovery flash ho gayi. Ab recovery me boot kar rahe hain..."))

        }

        rootLog(tr("→ Booting into recovery…", "→ Recovery me boot kar rahe hain…"))

        var bootedToRecovery = false

        for (cmd in listOf("reboot-recovery", "oem reboot-recovery")) {

            val rr = rootFastbootCommand(cmd)

            val txt = rr?.trim()?.uppercase() ?: ""

            if (rr != null && !txt.contains("FAIL") && !txt.contains("UNKNOWN")) {

                rootLog(tr("[OK] Sent '$cmd' - recovery is booting…", "[OK] '$cmd' bhej diya - recovery boot ho raha hai…"))

                bootedToRecovery = true

                break

            }

        }

        if (!bootedToRecovery) {

            rootLog(tr("[i] The bootloader does not support the 'reboot recovery' command - boot recovery manually.", "[i] Bootloader 'reboot recovery' command support nahi karta - recovery manually boot karni padegi."))

        }

        rootLog("→ Waiting for Recovery ADB (120s)…")

        var recoveryAdb = waitForDevice(DeviceMode.ADB, 120000)

        if (!recoveryAdb) {

            rootLog(tr("[!] Recovery ADB not detected.", "[!] Recovery ADB detect nahi hua."))

            rootLog(tr("[ACTION] Boot the phone into recovery (power+volume keys or bootloader menu → Recovery), then turn ADB ON in recovery.", "[ACTION] Phone ko recovery me boot karo (power+volume keys ya bootloader menu → Recovery), phir recovery me ADB ON karo."))

            if (rootAsk(tr("Turn ADB ON in recovery and tap YES?", "Recovery me ADB ON karke YES dabao?"))) {

                recoveryAdb = waitForDevice(DeviceMode.ADB, 120000)

            }

        }

        if (!recoveryAdb) {

            rootLog(tr("[!] No recovery ADB - reboot the target into system manually.", "[!] Recovery ADB nahi mila - target ko manually system me reboot karo."))

            waitForDevice(DeviceMode.ADB, 180000)

            return null

        }

        rootLog(tr("[OK] Recovery ADB interface detected. Extracting init_boot…", "[OK] Recovery ADB interface detected. init_boot extract kar rahe hain…"))

        // adbd needs several seconds after the recovery logo before it answers the ADB handshake,

        // so [rootExtractInitBootBytes] already retries the connection internally.  If it still

        // can't connect, ask the user to confirm recovery is fully booted (this also gives adbd

        // the time it needs) and try once more with a longer window.

        var bytes: ByteArray? = rootExtractPartitionBytes(targetPartition, connectAttempts = 8, connectDelayMs = 3000)

        if (bytes == null) {

            rootLog(tr("[ACTION] Recovery ADB is not connecting — adbd may not be ready yet.", "[ACTION] Recovery ADB connect nahi ho raha — adbd abhi ready nahi ho sakta."))

            rootLog(tr("         Wait until the target's recovery screen appears, then tap YES.", "         Target ka recovery screen dikhne ka wait karo, phir YES dabao."))

            if (rootAsk(tr("Has the target booted into recovery? (YES = read again)", "Target recovery me boot ho chuka hai? (YES = dobara read karo)"))) {

                if (waitForDevice(DeviceMode.ADB, 60000)) {

                    bytes = rootExtractInitBootBytes(connectAttempts = 15, connectDelayMs = 4000)

                } else {

                    rootLog(tr("[!] Still no recovery ADB.", "[!] Recovery ADB abhi bhi nahi mila."))

                }

            }

        }

        if (bytes == null) {

            rootLog(tr("[!] init_boot was not read — the [DEBUG] lines above explain the exact reason.", "[!] init_boot read nahi hua — upar ki [DEBUG] lines se exact reason pata chalega."))

        }

        rootLog(tr("→ Rebooting the target into system…", "→ Target ko system me reboot kar rahe hain…"))

        // Recovery adbd me `reboot:` service hamesha nahi hoti, isliye shell reboot.

        rootAdbShell("reboot")
        delay(4000)
        if (!waitForDevice(DeviceMode.ADB, 60000)) {

            rootAdbReboot("")

        }

        waitForDevice(DeviceMode.ADB, 120000)

        return bytes

    }

    /**

     * Reads the current ROM's `init_boot` off the target while it sits in a root shell

     * (TWRP / OrangeFox recovery, or a Magisk-rooted system).

     *

     * This deliberately reuses [EfsBackupRestoreEngine]'s PROVEN partition reader instead of a

     * hand-rolled `dd`.  Recovery adbd is flaky: a single large `dd if=… bs=1M` over `exec:`

     * silently TRUNCATES (and some OrangeFox builds don't implement `exec:` at all), which is

     * exactly what made the old code report "init_boot extract nahi hua".  The engine detects

     * the real root mode, resolves the actual `/dev/block/…/by-name` node, then reads with

     * `exec-out dd` plus `base64`/chunked fallbacks and verifies the length (or md5) so a

     * partial read is rejected and retried rather than saved.

     *

     * The active slot is honoured: `init_boot<suffix>` is tried first so we never patch the

     * inactive slot's image.

     */

    
    
    private suspend fun rootExtractPartitionLiveDirect(targetPartition: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val idOut = rootAdbShell("id") ?: ""
                val isRoot = idOut.contains("uid=0")
                val suCmd = if (isRoot) "" else {
                    val suCheck = rootAdbShell("su -c id 2>/dev/null || su 0 id 2>/dev/null") ?: ""
                    if (suCheck.contains("uid=0")) "su -c " else null
                }

                val candidatePaths = listOf(
                    "/dev/block/by-name/$targetPartition",
                    "/dev/block/bootdevice/by-name/$targetPartition",
                    "/dev/block/by-name/${targetPartition}_a",
                    "/dev/block/bootdevice/by-name/${targetPartition}_a"
                )

                if (isRoot || suCmd != null) {
                    for (p in candidatePaths) {
                        val readCmd = if (!suCmd.isNullOrEmpty()) "${suCmd}\"dd if=$p bs=4096 count=16384\"" else "dd if=$p bs=4096 count=16384"
                        val bytes = rootAdbExecOut(readCmd)
                        if (bytes != null && rootImageLooksValid(bytes)) {
                            return@withContext bytes
                        }
                    }
                }

                for (p in candidatePaths) {
                    val bytes = rootAdbExecOut("dd if=$p bs=4096 count=16384 2>/dev/null || cat $p 2>/dev/null")
                    if (bytes != null && rootImageLooksValid(bytes)) {
                        return@withContext bytes
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun rootExtractInitBootBytes(
        connectAttempts: Int = 8,
        connectDelayMs: Long = 3000
    ): ByteArray? = rootExtractPartitionBytes("init_boot", connectAttempts, connectDelayMs)
private suspend fun rootExtractPartitionBytes(
        targetPartition: String = "init_boot",
        connectAttempts: Int = 8,
        connectDelayMs: Long = 3000
    ): ByteArray? {

        val session = rootOpenRootSession(connectAttempts, connectDelayMs) ?: run {

            rootLog(tr("[ERROR] Root ADB session did not open. Turn ADB ON in recovery (or allow 'su' in Magisk).", "[ERROR] Root ADB session nahi khula. Recovery me ADB ON karo (ya Magisk 'su' allow karo)."))

            return null

        }

        val adb = session.session

        return try {

            val slot = EfsBackupRestoreEngine

                .runAsRoot(adb, session.rootMode, "getprop ro.boot.slot_suffix")

                .trim()

            rootLog("[i] Slot suffix: '${slot.ifEmpty { "?" }}'")

            val candidates = LinkedHashSet<String>()
            if (slot.startsWith("_")) candidates += "$targetPartition$slot"
            candidates += targetPartition
            candidates += "${targetPartition}_a"
            candidates += "${targetPartition}_b"
            for (name in candidates) {
                rootLog("→ $targetPartition read try: $name")

                val bytes = EfsBackupRestoreEngine.readPartitionViaAdb(adb, name, session.rootMode) { msg -> rootLog(msg) }

                if (bytes != null && rootImageLooksValid(bytes)) {

                    rootLog(tr("[OK] $name read (${bytes.size / 1024} KB, verified).", "[OK] $name read ho gaya (${bytes.size / 1024} KB, verified)."))

                    return bytes

                }

                if (bytes != null) rootLog(tr("[i] $name was read but the boot image is not valid (${bytes.size} B).", "[i] $name read mila par boot image valid nahi (${bytes.size} B)."))

            }

            rootLog(tr("[!] No readable init_boot partition found.", "[!] Koi readable init_boot partition nahi mila."))

            null

        } catch (e: Exception) {

            rootLog("[ERROR] init_boot read fail: ${e.message}")

            null

        } finally {

            try { adb.close() } catch (_: Exception) {}

        }

    }

    /**

     * Opens a root-capable ADB session, patiently retrying across the recovery adbd warm-up.

     *

     * This is the fix for "device did not answer CNXN (no ADB handshake)": right after

     * `reboot-recovery` the USB ADB **interface** enumerates several seconds before adbd actually

     * starts answering.  [waitForDevice] sees the interface and returns instantly, so a single

     * `open()` fails even though the phone is happily sitting in recovery.  We re-detect the

     * device and retry for up to [attempts] x [delayMs] before giving up.

     */

    private suspend fun rootOpenRootSession(

        attempts: Int = 8,

        delayMs: Long = 3000

    ): EfsBackupRestoreEngine.RootAdb? {

        val manager = usbManager ?: return null

        repeat(attempts) { i ->

            // Re-detect each pass: after a mode switch the target re-enumerates as a new UsbDevice.

            try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) {}

            for (dev in rootCandidateDevices()) {

                if (!ensureUsbPermission(dev)) continue

                val root = EfsBackupRestoreEngine.openRootAdbSession(manager, dev) { msg -> rootLog(msg) }

                if (root != null) return root

            }

            if (i < attempts - 1) {

                rootLog("[i] Waiting for Recovery ADB handshake… (${(i + 1) * delayMs / 1000}s)")

                delay(delayMs)

            }

        }

        return null

    }

    /**

     * Sanity check for a raw boot-partition dump. A GKI `init_boot`/`boot` image starts with the

     * 8-byte `ANDROID!` magic (MTK legacy images start with `ANDROID!` too). Anything that is

     * all-zero, tiny, or looks like shell/tool error text is rejected.

     */

    private fun rootImageLooksValid(bytes: ByteArray): Boolean {

        if (bytes.size < 256 * 1024) return false

        if (bytes.take(8).all { it == 0.toByte() }) return false

        val magic = String(bytes, 0, minOf(8, bytes.size), Charsets.US_ASCII)

        if (magic == "ANDROID!") return true

        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.US_ASCII).lowercase()

        val errorMarkers = listOf(

            "not found", "inaccessible", "permission denied", "no such file",

            "operation not permitted", "cannot open", "usage:", "read error",

            "unknown service", "unknown command"

        )

        if (errorMarkers.any { head.contains(it) }) return false

        rootLog(tr("[i] Boot magic '$magic' is not ANDROID! - accepting it based on size.", "[i] Boot magic '$magic' ANDROID! nahi hai - size ke basis par accept kar rahe hain."))

        return true

    }

    private fun rootPullPatched(): File? {

        rootLog(tr("→ Looking for the patched file in the target's Downloads…", "→ target ke Downloads me patched file dhoondh rahe hain…"))

        val listing = rootAdbShell("ls -t /sdcard/Download/")

            ?: rootAdbShell("ls -t /storage/emulated/0/Download/")

        if (listing == null) {

            rootLog(tr("[!] Could not list Downloads.", "[!] Downloads list nahi hui."))

            return null

        }

        val names = listing.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        val candidate = names.firstOrNull { it.contains("patched", ignoreCase = true) }

            ?: names.firstOrNull {

                it.contains("magisk", ignoreCase = true) || it.contains("kernel", ignoreCase = true)

            }

        if (candidate == null) {

            rootLog(tr("[!] No patched image found.", "[!] Koi patched image nahi mili."))

            return null

        }

        rootLog("→ Found: $candidate · copying…")

        val bytes = rootAdbExecOut("cat /sdcard/Download/$candidate")

        if (bytes == null || bytes.size < 100_000) {

            rootLog("[!] File copy fail.")

            return null

        }

        val saved = saveRootBytes(candidate, bytes)

        if (saved != null) rootLog("[OK] Host par save: ${saved.absolutePath}")

        return saved

    }

    // ------------------------------------------------------------- USB plumbing

    private fun rootDownloadDir(): File = RomFlasherStorage.rootFilesRoot()

    private fun saveRootBytes(name: String, bytes: ByteArray): File? {

        val dirs = mutableListOf(rootDownloadDir())

        appContext?.getExternalFilesDir(null)?.let { dirs.add(File(it, "root")) }

        appContext?.cacheDir?.let { dirs.add(it) }

        for (dir in dirs) {

            try {

                dir.mkdirs()

                val out = File(dir, name)

                FileOutputStream(out).write(bytes)

                return out

            } catch (_: Exception) {

                // try the next location

            }

        }

        return null

    }

    private fun rootCandidateDevices(): List<UsbDevice> {

        val manager = usbManager ?: return emptyList()

        val fresh = try { manager.deviceList.values.toList() } catch (_: Exception) { emptyList() }

        val current = currentConnectedDevice ?: return fresh

        // IMPORTANT: `ensureUsbPermission()` only appears AFTER the grant, but the UsbDevice

        // captured before it (e.g. by `waitForDevice`) can make `openDevice()` return null on

        // Android. Prefer the freshly-enumerated object with the same id; fall back to the

        // cached reference only if the device is no longer in the list.

        val freshMatch = fresh.firstOrNull { it.deviceId == current.deviceId }

        return if (freshMatch != null) {

            listOf(freshMatch) + fresh.filter { it.deviceId != current.deviceId }

        } else {

            listOf(current) + fresh

        }

    }

    private fun rootAdbShell(command: String): String? {

        val manager = usbManager ?: return null

        for (dev in rootCandidateDevices()) {

            if (!ensureUsbPermission(dev)) continue

            val proto = try { AdbSideloadProtocol.open(manager, dev) } catch (_: Exception) { null } ?: continue

            try {

                proto.executeAdbShellCommand(command)?.let { return it }

            } catch (_: Exception) {

            } finally {

                try { proto.close() } catch (_: Exception) {}

            }

        }

        return null

    }

    private fun rootAdbExecOut(command: String, maxBytes: Int = 64 * 1024 * 1024, idleTimeoutMs: Int = 90000): ByteArray? {

        val manager = usbManager ?: return null

        for (dev in rootCandidateDevices()) {

            if (!ensureUsbPermission(dev)) continue

            val proto = try { AdbSideloadProtocol.open(manager, dev) } catch (_: Exception) { null } ?: continue

            try {

                val out = proto.executeAdbExecOut(command, maxBytes, idleTimeoutMs)

                if (out != null && out.isNotEmpty()) return out

            } catch (_: Exception) {

            } finally {

                try { proto.close() } catch (_: Exception) {}

            }

        }

        return null

    }

    /**

     * Pushes [source] into the target's shared Download folder and returns the remote path

     * that can be read back with `cat`, or null.

     *

     * The device's `sync` service answers "couldn't create file: Operation not permitted"

     * when `/sdcard` is not mounted yet — which happens for the first seconds after a boot,

     * exactly when ADB is already up. To smooth both the Magisk and KernelSU routes this

     * waits for user storage, ensures the folder exists and retries a few fallbacks.

     */

    private suspend fun rootPushToDownloads(source: File, destName: String): String? {

        var mounted = false

        for (i in 0 until 20) {

            if (rootStorageReady()) { mounted = true; break }

            if (i == 0) rootLog("[i] Waiting for target storage to mount…")

            delay(2000)

        }

        if (!mounted) rootLog(tr("[i] Storage mount not confirmed - still trying.", "[i] Storage mount confirm nahi hua - phir bhi try kar rahe hain."))

        // Make sure the folders exist (some ROMs ship without /sdcard/Download).

        rootAdbShell("mkdir -p /sdcard/Download /storage/emulated/0/Download 2>/dev/null")

        val candidates = listOf(

            "/sdcard/Download/$destName",

            "/storage/emulated/0/Download/$destName",

            "/sdcard/$destName"

        )

        for ((idx, remote) in candidates.withIndex()) {

            repeat(2) {

                if (rootAdbPush(source, remote)) return remote

                delay(800)

            }

            if (idx == 0) rootLog(tr("[i] Push to /sdcard/Download failed - trying an alternative…", "[i] /sdcard/Download me push fail - alternative try kar rahe hain…"))

        }

        // Last resort: /data/local/tmp is always writable, then copy into Download via shell.

        val tmp = "/data/local/tmp/$destName"

        if (rootAdbPush(source, tmp)) {

            for (remote in listOf("/sdcard/Download/$destName", "/storage/emulated/0/Download/$destName")) {

                val out = rootAdbShell("cp -f $tmp $remote && echo COPIED")

                if (out != null && out.contains("COPIED")) return remote

            }

        }

        return null

    }

    /** True once the target's shared storage is actually mounted and its Download folder exists. */

    private fun rootStorageReady(): Boolean {

        val booted = rootAdbShell("getprop sys.boot_completed")?.trim()

        if (booted != "1") return false

        val out = rootAdbShell("test -d /sdcard/Download && echo YES-STORAGE")

        return out != null && out.contains("YES-STORAGE")

    }

    private fun rootAdbPush(

        source: File,

        remotePath: String,

        onProgress: ((Long, Long) -> Unit)? = null,

        onLog: ((String) -> Unit)? = null

    ): Boolean {

        val manager = usbManager ?: return false

        val logger: (String) -> Unit = onLog ?: { rootLog(it) }

        for (dev in rootCandidateDevices()) {

            if (!ensureUsbPermission(dev)) continue

            val proto = try { AdbSideloadProtocol.open(manager, dev) } catch (_: Exception) { null } ?: continue

            try {

                // Real `adb push` semantics (sync protocol): byte-exact, no trailing-block loss.

                if (proto.pushFile(source, remotePath, onProgress, { msg -> logger(msg) })) return true

            } catch (_: Exception) {

            } finally {

                try { proto.close() } catch (_: Exception) {}

            }

        }

        return false

    }

    /** Pulls [remotePath] off the target into [localFile] via the ADB sync protocol. */

    private fun rootAdbPull(remotePath: String, localFile: File, onProgress: ((Long) -> Unit)? = null): Boolean {

        val manager = usbManager ?: return false

        for (dev in rootCandidateDevices()) {

            if (!ensureUsbPermission(dev)) continue

            val proto = try { AdbSideloadProtocol.open(manager, dev) } catch (_: Exception) { null } ?: continue

            try {

                if (proto.pullFile(remotePath, localFile, onProgress)) return true

            } catch (_: Exception) {

            } finally {

                try { proto.close() } catch (_: Exception) {}

            }

        }

        return false

    }

    /** Copies a picked content:// file into the app cache under [subdir]. */

    private fun copyUriToCache(context: Context, uri: Uri, subdir: String): File? {

        return try {

            val raw = getFileNameFromUri(context, uri).ifBlank { "file.bin" }

            val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")

            val dir = File(context.cacheDir, subdir).apply { mkdirs() }

            val out = File(dir, safe)

            context.contentResolver.openInputStream(uri)?.use { input ->

                out.outputStream().use { output -> input.copyTo(output) }

            }

            if (out.exists() && out.length() > 0L) out else null

        } catch (_: Exception) {

            null

        }

    }

    private fun compactFmFileName(name: String, maxLen: Int = 18): String {
        if (name.length <= maxLen) return name
        val extIdx = name.lastIndexOf('.')
        if (extIdx in 1 until name.length && name.length - extIdx <= 7) {
            val ext = name.substring(extIdx)
            val keep = (maxLen - ext.length - 1).coerceAtLeast(4)
            return name.take(keep) + "…" + ext
        }
        val front = (maxLen - 3) / 2
        val back = maxLen - 3 - front
        return name.take(front) + "…" + name.takeLast(back)
    }

    private fun fmtSize(bytes: Long): String = FileManagerEngine.humanSize(bytes)

    private fun rootAdbReboot(target: String): Boolean {

        val manager = usbManager ?: return false

        val label = if (target.isBlank()) "system" else target

        for (dev in rootCandidateDevices()) {

            if (!ensureUsbPermission(dev)) continue

            val proto = try { AdbSideloadProtocol.open(manager, dev) } catch (_: Exception) { null } ?: continue

            try {

                rootLog("> adb reboot $label")

                var ok = false

                try {

                    ok = proto.executeAdbRebootService(target)

                } catch (_: Exception) {

                }

                if (!ok) {

                    // Same fallback the working "Reboot Options" card uses. The `shell:`

                    // service goes through the full CNXN/RSA handshake (drains stale packets

                    // and waits for the on-device "Allow USB debugging" tap), which the raw

                    // `reboot:` burst can miss — that is why the target previously stayed in

                    // Android after "adb reboot bootloader".

                    val shellCmd = if (target.isBlank()) "reboot" else "reboot $label"

                    try {

                        if (proto.executeAdbShellCommand(shellCmd) != null) ok = true

                    } catch (_: Exception) {}

                }

                if (!ok) {

                    val svcCmd = if (target.isBlank()) "svc power reboot" else "svc power reboot $label"

                    try {

                        if (proto.executeAdbShellCommand(svcCmd) != null) ok = true

                    } catch (_: Exception) {}

                }

                if (ok) {

                    rootLog("[OK] Sent reboot command ($label).")

                    // Target is about to vanish and re-enumerate as a new USB device;

                    // drop the stale reference so detection starts clean next tick.

                    currentConnectedDevice = null

                    permissionRequestedIds.clear()

                    return true

                }

            } catch (_: Exception) {

            } finally {

                try { proto.close() } catch (_: Exception) {}

            }

        }

        rootLog(tr("[!] Reboot command failed — put the target into bootloader manually.", "[!] Reboot command fail hua — target ko manually bootloader me daalo."))

        return false

    }

    private fun rootAdbInstall(apk: File): Boolean {

        val remote = "/data/local/tmp/romflasher_root.apk"

        rootLog("→ push APK (${apk.length() / 1048576} MB) → $remote")

        if (!rootAdbPush(apk, remote)) {

            rootLog(tr("[!] APK push failed.", "[!] APK push fail hua."))

            return false

        }

        rootLog("→ pm install -r -t $remote")

        val output = rootAdbShell("pm install -r -t $remote")

        if (output != null) rootLog(output.trim())

        if (output?.contains("Success", ignoreCase = true) == true) {

            rootAdbShell("rm -f $remote")

            return true

        }

        // HyperOS / MIUI (and some Android 14+ builds) refuse shell installs with

        // "INSTALL_FAILED_USER_RESTRICTED: Install canceled by user" until "Install via

        // USB" is on, and the package verifier can also cancel the session. Relax both

        // guards and retry as user 0. We keep this best-effort: if it still fails the

        // caller falls back to the on-device installer.

        val blocked = output?.contains("USER_RESTRICTED", ignoreCase = true) == true ||

            output?.contains("canceled", ignoreCase = true) == true ||

            output?.contains("cancelled", ignoreCase = true) == true ||

            output?.contains("Failure", ignoreCase = true) == true

        if (blocked) {

            rootLog(tr("[i] Shell install was blocked - retrying with verifier/USB guards off…", "[i] Shell install block hua - verifier/USB guards off karke retry…"))

            rootAdbShell("settings put global verifier_verify_adb_installs 0")

            rootAdbShell("settings put global package_verifier_enable 0")

            rootAdbShell("settings put global adb_install_need_confirm 0")

            rootLog("→ pm install -r --user 0 -t $remote")

            val retry = rootAdbShell("pm install -r --user 0 -t $remote")

            if (retry != null) rootLog(retry.trim())

            if (retry?.contains("Success", ignoreCase = true) == true) {

                rootAdbShell("rm -f $remote")

                return true

            }

        }

        rootAdbShell("rm -f $remote")

        return false

    }

    private fun rootFastbootFlash(partition: String, image: File, onLog: (String) -> Unit): Boolean {

        val manager = usbManager ?: return false

        for (dev in rootCandidateDevices()) {

            if (!ensureUsbPermission(dev)) continue

            val session = try { FastbootSession.open(manager, dev) } catch (_: Exception) { null } ?: continue

            try {

                if (session.flashPartition(partition, image, onLog)) return true

            } catch (e: Exception) {

                onLog("[ERROR] ${e.message}")

            } finally {

                try { session.close() } catch (_: Exception) {}

            }

        }

        return false

    }

    private fun rootFastbootBoot(image: File, onLog: (String) -> Unit): Boolean {

        if (!image.exists() || image.length() <= 0L) {

            onLog(tr("[ERROR] Recovery image file not found / empty: ${image.name}", "[ERROR] Recovery image file nahi mili / khali hai: ${image.name}"))

            return false

        }

        // The bootloader keeps settling for a moment after `adb reboot bootloader`, and the

        // first `openDevice()` can race the re-enumeration — so retry a few passes, each time

        // re-detecting so we always work with the freshest UsbDevice descriptors.

        for (pass in 1..4) {

            try { usbConnManager?.detectConnectedDevice()?.let { if (it.isConnected) currentConnectedDevice = it.usbDevice } } catch (_: Exception) {}

            val manager = usbManager ?: return false

            val candidates = rootCandidateDevices()

            if (pass == 1) {

                val info = candidates.joinToString(" | ") { d ->

                    val perm = try { manager.hasPermission(d) } catch (_: Exception) { false }

                    "id=${d.deviceId} ${d.vendorId.toString(16)}:${d.productId.toString(16)} ifaces=${d.interfaceCount} ${if (perm) "perm" else "NO-PERM"}"

                }

                onLog("[i] fastboot candidates: $info")

            }

            for (dev in candidates) {

                if (!ensureUsbPermission(dev)) {

                    onLog(tr("[!] id=${dev.deviceId}: USB permission not granted — tap ALLOW on the host phone.", "[!] id=${dev.deviceId}: USB permission nahi mili — host phone par ALLOW dabao."))

                    continue

                }

                val session = try { FastbootSession.open(manager, dev) } catch (e: Exception) {

                    onLog("[!] id=${dev.deviceId}: open exception ${e.message}")

                    null

                }

                if (session == null) {

                    onLog(tr("[!] id=${dev.deviceId} (${dev.vendorId.toString(16)}:${dev.productId.toString(16)}): the fastboot interface did not open (ifaces=${dev.interfaceCount}).", "[!] id=${dev.deviceId} (${dev.vendorId.toString(16)}:${dev.productId.toString(16)}) ka fastboot interface open nahi hua (ifaces=${dev.interfaceCount})."))

                    continue

                }

                try {

                    if (session.bootImage(image, onLog)) return true

                } catch (e: Exception) {

                    onLog("[ERROR] bootImage: ${e.message}")

                } finally {

                    try { session.close() } catch (_: Exception) {}

                }

            }

            if (pass < 4) {

                onLog(tr("[i] Waiting for the fastboot device to settle… retrying ($pass/4)", "[i] fastboot device settle ho raha hai… dobara try ($pass/4)"))

                try { Thread.sleep(1500) } catch (_: Exception) {}

            }

        }

        onLog(tr("[ERROR] No permitted fastboot device found.", "[ERROR] Koi permitted fastboot device nahi mila."))

        return false

    }

    private fun rootFastbootVar(name: String): String? {

        val manager = usbManager ?: return null

        for (dev in rootCandidateDevices()) {

            if (!ensureUsbPermission(dev)) continue

            val session = try { FastbootSession.open(manager, dev) } catch (_: Exception) { null } ?: continue

            try {

                session.getVariable(name)?.let { return it }

            } catch (_: Exception) {

            } finally {

                try { session.close() } catch (_: Exception) {}

            }

        }

        return null

    }

    private fun rootFastbootCommand(command: String): String? {

        val manager = usbManager ?: return null

        for (dev in rootCandidateDevices()) {

            if (!ensureUsbPermission(dev)) continue

            val session = try { FastbootSession.open(manager, dev) } catch (_: Exception) { null } ?: continue

            try {

                session.executeGenericCommand(command)?.let { return it }

            } catch (_: Exception) {

            } finally {

                try { session.close() } catch (_: Exception) {}

            }

        }

        return null

    }

    private suspend fun waitForDevice(mode: DeviceMode, timeoutMs: Long): Boolean {

        val deadline = System.currentTimeMillis() + timeoutMs

        val startedAt = System.currentTimeMillis()

        val label = if (mode == DeviceMode.FASTBOOT) "fastboot" else "ADB"

        var lastHintAt = startedAt

        while (System.currentTimeMillis() < deadline) {

            val detected = try { usbConnManager?.detectConnectedDevice() } catch (_: Exception) { null }

            if (detected != null && detected.isConnected && detected.mode == mode) {

                currentConnectedDevice = detected.usbDevice

                _uiState.update {

                    it.copy(

                        isConnected = true,

                        deviceMode = detected.mode,

                        deviceModel = detected.model,

                        deviceCodeName = detected.codeName

                    )

                }

                // Permission is deliberately NOT requested here. This is a

                // one-second wait loop; asking from inside it meant a fresh

                // "allow this device" dialog every single tick, which is what a

                // mode switch looked like on screen - a wall of popups. The

                // caller asks once via ensureUsbPermission() when it is actually

                // about to talk to the device.

                return true

            }

            // After a mode switch the phone re-enumerates as a new USB device. Some

            // OTG controllers only notice it after a physical replug, so give the

            // user actionable guidance instead of failing silently.

            val now = System.currentTimeMillis()

            if (now - lastHintAt >= 10000) {

                lastHintAt = now

                val waited = (System.currentTimeMillis() - startedAt) / 1000

                rootLog(tr("[i] Waiting for $label mode… (${waited}s) — if it is stuck, unplug and replug the data cable, then tap ALLOW on the host.", "[i] $label mode ka wait… (${waited}s) — agar ruka hai to data cable nikaal kar dobara laga do, host par ALLOW dabao."))

            }

            delay(1000)

        }

        rootLog(tr("[X] $label mode not detected within ${timeoutMs / 1000}s. Replug the data cable and try again.", "[X] $label mode ${timeoutMs / 1000}s me detect nahi hua. Data cable replug karke dobara try karo."))

        return false

    }

    /**

     * Builds the same permission PendingIntent [MainActivity] uses (per-device

     * request code + explicit package) so both call sites share one system

     * dialog instead of stacking two.

     */

    private fun permissionPendingIntent(dev: UsbDevice): PendingIntent? {

        val context = appContext ?: return null

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        } else {

            PendingIntent.FLAG_UPDATE_CURRENT

        }

        return PendingIntent.getBroadcast(

            context,

            dev.deviceId,

            Intent("com.romflasher.tool.USB_PERMISSION").setPackage(context.packageName),

            flags

        )

    }

    /**

     * Makes sure we hold USB permission for [dev], requesting it and waiting

     * (on the current IO thread) until the user allows it. Without this the

     * target's *fastboot* interface — a brand-new USB device after re-enumeration

     * — has no permission and every fastboot call was skipped.

     */

    private fun ensureUsbPermission(dev: UsbDevice, timeoutMs: Long = 25000): Boolean {

        val manager = usbManager ?: return false

        if (manager.hasPermission(dev)) {

            permissionRequestedIds.remove(dev.deviceId)

            return true

        }

        // Request the system dialog only ONCE per enumeration.  Every helper (adbshell / push /

        // exec-out / install / reboot) used to call requestPermission(), so a single root run

        // could stack up a dozen identical "allow USB device" popups.  `permissionRequestedIds`

        // is cleared when the device detaches (section 353/407), so a genuine reconnect still

        // gets a fresh prompt.

        if (permissionRequestedIds.add(dev.deviceId)) {

            val pi = permissionPendingIntent(dev) ?: return false

            try {

                rootLog(tr("[i] USB permission needed — tap ALLOW on the host phone…", "[i] USB permission chahiye — host phone par ALLOW dabao…"))

                manager.requestPermission(dev, pi)

            } catch (_: Exception) {

                return false

            }

        }

        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {

            if (manager.hasPermission(dev)) {

                rootLog(tr("[OK] USB permission granted.", "[OK] USB permission mil gayi."))

                return true

            }

            try { Thread.sleep(200) } catch (_: Exception) {}

        }

        // Timed out (user likely dismissed the dialog): allow a later retry to re-prompt.

        permissionRequestedIds.remove(dev.deviceId)

        return manager.hasPermission(dev)

    }

}

