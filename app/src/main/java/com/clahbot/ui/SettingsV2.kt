package com.clahbot.ui

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.clahbot.BuildConfig
import com.clahbot.R
import com.clahbot.data.AppSettings
import com.clahbot.data.AttackRepository
import com.clahbot.data.BotState
import com.clahbot.data.ControllerState
import com.clahbot.data.EffectStyle
import com.clahbot.data.MacroRepository
import com.clahbot.data.RecordingMode
import com.clahbot.data.SettingsStore
import com.clahbot.data.ThemeMode
import com.clahbot.data.TouchEffectConfig
import com.clahbot.data.models.Macro
import com.clahbot.execution.MacroTimeline
import com.clahbot.execution.StopSummary
import com.clahbot.input.InputAccessManager
import com.clahbot.overlay.BubbleCorner
import com.clahbot.ui.dashboard.AcLogo
import com.clahbot.ui.dashboard.AcSegmented
import com.clahbot.ui.dashboard.acScrollFade
import com.clahbot.ui.design.Ds
import com.clahbot.util.Haptics
import com.clahbot.util.PermissionHelper
import com.clahbot.util.Plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.Locale
import kotlin.math.roundToInt

private enum class SettingsTab(val label: String, val title: String, val subtitle: String, val icon: Int) {
    ACCESS("Access", "Access", "Recording access, permissions and diagnostics", R.drawable.ic_stop_conditions),
    PLAYBACK("Playback", "Playback", "Recording, defaults, touch effects and feedback", R.drawable.ic_speed),
    CONTROLLER("Controller", "Controller", "Floating panel, icon and docking", R.drawable.ic_gear),
    DATA("Data", "Data", "Macros, attack history and backups", R.drawable.ic_database),
    APP("App", "App", "Appearance, updates and about", R.drawable.ic_info)
}

