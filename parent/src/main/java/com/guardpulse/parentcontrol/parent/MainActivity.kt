package com.guardpulse.parentcontrol.parent

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.shared.ControlProtocol
import com.guardpulse.parentcontrol.shared.DeviceFreshness
import com.guardpulse.parentcontrol.shared.PinHasher
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ConfirmAction(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val destructive: Boolean = false,
    val onConfirm: () -> Unit
)

class MainActivity : ComponentActivity() {
    private val syncViewModel: ParentSyncViewModel by viewModels()
    private lateinit var qrScanLauncher: ActivityResultLauncher<ScanOptions>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.parseColor("#F5F7FB")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
            val payload = result.contents
            if (!payload.isNullOrBlank()) {
                syncViewModel.createPairRequest(payload, "", "")
            } else {
                syncViewModel.createPairRequest("", "", "")
            }
        }

        setContent {
            val state by syncViewModel.state.collectAsStateWithLifecycle()
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
                            onRescan = { syncViewModel.sendCommand(PolicyConstants.COMMAND_RESCAN_APPS) },
                            onOpenTvSetup = { syncViewModel.sendCommand(PolicyConstants.COMMAND_OPEN_SETUP) },
                            onResetToday = { packageName ->
                                syncViewModel.sendCommand(PolicyConstants.COMMAND_RESET_TODAY, packageName)
                            },
                            onReconnect = syncViewModel::reconnect,
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

private val GuardNavy = Color(0xFF031636)
private val GuardNavySoft = Color(0xFF1A2B4C)
private val ActionBlue = Color(0xFF316BF3)
private val SurfaceLight = Color(0xFFF8F9FF)
private val SurfaceCard = Color(0xFFFFFFFF)
private val SurfaceTint = Color(0xFFE5EEFF)
private val OutlineSoft = Color(0xFFC5C6CF)
private val TextMuted = Color(0xFF44474E)
private val AlertRed = Color(0xFFBA1A1A)
private val ErrorSoft = Color(0xFFFFDAD6)
private val SuccessGreen = Color(0xFF10B981)
private val SuccessSoft = Color(0xFFDCFCE7)

@Composable
private fun MissingFirebaseScreen(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceLight)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        GuardCard {
            ShieldMark()
            Spacer(Modifier.height(18.dp))
            Text(
                "Firebase not configured",
                style = MaterialTheme.typography.headlineSmall,
                color = GuardNavy,
                fontWeight = FontWeight.Bold
            )
            Text(message, color = TextMuted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun AuthScreen(
    message: String?,
    busy: Boolean,
    onSignIn: (String, String) -> Unit,
    onCreate: (String, String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceLight)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ShieldMark()
            Text(
                "GuardPulse",
                style = MaterialTheme.typography.headlineLarge,
                color = GuardNavy,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 18.dp)
            )
            Text(
                "Sign in to manage paired TVs.",
                color = TextMuted,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 28.dp)
            )
            GuardCard {
                GuardTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "Email",
                    placeholder = "name@example.com",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    leading = { Icon(Icons.Outlined.Security, contentDescription = null, tint = TextMuted) }
                )
                Spacer(Modifier.height(14.dp))
                GuardTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    placeholder = "Password",
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    leading = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = TextMuted) },
                    trailing = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Hide" else "Show")
                        }
                    }
                )
                Button(
                    enabled = !busy,
                    onClick = { onSignIn(email, password) },
                    colors = ButtonDefaults.buttonColors(containerColor = GuardNavy),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .height(54.dp)
                ) {
                    Text(if (busy) "Working..." else "Sign In")
                    Spacer(Modifier.width(8.dp))
                    Text(">")
                }
                TextButton(
                    enabled = !busy,
                    onClick = { onCreate(email, password) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("Create Account", color = ActionBlue)
                }
            }
            Text(
                message ?: "Connect to your Firebase project to get started.",
                color = TextMuted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 22.dp)
            )
        }
    }
}

@Composable
private fun ShieldMark() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(GuardNavySoft),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.Security, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
    }
}

@Composable
private fun GuardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(modifier) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(placeholder) },
            leadingIcon = leading,
            trailingIcon = trailing,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GuardCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, OutlineSoft.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), content = content)
    }
}

