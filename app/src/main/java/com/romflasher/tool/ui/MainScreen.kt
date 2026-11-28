package com.romflasher.tool.ui

import android.os.Build

import androidx.compose.animation.*

import androidx.compose.foundation.*

import androidx.compose.foundation.ExperimentalFoundationApi

import androidx.compose.foundation.layout.*

import androidx.compose.foundation.relocation.BringIntoViewRequester

import androidx.compose.foundation.relocation.bringIntoViewRequester

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.rounded.*

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.alpha

import androidx.compose.ui.draw.blur

import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.platform.LocalLifecycleOwner

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.core.spring
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider

import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import androidx.lifecycle.Lifecycle

import androidx.lifecycle.LifecycleEventObserver

import com.romflasher.tool.ui.theme.*

import com.romflasher.tool.root.RootKind
import com.romflasher.tool.root.XiaomiDevice
import com.romflasher.tool.root.XiaomiRegion
import com.romflasher.tool.root.XiaomiRomVersion

import com.romflasher.tool.util.RomFlasherStorage

import com.romflasher.tool.util.YouTubeFeed

import com.romflasher.tool.viewmodel.UiState

import java.io.File

@Composable

fun MainScreen(

    uiState: UiState,

    onSelectRomFileClick: () -> Unit,

    onSelectRomFolderClick: () -> Unit,
    onSelectExistingFolder: (File) -> Unit = {},

    onSelectBatScript: (File) -> Unit,

    onSelectCustomImageClick: () -> Unit,

    onSelectPartition: (String) -> Unit,

    onSetDualSlotChecked: (Boolean) -> Unit,

    onRunCustomCommand: (String) -> Unit = {},

    onClearSelectedImage: () -> Unit,

    onFlashCustomImage: () -> Unit,

    onBootCustomImage: (() -> Unit)? = null,

    onSelectSideloadFileClick: () -> Unit,

    onClearSelectedSideloadFile: () -> Unit,

    onFlashSideloadPackage: () -> Unit,

    onDismissBrickShield: () -> Unit = {},

    onConfirmArbDowngrade: () -> Unit = {},

    onDismissArbDowngrade: () -> Unit = {},

    onExecuteReboot: (modeTarget: String, command: String) -> Unit,

    onStartEfsBackup: (type: String) -> Unit,

    onCopyEfsBackupToHost: () -> Unit,

    onStartEfsRestore: () -> Unit,

    onSendTerminalInput: (String) -> Unit,

    onCloseTerminal: () -> Unit,

    onStopFlash: () -> Unit,

    onDismissBatDialog: () -> Unit,

    onDismissError: () -> Unit,

    onSelectPayloadFileClick: () -> Unit,

    onOpenPayloadDumper: () -> Unit,

    onTogglePayloadPartition: (String) -> Unit,

    onSelectAllPayloadPartitions: () -> Unit,

    onDeselectAllPayloadPartitions: () -> Unit,

    onClearSelectedPayloadFile: () -> Unit,

    onStartPayloadDump: () -> Unit,

    onPayloadVerifyToggle: (Boolean) -> Unit,

    onStartPayloadVerify: () -> Unit,

    onClosePayloadDumper: () -> Unit,

    onShowDeviceInfo: () -> Unit,

    onCloseDeviceInfo: () -> Unit,

    onRefreshSlot: () -> Unit,

    onSetActiveSlot: (String) -> Unit,

    onOpenDebloater: () -> Unit,

    onFilterDebloaterApps: (String) -> Unit,

    onToggleDebloaterApp: (String) -> Unit,

    onClearDebloaterSelection: () -> Unit,

    onRefreshDebloaterApps: () -> Unit,

    onDebloaterAction: (String) -> Unit,

    onCloseDebloater: () -> Unit,

    onDebloaterCategoryChange: (String) -> Unit,

    onCancelExtraction: () -> Unit,

    onOpenRootWizard: () -> Unit,
    onOpenZeroTouchRoot: () -> Unit = {},
    onStartZeroTouchRoot: (String) -> Unit = {},
    onZeroTouchPickFile: () -> Unit = {},
    onCloseZeroTouchRoot: () -> Unit = {},
    onUninstallRoot: () -> Unit = {},
    onOpenXiaomiBootDownloader: () -> Unit = {},
    onXiaomiSearchChange: (String) -> Unit = {},
    onXiaomiSelectDevice: (XiaomiDevice) -> Unit = {},
    onXiaomiSelectRegion: (XiaomiRegion) -> Unit = {},
    onXiaomiBackToDeviceList: () -> Unit = {},
    onXiaomiBackToRegionList: () -> Unit = {},
    onXiaomiStartDownload: (XiaomiRomVersion) -> Unit = {},
    onXiaomiUseForRoot: (File) -> Unit = {},
    onXiaomiDismissDownloader: () -> Unit = {},

    onRootWizardAnswer: (Boolean) -> Unit,

    onRootWizardChoice: (Int) -> Unit,

    onRootWizardPickFile: () -> Unit,

    onRootWizardChooseKind: (RootKind) -> Unit,

    onCloseRootWizard: () -> Unit,

    onKernelBackup: () -> Unit,

    onKernelRestore: () -> Unit,

    onSelectKernelZip: () -> Unit,

    onFlashKernelZip: () -> Unit,

    onToggleKernelAutoBackup: (Boolean) -> Unit,

    onCloseKernelFlasher: () -> Unit,

    onKernelCancelReboot: () -> Unit,

    onKernelRebootNow: () -> Unit,

    onKernelOpenRootWizard: () -> Unit,

    onChooseAppFile: () -> Unit,

    onClearAppFile: () -> Unit,

    onInstallApp: () -> Unit,

    onCloseAppInstaller: () -> Unit,

    onOpenFileManager: () -> Unit,

    onFileManagerNavigate: (String) -> Unit,

    onFileManagerUp: () -> Unit,

    onFileManagerHome: () -> Unit,

    onFileManagerRefresh: () -> Unit,

    onFileManagerSelect: (String?) -> Unit,

    onFileManagerSelectAll: () -> Unit,

    onFileManagerClearSelection: () -> Unit,

    onFileManagerUpload: () -> Unit,

    onFileManagerDownload: () -> Unit,

    onFileManagerNewFolder: (String) -> Unit,

    onFileManagerDelete: () -> Unit,

    onCloseFileManager: () -> Unit,

    onRomMismatchProceed: () -> Unit,

    onRomMismatchCancel: () -> Unit,

    onRestorePick: (RomFlasherStorage.BackupEntry) -> Unit,

    onRestorePickerDismiss: () -> Unit,

    onRestorePickerRefresh: () -> Unit,

    onKernelBootloopYes: () -> Unit,

    onKernelBootloopNo: () -> Unit,

    onKernelBootloopDismiss: () -> Unit,

    onDisableVbmeta: () -> Unit = {},

    onSelectGsiClick: () -> Unit = {},

    onClearGsiClick: () -> Unit = {},

    onSelectCustomVbmetaClick: () -> Unit = {},

    onClearCustomVbmetaClick: () -> Unit = {},

    onToggleGsiDisableVbmeta: (Boolean) -> Unit = {},

    onToggleGsiWipeUserdata: (Boolean) -> Unit = {},

    onToggleGsiRebootFastbootd: (Boolean) -> Unit = {},

    onToggleGsiFreeSuperSpace: (Boolean) -> Unit = {},

    onRebootToFastbootd: () -> Unit = {},

    onFlashGsi: () -> Unit = {}

) {

    MainDashboardScreen(

        uiState = uiState,

        onSelectRomFileClick = onSelectRomFileClick,

        onSelectRomFolderClick = onSelectRomFolderClick,
        onSelectExistingFolder = onSelectExistingFolder,

        onSelectBatScript = onSelectBatScript,

        onSelectCustomImageClick = onSelectCustomImageClick,

        onSelectPartition = onSelectPartition,

        onSetDualSlotChecked = onSetDualSlotChecked,

        onRunCustomCommand = onRunCustomCommand,

        onClearSelectedImage = onClearSelectedImage,

        onFlashCustomImage = onFlashCustomImage,

        onSelectSideloadFileClick = onSelectSideloadFileClick,

        onClearSelectedSideloadFile = onClearSelectedSideloadFile,

        onFlashSideloadPackage = onFlashSideloadPackage,

        onExecuteReboot = onExecuteReboot,

        onStartEfsBackup = onStartEfsBackup,

        onCopyEfsBackupToHost = onCopyEfsBackupToHost,

        onStartEfsRestore = onStartEfsRestore,

        onSendTerminalInput = onSendTerminalInput,

        onCloseTerminal = onCloseTerminal,

        onStopFlash = onStopFlash,

        onDismissBatDialog = onDismissBatDialog,

        onDismissError = onDismissError,

        onSelectPayloadFileClick = onSelectPayloadFileClick,

        onOpenPayloadDumper = onOpenPayloadDumper,

        onTogglePayloadPartition = onTogglePayloadPartition,

        onSelectAllPayloadPartitions = onSelectAllPayloadPartitions,

        onDeselectAllPayloadPartitions = onDeselectAllPayloadPartitions,

        onClearSelectedPayloadFile = onClearSelectedPayloadFile,

        onStartPayloadDump = onStartPayloadDump,

        onPayloadVerifyToggle = onPayloadVerifyToggle,

        onStartPayloadVerify = onStartPayloadVerify,

        onClosePayloadDumper = onClosePayloadDumper,

        onShowDeviceInfo = onShowDeviceInfo,

        onCloseDeviceInfo = onCloseDeviceInfo,

        onRefreshSlot = onRefreshSlot,

        onSetActiveSlot = onSetActiveSlot,

        onOpenDebloater = onOpenDebloater,

        onFilterDebloaterApps = onFilterDebloaterApps,

        onToggleDebloaterApp = onToggleDebloaterApp,

        onClearDebloaterSelection = onClearDebloaterSelection,

        onRefreshDebloaterApps = onRefreshDebloaterApps,

        onDebloaterAction = onDebloaterAction,

        onCloseDebloater = onCloseDebloater,

        onDebloaterCategoryChange = onDebloaterCategoryChange,

        onCancelExtraction = onCancelExtraction,

        onOpenRootWizard = onOpenRootWizard,
        onOpenZeroTouchRoot = onOpenZeroTouchRoot,
        onStartZeroTouchRoot = onStartZeroTouchRoot,
        onZeroTouchPickFile = onZeroTouchPickFile,
        onCloseZeroTouchRoot = onCloseZeroTouchRoot,
        onUninstallRoot = onUninstallRoot,
        onOpenXiaomiBootDownloader = onOpenXiaomiBootDownloader,
        onXiaomiSearchChange = onXiaomiSearchChange,
        onXiaomiSelectDevice = onXiaomiSelectDevice,
        onXiaomiSelectRegion = onXiaomiSelectRegion,
        onXiaomiBackToDeviceList = onXiaomiBackToDeviceList,
        onXiaomiBackToRegionList = onXiaomiBackToRegionList,
        onXiaomiStartDownload = onXiaomiStartDownload,
        onXiaomiUseForRoot = onXiaomiUseForRoot,
        onXiaomiDismissDownloader = onXiaomiDismissDownloader,

        onRootWizardAnswer = onRootWizardAnswer,

        onRootWizardChoice = onRootWizardChoice,

        onRootWizardPickFile = onRootWizardPickFile,

        onRootWizardChooseKind = onRootWizardChooseKind,

        onCloseRootWizard = onCloseRootWizard,

        onKernelBackup = onKernelBackup,

        onKernelRestore = onKernelRestore,

        onSelectKernelZip = onSelectKernelZip,

        onFlashKernelZip = onFlashKernelZip,

        onToggleKernelAutoBackup = onToggleKernelAutoBackup,

        onCloseKernelFlasher = onCloseKernelFlasher,

        onKernelCancelReboot = onKernelCancelReboot,

        onKernelRebootNow = onKernelRebootNow,

        onKernelOpenRootWizard = onKernelOpenRootWizard,

        onChooseAppFile = onChooseAppFile,

        onClearAppFile = onClearAppFile,

        onInstallApp = onInstallApp,

        onCloseAppInstaller = onCloseAppInstaller,

        onOpenFileManager = onOpenFileManager,

        onFileManagerNavigate = onFileManagerNavigate,

        onFileManagerUp = onFileManagerUp,

        onFileManagerHome = onFileManagerHome,

        onFileManagerRefresh = onFileManagerRefresh,

        onFileManagerSelect = onFileManagerSelect,

        onFileManagerSelectAll = onFileManagerSelectAll,

        onFileManagerClearSelection = onFileManagerClearSelection,

        onFileManagerUpload = onFileManagerUpload,

        onFileManagerDownload = onFileManagerDownload,

        onFileManagerNewFolder = onFileManagerNewFolder,

        onFileManagerDelete = onFileManagerDelete,

        onCloseFileManager = onCloseFileManager,

        onRomMismatchProceed = onRomMismatchProceed,

        onRomMismatchCancel = onRomMismatchCancel,

        onRestorePick = onRestorePick,

        onRestorePickerDismiss = onRestorePickerDismiss,

        onRestorePickerRefresh = onRestorePickerRefresh,

        onKernelBootloopYes = onKernelBootloopYes,

        onKernelBootloopNo = onKernelBootloopNo,

        onKernelBootloopDismiss = onKernelBootloopDismiss,

        onDismissBrickShield = onDismissBrickShield,

        onConfirmArbDowngrade = onConfirmArbDowngrade,

        onDismissArbDowngrade = onDismissArbDowngrade,

        onBootCustomImage = onBootCustomImage,

        onDisableVbmeta = onDisableVbmeta,

        onSelectGsiClick = onSelectGsiClick,

        onClearGsiClick = onClearGsiClick,

        onSelectCustomVbmetaClick = onSelectCustomVbmetaClick,

        onClearCustomVbmetaClick = onClearCustomVbmetaClick,

        onToggleGsiDisableVbmeta = onToggleGsiDisableVbmeta,

        onToggleGsiWipeUserdata = onToggleGsiWipeUserdata,

        onToggleGsiRebootFastbootd = onToggleGsiRebootFastbootd,

        onToggleGsiFreeSuperSpace = onToggleGsiFreeSuperSpace,

        onRebootToFastbootd = onRebootToFastbootd,

        onFlashGsi = onFlashGsi

    )

}

