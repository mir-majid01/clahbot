package com.clahbot.ui.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clahbot.R
import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.AttackStats
import com.clahbot.data.models.GameSession
import com.clahbot.data.models.Macro
import com.clahbot.data.models.SessionAggregator
import com.clahbot.data.models.SessionTotals
import com.clahbot.data.models.totalDarkElixir
import com.clahbot.data.models.totalElixir
import com.clahbot.data.models.totalGold
import com.clahbot.ui.design.Ds
import com.clahbot.util.PermissionHelper
import com.clahbot.util.Plural
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TextColor = Color(Ds.TEXT)
private val Muted = Color(Ds.MUTED)
private val Accent = Color(Ds.ACCENT)
private val Link = Color(Ds.LINK)
private val Panel = Color(Ds.PANEL)
private val PanelBorder = Color(Ds.PANEL_BORDER)
private val Success = Color(Ds.SUCCESS)
private val Warn = Color(Ds.WARN)

@Composable
fun AcLogo(size: androidx.compose.ui.unit.Dp) {
    Image(
        painterResource(R.drawable.ic_autoclash_mark),
        contentDescription = null,
        modifier = Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(percent = 30))
    )
}

@Composable
fun AcHeader(
    controllerActive: Boolean,
    needsAttention: Boolean,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AcLogo(40.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("AutoClash", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextColor)
            Text("Macro recorder", fontSize = 12.sp, color = Muted)
        }
        when {
            needsAttention -> AcPill("Needs attention", Warn, Color(Ds.WARN_BG))
            controllerActive -> AcPill("Active", Success, Color(Ds.SUCCESS_BG), dot = true)
            else -> AcPill("Ready", Success, Color(Ds.SUCCESS_BG), dot = true)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painterResource(R.drawable.ic_gear),
                contentDescription = "Settings",
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun AcPill(
    text: String,
    fg: Color,
    bg: Color,
    dot: Boolean = false
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
fun AcGameCard(
    gameLabel: String,
    gameStatus: String,
    gameIcon: ImageBitmap?,
    session: GameSession?,
    sessionAttacks: List<AttackRecord>,
    now: () -> Long,
    onLaunch: () -> Unit
) {
    AcCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFFFD36A), Color(0xFFB7791F))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (gameIcon != null) {
                    Image(
                        gameIcon,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Image(
                        painterResource(R.drawable.ic_game_clash),
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(gameLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextColor)
                Text(gameStatus, fontSize = 12.sp, color = Muted)
            }
            Button(
                onClick = onLaunch,
                modifier = Modifier.heightIn(min = 44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text("Launch", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (session != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(PanelBorder))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusColor = when (session.status) {
                    GameSession.ACTIVE -> Success
                    GameSession.RECOVERED -> Warn
                    else -> Muted
                }
                val statusLabel = when (session.status) {
                    GameSession.ACTIVE -> "active"
                    GameSession.RECOVERED -> "recovered"
                    else -> "closed"
                }
                Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(7.dp))
                Text(
                    // Counted from the live records, the same list the session box
                    // below renders: the persisted counter can lag a save and then
                    // the header and the list disagree about how many attacks ran.
                    "Session $statusLabel · " +
                        acSpan((session.endedAt ?: now()) - session.startedAt) +
                        " · ${Plural.attacks(sessionAttacks.count { it.confirmed })}",
                    fontSize = 12.sp,
                    color = Muted
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().height(1.dp).background(PanelBorder))
            Text(
                "No active session. Launch Clash to start one.",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 12.sp,
                color = Muted
            )
        }
    }
}

@Composable
fun AcSetupCard(
    overlayOk: Boolean,
    accessibilityStatus: PermissionHelper.AccessibilityStatus,
    notificationsOk: Boolean,
    controllerActive: Boolean,
    onStartController: () -> Unit,
    onEnableOverlay: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onEnableNotifications: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    val accessibilityConnected =
        accessibilityStatus == PermissionHelper.AccessibilityStatus.CONNECTED
    val allOk = overlayOk && accessibilityConnected && notificationsOk
    var expanded by remember { mutableStateOf(!allOk) }
    val accessibilityEnabled =
        accessibilityStatus != PermissionHelper.AccessibilityStatus.DISABLED
    val summary = when {
        allOk -> "Accessibility, overlay and notifications are ready"
        accessibilityStatus == PermissionHelper.AccessibilityStatus.ENABLED_NOT_CONNECTED ->
            "Accessibility is on but not connected"
        !accessibilityEnabled -> "Accessibility is off"
        !overlayOk -> "Display over other apps is off"
        !notificationsOk -> "Notifications are off"
        else -> "Accessibility, overlay, notifications"
    }
    AcCard {
        Column(
            modifier = Modifier
                .animateContentSize(tween(durationMillis = 180))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (allOk) Color(Ds.SUCCESS_BG) else Color(Ds.WARN_BG)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painterResource(if (allOk) R.drawable.ic_check else R.drawable.ic_gear),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (allOk) "All set" else "Setup needs attention",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextColor
                    )
                    Text(summary, fontSize = 12.sp, color = Muted)
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Details", fontSize = 12.sp, color = Link)
                    Image(
                        painterResource(R.drawable.ic_chevron),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                PermissionLine(
                    title = "Accessibility",
                    ok = accessibilityConnected,
                    action = if (accessibilityEnabled) "Fix" else "Grant",
                    onAction = onEnableAccessibility,
                    statusText = when {
                        accessibilityConnected -> "Connected"
                        accessibilityEnabled -> "Not connected"
                        else -> null
                    },
                    detail = if (accessibilityStatus ==
                        PermissionHelper.AccessibilityStatus.ENABLED_NOT_CONNECTED
                    ) {
                        "Android has it enabled but the service is not connected. " +
                            "Open Accessibility, turn AutoClash off, then on."
                    } else {
                        null
                    }
                )
                PermissionLine("Display over other apps", overlayOk, "Grant", onEnableOverlay)
                PermissionLine("Notifications", notificationsOk, "Grant", onEnableNotifications)
                if (!accessibilityConnected) {
                    Text(
                        "If Android says “Restricted setting”, open App info, " +
                            "tap ⋮ and allow restricted settings.",
                        fontSize = 10.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    TextButton(
                        onClick = onOpenAppInfo,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Open App info", fontSize = 10.sp, color = Link)
                    }
                }
                Text(
                    "Macros, loot and screenshots stay on this phone. " +
                        "Nothing is uploaded.",
                    fontSize = 10.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (allOk) {
                Button(
                    onClick = onStartController,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text(
                        if (controllerActive) "Open controller" else "Start floating controller",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionLine(
    title: String,
    ok: Boolean,
    action: String,
    onAction: () -> Unit,
    statusText: String? = null,
    detail: String? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(if (ok) Success else Warn)
            )
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 12.sp, color = TextColor, modifier = Modifier.weight(1f))
            if (ok) {
                Text(statusText ?: "Granted", fontSize = 11.sp, color = Success)
            } else {
                statusText?.let {
                    Text(it, fontSize = 11.sp, color = Warn)
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(onClick = onAction) {
                    Text(action, fontSize = 11.sp, color = Link)
                }
            }
        }
        detail?.let {
            Text(
                it,
                fontSize = 10.sp,
                color = Warn,
                modifier = Modifier.padding(start = 15.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
fun AcLibraryActions(
    importing: Boolean,
    selectionMode: Boolean,
    canDelete: Boolean,
    onImport: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AcOutlinedAction(
            label = if (importing) "Importing…" else "Import",
            icon = R.drawable.ic_import,
            color = Color(Ds.LINK),
            border = Color(0xFF2F4A86),
            enabled = !importing && !selectionMode,
            onClick = onImport
        )
        Spacer(Modifier.weight(1f))
        AcOutlinedAction(
            label = if (selectionMode) "Cancel" else "Delete",
            icon = if (selectionMode) R.drawable.ic_close else R.drawable.ic_delete,
            color = if (selectionMode) Color(Ds.TEXT) else Color(Ds.DANGER_TEXT),
            border = if (selectionMode) Color(0xFF3A4A70) else Color(0xFF7F2A38),
            enabled = selectionMode || canDelete,
            onClick = onDelete
        )
    }
}

@Composable
private fun AcOutlinedAction(
    label: String,
    icon: Int,
    color: Color,
    border: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (enabled) color else color.copy(alpha = 0.45f)),
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) color else color.copy(alpha = 0.45f)
        )
    }
}

@Composable
fun AcSelectionActionBar(
    count: Int,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF2172033))
            .border(1.dp, Color(0xFF2B3B5C), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$count selected",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextColor,
            modifier = Modifier.weight(1f)
        )
        AcOutlinedAction(
            label = "Cancel",
            icon = R.drawable.ic_close,
            color = Color(Ds.TEXT),
            border = Color(0xFF3A4A70),
            enabled = true,
            onClick = onCancel
        )
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF3A1620))
                .border(1.dp, Color(0xFF7F2A38), RoundedCornerShape(12.dp))
                .clickable(enabled = count > 0, onClick = onDelete)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painterResource(R.drawable.ic_delete),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(Ds.DANGER_TEXT)),
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Delete $count",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (count > 0) Color(Ds.DANGER_TEXT) else Color(Ds.DANGER_TEXT).copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
fun AcUndoBar(count: Int, backupName: String?, onUndo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF2172033))
            .border(1.dp, Color(0xFF2B3B5C), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$count macro${if (count == 1) "" else "s"} deleted",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextColor
            )
            if (backupName != null) {
                Text(
                    "Backup: $backupName",
                    fontSize = 10.sp,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        TextButton(onClick = onUndo) {
            Text("Undo", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(Ds.LINK))
        }
    }
}

@Composable
fun AcSelectionBox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (checked) Color(Ds.ACCENT) else Color.Transparent)
            .border(
                2.dp,
                if (checked) Color(Ds.ACCENT) else Color(0xFF3A4A70),
                RoundedCornerShape(7.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Image(
                painterResource(R.drawable.ic_check_white),
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun AcPlayCircle(macroName: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (enabled) Color(Ds.PLAY) else Color(Ds.BUTTON)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painterResource(R.drawable.ic_play_white),
                contentDescription = "Play $macroName",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun AcSectionHeader(
    title: String,
    meta: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextColor,
            modifier = Modifier.weight(1f)
        )
        if (meta != null) {
            Text(meta, fontSize = 12.sp, color = Muted)
            Spacer(Modifier.width(8.dp))
        }
        if (action != null && onAction != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    action,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Link
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AcMacroRow(
    macro: Macro,
    selected: Boolean,
    busy: Boolean,
    selectionMode: Boolean = false,
    checked: Boolean = false,
    wallLink: String? = null,
    onToggleChecked: () -> Unit = {},
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AcCard(
        shape = RoundedCornerShape(14.dp),
        borderColor = if (selectionMode && checked || selected) {
            Color(Ds.SELECTED_ROW_BORDER)
        } else {
            PanelBorder
        },
        container = if (selectionMode && checked || selected) Color(Ds.SELECTED_ROW) else Panel
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = if (selectionMode) onToggleChecked else onSelect)
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                AcSelectionBox(checked)
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (macro.pinned) {
                        Image(
                            painterResource(R.drawable.ic_pin),
                            contentDescription = "Pinned",
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        macro.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${Plural.of(macro.gestures.size, "gesture")} · " +
                        acDuration(macro.durationMs),
                    fontSize = 12.sp,
                    color = Muted
                )
                Spacer(Modifier.height(5.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AcChip(acSpeed(macro.speedMultiplier))
                    AcChip(acRepeat(macro.repeatMode))
                    AcChip(acGap(macro.effectiveRepeatDelayMs))
                    wallLink?.let { AcChip("Walls: $it") }
                }
            }
            if (!selectionMode) {
                AcPlayCircle(
                    macroName = macro.name,
                    enabled = !busy,
                    onClick = onPlay
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onShare),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painterResource(R.drawable.ic_share),
                        contentDescription = "Share ${macro.name}",
                        modifier = Modifier.size(22.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painterResource(R.drawable.ic_gear),
                        contentDescription = "Settings for ${macro.name}",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AcChip(text: String) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(Ds.BUTTON))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Muted
    )
}

@Composable
fun AcStatisticsHeader(
    scope: Int,
    onScope: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Statistics",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextColor,
            modifier = Modifier.weight(1f)
        )
        AcSegmented(
            options = listOf("Session", "All time"),
            selected = scope,
            onSelect = onScope,
            modifier = Modifier.width(160.dp)
        )
    }
}

@Composable
fun AcSeeAllHistory(onSeeAll: () -> Unit) {
    Text(
        "See all history",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Link,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSeeAll)
            .padding(vertical = 12.dp)
    )
}

@Composable
fun AcStatisticsSection(
    scope: Int,
    onScope: (Int) -> Unit,
    attacks: List<AttackRecord>,
    sessions: List<GameSession>,
    now: () -> Long,
    unreadable: Int,
    bannerDismissed: Boolean,
    onDismissBanner: () -> Unit,
    rescanning: Boolean,
    rescanStatus: String?,
    onRescan: () -> Unit,
    onSeeAll: () -> Unit
) {
    val confirmed = remember(attacks) { AttackStats.confirmedOnly(attacks) }
    val current = sessions.firstOrNull { it.active } ?: sessions.firstOrNull()
    val scoped = remember(scope, current, confirmed) {
        if (scope == 0) {
            current?.let { scopedAttacks(it, confirmed) } ?: emptyList()
        } else {
            confirmed
        }
    }
    val totals = remember(scoped) { SessionAggregator.totals(scoped) }
    var expandedSession by rememberSaveable { mutableStateOf<Long?>(null) }
    val recentSessions = remember(sessions) { sessions.sortedByDescending { it.startedAt }.take(3) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Statistics",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextColor,
            modifier = Modifier.weight(1f)
        )
        AcSegmented(
            options = listOf("Session", "All time"),
            selected = scope,
            onSelect = onScope,
            modifier = Modifier.width(160.dp)
        )
    }
    if (unreadable > 0 && !bannerDismissed) {
        Spacer(Modifier.height(10.dp))
        ReviewBanner(
            count = unreadable,
            rescanning = rescanning,
            onRescan = onRescan,
            onDismiss = onDismissBanner
        )
    }
    rescanStatus?.let {
        Text(it, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
    }
    if (unreadable > 0 && bannerDismissed) {
        Text(
            Plural.needReview(unreadable) + " — loot read so far is counted",
            fontSize = 11.sp,
            color = Warn,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
    Spacer(Modifier.height(10.dp))
    AcTotalsCard(totals)
    Spacer(Modifier.height(10.dp))
    AcTotalsMeta(totals)
    if (totals.attackCount == 0) {
        Spacer(Modifier.height(10.dp))
        AcEmptyCard(
            if (scope == 0) "No session data" else "No attacks yet",
            if (scope == 0) "Switch to All time or launch Clash to start a session."
            else "Play a macro or scan a result screen."
        )
    }
    Spacer(Modifier.height(16.dp))
    Text("Sessions", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextColor)
    Spacer(Modifier.height(10.dp))
    if (recentSessions.isEmpty()) {
        AcEmptyCard(
            "No sessions yet",
            "Launch Clash of Clans or play a macro to open a session."
        )
    }
    recentSessions.forEachIndexed { index, session ->
        val records = remember(session, confirmed) { scopedAttacks(session, confirmed) }
        AcSessionBox(
            session = session,
            title = "${acDate(session.startedAt)} · ${acClock(session.startedAt)}",
            subtitle = historySubtitle(session, records, now()),
            records = records,
            expanded = expandedSession == session.id,
            onToggle = {
                expandedSession = if (expandedSession == session.id) null else session.id
            },
            onAttackClick = null
        )
        if (index < recentSessions.lastIndex) {
            Spacer(Modifier.height(12.dp))
        }
    }
    Text(
        "See all history",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Link,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSeeAll)
            .padding(vertical = 12.dp)
    )
}

@Composable
fun AcTotalsCard(totals: SessionTotals) {
    AcCard {
        Column {
            AcTotalRow(R.drawable.ic_gold, "Gold", totals.totalGold, totals.bonusGold)
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(PanelBorder))
            AcTotalRow(R.drawable.ic_elixir, "Elixir", totals.totalElixir, totals.bonusElixir)
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(PanelBorder))
            AcTotalRow(R.drawable.ic_dark, "Dark elixir", totals.totalDarkElixir, totals.bonusDarkElixir)
        }
    }
}

@Composable
private fun AcTotalRow(iconRes: Int, label: String, total: Long, bonus: Long) {
    val exact = remember(total) { String.format(Locale.US, "%,d", total) }
    val measurer = rememberTextMeasurer()
    val base = TextStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val density = androidx.compose.ui.platform.LocalDensity.current
    var exactDialog by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val iconPx = with(density) { 22.dp.roundToPx() }
        val gapPx = with(density) { 10.dp.roundToPx() }
        val totalGapPx = with(density) { 8.dp.roundToPx() }
        val minLeftPx = with(density) { 84.dp.roundToPx() }
        val totalWidth = measurer.measure(
            text = exact,
            style = base.copy(fontSize = 22.sp)
        ).size.width
        val available = constraints.maxWidth - iconPx - gapPx
        if (available - totalWidth - totalGapPx >= minLeftPx) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(iconRes),
                    contentDescription = label,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                AcTotalLabel(label, total, bonus, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    exact,
                    style = base.copy(fontSize = 22.sp),
                    color = TextColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
        } else {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(iconRes),
                        contentDescription = label,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    AcTotalLabel(label, total, bonus, Modifier.weight(1f))
                }
                AcTotalValue(
                    value = total,
                    maxSize = 22f,
                    color = TextColor,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    onExact = { exactDialog = true }
                )
            }
        }
    }
    if (exactDialog) {
        AlertDialog(
            onDismissRequest = { exactDialog = false },
            title = { Text(label + " total") },
            text = { Text(String.format(Locale.US, "%,d", total)) },
            confirmButton = {
                TextButton(onClick = { exactDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun AcTotalLabel(
    label: String,
    total: Long,
    bonus: Long,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextColor)
        Text(
            String.format(
                Locale.US,
                "loot %,d + bonus %,d",
                (total - bonus).coerceAtLeast(0L),
                bonus
            ),
            fontSize = 11.sp,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Fits an exact total down to 11sp; if still too wide it shows the compact form and opens the exact value on tap. */
@Composable
fun AcTotalValue(
    value: Long,
    maxSize: Float,
    color: Color,
    modifier: Modifier = Modifier,
    onExact: () -> Unit
) {
    val exact = remember(value) { String.format(Locale.US, "%,d", value) }
    val compact = remember(value) { Ds.compactNumber(value) }
    val measurer = rememberTextMeasurer()
    val base = TextStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        var size = maxSize
        var fits = false
        while (size >= 11f) {
            val width = measurer.measure(
                text = exact,
                style = base.copy(fontSize = size.sp)
            ).size.width
            if (width <= constraints.maxWidth) {
                fits = true
                break
            }
            size -= 0.5f
        }
        if (fits) {
            Text(
                exact,
                style = base.copy(fontSize = size.sp),
                color = color,
                maxLines = 1,
                softWrap = false
            )
        } else {
            Text(
                compact,
                style = base.copy(fontSize = maxSize.sp),
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clickable(onClick = onExact)
            )
        }
    }
}

/** Fits one line of tabular digits into the available width, never clipping. */
@Composable
fun AcFitText(
    text: String,
    maxSize: Float,
    minSize: Float,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = true
) {
    val measurer = rememberTextMeasurer()
    val base = TextStyle(
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontFeatureSettings = "tnum"
    )
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        var size = maxSize
        while (size > minSize) {
            val width = measurer.measure(
                text = text,
                style = base.copy(fontSize = size.sp)
            ).size.width
            if (width <= constraints.maxWidth) break
            size -= 0.5f
        }
        Text(
            text,
            style = base.copy(fontSize = size.sp),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun Modifier.acScrollFade(color: Color = Color(Ds.BACKDROP)): Modifier = this.drawWithContent {
    drawContent()
    val edge = 8.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            startY = 0f,
            endY = edge
        ),
        size = androidx.compose.ui.geometry.Size(size.width, edge)
    )
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0f), color),
            startY = size.height - edge,
            endY = size.height
        ),
        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - edge),
        size = androidx.compose.ui.geometry.Size(size.width, edge)
    )
}

@Composable
fun AcSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(Ds.INPUT))
            .padding(3.dp)
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) Accent else Color.Transparent)
                    .clickable { onSelect(index) }
                    .heightIn(min = 44.dp)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) Color.White else Muted
                )
            }
        }
    }
}

