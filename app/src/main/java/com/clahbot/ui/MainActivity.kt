package com.clahbot.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
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
import androidx.core.graphics.drawable.toBitmap
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

import com.clahbot.R
import com.clahbot.data.AttackRepository
import com.clahbot.data.BotState
import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.AttackStats
import com.clahbot.data.models.LootInput
import com.clahbot.data.ControllerState
import com.clahbot.data.MacroRepository
import com.clahbot.data.SettingsStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        setContent {
            val settings by SettingsStore.get(this).settings.collectAsState()
            AutoClashTheme(settings.theme) {
                DashboardScreen(
                    viewModel = viewModel,
                    onStartController = { startController() },
                    onPlayMacro = { macro -> playMacro(macro) },
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
    onOpenSettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attackRepository = remember { AttackRepository.get(context) }
    val attacks by attackRepository.records.collectAsState()
    val macros by viewModel.macros.collectAsState()
    val selectedId by BotState.selectedMacroId.collectAsState()
    val controllerState by BotState.state.collectAsState()

    var tick by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Macro?>(null) }
    var deleting by remember { mutableStateOf<Macro?>(null) }
    var showStoreDialog by remember { mutableStateOf(false) }
    var historyFilter by remember { mutableStateOf(0) }
    var lootEditing by remember { mutableStateOf<AttackRecord?>(null) }
    var deletingAttack by remember { mutableStateOf<AttackRecord?>(null) }

    LaunchedEffect(Unit) { attackRepository.refresh() }

    // While a session is active the dashboard is behind the controller overlay:
    // polling here would only add main-thread work during recording.
    LaunchedEffect(controllerState) {
        if (controllerState != ControllerState.INACTIVE) return@LaunchedEffect
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

    val historySince = remember(historyFilter, tick) {
        val now = System.currentTimeMillis()
        when (historyFilter) {
            1 -> now - 7L * 24L * 60L * 60L * 1000L
            2 -> now - (now % (24L * 60L * 60L * 1000L))
            else -> null
        }
    }
    val confirmedAttacks = remember(attacks) { attacks.filter { it.confirmed } }
    val unconfirmedCount = attacks.size - confirmedAttacks.size
    val filteredAttacks = remember(attacks, historySince) {
        AttackStats.filter(attacks, historySince)
    }

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
            onSave = { name ->
                viewModel.update(macro.copy(name = name))
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            DashboardHeader(
                controllerActive = controllerActive,
                onOpenSettings = onOpenSettings
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GameCard(
                    installedPackage = gamePackage,
                    gameIcon = gameIcon,
                    controllerActive = controllerActive,
                    onLaunchGame = launchGame
                )
            }
            item {
                ControllerStatusCard(
                    controllerState = controllerState,
                    overlayOk = overlayOk,
                    accessibilityOk = accessibilityOk,
                    notificationsOk = notificationsOk,
                    onStartController = onStartController,
                    onEnableOverlay = { PermissionHelper.requestOverlayPermission(context) },
                    onEnableAccessibility = { PermissionHelper.openAccessibilitySettings(context) },
                    onEnableNotifications = onRequestNotificationPermission
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Macro library",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${macros.size} saved",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }

            if (macros.isEmpty()) {
                item {
                    EmptyLibrary(onStartController = onStartController)
                }
            } else {
                items(macros, key = { it.id }) { macro ->
                    MacroCard(
                        macro = macro,
                        selected = macro.id == selectedId,
                        busy = controllerState.isBusy,
                        onSelect = { viewModel.select(macro.id) },
                        onPlay = { onPlayMacro(macro) },
                        onRename = { editing = macro },
                        onDelete = { deleting = macro }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Attack history",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (unconfirmedCount == 0) "${confirmedAttacks.size} recorded"
                        else "${confirmedAttacks.size} confirmed · $unconfirmedCount unconfirmed",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
            item {
                AttackSummaryCard(confirmedAttacks, unconfirmedCount) {
                    attacks.firstOrNull { !it.confirmed }?.let { lootEditing = it }
                }
            }
            item {
                HistoryFilterRow(selected = historyFilter, onSelect = { historyFilter = it })
            }
            if (filteredAttacks.isEmpty()) {
                item { HistoryEmpty(hasAny = attacks.isNotEmpty()) }
            } else {
                items(filteredAttacks.take(25), key = { "attack-${it.id}" }) { record ->
                    AttackHistoryRow(
                        record = record,
                        onEdit = { lootEditing = record },
                        onConfirm = { lootEditing = record },
                        onDelete = { deletingAttack = record }
                    )
                }
            }
        }
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
                            verified = anyLoot,
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
private fun AttackSummaryCard(
    records: List<AttackRecord>,
    unconfirmedCount: Int,
    onConfirmNext: () -> Unit
) {
    val summary = remember(records) { AttackStats.summarize(records) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SummaryStat("Attacks", summary.count.toString(), modifier = Modifier.weight(1f))
                SummaryStat(
                    "Gold",
                    if (summary.lootedCount == 0) "—" else formatNumber(summary.gold),
                    iconRes = R.drawable.ic_gold,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SummaryStat(
                    "Elixir",
                    if (summary.lootedCount == 0) "—" else formatNumber(summary.elixir),
                    iconRes = R.drawable.ic_elixir,
                    modifier = Modifier.weight(1f)
                )
                SummaryStat(
                    "Dark",
                    if (summary.lootedCount == 0) "—" else formatNumber(summary.darkElixir),
                    iconRes = R.drawable.ic_dark,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Measured ${formatDuration(summary.totalDurationMs)} · average ${formatDuration(summary.averageDurationMs)}",
                fontSize = 10.sp,
                color = TextMuted
            )
            if (unconfirmedCount > 0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$unconfirmedCount unconfirmed playback session(s) are not counted as " +
                            "attacks yet. Confirm the result to count it.",
                        fontSize = 10.sp,
                        color = PauseAmber,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onConfirmNext,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Confirm next", fontSize = 10.sp, color = ElectricBlue)
                    }
                }
            }
            if (summary.count > 0 && summary.lootedCount == 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Loot is read automatically when a result screen is detected. A failed read " +
                        "stays \"loot not captured\"; tap a record to correct it.",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun SummaryStat(
    label: String,
    value: String,
    iconRes: Int? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, fontSize = 10.sp, color = TextMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (iconRes != null) {
                androidx.compose.foundation.Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterRow(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("All", "7 days", "Today")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelect(index) },
                label = { Text(label, fontSize = 11.sp) }
            )
        }
    }
}

@Composable
private fun HistoryEmpty(hasAny: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Text(
            if (hasAny) "No attacks in this range." else "No attacks recorded yet. Play a saved macro to start the timeline.",
            fontSize = 11.sp,
            color = TextMuted,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun AttackHistoryRow(
    record: AttackRecord,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(record.startedAt) {
        SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(record.startedAt))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        when (record.result) {
                            AttackRecord.RESULT_INTERRUPTED -> PauseAmber
                            AttackRecord.RESULT_COMPLETED -> ReadyGreen
                            else -> TextMuted
                        }
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$dateStr · ${formatDuration(record.durationMs)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    record.macroName + " · " + MacroTimeline.formatSpeed(record.speedMultiplier) +
                        " · " + if (record.confirmed) "Confirmed" else "Unconfirmed" +
                        (record.outcome?.let { " · " + it.replaceFirstChar { c -> c.uppercase() } } ?: ""),
                    fontSize = 10.sp,
                    color = if (record.confirmed) TextMuted else PauseAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                if (record.hasLoot) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        LootChip(R.drawable.ic_gold, record.gold)
                        LootChip(R.drawable.ic_elixir, record.elixir)
                        LootChip(R.drawable.ic_dark, record.darkElixir)
                        when (record.lootSource) {
                            AttackRecord.LOOT_OCR ->
                                Text("read", fontSize = 9.sp, color = TextMuted)

                            AttackRecord.LOOT_MANUAL ->
                                Text("edited", fontSize = 9.sp, color = TextMuted)
                        }
                    }
                } else {
                    Text(
                        if (record.confirmed) {
                            "Loot not captured"
                        } else {
                            "Loot not captured · tap ✓ to confirm"
                        },
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
                if (record.hasBonus) {
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text("Bonus", fontSize = 9.sp, color = TextMuted)
                        record.bonusGold?.let { LootChip(R.drawable.ic_gold, it) }
                        record.bonusElixir?.let { LootChip(R.drawable.ic_elixir, it) }
                        record.bonusDarkElixir?.let { LootChip(R.drawable.ic_dark, it) }
                    }
                }
            }
            if (!record.confirmed) {
                IconButton(onClick = onConfirm, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Confirm attack",
                        tint = ReadyGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit loot",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete attack record",
                    tint = RecordRed,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
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
private fun DashboardHeader(controllerActive: Boolean, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_autoclash_mark),
            contentDescription = null,
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "AutoClash",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text("Macro recorder", fontSize = 11.sp, color = TextMuted)
        }
        if (controllerActive) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(ReadyGreen)
            )
            Spacer(Modifier.width(5.dp))
            Text("Active", fontSize = 10.sp, color = ReadyGreen)
            Spacer(Modifier.width(6.dp))
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GameCard(
    installedPackage: String?,
    gameIcon: ImageBitmap?,
    controllerActive: Boolean,
    onLaunchGame: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceRaised),
                    contentAlignment = Alignment.Center
                ) {
                    if (gameIcon != null) {
                        androidx.compose.foundation.Image(
                            bitmap = gameIcon,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.ic_game_clash),
                            contentDescription = null,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Clash of Clans",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (installedPackage == null) "Not installed" else "Installed · ${installedPackage.removePrefix("com.supercell.")}",
                        fontSize = 11.sp,
                        color = if (installedPackage == null) TextMuted else ReadyGreen,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = onLaunchGame,
                    enabled = installedPackage != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricBlue,
                        disabledContainerColor = SurfaceRaised,
                        disabledContentColor = TextMuted
                    )
                ) {
                    Text("Launch", fontSize = 12.sp)
                }
            }
            if (!controllerActive) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Start the floating controller before you play to record an attack.",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun ControllerStatusCard(
    controllerState: ControllerState,
    overlayOk: Boolean,
    accessibilityOk: Boolean,
    notificationsOk: Boolean,
    onStartController: () -> Unit,
    onEnableOverlay: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onEnableNotifications: () -> Unit
) {
    val ready = overlayOk && accessibilityOk
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Controller",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(controllerState)
            }
            Spacer(Modifier.height(8.dp))
            PermissionRow("Accessibility service", accessibilityOk, onEnableAccessibility)
            PermissionRow("Display over other apps", overlayOk, onEnableOverlay)
            PermissionRow("Notifications", notificationsOk, onEnableNotifications)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    when {
                        !accessibilityOk -> onEnableAccessibility()
                        !overlayOk -> onEnableOverlay()
                        else -> onStartController()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (ready) ElectricBlue else PauseAmber,
                    contentColor = if (ready) Color.White else Color(0xFF1C1917)
                )
            ) {
                Text(
                    when {
                        !accessibilityOk -> "Enable accessibility"
                        !overlayOk -> "Allow display over apps"
                        controllerState == ControllerState.INACTIVE -> "Start floating controller"
                        controllerState.isRecording -> "Recording — open controller"
                        controllerState.isPlayback -> "Playing — open controller"
                        else -> "Open controller"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (!ready) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Passive recording reads the touchscreen through Shizuku or root, so the game " +
                        "still receives every touch. Accessibility and overlay access are needed for " +
                        "playback and the floating controls.",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun StatusChip(state: ControllerState) {
    val (label, color) = when (state) {
        ControllerState.INACTIVE -> "Idle" to TextMuted
        ControllerState.READY -> "Ready" to ReadyGreen
        ControllerState.RECORDING, ControllerState.RECORDING_PAUSED -> "Recording" to RecordRed
        ControllerState.REVIEW -> "Review" to ElectricBlue
        ControllerState.PLAYING, ControllerState.PLAYBACK_PAUSED -> "Playing" to ReadyGreen
        ControllerState.STOPPING -> "Stopping" to PauseAmber
        ControllerState.ERROR -> "Error" to RecordRed
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = color)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onEnable: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (granted) ReadyGreen else PauseAmber)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (granted) {
            Text("Granted", fontSize = 10.sp, color = ReadyGreen)
        } else {
            TextButton(onClick = onEnable, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                Text("Enable", fontSize = 10.sp, color = PauseAmber)
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onStartController: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_autoclash_mark),
                contentDescription = null,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "No macros yet",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Start the controller, tap Record, play the attack normally, then review and save the macro.",
                fontSize = 11.sp,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onStartController,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start recording", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MacroCard(
    macro: Macro,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) ElectricBlue else Color.Transparent,
                shape = shape
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (selected) ElectricBlue else Hairline, CircleShape)
                    .background(if (selected) ElectricBlue else Color.Transparent)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    macro.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${macro.gestures.size} gestures · ${formatDuration(macro.durationMs)}",
                    fontSize = 10.sp,
                    color = TextMuted
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    "${MacroTimeline.formatSpeed(macro.speedMultiplier)} · " +
                        MacroTimeline.repeatLabel(macro.repeatMode) + " · " + formatDate(macro.createdAt),
                    fontSize = 9.sp,
                    color = TextMuted
                )
            }
            IconButton(onClick = onPlay, enabled = !busy, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play ${macro.name}",
                    tint = if (busy) TextMuted else ReadyGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Rename ${macro.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${macro.name}",
                    tint = RecordRed,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(macro: Macro, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(macro.id) { mutableStateOf(macro.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename macro") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

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

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60
    return String.format(Locale.US, "%dm %02ds", minutes, seconds)
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MM/dd", Locale.US).format(Date(timestamp))

private fun formatNumber(value: Long): String = String.format(Locale.US, "%,d", value)
