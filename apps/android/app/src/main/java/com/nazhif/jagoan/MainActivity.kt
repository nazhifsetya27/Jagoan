package com.nazhif.jagoan

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nazhif.jagoan.ui.theme.JagoanAndroidTheme

/**
 * MainActivity - The main screen of the Jagoan app
 * 
 * This activity displays different UI states based on notification access permission:
 * - Permission not granted: Shows button to enable
 * - Permission granted: Shows "App is Ready" status
 */
class MainActivity : ComponentActivity() {
    
    // State to track permission changes
    private var permissionState = mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Check initial permission state
        permissionState.value = isNotificationAccessGranted()
        
        setContent {
            JagoanAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NotificationAccessScreen(
                        isPermissionGranted = permissionState.value,
                        onEnableClick = {
                            openNotificationSettings()
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    /**
     * Check if notification access permission is granted
     */
    private fun isNotificationAccessGranted(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        val packageName = packageName
        return enabledListeners?.contains(packageName) == true
    }

    /**
     * Opens the Android system settings for notification access
     */
    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    /**
     * Called when the activity resumes - check permission again
     */
    override fun onResume() {
        super.onResume()
        // Update permission state when returning from settings
        permissionState.value = isNotificationAccessGranted()
    }
}

/**
 * The main UI screen with dynamic states based on permission
 */
@Composable
fun NotificationAccessScreen(
    isPermissionGranted: Boolean = false,
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
        
        if (isPermissionGranted) {
            // Permission granted - show success state
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "✅",
                        style = MaterialTheme.typography.displayLarge
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "App is Ready!",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Jagoan is now monitoring your Jago transactions automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Info text
            Text(
                text = "Every time you make a transaction, you'll receive a Telegram message asking for the purpose.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Permission not granted - show setup instructions
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
}

@Preview(showBackground = true)
@Composable
fun NotificationAccessScreenPreview() {
    JagoanAndroidTheme {
        NotificationAccessScreen(isPermissionGranted = false)
    }
}

@Preview(showBackground = true)
@Composable
fun NotificationAccessScreenGrantedPreview() {
    JagoanAndroidTheme {
        NotificationAccessScreen(isPermissionGranted = true)
    }
}