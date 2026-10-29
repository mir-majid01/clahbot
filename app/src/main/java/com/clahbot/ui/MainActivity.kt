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
import com.clahbot.data.models.GameSession
import com.clahbot.session.GameSessionTracker
import com.clahbot.ui.dashboard.AcEmptyCard
import com.clahbot.ui.dashboard.AcGameCard
import com.clahbot.ui.dashboard.AcHeader
import com.clahbot.ui.dashboard.AcHistoryDialog
import com.clahbot.ui.dashboard.AcMacroRow
import com.clahbot.ui.dashboard.AcSectionHeader
import com.clahbot.ui.dashboard.AcSessionDetailDialog
import com.clahbot.ui.dashboard.AcSessionsDialog
import com.clahbot.ui.dashboard.AcSessionsSection
import com.clahbot.ui.dashboard.AcSetupCard
import com.clahbot.ui.dashboard.AcStatisticsSection
import com.clahbot.results.ResultsRescan
import com.clahbot.data.models.Macro
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

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

        setContent {
            val settings by SettingsStore.get(this).settings.collectAsState()
            AutoClashTheme(settings.theme) {
                DashboardScreen(
                    viewModel = viewModel,
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

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
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

    var tick by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Macro?>(null) }
    var deleting by remember { mutableStateOf<Macro?>(null) }
    var showStoreDialog by remember { mutableStateOf(false) }
    var lootEditing by remember { mutableStateOf<AttackRecord?>(null) }
    var deletingAttack by remember { mutableStateOf<AttackRecord?>(null) }
    var rescanning by remember { mutableStateOf(false) }
    var rescanStatus by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var statsScope by remember { mutableStateOf(0) }
    var showSessions by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var sessionDetail by remember { mutableStateOf<GameSession?>(null) }
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
    }

    val hasActiveSession = sessions.any { it.active }
    LaunchedEffect(controllerState, hasActiveSession) {
        if (controllerState != ControllerState.INACTIVE && !hasActiveSession) {
            return@LaunchedEffect
        }
        while (true) {
            delay(2000)
            tick++
        }
    }

    // Drop a selection that points at a macro deleted from any surface.
    LaunchedEffect(macros) {
        val current = BotState.selectedMacroId.value
        val reconciled = com.clahbot.data.MacroSelection.reconcile(current, macros)
        if (reconciled != current) BotState.setSelectedMacroId(reconciled)
    }

    val overlayOk = remember(tick) { PermissionHelper.canDrawOverlays(context) }
    val accessibilityOk = remember(tick) { PermissionHelper.isAccessibilityEnabled(context) }
    val notificationsOk = remember(tick) { PermissionHelper.canPostNotifications(context) }
    val gamePackage = remember(tick) { installedClashPackage(context.packageManager) }
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
                    scope.launch { GameSessionTracker.ensureSession() }
                    context.startActivity(intent)
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

    editing?.let { macro ->
        RenameDialog(
            macro = macro,
            onDismiss = { editing = null },
            onSave = { name, delayMs ->
                viewModel.update(macro.copy(name = name, repeatDelayMs = delayMs))
                editing = null
            }
        )
    }

    deleting?.let { macro ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete macro?") },
            text = { Text("\"${macro.name}\" will be removed from the library.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(macro.id)
                    deleting = null
                }) { Text("Delete", color = RecordRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }

    val now = remember(tick) { System.currentTimeMillis() }
    val activeSession = sessions.firstOrNull { it.active }
    val needsAttention = !(overlayOk && accessibilityOk && notificationsOk)

    Scaffold(containerColor = Color(com.clahbot.ui.design.Ds.BACKDROP)) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp
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
                    session = activeSession ?: sessions.firstOrNull(),
                    now = now,
                    onLaunch = launchGame
                )
            }
            item {
                AcSetupCard(
                    overlayOk = overlayOk,
                    accessibilityOk = accessibilityOk,
                    notificationsOk = notificationsOk,
                    controllerActive = controllerActive,
                    onStartController = onStartController,
                    onEnableOverlay = { PermissionHelper.requestOverlayPermission(context) },
                    onEnableAccessibility = { PermissionHelper.openAccessibilitySettings(context) },
                    onEnableNotifications = onRequestNotificationPermission
                )
            }
            item {
                AcSectionHeader(
                    title = "Macro library",
                    meta = "${macros.size} saved",
                    action = if (importing) "Importing…" else "Import",
                    onAction = {
                        if (!importing) {
                            importLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "text/plain",
                                    "application/octet-stream"
                                )
                            )
                        }
                    }
                )
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
                        onSelect = { viewModel.select(macro.id) },
                        onPlay = { onPlayMacro(macro) },
                        onShare = { onShareMacro(macro) },
                        onOpenSettings = { editing = macro }
                    )
                }
            }
            item {
                AcSectionHeader(
                    title = "Sessions",
                    action = "See all",
                    onAction = { showSessions = true }
                )
            }
            item {
                AcSessionsSection(
                    sessions = sessions,
                    attacks = confirmedAttacks,
                    now = now,
                    onOpen = { sessionDetail = it }
                )
            }
            item {
                AcSectionHeader(title = "Statistics")
            }
            item {
                AcStatisticsSection(
                    scope = statsScope,
                    onScope = { statsScope = it },
                    attacks = confirmedAttacks,
                    sessions = sessions,
                    onSeeAll = { showHistory = true }
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        enabled = !rescanning,
                        onClick = {
                            rescanning = true
                            rescanStatus = null
                            scope.launch {
                                val summary = ResultsRescan.run(context, attackRepository)
                                attackRepository.refresh()
                                rescanStatus = ResultsRescan.summaryText(summary)
                                rescanning = false
                            }
                        }
                    ) {
                        Text(
                            if (rescanning) "Re-scanning…" else "Re-scan unreadable",
                            fontSize = 12.sp
                        )
                    }
                    rescanStatus?.let {
                        Text(it, fontSize = 11.sp, color = Color(com.clahbot.ui.design.Ds.MUTED))
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        }
    }

    if (showSessions) {
        AcSessionsDialog(
            sessions = sessions,
            attacks = confirmedAttacks,
            onDismiss = { showSessions = false },
            onOpen = { sessionDetail = it }
        )
    }
    sessionDetail?.let { session ->
        AcSessionDetailDialog(
            session = session,
            attacks = confirmedAttacks,
            macros = macros,
            onDismiss = { sessionDetail = null }
        )
    }
    if (showHistory) {
        AcHistoryDialog(
            records = confirmedAttacks,
            onDismiss = { showHistory = false },
            onEdit = { lootEditing = it },
            onDelete = { deletingAttack = it }
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
