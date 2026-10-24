package com.clahbot.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clahbot.BuildConfig
import com.clahbot.R
import com.clahbot.data.AppSettings
import com.clahbot.data.AttackRepository
import com.clahbot.data.BotState
import com.clahbot.data.ControllerState
import com.clahbot.data.MacroRepository
import com.clahbot.data.SettingsStore
import com.clahbot.data.ThemeMode
import com.clahbot.data.models.Macro
import com.clahbot.execution.MacroTimeline
import com.clahbot.service.FloatingMenuService
import com.clahbot.ui.theme.AutoClashTheme
import com.clahbot.ui.theme.ElectricBlue
import com.clahbot.ui.theme.PauseAmber
import com.clahbot.ui.theme.ReadyGreen
import com.clahbot.ui.theme.RecordRed
import com.clahbot.ui.theme.TextMuted
import com.clahbot.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val GITHUB_URL = "https://github.com/mir-majid01/clahbot"
private val TELEGRAM_URL: String? = null

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore.get(this)
        setContent {
            val settings by store.settings.collectAsState()
            AutoClashTheme(settings.theme) {
                SettingsScreen(
                    settings = settings,
                    onUpdate = { transform -> store.update(transform) },
                    onBack = { finish() },
                    onOpenController = { FloatingMenuService.show(this) },
                    onResetPosition = { FloatingMenuService.resetPosition(this) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onOpenController: () -> Unit,
    onResetPosition: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { MacroRepository.get(context) }
    val attackRepository = remember { AttackRepository.get(context) }
    val macros by repository.macros.collectAsState()
    val attacks by attackRepository.records.collectAsState()
    val controllerState by BotState.state.collectAsState()

    var tick by remember { mutableStateOf(0) }
    var showClearAll by remember { mutableStateOf(false) }
    var showClearHistory by remember { mutableStateOf(false) }
    var showRemoveUnconfirmed by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showLicences by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repository.refresh()
        attackRepository.refresh()
        while (true) {
            delay(1500)
            tick++
        }
    }

    val overlayOk = remember(tick) { PermissionHelper.canDrawOverlays(context) }
    val accessibilityOk = remember(tick) { PermissionHelper.isAccessibilityEnabled(context) }
    val notificationsOk = remember(tick) { PermissionHelper.canPostNotifications(context) }
    val storageBytes = remember(tick, macros) { repository.storageBytes() }
    val controllerActive = controllerState != ControllerState.INACTIVE

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("Delete all macros?") },
            text = {
                Text(
                    "This permanently removes ${macros.size} saved macro(s) from this device. " +
                        "It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.clearAll() }
                    showClearAll = false
                }) { Text("Delete all", color = RecordRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text("Cancel") }
            }
        )
    }

    if (showRemoveUnconfirmed) {
        val unconfirmed = attacks.filter { !it.confirmed }
        AlertDialog(
            onDismissRequest = { showRemoveUnconfirmed = false },
            title = { Text("Remove unconfirmed sessions?") },
            text = {
                Text(
                    "This deletes ${unconfirmed.size} playback session(s) that were never confirmed as attacks. " +
                        "Confirmed attacks are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { unconfirmed.forEach { attackRepository.delete(it.id) } }
                    showRemoveUnconfirmed = false
                }) { Text("Remove", color = RecordRed) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveUnconfirmed = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearHistory) {
        AlertDialog(
            onDismissRequest = { showClearHistory = false },
            title = { Text("Clear attack history?") },
            text = {
                Text(
                    "This deletes ${attacks.size} attack record(s). Saved macros are not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { attackRepository.clearAll() }
                    showClearHistory = false
                }) { Text("Clear history", color = RecordRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistory = false }) { Text("Cancel") }
            }
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Privacy") },
            text = {
                Text(
                    "AutoClash stores macros and settings only on this device. " +
                        "The app has no network permission and sends nothing anywhere. " +
                        "It does not read screen content; the accessibility service is used only to " +
                        "replay the gestures you record."
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) { Text("Close") }
            }
        )
    }

    if (showLicences) {
        AlertDialog(
            onDismissRequest = { showLicences = false },
            title = { Text("Open-source licences") },
            text = {
                Text(
                    "AutoClash is built with Kotlin, Jetpack Compose, AndroidX, " +
                        "Kotlin Coroutines and Gson. These components are used under the " +
                        "Apache License 2.0. The full licence texts ship with each library " +
                        "and are available from their project pages."
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicences = false }) { Text("Close") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    "Settings",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection("Recording & playback") {
                    StepperRow(
                        title = "Default speed",
                        value = MacroTimeline.formatSpeed(settings.defaultSpeed),
                        onMinus = {
                            onUpdate {
                                it.copy(defaultSpeed = MacroTimeline.stepSpeed(it.defaultSpeed, -1))
                            }
                        },
                        onPlus = {
                            onUpdate {
                                it.copy(defaultSpeed = MacroTimeline.stepSpeed(it.defaultSpeed, 1))
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Default repeat", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MacroTimeline.REPEAT_CYCLE.forEach { mode ->
                            FilterChip(
                                selected = settings.defaultRepeat == mode,
                                onClick = { onUpdate { it.copy(defaultRepeat = mode) } },
                                label = { Text(MacroTimeline.repeatLabel(mode), fontSize = 11.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SwitchRow(
                        title = "Haptic feedback",
                        subtitle = "Vibrate on record, save and stop actions.",
                        checked = settings.hapticFeedback,
                        onCheckedChange = { value -> onUpdate { it.copy(hapticFeedback = value) } }
                    )
                    SwitchRow(
                        title = "Keep screen awake while recording",
                        subtitle = "Prevents the display from sleeping during a recording. Uses more battery.",
                        checked = settings.keepScreenAwake,
                        onCheckedChange = { value -> onUpdate { it.copy(keepScreenAwake = value) } }
                    )
                }
            }

            item {
                SettingsSection("Floating controller") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (controllerActive) "Controller is running" else "Controller is stopped",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "State: ${controllerState.name.lowercase().replace('_', ' ')}",
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }
                        Button(
                            onClick = onOpenController,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                        ) {
                            Text(if (controllerActive) "Open" else "Start", fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SwitchRow(
                        title = "Start minimized",
                        subtitle = "Show only the floating logo when the controller opens.",
                        checked = settings.startMinimized,
                        onCheckedChange = { value -> onUpdate { it.copy(startMinimized = value) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Opacity: ${(settings.controllerAlpha * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = settings.controllerAlpha,
                        onValueChange = { value ->
                            onUpdate { it.copy(controllerAlpha = value.coerceIn(SettingsStore.MIN_ALPHA, 1f)) }
                        },
                        valueRange = SettingsStore.MIN_ALPHA..1f,
                        steps = 4
                    )
                    Row {
                        TextButton(onClick = onResetPosition) {
                            Text("Reset controller position", fontSize = 11.sp)
                        }
                    }
                }
            }

            item {
                SettingsSection("Permissions & diagnostics") {
                    PermissionStatusRow(
                        label = "Accessibility service",
                        granted = accessibilityOk,
                        onOpen = { PermissionHelper.openAccessibilitySettings(context) }
                    )
                    PermissionStatusRow(
                        label = "Display over other apps",
                        granted = overlayOk,
                        onOpen = { PermissionHelper.requestOverlayPermission(context) }
                    )
                    PermissionStatusRow(
                        label = "Notifications",
                        granted = notificationsOk,
                        onOpen = { PermissionHelper.openNotificationSettings(context) }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Android does not give apps a raw touch stream from another app. " +
                            "AutoClash records by intercepting touches on a transparent overlay and " +
                            "replaying them through the accessibility service, so a recorded gesture " +
                            "is injected rather than observed. Overlay and accessibility access are " +
                            "required; without them recording and playback are unavailable.",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            }

            item {
                SettingsSection("Macro storage") {
                    InfoRow("Saved macros", macros.size.toString())
                    InfoRow("Storage used", formatBytes(storageBytes))
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { showClearAll = true },
                        enabled = macros.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RecordRed,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text("Delete all macros", fontSize = 12.sp)
                    }
                }
            }

            item {
                SettingsSection("Attack history") {
                    InfoRow("Recorded attacks", attacks.size.toString())
                    InfoRow("Storage used", formatBytes(attackRepository.storageBytes()))
                    Spacer(Modifier.height(10.dp))
                    val unconfirmedCount = attacks.count { !it.confirmed }
                    Button(
                        onClick = { showRemoveUnconfirmed = true },
                        enabled = unconfirmedCount > 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PauseAmber,
                            contentColor = Color(0xFF1C1917),
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text("Remove $unconfirmedCount unconfirmed session(s)", fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showClearHistory = true },
                        enabled = attacks.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RecordRed,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text("Clear attack history", fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Only confirmed records count as attacks. Playback sessions stay unconfirmed " +
                            "until you enter the result-screen loot. Loot values are only stored when you " +
                            "enter them; the app never invents a number it did not observe.",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            }

            item {
                SettingsSection("Appearance") {
                    Text("Theme", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.theme == mode,
                                onClick = { onUpdate { it.copy(theme = mode) } },
                                label = {
                                    Text(
                                        mode.name.lowercase().replaceFirstChar { it.uppercase() },
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection("About") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.ic_autoclash_full),
                            contentDescription = null,
                            modifier = Modifier.size(132.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "AutoClash",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Version ${BuildConfig.VERSION_NAME}",
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Record taps and drags, then replay them with adjustable speed and repeat. " +
                            "For educational use only — automation may violate the game's Terms of Service.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    LinkRow("GitHub repository", GITHUB_URL)
                    LinkRow("Telegram community", TELEGRAM_URL)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showPrivacy = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Privacy", fontSize = 11.sp)
                    }
                    TextButton(onClick = { showLicences = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Open-source licences", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMuted,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(14.dp), content = content)
        }
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("Affects delay and gesture duration.", fontSize = 10.sp, color = TextMuted)
        }
        TextButton(onClick = onMinus, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("−", fontSize = 16.sp)
        }
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = ElectricBlue,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(48.dp)
        )
        TextButton(onClick = onPlus, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("+", fontSize = 16.sp)
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 10.sp, color = TextMuted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(
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
            TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Enable", fontSize = 10.sp, color = PauseAmber)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LinkRow(label: String, url: String?) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (url == null) {
            Text("Not configured", fontSize = 10.sp, color = TextMuted)
        } else {
            TextButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        PermissionHelper.showToast(context, "No app can open this link")
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text("Open", fontSize = 11.sp, color = ElectricBlue)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