private data class SettingsProbeV2(
    val overlay: Boolean,
    val accessibility: PermissionHelper.AccessibilityStatus,
    val notifications: Boolean,
    val access: InputAccessManager.AccessStatus,
    val storage: Long?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenV2(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onOpenController: () -> Unit,
    onResetPosition: () -> Unit,
    initialTab: Int = SettingsTab.PLAYBACK.ordinal,
    resumeToken: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { MacroRepository.get(context) }
    val attackRepository = remember { AttackRepository.get(context) }
    val macros by repository.macros.collectAsState()
    val attacks by attackRepository.records.collectAsState()
    val controllerState by BotState.state.collectAsState()

    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, SettingsTab.entries.lastIndex)) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var overlayOk by remember { mutableStateOf(false) }
    var accessibilityStatus by remember {
        mutableStateOf(PermissionHelper.AccessibilityStatus.DISABLED)
    }
    var notificationsOk by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(InputAccessManager.status(context)) }
    var storageBytes by remember { mutableStateOf(0L) }
    var showClearAll by remember { mutableStateOf(false) }
    var showClearHistory by remember { mutableStateOf(false) }
    var showRemoveUnconfirmed by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showLicences by remember { mutableStateOf(false) }
    var playingEditor by remember { mutableStateOf(false) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var undoMacros by remember { mutableStateOf<List<Macro>?>(null) }
    var undoAttacks by remember { mutableStateOf<List<com.clahbot.data.models.AttackRecord>?>(null) }

    LaunchedEffect(Unit) {
        repository.refresh()
        attackRepository.refresh()
    }
    LaunchedEffect(undoMacros, undoAttacks) {
        if (undoMacros != null || undoAttacks != null) {
            delay(5_000)
            undoMacros = null
            undoAttacks = null
        }
    }
    LaunchedEffect(resumeToken) {
        if (resumeToken > 0) refreshToken++
    }
    LaunchedEffect(refreshToken) {
        var cycle = 0
        while (true) {
            val probe = withContext(Dispatchers.IO) {
                SettingsProbeV2(
                    overlay = PermissionHelper.canDrawOverlays(context),
                    accessibility = PermissionHelper.accessibilityStatus(context),
                    notifications = PermissionHelper.canPostNotifications(context),
                    access = InputAccessManager.status(context),
                    storage = if (cycle % 3 == 0) repository.storageBytes() else null
                )
            }
            overlayOk = probe.overlay
            accessibilityStatus = probe.accessibility
            notificationsOk = probe.notifications
            access = probe.access
            probe.storage?.let { storageBytes = it }
            cycle++
            delay(4_000)
        }
    }
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ ->
            Handler(Looper.getMainLooper()).post { refreshToken++ }
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (_: Throwable) {
            // Shizuku not running.
        }
        onDispose {
            try {
                Shizuku.removeRequestPermissionResultListener(listener)
            } catch (_: Throwable) {
                // Shizuku stopped.
            }
        }
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("Delete all macros?") },
            text = { Text("A backup file is written first. This removes ${Plural.of(macros.size, "saved macro")}.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAll = false
                    scope.launch {
                        val removed = macros.toList()
                        repository.backup("before-clear-all")
                        repository.clearAll()
                        undoMacros = removed
                    }
                }) { Text("Delete all", color = Color(Ds.RECORD)) }
            },
            dismissButton = { TextButton(onClick = { showClearAll = false }) { Text("Cancel") } }
        )
    }
    if (showClearHistory) {
        AlertDialog(
            onDismissRequest = { showClearHistory = false },
            title = { Text("Clear attack history?") },
            text = { Text("This removes ${Plural.of(attacks.size, "attack record")}. Saved macros are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistory = false
                    scope.launch {
                        attackRepository.backup("before-clear-history")
                        undoAttacks = attackRepository.clearAllReturning()
                    }
                }) { Text("Clear history", color = Color(Ds.RECORD)) }
            },
            dismissButton = { TextButton(onClick = { showClearHistory = false }) { Text("Cancel") } }
        )
    }
    if (showRemoveUnconfirmed) {
        val unconfirmed = attacks.filter { !it.confirmed }
        AlertDialog(
            onDismissRequest = { showRemoveUnconfirmed = false },
            title = { Text("Remove unconfirmed sessions?") },
            text = { Text("Deletes ${Plural.of(unconfirmed.size, "playback session")} that " +
                if (unconfirmed.size == 1) "was never confirmed as an attack."
                else "were never confirmed as attacks.") },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveUnconfirmed = false
                    scope.launch {
                        attackRepository.backup("before-remove-unconfirmed")
                        val ids = unconfirmed.map { it.id }.toSet()
                        undoAttacks = unconfirmed
                        ids.forEach { attackRepository.delete(it) }
                    }
                }) { Text("Remove", color = Color(Ds.RECORD)) }
            },
            dismissButton = { TextButton(onClick = { showRemoveUnconfirmed = false }) { Text("Cancel") } }
        )
    }
    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Privacy") },
            text = {
                Text(
                    "AutoClash stores macros, attacks and settings only on this device. " +
                        "It uses the internet only when you ask it to check GitHub for an update; " +
                        "no macros or gameplay data are uploaded."
                )
            },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("Close") } }
        )
    }
    if (showLicences) {
        AlertDialog(
            onDismissRequest = { showLicences = false },
            title = { Text("Open-source licences") },
            text = { Text("Kotlin, Jetpack Compose, AndroidX, Kotlin Coroutines and Gson are used under the Apache License 2.0.") },
            confirmButton = { TextButton(onClick = { showLicences = false }) { Text("Close") } }
        )
    }

    Scaffold(
        containerColor = Color(Ds.BACKDROP),
        bottomBar = {
            SettingsIslandBar(tab) { tab = it }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .acScrollFade(Color(Ds.BACKDROP)),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(Ds.TEXT)
                        )
                    }
                    Text("Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(Ds.TEXT))
                }
            }
            item {
                val current = SettingsTab.entries[tab]
                Column {
                    Text(
                        current.title,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(Ds.TEXT)
                    )
                    Text(current.subtitle, fontSize = 13.sp, color = Color(Ds.MUTED))
                }
            }
            when (SettingsTab.entries[tab]) {
                SettingsTab.ACCESS -> {
                    item {
                        ShizukuCard(
                            access = access,
                            onRequest = {
                                if (!InputAccessManager.requestShizukuPermission()) {
                                    PermissionHelper.showToast(context, "Start Shizuku first")
                                }
                            },
                            onOpen = { InputAccessManager.openShizuku(context) },
                            onInstall = { InputAccessManager.openShizukuInstall(context) }
                        )
                    }
                    item {
                        V2Section("Permissions") {
                            PermissionRowV2(
                                label = "Accessibility service",
                                granted = accessibilityStatus ==
                                    PermissionHelper.AccessibilityStatus.CONNECTED,
                                onOpen = { PermissionHelper.openAccessibilitySettings(context) },
                                statusText = when (accessibilityStatus) {
                                    PermissionHelper.AccessibilityStatus.CONNECTED -> "Connected"
                                    PermissionHelper.AccessibilityStatus.ENABLED_NOT_CONNECTED ->
                                        "Not connected"
                                    PermissionHelper.AccessibilityStatus.DISABLED -> null
                                }
                            )
                            PermissionRowV2("Display over other apps", overlayOk) {
                                PermissionHelper.requestOverlayPermission(context)
                            }
                            PermissionRowV2("Notifications", notificationsOk) {
                                PermissionHelper.openNotificationSettings(context)
                            }
                            if (accessibilityStatus ==
                                PermissionHelper.AccessibilityStatus.ENABLED_NOT_CONNECTED
                            ) {
                                Text(
                                    "Android has Accessibility on, but the service has not connected. " +
                                        "Open Accessibility, turn AutoClash off, then on.",
                                    fontSize = 10.sp,
                                    color = Color(Ds.WARN),
                                    modifier = Modifier.padding(start = 15.dp, bottom = 4.dp)
                                )
                            }
                            if (accessibilityStatus !=
                                PermissionHelper.AccessibilityStatus.CONNECTED
                            ) {
                                Text(
                                    "If Android says “Restricted setting”, open App info, " +
                                        "tap ⋮ and allow restricted settings.",
                                    fontSize = 10.sp,
                                    color = Color(Ds.MUTED),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                TextButton(
                                    onClick = { PermissionHelper.openAppDetails(context) },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Open App info", fontSize = 10.sp, color = Color(Ds.LINK))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Passive recording reads raw touchscreen events through Shizuku or root. " +
                                    "Playback uses the accessibility service to dispatch saved gestures. " +
                                    "Macros, loot and screenshots stay on this phone.",
                                fontSize = 10.sp,
                                color = Color(Ds.MUTED)
                            )
                            TextButton(onClick = { refreshToken++ }, contentPadding = PaddingValues(0.dp)) {
                                Text("Check again", fontSize = 10.sp, color = Color(Ds.LINK))
                            }
                        }
                    }
                }

                SettingsTab.PLAYBACK -> {
                    item {
                        V2Section("Recording") {
                            V2Row(
                                icon = R.drawable.ic_hand,
                                title = "Recording mode",
                                subtitle = "Passive: the game still receives every touch."
                            )
                            AcSegmented(
                                options = listOf("Passive", "Blocks game input"),
                                selected = if (settings.recordingMode == RecordingMode.PASSIVE) 0 else 1,
                                onSelect = { index ->
                                    onUpdate { current ->
                                        current.copy(
                                            recordingMode = if (index == 0) RecordingMode.PASSIVE
                                            else RecordingMode.CAPTURE_ONLY
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item {
                        V2Section("Defaults for new macros") {
                            StepperRowV2(
                                "Speed",
                                MacroTimeline.formatSpeed(settings.defaultSpeed),
                                { onUpdate { it.copy(defaultSpeed = MacroTimeline.stepSpeed(it.defaultSpeed, -1)) } },
                                { onUpdate { it.copy(defaultSpeed = MacroTimeline.stepSpeed(it.defaultSpeed, 1)) } }
                            )
                            V2Row(R.drawable.ic_repeat, "Repeat", "Once, loop forever, or a set number")
                            AcSegmented(
                                options = listOf("Once", "Loop", "Custom"),
                                selected = when {
                                    settings.defaultRepeat == Macro.REPEAT_INFINITE -> 1
                                    MacroTimeline.isCustomRepeat(settings.defaultRepeat) -> 2
                                    else -> 0
                                },
                                onSelect = { index ->
                                    onUpdate { current ->
                                        current.copy(
                                            defaultRepeat = when (index) {
                                                1 -> Macro.REPEAT_INFINITE
                                                2 -> MacroTimeline.customRepeatMode(10)
                                                else -> Macro.REPEAT_ONCE
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item {
                        V2Section("Touch effects") {
                            SwitchRowV2(
                                "While recording",
                                "Show where each touch lands",
                                settings.recordingEffect.enabled
                            ) { value ->
                                onUpdate { it.copy(recordingEffect = it.recordingEffect.copy(enabled = value)) }
                            }
                            EffectEditor(settings.recordingEffect) { config ->
                                onUpdate { it.copy(recordingEffect = config) }
                            }
                        }
                    }
                    item {
                        V2Section {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { playingEditor = !playingEditor }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                EffectIcon(R.drawable.ic_repeat)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("While playing", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(Ds.TEXT))
                                    Text(
                                        "${styleLabel(settings.playingEffect.style)} · " +
                                            "${colorLabel(settings.playingEffect.color)} · " +
                                            "${String.format(Locale.US, "%.1f", settings.playingEffect.fadeMs / 1000.0)} s · " +
                                            if (settings.playingEffect.trail) "trail on" else "trail off",
                                        fontSize = 12.sp,
                                        color = Color(Ds.MUTED)
                                    )
                                }
                                Switch(
                                    checked = settings.playingEffect.enabled,
                                    onCheckedChange = { value ->
                                        onUpdate { it.copy(playingEffect = it.playingEffect.copy(enabled = value)) }
                                    }
                                )
                                Text(
                                    if (playingEditor) "▾" else "›",
                                    fontSize = 18.sp,
                                    color = Color(Ds.MUTED),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            if (playingEditor) {
                                EffectEditor(settings.playingEffect) { config ->
                                    onUpdate { it.copy(playingEffect = config) }
                                }
                            }
                        }
                    }
                    item {
                        V2Section("Feedback") {
                            SwitchRowV2(
                                "Haptic feedback",
                                "Record, finish, save, play and stop",
                                settings.hapticFeedback
                            ) { value -> onUpdate { it.copy(hapticFeedback = value) } }
                            V2Row(R.drawable.ic_speaker, "Intensity", "")
                            AcSegmented(
                                options = listOf("Light", "Medium", "Strong"),
                                selected = when (settings.hapticIntensity) {
                                    "light" -> 0
                                    "strong" -> 2
                                    else -> 1
                                },
                                onSelect = { index ->
                                    onUpdate { current ->
                                        current.copy(
                                            hapticIntensity = when (index) {
                                                0 -> "light"
                                                2 -> "strong"
                                                else -> "medium"
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                EffectIcon(R.drawable.ic_speaker)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Test vibration", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(Ds.TEXT))
                                    Text("Plays the selected strength", fontSize = 12.sp, color = Color(Ds.MUTED))
                                }
                                OutlinedButton(
                                    onClick = { Haptics.test(context, settings.hapticIntensity) },
                                    shape = RoundedCornerShape(10.dp)
                                ) { Text("Test", fontSize = 12.sp) }
                            }
                            SwitchRowV2(
                                "Keep screen awake",
                                "While recording. Uses more battery",
                                settings.keepScreenAwake
                            ) { value -> onUpdate { it.copy(keepScreenAwake = value) } }
                        }
                    }
                }

                SettingsTab.CONTROLLER -> {
                    item {
                        V2Section("Floating controller") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (controllerState != ControllerState.INACTIVE) "Controller is running" else "Controller is stopped",
                                        fontSize = 12.sp,
                                        color = Color(Ds.TEXT)
                                    )
                                    Text(
                                        "State: ${controllerState.name.lowercase().replace('_', ' ')}",
                                        fontSize = 10.sp,
                                        color = Color(Ds.MUTED)
                                    )
                                }
                                Button(
                                    onClick = onOpenController,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.ACCENT))
                                ) { Text(if (controllerState != ControllerState.INACTIVE) "Open" else "Start", fontSize = 11.sp) }
                            }
                            SwitchRowV2("Start minimized", "Show only the floating logo when the controller opens.", settings.startMinimized) {
                                value -> onUpdate { it.copy(startMinimized = value) }
                            }
                            Text("Opacity: ${(settings.controllerAlpha * 100).toInt()}%", fontSize = 12.sp, color = Color(Ds.TEXT))
                            Slider(
                                value = settings.controllerAlpha,
                                onValueChange = { value -> onUpdate { it.copy(controllerAlpha = value) } },
                                valueRange = SettingsStore.MIN_ALPHA..1f,
                                steps = 4
                            )
                            Text(
                                "Controller size: ${(settings.controllerScale * 100).roundToInt()}%",
                                fontSize = 12.sp,
                                color = Color(Ds.TEXT)
                            )
                            Slider(
                                value = settings.controllerScale,
                                onValueChange = { value -> onUpdate { it.copy(controllerScale = value) } },
                                valueRange = SettingsStore.MIN_CONTROLLER_SCALE..SettingsStore.MAX_CONTROLLER_SCALE,
                                steps = 9
                            )
                            Text(
                                "Floating icon size: ${(settings.bubbleScale * 100).roundToInt()}%",
                                fontSize = 12.sp,
                                color = Color(Ds.TEXT)
                            )
                            Text("The controller size never changes the icon size.", fontSize = 9.sp, color = Color(Ds.MUTED))
                            Slider(
                                value = settings.bubbleScale,
                                onValueChange = { value -> onUpdate { it.copy(bubbleScale = value) } },
                                valueRange = SettingsStore.MIN_BUBBLE_SCALE..SettingsStore.MAX_BUBBLE_SCALE,
                                steps = 15
                            )
                            SwitchRowV2(
                                "Auto-dock icon when minimized",
                                if (settings.autoDockIcon) "Minimize sends the icon to the chosen corner."
                                else "The icon appears at the panel logo or where you last dragged it.",
                                settings.autoDockIcon
                            ) { value -> onUpdate { it.copy(autoDockIcon = value) } }
                            Text(
                                "Floating icon corner",
                                fontSize = 12.sp,
                                color = if (settings.autoDockIcon) Color(Ds.TEXT) else Color(Ds.MUTED)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CornerChipV2("Top left", BubbleCorner.TOP_LEFT, settings) { onUpdate { s -> s.copy(bubbleCorner = BubbleCorner.TOP_LEFT) } }
                                CornerChipV2("Top right", BubbleCorner.TOP_RIGHT, settings) { onUpdate { s -> s.copy(bubbleCorner = BubbleCorner.TOP_RIGHT) } }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CornerChipV2("Bottom left", BubbleCorner.BOTTOM_LEFT, settings) { onUpdate { s -> s.copy(bubbleCorner = BubbleCorner.BOTTOM_LEFT) } }
                                CornerChipV2("Bottom right", BubbleCorner.BOTTOM_RIGHT, settings) { onUpdate { s -> s.copy(bubbleCorner = BubbleCorner.BOTTOM_RIGHT) } }
                            }
                            if (!settings.autoDockIcon) {
                                Text("Corner choice is off while auto-dock is disabled.", fontSize = 9.sp, color = Color(Ds.MUTED))
                            }
                            TextButton(onClick = onResetPosition) { Text("Reset floating position", fontSize = 11.sp) }
                        }
                    }
                }

                SettingsTab.DATA -> {
                    if (undoMacros != null || undoAttacks != null) {
                        item {
                            V2Section {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Deleted ${Plural.of((undoMacros?.size ?: 0) + (undoAttacks?.size ?: 0), "item")}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(Ds.TEXT),
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        val ours = undoMacros
                                        val theirs = undoAttacks
                                        undoMacros = null
                                        undoAttacks = null
                                        scope.launch {
                                            if (ours != null) repository.restoreMany(ours)
                                            if (theirs != null) attackRepository.restoreMany(theirs)
                                        }
                                    }) { Text("Undo", fontSize = 13.sp, color = Color(Ds.LINK)) }
                                }
                            }
                        }
                    }
                    item {
                        V2Section("Macro storage") {
                            InfoRowV2("Saved macros", macros.size.toString())
                            InfoRowV2("Storage used", formatBytesV2(storageBytes))
                            Button(
                                onClick = { showClearAll = true },
                                enabled = macros.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.RECORD))
                            ) { Text("Delete all macros", fontSize = 12.sp) }
                            Text("A timestamped backup is saved before a bulk delete.", fontSize = 9.sp, color = Color(Ds.MUTED))
                        }
                    }
                    item {
                        V2Section("Attack history") {
                            InfoRowV2("Recorded attacks", attacks.size.toString())
                            InfoRowV2("Storage used", formatBytesV2(attackRepository.storageBytes()))
                            val unconfirmed = attacks.count { !it.confirmed }
                            Button(
                                onClick = { showRemoveUnconfirmed = true },
                                enabled = unconfirmed > 0,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.WARN))
                            ) { Text("Remove ${Plural.of(unconfirmed, "unconfirmed session")}", fontSize = 12.sp) }
                            Button(
                                onClick = { showClearHistory = true },
                                enabled = attacks.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.RECORD))
                            ) { Text("Clear attack history", fontSize = 12.sp) }
                        }
                    }
                }

                SettingsTab.APP -> {
                    item {
                        V2Section("About") {
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Image(
                                    painterResource(R.drawable.ic_autoclash_full),
                                    contentDescription = null,
                                    modifier = Modifier.size(132.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("AutoClash", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(Ds.TEXT))
                                Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 10.sp, color = Color(Ds.MUTED))
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Record taps and drags, then replay them with adjustable speed and repeat. " +
                                    "For educational use only — automation may violate the game's Terms of Service.",
                                fontSize = 11.sp,
                                color = Color(Ds.MUTED)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Theme: Dark", fontSize = 12.sp, color = Color(Ds.TEXT))
                            Text("Light theme is hidden until it is finished.", fontSize = 9.sp, color = Color(Ds.MUTED))
                            TextButton(onClick = { showPrivacy = true }, contentPadding = PaddingValues(0.dp)) {
                                Text("Privacy", fontSize = 11.sp)
                            }
                            TextButton(onClick = { showLicences = true }, contentPadding = PaddingValues(0.dp)) {
                                Text("Open-source licences", fontSize = 11.sp)
                            }
                        }
                    }
                    item {
                        V2Section("Updates") {
                            InfoRowV2("Installed version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                            Text(
                                "Checks the official GitHub releases. Downloads are verified with SHA-256.",
                                fontSize = 10.sp,
                                color = Color(Ds.MUTED)
                            )
                            Button(
                                onClick = {
                                    if (!updateBusy) {
                                        updateBusy = true
                                        updateStatus = "Checking GitHub…"
                                        scope.launch {
                                            val result = runCatching { com.clahbot.update.GitHubUpdater.check() }
                                                .getOrNull()
                                            updateStatus = when (result) {
                                                is com.clahbot.update.GitHubUpdater.CheckResult.UpToDate -> "You are up to date."
                                                is com.clahbot.update.GitHubUpdater.CheckResult.Available ->
                                                    "Update available: ${result.release.title}"
                                                is com.clahbot.update.GitHubUpdater.CheckResult.Failure -> result.message
                                                null -> "Could not check for updates."
                                            }
                                            updateBusy = false
                                        }
                                    }
                                },
                                enabled = !updateBusy,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.ACCENT))
                            ) { Text(if (updateBusy) "Working…" else "Check GitHub for updates", fontSize = 12.sp) }
                            updateStatus?.let { Text(it, fontSize = 10.sp, color = Color(Ds.MUTED)) }
                        }
                    }
                    item {
                        V2Section("Credits") {
                            InfoRowV2("Developer", "Mir Majid")
                            InfoRowV2("Clash profile", "M-A-J-I-D")
                            InfoRowV2("GitHub", "mir-majid01")
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun SettingsIslandBar(selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xF2172033))
                .border(1.dp, Color(0xFF2B3B5C), RoundedCornerShape(30.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SettingsTab.entries.forEachIndexed { index, tab ->
                val active = index == selected
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (active) Color(Ds.ACCENT) else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = if (active) 14.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painterResource(tab.icon),
                        contentDescription = tab.label,
                        colorFilter = if (active) ColorFilter.tint(Color.White) else null,
                        modifier = Modifier.size(20.dp)
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Text(tab.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShizukuCard(
    access: InputAccessManager.AccessStatus,
    onRequest: () -> Unit,
    onOpen: () -> Unit,
    onInstall: () -> Unit
) {
    val context = LocalContext.current
    val icon = remember {
        runCatching {
            context.packageManager.getApplicationIcon(InputAccessManager.SHIZUKU_PACKAGE)
                .toBitmap(72, 72).asImageBitmap()
        }.getOrNull()
    }
    V2Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF14234A)),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(icon, contentDescription = "Shizuku", modifier = Modifier.size(38.dp))
                } else {
                    Image(painterResource(R.drawable.ic_gear), null, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    InputAccessManager.title(access),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(Ds.TEXT)
                )
                Text(InputAccessManager.detail(access), fontSize = 11.sp, color = Color(Ds.MUTED))
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            access.shizukuRunning && !access.shizukuGranted ->
                Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.ACCENT))) {
                    Text("Grant Shizuku access", fontSize = 11.sp)
                }
            access.shizukuInstalled && !access.shizukuRunning ->
                Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.ACCENT))) {
                    Text("Open Shizuku", fontSize = 11.sp)
                }
            !access.shizukuInstalled && !access.rootPossible ->
                Button(onClick = onInstall, colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.ACCENT))) {
                    Text("Get Shizuku", fontSize = 11.sp)
                }
        }
        if (access.rootPossible && !access.shizukuGranted) {
            Text(
                "Root will be checked when recording starts.",
                fontSize = 10.sp,
                color = Color(Ds.MUTED)
            )
        }
    }
}

