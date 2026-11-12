package com.clahbot.ui.dashboard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.clahbot.R
import com.clahbot.data.AppEventLog
import com.clahbot.game.GameScreenEngine
import com.clahbot.results.AppResultsReader
import com.clahbot.ui.MainActivity
import com.clahbot.ui.design.Ds
import com.clahbot.walls.AutoWallsConfig
import com.clahbot.walls.AutoWallsDevice
import com.clahbot.walls.AutoWallsRepository
import com.clahbot.walls.AutoWallsScan
import com.clahbot.walls.AutoWallsScanReport
import com.clahbot.walls.AutoWallsScanner
import com.clahbot.walls.WallCandidate
import com.clahbot.walls.WallCriterion
import com.clahbot.walls.WallCurrency
import com.clahbot.walls.WallSelectionStrategy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SCAN_DELAY_SECONDS = 12
private const val SCAN_CHANNEL_ID = "auto_walls_scan"
private const val SCAN_NOTIFICATION_ID = 4242

/**
 * Auto Walls 2.0 Phase 1: a read-only lab. It runs the real capture → OCR →
 * [com.clahbot.walls.WallDiscovery] → [com.clahbot.walls.WallSelection] pipeline
 * and shows exactly what would be chosen. Tapping is locked
 * (`allowTapping=false`) and no gesture is ever dispatched from this screen —
 * the only device call is a frame observation.
 *
 * The legacy wall-macro recording/selection controls are deliberately absent;
 * their data and the old card are untouched.
 */
