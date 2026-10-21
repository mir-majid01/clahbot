package com.clahbot.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import com.clahbot.util.PermissionHelper

class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF4CAF50),
                background = Color(0xFF1A1A2E),
                surface = Color(0xFF16213E)
            )) {
                PermissionChecklist(onComplete = {
                    startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                    finish()
                })
            }
        }
    }
}

data class PermissionItem(
    val title: String,
    val description: String,
    val isGranted: () -> Boolean,
    val onRequest: () -> Unit,
    val isRequired: Boolean = true
)

@Composable
fun PermissionChecklist(onComplete: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }

    val permissions = remember {
        listOf(
            PermissionItem(
                title = "Display over other apps",
                description = "Required for the floating control panel to appear on top of Clash of Clans.",
                isGranted = { PermissionHelper.canDrawOverlays(context) },
                onRequest = { PermissionHelper.requestOverlayPermission(context) }
            ),
            PermissionItem(
                title = "Accessibility Service",
                description = "Required to simulate taps and swipes in Clash of Clans. Enable 'ClahBOT' in Accessibility settings.",
                isGranted = { PermissionHelper.isAccessibilityEnabled(context) },
                onRequest = { PermissionHelper.openAccessibilitySettings(context) }
            ),
            PermissionItem(
                title = "Battery Optimization Exemption",
                description = "Prevents Android from killing ClahBOT during long recording or playback sessions.",
                isGranted = { PermissionHelper.isBatteryOptimizationIgnored(context) },
                onRequest = { PermissionHelper.requestBatteryOptimizationExemption(context) },
                isRequired = false
            ),
            PermissionItem(
                title = "Notifications",
                description = "Shows recording and playback status in the notification bar.",
                isGranted = { PermissionHelper.canPostNotifications(context) },
                onRequest = { PermissionHelper.openNotificationSettings(context) },
                isRequired = false
            )
        )
    }

    val allRequiredGranted = permissions.filter { it.isRequired }.all { it.isGranted() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Permissions Required",
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = Color.White
        )
        Text(
            "ClahBOT needs these permissions to function. The red-highlighted items are mandatory.",
            color = Color.Gray,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(4.dp))

        var idx = 0
        for (perm in permissions) {
            val granted = perm.isGranted()
            idx++
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (perm.isRequired && !granted) Color(0xFF3A1A1A) else Color(0xFF16213E)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$idx.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            perm.title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (granted) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (granted) Color(0xFF4CAF50) else Color(0xFFF44336),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(perm.description, color = Color.Gray, fontSize = 12.sp)
                    if (!granted) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                perm.onRequest()
                                refresh++
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (perm.isRequired) Color(0xFFF44336) else Color(0xFF4CAF50)
                            )
                        ) {
                            Text(
                                if (perm.isRequired) "Grant (Required)" else "Grant (Recommended)",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onComplete,
            enabled = allRequiredGranted,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50),
                disabledContainerColor = Color(0xFF333333)
            )
        ) {
            Text(
                if (allRequiredGranted) "Continue to App" else "Grant all required permissions first",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Re-trigger recomposition every second to refresh permission states
        androidx.compose.runtime.LaunchedEffect(refresh) {
            kotlinx.coroutines.delay(1000)
            refresh++
        }
    }
}