@Composable
fun AcResourceTiles(totals: SessionTotals) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AcResourceTile(
            R.drawable.ic_gold, "Gold", totals.totalGold, totals.bonusGold,
            Modifier.weight(1f)
        )
        AcResourceTile(
            R.drawable.ic_elixir, "Elixir", totals.totalElixir, totals.bonusElixir,
            Modifier.weight(1f)
        )
        AcResourceTile(
            R.drawable.ic_dark, "Dark elixir", totals.totalDarkElixir, totals.bonusDarkElixir,
            Modifier.weight(1f)
        )
    }
}

@Composable
fun AcResourceTile(
    iconRes: Int,
    label: String,
    total: Long,
    bonus: Long,
    modifier: Modifier = Modifier
) {
    var exact by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .border(1.dp, PanelBorder, RoundedCornerShape(14.dp))
            .clickable { exact = !exact }
            .padding(10.dp)
    ) {
        Image(
            painterResource(iconRes),
            contentDescription = label,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(6.dp))
        if (exact) {
            AcFitText(
                text = String.format(Locale.US, "%,d", total),
                maxSize = 13f,
                minSize = 9f,
                color = TextColor,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                Ds.compactNumber(total),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextColor,
                maxLines = 1
            )
        }
        Text(
            if (bonus > 0L) "+${Ds.compactNumber(bonus)} bonus" else "no bonus",
            fontSize = 10.sp,
            color = Muted
        )
    }
}

