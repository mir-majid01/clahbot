package com.clahbot.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clahbot.R
import com.clahbot.data.MacroNaming
import com.clahbot.data.MacroWriteResult
import com.clahbot.data.models.Macro
import com.clahbot.data.models.MacroSettingsDraft
import com.clahbot.execution.MacroTimeline
import com.clahbot.execution.StopSummary
import com.clahbot.ui.design.Ds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The dashboard macro settings sheet. It edits the same
 * [MacroSettingsDraft] that the floating controller uses, with the same fields,
 * ranges and stop conditions, so saving in either place produces the same macro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroSettingsSheet(
    macro: Macro,
    wallMacros: List<Macro> = emptyList(),
    onDismiss: () -> Unit,
    onSave: suspend (MacroSettingsDraft) -> MacroWriteResult,
    onDuplicate: (MacroSettingsDraft) -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit
) {
    var draft by remember(macro.id) { mutableStateOf(MacroSettingsDraft.from(macro)) }
    var page by remember(macro.id) { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<EditField?>(null) }
    var pickWallMacro by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun apply(field: EditField, text: String) {
        when (field) {
            EditField.SPEED -> text.toFloatOrNull()?.let {
                draft = draft.copy(speed = MacroTimeline.clampSpeed(it))
            }

            EditField.REPEAT_COUNT -> text.toIntOrNull()?.let {
                if (it in 1..Macro.MAX_CUSTOM_REPEAT) {
                    draft = draft.copy(
                        repeatCount = it,
                        repeat = MacroTimeline.customRepeatMode(it)
                    )
                }
            }

            EditField.DELAY -> text.toDoubleOrNull()?.let {
                if (it.isFinite() && it in 0.0..600.0) {
                    draft = draft.copy(delayMs = (it * 1000.0).roundToInt().toLong())
                }
            }

            EditField.COUNTDOWN -> text.toIntOrNull()?.let {
                if (it in 0..30) draft = draft.copy(countdownSec = it)
            }

            EditField.STOP_MINUTES -> text.toIntOrNull()?.let {
                if (it in 1..Macro.MAX_STOP_AFTER_MINUTES) {
                    draft = draft.copy(stopMinutes = it)
                }
            }

            EditField.STOP_ATTACKS -> text.toIntOrNull()?.let {
                if (it in 1..Macro.MAX_STOP_AFTER_ATTACKS) {
                    draft = draft.copy(stopAttacks = it)
                }
            }
        }
    }

    fun persist(value: MacroSettingsDraft, dismissOnSuccess: Boolean) {
        if (saving) return
        val validation = MacroNaming.validationError(value.name, emptyList())
        if (validation != null) {
            saveError = validation
            return
        }
        saving = true
        saveError = null
        scope.launch {
            try {
                when (val result = onSave(value)) {
                    is MacroWriteResult.Saved -> if (dismissOnSuccess) onDismiss()
                    is MacroWriteResult.Rejected -> saveError = result.message
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveError = "Could not save macro"
            } finally {
                saving = false
            }
        }
    }

    editing?.let { field ->
        NumberEditDialog(
            title = field.title,
            initial = field.initial(draft),
            decimal = field.decimal,
            onDismiss = { editing = null },
            onConfirm = { text ->
                apply(field, text)
                editing = null
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        containerColor = Color(Ds.PANEL),
        contentColor = Color(Ds.TEXT),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 10.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF3A4A70))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    draft.name.ifBlank { macro.name },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(Ds.TEXT),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = !saving) {
                        androidx.compose.foundation.Image(
                            painterResource(R.drawable.ic_more),
                            contentDescription = "More actions",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (draft.pinned) "Unpin" else "Pin to top",
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                val next = draft.copy(pinned = !draft.pinned)
                                draft = next
                                menuOpen = false
                                persist(next, dismissOnSuccess = false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate", fontSize = 13.sp) },
                            onClick = {
                                if (!saving) {
                                    menuOpen = false
                                    onDuplicate(draft)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Reset to defaults", fontSize = 13.sp) },
                            onClick = {
                                if (saving) return@DropdownMenuItem
                                menuOpen = false
                                draft = MacroSettingsDraft.from(
                                    macro.copy(
                                        speedMultiplier = 1f,
                                        repeatMode = Macro.REPEAT_ONCE,
                                        repeatDelayMs = Macro.LEGACY_REPEAT_DELAY_MS,
                                        startCountdownMs = 0L,
                                        stopOnBackground = true,
                                        stopAfterMinutes = null,
                                        stopAfterAttacks = null,
                                        pinned = false
                                    )
                                )
                                onReset()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text("Delete", fontSize = 13.sp, color = Color(Ds.DANGER_TEXT))
                            },
                            onClick = {
                                if (saving) return@DropdownMenuItem
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
                IconButton(onClick = onDismiss, enabled = !saving) {
                    androidx.compose.foundation.Image(
                        painterResource(R.drawable.ic_close),
                        contentDescription = "Close",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            AcSegmented(
                options = listOf("Playback", "Stop conditions"),
                selected = page,
                onSelect = { page = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (page == 0) {
                    SheetRow(R.drawable.ic_person, "Name") {
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = {
                                draft = draft.copy(name = it)
                                saveError = null
                            },
                            singleLine = true,
                            enabled = !saving,
                            isError = saveError != null,
                            modifier = Modifier.widthIn(max = 170.dp).heightIn(min = 48.dp)
                        )
                    }
                    SheetDivider()
                    SheetRow(R.drawable.ic_speed, "Speed", "Delay and gesture length") {
                        AcStepper(
                            value = MacroTimeline.formatSpeed(draft.speed),
                            onMinus = {
                                draft = draft.copy(
                                    speed = MacroTimeline.stepSpeed(draft.speed, -1)
                                )
                            },
                            onPlus = {
                                draft = draft.copy(
                                    speed = MacroTimeline.stepSpeed(draft.speed, 1)
                                )
                            },
                            onClick = { editing = EditField.SPEED }
                        )
                    }
                    SheetDivider()
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SheetIcon(R.drawable.ic_repeat)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                SheetLabel("Repeat")
                                SheetHint("Once, loop forever, or a set number")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        AcSegmented(
                            options = listOf("Once", "Loop", "Custom"),
                            selected = when {
                                MacroTimeline.isCustomRepeat(draft.repeat) -> 2
                                draft.repeat == Macro.REPEAT_INFINITE -> 1
                                else -> 0
                            },
                            onSelect = {
                                draft = draft.copy(
                                    repeat = when (it) {
                                        1 -> Macro.REPEAT_INFINITE
                                        2 -> MacroTimeline.customRepeatMode(draft.repeatCount)
                                        else -> Macro.REPEAT_ONCE
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (MacroTimeline.isCustomRepeat(draft.repeat)) {
                        SheetDivider()
                        SheetRow(R.drawable.ic_repeat, "Repeat count", "Only for Custom") {
                            AcStepper(
                                value = draft.repeatCount.toString(),
                                onMinus = {
                                    draft = draft.copy(
                                        repeatCount = (draft.repeatCount - 1).coerceAtLeast(1)
                                    )
                                },
                                onPlus = {
                                    draft = draft.copy(
                                        repeatCount = (draft.repeatCount + 1)
                                            .coerceAtMost(Macro.MAX_CUSTOM_REPEAT)
                                    )
                                },
                                onClick = { editing = EditField.REPEAT_COUNT }
                            )
                        }
                    }
                    SheetDivider()
                    SheetRow(R.drawable.ic_timer, "Repeat delay", "Wait between repeats") {
                        AcStepper(
                            value = formatSeconds(draft.delayMs) + " s",
                            onMinus = {
                                draft = draft.copy(
                                    delayMs = (draft.delayMs - 200L).coerceAtLeast(0L)
                                )
                            },
                            onPlus = {
                                draft = draft.copy(
                                    delayMs = (draft.delayMs + 200L)
                                        .coerceAtMost(Macro.MAX_REPEAT_DELAY_MS)
                                )
                            },
                            onClick = { editing = EditField.DELAY }
                        )
                    }
                    SheetDivider()
                    SheetRow(R.drawable.ic_timer, "Countdown", "Before the first play") {
                        AcStepper(
                            value = "${draft.countdownSec} s",
                            onMinus = {
                                draft = draft.copy(
                                    countdownSec = (draft.countdownSec - 1).coerceAtLeast(0)
                                )
                            },
                            onPlus = {
                                draft = draft.copy(
                                    countdownSec = (draft.countdownSec + 1).coerceAtMost(30)
                                )
                            },
                            onClick = { editing = EditField.COUNTDOWN }
                        )
                    }
                } else {
                    SwitchSheetRow(
                        title = "Game leaves foreground",
                        subtitle = "Stop playback if Clash is no longer in front.",
                        checked = draft.stopOnBackground
                    ) { draft = draft.copy(stopOnBackground = it) }
                    SwitchSheetRow(
                        title = "After minutes",
                        subtitle = "Stop the whole run after a time limit.",
                        checked = draft.stopMinutes != null
                    ) {
                        draft = draft.copy(stopMinutes = if (it) 30 else null)
                    }
                    if (draft.stopMinutes != null) {
                        SheetRow(R.drawable.ic_timer, "Minutes", "1–${Macro.MAX_STOP_AFTER_MINUTES}") {
                            AcStepper(
                                value = draft.stopMinutes.toString(),
                                onMinus = {
                                    draft = draft.copy(
                                        stopMinutes = ((draft.stopMinutes ?: 30) - 1)
                                            .coerceAtLeast(1)
                                    )
                                },
                                onPlus = {
                                    draft = draft.copy(
                                        stopMinutes = ((draft.stopMinutes ?: 30) + 1)
                                            .coerceAtMost(Macro.MAX_STOP_AFTER_MINUTES)
                                    )
                                },
                                onClick = { editing = EditField.STOP_MINUTES }
                            )
                        }
                    }
                    SwitchSheetRow(
                        title = "After attacks",
                        subtitle = "Stop the whole run after a number of attacks.",
                        checked = draft.stopAttacks != null
                    ) {
                        draft = draft.copy(stopAttacks = if (it) 10 else null)
                    }
                    if (draft.stopAttacks != null) {
                        SheetRow(R.drawable.ic_repeat, "Attacks", "1–${Macro.MAX_STOP_AFTER_ATTACKS}") {
                            AcStepper(
                                value = draft.stopAttacks.toString(),
                                onMinus = {
                                    draft = draft.copy(
                                        stopAttacks = ((draft.stopAttacks ?: 10) - 1)
                                            .coerceAtLeast(1)
                                    )
                                },
                                onPlus = {
                                    draft = draft.copy(
                                        stopAttacks = ((draft.stopAttacks ?: 10) + 1)
                                            .coerceAtMost(Macro.MAX_STOP_AFTER_ATTACKS)
                                    )
                                },
                                onClick = { editing = EditField.STOP_ATTACKS }
                            )
                        }
                    }
                    SwitchSheetRow(
                        title = "Pin to top",
                        subtitle = "Keep this macro at the top of the library.",
                        checked = draft.pinned
                    ) { draft = draft.copy(pinned = it) }
                    SheetRow(
                        icon = R.drawable.ic_repeat,
                        title = "Walls after this attack",
                        subtitle = wallMacros.firstOrNull { it.id == draft.linkedWallMacroId }?.name
                            ?: "Global wall macro from the Walls tab"
                    ) {
                        TextButton(onClick = { pickWallMacro = true }) {
                            Text("Change", color = Color(Ds.LINK), fontSize = 12.sp)
                        }
                    }
                    val summaryMacro = draft.toMacro(macro)
                    Box(
                        Modifier.fillMaxWidth().padding(top = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF14234A))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                "Runs until the first of:",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFBFD4FF)
                            )
                            Text(
                                StopSummary.summaryLine(summaryMacro),
                                fontSize = 11.sp,
                                color = Color(Ds.MUTED),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    StopSummary.conflictHint(summaryMacro)?.let { hint ->
                        Text(
                            hint,
                            fontSize = 10.5.sp,
                            color = Color(Ds.WARN),
                            modifier = Modifier.padding(start = 2.dp, top = 6.dp)
                        )
                    }
                    Text(
                        "${com.clahbot.util.Plural.of(macro.gestures.size, "gesture")} · " +
                            "${acDuration(macro.durationMs)}\n" +
                            "Played ${com.clahbot.util.Plural.of(macro.playCount, "time")}",
                        fontSize = 11.sp,
                        color = Color(Ds.MUTED),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            saveError?.let { message ->
                Text(
                    message,
                    fontSize = 11.sp,
                    color = Color(Ds.DANGER_TEXT),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !saving,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A4A70))
                ) {
                    Text("Cancel", color = Color(Ds.TEXT), fontSize = 14.sp)
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { persist(draft, dismissOnSuccess = true) },
                    enabled = !saving,
                    modifier = Modifier.weight(1.4f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.ACCENT))
                ) {
                    Text(
                        if (saving) "Saving..." else "Save",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    if (pickWallMacro) {
        AlertDialog(
            onDismissRequest = { pickWallMacro = false },
            containerColor = Color(Ds.PANEL),
            title = { Text("Walls after this attack", color = Color(Ds.TEXT)) },
            text = {
                Column {
                    WallPickRow(
                        label = "Global wall macro from the Walls tab",
                        selected = draft.linkedWallMacroId <= 0
                    ) {
                        draft = draft.copy(linkedWallMacroId = -1L)
                        pickWallMacro = false
                    }
                    wallMacros.forEach { option ->
                        WallPickRow(
                            label = option.name,
                            selected = option.id == draft.linkedWallMacroId
                        ) {
                            draft = draft.copy(linkedWallMacroId = option.id)
                            pickWallMacro = false
                        }
                    }
                    if (wallMacros.isEmpty()) {
                        Text(
                            "No wall macros yet. Record one from the controller's Walls tab.",
                            fontSize = 12.sp,
                            color = Color(Ds.MUTED)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickWallMacro = false }) {
                    Text("Close", color = Color(Ds.LINK))
                }
            }
        )
    }

}

@Composable
private fun SheetDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(Ds.DIVIDER)))
}

@Composable
private fun SheetIcon(icon: Int) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF14234A)),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(Ds.TEXT))
}

@Composable
private fun SheetHint(text: String) {
    Text(text, fontSize = 12.sp, color = Color(Ds.MUTED))
}

@Composable
private fun SheetRow(
    icon: Int,
    title: String,
    subtitle: String? = null,
    control: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SheetIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SheetLabel(title)
            if (subtitle != null) SheetHint(subtitle)
        }
        Spacer(Modifier.width(8.dp))
        control()
    }
}

@Composable
private fun SwitchSheetRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            SheetLabel(title)
            SheetHint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AcStepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onClick: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepCircle("−", onMinus)
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(Ds.TEXT),
            maxLines = 1,
            modifier = Modifier
                .widthIn(min = 52.dp)
                .padding(horizontal = 4.dp)
                .clickable(enabled = onClick != null) { onClick?.invoke() },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        StepCircle("+", onPlus)
    }
}

@Composable
private fun StepCircle(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(Ds.BUTTON))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 17.sp, color = Color(Ds.TEXT))
    }
}

private enum class EditField(
    val title: String,
    val decimal: Boolean
) {
    SPEED("Speed", true),
    REPEAT_COUNT("Repeat count", false),
    DELAY("Repeat delay (seconds)", true),
    COUNTDOWN("Countdown (seconds)", false),
    STOP_MINUTES("After minutes", false),
    STOP_ATTACKS("After attacks", false);

    fun initial(draft: MacroSettingsDraft): String = when (this) {
        SPEED -> draft.speed.toString()
        REPEAT_COUNT -> draft.repeatCount.toString()
        DELAY -> formatSeconds(draft.delayMs)
        COUNTDOWN -> draft.countdownSec.toString()
        STOP_MINUTES -> (draft.stopMinutes ?: 30).toString()
        STOP_ATTACKS -> (draft.stopAttacks ?: 10).toString()
    }
}

@Composable
private fun NumberEditDialog(
    title: String,
    initial: String,
    decimal: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(title) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Color(Ds.TEXT)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = Color(Ds.PANEL),
        titleContentColor = Color(Ds.TEXT),
        textContentColor = Color(Ds.TEXT)
    )
}

private fun formatSeconds(ms: Long): String =
    String.format(Locale.US, "%.1f", ms / 1000.0)
        .trimEnd('0')
        .trimEnd('.')

@Composable
private fun WallPickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        if (selected) "✓  $label" else label,
        fontSize = 13.sp,
        color = if (selected) Color(Ds.SUCCESS) else Color(Ds.TEXT),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    )
}
