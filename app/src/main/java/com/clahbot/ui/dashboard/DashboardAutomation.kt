package com.clahbot.ui.dashboard

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.clahbot.recovery.RecoveryStats
import com.clahbot.recovery.UnknownScreen
import com.clahbot.recovery.RecoveryRule
import com.clahbot.recovery.RecoveryActionType
import com.clahbot.ui.design.Ds
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
    onSave: (name: String, purpose: String, action: TeachAction, tapX: Float?, tapY: Float?, expectedState: com.clahbot.game.GameState?, waitForTransition: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("Unknown screen rule") }
    var purpose by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(TeachAction.TAP_HERE) }
    var tapX by remember { mutableStateOf<Float?>(null) }
    var tapY by remember { mutableStateOf<Float?>(null) }
    var expectedState by remember { mutableStateOf<com.clahbot.game.GameState?>(null) }
    var waitForTransition by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color(Ds.PANEL),
        title = {
            Text("Teach this screen", color = Color(Ds.TEXT), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                Spacer(Modifier.height(8.dp))
                Text("Expected screen", fontSize = 10.sp, color = Color(Ds.MUTED))
                Spacer(Modifier.height(4.dp))
                AcExpectedStateChips(expectedState) { expectedState = it }
                AcWaitToggle(waitForTransition) { waitForTransition = it }
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
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = purpose,
                    onValueChange = { purpose = it.take(80) },
                    label = { Text("Screen purpose (optional)", fontSize = 11.sp) },
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
                    onSave(name.ifBlank { "Unknown screen rule" }, purpose, action, tapX, tapY, expectedState, waitForTransition)
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

/** Maps a taught sheet choice onto the stored rule. */
fun taughtRule(
    entry: UnknownScreen,
    name: String,
    purpose: String?,
    expectedState: com.clahbot.game.GameState?,
    waitForTransition: Boolean,
    action: TeachAction,
    tapX: Float?,
    tapY: Float?,
    hash: String
): RecoveryRule = RecoveryRule(
    id = "taught_${entry.id}",
    name = name,
    purpose = purpose?.ifBlank { null },
    expectedState = expectedState,
    waitForTransition = waitForTransition,
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
