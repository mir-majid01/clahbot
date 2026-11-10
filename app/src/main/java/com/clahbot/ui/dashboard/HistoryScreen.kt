package com.clahbot.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clahbot.R
import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.GameSession
import com.clahbot.data.models.SessionAggregator
import com.clahbot.data.models.SessionTotals
import com.clahbot.data.models.totalDarkElixir
import com.clahbot.data.models.totalElixir
import com.clahbot.data.models.totalGold
import com.clahbot.ui.design.Ds
import com.clahbot.util.Plural
import java.util.Locale

private val HistText = Color(Ds.TEXT)
private val HistMuted = Color(Ds.MUTED)
private val HistPanelBorder = Color(Ds.PANEL_BORDER)
private val HistWarn = Color(Ds.WARN)

enum class HistoryFilter(val label: String) {
    ALL("All"),
    REVIEW("Needs review"),
    WEEK("This week")
}

/**
 * The History page groups confirmed attacks by game session. Filtering stays
 * pure so the chips and the empty states can be verified off-device.
 */
object HistoryFiltering {

    const val WEEK_MS = 7L * 24L * 60L * 60L * 1000L

    fun weeklyCutoff(now: Long): Long = now - WEEK_MS

    fun sessions(
        sessions: List<GameSession>,
        attacks: List<AttackRecord>,
        filter: HistoryFilter,
        now: Long
    ): List<GameSession> {
        val cutoff = weeklyCutoff(now)
        return sessions
            .filter { session ->
                when (filter) {
                    HistoryFilter.ALL -> true
                    HistoryFilter.WEEK -> session.startedAt >= cutoff ||
                        attacks.any {
                            it.confirmed && it.id in session.attackIds && it.startedAt >= cutoff
                        }
                    HistoryFilter.REVIEW -> attacks.any {
                        it.confirmed && it.id in session.attackIds && it.needsReviewDefinition
                    }
                }
            }
            .sortedByDescending { it.startedAt }
    }

    fun orphans(
        sessions: List<GameSession>,
        attacks: List<AttackRecord>,
        filter: HistoryFilter,
        now: Long
    ): List<AttackRecord> {
        val linked = sessions.flatMapTo(mutableSetOf()) { it.attackIds }
        val cutoff = weeklyCutoff(now)
        return attacks
            .filter { record ->
                record.confirmed && record.id !in linked && when (filter) {
                    HistoryFilter.ALL -> true
                    HistoryFilter.WEEK -> record.startedAt >= cutoff
                    HistoryFilter.REVIEW -> record.needsReviewDefinition
                }
            }
            .sortedByDescending { it.startedAt }
    }
}