@Composable
private fun GuardSectionTitle(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = GuardNavy)
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceTint)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun StatusPill(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    val bg = if (ok) SuccessSoft else ErrorSoft
    val fg = if (ok) Color(0xFF166534) else AlertRed
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        Text(label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetaTile(label: String, value: String, ok: Boolean? = null, modifier: Modifier = Modifier) {
    val tileColor = if (ok == true) SuccessSoft else SurfaceLight
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tileColor)
            .border(1.dp, if (ok == true) SuccessGreen.copy(alpha = 0.25f) else OutlineSoft.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(label.uppercase(Locale.US), color = TextMuted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(value, color = if (ok == true) Color(0xFF065F46) else GuardNavy, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun EmptyPanel(title: String, detail: String) {
    GuardCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Icon(Icons.Outlined.Tv, contentDescription = null, tint = OutlineSoft, modifier = Modifier.size(48.dp))
            Text(title, color = GuardNavy, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text(detail, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun ConfirmDialog(
    action: ConfirmAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title, color = GuardNavy, fontWeight = FontWeight.Bold) },
        text = { Text(action.body, color = TextMuted) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (action.destructive) AlertRed else ActionBlue
                )
            ) {
                Text(action.confirmLabel)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = SurfaceCard
    )
}

@Composable
private fun TopBar(selectedDeviceId: String?, onSignOut: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceLight)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("GuardPulse", style = MaterialTheme.typography.headlineSmall, color = GuardNavy, fontWeight = FontWeight.Bold)
            Text(selectedDeviceId ?: "No TV selected", color = TextMuted, style = MaterialTheme.typography.labelMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}) { Icon(Icons.Outlined.Security, contentDescription = "Protection", tint = GuardNavy) }
            IconButton(onClick = {}) { Icon(Icons.Outlined.Lock, contentDescription = "Settings", tint = GuardNavy) }
            TextButton(onClick = onSignOut) { Text("Sign out", color = AlertRed) }
        }
    }
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("Devices", "Apps", "Security", "Events")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceTint)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (active) ActionBlue else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = when (index) {
                        0 -> Icons.Outlined.Tv
                        1 -> Icons.Outlined.Add
                        2 -> Icons.Outlined.Security
                        else -> Icons.Outlined.Lock
                    },
                    contentDescription = label,
                    tint = if (active) Color.White else TextMuted
                )
                Text(label, color = if (active) Color.White else GuardNavy, style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
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
                    onReconnect
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

@Composable
private fun DevicesTab(
    devices: List<ParentDevice>,
    loadingDevices: Boolean,
    selectedDeviceId: String?,
    onSelectDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    pairRequest: PairRequestState?,
    onPair: (String, String, String) -> Unit,
    onScanQr: () -> Unit
) {
    var payload by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var codeDigits by remember { mutableStateOf(List(6) { "" }) }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(18.dp)
    ) {
        item {
            GuardSectionTitle("TV Control")
            val selected = devices.firstOrNull { it.deviceId == selectedDeviceId }
            selected?.let {
                SelectedDeviceBanner(it)
            }
        }
        item {
            GuardCard {
                Text("Pair New TV", style = MaterialTheme.typography.titleLarge, color = GuardNavy, fontWeight = FontWeight.Bold)
                Text("Scan the QR code displayed on your TV or enter details manually.", color = TextMuted, modifier = Modifier.padding(top = 8.dp))
                pairRequest?.let { request ->
                    StatusLabel(
                        "Pairing ${request.status}",
                        when (request.status) {
                            PolicyConstants.PAIR_ACCEPTED -> SuccessGreen
                            PolicyConstants.PAIR_PENDING -> ActionBlue
                            else -> AlertRed
                        },
                        Modifier.padding(top = 12.dp)
                    )
                }
                Button(
                    onClick = onScanQr,
                    colors = ButtonDefaults.buttonColors(containerColor = GuardNavy),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan QR Code")
                }
                Text("OR MANUAL ENTRY", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                GuardTextField(payload, { payload = it }, "QR payload", "guardpulse://pair?deviceId=...")
                Spacer(Modifier.height(12.dp))
                GuardTextField(deviceId, { deviceId = it }, "Device ID", "e.g. TV-9A8B7C")
                Text("6-Digit Code", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    codeDigits.forEachIndexed { index, digit ->
                        if (index == 3) Text("-", color = TextMuted)
                        OutlinedTextField(
                            value = digit,
                            onValueChange = { value ->
                                codeDigits = codeDigits.toMutableList().also { it[index] = value.filter(Char::isDigit).take(1) }
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(42.dp)
                        )
                    }
                }
                Button(
                    onClick = { onPair(payload, deviceId, codeDigits.joinToString("")) },
                    colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp)
                ) { Text("Connect Manually") }
            }
        }
        if (loadingDevices) {
            item {
                EmptyPanel("Loading TVs", "Reading paired TVs from Firebase...")
            }
        } else if (devices.isEmpty()) {
            item {
                EmptyPanel("No TVs paired", "Pair the TV using the QR payload or manual code shown on the TV app.")
            }
        }
        if (devices.isNotEmpty()) {
            item { GuardSectionTitle("Paired Devices", "${devices.count { it.online }} Active") }
            items(devices) { device ->
                DeviceCard(
                    device = device,
                    selected = device.deviceId == selectedDeviceId,
                    onSelectDevice = onSelectDevice,
                    onRemoveDevice = onRemoveDevice
                )
            }
        }
    }
}

@Composable
private fun SelectedDeviceBanner(device: ParentDevice) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GuardNavy)
            .padding(18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Security, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(device.label, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(device.deviceId, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        }
        StatusPill(if (device.online) "Online" else "Offline", device.online)
    }
}

