package com.nazhif.jagoan

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.nazhif.jagoan.overlay.OverlayCategory
import com.nazhif.jagoan.overlay.OverlayController
import com.nazhif.jagoan.overlay.OverlayTransaction
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

    // State to track the "Display over other apps" (overlay) permission
    private var overlayPermissionState = mutableStateOf(false)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshPermissions() }

    /** Re-read both permissions into Compose state. */
    private fun refreshPermissions() {
        permissionState.value = isNotificationAccessGranted()
        overlayPermissionState.value = canDrawOverlays()
    }

    // Debug-only: an overlay controller used by the "Test Overlay" button so the overlay
    // can be exercised without a real Jago transaction. Save just simulates success.
    private val testOverlay by lazy {
        OverlayController(
            context = this,
            onConfirm = { _, _, _, onResult ->
                Handler(Looper.getMainLooper()).postDelayed({ onResult(true, null) }, 700)
            },
            onDiscard = { },
        )
    }

    private fun showTestOverlay() {
        testOverlay.show(
            OverlayTransaction(
                transactionId = "test-${System.currentTimeMillis()}",
                amount = 12345.0,
                categories = listOf(
                    OverlayCategory("week1", "Week 1"),
                    OverlayCategory("rokok", "Rokok"),
                    OverlayCategory("amplop", "amplop"),
                    OverlayCategory("invest", "INVEST"),
                ),
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check initial permission state
        refreshPermissions()

        setContent {
            JagoanAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NotificationAccessScreen(
                        isPermissionGranted = permissionState.value,
                        isOverlayGranted = overlayPermissionState.value,
                        onEnableClick = {
                            openNotificationSettings()
                        },
                        onEnableOverlayClick = {
                            openOverlaySettings()
                        },
                        // Debug builds get a button to preview the overlay on demand.
                        onTestOverlayClick = if (BuildConfig.DEBUG) {
                            { showTestOverlay() }
                        } else null,
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
     * Check if the "Display over other apps" permission is granted (needed for overlay mode)
     */
    private fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    /**
     * Opens notification-access settings. Deep-links straight to Jagoan's own toggle
     * (API 30+) and flags that we're awaiting a grant, so JagoanListenerService can pull
     * the app back to the foreground once access is enabled.
     */
    private fun openNotificationSettings() {
        getSharedPreferences(JagoanListenerService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(JagoanListenerService.KEY_AWAITING_NOTIF, true)
            .apply()

        val component = ComponentName(this, JagoanListenerService::class.java)
        try {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        component.flattenToString()
                    )
            )
        } catch (e: Exception) {
            // Fallback to the full list on devices without the per-app detail screen.
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    /**
     * Opens the system settings to grant "Display over other apps"
     */
    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    /**
     * Called when the activity resumes - check permissions again.
     *
     * We refresh immediately, then re-check a couple of times shortly after: when the
     * listener service auto-returns us at the exact moment notification access is granted,
     * Settings.Secure can still report the old value on this first read, leaving the UI
     * stuck on the previous step.
     */
    override fun onResume() {
        super.onResume()
        refreshPermissions()
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, 350)
        mainHandler.postDelayed(refreshRunnable, 900)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(refreshRunnable)
    }
}

/**
 * The main UI screen with dynamic states based on permission
 */
@Composable
fun NotificationAccessScreen(
    isPermissionGranted: Boolean = false,
    isOverlayGranted: Boolean = false,
    onEnableClick: () -> Unit = {},
    onEnableOverlayClick: () -> Unit = {},
    onTestOverlayClick: (() -> Unit)? = null,
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

        when {
            // Fully set up — both permissions granted
            isPermissionGranted && isOverlayGranted -> {
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
                    text = "Every time you make a transaction, you'll be asked for the purpose " +
                            "— via a quick on-screen popup or Telegram, depending on the server mode.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Debug-only: preview the overlay without a real transaction.
                if (onTestOverlayClick != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onTestOverlayClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🧪 Test Overlay")
                    }
                }
            }

            // Step 1: notification access not granted yet
            !isPermissionGranted -> {
                Text(
                    text = "To track your Jago transactions automatically, " +
                            "you need to enable notification access for this app.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(32.dp))

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

                Text(
                    text = "After clicking the button, find 'Jagoan' in the list and toggle it ON",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Step 2: notification access granted, overlay permission still needed
            else -> {
                Text(
                    text = "One more step: allow Jagoan to display over other apps so it can " +
                            "show the quick transaction popup right after you spend.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = onEnableOverlayClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Enable Display Over Other Apps",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "After clicking the button, find 'Jagoan' and toggle 'Allow display over other apps' ON",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NotificationAccessScreenPreview() {
    JagoanAndroidTheme {
        NotificationAccessScreen(isPermissionGranted = false, isOverlayGranted = false)
    }
}

@Preview(showBackground = true)
@Composable
fun NotificationAccessScreenOverlayStepPreview() {
    JagoanAndroidTheme {
        NotificationAccessScreen(isPermissionGranted = true, isOverlayGranted = false)
    }
}

@Preview(showBackground = true)
@Composable
fun NotificationAccessScreenGrantedPreview() {
    JagoanAndroidTheme {
        NotificationAccessScreen(isPermissionGranted = true, isOverlayGranted = true)
    }
}