@Composable
fun AcTotalsMeta(totals: SessionTotals) {
    Row {
        AcMetaCell("Attacks", totals.attackCount.toString(), Modifier.weight(1f))
        AcMetaCell(
            "Avg / attack",
            acDuration(if (totals.attackCount > 0) totals.durationMs / totals.attackCount else 0L),
            Modifier.weight(1f)
        )
        AcMetaCell("Time", acSpan(totals.durationMs), Modifier.weight(1f))
    }
}

@Composable
private fun AcMetaCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = Muted)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextColor)
    }
}

@Composable
fun AcSessionStatus(session: GameSession) {
    when (session.status) {
        GameSession.ACTIVE -> AcPill("active", Success, Color(Ds.SUCCESS_BG))
        GameSession.RECOVERED -> AcPill("recovered", Warn, Color(Ds.WARN_BG))
        else -> AcPill("closed", Muted, Color(Ds.BUTTON))
    }
}

@Composable
fun AcEmptyCard(title: String, subtitle: String) {
    AcCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextColor)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun AcCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    container: Color = Panel,
    borderColor: Color = PanelBorder,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, borderColor)
    ) {
        content()
    }
}

fun scopedAttacks(session: GameSession, attacks: List<AttackRecord>): List<AttackRecord> =
    attacks.filter { it.id in session.attackIds }.sortedByDescending { it.startedAt }

