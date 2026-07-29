package com.guardpulse.parentcontrol.parent

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val syncViewModel: ParentSyncViewModel by viewModels()
    private lateinit var qrScanLauncher: ActivityResultLauncher<ScanOptions>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.parseColor("#F5F7FB")
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
            val payload = result.contents
            if (!payload.isNullOrBlank()) {
                syncViewModel.createPairRequest(payload, "", "")
            } else {
                syncViewModel.createPairRequest("", "", "")
            }
        }

        setContent {
            val state by syncViewModel.state.collectAsStateWithLifecycle(
                lifecycleOwner = this@MainActivity
            )
            ParentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!state.configured) {
                        MissingFirebaseScreen(state.firebaseMessage.orEmpty())
                    } else if (!state.signedIn) {
                        AuthScreen(
                            message = state.message,
                            busy = state.authBusy,
                            onSignIn = syncViewModel::signIn,
                            onCreate = syncViewModel::createAccount
                        )
                    } else {
                        ParentDashboard(
                            message = state.message,
                            devices = state.devices,
                            loadingDevices = state.loadingDevices,
                            selectedDeviceId = state.selectedDeviceId,
                            apps = state.apps,
                            policies = state.policies,
                            states = state.states,
                            modes = state.modes,
                            activeMode = state.activeMode,
                            safeMode = state.safeMode,
                            security = state.security,
                            unlockRequests = state.unlockRequests,
                            tamperEvents = state.tamperEvents,
                            syncState = state,
                            loadingDeviceDetails = state.loadingDeviceDetails,
                            onSignOut = syncViewModel::signOut,
                            onSelectDevice = syncViewModel::selectDevice,
                            onRemoveDevice = syncViewModel::removePairedDevice,
                            onPair = syncViewModel::createPairRequest,
                            onUpdatePolicy = syncViewModel::updatePolicy,
                            onSetPin = syncViewModel::setPin,
                            onApproveUnlock = { request, approvalType, durationMs ->
                                syncViewModel.updateUnlock(
                                    request,
                                    PolicyConstants.UNLOCK_APPROVED,
                                    approvalType,
                                    durationMs
                                )
                            },
                            onDenyUnlock = { request ->
                                syncViewModel.updateUnlock(request, PolicyConstants.UNLOCK_DENIED)
                            },
                            onCreateMode = syncViewModel::createMode,
                            onRenameMode = syncViewModel::renameMode,
                            onDeleteMode = syncViewModel::deleteMode,
                            onUpdateModePolicy = syncViewModel::updateModePolicy,
                            onSetActiveMode = syncViewModel::setActiveMode,
                            onStartSafeMode = syncViewModel::startSafeMode,
                            onStopSafeMode = syncViewModel::stopSafeMode,
                            onRescan = {
                                syncViewModel.sendCommand(PolicyConstants.COMMAND_RESCAN_APPS)
                            },
                            onOpenTvSetup = {
                                syncViewModel.sendCommand(PolicyConstants.COMMAND_OPEN_SETUP)
                            },
                            onResetToday = { packageName ->
                                syncViewModel.sendCommand(
                                    PolicyConstants.COMMAND_RESET_TODAY,
                                    packageName
                                )
                            },
                            onReconnect = syncViewModel::reconnect,
                            onRepairControl = syncViewModel::repairControlV2,
                            onScanQr = ::openExternalQrScanner
                        )
                    }
                }
            }
        }
    }

    private fun openExternalQrScanner() {
        qrScanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setCaptureActivity(PortraitCaptureActivity::class.java)
                setPrompt("Scan the GuardPulse TV pairing QR")
                setBeepEnabled(false)
                setOrientationLocked(true)
            }
        )
    }
}

@Composable
private fun ParentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = GuardNavy,
            onPrimary = Color.White,
            primaryContainer = GuardNavySoft,
            secondary = ActionBlue,
            onSecondary = Color.White,
            secondaryContainer = ActionBlue,
            onSecondaryContainer = Color.White,
            tertiary = AlertRed,
            surface = SurfaceLight,
            background = SurfaceLight,
            error = AlertRed,
            errorContainer = ErrorSoft
        ),
        content = content
    )
}