@Composable
fun HistoryScreen(
    sessions: List<GameSession>,
    attacks: List<AttackRecord>,
    now: () -> Long,
    unreadable: Int,
    bannerDismissed: Boolean,
    onDismissBanner: () -> Unit,
    rescanning: Boolean,
    rescanStatus: String?,
    onRescan: () -> Unit,
    onBack: () -> Unit,
    onEdit: (AttackRecord) -> Unit,
    onDelete: (AttackRecord) -> Unit
) {
    BackHandler(onBack = onBack)
    val confirmed = remember(attacks) { attacks.filter { it.confirmed } }
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedAttack by remember { mutableStateOf<AttackRecord?>(null) }
    val visibleSessions = remember(sessions, confirmed, filter, now()) {
        HistoryFiltering.sessions(sessions, confirmed, filter, now())
    }
    val orphans = remember(sessions, confirmed, filter, now()) {
        HistoryFiltering.orphans(sessions, confirmed, filter, now())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(Ds.BACKDROP))
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painterResource(R.drawable.ic_back),
                    contentDescription = "Back",
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                "History",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = HistText,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (unreadable > 0 && !bannerDismissed) {
                item("banner") {
                    ReviewBanner(
                        count = unreadable,
                        rescanning = rescanning,
                        onRescan = onRescan,
                        onDismiss = onDismissBanner
                    )
                }
            }
            item("filters") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HistoryFilter.entries.forEach { option ->
                        FilterChip(
                            label = option.label,
                            selected = filter == option,
                            onClick = {
                                filter = option
                                expandedId = null
                            }
                        )
                    }
                }
            }
            if (unreadable > 0 && bannerDismissed) {
                // The page stays honest after the banner is dismissed.
                item("banner-status") {
                    Text(
                        rescanStatus ?: Plural.needReview(unreadable),
                        fontSize = 12.sp,
                        color = HistMuted
                    )
                }
            } else if (rescanStatus != null) {
                item("rescan-status") {
                    Text(rescanStatus, fontSize = 12.sp, color = HistMuted)
                }
            }
            if (visibleSessions.isEmpty() && orphans.isEmpty()) {
                item("empty") {
                    AcEmptyCard(
                        when (filter) {
                            HistoryFilter.ALL -> "No attacks yet"
                            HistoryFilter.REVIEW -> "Nothing needs review"
                            HistoryFilter.WEEK -> "No attacks this week"
                        },
                        when (filter) {
                            HistoryFilter.ALL -> "Play a macro or scan a result screen and it will appear here."
                            HistoryFilter.REVIEW -> "Every captured attack has all three loot values."
                            HistoryFilter.WEEK -> "Switch to All to see older attacks."
                        }
                    )
                }
            }
            items(visibleSessions, key = { "session-${it.id}" }) { session ->
                val records = remember(session, confirmed) { scopedAttacks(session, confirmed) }
                AcSessionBox(
                    session = session,
                    title = "${acDate(session.startedAt)} · ${acClock(session.startedAt)}",
                    subtitle = historySubtitle(session, records, now()),
                    records = records,
                    expanded = expandedId == session.id,
                    onToggle = {
                        expandedId = if (expandedId == session.id) null else session.id
                    },
                    onAttackClick = { selectedAttack = it }
                )
            }
            if (orphans.isNotEmpty()) {
                item("orphans") {
                    AcSessionBox(
                        session = null,
                        title = "Before sessions",
                        subtitle = Plural.attacks(orphans.size),
                        records = orphans,
                        expanded = expandedId == ORPHAN_ID,
                        onToggle = {
                            expandedId = if (expandedId == ORPHAN_ID) null else ORPHAN_ID
                        },
                        onAttackClick = { selectedAttack = it }
                    )
                }
            }
            if (visibleSessions.isNotEmpty() || orphans.isNotEmpty()) {
                item("hint") {
                    Text(
                        "Tap a session to open it, tap an attack for details, edit or delete.",
                        fontSize = 12.sp,
                        color = HistMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 8.dp, end = 8.dp)
                    )
                }
            }
        }
    }

    selectedAttack?.let { record ->
        AttackDetailSheet(
            record = record,
            onDismiss = { selectedAttack = null },
            onEdit = {
                selectedAttack = null
                onEdit(record)
            },
            onDelete = {
                selectedAttack = null
                onDelete(record)
            }
        )
    }
}

private const val ORPHAN_ID = -1L

fun historySubtitle(
    session: GameSession,
    records: List<AttackRecord>,
    now: Long
): String {
    val span = acSpan((session.endedAt ?: now) - session.startedAt)
    return "$span · ${Plural.attacks(records.size)}"
}

