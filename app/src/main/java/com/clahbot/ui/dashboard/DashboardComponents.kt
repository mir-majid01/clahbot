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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
fun AcHeader(
    controllerActive: Boolean,
    needsAttention: Boolean,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Accent),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painterResource(R.drawable.ic_autoclash_mark),
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
        }
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
    now: Long,
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
                    "Session $statusLabel · " +
                        acSpan((session.endedAt ?: now) - session.startedAt) +
                        " · ${session.attackCount} attacks",
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
    accessibilityOk: Boolean,
    notificationsOk: Boolean,
    controllerActive: Boolean,
    onStartController: () -> Unit,
    onEnableOverlay: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onEnableNotifications: () -> Unit
) {
    val allOk = overlayOk && accessibilityOk && notificationsOk
    var expanded by remember { mutableStateOf(!allOk) }
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
                    Text(
                        "Accessibility, overlay, notifications",
                        fontSize = 12.sp,
                        color = Muted
                    )
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
                PermissionLine("Accessibility", accessibilityOk, "Grant", onEnableAccessibility)
                PermissionLine("Display over other apps", overlayOk, "Grant", onEnableOverlay)
                PermissionLine("Notifications", notificationsOk, "Grant", onEnableNotifications)
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
    onAction: () -> Unit
) {
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
            Text("Granted", fontSize = 11.sp, color = Success)
        } else {
            TextButton(onClick = onAction) {
                Text(action, fontSize = 11.sp, color = Link)
            }
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
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AcCard(
        shape = RoundedCornerShape(14.dp),
        borderColor = if (selected) Color(Ds.SELECTED_ROW_BORDER) else PanelBorder,
        container = if (selected) Color(Ds.SELECTED_ROW) else Panel
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (macro.pinned) "PIN · ${macro.name}" else macro.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${macro.gestures.size} gestures · ${acDuration(macro.durationMs)}",
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
                }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !busy, onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (busy) Color(Ds.BUTTON) else Color(Ds.PLAY)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painterResource(R.drawable.ic_play),
                        contentDescription = "Play ${macro.name}",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
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
fun AcSessionsSection(
    sessions: List<GameSession>,
    attacks: List<AttackRecord>,
    now: Long,
    onOpen: (GameSession) -> Unit
) {
    val active = sessions.firstOrNull { it.active }
    val recent = sessions.filter { !it.active }.take(3)
    if (active == null && recent.isEmpty()) {
        AcEmptyCard(
            "No sessions yet",
            "Launch Clash of Clans or play a macro to open a session."
        )
        return
    }
    active?.let {
        AcSessionLiveCard(it, attacks, now, onClick = { onOpen(it) })
        Spacer(Modifier.height(10.dp))
    }
    recent.forEach { session ->
        AcSessionRow(session, attacks) { onOpen(session) }
    }
}

@Composable
fun AcSessionLiveCard(
    session: GameSession,
    attacks: List<AttackRecord>,
    now: Long,
    onClick: () -> Unit
) {
    val scoped = remember(session, attacks) { scopedAttacks(session, attacks) }
    val totals = remember(scoped) { SessionAggregator.totals(scoped) }
    AcCard(
        borderColor = Color(Ds.SUCCESS),
        container = Panel,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "LIVE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Success,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(Ds.SUCCESS_BG))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Started ${acClock(session.startedAt)}",
                    fontSize = 11.sp,
                    color = Muted
                )
            }
            Text(
                acSpan((session.endedAt ?: now) - session.startedAt),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = TextColor,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(10.dp))
            AcResourceTiles(totals)
            Spacer(Modifier.height(10.dp))
            Row {
                AcMetaCell("Attacks", totals.attackCount.toString(), Modifier.weight(1f))
                AcMetaCell(
                    "Avg / attack",
                    acDuration(
                        if (totals.attackCount > 0) totals.durationMs / totals.attackCount else 0L
                    ),
                    Modifier.weight(1f)
                )
                AcMetaCell("Incomplete", totals.incompleteAttacks.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun AcSessionRow(
    session: GameSession,
    attacks: List<AttackRecord>,
    onClick: () -> Unit
) {
    val scoped = remember(session, attacks) { scopedAttacks(session, attacks) }
    val totals = remember(scoped) { SessionAggregator.totals(scoped) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${acDate(session.startedAt)} · ${acClock(session.startedAt)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextColor
                )
                Text(
                    "${acSpan((session.endedAt ?: session.lastUpdatedAt) - session.startedAt)} · " +
                        "${totals.attackCount} attacks",
                    fontSize = 12.sp,
                    color = Muted
                )
            }
            AcLootInline(totals)
            Spacer(Modifier.width(8.dp))
            AcSessionStatus(session)
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(PanelBorder)
        )
    }
}

@Composable
fun AcStatisticsSection(
    scope: Int,
    onScope: (Int) -> Unit,
    attacks: List<AttackRecord>,
    sessions: List<GameSession>,
    unreadable: Int,
    rescanning: Boolean,
    rescanStatus: String?,
    onRescan: () -> Unit,
    onSeeAll: () -> Unit
) {
    val confirmed = remember(attacks) { AttackStats.confirmedOnly(attacks) }
    val current = sessions.firstOrNull { it.active } ?: sessions.firstOrNull()
    val scoped = if (scope == 0 && current != null) {
        scopedAttacks(current, confirmed)
    } else {
        confirmed
    }
    val totals = remember(scoped) { SessionAggregator.totals(scoped) }
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
    if (unreadable > 0) {
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(Ds.WARN_BG))
                .clickable(enabled = !rescanning, onClick = onRescan),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$unreadable attack${if (unreadable == 1) "" else "s"} unreadable",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Warn,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp)
            )
            Text(
                if (rescanning) "Re-scanning…" else "Re-scan",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Warn,
                modifier = Modifier.padding(start = 8.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)
            )
        }
        rescanStatus?.let {
            Text(it, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    AcTotalsCard(totals)
    Spacer(Modifier.height(10.dp))
    AcTotalsMeta(totals)
    if (totals.incompleteAttacks > 0) {
        Text(
            "${totals.incompleteAttacks} attack(s) incomplete — excluded from totals",
            fontSize = 11.sp,
            color = Warn,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    Spacer(Modifier.height(12.dp))
    if (scoped.isEmpty()) {
        AcEmptyCard(
            if (scope == 0) "No session data" else "No attacks yet",
            if (scope == 0) "Switch to All time or launch Clash to start a session."
            else "Play a macro or scan a result screen."
        )
    } else {
        AcRecentAttackCard(scoped)
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painterResource(iconRes),
            contentDescription = label,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
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
                maxLines = 3,
                overflow = TextOverflow.Visible
            )
        }
        Spacer(Modifier.width(8.dp))
        AcAutoSizeTotal(total)
    }
}

@Composable
private fun AcAutoSizeTotal(value: Long) {
    val text = remember(value) { String.format(Locale.US, "%,d", value) }
    val measurer = rememberTextMeasurer()
    val base = TextStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    BoxWithConstraints(contentAlignment = Alignment.CenterEnd) {
        var size = 17
        while (size > 11) {
            val width = measurer.measure(
                text = text,
                style = base.copy(fontSize = size.sp)
            ).size.width
            if (width <= constraints.maxWidth) break
            size--
        }
        Text(
            text,
            style = base.copy(fontSize = size.sp),
            color = TextColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible
        )
    }
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
        Text(
            if (exact) String.format(Locale.US, "%,d", total) else Ds.compactNumber(total),
            fontSize = if (exact) 13.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextColor,
            maxLines = 1
        )
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
private fun AcRecentAttackCard(records: List<AttackRecord>) {
    AcCard {
        Column {
            records.take(4).forEachIndexed { index, record ->
                AcAttackRow(record)
                if (index < minOf(3, records.size - 1)) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp)
                        .background(PanelBorder))
                }
            }
        }
    }
}

