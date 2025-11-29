package com.nfo.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nfo.tracker.tracking.TrackingForegroundService
import com.nfo.tracker.ui.theme.NfoKotlinAppTheme
import com.nfo.tracker.work.HeartbeatWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NfoKotlinAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TrackingScreen()
                }
            }
        }
    }
}

@Composable
fun TrackingScreen() {
    val context = LocalContext.current
    var onShift by remember { mutableStateOf(false) }

    val statusText = if (onShift) {
        "On shift – tracking active"
    } else {
        "Off shift – tracking stopped"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "NFO Tracker (Kotlin)",
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )

        Button(
            onClick = {
                onShift = !onShift
                if (onShift) {
                    // Start foreground tracking and schedule watchdog
                    TrackingForegroundService.start(context)
                    HeartbeatWorker.schedule(context)
                } else {
                    // Stop foreground tracking
                    TrackingForegroundService.stop(context)
                }
            }
        ) {
            Text(text = if (onShift) "Go Off Shift" else "Go On Shift")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TrackingScreenPreview() {
    NfoKotlinAppTheme {
        TrackingScreen()
    }
}