fun acDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60
    return "${minutes}m ${String.format(Locale.US, "%02d", seconds)}s"
}

fun acSpan(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    return if (totalMinutes >= 60L) {
        "${totalMinutes / 60L}h ${totalMinutes % 60L}m"
    } else {
        "${totalMinutes}m"
    }
}

private val CLOCK_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm", Locale.US)
}

private val DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("MMM dd", Locale.US)
}

fun acClock(timestamp: Long): String =
    CLOCK_FORMAT.get()!!.format(Date(timestamp))

fun acDate(timestamp: Long): String =
    DATE_FORMAT.get()!!.format(Date(timestamp))

private fun acSpeed(speed: Float): String {
    val rounded = Math.round(speed * 100f)
    return if (rounded % 100 == 0) "${rounded / 100}.0x"
    else String.format(Locale.US, "%.2fx", speed)
}

private fun acRepeat(mode: String?): String = when {
    mode == Macro.REPEAT_INFINITE -> "Loop"
    mode == null || mode == Macro.REPEAT_ONCE -> "Once"
    else -> "${mode}×"
}

private fun acGap(ms: Long): String =
    if (ms <= 0L) "no gap"
    else {
        val text = String.format(Locale.US, "%.1f", ms / 1000.0)
            .trimEnd('0').trimEnd('.')
        "${text}s gap"
    }
