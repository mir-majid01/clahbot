package com.clahbot.ui.dashboard

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.clahbot.data.MacroNaming
import com.clahbot.data.MacroWriteResult
import com.clahbot.data.models.Macro
import com.clahbot.recovery.RecoveryStats
import com.clahbot.recovery.UnknownScreen
import com.clahbot.recovery.RecoveryRule
import com.clahbot.recovery.RecoveryActionType
import com.clahbot.ui.design.Ds
import com.clahbot.walls.WallResourceMode
import com.clahbot.walls.WallRunLog
import com.clahbot.walls.WallSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class TeachAction(val label: String) {
    TAP_HERE("Tap here"),
    CLOSE_X("Close ✕"),
    WAIT("Wait"),
    GO_HOME("Go home")
}

/** Full-screen gallery of unknown screens with a Teach button per row. */
@Composable
fun AcUnknownGallery(
    entries: List<UnknownScreen>,
    loadBitmap: (UnknownScreen) -> Bitmap?,
    onTeach: (UnknownScreen) -> Unit,
    onDelete: (UnknownScreen) -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color(Ds.PANEL),
        title = {
            Text("Unknown screens", color = Color(Ds.TEXT), fontWeight = FontWeight.Bold)
        },
        text = {
            if (entries.isEmpty()) {
                Text(
                    "No unknown screens yet. The watchdog saves a capped screenshot when a " +
                        "screen stays unrecognised for 15 s.",
                    fontSize = 12.sp,
                    color = Color(Ds.MUTED)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        val bitmap = remember(entry.id) { loadBitmap(entry) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(Color(Ds.INPUT), RoundedCornerShape(Ds.RADIUS_ROW.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(width = 96.dp, height = 54.dp)
                                    .background(Color(Ds.BACKDROP), RoundedCornerShape(6.dp))
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Unknown screen",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.context,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(Ds.TEXT)
                                )
                                Text(
                                    acClock(entry.at) + if (entry.taught) " · taught" else "",
                                    fontSize = 10.sp,
                                    color = if (entry.taught) Color(Ds.SUCCESS) else Color(Ds.MUTED)
                                )
                            }
                            TextButton(onClick = { onTeach(entry) }) {
                                Text("Teach", fontSize = 12.sp, color = Color(Ds.LINK))
                            }
                            TextButton(onClick = { onDelete(entry) }) {
                                Text("Delete", fontSize = 12.sp, color = Color(Ds.DANGER_TEXT))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("Close", color = Color(Ds.LINK))
            }
        }
    )
}

/**
 * Teach sheet: tap the screenshot to choose where to press, name the rule and
 * pick the action. Saving stores a hash-matched rule; the never-press guard
 * still applies when it runs.
 */
@Composable
fun AcTeachSheet(
    entry: UnknownScreen,
    bitmap: Bitmap?,
    onSave: (name: String, action: TeachAction, tapX: Float?, tapY: Float?) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("Unknown screen rule") }
    var action by remember { mutableStateOf(TeachAction.TAP_HERE) }
    var tapX by remember { mutableStateOf<Float?>(null) }
    var tapY by remember { mutableStateOf<Float?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color(Ds.PANEL),
        title = {
            Text("Teach this screen", color = Color(Ds.TEXT), fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                TeachImagePicker(
                    bitmap = bitmap,
                    tapX = tapX,
                    tapY = tapY,
                    onTap = { x, y ->
                        tapX = x
                        tapY = y
                    }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (tapX == null) "Tap the screenshot where the app should press."
                    else "Tap point saved · tap again to move it.",
                    fontSize = 10.5.sp,
                    color = Color(Ds.MUTED)
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TeachAction.entries.forEach { option ->
                        val selected = option == action
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .background(
                                    if (selected) Color(Ds.ACCENT) else Color(Ds.BUTTON),
                                    RoundedCornerShape(Ds.RADIUS_BUTTON.dp)
                                )
                                .clickable { action = option },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                option.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) Color.White else Color(Ds.TEXT)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Rule name", fontSize = 11.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(Ds.TEXT),
                        unfocusedTextColor = Color(Ds.TEXT),
                        focusedBorderColor = Color(Ds.ACCENT),
                        unfocusedBorderColor = Color(Ds.PANEL_BORDER)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val needsPoint = action == TeachAction.TAP_HERE
                    if (needsPoint && (tapX == null || tapY == null)) return@TextButton
                    onSave(name.ifBlank { "Unknown screen rule" }, action, tapX, tapY)
                }
            ) {
                Text("Save rule", color = Color(Ds.LINK), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Color(Ds.MUTED))
            }
        }
    )
}

/**
 * The dashboard side of Auto Walls. It reads and writes the same walls.json as
 * the controller's Walls tab, so a change in either place shows immediately in
 * the other. Wall macros live here too: pick, rename, delete.
 */
@Composable
fun AcWallsSection(
    settings: WallSettings,
    wallMacros: List<Macro>,
    linkCounts: Map<Long, Int> = emptyMap(),
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    saveError: String? = null,
    onToggle: (Boolean) -> Unit,
    onSelectMacro: (Long) -> Unit,
    onMode: (WallResourceMode) -> Unit,
    onCost: (Long) -> Unit,
    onReserve: (Long) -> Unit,
    onRepeat: (Boolean) -> Unit,
    onRun: () -> Unit,
    onTest: () -> Unit,
    onRenameMacro: suspend (Macro, String) -> MacroWriteResult,
    onDeleteMacro: (Macro) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Macro?>(null) }
    var pendingDelete by remember { mutableStateOf<Macro?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameSaving by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val selected = wallMacros.firstOrNull { it.id == settings.wallMacroId }
    val costState = remember(settings.costPerWall) {
        mutableStateOf(if (settings.costPerWall > 0) settings.costPerWall.toString() else "")
    }
    val reserveState = remember(settings.minReserve) {
        mutableStateOf(if (settings.minReserve > 0) settings.minReserve.toString() else "")
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(Ds.PANEL), RoundedCornerShape(Ds.RADIUS_CARD.dp))
            .border(1.dp, Color(Ds.PANEL_BORDER), RoundedCornerShape(Ds.RADIUS_CARD.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Auto Walls",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(Ds.TEXT),
                modifier = Modifier.weight(1f)
            )
            Text(
                if (settings.enabled) "ON" else "OFF",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (settings.enabled) Color(Ds.SUCCESS) else Color(Ds.MUTED)
            )
            Spacer(Modifier.width(8.dp))
            Switch(checked = settings.enabled, onCheckedChange = onToggle)
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onExpandedChange(!expanded) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (expanded) "▴" else "▾",
                    fontSize = 15.sp,
                    color = Color(Ds.LINK)
                )
            }
        }
        Text(
            settings.summary(selected?.name) +
                if (saveError == null) "" else " · not saved: $saveError",
            fontSize = 11.sp,
            color = Color(Ds.MUTED)
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(Modifier.padding(top = 10.dp)) {
            AcWallsLabel("Wall upgrade macro")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(Color(Ds.INPUT), RoundedCornerShape(Ds.RADIUS_ROW.dp))
                    .clickable { showPicker = true }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    selected?.name ?: "Choose a wall macro",
                    fontSize = 12.5.sp,
                    color = Color(Ds.TEXT)
                )
            }

            Spacer(Modifier.height(10.dp))
            AcWallsLabel("Pay with")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WallResourceMode.entries.forEach { mode ->
                    val on = mode == settings.resourceMode
                    Box(
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .background(
                                if (on) Color(Ds.ACCENT) else Color(Ds.BUTTON),
                                RoundedCornerShape(Ds.RADIUS_BUTTON.dp)
                            )
                            .clickable { onMode(mode) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            mode.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (on) Color.White else Color(Ds.MUTED)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    AcWallsLabel("Cost per wall")
                    AcWallsNumber(costState.value) { raw ->
                        costState.value = raw
                        onCost(raw.toLongOrNull() ?: 0L)
                    }
                }
                Column(Modifier.weight(1f)) {
                    AcWallsLabel("Keep in reserve")
                    AcWallsNumber(reserveState.value) { raw ->
                        reserveState.value = raw
                        onReserve(raw.toLongOrNull() ?: 0L)
                    }
                }
            }
            Text(
                "Walls cost the same in gold and elixir, so one number covers both.",
                fontSize = 10.sp,
                color = Color(Ds.MUTED)
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Upgrade again on the same visit",
                    fontSize = 12.sp,
                    color = Color(Ds.TEXT),
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = settings.allowRepeatUpgrades, onCheckedChange = onRepeat)
            }
            Text(
                "Only repeats after the last upgrade is confirmed by a balance drop, up to 5 per visit.",
                fontSize = 10.sp,
                color = Color(Ds.MUTED)
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onTest,
                    modifier = Modifier.weight(1f).height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.BUTTON)),
                    shape = RoundedCornerShape(Ds.RADIUS_BUTTON.dp)
                ) {
                    Text("Test macro", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(Ds.TEXT))
                }
                Button(
                    onClick = onRun,
                    modifier = Modifier.weight(1f).height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.ACCENT)),
                    shape = RoundedCornerShape(Ds.RADIUS_BUTTON.dp)
                ) {
                    Text("Run now", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            if (wallMacros.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AcWallsLabel("Wall macros")
                wallMacros.forEach { macro ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(macro.name, fontSize = 12.5.sp, color = Color(Ds.TEXT))
                            Text(
                                com.clahbot.util.Plural.of(macro.gestures.size, "gesture"),
                                fontSize = 10.sp,
                                color = Color(Ds.MUTED)
                            )
                        }
                        TextButton(onClick = {
                            renaming = macro
                            renameText = macro.name
                            renameError = null
                        }) {
                            Text("Rename", fontSize = 11.5.sp, color = Color(Ds.LINK))
                        }
                        TextButton(onClick = { pendingDelete = macro }) {
                            Text("Delete", fontSize = 11.5.sp, color = Color(Ds.DANGER_TEXT))
                        }
                    }
                }
            }

            val recent = settings.history.takeLast(3).reversed()
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                AcWallsLabel("Last upgrades")
                recent.forEach { entry ->
                    Text(
                        "${acClock(entry.at)} · ${entry.status} · ${entry.detail}",
                        fontSize = 10.5.sp,
                        color = Color(Ds.MUTED)
                    )
                }
            }
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            containerColor = Color(Ds.PANEL),
            title = { Text("Wall macros", color = Color(Ds.TEXT)) },
            text = {
                Column {
                    AcWallsPickerRow(
                        label = "None",
                        selected = settings.wallMacroId <= 0,
                        onClick = {
                            onSelectMacro(-1L)
                            showPicker = false
                        }
                    )
                    wallMacros.forEach { macro ->
                        AcWallsPickerRow(
                            label = macro.name,
                            selected = macro.id == settings.wallMacroId,
                            onClick = {
                                onSelectMacro(macro.id)
                                showPicker = false
                            }
                        )
                    }
                    if (wallMacros.isEmpty()) {
                        Text(
                            "No wall macros yet. Record one from the controller's Walls tab.",
                            fontSize = 11.5.sp,
                            color = Color(Ds.MUTED)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Close", color = Color(Ds.LINK))
                }
            }
        )
    }

    pendingDelete?.let { macro ->
        val links = linkCounts[macro.id] ?: 0
        val isSelected = settings.wallMacroId == macro.id
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Color(Ds.PANEL),
            title = { Text("Delete wall macro?", color = Color(Ds.TEXT)) },
            text = {
                Text(
                    buildString {
                        append("\"${macro.name}\" will be removed.")
                        if (isSelected) append("\nIt is currently selected for Auto Walls.")
                        if (links > 0) {
                            append(
                                "\n${com.clahbot.util.Plural.of(links, "attack macro")} " +
                                    "link to it; those links will be cleared."
                            )
                        }
                    },
                    fontSize = 12.5.sp,
                    color = Color(Ds.TEXT)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteMacro(macro)
                    pendingDelete = null
                }) {
                    Text("Delete", color = Color(Ds.DANGER_TEXT))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = Color(Ds.LINK))
                }
            }
        )
    }

    renaming?.let { macro ->
        AlertDialog(
            onDismissRequest = { if (!renameSaving) renaming = null },
            containerColor = Color(Ds.PANEL),
            title = { Text("Rename macro", color = Color(Ds.TEXT)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = {
                            renameText = it
                            renameError = null
                        },
                        label = { Text("Name", fontSize = 11.sp) },
                        singleLine = true,
                        enabled = !renameSaving,
                        isError = renameError != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(Ds.TEXT),
                            unfocusedTextColor = Color(Ds.TEXT),
                            focusedBorderColor = Color(Ds.ACCENT),
                            unfocusedBorderColor = Color(Ds.PANEL_BORDER)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    renameError?.let { message ->
                        Text(
                            message,
                            fontSize = 11.sp,
                            color = Color(Ds.DANGER_TEXT),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !renameSaving,
                    onClick = {
                        val validation = MacroNaming.validationError(
                            renameText,
                            wallMacros.filter { it.id != macro.id }.map { it.name }
                        )
                        if (validation != null) {
                            renameError = validation
                        } else {
                            renameSaving = true
                            renameError = null
                            scope.launch {
                                try {
                                    when (val result = onRenameMacro(macro, renameText.trim())) {
                                        is MacroWriteResult.Saved -> renaming = null
                                        is MacroWriteResult.Rejected -> renameError = result.message
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    renameError = "Could not rename macro"
                                } finally {
                                    renameSaving = false
                                }
                            }
                        }
                    }
                ) {
                    Text(if (renameSaving) "Saving..." else "Save", color = Color(Ds.LINK))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }, enabled = !renameSaving) {
                    Text("Cancel", color = Color(Ds.MUTED))
                }
            }
        )
    }
}

@Composable
private fun AcWallsLabel(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Color(Ds.MUTED),
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun AcWallsNumber(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onChange(raw.filter { it.isDigit() }.take(11)) },
        singleLine = true,
        placeholder = { Text("0", color = Color(Ds.MUTED)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color(Ds.TEXT),
            unfocusedTextColor = Color(Ds.TEXT),
            focusedBorderColor = Color(Ds.ACCENT),
            unfocusedBorderColor = Color(Ds.PANEL_BORDER),
            cursorColor = Color(Ds.LINK)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AcWallsPickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (selected) "✓  $label" else label,
            fontSize = 13.sp,
            color = if (selected) Color(Ds.SUCCESS) else Color(Ds.TEXT)
        )
    }
}

/** Maps a taught sheet choice onto the stored rule. */
fun taughtRule(
    entry: UnknownScreen,
    name: String,
    action: TeachAction,
    tapX: Float?,
    tapY: Float?,
    hash: String
): RecoveryRule = RecoveryRule(
    id = "taught_${entry.id}",
    name = name,
    action = when (action) {
        TeachAction.TAP_HERE -> RecoveryActionType.TAP_POINT
        TeachAction.CLOSE_X -> RecoveryActionType.TAP_CLOSE_X
        TeachAction.WAIT -> RecoveryActionType.WAIT
        TeachAction.GO_HOME -> RecoveryActionType.GO_HOME
    },
    matchHash = hash,
    tapX = tapX,
    tapY = tapY
)

/**
 * Shows the screenshot fitted inside a fixed box and reports taps as frame
 * fractions, compensating for letterboxing so the stored point matches the
 * pixel the user actually pressed.
 */
@Composable
private fun TeachImagePicker(
    bitmap: Bitmap?,
    tapX: Float?,
    tapY: Float?,
    onTap: (Float, Float) -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val imageW = (bitmap?.width ?: 1).coerceAtLeast(1)
    val imageH = (bitmap?.height ?: 1).coerceAtLeast(1)

    fun displayScale(w: Int, h: Int): Float =
        minOf(w.toFloat() / imageW, h.toFloat() / imageH)

    Box(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .background(Color(Ds.BACKDROP), RoundedCornerShape(Ds.RADIUS_ROW.dp))
            .onSizeChanged { boxSize = it }
            .pointerInput(bitmap) {
                detectTapGestures { offset ->
                    val w = size.width
                    val h = size.height
                    if (w <= 0 || h <= 0) return@detectTapGestures
                    val scale = displayScale(w, h)
                    val shownW = imageW * scale
                    val shownH = imageH * scale
                    val left = (w - shownW) / 2f
                    val top = (h - shownH) / 2f
                    onTap(
                        ((offset.x - left) / shownW).coerceIn(0f, 1f),
                        ((offset.y - top) / shownH).coerceIn(0f, 1f)
                    )
                }
            }
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Screenshot to teach",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        val x = tapX
        val y = tapY
        if (x != null && y != null && boxSize.width > 0 && boxSize.height > 0) {
            val scale = displayScale(boxSize.width, boxSize.height)
            val shownW = imageW * scale
            val shownH = imageH * scale
            val left = (boxSize.width - shownW) / 2f
            val top = (boxSize.height - shownH) / 2f
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (left + x * shownW - 11.dp.toPx()).roundToInt(),
                            (top + y * shownH - 11.dp.toPx()).roundToInt()
                        )
                    }
                    .size(22.dp)
                    .border(2.dp, Color(Ds.WARN), RoundedCornerShape(11.dp))
            )
        }
    }
}
