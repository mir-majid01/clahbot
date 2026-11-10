package com.clahbot.ui.dashboard

import android.graphics.Bitmap
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import com.clahbot.triggers.ImageTrigger
import com.clahbot.triggers.TriggerAction
import com.clahbot.triggers.TriggerActionType
import com.clahbot.triggers.TriggerPoint
import com.clahbot.ui.design.Ds
import kotlin.math.roundToInt

/**
 * The image-trigger card in the Automation section: list saved triggers with a
 * live last-result line, an enable switch, priority arrows and delete, plus the
 * Add button that opens a gallery pick. Adding is done by the caller (it owns
 * the activity-result launcher); this file only draws and reports intent.
 */
@Composable
fun AcImageTriggersCard(
    triggers: List<ImageTrigger>,
    onAdd: () -> Unit,
    onToggle: (ImageTrigger, Boolean) -> Unit,
    onPriority: (ImageTrigger, Int) -> Unit,
    onEdit: (ImageTrigger) -> Unit,
    onDelete: (ImageTrigger) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(Ds.PANEL), RoundedCornerShape(Ds.RADIUS_CARD.dp))
            .border(1.dp, Color(Ds.PANEL_BORDER), RoundedCornerShape(Ds.RADIUS_CARD.dp))
            .padding(14.dp)
    ) {
        Text("Image triggers", fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(Ds.TEXT))
        Spacer(Modifier.height(2.dp))
        Text(
            "When the reference picture is recognised on screen, run the chosen action. " +
                "A match never presses a Buy, Gem, Wall Ring or Surrender control.",
            fontSize = 11.sp,
            color = Color(Ds.MUTED)
        )
        Spacer(Modifier.height(12.dp))
        if (triggers.isEmpty()) {
            Text("No triggers yet. Add a picture from your gallery.", fontSize = 11.sp, color = Color(Ds.MUTED))
        } else {
            triggers.sortedByDescending { it.priority }.forEach { trigger ->
                TriggerRow(trigger, onToggle, onPriority, onEdit, onDelete)
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().height(38.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.BUTTON)),
            shape = RoundedCornerShape(Ds.RADIUS_BUTTON.dp)
        ) {
            Text("Add image trigger", fontSize = 12.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(Ds.TEXT))
        }
    }
}

@Composable
private fun TriggerRow(
    trigger: ImageTrigger,
    onToggle: (ImageTrigger, Boolean) -> Unit,
    onPriority: (ImageTrigger, Int) -> Unit,
    onEdit: (ImageTrigger) -> Unit,
    onDelete: (ImageTrigger) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(trigger.name, fontSize = 12.5.sp, color = Color(Ds.TEXT))
            Text(
                trigger.lastResult ?: "never matched · ${trigger.action.type.name.lowercase().replace('_', ' ')}",
                fontSize = 10.sp,
                color = if (trigger.lastResult?.startsWith("refused") == true) Color(Ds.WARN) else Color(Ds.MUTED)
            )
        }
        TextButton(onClick = { onPriority(trigger, trigger.priority + 1) }) {
            Text("▲", fontSize = 12.sp, color = Color(Ds.LINK))
        }
        TextButton(onClick = { onPriority(trigger, trigger.priority - 1) }) {
            Text("▼", fontSize = 12.sp, color = Color(Ds.LINK))
        }
        TextButton(onClick = { onEdit(trigger) }) {
            Text("Edit", fontSize = 11.5.sp, color = Color(Ds.LINK))
        }
        Switch(checked = trigger.enabled, onCheckedChange = { onToggle(trigger, it) })
        TextButton(onClick = { onDelete(trigger) }) {
            Text("×", fontSize = 15.sp, color = Color(Ds.DANGER_TEXT))
        }
    }
}

/**
 * The trigger editor shown after a picture is picked. The user taps the picture
 * to choose where Tap / Long press lands, picks an action, and names it. Stop and
 * Wait need no point. Swipe, Run macro and Wait-until are not offered yet.
 */