@Composable
fun AcAutoWalls2Section(repository: AutoWallsRepository, scope: CoroutineScope) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(repository.loadConfig()) }
    var report by remember { mutableStateOf(repository.loadScan()) }
    var history by remember { mutableStateOf(repository.history()) }
    var expanded by remember { mutableStateOf(true) }
    var countdown by remember { mutableStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    fun persist(next: AutoWallsConfig) {
        config = next
        repository.saveConfig(next)
    }

    fun chooseStrategy(strategy: WallSelectionStrategy) {
        val order = if (strategy == WallSelectionStrategy.CUSTOM &&
            config.selection.customOrder.isEmpty()
        ) {
            listOf(
                WallCriterion.CHEAPEST,
                WallCriterion.HIGHEST_LEVEL,
                WallCriterion.LOWEST_LEVEL
            )
        } else {
            config.selection.customOrder
        }
        persist(config.copy(selection = config.selection.copy(strategy = strategy, customOrder = order)))
    }

    fun moveCriterion(from: Int, to: Int) {
        val order = config.selection.customOrder.ifEmpty { listOf(WallCriterion.CHEAPEST) }
            .toMutableList()
        if (from !in order.indices || to !in order.indices) return
        val moved = order.removeAt(from)
        order.add(to, moved)
        persist(config.copy(selection = config.selection.copy(customOrder = order)))
    }

    fun startScan() {
        if (scanning || countdown > 0) return
        scope.launch {
            for (second in SCAN_DELAY_SECONDS downTo 1) {
                countdown = second
                delay(1_000)
            }
            countdown = 0
            scanning = true
            val result = try {
                // One reader per scan: the OCR engine is warmed for this read and
                // released after, so the card never leaks a recognizer.
                val reader = AppResultsReader(context)
                try {
                    AutoWallsScanner(
                        repository = repository,
                        device = AutoWallsDevice(GameScreenEngine(), reader),
                        log = { message -> AppEventLog.log(message) }
                    ).scan()
                } finally {
                    reader.closeAsync()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AutoWallsScan.captureFailed(
                    foregroundPackage = null,
                    config = config,
                    detail = "scan failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }
            report = result
            history = repository.history()
            scanning = false
            notifyScanResult(context, result)
        }
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
                "Auto Walls 2.0",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(Ds.TEXT),
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(Ds.WARN_BG))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("SCAN ONLY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(Ds.WARN))
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center
            ) {
                Text(if (expanded) "▴" else "▾", fontSize = 15.sp, color = Color(Ds.LINK))
            }
        }
        Text(
            "Reads the wall list with OCR. No taps, no scrolls, no upgrades.",
            fontSize = 11.sp,
            color = Color(Ds.MUTED)
        )
        Text(
            AutoWallsScan.strategyLabel(config.selection.strategy) + " · " +
                AutoWallsScan.currencyLabel(config.selection.currency) + " · conf " +
                AutoWallsScan.confidenceLabel(config.selection.minConfidence) + " · " +
                (report?.let { "last scan " + timeFormat.format(Date(it.at)) } ?: "no scan yet"),
            fontSize = 11.sp,
            color = Color(Ds.MUTED)
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(Ds.SUCCESS_BG))
                        .padding(10.dp)
                ) {
                    Text(
                        "Execution locked — allowTapping=false",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(Ds.SUCCESS)
                    )
                    Text(
                        "This build sends no row taps, confirmation taps or scrolls.",
                        fontSize = 10.sp,
                        color = Color(Ds.MUTED)
                    )
                }

                Ac2Label("PRIORITY")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        WallSelectionStrategy.CHEAPEST_FIRST,
                        WallSelectionStrategy.HIGHEST_LEVEL_FIRST,
                        WallSelectionStrategy.LOWEST_LEVEL_FIRST
                    ).forEach { strategy ->
                        Ac2Choice(
                            label = AutoWallsScan.strategyLabel(strategy),
                            selected = config.selection.strategy == strategy
                        ) { chooseStrategy(strategy) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        WallSelectionStrategy.FIRST_IN_LIST,
                        WallSelectionStrategy.CUSTOM
                    ).forEach { strategy ->
                        Ac2Choice(
                            label = AutoWallsScan.strategyLabel(strategy),
                            selected = config.selection.strategy == strategy
                        ) { chooseStrategy(strategy) }
                    }
                    Spacer(Modifier.weight(1f))
                }

                if (config.selection.strategy == WallSelectionStrategy.CUSTOM) {
                    Ac2Label("CUSTOM ORDER")
                    val order = config.selection.customOrder.ifEmpty { listOf(WallCriterion.CHEAPEST) }
                    order.forEachIndexed { index, criterion ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "${index + 1}. ${AutoWallsScan.criterionLabel(criterion)}",
                                fontSize = 11.5.sp,
                                color = Color(Ds.TEXT),
                                modifier = Modifier.weight(1f)
                            )
                            Ac2SmallButton("▲", enabled = index > 0) { moveCriterion(index, index - 1) }
                            Ac2SmallButton("▼", enabled = index < order.lastIndex) {
                                moveCriterion(index, index + 1)
                            }
                        }
                        if (index < order.lastIndex) Spacer(Modifier.height(4.dp))
                    }
                }

                Ac2Label("PAY WITH")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WallCurrency.entries.forEach { currency ->
                        Ac2Choice(
                            label = AutoWallsScan.currencyLabel(currency),
                            selected = config.selection.currency == currency
                        ) {
                            persist(config.copy(selection = config.selection.copy(currency = currency)))
                        }
                    }
                }

                Ac2Label("MIN CONFIDENCE")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Drop rows below",
                        fontSize = 10.5.sp,
                        color = Color(Ds.MUTED),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        AutoWallsScan.confidenceLabel(config.selection.minConfidence),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(Ds.LINK)
                    )
                }
                Slider(
                    value = config.selection.minConfidence.coerceIn(0.3f, 1f),
                    onValueChange = { value ->
                        config = config.copy(
                            selection = config.selection.copy(
                                minConfidence = snapConfidence(value)
                            )
                        )
                    },
                    onValueChangeFinished = { repository.saveConfig(config) },
                    valueRange = 0.3f..1f,
                    steps = 13,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(Ds.ACCENT),
                        activeTrackColor = Color(Ds.ACCENT),
                        inactiveTrackColor = Color(Ds.BUTTON)
                    )
                )

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(Ds.DIVIDER)))
                Ac2Label("SCAN GAME SCREEN")
                Text(
                    "1. Open Buildings & Heroes → Walls in Clash.\n" +
                        "2. Tap the button, then switch back to Clash within " +
                        "$SCAN_DELAY_SECONDS seconds.\n" +
                        "3. A notification shows the result; open AutoClash for the full report.",
                    fontSize = 10.5.sp,
                    color = Color(Ds.MUTED)
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { startScan() },
                    enabled = countdown == 0 && !scanning,
                    shape = RoundedCornerShape(Ds.RADIUS_BUTTON.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(Ds.ACCENT)),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text(
                        when {
                            countdown > 0 -> "Switch to Clash — scanning in ${countdown}s"
                            scanning -> "Reading the screen…"
                            else -> "Scan game screen ($SCAN_DELAY_SECONDS s)"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                report?.let { scan ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(Ds.DIVIDER)))
                    Ac2Label("LAST SCAN")
                    Text(
                        timeFormat.format(Date(scan.at)) + " · " +
                            (scan.foregroundPackage ?: "foreground unreadable"),
                        fontSize = 10.5.sp,
                        color = Color(Ds.MUTED)
                    )
                    if (scan.captured) {
                        Text(
                            "${scan.frameWidth}×${scan.frameHeight} · ${scan.lineCount} OCR lines · " +
                                "screen ${scan.screenState}",
                            fontSize = 10.5.sp,
                            color = Color(Ds.MUTED)
                        )
                        Text(
                            "${scan.candidates.size} wall row(s) · ${scan.eligible.size} eligible · " +
                                "min conf ${AutoWallsScan.confidenceLabel(scan.minConfidence)}",
                            fontSize = 10.5.sp,
                            color = Color(Ds.MUTED)
                        )
                    }
                    scan.reason?.let { reason ->
                        Text(reason, fontSize = 11.sp, color = Color(Ds.WARN))
                    }
                    scan.candidates.take(10).forEach { candidate ->
                        Ac2ReportRow(
                            candidate = candidate,
                            selected = candidate.id == scan.selected?.id,
                            minConfidence = scan.minConfidence
                        )
                    }
                    if (scan.candidates.size > 10) {
                        Text(
                            "+${scan.candidates.size - 10} more row(s) (counts are in the event log)",
                            fontSize = 10.sp,
                            color = Color(Ds.MUTED)
                        )
                    }
                    scan.selected?.let { selected ->
                        Text(
                            "Would select: L${selected.level} · " +
                                "${currencyText(selected.currency)} · " +
                                AutoWallsScan.priceLabel(selected.price),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(Ds.SUCCESS)
                        )
                    }
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(Ds.DIVIDER)))
                Ac2Label("HISTORY")
                if (history.isEmpty()) {
                    Text(
                        "No upgrade history yet — this phase never executes an upgrade.",
                        fontSize = 10.5.sp,
                        color = Color(Ds.MUTED)
                    )
                } else {
                    history.take(5).forEach { entry ->
                        Text(
                            timeFormat.format(Date(entry.at)) + " · " + entry.status +
                                " · L${entry.level} ${currencyText(entry.currency)} " +
                                AutoWallsScan.priceLabel(entry.price) +
                                if (entry.detail.isBlank()) "" else " · ${entry.detail}",
                            fontSize = 10.sp,
                            color = Color(Ds.MUTED)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Trigger, reserve, repeat and speed are Phase 2 — their logic exists " +
                        "but nothing here is wired to a gesture.",
                    fontSize = 9.5.sp,
                    color = Color(Ds.MUTED)
                )
            }
        }
    }
}