@Composable
private fun DeviceCard(
    device: ParentDevice,
    selected: Boolean,
    onSelectDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit
) {
    GuardCard(
        modifier = Modifier
            .border(if (selected) 2.dp else 1.dp, if (selected) GuardNavy else OutlineSoft, RoundedCornerShape(14.dp))
            .clickable { onSelectDevice(device.deviceId) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(GuardNavy), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(device.label, style = MaterialTheme.typography.titleLarge, color = GuardNavy, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.deviceId, color = TextMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                StatusPill("Active", true, Modifier.weight(1f))
            } else {
                OutlinedButton(
                    onClick = { onSelectDevice(device.deviceId) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Select")
                }
            }
            OutlinedButton(
                onClick = { onRemoveDevice(device.deviceId) },
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, AlertRed)
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, tint = AlertRed)
                Spacer(Modifier.width(6.dp))
                Text("Remove", color = AlertRed)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetaTile("Mode", device.enforcementMode, device.enforcementMode != PolicyConstants.ENFORCEMENT_UNPROTECTED, Modifier.weight(1f))
            MetaTile("Health", if (device.protectionHealthy) "Healthy" else "Needs setup", device.protectionHealthy, Modifier.weight(1f))
        }
        MetaTile("Last seen", formatTimestamp(device.lastSeen), device.online, Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

private fun defaultParentPolicy(packageName: String): ParentPolicy {
    return if (PolicyConstants.isDefaultLocked(packageName)) {
        ParentPolicy(manualBlocked = true)
    } else {
        ParentPolicy()
    }
}

@Composable
private fun AppsTab(
    selectedDevice: ParentDevice?,
    selectedDeviceId: String?,
    loadingDeviceDetails: Boolean,
    apps: Map<String, ParentApp>,
    policies: Map<String, ParentPolicy>,
    states: Map<String, ParentState>,
    confirmedStates: Map<String, ParentState>,
    syncState: ParentSyncUiState,
    serverNow: Long,
    onUpdatePolicy: (String, ParentPolicy) -> Unit,
    onRescan: () -> Unit,
    onResetToday: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = apps.values
        .filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        .sortedBy { it.label.lowercase() }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(18.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Managing Device", color = TextMuted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Tv, contentDescription = null, tint = TextMuted)
                        Spacer(Modifier.width(8.dp))
                        Text(selectedDevice?.label ?: selectedDeviceId ?: "No TV selected", color = GuardNavy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Button(onClick = onRescan, colors = ButtonDefaults.buttonColors(containerColor = ActionBlue), shape = RoundedCornerShape(50)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Rescan")
                }
            }
        }
        if (selectedDeviceId == null) {
            item {
                EmptyPanel("No TV selected", "Select or pair a TV before managing apps.")
            }
            return@LazyColumn
        }
        item {
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("Search apps...") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
            )
        }
        if (loadingDeviceDetails) {
            item {
                EmptyPanel("Loading apps", "Waiting for the TV to upload its app list.")
            }
            return@LazyColumn
        }
        if (apps.isEmpty()) {
            item {
                EmptyPanel("No apps yet", "Start Sync Service or Rescan Installed Apps on the TV.")
            }
            return@LazyColumn
        }
        items(filtered) { app ->
            val policy = policies[app.packageName] ?: defaultParentPolicy(app.packageName)
            val liveState = states[app.packageName] ?: ParentState()
            val confirmedState = if (syncState.confirmedControl == null) {
                liveState
            } else {
                confirmedStates[app.packageName] ?: ParentState()
            }
            val pending = syncState.isAppPolicyPending(app.packageName)
            val requestedPolicy = syncState.desiredControl?.apps?.get(app.packageName)?.let { rule ->
                ParentPolicy(rule.manualBlocked, rule.dailyLimitMinutes)
            }
            AppPolicyCard(
                app,
                policy,
                confirmedState,
                liveState,
                pending,
                requestedPolicy,
                serverNow,
                onUpdatePolicy,
                onResetToday
            )
        }
    }
}

@Composable
private fun AppPolicyCard(
    app: ParentApp,
    policy: ParentPolicy,
    state: ParentState,
    usageState: ParentState,
    pending: Boolean,
    requestedPolicy: ParentPolicy?,
    serverNow: Long,
    onUpdatePolicy: (String, ParentPolicy) -> Unit,
    onResetToday: (String) -> Unit
) {
    val usageMs = effectiveUsageMs(usageState, serverNow)
    var limitText by remember(app.packageName, policy.dailyLimitMinutes) {
        mutableStateOf(policy.dailyLimitMinutes?.toString().orEmpty())
    }
    var expanded by remember(app.packageName) { mutableStateOf(false) }
    val sourceApp = app.packageName in PolicyConstants.sourceLockPackages
    val settingsApp = app.packageName in PolicyConstants.primarySettingsPackages
    val settingsSection = PolicyConstants.settingsSectionPolicy(app.packageName)
    val settingsSectionApp = settingsSection != null
    val settingsSectionName = settingsSection?.shortLabel ?: "Settings section"
    val lockControlled = app.blockable
    val networkBlocked = false
    val runtimeConfirmed = state.controlRevisionId != null
    val lockBlocked = if (runtimeConfirmed) {
        state.lockBlocked || (!app.blockable && state.fallbackLocked)
    } else {
        (lockControlled && (policy.manualBlocked || state.manualBlocked || state.dailyLimitBlocked)) ||
            (!app.blockable && state.fallbackLocked)
    }
    val sourceLocked = sourceApp && lockBlocked
    val settingsLocked = settingsApp && lockBlocked
    val settingsSectionsLocked = settingsSectionApp && lockBlocked
    val blocked = networkBlocked || lockBlocked
    val statusLabel = when {
        pending -> "Waiting for TV"
        !app.blockable -> "Protected"
        sourceLocked -> "Live TV locked"
        settingsSectionsLocked -> "$settingsSectionName locked"
        settingsLocked -> "Settings locked"
        sourceApp -> "Live TV allowed"
        settingsSectionApp -> "$settingsSectionName allowed"
        settingsApp -> "Settings allowed"
        lockBlocked -> "App locked"
        state.dailyLimitBlocked -> "Daily limit lock"
        else -> "App allowed"
    }
    val statusColor = when {
        pending -> ActionBlue
        !app.blockable -> OutlineSoft
        blocked -> AlertRed
        else -> ActionBlue
    }
    GuardCard(modifier = Modifier.clickable { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(5.dp).height(86.dp).clip(RoundedCornerShape(4.dp)).background(statusColor))
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(
                    when {
                        !app.blockable -> SurfaceTint
                        blocked -> ErrorSoft
                        else -> SurfaceTint
                    }
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (!app.blockable) Icons.Outlined.Lock else Icons.Outlined.Tv,
                    contentDescription = null,
                    tint = if (blocked) AlertRed else GuardNavy
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(
                    app.label,
                    color = GuardNavy,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusLabel(
                    statusLabel,
                    when {
                        !app.blockable -> OutlineSoft
                        blocked -> AlertRed
                        else -> ActionBlue
                    },
                    modifier = Modifier.padding(top = 5.dp)
                )
                Text(
                    app.packageName,
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
                val reason = when {
                    pending && requestedPolicy?.manualBlocked == true -> "Lock requested; TV confirmation pending"
                    pending -> "Unlock or limit change requested; TV confirmation pending"
                    !app.blockable -> "Reason: ${app.protectedReason ?: "System critical"}"
                    sourceLocked && state.dailyLimitBlocked -> "Daily limit source lock"
                    sourceLocked && policy.manualBlocked -> "Live TV source locked by parent"
                    sourceLocked -> "Live TV source locked"
                    settingsSectionsLocked && policy.manualBlocked -> "$settingsSectionName locked by parent"
                    settingsSectionsLocked -> "$settingsSectionName locked"
                    settingsLocked && state.dailyLimitBlocked -> "Daily limit settings lock"
                    settingsLocked && policy.manualBlocked -> "Settings locked by parent"
                    settingsLocked -> "Settings locked"
                    lockBlocked && state.dailyLimitBlocked -> "Daily limit lock"
                    lockBlocked && policy.manualBlocked -> "Locked by parent"
                    lockBlocked -> "App locked"
                    state.dailyLimitBlocked -> "Daily limit lock"
                    policy.manualBlocked -> "Locked by parent"
                    policy.dailyLimitMinutes != null -> "Daily Limit Active (${policy.dailyLimitMinutes} mins)"
                    else -> null
                }
                reason?.let {
                    Text(
                        it,
                        color = if (blocked) AlertRed else TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Switch(
                enabled = app.blockable && !pending,
                checked = !policy.manualBlocked,
                onCheckedChange = { allowed -> onUpdatePolicy(app.packageName, policy.copy(manualBlocked = !allowed)) }
            )
        }
        if (usageMs > 0 || state.dailyLimitBlocked) {
            Row(
                modifier = Modifier
                    .padding(start = 75.dp, top = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (state.dailyLimitBlocked) ErrorSoft else SurfaceTint)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Security, contentDescription = null, tint = if (state.dailyLimitBlocked) AlertRed else TextMuted)
                Spacer(Modifier.width(8.dp))
                Text("${formatUsage(usageMs)} used today", color = if (state.dailyLimitBlocked) AlertRed else TextMuted)
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceLight)
                    .padding(14.dp)
            ) {
                Text(app.packageName, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusChip(
                        when {
                            sourceLocked && policy.manualBlocked -> "Source locked by parent"
                            sourceApp -> if (policy.manualBlocked) "Source locked by parent" else "Source allowed by parent"
                            settingsSectionApp -> if (policy.manualBlocked) "$settingsSectionName locked by parent" else "$settingsSectionName allowed by parent"
                            settingsApp -> if (policy.manualBlocked) "Settings locked by parent" else "Settings allowed by parent"
                            policy.manualBlocked -> "Locked by parent"
                            else -> "Allowed by parent"
                        },
                        !policy.manualBlocked,
                        Modifier.weight(1f)
                    )
                    StatusChip(
                        when {
                            sourceLocked -> "Source lock active"
                            settingsSectionsLocked -> "$settingsSectionName lock active"
                            settingsLocked -> "Settings lock active"
                            lockBlocked -> "Screen lock active"
                            else -> "No screen lock"
                        },
                        !lockBlocked && (!networkBlocked || state.vpnApplied),
                        Modifier.weight(1f)
                    )
                }
                StatusChip(
                    "Mode: ${state.enforcementMode}",
                    state.enforcementMode != PolicyConstants.ENFORCEMENT_UNPROTECTED,
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        limitText,
                        { limitText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Daily Limit") },
                        suffix = { Text("min") },
                        enabled = app.blockable && !pending,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(enabled = app.blockable && !pending, onClick = {
                        onUpdatePolicy(app.packageName, policy.copy(dailyLimitMinutes = limitText.toIntOrNull()?.takeIf { it > 0 }))
                    }, colors = ButtonDefaults.buttonColors(containerColor = GuardNavy)) { Text("Save") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = app.blockable && !pending && policy.dailyLimitMinutes != null, onClick = {
                        limitText = ""
                        onUpdatePolicy(app.packageName, policy.copy(dailyLimitMinutes = null))
                    }) { Text("Clear") }
                }
                TextButton(enabled = app.blockable && !pending, onClick = { onResetToday(app.packageName) }) { Text("Reset today") }
                state.lastError?.let { Text("Error: $it", color = AlertRed) }
            }
        }
    }
}

@Composable
private fun StatusLabel(label: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        label.uppercase(Locale.US),
        color = if (color == OutlineSoft) TextMuted else color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (color == OutlineSoft) SurfaceTint else color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun SyncHealthCard(state: ParentSyncUiState, onReconnect: () -> Unit) {
    val selectedDevice = state.devices.firstOrNull { it.deviceId == state.selectedDeviceId }
    val protocolReady = state.syncRuntime.protocolVersion >= PolicyConstants.SYNC_PROTOCOL_VERSION
    val tvConnected = if (protocolReady) state.syncRuntime.connected else selectedDevice?.online == true
    val freshness = ControlProtocol.freshness(tvConnected, selectedDevice?.lastSeen, state.serverNow)
    val desired = state.desiredRevision
    val applied = state.appliedRevision
    val syncStatus = when {
        !state.phoneConnected -> ParentSyncStatus.SENDING
        state.controlV2Exists && !protocolReady -> ParentSyncStatus.TV_UPDATE_REQUIRED
        desired?.revisionId != null && applied.revisionId == desired.revisionId &&
            applied.status == PolicyConstants.SYNC_STATUS_FAILED -> ParentSyncStatus.FAILED
        desired?.revisionId != null && applied.revisionId != desired.revisionId &&
            freshness == DeviceFreshness.OFFLINE -> ParentSyncStatus.OFFLINE_PENDING
        desired?.revisionId != null && applied.revisionId != desired.revisionId &&
            freshness == DeviceFreshness.DELAYED -> ParentSyncStatus.DELAYED
        desired?.revisionId != null && applied.revisionId != desired.revisionId -> ParentSyncStatus.WAITING_FOR_TV
        desired?.revisionId != null && applied.revisionId == desired.revisionId -> ParentSyncStatus.APPLIED
        freshness == DeviceFreshness.DELAYED -> ParentSyncStatus.DELAYED
        freshness == DeviceFreshness.OFFLINE -> ParentSyncStatus.IDLE
        else -> ParentSyncStatus.IDLE
    }
    val statusText = if (!state.phoneConnected) {
        if (desired?.revisionId != null && applied.revisionId != desired.revisionId) {
            "Phone offline - writes queued"
        } else {
            "Phone offline"
        }
    } else when (syncStatus) {
        ParentSyncStatus.SENDING -> "Phone offline - writes queued"
        ParentSyncStatus.WAITING_FOR_TV -> "Waiting for TV"
        ParentSyncStatus.APPLIED -> "Applied"
        ParentSyncStatus.DELAYED -> "TV connection delayed"
        ParentSyncStatus.OFFLINE_PENDING -> "TV offline - change pending"
        ParentSyncStatus.FAILED -> "TV rejected latest change"
        ParentSyncStatus.TV_UPDATE_REQUIRED -> "TV update required"
        else -> when (freshness) {
            DeviceFreshness.LIVE -> "Synchronized"
            DeviceFreshness.DELAYED -> "TV connection delayed"
            DeviceFreshness.OFFLINE -> "TV offline"
        }
    }
    val healthy = state.phoneConnected && (syncStatus == ParentSyncStatus.APPLIED ||
        (syncStatus == ParentSyncStatus.IDLE && freshness == DeviceFreshness.LIVE)
    )
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Synchronization", style = MaterialTheme.typography.titleLarge, color = GuardNavy, fontWeight = FontWeight.Bold)
                StatusLabel(
                    statusText,
                    when {
                        healthy -> SuccessGreen
                        syncStatus == ParentSyncStatus.WAITING_FOR_TV || syncStatus == ParentSyncStatus.DELAYED -> ActionBlue
                        else -> AlertRed
                    },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            IconButton(onClick = onReconnect) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Reconnect", tint = ActionBlue)
            }
        }
        RuntimeRow("Phone Firebase", if (state.phoneConnected) "Connected" else "Offline", state.phoneConnected)
        RuntimeRow(
            "TV connection",
            when (freshness) {
                DeviceFreshness.LIVE -> "Live"
                DeviceFreshness.DELAYED -> "Delayed"
                DeviceFreshness.OFFLINE -> "Offline"
            },
            freshness == DeviceFreshness.LIVE
        )
        RuntimeRow("Sync protocol", if (protocolReady) "V2" else "Legacy", protocolReady)
        state.syncRuntime.lastPolicyAppliedAt?.let {
            RuntimeRow("Policy applied", formatTimestamp(it), true)
        }
        state.syncRuntime.lastUsageWriteAt?.let {
            RuntimeRow("Usage updated", formatTimestamp(it), true)
        }
        state.syncRuntime.lastInventoryWriteAt?.let {
            RuntimeRow("Inventory updated", formatTimestamp(it), true)
        }
        state.commands.firstOrNull()?.let { command ->
            RuntimeRow(
                "Latest command",
                "${command.type}: ${command.status}",
                command.status == PolicyConstants.COMMAND_DONE
            )
            command.error?.let { Text(it, color = AlertRed, modifier = Modifier.padding(top = 6.dp)) }
        }
        val currentRuntimeError = state.syncRuntime.lastError.takeIf {
            (state.syncRuntime.lastErrorAt ?: 0L) >= (state.syncRuntime.lastSuccessAt ?: 0L)
        }
        val error = applied.error ?: currentRuntimeError
        error?.let { Text(it, color = AlertRed, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
private fun SecurityTab(
    selectedDeviceId: String?,
    loadingDeviceDetails: Boolean,
    apps: Map<String, ParentApp>,
    states: Map<String, ParentState>,
    modes: List<ParentMode>,
    activeMode: ActiveMode,
    safeMode: SafeModeState,
    security: SecurityRuntime,
    unlockRequests: List<UnlockRequest>,
    syncState: ParentSyncUiState,
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
    onConfirmAction: (String, String, String, Boolean, () -> Unit) -> Unit,
    onOpenTvSetup: () -> Unit,
    onReconnect: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var newModeName by remember { mutableStateOf("") }
    var expandedModeId by remember(modes) {
        mutableStateOf(modes.firstOrNull { it.modeId == activeMode.modeId }?.modeId)
    }
    var safeModeCustomMinutes by remember { mutableStateOf("") }
    val safeModeActive = safeMode.isActive(syncState.serverNow)
    val safeModeUntil = safeMode.until
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(18.dp)) {
        item {
            Text("Security Settings", style = MaterialTheme.typography.headlineSmall, color = GuardNavy, fontWeight = FontWeight.Bold)
            Text("Manage protection layers and review pending requests.", color = TextMuted, modifier = Modifier.padding(top = 4.dp))
        }
        if (selectedDeviceId == null) {
            item {
                EmptyPanel("No TV selected", "Select or pair a TV before changing security settings.")
            }
            return@LazyColumn
        }
        if (loadingDeviceDetails) {
            item {
                EmptyPanel("Loading security", "Reading TV protection health from Firebase...")
            }
        }
        item {
            GuardCard {
                Text("Protection Health", style = MaterialTheme.typography.titleLarge, color = GuardNavy, fontWeight = FontWeight.Bold)
                RuntimeRow("Enforcement Mode", security.enforcementMode, security.enforcementMode != PolicyConstants.ENFORCEMENT_UNPROTECTED)
                RuntimeRow(if (security.deviceAdminSetupAvailable) "Device Admin" else "Device Admin unavailable", if (security.deviceAdmin) "Active" else if (!security.deviceAdminSetupAvailable) "Unavailable" else "Needs setup", security.deviceAdmin || !security.deviceAdminSetupAvailable)
                RuntimeRow("Accessibility", if (security.accessibility) "Active" else "Needs action", security.accessibility)
                RuntimeRow("Usage Access", if (security.usageAccess) "Active" else "Needs action", security.usageAccess)
                RuntimeRow(
                    "Network Filter",
                    "Not required for app locks",
                    true
                )
                RuntimeRow(
                    "Background Access",
                    if (security.backgroundUnrestricted) "Unrestricted" else "Battery restricted",
                    security.backgroundUnrestricted
                )
                RuntimeRow("PIN", if (security.pinConfigured) "Configured" else "Missing", security.pinConfigured)
                if (security.pinConfigured && security.pinHashVersion in 0 until PinHasher.CURRENT_VERSION) {
                    Text(
                        "PIN security upgrade required. Set a new PIN below to upgrade protection.",
                        color = AlertRed,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                RuntimeRow("Healthy", if (security.protectionHealthy) "Healthy" else "Needs setup", security.protectionHealthy)
                RuntimeRow("Active Mode", activeMode.modeName ?: "Normal policy", activeMode.modeId != null)
                RuntimeRow(
                    "Safe Mode",
                    if (safeModeActive) "Active until ${formatTimestamp(safeModeUntil)}" else "Off",
                    !safeModeActive
                )
                if (security.enforcementMode == PolicyConstants.ENFORCEMENT_FALLBACK) {
                    Text(
                        "Fallback mode protects via Accessibility and PIN gate. It is not uninstall-proof.",
                        color = GuardNavy,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceTint)
                            .padding(14.dp)
                    )
                }
                security.lastSyncError?.let { Text("Last sync error: $it", color = AlertRed, modifier = Modifier.padding(top = 10.dp)) }
                security.lastForegroundPackage?.let { Text("Last foreground: $it", color = TextMuted, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        item {
            SyncHealthCard(syncState, onReconnect)
        }
        item {
            GuardCard {
                Text("Emergency Safe Mode", style = MaterialTheme.typography.titleLarge, color = GuardNavy, fontWeight = FontWeight.Bold)
                Text(
                    if (safeModeActive) {
                        "All app, Live TV, Settings, and protected Settings-section locks are paused until ${formatTimestamp(safeModeUntil)}."
                    } else {
                        "Pause all TV PIN locks for a chosen duration without disabling sync, inventory, or health reporting."
                    },
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (safeModeActive) {
                    Button(
                        onClick = {
                            onConfirmAction(
                                "Deactivate Safe Mode?",
                                "TV app, Live TV, Settings, and Settings-section locks will resume immediately.",
                                "Deactivate",
                                true,
                                onStopSafeMode
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(52.dp)
                    ) {
                        Icon(Icons.Outlined.Security, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Deactivate Safe Mode")
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 120).forEach { minutes ->
                            OutlinedButton(
                                onClick = {
                                    onConfirmAction(
                                        "Start Safe Mode?",
                                        "All TV PIN locks will pause for $minutes minutes, until ${formatTimestamp(syncState.serverNow + minutes * 60_000L)}.",
                                        "Start",
                                        true
                                    ) { onStartSafeMode(minutes) }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("${minutes}m")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            safeModeCustomMinutes,
                            { safeModeCustomMinutes = it.filter(Char::isDigit).take(4) },
                            label = { Text("Custom minutes") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val minutes = safeModeCustomMinutes.toIntOrNull()
                                if (minutes != null && minutes in 1..1440) {
                                    onConfirmAction(
                                        "Start Safe Mode?",
                                        "All TV PIN locks will pause for $minutes minutes, until ${formatTimestamp(syncState.serverNow + minutes * 60_000L)}.",
                                        "Start",
                                        true
                                    ) { onStartSafeMode(minutes) }
                                }
                            },
                            enabled = safeModeCustomMinutes.toIntOrNull()?.let { it in 1..1440 } == true,
                            colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                            modifier = Modifier.height(58.dp)
                        ) {
                            Text("Start")
                        }
                    }
                    Text("Custom duration must be 1 to 1440 minutes.", color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            ModesCard(
                apps = apps,
                states = states,
                serverNow = syncState.serverNow,
                modes = modes,
                activeMode = activeMode,
                expandedModeId = expandedModeId,
                newModeName = newModeName,
                onNewModeNameChange = { newModeName = it },
                onCreateMode = {
                    onCreateMode(newModeName)
                    newModeName = ""
                },
                onToggleMode = { modeId -> expandedModeId = if (expandedModeId == modeId) null else modeId },
                onRenameMode = onRenameMode,
                onDeleteMode = { mode ->
                    onConfirmAction(
                        "Delete ${mode.name}?",
                        "This permanently removes the mode and its app rules.",
                        "Delete",
                        true
                    ) {
                        if (expandedModeId == mode.modeId) expandedModeId = null
                        onDeleteMode(mode.modeId)
                    }
                },
                onSetActiveMode = onSetActiveMode,
                onUpdateModePolicy = onUpdateModePolicy
            )
        }
        item {
            GuardCard {
                Text("TV Setup Access", style = MaterialTheme.typography.titleLarge, color = GuardNavy, fontWeight = FontWeight.Bold)
                Text("Open the hidden setup screen on the selected TV. The TV will require the parent PIN before showing setup.", color = TextMuted, modifier = Modifier.padding(top = 6.dp))
                Button(
                    onClick = onOpenTvSetup,
                    colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(52.dp)
                ) {
                    Icon(Icons.Outlined.Tv, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open TV Setup")
                }
            }
        }
        item {
            GuardCard {
                Text("Parent PIN", style = MaterialTheme.typography.titleLarge, color = GuardNavy, fontWeight = FontWeight.Bold)
                if (security.pinConfigured && security.pinHashVersion in 0 until PinHasher.CURRENT_VERSION) {
                    Text(
                        "Your existing PIN still works, but it uses legacy hashing. Set it again to upgrade security.",
                        color = AlertRed,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { index ->
                        Box(
                            Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceLight).border(1.dp, OutlineSoft, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (pin.length > index || security.pinConfigured) "*" else "", color = GuardNavy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                GuardTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    label = "New 6-digit PIN",
                    placeholder = "Enter PIN",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.padding(top = 14.dp)
                )
                Button(
                    onClick = {
                        onConfirmAction(
                            if (security.pinConfigured) "Change parent PIN?" else "Set parent PIN?",
                            "This PIN controls the TV lock wall and protected setup access.",
                            if (security.pinConfigured) "Change PIN" else "Set PIN",
                            false
                        ) { onSetPin(pin) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GuardNavy),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(52.dp)
                ) {
                    Icon(Icons.Outlined.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (security.pinConfigured) "Change PIN" else "Set PIN")
                }
            }
        }
        item { GuardSectionTitle("Pending Requests") }
        items(unlockRequests.filter {
            it.status == PolicyConstants.UNLOCK_PENDING &&
                (it.expiresAt == null || System.currentTimeMillis() <= it.expiresAt)
        }) { request ->
            GuardCard(modifier = Modifier.border(2.dp, ActionBlue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))) {
                val appLabel = apps[request.packageName]?.label ?: request.packageName
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(AlertRed), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Tv, contentDescription = null, tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(appLabel, color = GuardNavy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(request.packageName, color = TextMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(request.reason, color = AlertRed)
                        Text("Age: ${formatAge(request.createdAt)}", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                        Text("Requested: ${formatTimestamp(request.createdAt)}", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                        Text("Expires: ${formatTimestamp(request.expiresAt)}", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                        Text("Approval: ${unlockApprovalLabel(request)}", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onDenyUnlock(request) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Deny")
                    }
                    Button(
                        onClick = {
                            onApproveUnlock(
                                request,
                                PolicyConstants.UNLOCK_APPROVAL_ONE_VISIT,
                                null
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("One Visit")
                    }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            onApproveUnlock(
                                request,
                                PolicyConstants.UNLOCK_APPROVAL_TIMED,
                                PolicyConstants.UNLOCK_15_MINUTES_MS
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GuardNavy),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("15 Minutes")
                    }
                    Button(
                        onClick = {
                            onApproveUnlock(
                                request,
                                PolicyConstants.UNLOCK_APPROVAL_TIMED,
                                PolicyConstants.UNLOCK_30_MINUTES_MS
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GuardNavy),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("30 Minutes")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModesCard(
    apps: Map<String, ParentApp>,
    states: Map<String, ParentState>,
    serverNow: Long,
    modes: List<ParentMode>,
    activeMode: ActiveMode,
    expandedModeId: String?,
    newModeName: String,
    onNewModeNameChange: (String) -> Unit,
    onCreateMode: () -> Unit,
    onToggleMode: (String) -> Unit,
    onRenameMode: (String, String) -> Unit,
    onDeleteMode: (ParentMode) -> Unit,
    onSetActiveMode: (ParentMode?) -> Unit,
    onUpdateModePolicy: (String, String, ParentPolicy) -> Unit
) {
    GuardCard {
        Text("One-Tap Modes", style = MaterialTheme.typography.titleLarge, color = GuardNavy, fontWeight = FontWeight.Bold)
        Text("Create named policy sets. When a mode is active, listed apps use the mode rules and unlisted apps are allowed.", color = TextMuted, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GuardTextField(
                value = newModeName,
                onValueChange = onNewModeNameChange,
                label = "New mode name",
                placeholder = "Study time",
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onCreateMode,
                colors = ButtonDefaults.buttonColors(containerColor = GuardNavy),
                modifier = Modifier.height(58.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Create")
            }
        }
        if (modes.isEmpty()) {
            Text("No custom modes yet.", color = TextMuted, modifier = Modifier.padding(top = 14.dp))
            return@GuardCard
        }
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            modes.forEach { mode ->
                ModeSummaryRow(
                    apps = apps,
                    states = states,
                    serverNow = serverNow,
                    mode = mode,
                    expanded = expandedModeId == mode.modeId,
                    active = activeMode.modeId == mode.modeId,
                    onToggleMode = onToggleMode,
                    onRenameMode = onRenameMode,
                    onDeleteMode = onDeleteMode,
                    onSetActiveMode = onSetActiveMode,
                    onUpdateModePolicy = onUpdateModePolicy
                )
            }
        }
    }
}

@Composable
private fun ModeSummaryRow(
    apps: Map<String, ParentApp>,
    states: Map<String, ParentState>,
    serverNow: Long,
    mode: ParentMode,
    expanded: Boolean,
    active: Boolean,
    onToggleMode: (String) -> Unit,
    onRenameMode: (String, String) -> Unit,
    onDeleteMode: (ParentMode) -> Unit,
    onSetActiveMode: (ParentMode?) -> Unit,
    onUpdateModePolicy: (String, String, ParentPolicy) -> Unit
) {
    var renameText by remember(mode.modeId, mode.name) { mutableStateOf(mode.name) }
    val lockedCount = mode.appPolicies.values.count { it.manualBlocked }
    val limitCount = mode.appPolicies.values.count { it.dailyLimitMinutes != null }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) SurfaceTint else SurfaceLight)
            .border(1.dp, if (active) ActionBlue.copy(alpha = 0.45f) else OutlineSoft, RoundedCornerShape(10.dp))
            .clickable { onToggleMode(mode.modeId) }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onToggleMode(mode.modeId) }
                    .padding(end = 10.dp)
            ) {
                Text(mode.name, color = GuardNavy, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${lockedCount} locked · ${limitCount} limits",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            StatusLabel(if (expanded) "Open" else "Closed", if (expanded) ActionBlue else OutlineSoft)
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = active,
                onCheckedChange = { enabled ->
                    onSetActiveMode(if (enabled) mode else null)
                }
            )
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GuardTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = "Mode name",
                    placeholder = "Mode name",
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { onRenameMode(mode.modeId, renameText) }, colors = ButtonDefaults.buttonColors(containerColor = GuardNavy), modifier = Modifier.height(58.dp)) {
                    Text("Save")
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onDeleteMode(mode) }, modifier = Modifier.weight(1f)) {
                    Text("Delete")
                }
            }
            Text("Mode App Rules", color = GuardNavy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            apps.values
                .filter { it.blockable }
                .sortedBy { it.label.lowercase() }
                .forEach { app ->
                    ModeAppPolicyRow(
                        modeId = mode.modeId,
                        app = app,
                        policy = mode.appPolicies[app.packageName] ?: ParentPolicy(),
                        usageMsToday = modeUsageMs(app.packageName, states, serverNow),
                        onUpdateModePolicy = onUpdateModePolicy
                    )
                }
        }
    }
}

@Composable
private fun ModeAppPolicyRow(
    modeId: String,
    app: ParentApp,
    policy: ParentPolicy,
    usageMsToday: Long,
    onUpdateModePolicy: (String, String, ParentPolicy) -> Unit
) {
    var limitText by remember(modeId, app.packageName, policy.dailyLimitMinutes) {
        mutableStateOf(policy.dailyLimitMinutes?.toString().orEmpty())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceLight)
            .padding(12.dp)
    ) {
        val limitReached = policy.dailyLimitMinutes?.let { usageMsToday >= it * 60_000L } == true
        val usageLabel = policy.dailyLimitMinutes?.let { limit ->
            "${formatUsage(usageMsToday)} / $limit mins used today"
        } ?: "${formatUsage(usageMsToday)} used today"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(app.label, color = GuardNavy, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, color = TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(
                checked = !policy.manualBlocked,
                onCheckedChange = { allowed ->
                    onUpdateModePolicy(modeId, app.packageName, policy.copy(manualBlocked = !allowed))
                }
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (limitReached) ErrorSoft else SurfaceTint)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Security,
                contentDescription = null,
                tint = if (limitReached) AlertRed else TextMuted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                usageLabel,
                color = if (limitReached) AlertRed else TextMuted,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                limitText,
                { limitText = it.filter(Char::isDigit).take(4) },
                label = { Text("Mode daily limit") },
                suffix = { Text("min") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    onUpdateModePolicy(
                        modeId,
                        app.packageName,
                        policy.copy(dailyLimitMinutes = limitText.toIntOrNull()?.takeIf { it > 0 })
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = GuardNavy)
            ) {
                Text("Save")
            }
            OutlinedButton(
                enabled = policy.dailyLimitMinutes != null,
                onClick = {
                    limitText = ""
                    onUpdateModePolicy(modeId, app.packageName, policy.copy(dailyLimitMinutes = null))
                }
            ) {
                Text("Clear")
            }
        }
    }
}

private fun effectiveUsageMs(state: ParentState, serverNow: Long): Long {
    if (!state.foregroundActive) return state.usageMsToday.coerceAtLeast(0L)
    val capturedAt = state.usageCapturedAt ?: return state.usageMsToday.coerceAtLeast(0L)
    val elapsed = (serverNow - capturedAt)
        .coerceIn(0L, PolicyConstants.FOREGROUND_USAGE_EXTRAPOLATION_MAX_MS)
    return (state.usageMsToday + elapsed).coerceAtLeast(0L)
}

private fun formatUsage(usageMs: Long): String {
    val totalSeconds = usageMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun modeUsageMs(packageName: String, states: Map<String, ParentState>, serverNow: Long): Long {
    if (packageName in PolicyConstants.settingsSectionLockPackages) {
        return PolicyConstants.primarySettingsPackages.maxOfOrNull { settingsPackage ->
            states[settingsPackage]?.let { effectiveUsageMs(it, serverNow) } ?: 0L
        } ?: 0L
    }
    return states[packageName]?.let { effectiveUsageMs(it, serverNow) } ?: 0L
}

@Composable
private fun RuntimeRow(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = GuardNavy)
        StatusPill(value, ok)
    }
}

@Composable
private fun EventsTab(tamperEvents: List<TamperEvent>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(18.dp)) {
        item {
            Text("Events", style = MaterialTheme.typography.headlineSmall, color = GuardNavy, fontWeight = FontWeight.Bold)
            Text("Tamper and protection events from the selected TV.", color = TextMuted, modifier = Modifier.padding(top = 4.dp))
        }
        if (tamperEvents.isEmpty()) {
            item {
                EmptyPanel("No events", "Tamper and protection events will appear here.")
            }
        }
        items(tamperEvents) { event ->
            val critical = event.type.contains("disabled", ignoreCase = true) || event.type.contains("risky", ignoreCase = true)
            GuardCard(modifier = Modifier.border(1.dp, if (critical) AlertRed.copy(alpha = 0.35f) else OutlineSoft, RoundedCornerShape(14.dp))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(if (critical) ErrorSoft else SurfaceTint), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Security, contentDescription = null, tint = if (critical) AlertRed else GuardNavy)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(event.type.ifBlank { "Event" }, color = GuardNavy, fontWeight = FontWeight.Bold)
                        Text(event.message ?: "No details", color = TextMuted)
                        Text("Time: ${formatTimestamp(event.createdAt)}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                if (ok) Icons.Outlined.Check else Icons.Outlined.Security,
                contentDescription = null
            )
        },
        modifier = modifier
    )
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    GuardCard {
        Text(title, style = MaterialTheme.typography.titleMedium, color = GuardNavy, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

private fun formatTimestamp(value: Long?): String {
    if (value == null || value <= 0L) return "unknown"
    return SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(Date(value))
}

private fun formatAge(value: Long?): String {
    if (value == null || value <= 0L) return "unknown"
    val minutes = ((System.currentTimeMillis() - value).coerceAtLeast(0L) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "just now"
        minutes == 1L -> "1 min"
        minutes < 60L -> "$minutes mins"
        else -> "${minutes / 60L}h ${minutes % 60L}m"
    }
}

private fun unlockApprovalLabel(request: UnlockRequest): String {
    if (request.status == PolicyConstants.UNLOCK_PENDING) return "waiting"
    return when (request.approvalType) {
        PolicyConstants.UNLOCK_APPROVAL_TIMED -> "${(request.approvalDurationMs ?: 0L) / 60_000L} minutes"
        PolicyConstants.UNLOCK_APPROVAL_ONE_VISIT, null -> "one visit"
        else -> request.approvalType
    }
}
