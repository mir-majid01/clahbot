package com.clahbot.ui.dashboard

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clahbot.recovery.RecoveryActionType
import com.clahbot.recovery.RecoveryRule
import com.clahbot.recovery.RecoveryStats
import com.clahbot.triggers.ImageTrigger
import com.clahbot.ui.design.Ds

/**
 * Screen Rules card: what the watchdog has been taught, plus the entry point to
 * the unknown-screen gallery. Each rule can be enabled/disabled and deleted, and
 * shows how often and when it last matched.
 */
@Composable
fun AcScreenRulesCard(
    rules: List<RecoveryRule>,
    unknownCount: Int,
    onOpenGallery: () -> Unit,
    onToggle: (RecoveryRule, Boolean) -> Unit,
    onEdit: (RecoveryRule) -> Unit,
    onDelete: (RecoveryRule) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(Ds.PANEL), RoundedCornerShape(Ds.RADIUS_CARD.dp))
            .border(1.dp, Color(Ds.PANEL_BORDER), RoundedCornerShape(Ds.RADIUS_CARD.dp))
            .padding(14.dp)
    ) {
        Text("Screen rules", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(Ds.TEXT))
        Spacer(Modifier.height(2.dp))
        Text(
            "Screens the watchdog has learned. It closes or waits per rule; a disabled rule is ignored.",
            fontSize = 11.sp,
            color = Color(Ds.MUTED)
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onOpenGallery,
            modifier = Modifier.fillMaxWidth().height(38.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.BUTTON)),
            shape = RoundedCornerShape(Ds.RADIUS_BUTTON.dp)
        ) {
            Text(
                if (unknownCount > 0) "Review unknown screens ($unknownCount)" else "Unknown screens",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color(Ds.TEXT)
            )
        }
        if (rules.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("No saved rules yet. Teach an unknown screen to add one.", fontSize = 11.sp, color = Color(Ds.MUTED))
        } else {
            Spacer(Modifier.height(12.dp))
            Text("Saved rules", fontSize = 10.sp, color = Color(Ds.MUTED))
            rules.forEach { rule ->
                RuleRow(rule, onToggle, onEdit, onDelete)
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: RecoveryRule,
    onToggle: (RecoveryRule, Boolean) -> Unit,
    onEdit: (RecoveryRule) -> Unit,
    onDelete: (RecoveryRule) -> Unit
) {
    val at = rule.lastMatchedAt
    val on = rule.expectedState?.name?.lowercase() ?: "popup"
    val status = if (at != null) {
        "matched ${rule.matchCount}× · last ${acClock(at)} · on $on"
    } else {
        "never matched · on $on"
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(rule.name, fontSize = 12.5.sp, color = Color(Ds.TEXT))
            val purpose = rule.purpose
            if (!purpose.isNullOrBlank()) {
                Text(purpose, fontSize = 10.sp, color = Color(Ds.META_ACCENT))
            }
            Text(status, fontSize = 10.sp, color = Color(Ds.MUTED))
        }
        TextButton(onClick = { onEdit(rule) }) {
            Text("Edit", fontSize = 11.sp, color = Color(Ds.LINK))
        }
        Switch(checked = rule.isEnabled, onCheckedChange = { onToggle(rule, it) })
        TextButton(onClick = { onDelete(rule) }) {
            Text("×", fontSize = 15.sp, color = Color(Ds.DANGER_TEXT))
        }
    }
}

/**
 * Activity card: lifetime recovery counters plus the most recent rule or image
 * trigger that matched, newest first.
 */