private fun snapConfidence(value: Float): Float =
    ((value / 0.05f).roundToInt() * 0.05f).coerceIn(0.3f, 1f)

@Composable
private fun Ac2Label(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Color(Ds.MUTED),
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun RowScope.Ac2Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(32.dp)
            .clip(RoundedCornerShape(Ds.RADIUS_BUTTON.dp))
            .background(if (selected) Color(Ds.ACCENT) else Color(Ds.BUTTON))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else Color(Ds.MUTED)
        )
    }
}

@Composable
private fun Ac2SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 32.dp, height = 26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(Ds.BUTTON))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (enabled) Color(Ds.LINK) else Color(Ds.MUTED).copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun Ac2ReportRow(candidate: WallCandidate, selected: Boolean, minConfidence: Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(Ds.SELECTED_ROW) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "L${candidate.level}",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = Color(Ds.TEXT),
            modifier = Modifier.width(42.dp)
        )
        Text(
            currencyText(candidate.currency),
            fontSize = 10.5.sp,
            color = currencyColor(candidate.currency),
            modifier = Modifier.width(52.dp)
        )
        Text(
            AutoWallsScan.priceLabel(candidate.price),
            fontSize = 10.5.sp,
            color = Color(Ds.TEXT),
            modifier = Modifier.weight(1f)
        )
        Text(
            "×${candidate.count ?: "?"}",
            fontSize = 10.sp,
            color = Color(Ds.MUTED),
            modifier = Modifier.width(46.dp)
        )
        Text(
            AutoWallsScan.confidenceLabel(candidate.confidence),
            fontSize = 10.sp,
            color = if (candidate.confidence >= minConfidence) Color(Ds.MUTED) else Color(Ds.WARN),
            modifier = Modifier.width(36.dp)
        )
        if (selected) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(Ds.SUCCESS_BG))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "SELECTED",
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(Ds.SUCCESS)
                )
            }
        }
    }
}

private fun currencyText(currency: String): String = when (currency.lowercase(Locale.US)) {
    "gold" -> "Gold"
    "elixir" -> "Elixir"
    "dark" -> "Dark"
    else -> currency.ifBlank { "?" }
}

private fun currencyColor(currency: String): Color = when (currency.lowercase(Locale.US)) {
    "gold" -> Color(Ds.GOLD)
    "elixir" -> Color(Ds.ELIXIR)
    "dark" -> Color(Ds.DARK_ELIXIR)
    else -> Color(Ds.MUTED)
}

private fun notifyScanResult(context: Context, report: AutoWallsScanReport) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
            NotificationChannel(
                SCAN_CHANNEL_ID,
                "Auto Walls 2.0 scans",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Result of a read-only wall-list OCR scan" }
        )
    }
    val selected = report.selected
    val summary = when {
        selected != null -> "Scan done · ${report.candidates.size} rows · would select " +
            "L${selected.level} ${currencyText(selected.currency)} " +
            AutoWallsScan.priceLabel(selected.price)
        report.reason != null -> "Scan done · ${report.reason}"
        else -> "Scan done"
    }
    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val notification = NotificationCompat.Builder(context, SCAN_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Auto Walls 2.0 scan")
        .setContentText(summary)
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                summary + "\nNo taps or scrolls were sent (allowTapping=false)."
            )
        )
        .setContentIntent(open)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    NotificationManagerCompat.from(context).notify(SCAN_NOTIFICATION_ID, notification)
}