@Composable
fun AcTriggerEditor(
    bitmap: Bitmap?,
    initial: ImageTrigger?,
    onSave: (name: String, type: TriggerActionType, point: TriggerPoint?, durationMs: Int) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "Image trigger") }
    var type by remember { mutableStateOf(initial?.action?.type?.takeIf { it.isSupported() } ?: TriggerActionType.TAP) }
    var duration by remember { mutableStateOf((initial?.action?.durationMs ?: 0).toString()) }
    var tapX by remember { mutableStateOf(initial?.action?.point?.x) }
    var tapY by remember { mutableStateOf(initial?.action?.point?.y) }
    val needsPoint = type == TriggerActionType.TAP || type == TriggerActionType.LONG_PRESS

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color(Ds.PANEL),
        title = { Text(if (initial == null) "New image trigger" else "Edit trigger", color = Color(Ds.TEXT), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            Column {
                TriggerImagePicker(bitmap, tapX, tapY) { x, y ->
                    tapX = x
                    tapY = y
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (needsPoint && tapX == null) "Tap the picture where the press should land."
                    else if (needsPoint) "Press point saved · tap again to move it."
                    else "This action needs no press point.",
                    fontSize = 10.5.sp,
                    color = Color(Ds.MUTED)
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        TriggerActionType.TAP,
                        TriggerActionType.LONG_PRESS,
                        TriggerActionType.WAIT,
                        TriggerActionType.STOP
                    ).forEach { option ->
                        val selected = option == type
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .background(
                                    if (selected) Color(Ds.ACCENT) else Color(Ds.BUTTON),
                                    RoundedCornerShape(Ds.RADIUS_BUTTON.dp)
                                )
                                .clickable { type = option },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                option.label(),
                                fontSize = 10.5.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = if (selected) Color.White else Color(Ds.TEXT)
                            )
                        }
                    }
                }
                if (type == TriggerActionType.LONG_PRESS || type == TriggerActionType.WAIT) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = duration,
                        onValueChange = { duration = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text(if (type == TriggerActionType.WAIT) "Wait ms" else "Hold ms", fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(Ds.TEXT),
                            unfocusedTextColor = Color(Ds.TEXT),
                            focusedBorderColor = Color(Ds.ACCENT),
                            unfocusedBorderColor = Color(Ds.PANEL_BORDER)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Trigger name", fontSize = 11.sp) },
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
            TextButton(onClick = {
                if (needsPoint && (tapX == null || tapY == null)) return@TextButton
                val point = if (needsPoint) TriggerPoint(tapX!!, tapY!!, relativeToMatch = true) else null
                onSave(name.ifBlank { "Image trigger" }, type, point, duration.toIntOrNull() ?: 0)
            }) {
                Text("Save", color = Color(Ds.LINK), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = Color(Ds.MUTED)) }
        }
    )
}

private fun TriggerActionType.isSupported(): Boolean = when (this) {
    TriggerActionType.TAP, TriggerActionType.LONG_PRESS, TriggerActionType.WAIT, TriggerActionType.STOP -> true
    else -> false
}

private fun TriggerActionType.label(): String = when (this) {
    TriggerActionType.TAP -> "Tap"
    TriggerActionType.LONG_PRESS -> "Long press"
    TriggerActionType.SWIPE -> "Swipe"
    TriggerActionType.WAIT -> "Wait"
    TriggerActionType.RUN_MACRO -> "Run macro"
    TriggerActionType.STOP -> "Stop"
    TriggerActionType.WAIT_UNTIL -> "Wait until"
}

@Composable
private fun TriggerImagePicker(
    bitmap: Bitmap?,
    tapX: Float?,
    tapY: Float?,
    onTap: (Float, Float) -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val imageW = (bitmap?.width ?: 1).coerceAtLeast(1)
    val imageH = (bitmap?.height ?: 1).coerceAtLeast(1)

    fun scale(w: Int, h: Int): Float = minOf(w.toFloat() / imageW, h.toFloat() / imageH)

    Box(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .background(Color(Ds.BACKDROP), RoundedCornerShape(Ds.RADIUS_ROW.dp))
            .onSizeChanged { boxSize = it }
            .pointerInput(bitmap) {
                detectTapGestures { offset: Offset ->
                    val w = size.width
                    val h = size.height
                    if (w <= 0 || h <= 0) return@detectTapGestures
                    val s = scale(w, h)
                    val shownW = imageW * s
                    val shownH = imageH * s
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
                contentDescription = "Trigger reference",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        val x = tapX
        val y = tapY
        if (x != null && y != null && boxSize.width > 0 && boxSize.height > 0) {
            val s = scale(boxSize.width, boxSize.height)
            val shownW = imageW * s
            val shownH = imageH * s
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
