package com.nazhif.jagoan

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nazhif.jagoan.ui.theme.JagoanAndroidTheme

/**
 * MainActivity - The main screen of the Jagoan app
 * 
 * This activity displays a simple UI with a button that allows users
 * to enable notification access permission for the app.
 * 
 * Key Concepts for Beginners:
 * - ComponentActivity: The base class for activities using Jetpack Compose
 * - Jetpack Compose: Modern Android UI toolkit (declarative UI)
 * - Intent: A message object used to request actions from other app components
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JagoanAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NotificationAccessScreen(
                        onEnableClick = {
                            // Open Android's notification access settings
                            openNotificationSettings()
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    /**
     * Opens the Android system settings for notification access
     * 
     * This is where users can manually grant permission for the app
     * to read notifications. This is a sensitive permission that cannot
     * be requested programmatically - users must enable it manually.
     */
    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }
}

/**
 * The main UI screen with instructions and a button
 * 
 * @param onEnableClick Callback function when the button is clicked
 * @param modifier Modifier for styling
 */
@Composable
fun NotificationAccessScreen(
    onEnableClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App title
        Text(
            text = "💰 Jagoan",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Subtitle
        Text(
            text = "The Notification Sensor",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Instructions
        Text(
            text = "To track your Jago transactions automatically, " +
                    "you need to enable notification access for this app.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Enable button
        Button(
            onClick = onEnableClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = "Enable Notification Access",
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Help text
        Text(
            text = "After clicking the button, find 'Jagoan' in the list and toggle it ON",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
fun NotificationAccessScreenPreview() {
    JagoanAndroidTheme {
        NotificationAccessScreen()
    }
}