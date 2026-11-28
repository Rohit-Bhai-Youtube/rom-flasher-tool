package com.romflasher.tool

import android.provider.DocumentsContract

import android.app.PendingIntent

import android.Manifest

import android.content.BroadcastReceiver

import android.content.Context

import android.content.Intent

import android.content.IntentFilter

import android.hardware.usb.UsbDevice

import android.hardware.usb.UsbManager

import android.net.Uri

import android.os.Build

import android.os.Bundle

import android.os.Environment

import android.provider.OpenableColumns

import android.provider.Settings

import android.view.WindowManager

import android.widget.Toast

import androidx.activity.ComponentActivity

import androidx.activity.compose.setContent

import androidx.activity.enableEdgeToEdge

import androidx.activity.result.contract.ActivityResultContracts

import androidx.activity.viewModels

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.collectAsState

import androidx.compose.runtime.getValue

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Surface

import androidx.compose.ui.Modifier

import androidx.core.content.ContextCompat

import com.romflasher.tool.ui.AppLanguage

import com.romflasher.tool.ui.MainScreen

import com.romflasher.tool.ui.UpdateChecker

import com.romflasher.tool.ui.UpdateDialog

import com.romflasher.tool.ui.theme.ROMFlasherTheme

import com.romflasher.tool.root.RootFileRequest

import com.romflasher.tool.viewmodel.MainViewModel

import com.romflasher.tool.viewmodel.UiState

import kotlinx.coroutines.delay

import kotlinx.coroutines.launch

import java.io.File

class MainActivity : ComponentActivity() {

    companion object {

        private const val ACTION_USB_PERMISSION = "com.romflasher.tool.USB_PERMISSION"

    }

    /**

     * The version this build actually is.

     *

     * Read from the installed package, never hardcoded. A constant here was a

     * landmine: it had to be bumped in two places for every release, and when

     * only build.gradle.kts got bumped the app judged itself older than the

     * release it was already running - v1.2.4 blocked itself and showed the

     * update dialog forever. The package manager cannot get out of step with

     * the APK, so this is the one source of truth that cannot drift.

     */

    private fun appVersion(): String = try {

        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"

    } catch (_: Exception) {

        "0.0.0"

    }