@Composable
private fun EffectEditor(config: TouchEffectConfig, onChange: (TouchEffectConfig) -> Unit) {
    V2Row(R.drawable.ic_hand, "Style", "")
    AcSegmented(
        options = listOf("Ring", "Dot", "Ripple"),
        selected = when (config.style) {
            EffectStyle.RING -> 0
            EffectStyle.DOT -> 1
            EffectStyle.RIPPLE -> 2
        },
        onSelect = { index ->
            onChange(
                config.copy(
                    style = when (index) {
                        0 -> EffectStyle.RING
                        1 -> EffectStyle.DOT
                        else -> EffectStyle.RIPPLE
                    }
                )
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
    V2Row(R.drawable.ic_pin, "Colour", "")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        EFFECT_COLORS.forEach { color ->
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(
                        if (color == config.color) 2.dp else 1.dp,
                        if (color == config.color) Color(Ds.TEXT) else Color(0xFF3A4A70),
                        CircleShape
                    )
                    .clickable { onChange(config.copy(color = color)) }
            )
        }
    }
    Text(
        "Size: ${config.sizeDp} dp",
        fontSize = 12.sp,
        color = Color(Ds.TEXT)
    )
    Slider(
        value = config.sizeDp.toFloat(),
        onValueChange = { onChange(config.copy(sizeDp = it.roundToInt())) },
        valueRange = 16f..96f,
        steps = 15
    )
    Text(
        "Fade time: ${String.format(Locale.US, "%.1f", config.fadeMs / 1000.0)} s",
        fontSize = 12.sp,
        color = Color(Ds.TEXT)
    )
    Slider(
        value = config.fadeMs.toFloat(),
        onValueChange = { onChange(config.copy(fadeMs = it.roundToInt())) },
        valueRange = 200f..1500f,
        steps = 12
    )
    SwitchRowV2("Trail for drags", "Follow the finger along the swipe", config.trail) {
        onChange(config.copy(trail = it))
    }
    EffectPreview(config)
}

@Composable
private fun EffectPreview(config: TouchEffectConfig) {
    V2Row(R.drawable.ic_timer, "Preview", "Shown here only, never on the game")
    Box(
        Modifier.fillMaxWidth().height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(Ds.INPUT)),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            val size = (config.sizeDp.coerceIn(16, 64) * 0.75f).dp
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
                when (config.style) {
                    EffectStyle.RING -> Box(
                        Modifier.fillMaxSize().border(2.dp, Color(config.color), CircleShape)
                    )
                    EffectStyle.DOT -> Box(
                        Modifier.size(size / 2).clip(CircleShape).background(Color(config.color))
                    )
                    EffectStyle.RIPPLE -> {
                        Box(Modifier.fillMaxSize().border(2.dp, Color(config.color).copy(alpha = 0.35f), CircleShape))
                        Box(Modifier.size(size / 2).border(2.dp, Color(config.color).copy(alpha = 0.7f), CircleShape))
                        Box(Modifier.size(size / 5).clip(CircleShape).background(Color(config.color)))
                    }
                }
            }
            if (config.trail) {
                Box(
                    Modifier.width(100.dp).height(3.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(Color.Transparent, Color(config.color))
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun V2Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        title?.let {
            Text(
                it.uppercase(Locale.US),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(Ds.MUTED),
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(Ds.PANEL)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(Ds.PANEL_BORDER))
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), content = content)
        }
    }
}

