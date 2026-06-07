package com.nazhif.jagoan

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.nazhif.jagoan.overlay.OverlayCategory
import com.nazhif.jagoan.overlay.OverlayController
import com.nazhif.jagoan.overlay.OverlayTransaction
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

    // Server base URL for the webhook (debug -> localhost via `adb reverse`,
    // release -> production). Set per build type in build.gradle.kts.
    // /confirm and /discard are derived from it.
    private val BASE_URL = BuildConfig.SERVER_BASE_URL

    // Posts results back to the main thread (overlay UI lives there).
    private val mainHandler = Handler(Looper.getMainLooper())

    // Floating overlay used in "overlay" mode (created lazily on first use).
    private val overlayController by lazy {
        OverlayController(
            context = this,
            onConfirm = { transactionId, name, categoryKey, onResult ->
                confirmTransaction(transactionId, name, categoryKey, onResult)
            },
            onDiscard = { transactionId -> discardTransaction(transactionId) },
        )
    }
    
    // Package name of the Jago banking app (verified via adb)
    private val JAGO_PACKAGE = "com.jago.digitalBanking"
    
    // Deduplication: Store pairs of (Time, Amount)
    // We keep a small history to prevent double-counting the EXACT same amount within a short window
    private val processedTransactions = mutableListOf<Pair<Long, Double>>()
    private val DEBOUNCE_TIME = 5000L // 5 seconds window for exact duplicate amounts

    // Notification type constants
    private val TYPE_OUTGOING = "OUTGOING"  // Real expenses (money going out)
    private val TYPE_INCOMING = "INCOMING"  // Money received, promos, cashback, etc.

    /**
     * Detect if the notification is an outgoing transaction (expense) or incoming (revenue/promo)
     * 
     * Based on actual Jago notification patterns (in English):
     * - OUTGOING: "You've transferred Rp5.000 to NAZHIF SETYA NUGROHO..."
     * - INCOMING: "You've received Rp5.000 from GoPay..."
     * 
     * @param title The notification title
     * @param text The notification text
     * @return TYPE_OUTGOING for expenses, TYPE_INCOMING for received money/promos
     */
    private fun detectNotificationType(title: String, text: String): String {
        val combinedText = "$title $text".lowercase()
        
        // Keywords that indicate OUTGOING transactions (expenses)
        // English patterns (actual Jago notifications)
        val outgoingKeywords = listOf(
            // English - Primary patterns from actual Jago notifications
            "you've transferred",   // "You've transferred Rp5.000 to..."
            "you have transferred", // Alternative phrasing
            "transferred to",       // "transferred Rp5.000 to NAZHIF..."
            "made a transfer",      // "You have made a transfer"
            "you've paid",          // Payment notification
            "you have paid",        // Alternative phrasing
            "paid to",              // "paid Rp... to..."
            "you've sent",          // Sent money
            "you have sent",        // Alternative phrasing
            "sent to",              // "sent Rp... to..."
            "withdrawal",           // Cash withdrawal
            "payment successful",   // Payment confirmation
            "purchase",             // Purchase notification
            "top up",               // Top up e-wallet
            "topped up",            // Topped up
            
            // Indonesian patterns (backup)
            "transfer berhasil",    // Successful transfer (sent)
            "pembayaran berhasil",  // Successful payment
            "transaksi berhasil",   // Successful transaction
            "berhasil dikirim",     // Successfully sent
            "tarik tunai",          // Cash withdrawal
            "transfer ke",          // Transfer to
            "bayar ",               // Pay
            "beli ",                // Buy
            "pembelian"             // Purchase
        )
        
        // Keywords that indicate INCOMING transactions (received money, promos, cashback)
        val incomingKeywords = listOf(
            // English - Primary patterns from actual Jago notifications
            "you've received",      // "You've received Rp5.000 from GoPay"
            "you have received",    // Alternative phrasing
            "received from",        // "received Rp... from..."
            "you've got",           // Got money
            "you have got",         // Alternative phrasing
            "incoming transfer",    // Incoming transfer
            "money received",       // Money received
            
            // Common patterns for promos/cashback
            "promo",                // Promo
            "cashback",             // Cashback
            "bonus",                // Bonus
            "reward",               // Reward
            "interest",             // Interest earned
            "refund",               // Refund
            
            // Indonesian patterns (backup)
            "transfer masuk",       // Incoming transfer
            "terima ",              // Receive
            "menerima",             // Received
            "hadiah",               // Gift/reward
            "pengembalian",         // Refund
            "saldo masuk",          // Balance in
            "bunga"                 // Interest
        )
        
        // Check for outgoing keywords first (expenses are what we want to track)
        for (keyword in outgoingKeywords) {
            if (combinedText.contains(keyword)) {
                Log.d(TAG, "🔍 Matched OUTGOING keyword: '$keyword'")
                return TYPE_OUTGOING
            }
        }
        
        // Check for incoming keywords
        for (keyword in incomingKeywords) {
            if (combinedText.contains(keyword)) {
                Log.d(TAG, "🔍 Matched INCOMING keyword: '$keyword'")
                return TYPE_INCOMING
            }
        }
        
        // Default: If we can't determine, assume INCOMING to be safe
        // This prevents accidentally logging promos as expenses
        Log.d(TAG, "⚠️ No keyword matched, defaulting to INCOMING")
        return TYPE_INCOMING
    }

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

        // Detect notification type (OUTGOING or INCOMING)
        val notificationType = detectNotificationType(title, text)
        Log.d(TAG, "📋 Notification type: $notificationType")

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
            // Send the amount and type to the server
            sendToServer(amount, notificationType)
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
     * @param type The notification type (OUTGOING or INCOMING)
     */
    private fun sendToServer(amount: Double, type: String) {
        // Launch a coroutine on the IO dispatcher (optimized for network operations)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Create JSON payload: { "amount": 50000, "type": "OUTGOING" }
                val json = JSONObject().apply {
                    put("amount", amount)
                    put("type", type)
                }

                Log.d(TAG, "📤 Sending to server: $json")

                // Create the HTTP request body
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = json.toString().toRequestBody(mediaType)

                // Build the HTTP POST request
                val request = Request.Builder()
                    .url(BASE_URL)
                    .post(requestBody)
                    .build()

                // Execute the request (.use closes the response for us)
                httpClient.newCall(request).execute().use { response ->
                    // body.string() is single-use — read it exactly once.
                    val bodyStr = response.body?.string()

                    if (!response.isSuccessful) {
                        Log.e(TAG, "❌ Server error: ${response.code} - ${response.message}")
                        return@use
                    }

                    Log.d(TAG, "✅ Successfully sent to server: ${response.code}")

                    // In "overlay" mode the server returns the data the on-device overlay
                    // needs. In "telegram" mode there's nothing more to do here.
                    val obj = bodyStr?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (obj != null && obj.optString("mode") == "overlay") {
                        maybeShowOverlay(obj)
                    }
                }
            } catch (e: Exception) {
                // Log any errors that occur
                Log.e(TAG, "❌ Error sending to server: ${e.message}", e)
            }
        }
    }

    /**
     * Parse an overlay-mode webhook response and show the floating overlay, if we're
     * allowed to draw over other apps. Runs on the IO thread; the controller hops to main.
     */
    private fun maybeShowOverlay(obj: JSONObject) {
        val transactionId = obj.optString("transactionId")
        if (transactionId.isEmpty()) {
            Log.e(TAG, "⚠️ Overlay response missing transactionId")
            return
        }

        // Graceful fallback: without the "Display over other apps" permission we cannot
        // show the overlay. Skip this round (MainActivity nudges the user to grant it).
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "⚠️ Overlay permission not granted; skipping overlay for $transactionId")
            return
        }

        val categories = mutableListOf<OverlayCategory>()
        obj.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val key = c.optString("key")
                val label = c.optString("label")
                if (key.isNotEmpty() && label.isNotEmpty()) {
                    categories.add(OverlayCategory(key, label))
                }
            }
        }

        overlayController.show(
            OverlayTransaction(
                transactionId = transactionId,
                amount = obj.optDouble("amount", 0.0),
                categories = categories,
            )
        )
    }

    /**
     * Submit the user's chosen purpose + category for a pending transaction.
     * Delivers the result on the main thread (the overlay UI lives there).
     */
    private fun confirmTransaction(
        transactionId: String,
        name: String,
        categoryKey: String?,
        onResult: (success: Boolean, errorMessage: String?) -> Unit,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val (success, error) = try {
                val json = JSONObject().apply {
                    put("transactionId", transactionId)
                    put("name", name)
                    if (categoryKey != null) put("categoryKey", categoryKey)
                }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url("$BASE_URL/confirm")
                    .post(json.toString().toRequestBody(mediaType))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        true to null
                    } else {
                        Log.e(TAG, "❌ Confirm failed: ${response.code}")
                        false to "Gagal menyimpan (${response.code})."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error confirming transaction: ${e.message}", e)
                false to "Tidak dapat terhubung ke server."
            }
            mainHandler.post { onResult(success, error) }
        }
    }

    /**
     * Fire-and-forget: drop a cancelled transaction from the server cache.
     */
    private fun discardTransaction(transactionId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply { put("transactionId", transactionId) }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url("$BASE_URL/discard")
                    .post(json.toString().toRequestBody(mediaType))
                    .build()
                httpClient.newCall(request).execute().use { /* result ignored */ }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error discarding transaction: ${e.message}", e)
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

    /**
     * Called when notification access is granted and the listener is bound. If the user
     * just enabled it from our in-app flow, bring Jagoan back to the foreground so they
     * don't have to navigate out of system settings manually.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AWAITING_NOTIF, false)) return
        prefs.edit().putBoolean(KEY_AWAITING_NOTIF, false).apply()
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            Log.d(TAG, "🔙 Notification access granted — returning to Jagoan")
        } catch (e: Exception) {
            Log.e(TAG, "Could not return to app: ${e.message}")
        }
    }

    /**
     * Clean up the overlay window if the service is torn down, to avoid leaking it.
     */
    override fun onDestroy() {
        super.onDestroy()
        overlayController.dismiss()
    }

    companion object {
        const val PREFS = "jagoan_prefs"
        const val KEY_AWAITING_NOTIF = "awaiting_notif_grant"
    }
}