    private fun appVersionCode(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (_: Exception) {
        0L
    }

    private val viewModel: MainViewModel by viewModels()

    /** The blocked-version dialog payload, set once the gate has an answer. */

    private val pendingUpdate = androidx.compose.runtime.mutableStateOf<UpdateChecker.Result?>(null)

    /** Device ids we've already asked the system for permission — avoids stacking dialogs. */

    private val pendingUsbPermission = mutableSetOf<Int>()

    /**

     * Last time we asked for permission, per device id.

     *

     * A phone that switches mode (normal -> ADB -> recovery -> fastboot)

     * re-enumerates each time, so it arrives as a brand-new USB device with a

     * new id and the system is willing to show the "allow this device" dialog

     * again. Asking the instant every one of those enumerations lands produced

     * a burst of identical dialogs with no pause in between. Holding off for a

     * few seconds after the last ask lets the phone settle on its final mode

     * first, so one dialog covers the connection the user actually wants.

     */

    private val lastUsbPermissionAsk = mutableMapOf<Int, Long>()

    private val usbAskThrottleMs = 2500L

    private val usbPermissionReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            val action = intent?.action

            if (ACTION_USB_PERMISSION == action || UsbManager.ACTION_USB_DEVICE_ATTACHED == action || UsbManager.ACTION_USB_DEVICE_DETACHED == action) {

                synchronized(this) {

                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

                    } else {

                        @Suppress("DEPRECATION")

                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                    }

                    // A permission result (grant OR deny) or a detach clears the "asked" flag so a

                    // future tap can ask again, but a burst of button presses won't re-show it.

                    if (ACTION_USB_PERMISSION == action || UsbManager.ACTION_USB_DEVICE_DETACHED == action) {

                        device?.let {

                            pendingUsbPermission.remove(it.deviceId)

                            lastUsbPermissionAsk.remove(it.deviceId)

                        }

                    }

                    if (ACTION_USB_PERMISSION == action && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {

                        device?.let {

                            Toast.makeText(this@MainActivity, "USB Permission Granted!", Toast.LENGTH_SHORT).show()

                        }

                    }

                    requestUsbPermissionIfNeeded()

                    viewModel.refreshUsbDevices()

                }

            }

        }

    }

    // File Picker for Archive (.zip, .7z, .tar, .tgz, .rar)

    private val selectRomArchiveLauncher = registerForActivityResult(

        ActivityResultContracts.OpenDocument()

    ) { uri: Uri? ->

        uri?.let {

            try {

                contentResolver.takePersistableUriPermission(

                    it,

                    Intent.FLAG_GRANT_READ_URI_PERMISSION

                )

            } catch (e: Exception) {

                // Ignore if not persistable

            }

            val fileName = getFileNameFromUri(it)

            viewModel.handleAutoRomArchiveSelected(this, it, fileName)

        }

    }

    // Directory Picker for Extracted ROM Folder

    private val selectRomFolderLauncher = registerForActivityResult(

        ActivityResultContracts.OpenDocumentTree()

    ) { uri: Uri? ->

        uri?.let {

            val path = getFolderPathFromUri(it)

            if (path != null) {

                viewModel.scanExtractedFolder(File(path))

            } else {

                Toast.makeText(this, "Could not resolve folder path. Please select from internal storage.", Toast.LENGTH_LONG).show()

            }

        }

    }

    // File Picker for Custom Partition Images (.img, .bin)

    private val selectCustomImageLauncher = registerForActivityResult(

        ActivityResultContracts.OpenDocument()

    ) { uri: Uri? ->

        uri?.let {

            viewModel.processSelectedCustomImageUri(this, it)

        }

    }

    // File Picker for ADB Sideload Packages (.zip, .apk, .bin)

    private val selectSideloadFileLauncher = registerForActivityResult(

        ActivityResultContracts.OpenDocument()

    ) { uri: Uri? ->

        uri?.let {

            viewModel.processSelectedSideloadUri(this, it)

        }

    }

    // File Picker for Payload Dumper (.zip files containing payload.bin)

    private val selectPayloadZipLauncher = registerForActivityResult(

        ActivityResultContracts.OpenDocument()

    ) { uri: Uri? ->

        uri?.let {

            try {

                contentResolver.takePersistableUriPermission(

                    it,

                    Intent.FLAG_GRANT_READ_URI_PERMISSION

                )

            } catch (e: Exception) {

                // Ignore if not persistable

            }

            viewModel.processSelectedPayloadZipUri(this, it)

        }

    }

    // Notification permission (Android 13+) for the flash progress notification

    private val notificationPermissionLauncher = registerForActivityResult(

        ActivityResultContracts.RequestPermission()

    ) { /* result ignored — notifications just stay off if denied */ }

    // File Picker for Zero-Touch fallback (init_boot.img / boot.img)
    private val selectZeroTouchImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        viewModel.provideZeroTouchFileUri(this, uri, "stock_boot.img")
    }

    // File Picker for the Root wizard (init_boot.img / patched .img)

    private val selectRootImageLauncher = registerForActivityResult(

        ActivityResultContracts.OpenDocument()

    ) { uri: Uri? ->

        viewModel.provideRootFileUri(this, uri, "root_image.img")

    }

    // File Picker for the Root wizard (KernelSU / Magisk APK)

    private val selectRootApkLauncher = registerForActivityResult(

        ActivityResultContracts.OpenDocument()

    ) { uri: Uri? ->

        viewModel.provideRootFileUri(this, uri, "root_app.apk")

    }

    // File Picker for the Kernel Flasher (AnyKernel3 kernel .zip)

    private val selectKernelZipLauncher = registerForActivityResult(

        ActivityResultContracts.OpenDocument()

    ) { uri: Uri? ->

        uri?.let {

            try {

                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            } catch (e: Exception) {

                // Ignore if not persistable

            }

            viewModel.setKernelZip(this, it)

        }

    }

    // File Picker for the App Installer (any app file: .apk/.apks/.xapk/.apkm/.apkx ...)

    private val selectAppFileLauncher = registerForActivityResult(

        ActivityResultContracts.OpenDocument()

    ) { uri: Uri? ->

        uri?.let {

            try {

                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            } catch (e: Exception) {

                // Ignore if not persistable

            }

            viewModel.setAppInstallerFile(this, it)

        }

    }

    // File Picker for the File Manager (host -> target upload).

    // OpenMultipleDocuments so a whole batch can be pushed in one pass.

    private val selectFileManagerUploadLauncher = registerForActivityResult(

        ActivityResultContracts.OpenMultipleDocuments()

    ) { uris: List<Uri> ->

        if (uris.isEmpty()) return@registerForActivityResult

        for (uri in uris) {

            try {

                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            } catch (e: Exception) {

                // Ignore if not persistable

            }

        }

        viewModel.uploadFilesToTarget(this, uris)

    }

    private val selectGsiFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            viewModel.processSelectedGsiUri(this, it)
        }
    }

    private val selectCustomVbmetaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            viewModel.processSelectedCustomVbmetaUri(this, it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // Keep screen ON permanently while ROM Flasher Tool is running (Movie Player style)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        registerUsbReceiver()

        viewModel.initUsbManager(applicationContext)

        // Defer the storage / notification prompts until AFTER the first frame is

        // drawn. Previously they ran before setContent(), so on a fresh install the

        // app could jump straight to a system prompt while the window was still

        // blank — which reads as a "black screen" on launch.

        window.decorView.post {

            checkStoragePermissions()

            requestNotificationPermissionIfNeeded()

        }

        setContent {

            ROMFlasherTheme {

                val uiState by viewModel.uiState.collectAsState()

                val update by pendingUpdate

                // Restore the saved language before ANY screen draws, so the

                // dashboard is already in the language the user picked earlier.

                LaunchedEffect(Unit) { AppLanguage.load(this@MainActivity) }

                // The update gate runs on every launch, before the dashboard is

                // usable. If the installed build is behind the release, the app

                // stays behind the dialog - closing it does not get you in,

                // because the next launch asks exactly the same question.

                // The update gate runs in background so dashboard loads instantly (0ms delay).

                // If the installed build is behind the release, the dialog appears modally on top.

                LaunchedEffect(Unit) {

                    val current = appVersion()
                    val currentCode = appVersionCode()
                    UpdateChecker.checkForBlockedUpdate(this@MainActivity, current, currentCode)
                        ?.let { pendingUpdate.value = it }

                }

                Surface(

                    modifier = Modifier.fillMaxSize(),

                    color = MaterialTheme.colorScheme.background

                ) {

                    MainScreen(

                    uiState = uiState,

                    onSelectRomFileClick = {

                        selectRomArchiveLauncher.launch(

                            arrayOf(

                                "application/zip",

                                "application/x-zip-compressed",

                                "application/x-7z-compressed",

                                "application/x-tar",

                                "application/gzip",

                                "application/x-gtar",

                                "application/x-rar-compressed",

                                "application/octet-stream",

                                "*/*"

                            )

                        )

                    },

                    onSelectRomFolderClick = {

                        selectRomFolderLauncher.launch(null)

                    },
                    onSelectExistingFolder = { folder ->
                        viewModel.scanExtractedFolder(folder)
                    },

                    onSelectBatScript = { batFile ->

                        requestUsbPermissionIfNeeded()

                        viewModel.executeBatScript(batFile)

                    },

                    onSelectCustomImageClick = {

                        selectCustomImageLauncher.launch(

                            arrayOf("application/octet-stream", "*/*")

                        )

                    },

                    onSelectPartition = { part ->

                        viewModel.selectPartition(part)

                    },

                    onSetDualSlotChecked = { checked ->

                        viewModel.setDualSlotChecked(checked)

                    },

                    onRunCustomCommand = { cmd ->

                        requestUsbPermissionIfNeeded()

                        viewModel.executeCustomCommandLine(cmd, this)

                    },

                    onClearSelectedImage = {

                        viewModel.clearSelectedImageFile()

                    },

                    onFlashCustomImage = {

                        requestUsbPermissionIfNeeded()

                        viewModel.flashCustomPartitionImage(this)

                    },

                    onBootCustomImage = {

                        requestUsbPermissionIfNeeded()

                        viewModel.bootCustomPartitionImage(this)

                    },

                    onSelectSideloadFileClick = {

                        selectSideloadFileLauncher.launch(

                            arrayOf("application/zip", "application/x-zip-compressed", "application/vnd.android.package-archive", "application/octet-stream", "*/*")

                        )

                    },

                    onClearSelectedSideloadFile = {

                        viewModel.clearSelectedSideloadFile()

                    },

                    onFlashSideloadPackage = {

                        requestUsbPermissionIfNeeded()

                        viewModel.flashSideloadPackage()

                    },

                    onDismissBrickShield = { viewModel.dismissBrickShieldDialog() },

                    onConfirmArbDowngrade = { viewModel.confirmArbDowngradeAndFlash() },

                    onDismissArbDowngrade = { viewModel.dismissArbDowngradeDialog() },

                    onExecuteReboot = { modeTarget, command ->

                        requestUsbPermissionIfNeeded()

                        viewModel.executeRebootCommand(modeTarget, command)

                    },

                    onStartEfsBackup = { type ->

                        requestUsbPermissionIfNeeded()

                        viewModel.startEfsBackup(this, type)

                    },

                    onCopyEfsBackupToHost = {

                        requestUsbPermissionIfNeeded()

                        viewModel.startEfsBackupFileCopy()

                    },

                    onStartEfsRestore = {

                        requestUsbPermissionIfNeeded()

                        viewModel.openImeiRestorePicker()

                    },

                    onSendTerminalInput = { text ->

                        viewModel.sendTerminalInput(text)

                    },

                    onCloseTerminal = {

                        viewModel.closeTerminal()

                    },

                    onStopFlash = {

                        viewModel.stopFlash()

                    },

                    onDismissBatDialog = {

                        viewModel.dismissBatDialog()

                    },

                    onDismissError = {

                        viewModel.dismissError()

                    },

                    onSelectPayloadFileClick = {

                        selectPayloadZipLauncher.launch(

                            arrayOf(

                                "application/zip",

                                "application/x-zip-compressed",

                                "application/octet-stream",

                                "*/*"

                            )

                        )

                    },

                    onOpenPayloadDumper = {

                        viewModel.openPayloadDumperScreen()

                    },

                    onTogglePayloadPartition = { name ->

                        viewModel.togglePayloadPartitionSelection(name)

                    },

                    onSelectAllPayloadPartitions = {

                        viewModel.selectAllPayloadPartitions()

                    },

                    onDeselectAllPayloadPartitions = {

                        viewModel.deselectAllPayloadPartitions()

                    },

                    onClearSelectedPayloadFile = {

                        viewModel.clearSelectedPayloadZipFile()

                    },

                    onStartPayloadDump = {

                        viewModel.startPayloadDump(this)

                    },

                    onPayloadVerifyToggle = { value ->

                        viewModel.setPayloadVerifyAfterExtract(value)

                    },

                    onStartPayloadVerify = {

                        viewModel.startPayloadVerify()

                    },

                    onClosePayloadDumper = {

                        viewModel.closePayloadDumperScreen()

                    },

                    onShowDeviceInfo = {

                        viewModel.requestDeviceInfo()

                    },

                    onCloseDeviceInfo = {

                        viewModel.closeDeviceInfoDialog()

                    },

                    onRefreshSlot = {

                        requestUsbPermissionIfNeeded()

                        viewModel.refreshSlotInfo()

                    },

                    onSetActiveSlot = { slot ->

                        requestUsbPermissionIfNeeded()

                        viewModel.setActiveSlot(slot)

                    },

                    onOpenDebloater = {

                        requestUsbPermissionIfNeeded()

                        viewModel.openDebloater()

                    },

                    onFilterDebloaterApps = { value ->

                        viewModel.setDebloaterFilter(value)

                    },

                    onToggleDebloaterApp = { pkg ->

                        viewModel.toggleDebloaterApp(pkg)

                    },

                    onClearDebloaterSelection = {

                        viewModel.clearDebloaterSelection()

                    },

                    onRefreshDebloaterApps = {

                        viewModel.refreshDebloaterApps()

                    },

                    onDebloaterAction = { action ->

                        viewModel.applyDebloaterAction(action)

                    },

                    onCloseDebloater = {

                        viewModel.closeDebloater()

                    },

                    onDebloaterCategoryChange = { value ->

                        viewModel.setDebloaterCategory(value)

                    },

                    onCancelExtraction = {

                        viewModel.cancelExtraction()

                    },

                    onOpenRootWizard = {

                        requestUsbPermissionIfNeeded()

                        viewModel.openRootWizard()

                    },

                    onRootWizardChooseKind = { kind ->

                        viewModel.chooseRootKind(kind)

                    },

                    onRootWizardAnswer = { yes ->

                        viewModel.answerRootPrompt(yes)

                    },

                    onRootWizardChoice = { index ->

                        viewModel.answerRootChoice(index)

                    },

                    onRootWizardPickFile = {

                        requestUsbPermissionIfNeeded()

                        when (viewModel.uiState.value.rootFileRequest) {

                            RootFileRequest.ROOT_APK -> selectRootApkLauncher.launch(arrayOf("*/*"))

                            else -> selectRootImageLauncher.launch(arrayOf("*/*"))

                        }

                    },

                    onCloseRootWizard = {

                        viewModel.closeRootWizard()

                    },
                    onOpenZeroTouchRoot = {
                        requestUsbPermissionIfNeeded()
                        viewModel.openZeroTouchDialog()
                    },
                    onStartZeroTouchRoot = { targetRoot ->
                        viewModel.startZeroTouchEngine(targetRoot)
                    },
                    onZeroTouchPickFile = {
                        requestUsbPermissionIfNeeded()
                        selectZeroTouchImageLauncher.launch(arrayOf("*/*"))
                    },
                    onCloseZeroTouchRoot = {
                        viewModel.closeZeroTouchDialog()
                    },
                    onUninstallRoot = {
                        requestUsbPermissionIfNeeded()
                        viewModel.startUninstallRootEngine()
                    },
                    onOpenXiaomiBootDownloader = {
                        viewModel.openXiaomiBootDownloader()
                    },
                    onXiaomiSearchChange = { query ->
                        viewModel.setXiaomiSearchQuery(query)
                    },
                    onXiaomiSelectDevice = { device ->
                        viewModel.selectXiaomiDevice(device)
                    },
                    onXiaomiSelectRegion = { region ->
                        viewModel.selectXiaomiRegion(region)
                    },
                    onXiaomiBackToDeviceList = {
                        viewModel.clearXiaomiSelectedDevice()
                    },
                    onXiaomiBackToRegionList = {
                        viewModel.clearXiaomiSelectedRegion()
                    },
                    onXiaomiStartDownload = { ver ->
                        viewModel.startXiaomiBootDownload(ver)
                    },
                    onXiaomiUseForRoot = { file ->
                        viewModel.useDownloadedBootForRoot(file)
                    },
                    onXiaomiDismissDownloader = {
                        viewModel.closeXiaomiBootDownloader()
                    },

                    onKernelBackup = {

                        requestUsbPermissionIfNeeded()

                        viewModel.startKernelBackup()

                    },

                    onKernelRestore = {

                        requestUsbPermissionIfNeeded()

                        viewModel.openKernelRestorePicker()

                    },

                    onRestorePick = { entry ->

                        viewModel.confirmRestorePick(entry)

                    },

                    onRestorePickerDismiss = {

                        viewModel.dismissRestorePicker()

                    },

                    onRestorePickerRefresh = {

                        viewModel.refreshRestorePicker()

                    },

                    onKernelBootloopYes = {

                        viewModel.kernelBootloopYes()

                    },

                    onKernelBootloopNo = {

                        viewModel.kernelBootloopNo()

                    },

                    onKernelBootloopDismiss = {

                        viewModel.dismissKernelBootloopPrompt()

                    },

                    onSelectKernelZip = {

                        selectKernelZipLauncher.launch(

                            arrayOf("application/zip", "application/octet-stream", "*/*")

                        )

                    },

                    onFlashKernelZip = {

                        requestUsbPermissionIfNeeded()

                        viewModel.flashKernelZip()

                    },

                    onToggleKernelAutoBackup = { enabled ->

                        viewModel.setKernelAutoBackup(enabled)

                    },

                    onCloseKernelFlasher = {

                        viewModel.closeKernelFlasher()

                    },

                    onKernelCancelReboot = {

                        viewModel.cancelKernelReboot()

                    },

                    onKernelRebootNow = {

                        viewModel.rebootNow()

                    },

                    onKernelOpenRootWizard = {

                        requestUsbPermissionIfNeeded()

                        viewModel.openRootWizardFromKernel()

                    },

                    onChooseAppFile = {

                        selectAppFileLauncher.launch(

                            arrayOf(

                                "application/vnd.android.package-archive",

                                "application/zip",

                                "application/octet-stream",

                                "*/*"

                            )

                        )

                    },

                    onInstallApp = {

                        requestUsbPermissionIfNeeded()

                        viewModel.installSelectedApp()

                    },

                    onClearAppFile = {

                        viewModel.clearAppInstallerFile()

                    },

                    onCloseAppInstaller = {

                        viewModel.closeAppInstaller()

                    },

                    onOpenFileManager = {

                        requestUsbPermissionIfNeeded()

                        viewModel.openFileManager()

                    },

                    onFileManagerNavigate = { path ->

                        viewModel.fileManagerNavigate(path)

                    },

                    onFileManagerUp = {

                        viewModel.fileManagerUp()

                    },

                    onFileManagerHome = {

                        viewModel.fileManagerHome()

                    },

                    onFileManagerRefresh = {

                        requestUsbPermissionIfNeeded()

                        viewModel.refreshFileManager()

                    },

                    onFileManagerSelect = { name ->

                        viewModel.fileManagerSelect(name)

                    },

                    onFileManagerSelectAll = {

                        viewModel.fileManagerSelectAll()

                    },

                    onFileManagerClearSelection = {

                        viewModel.fileManagerClearSelection()

                    },

                    onFileManagerUpload = {

                        selectFileManagerUploadLauncher.launch(arrayOf("*/*"))

                    },

                    onFileManagerDownload = {

                        viewModel.downloadSelectedFiles()

                    },

                    onFileManagerNewFolder = { name ->

                        viewModel.fileManagerNewFolder(name)

                    },

                    onFileManagerDelete = {

                        viewModel.fileManagerDeleteSelected()

                    },

                    onCloseFileManager = {

                        viewModel.closeFileManager()

                    },

                    onRomMismatchProceed = {

                        viewModel.proceedRomMismatch()

                    },

                    onRomMismatchCancel = {

                        viewModel.cancelRomMismatch()

                    },

                    onDisableVbmeta = {
                        requestUsbPermissionIfNeeded()
                        viewModel.disableVbmetaVerity()
                    },

                    onSelectGsiClick = {
                        selectGsiFileLauncher.launch(
                            arrayOf("application/octet-stream", "application/x-xz", "application/zip", "application/gzip", "*/*")
                        )
                    },

                    onClearGsiClick = {
                        viewModel.clearGsiFile()
                    },

                    onSelectCustomVbmetaClick = {
                        selectCustomVbmetaLauncher.launch(
                            arrayOf("application/octet-stream", "*/*")
                        )
                    },

                    onClearCustomVbmetaClick = {
                        viewModel.clearCustomVbmetaFile()
                    },

                    onToggleGsiDisableVbmeta = { enabled ->
                        viewModel.setGsiDisableVbmeta(enabled)
                    },

                    onToggleGsiWipeUserdata = { enabled ->
                        viewModel.setGsiWipeUserdata(enabled)
                    },

                    onToggleGsiRebootFastbootd = { enabled ->
                        viewModel.setGsiRebootFastbootd(enabled)
                    },

                    onToggleGsiFreeSuperSpace = { enabled ->
                        viewModel.setGsiFreeSuperSpace(enabled)
                    },

                    onRebootToFastbootd = {
                        requestUsbPermissionIfNeeded()
                        viewModel.rebootToFastbootd()
                    },

                    onFlashGsi = {
                        requestUsbPermissionIfNeeded()
                        viewModel.flashGsi()
                    }

                    )

                }

                // If an update is required, show the modal blocking dialog on top

                update?.let { result ->

                    UpdateDialog(

                        result = result,

                        onUpdateNow = {

                            // Leave the dialog standing behind the browser

                            UpdateChecker.openSite(this@MainActivity)

                        },

                        onCloseApp = {

                            // Close app if user declines required update

                            finishAndRemoveTask()

                        }

                    )

                }

            }

        }

    }

    /**

     * True when nothing is running and no dialog is open.

     *

     * This is the gate that keeps the update prompt out of the way: a flash, an

     * extraction, a sideload, a payload dump, a download or any open screen all

     * mean "busy", and the prompt simply waits.

     */

    private fun isAppIdle(s: UiState): Boolean {

        val busy = s.isExtracting ||

            s.isFlashingCustomImage ||

            s.isPreparingCustomImageFile ||

            s.isSideloadingPackage ||

            s.isPreparingSideloadFile ||

            s.isFlashingGsi ||

            s.isBackingUpEfs ||

            s.isRestoringEfs ||

            s.isCopyingEfsBackup ||

            s.isParsingPayloadManifest ||

            s.isDumpingPayload ||

            s.isVerifyingPayload ||

            s.isLoadingDeviceInfo ||

            s.isLoadingSlot ||

            s.isSettingSlot ||

            s.isLoadingDebloater ||

            s.isDebloaterBusy ||

            s.isWaitingForInput

        val screenOpen = s.showTerminal ||

            s.showBatDialog ||

            s.showDeviceInfoDialog ||

            s.showDebloaterDialog ||

            s.showRootWizard ||
            s.showZeroTouchRootDialog ||

            s.showKernelFlasher ||

            s.showKernelRootPrompt ||

            s.showKernelBootloopPrompt ||

            s.showAppInstaller ||

            s.showFileManager ||

            s.showRomMismatchDialog ||

            s.showRestorePicker ||

            s.isPayloadDumperScreenOpen

        return !busy && !screenOpen

    }

    override fun onStart() {

        super.onStart()

        viewModel.resumeUsbPolling()

    }

    override fun onResume() {

        super.onResume()

        viewModel.resumeUsbPolling()

        requestUsbPermissionIfNeeded()

        viewModel.refreshUsbDevices()

    }

    override fun onStop() {

        super.onStop()

        viewModel.pauseUsbPolling()

    }

    /**

     * The device being attached (ADB / fastboot) delivers a USB_DEVICE_ATTACHED

     * intent. With `singleTop` the activity is reused instead of being recreated,

     * so ongoing work keeps running and the UI just updates live.

     */

    override fun onNewIntent(intent: Intent) {

        super.onNewIntent(intent)

        setIntent(intent)

        requestUsbPermissionIfNeeded()

        viewModel.refreshUsbDevices()

    }

    override fun onDestroy() {

        super.onDestroy()

        try {

            unregisterReceiver(usbPermissionReceiver)

        } catch (_: Exception) {}

    }

    private fun registerUsbReceiver() {

        try {

            val filter = IntentFilter().apply {

                addAction(ACTION_USB_PERMISSION)

                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)

                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)

            }

            ContextCompat.registerReceiver(this, usbPermissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        } catch (_: Exception) {}

    }

    private fun requestUsbPermissionIfNeeded() {

        try {

            val usbManager = getSystemService(USB_SERVICE) as UsbManager

            val deviceList = usbManager.deviceList

            val now = System.currentTimeMillis()

            for (device in deviceList.values) {

                if (usbManager.hasPermission(device)) {

                    pendingUsbPermission.remove(device.deviceId)

                    lastUsbPermissionAsk.remove(device.deviceId)

                    continue

                }

                // Only ask once per attach; without this every button press queued another dialog.

                if (!pendingUsbPermission.add(device.deviceId)) continue

                // ...and at most once every couple of seconds per device, so a

                // phone re-enumerating through its modes does not stack dialogs.

                val last = lastUsbPermissionAsk[device.deviceId] ?: 0L

                if (now - last < usbAskThrottleMs) {

                    pendingUsbPermission.remove(device.deviceId)

                    continue

                }

                lastUsbPermissionAsk[device.deviceId] = now

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

                } else {

                    PendingIntent.FLAG_UPDATE_CURRENT

                }

                val permissionIntent = PendingIntent.getBroadcast(

                    this,

                    device.deviceId,

                    Intent(ACTION_USB_PERMISSION).setPackage(packageName),

                    flags

                )

                usbManager.requestPermission(device, permissionIntent)

            }

        } catch (_: Exception) {}

    }

    private fun requestNotificationPermissionIfNeeded() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            try {

                val granted = ContextCompat.checkSelfPermission(

                    this,

                    Manifest.permission.POST_NOTIFICATIONS

                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (!granted) {

                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                }

            } catch (_: Exception) {

            }

        }

    }

    private fun checkStoragePermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            if (!Environment.isExternalStorageManager()) {

                try {

                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)

                    intent.data = Uri.parse("package:" + applicationContext.packageName)

                    startActivity(intent)

                } catch (e: Exception) {

                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

                    startActivity(intent)

                }

            }

        }

    }

    private fun getFileNameFromUri(uri: Uri): String {

        var name = "custom_image.img"

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->

            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

            if (nameIndex != -1 && cursor.moveToFirst()) {

                name = cursor.getString(nameIndex)

            }

        }

        return name

    }

    private fun getFolderPathFromUri(uri: Uri): String? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            if (docId != null && docId.startsWith("primary:")) {
                val relPath = docId.substringAfter("primary:")
                return "${Environment.getExternalStorageDirectory().absolutePath}/$relPath"
            }
        } catch (_: Exception) {}

        try {
            val docId = DocumentsContract.getDocumentId(uri)
            if (docId != null && docId.startsWith("primary:")) {
                val relPath = docId.substringAfter("primary:")
                return "${Environment.getExternalStorageDirectory().absolutePath}/$relPath"
            }
        } catch (_: Exception) {}

        val decodedUri = Uri.decode(uri.toString())
        if (decodedUri != null && decodedUri.contains("/tree/primary:")) {
            val subPath = decodedUri.substringAfter("/tree/primary:")
            return "${Environment.getExternalStorageDirectory().absolutePath}/$subPath"
        }

        val rawPath = uri.path ?: return null
        val decodedPath = Uri.decode(rawPath) ?: rawPath
        if (decodedPath.contains("/tree/primary:")) {
            val subPath = decodedPath.substringAfter("/tree/primary:")
            return "${Environment.getExternalStorageDirectory().absolutePath}/$subPath"
        } else if (decodedPath.contains("/tree/raw:")) {
            return decodedPath.substringAfter("/tree/raw:")
        }
        return decodedPath
    }

}