@Composable
private fun ParentDashboard(
    message: String?,
    devices: List<ParentDevice>,
    loadingDevices: Boolean,
    selectedDeviceId: String?,
    apps: Map<String, ParentApp>,
    policies: Map<String, ParentPolicy>,
    states: Map<String, ParentState>,
    modes: List<ParentMode>,
    activeMode: ActiveMode,
    safeMode: SafeModeState,
    security: SecurityRuntime,
    unlockRequests: List<UnlockRequest>,
    tamperEvents: List<TamperEvent>,
    syncState: ParentSyncUiState,
    loadingDeviceDetails: Boolean,
    onSignOut: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onPair: (String, String, String) -> Unit,
    onUpdatePolicy: (String, ParentPolicy) -> Unit,
    onSetPin: (String) -> Unit,
    onApproveUnlock: (UnlockRequest, String, Long?) -> Unit,
    onDenyUnlock: (UnlockRequest) -> Unit,
    onCreateMode: (String) -> Unit,
    onRenameMode: (String, String) -> Unit,
    onDeleteMode: (String) -> Unit,
    onUpdateModePolicy: (String, String, ParentPolicy) -> Unit,
    onSetActiveMode: (ParentMode?) -> Unit,
    onStartSafeMode: (Int) -> Unit,
    onStopSafeMode: () -> Unit,
    onRescan: () -> Unit,
    onOpenTvSetup: () -> Unit,
    onResetToday: (String) -> Unit,
    onReconnect: () -> Unit,
    onRepairControl: () -> Unit,
    onScanQr: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedDevice = devices.firstOrNull { it.deviceId == selectedDeviceId }
    val confirm: (String, String, String, Boolean, () -> Unit) -> Unit = { title, body, label, destructive, action ->
        confirmAction = ConfirmAction(title, body, label, destructive, action)
    }
    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
        }
    }
    Scaffold(
        containerColor = SurfaceLight,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopBar(selectedDevice?.label ?: selectedDeviceId) {
                confirm(
                    "Sign out?",
                    "You will need to sign in again before managing this TV.",
                    "Sign out",
                    true,
                    onSignOut
                )
            }
        },
        bottomBar = { BottomNav(tab) { tab = it } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> DevicesTab(
                    devices,
                    loadingDevices,
                    selectedDeviceId,
                    onSelectDevice,
                    { deviceId ->
                        val label = devices.firstOrNull { it.deviceId == deviceId }?.label ?: deviceId
                        confirm(
                            "Remove paired TV?",
                            "This removes $label from the parent account and sends an unpair command to the TV.",
                            "Remove",
                            true
                        ) { onRemoveDevice(deviceId) }
                    },
                    syncState.pairRequest,
                    onPair,
                    onScanQr
                )
                1 -> AppsTab(
                    selectedDevice,
                    selectedDeviceId,
                    loadingDeviceDetails,
                    apps,
                    policies,
                    states,
                    syncState.confirmedStates,
                    syncState,
                    syncState.serverNow,
                    onUpdatePolicy,
                    onRescan,
                    { packageName ->
                        val label = apps[packageName]?.label ?: packageName
                        confirm(
                            "Reset today's limit?",
                            "This clears today's daily-limit lock and usage offset for $label.",
                            "Reset today",
                            false
                        ) { onResetToday(packageName) }
                    }
                )
                2 -> SecurityTab(
                    selectedDeviceId,
                    loadingDeviceDetails,
                    apps,
                    states,
                    modes,
                    activeMode,
                    safeMode,
                    security,
                    unlockRequests,
                    syncState,
                    onSetPin,
                    onApproveUnlock,
                    onDenyUnlock,
                    onCreateMode,
                    onRenameMode,
                    onDeleteMode,
                    onUpdateModePolicy,
                    { mode ->
                        confirm(
                            if (mode == null) "Disable active mode?" else "Activate ${mode.name}?",
                            if (mode == null) {
                                "The TV will return to normal per-app policies."
                            } else {
                                "The TV will immediately apply this mode's app locks and limits."
                            },
                            if (mode == null) "Disable" else "Activate",
                            false
                        ) { onSetActiveMode(mode) }
                    },
                    onStartSafeMode,
                    onStopSafeMode,
                    confirm,
                    onOpenTvSetup,
                    onReconnect,
                    onRepairControl
                )
                3 -> EventsTab(tamperEvents)
            }
            confirmAction?.let { action ->
                ConfirmDialog(
                    action = action,
                    onDismiss = { confirmAction = null },
                    onConfirm = {
                        val pending = confirmAction ?: return@ConfirmDialog
                        confirmAction = null
                        pending.onConfirm()
                    }
                )
            }
        }
    }
}
