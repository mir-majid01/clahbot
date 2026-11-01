package com.clahbot.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

import com.clahbot.BuildConfig
import com.clahbot.R
import com.clahbot.data.AttackRepository
import com.clahbot.data.BotState
import com.clahbot.data.CaptureSource
import com.clahbot.data.MacroShare
import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.LootInput
import com.clahbot.data.ControllerState
import com.clahbot.data.MacroRepository
import com.clahbot.data.SessionRepository
import com.clahbot.data.SettingsStore
import com.clahbot.session.GameSessionTracker
import com.clahbot.ui.dashboard.AcEmptyCard
import com.clahbot.ui.dashboard.AcGameCard
import com.clahbot.ui.dashboard.AcHeader
import com.clahbot.ui.dashboard.AcLibraryActions
import com.clahbot.ui.dashboard.AcSelectionActionBar
import com.clahbot.ui.dashboard.AcUndoBar
import com.clahbot.ui.dashboard.acScrollFade
import com.clahbot.ui.dashboard.MacroSettingsSheet
import com.clahbot.ui.dashboard.AcMacroRow
import com.clahbot.ui.dashboard.AcSectionHeader
import com.clahbot.ui.dashboard.AcSetupCard
import com.clahbot.ui.dashboard.AcSessionBox
import com.clahbot.ui.dashboard.AcSeeAllHistory
import com.clahbot.ui.dashboard.AcStatisticsHeader
import com.clahbot.ui.dashboard.AcTotalsCard
import com.clahbot.ui.dashboard.AcTotalsMeta
import com.clahbot.ui.dashboard.ReviewBanner
import com.clahbot.ui.dashboard.acClock
import com.clahbot.ui.dashboard.acDate
import com.clahbot.ui.dashboard.historySubtitle
import com.clahbot.ui.dashboard.scopedAttacks
import com.clahbot.ui.dashboard.HistoryScreen
import com.clahbot.results.ResultsRescan
import com.clahbot.data.models.Macro
import com.clahbot.data.models.MacroSettingsDraft
import com.clahbot.data.models.SessionAggregator
import com.clahbot.execution.MacroTimeline
import com.clahbot.service.FloatingMenuService
import com.clahbot.ui.theme.AutoClashTheme
import com.clahbot.ui.theme.ElectricBlue
import com.clahbot.ui.theme.Hairline
import com.clahbot.ui.theme.PauseAmber
import com.clahbot.ui.theme.ReadyGreen
import com.clahbot.ui.theme.RecordRed
import com.clahbot.ui.theme.SurfaceRaised
import com.clahbot.ui.theme.TextMuted
import com.clahbot.util.PermissionHelper
import com.clahbot.util.Plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    private val historyRequests = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val permissionRefresh = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(MacroRepository.get(applicationContext)) as T
            }
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        GameSessionTracker.start(this)
        val startWithHistory = intent?.getBooleanExtra(EXTRA_OPEN_HISTORY, false) == true

        setContent {
            val settings by SettingsStore.get(this).settings.collectAsState()
            val historyRequest by historyRequests.collectAsState()
            val permissionsTick by permissionRefresh.collectAsState()
            AutoClashTheme(settings.theme) {
                DashboardScreen(
                    viewModel = viewModel,
                    startWithHistory = startWithHistory,
                    historyRequest = historyRequest,
                    permissionRefresh = permissionsTick,
                    onStartController = { startController() },
                    onPlayMacro = { macro -> playMacro(macro) },
                    onShareMacro = { macro -> shareMacro(macro) },
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onRequestNotificationPermission = { requestNotificationsIfNeeded() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_HISTORY, false)) {
            historyRequests.value++
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        permissionRefresh.value++
    }

    private fun startController() {
        if (!PermissionHelper.canDrawOverlays(this)) {
            PermissionHelper.requestOverlayPermission(this)
            PermissionHelper.showToast(this, "Allow 'Display over other apps', then try again")
            return
        }
        if (!PermissionHelper.isAccessibilityEnabled(this)) {
            PermissionHelper.openAccessibilitySettings(this)
            PermissionHelper.showToast(this, "Enable the AutoClash accessibility service, then try again")
            return
        }
        requestNotificationsIfNeeded()
        FloatingMenuService.show(this)
    }

    private fun playMacro(macro: Macro) {
        BotState.setSelectedMacroId(macro.id)
        if (!PermissionHelper.isAccessibilityEnabled(this)) {
            PermissionHelper.showToast(this, "Enable the AutoClash accessibility service first")
            PermissionHelper.openAccessibilitySettings(this)
            return
        }
        if (!PermissionHelper.canDrawOverlays(this)) {
            PermissionHelper.showToast(this, "Overlay permission is required to control playback")
            PermissionHelper.requestOverlayPermission(this)
            return
        }
        requestNotificationsIfNeeded()
        FloatingMenuService.play(this, macro.id)
    }

    private fun shareMacro(macro: Macro) {
        val file = runCatching {
            MacroShare.writeExport(this, macro, BuildConfig.VERSION_NAME)
        }.getOrNull()
        if (file == null) {
            PermissionHelper.showToast(this, "Could not prepare the macro file")
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.updates", file)
        }.getOrNull()
        if (uri == null) {
            PermissionHelper.showToast(this, "Could not share the macro file")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AutoClash macro: ${macro.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("AutoClash macro", uri)
        }
        try {
            startActivity(Intent.createChooser(send, "Share ${macro.name}"))
        } catch (_: ActivityNotFoundException) {
            PermissionHelper.showToast(this, "No app can share the macro file")
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionHelper.canPostNotifications(this)
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_HISTORY = "open_history"
    }
}

private const val CLASH_PACKAGE = "com.supercell.clashofclans"
private const val CLASH_MARKET = "market://details?id=$CLASH_PACKAGE"
private const val CLASH_STORE = "https://play.google.com/store/apps/details?id=$CLASH_PACKAGE"

private val CLASH_CANDIDATES = listOf(
    "com.supercell.clashofclans",
    "com.supercell.clashofclans.overseas",
    "com.supercell.clashofclans.kunlun",
    "com.supercell.clashofclans.google"
)

private fun installedClashPackage(packageManager: PackageManager): String? {
    for (pkg in CLASH_CANDIDATES) {
        val info = try {
            packageManager.getApplicationInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (info != null) return pkg
    }
    return null
}

private data class PermissionProbe(
    val overlay: Boolean,
    val accessibility: PermissionHelper.AccessibilityStatus,
    val notifications: Boolean,
    val game: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    startWithHistory: Boolean = false,
    historyRequest: Int = 0,
    permissionRefresh: Int = 0,
    onStartController: () -> Unit,
    onPlayMacro: (Macro) -> Unit,
    onShareMacro: (Macro) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attackRepository = remember { AttackRepository.get(context) }
    val sessionRepository = remember { SessionRepository.get(context) }
    val attacks by attackRepository.records.collectAsState()
    val sessions by sessionRepository.sessions.collectAsState()
    val macros by viewModel.macros.collectAsState()
    val selectedId by BotState.selectedMacroId.collectAsState()
    val controllerState by BotState.state.collectAsState()

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val nowProvider = remember { { now } }
    var overlayOk by remember { mutableStateOf(false) }
    var accessibilityStatus by remember {
        mutableStateOf(PermissionHelper.AccessibilityStatus.DISABLED)
    }
    var notificationsOk by remember { mutableStateOf(false) }
    var gamePackage by remember { mutableStateOf<String?>(null) }
    var settingsMacro by remember { mutableStateOf<Macro?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedMacros by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showSelectionDelete by remember { mutableStateOf(false) }
    var deletingMacroFromSheet by remember { mutableStateOf<Macro?>(null) }
    var undoing by remember { mutableStateOf<List<Macro>?>(null) }
    var undoBackupName by remember { mutableStateOf<String?>(null) }
    var showStoreDialog by remember { mutableStateOf(false) }
    var lootEditing by remember { mutableStateOf<AttackRecord?>(null) }
    var deletingAttack by remember { mutableStateOf<AttackRecord?>(null) }
    var rescanning by remember { mutableStateOf(false) }
    var rescanStatus by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var statsScope by remember { mutableStateOf(0) }
    var showHistory by remember { mutableStateOf(startWithHistory) }
    var reviewBannerDismissed by remember { mutableStateOf(false) }
    var lastReviewCount by remember { mutableStateOf(0) }
    var expandedStatsSession by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(historyRequest) {
        if (historyRequest > 0) showHistory = true
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                val shared = if (bytes != null && bytes.size <= MacroShare.MAX_IMPORT_BYTES) {
                    MacroShare.decode(String(bytes, Charsets.UTF_8))
                } else {
                    null
                }
                val imported = shared?.let { viewModel.importShared(it) }
                importing = false
                Toast.makeText(
                    context,
                    if (imported != null) {
                        "Imported macro: ${imported.name}"
                    } else {
                        "Not an AutoClash macro file"
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        attackRepository.refresh()
        sessionRepository.refresh()
        viewModel.refresh()
        val active = sessionRepository.sessions.value.firstOrNull { it.active }
        statsScope = if ((active?.attackCount ?: 0) > 0) 0 else 1
    }

    LaunchedEffect(Unit) {
        while (true) {
            // Session cards show whole minutes; a 15 s tick only rebuilt the
            // dashboard without changing a pixel. One minute is enough.
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    suspend fun probePermissions() {
        val probe = withContext(Dispatchers.IO) {
            PermissionProbe(
                overlay = PermissionHelper.canDrawOverlays(context),
                accessibility = PermissionHelper.accessibilityStatus(context),
                notifications = PermissionHelper.canPostNotifications(context),
                game = installedClashPackage(context.packageManager)
            )
        }
        overlayOk = probe.overlay
        accessibilityStatus = probe.accessibility
        notificationsOk = probe.notifications
        if (gamePackage != probe.game) gamePackage = probe.game
    }

    // Permission and package probes hit the system services; keep them off the
    // UI thread and only publish actual changes.
    LaunchedEffect(Unit) {
        while (true) {
            probePermissions()
            delay(4_000)
        }
    }

    LaunchedEffect(permissionRefresh) {
        if (permissionRefresh > 0) probePermissions()
    }

    // Drop a selection that points at a macro deleted from any surface.
    LaunchedEffect(macros) {
        val current = BotState.selectedMacroId.value
        val reconciled = com.clahbot.data.MacroSelection.reconcile(current, macros)
        if (reconciled != current) BotState.setSelectedMacroId(reconciled)
        if (selectionMode) {
            val existing = macros.mapTo(mutableSetOf()) { it.id }
            val filtered = selectedMacros.filter { it in existing }.toSet()
            if (filtered != selectedMacros) selectedMacros = filtered
            if (macros.isEmpty()) {
                selectionMode = false
                selectedMacros = emptySet()
            }
        }
        settingsMacro?.let { editing ->
            if (macros.none { it.id == editing.id }) settingsMacro = null
        }
    }

    val gameIcon: ImageBitmap? = remember(gamePackage) {
        gamePackage?.let { pkg ->
            runCatching {
                context.packageManager.getApplicationIcon(pkg)
                    .toBitmap(96, 96)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val controllerActive = controllerState != ControllerState.INACTIVE

    val confirmedAttacks = remember(attacks) { attacks.filter { it.confirmed } }

    val launchGame: () -> Unit = {
        val pkg = installedClashPackage(context.packageManager)
        if (pkg == null) {
            showStoreDialog = true
        } else {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                PermissionHelper.showToast(context, "Installed but no launch intent — open it from your launcher")
            } else {
                try {
                    context.startActivity(intent)
                    scope.launch { GameSessionTracker.ensureSession() }
                } catch (_: Exception) {
                    PermissionHelper.showToast(context, "Could not launch Clash of Clans")
                }
            }
        }
    }

    if (showStoreDialog) {
        AlertDialog(
            onDismissRequest = { showStoreDialog = false },
            title = { Text("Clash of Clans not found") },
            text = {
                Text(
                    "AutoClash could not find Clash of Clans on this device. " +
                        "Install it from the Play Store, then try again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStoreDialog = false
                    openStore(context)
                }) { Text("Open Play Store") }
            },
            dismissButton = {
                TextButton(onClick = { showStoreDialog = false }) { Text("Cancel") }
            }
        )
    }

    deletingMacroFromSheet?.let { macro ->
        AlertDialog(
            onDismissRequest = { deletingMacroFromSheet = null },
            title = { Text("Delete macro?") },
            text = { Text("\"${macro.name}\" will be removed. A backup is saved first and you can undo for 5 seconds.") },
            confirmButton = {
                TextButton(onClick = {
                    deletingMacroFromSheet = null
                    settingsMacro = null
                    scope.launch {
                        val backup = viewModel.backup("before-single-delete")
                        if (backup == null) {
                            Toast.makeText(context, "Backup failed; nothing deleted", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        if (viewModel.macros.value.any { it.id == macro.id }) {
                            if (viewModel.deleteMany(setOf(macro.id)).isNotEmpty()) {
                                undoBackupName = backup?.name
                                undoing = listOf(macro)
                                Toast.makeText(context, "Deleted \"${macro.name}\"", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }) { Text("Delete", color = RecordRed) }
            },
            dismissButton = {
                TextButton(onClick = { deletingMacroFromSheet = null }) { Text("Cancel") }
            }
        )
    }

    if (showSelectionDelete) {
        val count = selectedMacros.size
        AlertDialog(
            onDismissRequest = { showSelectionDelete = false },
            title = { Text("Delete $count macro${if (count == 1) "" else "s"}?") },
            text = {
                Text(
                    "A backup file is written first. Deleted macros can be restored " +
                        "with Undo for 5 seconds."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSelectionDelete = false
                    val ids = selectedMacros
                    scope.launch {
                        val backup = viewModel.backup("before-delete")
                        if (backup == null) {
                            Toast.makeText(context, "Backup failed; nothing deleted", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        val removed = viewModel.deleteMany(ids)
                        if (removed.isNotEmpty()) {
                            undoBackupName = backup?.name
                            undoing = removed
                            Toast.makeText(
                                context,
                                "Deleted ${Plural.macros(removed.size)}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        selectionMode = false
                        selectedMacros = emptySet()
                    }
                }) { Text("Delete", color = RecordRed) }
            },
            dismissButton = {
                TextButton(onClick = { showSelectionDelete = false }) { Text("Cancel") }
            }
        )
    }

    LaunchedEffect(undoing) {
        if (undoing != null) {
            delay(5_000)
            undoing = null
        }
    }


    val activeSession = sessions.firstOrNull { it.active }
    val visibleSessions = remember(sessions) {
        sessions.filter { it.active || it.attackCount > 0 || it.macroIds.isNotEmpty() }
    }
    val unreadableCount = remember(confirmedAttacks) {
        confirmedAttacks.count { it.needsReviewDefinition }
    }
    LaunchedEffect(unreadableCount) {
        if (unreadableCount > lastReviewCount) reviewBannerDismissed = false
        lastReviewCount = unreadableCount
    }
    val needsAttention = !overlayOk ||
        accessibilityStatus != PermissionHelper.AccessibilityStatus.CONNECTED ||
        !notificationsOk

    val statsSession = activeSession ?: visibleSessions.firstOrNull()
    val statsAttacks = remember(statsScope, statsSession, confirmedAttacks) {
        if (statsScope == 0) {
            statsSession?.let { scopedAttacks(it, confirmedAttacks) } ?: emptyList()
        } else {
            confirmedAttacks
        }
    }
    val statsTotals = remember(statsAttacks) { SessionAggregator.totals(statsAttacks) }
    val statsSessions = remember(visibleSessions) {
        visibleSessions.sortedByDescending { it.startedAt }.take(3)
    }

    var hadActiveSession by remember { mutableStateOf(false) }
    LaunchedEffect(activeSession?.id) {
        if (activeSession != null) {
            hadActiveSession = true
        } else if (hadActiveSession) {
            hadActiveSession = false
            statsScope = 1
        }
    }

    val runRescan: () -> Unit = {
        if (!rescanning) {
            rescanning = true
            rescanStatus = null
            scope.launch {
                val summary = ResultsRescan.run(context, attackRepository)
                attackRepository.refresh()
                rescanStatus = ResultsRescan.summaryText(summary)
                rescanning = false
            }
        }
    }

    if (showHistory) {
        HistoryScreen(
            sessions = visibleSessions,
            attacks = confirmedAttacks,
            now = nowProvider,
            unreadable = unreadableCount,
            bannerDismissed = reviewBannerDismissed,
            onDismissBanner = { reviewBannerDismissed = true },
            rescanning = rescanning,
            rescanStatus = rescanStatus,
            onRescan = runRescan,
            onBack = { showHistory = false },
            onEdit = { lootEditing = it },
            onDelete = { deletingAttack = it }
        )
    } else
        Scaffold(containerColor = Color(com.clahbot.ui.design.Ds.BACKDROP)) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .acScrollFade(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = if (selectionMode || undoing != null) 92.dp else 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AcHeader(
                    controllerActive = controllerActive,
                    needsAttention = needsAttention,
                    onOpenSettings = onOpenSettings
                )
            }
            item {
                AcGameCard(
                    gameLabel = "Clash of Clans",
                    gameStatus = if (gamePackage != null) "Installed · ready" else "Not installed",
                    gameIcon = gameIcon,
                    session = activeSession ?: visibleSessions.firstOrNull(),
                    now = nowProvider,
                    onLaunch = launchGame
                )
            }
            item {
                AcSetupCard(
                    overlayOk = overlayOk,
                    accessibilityStatus = accessibilityStatus,
                    notificationsOk = notificationsOk,
                    controllerActive = controllerActive,
                    onStartController = onStartController,
                    onEnableOverlay = { PermissionHelper.requestOverlayPermission(context) },
                    onEnableAccessibility = { PermissionHelper.openAccessibilitySettings(context) },
                    onEnableNotifications = onRequestNotificationPermission,
                    onOpenAppInfo = { PermissionHelper.openAppDetails(context) }
                )
            }
            item {
                Column {
                    AcSectionHeader(
                        title = "Macro library",
                        meta = "${macros.size} saved"
                    )
                    AcLibraryActions(
                        importing = importing,
                        selectionMode = selectionMode,
                        canDelete = macros.isNotEmpty(),
                        onImport = {
                            if (!importing) {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/json",
                                        "text/plain",
                                        "application/octet-stream"
                                    )
                                )
                            }
                        },
                        onDelete = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedMacros = emptySet()
                            } else if (macros.isNotEmpty()) {
                                selectionMode = true
                                selectedMacros = emptySet()
                            }
                        }
                    )
                }
            }
            if (macros.isEmpty()) {
                item {
                    AcEmptyCard(
                        "No macros yet",
                        "Open the controller and record your first attack."
                    )
                }
            } else {
                items(macros, key = { it.id }) { macro ->
                    AcMacroRow(
                        macro = macro,
                        selected = macro.id == selectedId,
                        busy = controllerState.isBusy,
                        selectionMode = selectionMode,
                        checked = macro.id in selectedMacros,
                        onToggleChecked = {
                            selectedMacros = if (macro.id in selectedMacros) {
                                selectedMacros - macro.id
                            } else {
                                selectedMacros + macro.id
                            }
                        },
                        onSelect = { viewModel.select(macro.id) },
                        onPlay = { onPlayMacro(macro) },
                        onShare = { onShareMacro(macro) },
                        onOpenSettings = { settingsMacro = macro }
                    )
                }
            }
            item(key = "stats-header") {
                AcStatisticsHeader(
                    scope = statsScope,
                    onScope = {
                        statsScope = it
                        expandedStatsSession = null
                    }
                )
            }
            if (unreadableCount > 0 && !reviewBannerDismissed) {
                item(key = "stats-banner") {
                    ReviewBanner(
                        count = unreadableCount,
                        rescanning = rescanning,
                        onRescan = runRescan,
                        onDismiss = { reviewBannerDismissed = true }
                    )
                }
            }
            rescanStatus?.let {
                item(key = "stats-rescan") {
                    Text(it, fontSize = 11.sp, color = TextMuted)
                }
            }
            if (unreadableCount > 0 && reviewBannerDismissed) {
                item(key = "stats-review-count") {
                    Text(
                        Plural.needReview(unreadableCount) + " — excluded from totals",
                        fontSize = 11.sp,
                        color = PauseAmber
                    )
                }
            }
            item(key = "stats-totals") {
                Column {
                    AcTotalsCard(statsTotals)
                    Spacer(Modifier.height(10.dp))
                    AcTotalsMeta(statsTotals)
                    if (statsTotals.attackCount == 0) {
                        Spacer(Modifier.height(10.dp))
                        AcEmptyCard(
                            if (statsScope == 0) "No session data" else "No attacks yet",
                            if (statsScope == 0) {
                                "Switch to All time or launch Clash to start a session."
                            } else {
                                "Play a macro or scan a result screen."
                            }
                        )
                    }
                }
            }
            item(key = "stats-sessions-header") {
                Column(Modifier.padding(top = 4.dp)) {
                    Text(
                        "Sessions",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(com.clahbot.ui.design.Ds.TEXT)
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (statsSessions.isEmpty()) {
                item(key = "stats-sessions-empty") {
                    AcEmptyCard(
                        "No sessions yet",
                        "Launch Clash of Clans or play a macro to open a session."
                    )
                }
            } else {
                items(statsSessions, key = { "stats-session-${it.id}" }) { session ->
                    val records = remember(session, confirmedAttacks) {
                        scopedAttacks(session, confirmedAttacks)
                    }
                    AcSessionBox(
                        session = session,
                        title = "${acDate(session.startedAt)} · ${acClock(session.startedAt)}",
                        subtitle = historySubtitle(session, records, nowProvider()),
                        records = records,
                        expanded = expandedStatsSession == session.id,
                        onToggle = {
                            expandedStatsSession =
                                if (expandedStatsSession == session.id) null else session.id
                        },
                        onAttackClick = null
                    )
                }
            }
            item(key = "stats-see-all") {
                AcSeeAllHistory(onSeeAll = { showHistory = true })
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
            ) {
                AcSelectionActionBar(
                    count = selectedMacros.size,
                    onCancel = {
                        selectionMode = false
                        selectedMacros = emptySet()
                    },
                    onDelete = {
                        if (selectedMacros.isNotEmpty()) showSelectionDelete = true
                    }
                )
            }
        } else {
            undoing?.let { removed ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                ) {
                    AcUndoBar(
                        count = removed.size,
                        backupName = undoBackupName,
                        onUndo = {
                            scope.launch {
                                val restored = viewModel.restoreMany(removed)
                                undoing = null
                                undoBackupName = null
                                Toast.makeText(
                                    context,
                                    "Restored ${Plural.macros(restored)}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
        }
        }
    }

    settingsMacro?.let { macro ->
        MacroSettingsSheet(
            macro = macro,
            onDismiss = { settingsMacro = null },
            onSave = { draft ->
                viewModel.update(draft.toMacro(macro))
                settingsMacro = null
                Toast.makeText(context, "Saved \"${draft.name}\"", Toast.LENGTH_SHORT).show()
            },
            onDuplicate = { draft ->
                scope.launch {
                    val copy = viewModel.duplicate(macro.id)
                    if (copy == null) {
                        Toast.makeText(context, "Duplicate failed", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.update(draft.toMacro(copy).copy(name = copy.name))
                        settingsMacro = null
                        Toast.makeText(
                            context,
                            "Duplicated as \"${copy.name}\"",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onReset = {},
            onDelete = { deletingMacroFromSheet = macro }
        )
    }

    lootEditing?.let { record ->
        LootEditDialog(
            record = record,
            onDismiss = { lootEditing = null },
            onSave = { gold, elixir, dark, bonusGold, bonusElixir, bonusDark ->
                scope.launch {
                    val anyLoot = listOf(
                        gold,
                        elixir,
                        dark,
                        bonusGold,
                        bonusElixir,
                        bonusDark
                    ).any { it != null }
                    attackRepository.update(
                        record.copy(
                            gold = gold,
                            elixir = elixir,
                            darkElixir = dark,
                            bonusGold = bonusGold,
                            bonusElixir = bonusElixir,
                            bonusDarkElixir = bonusDark,
                            lootSource = if (anyLoot) {
                                AttackRecord.LOOT_MANUAL
                            } else {
                                AttackRecord.LOOT_NONE
                            },
                            captureSource = AttackRecord.SOURCE_MANUAL,
                            verified = anyLoot,
                            complete = gold != null && elixir != null && dark != null,
                            bonusState = when {
                                bonusGold != null || bonusElixir != null || bonusDark != null ->
                                    AttackRecord.BONUS_CAPTURED
                                else -> record.bonusState
                            },
                            reviewReason = null,
                            confirmed = true
                        )
                    )
                }
                lootEditing = null
            }
        )
    }

    deletingAttack?.let { record ->
        AlertDialog(
            onDismissRequest = { deletingAttack = null },
            title = { Text("Delete attack record?") },
            text = { Text("The history entry will be removed. Saved macros are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { attackRepository.delete(record.id) }
                    deletingAttack = null
                }) { Text("Delete", color = RecordRed) }
            },
            dismissButton = {
                TextButton(onClick = { deletingAttack = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LootChip(iconRes: Int, value: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            value?.let { formatNumber(it) } ?: "—",
            fontSize = 10.sp,
            color = if (value != null) ReadyGreen else TextMuted
        )
    }
}

@Composable
private fun LootEditDialog(
    record: AttackRecord,
    onDismiss: () -> Unit,
    onSave: (Long?, Long?, Long?, Long?, Long?, Long?) -> Unit
) {
    fun initial(value: Long?) = value?.toString() ?: ""
    var gold by remember(record.id) { mutableStateOf(initial(record.gold)) }
    var elixir by remember(record.id) { mutableStateOf(initial(record.elixir)) }
    var dark by remember(record.id) { mutableStateOf(initial(record.darkElixir)) }
    var bonusGold by remember(record.id) { mutableStateOf(initial(record.bonusGold)) }
    var bonusElixir by remember(record.id) { mutableStateOf(initial(record.bonusElixir)) }
    var bonusDark by remember(record.id) { mutableStateOf(initial(record.bonusDarkElixir)) }

    fun parse(value: String): Long? = LootInput.parse(value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (record.confirmed) "Edit attack loot" else "Confirm this attack") },
        text = {
            Column(modifier = Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (record.confirmed) {
                        "Correct the values from the result screen. Empty means not captured."
                    } else {
                        "A playback is not an attack yet. Enter the loot from the result screen " +
                            "to confirm it. You can leave every field empty and still confirm the attack."
                    },
                    fontSize = 11.sp,
                    color = TextMuted
                )
                record.reviewReason?.let { reason ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Needs review: $reason",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PauseAmber
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("You got", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                listOf(
                    Triple("Gold", gold) { v: String -> gold = v },
                    Triple("Elixir", elixir) { v: String -> elixir = v },
                    Triple("Dark elixir", dark) { v: String -> dark = v }
                ).forEach { (label, value, setter) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = setter,
                        label = { Text(label) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("Bonus (separate panel)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                listOf(
                    Triple("Bonus gold", bonusGold) { v: String -> bonusGold = v },
                    Triple("Bonus elixir", bonusElixir) { v: String -> bonusElixir = v },
                    Triple("Bonus dark", bonusDark) { v: String -> bonusDark = v }
                ).forEach { (label, value, setter) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = setter,
                        label = { Text(label) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    parse(gold), parse(elixir), parse(dark),
                    parse(bonusGold), parse(bonusElixir), parse(bonusDark)
                )
            }) {
                Text(if (record.confirmed) "Save" else "Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}


@Composable
private fun RenameDialog(
    macro: Macro,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit
) {
    var name by remember(macro.id) { mutableStateOf(macro.name) }
    var delayText by remember(macro.id) {
        mutableStateOf(formatDelaySeconds(macro.effectiveRepeatDelayMs))
    }
    var error by remember(macro.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Macro settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = delayText,
                    onValueChange = {
                        delayText = it
                        error = null
                    },
                    label = { Text("Wait between repeats (seconds)") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "0-600 s. Default 0.8 s; 0 means no extra wait.",
                    color = TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                error?.let {
                    Text(it, color = RecordRed, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val seconds = delayText.trim().toDoubleOrNull()
                    val validRange = seconds != null &&
                        seconds.isFinite() &&
                        seconds >= 0.0 &&
                        seconds <= 600.0
                    when {
                        name.isBlank() -> error = "Enter a macro name"
                        !validRange -> error = "Enter a wait between 0 and 600 seconds"
                        else -> {
                            val delayMs = (seconds!! * 1000.0).roundToLong()
                                .coerceIn(0L, Macro.MAX_REPEAT_DELAY_MS)
                            onSave(name.trim(), delayMs)
                        }
                    }
                },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatDelaySeconds(ms: Long): String =
    String.format(Locale.US, "%.2f", ms / 1000.0)
        .trimEnd('0')
        .trimEnd('.')

private fun openStore(context: android.content.Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CLASH_MARKET)))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CLASH_STORE)))
        } catch (_: Exception) {
            PermissionHelper.showToast(context, "No app available to open the Play Store")
        }
    } catch (_: Exception) {
        PermissionHelper.showToast(context, "Could not open the Play Store")
    }
}



private fun formatNumber(value: Long): String = String.format(Locale.US, "%,d", value)