@Composable
fun AcActivityCard(
    stats: RecoveryStats,
    unknownCount: Int,
    rules: List<RecoveryRule>,
    triggers: List<ImageTrigger>
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(Ds.PANEL), RoundedCornerShape(Ds.RADIUS_CARD.dp))
            .border(1.dp, Color(Ds.PANEL_BORDER), RoundedCornerShape(Ds.RADIUS_CARD.dp))
            .padding(14.dp)
    ) {
        Text("Activity", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(Ds.TEXT))
        Spacer(Modifier.height(2.dp))
        Text("Recoveries, and the last rule or trigger that recognised a screen.", fontSize = 11.sp, color = Color(Ds.MUTED))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityStat("Recovered", stats.recovered, Ds.SUCCESS, Modifier.weight(1f))
            ActivityStat("Stopped", stats.stopped, Ds.WARN, Modifier.weight(1f))
            ActivityStat("Unknown", unknownCount, Ds.META_ACCENT, Modifier.weight(1f))
        }

        val events = buildList {
            rules.forEach { rule -> rule.lastMatchedAt?.let { add(Triple(it, "Rule", "${rule.name} matched")) } }
            triggers.forEach { trigger ->
                trigger.lastMatchedAt?.let { add(Triple(it, "Trigger", "${trigger.name} → ${trigger.lastResult ?: "matched"}")) }
            }
        }.sortedByDescending { it.first }.take(5)

        Spacer(Modifier.height(12.dp))
        if (events.isEmpty()) {
            Text("No recent matches yet.", fontSize = 11.sp, color = Color(Ds.MUTED))
        } else {
            Text("Recent", fontSize = 10.sp, color = Color(Ds.MUTED))
            events.forEach { (at, _, text) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text, fontSize = 11.5.sp, color = Color(Ds.TEXT), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(acClock(at), fontSize = 10.sp, color = Color(Ds.MUTED))
                }
            }
        }
    }
}

@Composable
private fun ActivityStat(label: String, value: Int, tone: Int, modifier: Modifier) {
    Column(
        modifier
            .background(Color(Ds.INPUT), RoundedCornerShape(Ds.RADIUS_ROW.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$value", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(tone))
        Text(label, fontSize = 10.sp, color = Color(Ds.MUTED))
    }
}

/** Edit a taught rule's name, purpose and action. The taught press point is kept. */
@Composable
fun AcRuleEditor(
    initial: RecoveryRule,
    bitmap: Bitmap?,
    onSave: (name: String, purpose: String, action: RecoveryActionType) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var purpose by remember { mutableStateOf(initial.purpose ?: "") }
    var action by remember { mutableStateOf(initial.action.editableOrDefault()) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color(Ds.PANEL),
        title = { Text("Edit rule", color = Color(Ds.TEXT), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Taught screen",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(Color(Ds.BACKDROP), RoundedCornerShape(Ds.RADIUS_ROW.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EDIT_ACTIONS.forEach { option ->
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
                                option.ruleLabel(),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) Color.White else Color(Ds.TEXT)
                            )
                        }
                    }
                }
                if (action == RecoveryActionType.TAP_POINT) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The press point from when you taught it is kept.",
                        fontSize = 10.sp,
                        color = Color(Ds.MUTED)
                    )
                }
                Spacer(Modifier.height(8.dp))
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
            TextButton(onClick = { onSave(name.ifBlank { initial.name }, purpose, action) }) {
                Text("Save", color = Color(Ds.LINK), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = Color(Ds.MUTED)) }
        }
    )
}

private val EDIT_ACTIONS = listOf(
    RecoveryActionType.TAP_POINT,
    RecoveryActionType.TAP_CLOSE_X,
    RecoveryActionType.WAIT,
    RecoveryActionType.GO_HOME
)

private fun RecoveryActionType.editableOrDefault(): RecoveryActionType =
    if (this in EDIT_ACTIONS) this else RecoveryActionType.TAP_POINT

private fun RecoveryActionType.ruleLabel(): String = when (this) {
    RecoveryActionType.TAP_POINT -> "Tap here"
    RecoveryActionType.TAP_CLOSE_X -> "Close X"
    RecoveryActionType.WAIT -> "Wait"
    RecoveryActionType.GO_HOME -> "Go home"
    else -> name.lowercase()
}