@Composable
fun AcSessionBox(
    session: GameSession?,
    title: String,
    subtitle: String,
    records: List<AttackRecord>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAttackClick: ((AttackRecord) -> Unit)?
) {
    val totals = remember(records) { SessionAggregator.totals(records) }
    AcCard(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.animateContentSize()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HistText)
                    Text(subtitle, fontSize = 12.sp, color = HistMuted)
                }
                if (session != null) {
                    AcSessionStatus(session)
                } else {
                    AcPill("earlier", HistMuted, Color(Ds.BUTTON))
                }
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painterResource(R.drawable.ic_chevron),
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(if (expanded) 90f else 0f)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HistoryLootValue(R.drawable.ic_gold, totals.totalGold, "Gold")
                HistoryLootValue(R.drawable.ic_elixir, totals.totalElixir, "Elixir")
                HistoryLootValue(R.drawable.ic_dark, totals.totalDarkElixir, "Dark elixir")
            }
            if (expanded) {
                Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(1.dp).background(HistPanelBorder)
                    )
                    records.forEachIndexed { index, record ->
                        HistoryAttackLine(
                            record = record,
                            onClick = onAttackClick?.let { click -> { click(record) } }
                        )
                        if (index < records.lastIndex) {
                            Box(
                                Modifier.fillMaxWidth().height(1.dp).background(HistPanelBorder)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryAttackLine(
    record: AttackRecord,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                acClock(record.startedAt),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = HistText
            )
            Text(acDuration(record.durationMs), fontSize = 12.sp, color = HistMuted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HistoryLootValue(
                    R.drawable.ic_gold,
                    attackTotal(record.gold, record.bonusGold),
                    "Gold"
                )
                HistoryLootValue(
                    R.drawable.ic_elixir,
                    attackTotal(record.elixir, record.bonusElixir),
                    "Elixir"
                )
                HistoryLootValue(
                    R.drawable.ic_dark,
                    attackTotal(record.darkElixir, record.bonusDarkElixir),
                    "Dark elixir"
                )
            }
            if (record.needsReviewDefinition) {
                Spacer(Modifier.height(5.dp))
                Text(
                    reviewChipText(record),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HistWarn,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(Ds.WARN_BG))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

fun reviewChipText(record: AttackRecord): String {
    val reason = record.reviewReason?.trim()
    val base = if (reason.isNullOrEmpty()) {
        if (record.bonusState == AttackRecord.BONUS_NOT_CAPTURED) "bonus unreadable" else "needs review"
    } else {
        reason.split("; ").joinToString(" · ") { part ->
            part.replaceFirstChar { it.lowercaseChar() }
        }
    }
    return base
}

private fun attackTotal(loot: Long?, bonus: Long?): Long? =
    if (loot == null && bonus == null) null else (loot ?: 0L) + (bonus ?: 0L)

@Composable
fun HistoryLootValue(iconRes: Int, value: Long?, contentDescription: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 12.dp)
    ) {
        Image(
            painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            value?.let { Ds.compactNumber(it) } ?: "—",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (value != null) HistText else HistMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun ReviewBanner(
    count: Int,
    rescanning: Boolean,
    onRescan: () -> Unit,
    onDismiss: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(Ds.WARN_BG))
            .clickable(enabled = !rescanning, onClick = onRescan)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            modifier = Modifier.padding(start = 14.dp).size(19.dp)
        )
        Text(
            Plural.needReview(count),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = HistWarn,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 14.dp)
        )
        Text(
            if (rescanning) "Re-scanning…" else "Re-scan",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = HistWarn,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp)
        )
        if (onDismiss != null) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painterResource(R.drawable.ic_close),
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        label,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) Color.White else HistText,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Color(Ds.ACCENT) else Color(Ds.BUTTON))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttackDetailSheet(
    record: AttackRecord,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(Ds.PANEL),
        contentColor = HistText,
        dragHandle = { BottomSheetDefaults.DragHandle(color = HistMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
        ) {
            Text(
                "Attack details",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = HistText
            )
            Text(
                "${acDate(record.startedAt)} · ${acClock(record.startedAt)} · " +
                    acDuration(record.durationMs),
                fontSize = 12.sp,
                color = HistMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (record.macroName.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                SheetLine("Macro", record.macroName)
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(HistPanelBorder))
            Spacer(Modifier.height(14.dp))
            LootDetailRow(R.drawable.ic_gold, "Gold", record.gold, record.bonusGold)
            LootDetailRow(R.drawable.ic_elixir, "Elixir", record.elixir, record.bonusElixir)
            LootDetailRow(R.drawable.ic_dark, "Dark elixir", record.darkElixir, record.bonusDarkElixir)
            if (record.needsReviewDefinition) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(Ds.WARN_BG))
                        .padding(12.dp)
                ) {
                    Text(
                        "Needs review",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = HistWarn
                    )
                    Text(
                        record.reviewExplanation
                            ?: "Some values could not be read from the result screen.",
                        fontSize = 12.sp,
                        color = HistWarn,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.ACCENT))
                ) {
                    Text("Edit", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color(Ds.DANGER_OUTLINE)
                    )
                ) {
                    Text(
                        "Delete",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(Ds.DANGER_TEXT)
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, fontSize = 12.sp, color = HistMuted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = HistText)
    }
}

@Composable
private fun LootDetailRow(
    iconRes: Int,
    label: String,
    loot: Long?,
    bonus: Long?
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painterResource(iconRes),
            contentDescription = label,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HistText)
            Text(
                if (loot == null && bonus == null) {
                    "Not captured"
                } else {
                    "loot ${exact(loot)} + bonus ${exact(bonus)}"
                },
                fontSize = 11.sp,
                color = HistMuted
            )
        }
        Text(
            exact(attackTotal(loot, bonus)),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = HistText,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun exact(value: Long?): String =
    value?.let { String.format(Locale.US, "%,d", it) } ?: "—"
