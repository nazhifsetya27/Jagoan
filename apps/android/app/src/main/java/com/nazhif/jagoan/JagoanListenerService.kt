package com.nazhif.jagoan

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * JagoanListenerService - The Notification Sensor
 * 
 * This service runs in the background and listens to all notifications.
 * When a notification from Jago bank app arrives, it extracts the transaction
 * amount and sends it to the Node.js server.
 * 
 * Key Concepts for Beginners:
 * - NotificationListenerService: A special Android service that receives notification events
 * - Regex: Pattern matching to find amounts like "IDR 50.000" or "IDR 50,000"
 * - Coroutines: Kotlin's way to run code in the background without blocking the UI
 * - OkHttp: A library for making HTTP requests
 */
class JagoanListenerService : NotificationListenerService() {

    // Tag for logging - helps us find our logs in Logcat
    private val TAG = "JagoanSensor"
    
    // HTTP client for making network requests
    private val httpClient = OkHttpClient()
    
    // Server URL - Using localhost via ADB port forwarding
    // This bypasses WiFi network isolation issues
    private val SERVER_URL = "https://jagoan.kalachakra.io/webhook/transaction"
    
    // Package name of the Jago banking app (verified via adb)
    private val JAGO_PACKAGE = "com.jago.digitalBanking"
    
    // Deduplication: Store pairs of (Time, Amount)
    // We keep a small history to prevent double-counting the EXACT same amount within a short window
    private val processedTransactions = mutableListOf<Pair<Long, Double>>()
    private val DEBOUNCE_TIME = 5000L // 5 seconds window for exact duplicate amounts

    /**
     * This function is called whenever a new notification is posted
     * 
     * @param sbn StatusBarNotification - contains all notification data
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Safety check - make sure the notification is not null
        if (sbn == null) return

        // Get the package name of the app that posted this notification
        val packageName = sbn.packageName
        
        // Filter: Only process notifications from Jago bank app
        if (packageName != JAGO_PACKAGE) {
            return // Ignore notifications from other apps
        }

        Log.d(TAG, "📱 Jago notification received!")

        // Extract the notification content
        val notification = sbn.notification
        val extras = notification.extras
        
        // Get the notification title and text
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        
        Log.d(TAG, "Title: $title")
        Log.d(TAG, "Text: $text")

        // Try to extract the amount from the notification
        val amount = extractAmount(text)
        
        if (amount != null) {
            val currentTime = System.currentTimeMillis()
            
            // Cleanup: Remove transactions older than DEBOUNCE_TIME
            processedTransactions.removeAll { (time, _) -> 
                currentTime - time > DEBOUNCE_TIME 
            }
            
            // Check if we already processed this EXACT amount recently
            val isDuplicate = processedTransactions.any { (_, storedAmount) -> 
                storedAmount == amount 
            }
            
            if (isDuplicate) {
                Log.d(TAG, "♻️ Duplicate transaction amount ($amount) detected within ${DEBOUNCE_TIME/1000}s, ignoring...")
                return
            }

            // Add to processed list
            processedTransactions.add(Pair(currentTime, amount))
            
            Log.d(TAG, "💰 Amount extracted: $amount")
            // Send the amount to the server
            sendToServer(amount)
        } else {
            Log.d(TAG, "⚠️ No amount found in notification")
        }
    }

    /**
     * Extract transaction amount from notification text
     * 
     * This function uses Regex (Regular Expression) to find patterns like:
     * - "IDR 50.000" (with dots as thousand separators)
     * - "IDR 50,000" (with commas as thousand separators)
     * - "Rp 50.000" or "Rp 50,000"
     * 
     * Regex Explanation for Beginners:
     * - (IDR|Rp): Matches either "IDR" or "Rp"
     * - \s*: Matches zero or more whitespace characters
     * - ([\d.,]+): Matches one or more digits, dots, or commas
     * 
     * @param text The notification text to search
     * @return The amount as a Double, or null if not found
     */
    private fun extractAmount(text: String): Double? {
        // Regex pattern to match Indonesian currency formats
        val pattern = Regex("(IDR|Rp)\\s*([\\d.,]+)")
        
        // Find the first match in the text
        val matchResult = pattern.find(text)
        
        if (matchResult != null) {
            // Extract the amount part (group 2)
            val amountString = matchResult.groupValues[2]
            
            // Clean up the amount string:
            // - Remove dots (thousand separators in Indonesian format)
            // - Remove commas (thousand separators in international format)
            val cleanAmount = amountString.replace(".", "").replace(",", "")
            
            // Convert to Double and return
            return cleanAmount.toDoubleOrNull()
        }
        
        return null
    }

    /**
     * Send the extracted amount to the Node.js server
     * 
     * This function runs in the background using Coroutines to avoid blocking
     * the main thread. Network operations must always run in the background.
     * 
     * @param amount The transaction amount to send
     */
    private fun sendToServer(amount: Double) {
        // Launch a coroutine on the IO dispatcher (optimized for network operations)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Create JSON payload: { "amount": 50000 }
                val json = JSONObject().apply {
                    put("amount", amount)
                }
                
                Log.d(TAG, "📤 Sending to server: $json")
                
                // Create the HTTP request body
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = json.toString().toRequestBody(mediaType)
                
                // Build the HTTP POST request
                val request = Request.Builder()
                    .url(SERVER_URL)
                    .post(requestBody)
                    .build()
                
                // Execute the request
                val response = httpClient.newCall(request).execute()
                
                // Check if the request was successful
                if (response.isSuccessful) {
                    Log.d(TAG, "✅ Successfully sent to server: ${response.code}")
                } else {
                    Log.e(TAG, "❌ Server error: ${response.code} - ${response.message}")
                }
                
                response.close()
                
            } catch (e: Exception) {
                // Log any errors that occur
                Log.e(TAG, "❌ Error sending to server: ${e.message}", e)
            }
        }
    }

    /**
     * Called when a notification is removed
     * We don't need to do anything here, but it's part of the NotificationListenerService
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed for our use case
    }
}
