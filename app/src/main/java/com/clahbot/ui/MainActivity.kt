package com.clahbot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clahbot.data.BotState
import com.clahbot.data.ControllerState
import com.clahbot.data.MacroRepository
import com.clahbot.data.models.Macro
import com.clahbot.execution.MacroTimeline
import com.clahbot.service.ClahAccessibilityService
import com.clahbot.service.FloatingMenuService
import com.clahbot.util.PermissionHelper
import kotlinx.coroutines.delay
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
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4CAF50),
                    background = Color(0xFF1A1A2E),
                    surface = Color(0xFF16213E)
                )
            ) {
                MainScreen(
                    viewModel = viewModel,
                    onStartFloating = { startFloating() },
                    onEnterGame = { openClashOfClans() },
                    onPlayMacro = { macro -> playMacro(macro) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadMacros()
    }

    private fun startFloating() {
        if (!PermissionHelper.canDrawOverlays(this) || !ClahAccessibilityService.isEnabled()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            return
        }
        requestNotificationsIfNeeded()
        FloatingMenuService.show(this)
    }

    private fun playMacro(macro: Macro) {
        if (!ClahAccessibilityService.isEnabled()) {
            PermissionHelper.showToast(this, "Enable the ClahBOT accessibility service first")
            PermissionHelper.openAccessibilitySettings(this)
            return
        }
        if (!PermissionHelper.canDrawOverlays(this)) {
            PermissionHelper.showToast(this, "Overlay permission is required to control playback")
            startActivity(Intent(this, OnboardingActivity::class.java))
            return
        }
        requestNotificationsIfNeeded()
        FloatingMenuService.play(this, macro.id)
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionHelper.canPostNotifications(this)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openClashOfClans() {
        val candidates = listOf(
            "com.supercell.clashofclans",
            "com.supercell.clashofclans.overseas",
            "com.supercell.clashofclans.kunlun",
            "com.supercell.clashofclans.google"
        )
        for (pkg in candidates) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) {
                // Try the next package name.
            }
        }
        try {
            val allApps = packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
            val cocApp = allApps.firstOrNull { app ->
                val label = try {
                    app.loadLabel(packageManager).toString()
                } catch (_: Exception) {
                    ""
                }
                app.packageName.contains("clash", ignoreCase = true) ||
                    label.contains("Clash of Clans", ignoreCase = true) ||
                    app.packageName.contains("supercell", ignoreCase = true)
            }
            if (cocApp != null) {
                val intent = packageManager.getLaunchIntentForPackage(cocApp.packageName)
                if (intent != null) {
                    startActivity(intent)
                    return
                }
                PermissionHelper.showToast(
                    this,
                    "Found Clash of Clans but cannot launch it"
                )
                return
            }
        } catch (_: Exception) {
            // Fall through to the toast below.
        }
        PermissionHelper.showToast(this, "Clash of Clans is not installed on this device")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onStartFloating: () -> Unit,
    onEnterGame: () -> Unit,
    onPlayMacro: (Macro) -> Unit
) {
    val macros by viewModel.macros.collectAsState()
    val controllerState by BotState.state.collectAsState()
    val context = LocalContext.current
    var accessibilityOk by remember { mutableStateOf(ClahAccessibilityService.isEnabled()) }
    var editing by remember { mutableStateOf<Macro?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadMacros()
        while (true) {
            accessibilityOk = ClahAccessibilityService.isEnabled()
            delay(1500)
        }
    }

    editing?.let { macro ->
        EditMacroDialog(
            macro = macro,
            onDismiss = { editing = null },
            onSave = { name, speed, loop ->
                viewModel.updateMacro(
                    macro.copy(
                        name = name,
                        speedMultiplier = speed,
                        repeatMode = if (loop) Macro.REPEAT_INFINITE else Macro.REPEAT_ONCE
                    )
                )
                editing = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ClahBOT", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF16213E))
            )
        },
        containerColor = Color(0xFF1A1A2E)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!accessibilityOk) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4E2A00)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Accessibility service not enabled",
                            color = Color(0xFFFF9800),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Enable ClahBOT in Settings > Accessibility so it can replay gestures.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { PermissionHelper.openAccessibilitySettings(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Text("Open Accessibility Settings", fontSize = 12.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartFloating,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) {
                    Text("Start Floating", fontSize = 14.sp)
                }
                Button(
                    onClick = onEnterGame,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Enter Game", fontSize = 14.sp)
                }
            }

            Text(
                "Recorded Attacks",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(macros, key = { it.id }) { macro ->
                    MacroDashboardCard(
                        macro = macro,
                        isPlaying = controllerState == ControllerState.PLAYING ||
                            controllerState == ControllerState.PAUSED ||
                            controllerState == ControllerState.RECORDING,
                        onPlay = { onPlayMacro(macro) },
                        onEdit = { editing = macro },
                        onDelete = { viewModel.deleteMacro(macro.id) }
                    )
                }
                if (macros.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("No recordings yet", color = Color.Gray, fontSize = 14.sp)
                                Text(
                                    "Tap 'Start Floating' then Record to capture an attack",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MacroDashboardCard(
    macro: Macro,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(macro.createdAt) {
        SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(macro.createdAt))
    }
    val formattedDuration = remember(macro.durationMs) {
        val seconds = (macro.durationMs / 1000) % 60
        val minutes = macro.durationMs / 60000
        String.format(Locale.US, "%dm %02ds", minutes, seconds)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(macro.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${macro.gestures.size} actions", color = Color.Gray, fontSize = 11.sp)
                    Text(formattedDuration, color = Color(0xFF4CAF50), fontSize = 11.sp)
                    Text(
                        MacroTimeline.formatSpeed(macro.speedMultiplier),
                        color = Color(0xFF81C784),
                        fontSize = 11.sp
                    )
                }
                Text(
                    "${if (macro.loops) "Loop" else "Once"} · ${macro.orientation} · $dateStr",
                    color = Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
            IconButton(
                onClick = onPlay,
                enabled = !isPlaying,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    "Play",
                    tint = if (isPlaying) Color.Gray else Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Edit,
                    "Edit",
                    tint = Color(0xFF90CAF9),
                    modifier = Modifier.size(17.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    "Delete",
                    tint = Color(0xFFF44336),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun EditMacroDialog(
    macro: Macro,
    onDismiss: () -> Unit,
    onSave: (String, Float, Boolean) -> Unit
) {
    var name by remember(macro.id) { mutableStateOf(macro.name) }
    var speed by remember(macro.id) { mutableStateOf(macro.speedMultiplier) }
    var loop by remember(macro.id) { mutableStateOf(macro.loops) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Macro") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text("Playback speed", color = Color.Gray, fontSize = 12.sp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { speed = MacroTimeline.stepSpeed(speed, -1) }) {
                        Text("−")
                    }
                    Text(
                        MacroTimeline.formatSpeed(speed),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { speed = MacroTimeline.stepSpeed(speed, 1) }) {
                        Text("+")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Repeat", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(checked = loop, onCheckedChange = { loop = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), speed, loop) },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