@Composable
private fun V2Row(icon: Int, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EffectIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(Ds.TEXT))
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = Color(Ds.MUTED))
        }
    }
}

@Composable
private fun EffectIcon(icon: Int) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF14234A)),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(icon), null, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SwitchRowV2(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(Ds.TEXT))
            Text(subtitle, fontSize = 12.sp, color = Color(Ds.MUTED))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StepperRowV2(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(Ds.TEXT), modifier = Modifier.weight(1f))
        TextButton(onClick = onMinus, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("−", fontSize = 17.sp) }
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(Ds.TEXT), textAlign = TextAlign.Center, modifier = Modifier.width(52.dp))
        TextButton(onClick = onPlus, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+", fontSize = 17.sp) }
    }
}

@Composable
private fun PermissionRowV2(
    label: String,
    granted: Boolean,
    statusText: String? = null,
    onOpen: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (granted) Color(Ds.SUCCESS) else Color(Ds.WARN)))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, color = Color(Ds.TEXT), modifier = Modifier.weight(1f))
        if (granted) {
            Text(statusText ?: "Granted", fontSize = 10.sp, color = Color(Ds.SUCCESS))
        } else {
            statusText?.let {
                Text(it, fontSize = 10.sp, color = Color(Ds.WARN))
                Spacer(Modifier.width(4.dp))
            }
            TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (statusText == "Not connected") "Fix" else "Enable", fontSize = 10.sp, color = Color(Ds.WARN))
            }
        }
    }
}