@Composable

fun MainDashboardScreen(

    uiState: UiState,

    onSelectRomFileClick: () -> Unit,

    onSelectRomFolderClick: () -> Unit,
    onSelectExistingFolder: (File) -> Unit = {},

    onSelectBatScript: (File) -> Unit,

    onSelectCustomImageClick: () -> Unit,

    onSelectPartition: (String) -> Unit,

    onSetDualSlotChecked: (Boolean) -> Unit,

    onRunCustomCommand: (String) -> Unit = {},

    onClearSelectedImage: () -> Unit,

    onFlashCustomImage: () -> Unit,

    onBootCustomImage: (() -> Unit)? = null,

    onSelectSideloadFileClick: () -> Unit,

    onClearSelectedSideloadFile: () -> Unit,

    onFlashSideloadPackage: () -> Unit,

    onDismissBrickShield: () -> Unit = {},

    onConfirmArbDowngrade: () -> Unit = {},

    onDismissArbDowngrade: () -> Unit = {},

    onExecuteReboot: (modeTarget: String, command: String) -> Unit,

    onStartEfsBackup: (type: String) -> Unit,

    onCopyEfsBackupToHost: () -> Unit,

    onStartEfsRestore: () -> Unit,

    onSendTerminalInput: (String) -> Unit,

    onCloseTerminal: () -> Unit,

    onStopFlash: () -> Unit,

    onDismissBatDialog: () -> Unit,

    onDismissError: () -> Unit,

    onSelectPayloadFileClick: () -> Unit,

    onOpenPayloadDumper: () -> Unit,

    onTogglePayloadPartition: (String) -> Unit,

    onSelectAllPayloadPartitions: () -> Unit,

    onDeselectAllPayloadPartitions: () -> Unit,

    onClearSelectedPayloadFile: () -> Unit,

    onStartPayloadDump: () -> Unit,

    onPayloadVerifyToggle: (Boolean) -> Unit,

    onStartPayloadVerify: () -> Unit,

    onClosePayloadDumper: () -> Unit,

    onShowDeviceInfo: () -> Unit,

    onCloseDeviceInfo: () -> Unit,

    onRefreshSlot: () -> Unit,

    onSetActiveSlot: (String) -> Unit,

    onOpenDebloater: () -> Unit,

    onFilterDebloaterApps: (String) -> Unit,

    onToggleDebloaterApp: (String) -> Unit,

    onClearDebloaterSelection: () -> Unit,

    onRefreshDebloaterApps: () -> Unit,

    onDebloaterAction: (String) -> Unit,

    onCloseDebloater: () -> Unit,

    onDebloaterCategoryChange: (String) -> Unit,

    onCancelExtraction: () -> Unit,

    onOpenRootWizard: () -> Unit,
    onOpenZeroTouchRoot: () -> Unit = {},
    onStartZeroTouchRoot: (String) -> Unit = {},
    onZeroTouchPickFile: () -> Unit = {},
    onCloseZeroTouchRoot: () -> Unit = {},
    onUninstallRoot: () -> Unit = {},
    onOpenXiaomiBootDownloader: () -> Unit = {},
    onXiaomiSearchChange: (String) -> Unit = {},
    onXiaomiSelectDevice: (XiaomiDevice) -> Unit = {},
    onXiaomiSelectRegion: (XiaomiRegion) -> Unit = {},
    onXiaomiBackToDeviceList: () -> Unit = {},
    onXiaomiBackToRegionList: () -> Unit = {},
    onXiaomiStartDownload: (XiaomiRomVersion) -> Unit = {},
    onXiaomiUseForRoot: (File) -> Unit = {},
    onXiaomiDismissDownloader: () -> Unit = {},

    onRootWizardAnswer: (Boolean) -> Unit,

    onRootWizardChoice: (Int) -> Unit,

    onRootWizardPickFile: () -> Unit,

    onRootWizardChooseKind: (RootKind) -> Unit,

    onCloseRootWizard: () -> Unit,

    onKernelBackup: () -> Unit,

    onKernelRestore: () -> Unit,

    onSelectKernelZip: () -> Unit,

    onFlashKernelZip: () -> Unit,

    onToggleKernelAutoBackup: (Boolean) -> Unit,

    onCloseKernelFlasher: () -> Unit,

    onKernelCancelReboot: () -> Unit,

    onKernelRebootNow: () -> Unit,

    onKernelOpenRootWizard: () -> Unit,

    onChooseAppFile: () -> Unit,

    onClearAppFile: () -> Unit,

    onInstallApp: () -> Unit,

    onCloseAppInstaller: () -> Unit,

    onOpenFileManager: () -> Unit,

    onFileManagerNavigate: (String) -> Unit,

    onFileManagerUp: () -> Unit,

    onFileManagerHome: () -> Unit,

    onFileManagerRefresh: () -> Unit,

    onFileManagerSelect: (String?) -> Unit,

    onFileManagerSelectAll: () -> Unit,

    onFileManagerClearSelection: () -> Unit,

    onFileManagerUpload: () -> Unit,

    onFileManagerDownload: () -> Unit,

    onFileManagerNewFolder: (String) -> Unit,

    onFileManagerDelete: () -> Unit,

    onCloseFileManager: () -> Unit,

    onRomMismatchProceed: () -> Unit,

    onRomMismatchCancel: () -> Unit,

    onRestorePick: (RomFlasherStorage.BackupEntry) -> Unit,

    onRestorePickerDismiss: () -> Unit,

    onRestorePickerRefresh: () -> Unit,

    onKernelBootloopYes: () -> Unit,

    onKernelBootloopNo: () -> Unit,

    onKernelBootloopDismiss: () -> Unit,

    onDisableVbmeta: () -> Unit = {},

    onSelectGsiClick: () -> Unit = {},

    onClearGsiClick: () -> Unit = {},

    onSelectCustomVbmetaClick: () -> Unit = {},

    onClearCustomVbmetaClick: () -> Unit = {},

    onToggleGsiDisableVbmeta: (Boolean) -> Unit = {},

    onToggleGsiWipeUserdata: (Boolean) -> Unit = {},

    onToggleGsiRebootFastbootd: (Boolean) -> Unit = {},

    onToggleGsiFreeSuperSpace: (Boolean) -> Unit = {},

    onRebootToFastbootd: () -> Unit = {},

    onFlashGsi: () -> Unit = {}

) {

    var expandedFlashingRomsAndImgOption by remember { mutableStateOf(false) }

    var expandedRebootOption by remember { mutableStateOf(false) }

    var expandedSlotOption by remember { mutableStateOf(false) }

    var expandedUninstallerOption by remember { mutableStateOf(false) }

    var expandedGsiOption by remember { mutableStateOf(false) }

    var expandedPayloadDumperOption by remember { mutableStateOf(false) }

    var expandedAppManagerOption by remember { mutableStateOf(false) }

    var expandedRootOption by remember { mutableStateOf(false) }

    var expandedKernelOption by remember { mutableStateOf(false) }

    var expandedFileManagerOption by remember { mutableStateOf(false) }

    var expandedYouTubeOption by remember { mutableStateOf(false) }

    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 3) {
            if (uiState.fileManagerPath.isBlank()) {
                onOpenFileManager()
            } else if (uiState.isConnected) {
                onFileManagerRefresh()
            }
        }
    }

    // Key Features intro popup (once per app open, unless "Don't show again").

    val context = LocalContext.current

    var showKeyFeatures by remember { mutableStateOf(false) }

    // Destructive action waiting for an explicit confirm (partition flash,

    // sideload, reboot, slot switch). Null = nothing pending.

    var pendingConfirm by remember { mutableStateOf<ConfirmRequest?>(null) }

    // ---- YouTube: latest-upload card (expand to play inline) ----

    var youTubeVideo by remember { mutableStateOf<YouTubeFeed.VideoInfo?>(null) }

    val youTubeScope = rememberCoroutineScope()

    // Pulls the newest upload off the main thread and updates the card. Runs on

    // launch and every time the app comes back to the foreground, so a freshly

    // uploaded video replaces the card automatically.

    val refreshLatestVideo: () -> Unit = {

        youTubeScope.launch {

            val fresh = withContext(Dispatchers.IO) {

                try {

                    YouTubeFeed.refreshLatest(context)

                } catch (_: Throwable) {

                    null

                }

            }

            if (fresh != null) youTubeVideo = fresh

        }

    }

    LaunchedEffect(Unit) {

        AppLanguage.load(context)

        if (shouldShowKeyFeatures(context)) {

            showKeyFeatures = true

        }

        // A recent cache paints instantly; a stale one is skipped so the card never

        // shows yesterday's upload before swapping to the current video.

        youTubeVideo = YouTubeFeed.cachedLatest(context)?.takeIf { YouTubeFeed.cacheIsRecent(context) }

        if (youTubeVideo == null || YouTubeFeed.shouldRefresh(context)) {
            refreshLatestVideo()
        }

    }

    // Auto-update whenever the user comes back to the app (throttled by the cache).

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {

        val observer = LifecycleEventObserver { _, event ->

            if (event == Lifecycle.Event.ON_RESUME && YouTubeFeed.shouldRefresh(context)) {

                refreshLatestVideo()

            }

        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }

    }

    // Any tool window open on top of the dashboard? When one is, the dashboard

    // behind it is blurred and darkened so the focus stays on the open window

    // (blur needs Android 12+; on older builds the dark scrim still separates them).

    val overlayOpen = uiState.showBrickShieldMismatchDialog ||
        uiState.showArbDowngradeDialog ||
        uiState.showBatDialog ||
        uiState.showTerminal ||
        uiState.isPayloadDumperScreenOpen ||
        uiState.showDeviceInfoDialog ||
        uiState.showDebloaterDialog ||
        uiState.showRootWizard ||
        uiState.showZeroTouchRootDialog ||
        uiState.showXiaomiBootDownloader ||
        uiState.showKernelFlasher ||
        uiState.showKernelRootPrompt ||
        uiState.showKernelBootloopPrompt ||
        uiState.showAppInstaller ||
        (uiState.showFileManager && selectedTab != 3) ||
        uiState.showRomMismatchDialog ||
        uiState.showRestorePicker ||
        showKeyFeatures ||
        pendingConfirm != null

    val dockBackdropLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07080C))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    dockBackdropLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(dockBackdropLayer)
                }
        ) {
            // Dynamic Ambient Fluid Canvas (Neon Mint, Apple Azure, Cyber Violet floating orbs)
            AmbientFluidCanvas()

            val bgBlur by androidx.compose.animation.core.animateDpAsState(
                targetValue = if (overlayOpen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 18.dp else 0.dp,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 220,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                ),
                label = "overlayBgBlur"
            )

            Column(

            modifier = Modifier

                .fillMaxSize()

                .statusBarsPadding()

                .then(

                    if (bgBlur > 0.5.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                        Modifier.blur(bgBlur)

                    } else {

                        Modifier

                    }

                )

        ) {

            // FIXED / STICKY TOP SECTION — the title banner and the device status sit inside

            // one simple border line so the top reads as a single unit, clearly separate from

            // the scrolling cards below. The border is inset 8dp and the content padding keeps

            // the title on the same 16dp left edge as the cards, so nothing looks shifted.

            Column(

                modifier = Modifier

                    .fillMaxWidth()

                    .background(Color(0x99080B12))

            ) {

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .liquidGlass(
                            glowColors = listOf(
                                Color(0xFF00E5FF),
                                Color(0xFF7C4DFF),
                                Color(0xFFFF2D9B)
                            ),
                            shape = RoundedCornerShape(24.dp),
                            borderWidth = 1.6.dp
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {

                    Column {

                        // 3D Apple VisionOS Liquid Glass Header (ROM FLASHER + Crown + Red Ribbon + YouTube)
                        TopHeaderBanner()

                        Spacer(modifier = Modifier.height(6.dp))

                        // Frosted specular divider line separating title and device status
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .height(1.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color(0x30FFFFFF),
                                            Color(0x4000E5FF),
                                            Color(0x30FFFFFF),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Cyberpunk Dynamic Island Device Status (embedded: the frame is its border)
                        CyberDeviceStatusCard(uiState, onShowDeviceInfo, embedded = true)

                    }

                }

                Spacer(modifier = Modifier.height(8.dp))

                // Glow line separating the fixed top section from the scrolling content

                Box(

                    modifier = Modifier

                        .fillMaxWidth()

                        .padding(horizontal = 16.dp)

                        .height(1.dp)

                        .background(

                            Brush.horizontalGradient(

                                colors = listOf(

                                    Color.Transparent,

                                    Color(0x6600E5FF),

                                    Color.Transparent

                                )

                            )

                        )

                )

            }

            // TAB CONTENT CONTAINER
            androidx.compose.animation.AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(210, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                    ) + androidx.compose.animation.scaleIn(
                        initialScale = 0.985f,
                        animationSpec = androidx.compose.animation.core.tween(210, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                    )) togetherWith androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(140)
                    )
                },
                label = "mainTabContent",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { tabIndex ->
                when (tabIndex) {
                    0 -> {
                        // Flashing Tab: Cards 1, 2, 3, 5 (Fastboot, Sideload, Partition Images, GSI Flasher, Kernel Flasher)
                        val flashingScrollState = rememberScrollState()
                        var containerTopInRoot by remember { mutableFloatStateOf(0f) }
                        CompositionLocalProvider(
                            LocalMainScrollState provides flashingScrollState,
                            LocalScrollContainerTop provides containerTopInRoot
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(flashingScrollState)
                                    .padding(horizontal = 16.dp)
                                    .onGloballyPositioned { coordinates ->
                                        if (coordinates.isAttached) {
                                            containerTopInRoot = coordinates.boundsInRoot().top
                                        }
                                    }
                            ) {
                            // YouTube card
                            AutoScrollOnExpand(expanded = expandedYouTubeOption) {
                                YouTubeCard(
                                    video = youTubeVideo,
                                    isExpanded = expandedYouTubeOption,
                                    onToggleExpand = {
                                        val opening = !expandedYouTubeOption
                                        expandedYouTubeOption = opening
                                        if (opening && YouTubeFeed.shouldRefresh(context)) {
                                            refreshLatestVideo()
                                        }
                                    },
                                    onOpenInYouTube = { id -> YouTubeFeed.openVideo(context, id) }
                                )
                            }

                            Spacer(modifier = Modifier.height(22.dp))

                            // Error Banner if present
                            if (uiState.errorMessage != null) {
                                ErrorCard(
                                    errorMessage = uiState.errorMessage,
                                    onDismiss = onDismissError
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                            }

                            // Extraction Progress Bar if currently extracting
                            if (uiState.isExtracting) {
                                ExtractionProgressCard(
                                    progress = uiState.extractionProgress,
                                    archiveName = uiState.extractingFileName,
                                    currentFile = uiState.currentExtractingFile,
                                    filesDone = uiState.filesDoneCount,
                                    totalFiles = uiState.totalFilesCount,
                                    onCancel = onCancelExtraction
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                            }

                            // Card 1: Master Option: Flashing Rom's & Img (Hosts Fastboot, Sideload & Custom Images)
                            AutoScrollOnExpand(expanded = expandedFlashingRomsAndImgOption) {
                                FlashingRomsAndImgOptionCard(
                                    uiState = uiState,
                                    isExpanded = expandedFlashingRomsAndImgOption,
                                    onToggleExpand = { expandedFlashingRomsAndImgOption = !expandedFlashingRomsAndImgOption },
                                    onSelectRomFile = onSelectRomFileClick,
                                    onSelectRomFolder = onSelectRomFolderClick,
                                    onSelectExistingFolder = onSelectExistingFolder,
                                    onSelectSideloadFile = onSelectSideloadFileClick,
                                    onClearSelectedFile = onClearSelectedSideloadFile,
                                    onFlashSideload = {
                                        pendingConfirm = ConfirmRequest(
                                            title = tr("Start sideload?", "Sideload shuru karein?"),
                                            message = tr(
                                                "The phone in recovery will flash '${uiState.selectedSideloadFile?.name ?: "the selected zip"}'. Do not unplug the cable until it finishes.",
                                                "Recovery me phone '${uiState.selectedSideloadFile?.name ?: "chuni hui zip"}' flash karega. Khatam hone tak cable mat nikaalo."
                                            ),
                                            confirmLabel = "SIDELOAD",
                                            accent = Color(0xFF64D2FF),
                                            action = onFlashSideloadPackage
                                        )
                                    },
                                    onSelectPartition = onSelectPartition,
                                    onSetDualSlotChecked = onSetDualSlotChecked,
                                    onRunCustomCommand = onRunCustomCommand,
                                    onSelectImageClick = onSelectCustomImageClick,
                                    onClearSelectedImage = onClearSelectedImage,
                                    onBootCustomImage = onBootCustomImage,
                                    onFlashCustomImage = {
                                        val target = uiState.selectedPartition +
                                            if (uiState.isDualSlotChecked) " (both slots)" else ""
                                        pendingConfirm = ConfirmRequest(
                                            title = tr("Flash partition image?", "Partition image flash karein?"),
                                            message = tr(
                                                "This writes '$target' on the phone. Flashing a wrong or corrupt image can brick the device — make sure the file matches your phone.",
                                                "Ye phone par '$target' likh dega. Galat ya corrupt image flash karne se phone brick ho sakta hai — file apne phone ke liye sahi honi chahiye."
                                            ),
                                            confirmLabel = "FLASH",
                                            accent = Color(0xFFBF5AF2),
                                            action = onFlashCustomImage
                                        )
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))

                            // Card 2: GSI Flasher Card
                            AutoScrollOnExpand(expanded = expandedGsiOption) {
                                GsiFlasherOptionCard(
                                    uiState = uiState,
                                    isExpanded = expandedGsiOption,
                                    onToggleExpand = { expandedGsiOption = !expandedGsiOption },
                                    selectedGsiFile = uiState.selectedGsiFile,
                                    onSelectGsiClick = onSelectGsiClick,
                                    onClearGsiClick = onClearGsiClick,
                                    selectedCustomVbmetaFile = uiState.selectedCustomVbmetaFile,
                                    onSelectCustomVbmetaClick = onSelectCustomVbmetaClick,
                                    onClearCustomVbmetaClick = onClearCustomVbmetaClick,
                                    disableVbmeta = uiState.gsiDisableVbmeta,
                                    onToggleDisableVbmeta = onToggleGsiDisableVbmeta,
                                    wipeUserdata = uiState.gsiWipeUserdata,
                                    onToggleWipeUserdata = onToggleGsiWipeUserdata,
                                    freeSuperSpace = uiState.gsiFreeSuperSpace,
                                    onToggleFreeSuperSpace = onToggleGsiFreeSuperSpace,
                                    rebootFastbootd = uiState.gsiRebootFastbootd,
                                    onToggleRebootFastbootd = onToggleGsiRebootFastbootd,
                                    onRebootToFastbootd = onRebootToFastbootd,
                                    onFlashGsi = {
                                        val gsiName = uiState.selectedGsiFile?.name ?: "system.img"
                                        val steps = buildList {
                                            add("system <- $gsiName")
                                            if (uiState.gsiDisableVbmeta) add("disable vbmeta verification")
                                            if (uiState.gsiFreeSuperSpace) add("remove unused dynamic partitions in super")
                                            if (uiState.gsiWipeUserdata) add("WIPE USERDATA (erases all user data)")
                                            if (uiState.gsiRebootFastbootd) add("auto-reboot to fastbootd if needed")
                                        }.joinToString("\n• ")
                                        pendingConfirm = ConfirmRequest(
                                            title = tr("Flash GSI to system?", "GSI system par flash karein?"),
                                            message = tr(
                                                "This will perform:\n• $steps\n\nMake sure this GSI image matches your device architecture and you have a backup.",
                                                "Ye phone par chalega:\n• $steps\n\nPakka karein ki GSI aapke phone ke liye sahi hai aur zaroori data backup hai."
                                            ),
                                            confirmLabel = if (uiState.gsiWipeUserdata) "WIPE & FLASH" else "FLASH GSI",
                                            accent = Color(0xFF6C5CE7),
                                            action = onFlashGsi
                                        )
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))

                            // Card 3: Advanced Reboot Options (Moved to Flashing Tab)
                            AutoScrollOnExpand(expanded = expandedRebootOption) {
                                AdvancedRebootOptionCard(
                                    uiState = uiState,
                                    isExpanded = expandedRebootOption,
                                    onToggleExpand = { expandedRebootOption = !expandedRebootOption },
                                    onExecuteReboot = { modeTarget, command ->
                                        pendingConfirm = ConfirmRequest(
                                            title = tr("Reboot the phone?", "Phone reboot karein?"),
                                            message = tr(
                                                "The phone will now run '$command' over $modeTarget. Nothing gets flashed — it is only a restart.",
                                                "Phone abhi $modeTarget se '$command' chalayega. Kuch flash nahi hota — sirf restart hai."
                                            ),
                                            confirmLabel = "REBOOT",
                                            accent = Color(0xFFFF1744),
                                            action = { onExecuteReboot(modeTarget, command) }
                                        )
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(22.dp))

                            FooterNote()
                            Spacer(modifier = Modifier.height(160.dp))
                        }
                        }
                    }

                    1 -> {
                        // Root Tab: Dedicated Page directly displaying the 3 Root Device features
                        RootPageView(
                            uiState = uiState,
                            onOpenRoot = onOpenZeroTouchRoot,
                            onUninstallRoot = onUninstallRoot,
                            onOpenDownloader = onOpenXiaomiBootDownloader,
                            onDismissError = onDismissError
                        )
                    }

                    2 -> {
                        // Tools Tab: Cards 4, 6, 7, 9
                        val toolsScrollState = rememberScrollState()
                        var toolsContainerTopInRoot by remember { mutableFloatStateOf(0f) }
                        CompositionLocalProvider(
                            LocalMainScrollState provides toolsScrollState,
                            LocalScrollContainerTop provides toolsContainerTopInRoot
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(toolsScrollState)
                                    .padding(horizontal = 16.dp)
                                    .onGloballyPositioned { coordinates ->
                                        if (coordinates.isAttached) {
                                            toolsContainerTopInRoot = coordinates.boundsInRoot().top
                                        }
                                    }
                            ) {
                            Spacer(modifier = Modifier.height(10.dp))

                            // Card 1: Kernel Flasher Card
                            AutoScrollOnExpand(expanded = expandedKernelOption) {
                                KernelOptionCard(
                                    isExpanded = expandedKernelOption,
                                    onToggleExpand = { expandedKernelOption = !expandedKernelOption },
                                    selectedZipName = uiState.kernelZipName,
                                    busy = uiState.kernelBusy,
                                    connected = uiState.isConnected,
                                    autoBackup = uiState.kernelAutoBackup,
                                    onToggleAutoBackup = onToggleKernelAutoBackup,
                                    onBackupKernel = onKernelBackup,
                                    onRestoreKernel = onKernelRestore,
                                    onSelectZip = onSelectKernelZip,
                                    onFlashZip = onFlashKernelZip
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))

                            // Card 2: A/B Slot Manager
                            AutoScrollOnExpand(expanded = expandedSlotOption) {
                                SlotManagerOptionCard(
                                    uiState = uiState,
                                    isExpanded = expandedSlotOption,
                                    onToggleExpand = { expandedSlotOption = !expandedSlotOption },
                                    onRefreshSlot = onRefreshSlot,
                                    onSetActiveSlot = { slot ->
                                        pendingConfirm = ConfirmRequest(
                                            title = tr("Switch active slot?", "Active slot badlein?"),
                                            message = tr(
                                                "The phone will boot from slot '${slot.removePrefix("_").uppercase()}' next time. If that slot has no working ROM, the phone may not boot.",
                                                "Agli baar phone slot '${slot.removePrefix("_").uppercase()}' se boot karega. Us slot me ROM theek na ho to phone boot na ho paye."
                                            ),
                                            confirmLabel = "SWITCH",
                                            accent = Color(0xFF5AC8FA),
                                            action = { onSetActiveSlot(slot) }
                                        )
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))

                            // Card 3: IMEI & EFS Toolkit
                            AutoScrollOnExpand(expanded = expandedUninstallerOption) {
                                ToolsOptionCard(
                                    uiState = uiState,
                                    isExpanded = expandedUninstallerOption,
                                    onToggleExpand = { expandedUninstallerOption = !expandedUninstallerOption },
                                    onStartEfsBackup = onStartEfsBackup,
                                    onCopyEfsBackupToHost = onCopyEfsBackupToHost,
                                    onStartEfsRestore = onStartEfsRestore
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))

                            // Card 4: Payload Dumper
                            AutoScrollOnExpand(expanded = expandedPayloadDumperOption) {
                                PayloadDumperOptionCard(
                                    uiState = uiState,
                                    isExpanded = expandedPayloadDumperOption,
                                    onToggleExpand = { expandedPayloadDumperOption = !expandedPayloadDumperOption },
                                    onSelectPayloadFile = onSelectPayloadFileClick,
                                    onOpenPayloadDumper = onOpenPayloadDumper,
                                    onClearSelectedFile = onClearSelectedPayloadFile
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))

                            // Card 5: Combined App Installer & Debloater
                            AutoScrollOnExpand(expanded = expandedAppManagerOption) {
                                AppManagerOptionCard(
                                    isExpanded = expandedAppManagerOption,
                                    onToggleExpand = { expandedAppManagerOption = !expandedAppManagerOption },
                                    selectedFileName = uiState.appInstallerFileName,
                                    busy = uiState.appInstallerBusy,
                                    connected = uiState.isConnected,
                                    onChooseFile = onChooseAppFile,
                                    onClearFile = onClearAppFile,
                                    onInstall = onInstallApp,
                                    onOpenDebloater = onOpenDebloater
                                )
                            }
                            Spacer(modifier = Modifier.height(160.dp))
                        }
                        }
                    }

                    3 -> {
                        // File Manager Tab: Dedicated Full-Page file browser without terminal
                        FileManagerPageView(
                            path = uiState.fileManagerPath,
                            entries = uiState.fileManagerEntries,
                            loading = uiState.fileManagerLoading,
                            busy = uiState.fileManagerBusy,
                            selected = uiState.fileManagerSelected,
                            progress = uiState.fileManagerProgress,
                            progressLabel = uiState.fileManagerProgressLabel,
                            connected = uiState.isConnected,
                            onNavigate = onFileManagerNavigate,
                            onUp = onFileManagerUp,
                            onHome = onFileManagerHome,
                            onRefresh = onFileManagerRefresh,
                            onSelect = onFileManagerSelect,
                            onSelectAll = onFileManagerSelectAll,
                            onClearSelection = onFileManagerClearSelection,
                            onUpload = onFileManagerUpload,
                            onDownload = onFileManagerDownload,
                            onNewFolder = onFileManagerNewFolder,
                            onDelete = onFileManagerDelete
                        )
                    }
                }
            }
        }
        }

        // Floating Dynamic Island Live Operation Pill (when extracting/flashing in background)
        val activeOpLabel = when {
            uiState.isExtracting -> tr("Extracting ROM...", "ROM extract ho rahi hai...")
            uiState.isFlashingRom -> tr("Flashing ROM...", "ROM flash ho rahi hai...")
            uiState.isSideloadingPackage -> tr("ADB Sideloading...", "ADB Sideload chal raha hai...")
            uiState.isFlashingCustomImage -> tr("Flashing Image...", "Image flash ho rahi hai...")
            uiState.isFlashingGsi -> tr("Flashing GSI...", "GSI flash ho rahi hai...")
            else -> null
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = activeOpLabel != null && !overlayOpen,
            enter = androidx.compose.animation.slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy, stiffness = 380f)
            ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = androidx.compose.animation.core.tween(180)
            ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 6.dp, start = 24.dp, end = 24.dp)
        ) {
            val opShape = RoundedCornerShape(50)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(opShape)
                    .background(Color(0xEE0B101B))
                    .border(1.2.dp, Color(0x9900E5FF), opShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF00E5FF)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = activeOpLabel ?: "",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (uiState.isExtracting && uiState.extractionProgress > 0f) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(uiState.extractionProgress * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF00FF66)
                    )
                }
            }
        }

        // Apple-style Floating Liquid Glass Bottom Dock Bar
        LiquidGlassDockBar(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            backdropLayer = dockBackdropLayer,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )

        // Dim scrim behind any open tool window. Drawn AFTER the dashboard and

        // BEFORE the dialogs, so it darkens the blurred dashboard without ever

        // covering the window itself.

        androidx.compose.animation.AnimatedVisibility(
            visible = overlayOpen,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x73000000))
            )
        }

        // Popup Dialog for detected .bat files

        if (uiState.showBatDialog && uiState.foundBatFiles.isNotEmpty()) {

            BatSelectionDialog(

                batFiles = uiState.foundBatFiles,

                onSelect = onSelectBatScript,

                onDismiss = onDismissBatDialog

            )

        }

        // Brick Shield mismatch warning

        if (uiState.showBrickShieldMismatchDialog) {

            AlertDialog(

                onDismissRequest = onDismissBrickShield,

                containerColor = Color(0xFF1E1F24),

                icon = {

                    Icon(

                        imageVector = Icons.Rounded.Warning,

                        contentDescription = null,

                        tint = Color(0xFFFF5F57),

                        modifier = Modifier.size(32.dp)

                    )

                },

                title = {

                    Text(

                        text = tr("Brick Shield Alert", "ब्रिक शील्ड अलर्ट"),

                        color = Color.White,

                        fontWeight = FontWeight.Bold,

                        fontSize = 17.sp

                    )

                },

                text = {

                    Text(

                        text = uiState.brickShieldMismatchMessage,

                        color = Color(0xFFD9CDB8),

                        fontSize = 13.sp,

                        lineHeight = 19.sp

                    )

                },

                confirmButton = {

                    TextButton(onClick = onDismissBrickShield) {

                        Text(tr("OK", "THEEK HAI"), color = AppleBlue, fontWeight = FontWeight.Bold)

                    }

                }

            )

        }

        // Anti-Rollback downgrade warning

        if (uiState.showArbDowngradeDialog) {

            AlertDialog(

                onDismissRequest = onDismissArbDowngrade,

                containerColor = Color(0xFF1E1F24),

                icon = {

                    Icon(

                        imageVector = Icons.Rounded.Warning,

                        contentDescription = null,

                        tint = Color(0xFFFFB020),

                        modifier = Modifier.size(32.dp)

                    )

                },

                title = {

                    Text(

                        text = tr("Anti-Rollback Warning", "एंटी-रोलबैक चेतावनी"),

                        color = Color.White,

                        fontWeight = FontWeight.Bold,

                        fontSize = 17.sp

                    )

                },

                text = {

                    Text(

                        text = uiState.arbDowngradeMessage,

                        color = Color(0xFFD9CDB8),

                        fontSize = 13.sp,

                        lineHeight = 19.sp

                    )

                },

                confirmButton = {

                    TextButton(onClick = onConfirmArbDowngrade) {

                        Text(tr("CONTINUE ANYWAY", "PHIR BHI FLASH KARO"), color = Color(0xFFFF5F57), fontWeight = FontWeight.Bold)

                    }

                },

                dismissButton = {

                    TextButton(onClick = onDismissArbDowngrade) {

                        Text(tr("CANCEL", "CANCEL"), color = Color(0xFF9AA0AA), fontWeight = FontWeight.Bold)

                    }

                }

            )

        }

        // Interactive Live CMD Terminal Dialog

        if (uiState.showTerminal) {

            TerminalDialog(

                logs = uiState.terminalLogs,

                isWaitingForInput = uiState.isWaitingForInput,

                inputPrompt = uiState.inputPrompt,

                onSendInput = onSendTerminalInput,

                onClose = onCloseTerminal,

                isFlashing = uiState.isFlashingRom,

                onStopFlash = onStopFlash,

                isExtracting = uiState.isExtracting,

                extractionProgress = uiState.extractionProgress,

                extractingFile = uiState.currentExtractingFile

            )

        }

        // Payload Dumper Dialog (Extract partitions from payload.bin)

        if (uiState.isPayloadDumperScreenOpen) {

            PayloadDumperDialog(

                uiState = uiState,

                onTogglePartition = onTogglePayloadPartition,

                onSelectAll = onSelectAllPayloadPartitions,

                onDeselectAll = onDeselectAllPayloadPartitions,

                onStartDump = onStartPayloadDump,

                onVerifyToggle = onPayloadVerifyToggle,

                onStartVerify = onStartPayloadVerify,

                onClose = onClosePayloadDumper

            )

        }

        // Full Device Info Dialog

        if (uiState.showDeviceInfoDialog) {

            DeviceInfoDialog(

                isLoading = uiState.isLoadingDeviceInfo,

                lines = uiState.deviceInfoLines,

                onClose = onCloseDeviceInfo

            )

        }

        // Upgraded Debloater Dialog

        if (uiState.showDebloaterDialog) {

            DebloaterDialog(

                uiState = uiState,

                onFilterChange = onFilterDebloaterApps,

                onToggleApp = onToggleDebloaterApp,

                onClearSelection = onClearDebloaterSelection,

                onRefresh = onRefreshDebloaterApps,

                onAction = onDebloaterAction,

                onCategoryChange = onDebloaterCategoryChange,

                onClose = onCloseDebloater

            )

        }

        // Key Features intro popup (once per app open, unless "Don't show again").

        if (showKeyFeatures) {

            KeyFeaturesDialog(

                onClose = { showKeyFeatures = false },

                onDontShowAgain = {

                    setKeyFeaturesDontShow(context, true)

                    showKeyFeatures = false

                }

            )

        }

        // Confirm sheet for every destructive action (flash / sideload / reboot /

        // slot switch). The action runs only on confirm.

        pendingConfirm?.let { request ->

            ConfirmActionDialog(

                request = request,

                onDismiss = { pendingConfirm = null }

            )

        }

        
        // Zero-Touch Direct Boot Patcher Console
        if (uiState.showZeroTouchRootDialog) {
            ZeroTouchRootDialog(
                uiState = uiState,
                onStart = { targetRoot ->
                    onStartZeroTouchRoot(targetRoot)
                },
                onPickFile = onZeroTouchPickFile,
                onClose = onCloseZeroTouchRoot
            )
        }

        if (uiState.showXiaomiBootDownloader) {
            XiaomiBootDownloaderDialog(
                show = uiState.showXiaomiBootDownloader,
                searchQuery = uiState.xiaomiSearchQuery,
                selectedDevice = uiState.xiaomiSelectedDevice,
                selectedRegion = uiState.xiaomiSelectedRegion,
                versions = uiState.xiaomiRomVersions,
                versionsLoading = uiState.xiaomiRomVersionsLoading,
                downloading = uiState.xiaomiDownloading,
                downloadProgress = uiState.xiaomiDownloadProgress,
                downloadStatus = uiState.xiaomiDownloadStatus,
                downloadedFiles = uiState.xiaomiDownloadedFiles,
                errorMessage = uiState.xiaomiDownloaderError,
                detectedCodename = uiState.deviceCodeName.ifBlank { null },
                onSearchChange = onXiaomiSearchChange,
                onSelectDevice = onXiaomiSelectDevice,
                onSelectRegion = onXiaomiSelectRegion,
                onBackToDeviceList = onXiaomiBackToDeviceList,
                onBackToRegionList = onXiaomiBackToRegionList,
                onStartDownload = onXiaomiStartDownload,
                onUseForRoot = onXiaomiUseForRoot,
                onDismiss = onXiaomiDismissDownloader
            )
        }

        // Kernel Flasher console (backup / restore / zip flash + reboot countdown)

        if (uiState.showKernelFlasher) {

            KernelFlasherDialog(

                logs = uiState.kernelLogs,

                busy = uiState.kernelBusy,

                rebootSeconds = uiState.kernelRebootSeconds,

                onCancelReboot = onKernelCancelReboot,

                onRebootNow = onKernelRebootNow,

                onClose = onCloseKernelFlasher

            )

        }

        // "Root chahiye" prompt — hands off to the Root Wizard.

        if (uiState.showKernelRootPrompt) {

            KernelRootPromptDialog(

                onOpenRootWizard = onKernelOpenRootWizard,

                onDismiss = onCloseKernelFlasher

            )

        }

        // App Installer console (any app file -> target phone)

        if (uiState.showAppInstaller) {

            AppInstallerDialog(

                logs = uiState.appInstallerLogs,

                busy = uiState.appInstallerBusy,

                fileName = uiState.appInstallerFileName,

                onClose = onCloseAppInstaller

            )

        }

        // File Manager (browse + push/pull files between host and target)

        if (uiState.showFileManager && selectedTab != 3) {

            FileManagerDialog(

                path = uiState.fileManagerPath,

                entries = uiState.fileManagerEntries,

                loading = uiState.fileManagerLoading,

                busy = uiState.fileManagerBusy,

                selected = uiState.fileManagerSelected,

                logs = uiState.fileManagerLogs,

                progress = uiState.fileManagerProgress,

                progressLabel = uiState.fileManagerProgressLabel,

                onNavigate = onFileManagerNavigate,

                onUp = onFileManagerUp,

                onHome = onFileManagerHome,

                onRefresh = onFileManagerRefresh,

                onSelect = onFileManagerSelect,

                onSelectAll = onFileManagerSelectAll,

                onClearSelection = onFileManagerClearSelection,

                onUpload = onFileManagerUpload,

                onDownload = onFileManagerDownload,

                onNewFolder = onFileManagerNewFolder,

                onDelete = onFileManagerDelete,

                onClose = onCloseFileManager

            )

        }

        // ROM mismatch confirmation (backup ROM != current ROM on restore)

        if (uiState.showRomMismatchDialog) {

            RomMismatchDialog(

                backupRom = uiState.romMismatchBackup,

                currentRom = uiState.romMismatchCurrent,

                onProceed = onRomMismatchProceed,

                onCancel = onRomMismatchCancel

            )

        }

        // Restore picker: which backup do you want to restore? (IMEI/EFS or kernel)

        if (uiState.showRestorePicker) {

            RestorePickerDialog(

                isKernel = uiState.restorePickerKind == RomFlasherStorage.KIND_KERNEL,

                entries = uiState.restorePickerEntries,

                scanned = uiState.restorePickerScanned,

                currentDeviceModel = uiState.deviceModel,

                onPick = onRestorePick,

                onRefresh = onRestorePickerRefresh,

                onDismiss = onRestorePickerDismiss

            )

        }

        // Kernel restore: ask whether the phone boot-looped before choosing the restore path.

        if (uiState.showKernelBootloopPrompt) {

            KernelBootloopDialog(

                backupName = uiState.kernelBootloopBackupLabel,

                onYes = onKernelBootloopYes,

                onNo = onKernelBootloopNo,

                onDismiss = onKernelBootloopDismiss

            )

        }

    }

}

