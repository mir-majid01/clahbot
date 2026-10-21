package com.clahbot.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clahbot.service.ClahAccessibilityService
import com.clahbot.util.PermissionHelper
import kotlinx.coroutines.delay

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF4CAF50),
                background = Color(0xFF1A1A2E)
            )) {
                SplashContent()
            }
        }
    }
}

@Composable
fun SplashContent() {
    val context = LocalContext.current
    val scale = remember { Animatable(0.3f) }
    val alpha = remember { Animatable(0f) }
    val subtitleAlpha = remember { Animatable(0f) }
    val navigated = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        scale.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
        alpha.animateTo(1f, animationSpec = tween(400))
        subtitleAlpha.animateTo(1f, animationSpec = tween(600))
        delay(1200)

        if (navigated.getAndSet(true)) return@LaunchedEffect
        val hasOverlay = PermissionHelper.canDrawOverlays(context)
        val hasAccessibility = ClahAccessibilityService.isEnabled()
        val skip = hasOverlay && hasAccessibility
        val next = if (skip) {
            Intent(context, MainActivity::class.java)
        } else {
            Intent(context, OnboardingActivity::class.java)
        }
        context.startActivity(next)
        (context as? android.app.Activity)?.finish()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1A1A2E), Color(0xFF0F1623))
                )
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ClahBOT",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4CAF50),
            modifier = Modifier.scale(scale.value).alpha(alpha.value)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Macro Recorder",
            fontSize = 16.sp,
            color = Color(0xFF81C784),
            modifier = Modifier.alpha(subtitleAlpha.value)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "for Clash of Clans",
            fontSize = 14.sp,
            color = Color(0xFF888888),
            modifier = Modifier.alpha(subtitleAlpha.value)
        )
        Spacer(modifier = Modifier.height(48.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = Color(0xFF4CAF50),
            strokeWidth = 2.dp
        )
    }
}