@Composable
private fun InfoRowV2(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Color(Ds.MUTED), modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, color = Color(Ds.TEXT))
    }
}

@Composable
private fun CornerChipV2(label: String, corner: BubbleCorner, settings: AppSettings, onSelect: () -> Unit) {
    FilterChip(
        selected = settings.bubbleCorner == corner,
        onClick = onSelect,
        enabled = settings.autoDockIcon,
        label = { Text(label, fontSize = 11.sp) }
    )
}

private fun styleLabel(style: EffectStyle): String = when (style) {
    EffectStyle.RING -> "Ring"
    EffectStyle.DOT -> "Dot"
    EffectStyle.RIPPLE -> "Ripple"
}

private fun colorLabel(color: Int): String = when (color) {
    0xFFFFFFFF.toInt() -> "White"
    0xFF3D7BFF.toInt() -> "Blue"
    0xFF4ADE80.toInt() -> "Green"
    0xFFFFB020.toInt() -> "Amber"
    0xFFF87171.toInt() -> "Red"
    else -> "Violet"
}

private fun formatBytesV2(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}

private val EFFECT_COLORS = listOf(
    0xFFFFFFFF.toInt(),
    0xFF3D7BFF.toInt(),
    0xFF4ADE80.toInt(),
    0xFFFFB020.toInt(),
    0xFFF87171.toInt(),
    0xFFD946EF.toInt()
)