/**

 * Small closing note pinned to the bottom of the scroll list: how to reach the dev about a

 * bug/feature request, plus a gentle nudge to donate.

 */

@Composable
private fun FooterNote() {
    var showMyProjectsDialog by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        // Left: Star on GitHub
        GitHubStarPill(
            modifier = Modifier.weight(1.05f),
            label = "Star on GitHub"
        )

        // Middle: My Projects (with GitHub icon + live GitHub sync popup)
        MyProjectsPill(
            modifier = Modifier.weight(1.0f),
            onClick = { showMyProjectsDialog = true }
        )

        // Right: Guides
        WebsitePill(
            modifier = Modifier.weight(0.75f),
            label = "Guides"
        )
    }

    if (showMyProjectsDialog) {
        MyProjectsDialog(onDismiss = { showMyProjectsDialog = false })
    }
}

/**
 * Wraps an expandable card so that, the moment it expands, the scroll container animates it into
 * view. Fixes the "card expands below the fold and I can't see it" problem for cards near the
 * bottom of the list: with the default bring-into-view spec a card taller than the viewport is
 * scrolled so its *top* lands at the top of the viewport, so the freshly-expanded content is
 * LocalMainScrollState, LocalScrollContainerTop, and AutoScrollOnExpand now live in Expandable.kt
 * so every current and future expandable card shares the same adaptive frame-by-frame scroll.
 */

