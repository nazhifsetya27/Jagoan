package com.nazhif.jagoan.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nazhif.jagoan.ui.theme.JagoanAndroidTheme
import kotlin.math.hypot

/**
 * Owns the floating overlay: a full editing **card** window and a small draggable **bubble**
 * window (chat-head style). Only one is attached at a time.
 *
 * - Card (default): focusable (IME input), dims + blurs the apps behind for focus.
 * - Bubble: tiny, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`, no dim/blur so the background
 *   stays fully readable (e.g. while showing a QRIS receipt to a merchant). Drag it anywhere;
 *   tap it to expand back to the card.
 *
 * Input state (`purpose`, `selectedKey`) is hoisted here on the controller, so it survives
 * card ⇄ bubble toggles without re-typing.
 *
 * All public methods hop to the main thread; all WindowManager work stays on the main thread.
 */
class OverlayController(
    private val context: Context,
    private val onConfirm: (
        transactionId: String,
        name: String,
        categoryKey: String?,
        onResult: (success: Boolean, errorMessage: String?) -> Unit,
    ) -> Unit,
    private val onDiscard: (transactionId: String) -> Unit,
) {
    private val tag = "JagoanOverlay"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var currentTransactionId: String? = null
    private var currentTransaction: OverlayTransaction? = null

    // Compose-observable UI state; recomposes the overlay as the save round-trip progresses.
    private var uiState by mutableStateOf<OverlayUiState>(OverlayUiState.Editing)

    // Hoisted input state lives on the controller (NOT inside the composable) so it survives
    // minimize/maximize without re-creating the window. Reset on each new transaction.
    private var purpose by mutableStateOf("")
    private var selectedKey by mutableStateOf<String?>(null)
    private var minimized by mutableStateOf(false)

    // Bubble position within the current transaction; reset to the default on each show.
    private var bubblePosX = 0
    private var bubblePosY = 0

    // Safety net: auto-dismiss an ignored overlay so it never holds focus forever.
    private val autoDismiss = Runnable { dismissInternal(discard = true) }

    /** Show the overlay for [transaction]. Safe to call from any thread. No-op if one is up. */
    fun show(transaction: OverlayTransaction) {
        mainHandler.post { showInternal(transaction) }
    }

    /** Remove the overlay if present. Safe to call from any thread. */
    fun dismiss() {
        mainHandler.post { dismissInternal(discard = false) }
    }

    private fun showInternal(transaction: OverlayTransaction) {
        // Debounce: ignore if either window is already attached (mirrors the service's
        // amount-debounce). Avoids stacked windows / flicker.
        if (overlayView != null || bubbleView != null) {
            Log.d(tag, "Overlay already showing, ignoring new request")
            return
        }

        uiState = OverlayUiState.Editing
        purpose = ""
        selectedKey = transaction.categories.firstOrNull()?.key
        minimized = false
        currentTransactionId = transaction.transactionId
        currentTransaction = transaction
        bubblePosX = 0
        bubblePosY = 0

        showCard(transaction)
    }

    // ---------------------------------------------------------------- card window ----

    private fun showCard(transaction: OverlayTransaction) {
        if (overlayView != null) return
        mainHandler.removeCallbacks(autoDismiss)

        val owner = OverlayLifecycleOwner().apply { onCreate() }
        lifecycleOwner = owner

        val composeView = ComposeView(context).apply {
            // Tie the composition to the view-tree lifecycle (owner set on the root below).
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                JagoanAndroidTheme {
                    OverlayContent(
                        transaction = transaction,
                        uiState = uiState,
                        purpose = purpose,
                        onPurposeChange = { purpose = it },
                        selectedKey = selectedKey,
                        onSelectCategory = { selectedKey = it },
                        onMinimizeToggle = { toggleMinimize() },
                        onSave = { name, categoryKey -> handleSave(name, categoryKey) },
                        onCancel = { handleCancel() },
                    )
                }
            }
        }

        // Root frame that intercepts the Back key (a focusable window receives it) so the
        // user is never trapped — Back == Cancel.
        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP
                ) {
                    handleCancel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        // Explicit WRAP_CONTENT height so the window sizes to the card (not full-screen
        // / collapsed). Without this the ComposeView defaults to MATCH_PARENT and the
        // overlay can render invisibly.
        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // Drive the lifecycle to RESUMED and expose the owners on the root (the view
        // actually added to the window) BEFORE attach, so Compose finds them.
        owner.onResume()
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // minSdk = 30, so TYPE_APPLICATION_OVERLAY is always available.
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (NO FLAG_NOT_FOCUSABLE) so the purpose TextField gets IME input;
            // dim the apps behind for focus.
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            dimAmount = 0.55f
            // Resize so the soft keyboard pushes the sheet up instead of covering the field.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            // Glass: blur the content behind the floating card on Android 12+ (best-effort;
            // silently ignored on older versions or where cross-window blur is disabled).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = 80
            }
        }
        overlayParams = params

        try {
            windowManager.addView(root, params)
            overlayView = root
            mainHandler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
            Log.d(tag, "Overlay card shown for ${transaction.transactionId}")
        } catch (e: Exception) {
            // BadTokenException (permission revoked at runtime), IllegalState, etc.
            Log.e(tag, "Failed to add overlay view: ${e.message}", e)
            lifecycleOwner?.onDestroy()
            lifecycleOwner = null
            overlayView = null
            overlayParams = null
            currentTransactionId = null
            currentTransaction = null
        }
    }

    private fun hideCard() {
        val view = overlayView ?: return
        try {
            if (view.isAttachedToWindow) windowManager.removeView(view)
        } catch (e: Exception) {
            Log.e(tag, "Failed to remove card view: ${e.message}", e)
        }
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        overlayView = null
        overlayParams = null
    }

    // --------------------------------------------------------------- bubble window ----

    private fun showBubble() {
        if (bubbleView != null) return
        val density = context.resources.displayMetrics.density
        val sizePx = (BUBBLE_SIZE_DP * density).toInt()

        if (bubblePosX == 0 && bubblePosY == 0) {
            val dm = context.resources.displayMetrics
            bubblePosX = dm.widthPixels - sizePx - (12 * density).toInt()
            bubblePosY = (dm.heightPixels * 0.4f).toInt()
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable + not touch-modal: taps elsewhere fall through to the app, and the
            // bubble never grabs focus. No DIM/BLUR so the background stays readable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubblePosX
            y = bubblePosY
        }

        val bubbleRoot = FrameLayout(context)
        val emoji = TextView(context).apply {
            text = "💰"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
        }
        bubbleRoot.addView(
            emoji,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColors(intArrayOf(0xFF4F46E5.toInt(), 0xFF818CF8.toInt()))
        }
        bubbleRoot.background = bg
        attachBubbleDrag(bubbleRoot)

        try {
            windowManager.addView(bubbleRoot, params)
            bubbleView = bubbleRoot
            bubbleParams = params
            Log.d(tag, "Bubble shown")
        } catch (e: Exception) {
            Log.e(tag, "Failed to add bubble view: ${e.message}", e)
            bubbleView = null
            bubbleParams = null
        }
    }

    private fun hideBubble() {
        val b = bubbleView ?: return
        try {
            if (b.isAttachedToWindow) windowManager.removeView(b)
        } catch (e: Exception) {
            Log.e(tag, "Failed to remove bubble view: ${e.message}", e)
        }
        bubbleView = null
        bubbleParams = null
    }

    /** Drag the bubble around; a tap (no significant move) expands back to the card. */
    private fun attachBubbleDrag(root: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        root.setOnTouchListener { v, e ->
            val params = bubbleParams ?: return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (hypot(dx, dy) > touchSlop) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        bubblePosX = params.x
                        bubblePosY = params.y
                        try {
                            windowManager.updateViewLayout(v, params)
                        } catch (ex: Exception) {
                            Log.e(tag, "Failed to move bubble: ${ex.message}", ex)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMinimize() // tap = expand back to the card
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    // --------------------------------------------------------------- actions ----

    private fun handleSave(name: String, categoryKey: String?) {
        val txnId = currentTransactionId ?: return
        uiState = OverlayUiState.Saving
        onConfirm(txnId, name, categoryKey) { success, errorMessage ->
            // Delivered on the main thread by the caller.
            if (success) {
                uiState = OverlayUiState.Success
                mainHandler.postDelayed({ dismissInternal(discard = false) }, SUCCESS_DISMISS_MS)
            } else {
                uiState = OverlayUiState.Error(errorMessage ?: "Gagal menyimpan. Coba lagi.")
            }
        }
    }

    private fun handleCancel() {
        dismissInternal(discard = true)
    }

    /** Swap between the editing card and the draggable bubble. Safe from any thread. */
    fun toggleMinimize() {
        mainHandler.post {
            // Never minimize away the success state; it auto-closes in a moment.
            if (uiState is OverlayUiState.Success) return@post
            minimized = !minimized
            if (minimized) {
                hideCard()
                showBubble()
            } else {
                hideBubble()
                currentTransaction?.let { showCard(it) }
            }
        }
    }

    private fun dismissInternal(discard: Boolean) {
        mainHandler.removeCallbacks(autoDismiss)

        if (discard) {
            currentTransactionId?.let { onDiscard(it) }
        }

        hideCard()
        hideBubble()
        currentTransactionId = null
        currentTransaction = null
        uiState = OverlayUiState.Editing
    }

    private companion object {
        const val AUTO_DISMISS_MS = 120_000L // 2 minutes
        const val SUCCESS_DISMISS_MS = 900L
        const val BUBBLE_SIZE_DP = 62
    }
}