@Composable
fun AcAttackRow(record: AttackRecord) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    acClock(record.startedAt),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextColor
                )
                Spacer(Modifier.width(6.dp))
                AcAttackStatus(record)
            }
            Text(acDuration(record.durationMs), fontSize = 11.sp, color = Muted)
        }
        AcLootInline(SessionAggregator.totals(listOf(record)))
    }
}

@Composable
private fun AcLootInline(totals: SessionTotals) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AcLootNumber(R.drawable.ic_gold, totals.totalGold)
        AcLootNumber(R.drawable.ic_elixir, totals.totalElixir)
        AcLootNumber(R.drawable.ic_dark, totals.totalDarkElixir)
    }
}

@Composable
private fun AcLootNumber(iconRes: Int, value: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 6.dp)
    ) {
        Image(
            painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            Ds.compactNumber(value),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextColor
        )
    }
}

@Composable
fun AcAttackStatus(record: AttackRecord) {
    when {
        record.needsReview -> AcPill("review", Warn, Color(Ds.WARN_BG))
        !record.hasLoot -> AcPill("not captured", Warn, Color(Ds.WARN_BG))
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

@Composable
fun AcSessionsDialog(
    sessions: List<GameSession>,
    attacks: List<AttackRecord>,
    onDismiss: () -> Unit,
    onOpen: (GameSession) -> Unit
) {
    val confirmed = remember(attacks) { AttackStats.confirmedOnly(attacks) }
    val firstSessionStart = sessions.minOfOrNull { it.startedAt }
    val beforeSessions = if (firstSessionStart == null) confirmed.size else {
        confirmed.count { it.startedAt < firstSessionStart }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sessions", color = TextColor) },
        text = {
            Column {
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    items(sessions, key = { "session-${it.id}" }) { session ->
                        AcSessionRow(session, attacks) { onOpen(session) }
                    }
                }
                if (beforeSessions > 0) {
                    Text(
                        "Before sessions · $beforeSessions attacks",
                        fontSize = 11.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun AcSessionDetailDialog(
    session: GameSession,
    attacks: List<AttackRecord>,
    macros: List<Macro>,
    onDismiss: () -> Unit
) {
    val scoped = remember(session, attacks) { scopedAttacks(session, attacks) }
    val totals = remember(scoped) { SessionAggregator.totals(scoped) }
    val macroNames = remember(session, macros) {
        session.macroIds.mapNotNull { id -> macros.firstOrNull { it.id == id }?.name }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    "${acDate(session.startedAt)} · ${acClock(session.startedAt)}",
                    color = TextColor
                )
                Text(
                    "${acSpan((session.endedAt ?: session.lastUpdatedAt) - session.startedAt)} · " +
                        session.status,
                    fontSize = 12.sp,
                    color = Muted
                )
            }
        },
        text = {
            Column {
                AcResourceTiles(totals)
                Spacer(Modifier.height(8.dp))
                AcTotalsMeta(totals)
                if (macroNames.isNotEmpty()) {
                    Text(
                        "Macros: " + macroNames.joinToString(", "),
                        fontSize = 11.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(scoped, key = { "detail-${it.id}" }) { record ->
                        AcAttackRow(record)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun AcHistoryDialog(
    records: List<AttackRecord>,
    onDismiss: () -> Unit,
    onEdit: (AttackRecord) -> Unit,
    onDelete: (AttackRecord) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attack history", color = TextColor) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                items(records, key = { "history-${it.id}" }) { record ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { AcAttackRow(record) }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onEdit(record) },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painterResource(R.drawable.ic_edit),
                                contentDescription = "Edit attack",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onDelete(record) },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = "Delete attack",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
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

fun acClock(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(timestamp))

fun acDate(timestamp: Long): String =
    SimpleDateFormat("MM/dd", Locale.US).format(Date(timestamp))

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
