package com.nazhif.jagoan.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nazhif.jagoan.ui.theme.JagoanAndroidTheme

/**
 * Owns the floating overlay window: shows a Compose UI inside a WindowManager view,
 * keeps all WindowManager work on the main thread, and guards against leaks/double-shows.
 *
 * The overlay is triggered from the listener service's network callback (an OkHttp IO
 * thread), so every public method hops to the main thread internally.
 *
 * @param context a Context able to obtain WindowManager (the listener Service works)
 * @param onConfirm performs the server confirm POST; must deliver its result on the main thread
 * @param onDiscard performs the fire-and-forget discard POST
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
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var currentTransactionId: String? = null

    // Compose-observable UI state; recomposes the overlay as the save round-trip progresses.
    private var uiState by mutableStateOf<OverlayUiState>(OverlayUiState.Editing)

    // Safety net: auto-dismiss an ignored overlay so it never holds focus forever.
    private val autoDismiss = Runnable { dismissInternal(discard = true) }

    /** Show the overlay for [transaction]. Safe to call from any thread. No-op if one is already up. */
    fun show(transaction: OverlayTransaction) {
        mainHandler.post { showInternal(transaction) }
    }

    /** Remove the overlay if present. Safe to call from any thread. */
    fun dismiss() {
        mainHandler.post { dismissInternal(discard = false) }
    }

    private fun showInternal(transaction: OverlayTransaction) {
        // Debounce: ignore if an overlay is already attached (mirrors the service's
        // amount-debounce). Avoids stacked windows / flicker.
        if (overlayView != null) {
            Log.d(tag, "Overlay already showing, ignoring new request")
            return
        }

        uiState = OverlayUiState.Editing
        currentTransactionId = transaction.transactionId

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

        try {
            windowManager.addView(root, params)
            overlayView = root
            mainHandler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
            Log.d(tag, "Overlay shown for ${transaction.transactionId}")
            root.post {
                Log.d(
                    tag,
                    "Overlay laid out: ${root.width}x${root.height}, children=${root.childCount}"
                )
            }
        } catch (e: Exception) {
            // BadTokenException (permission revoked at runtime), IllegalState, etc.
            Log.e(tag, "Failed to add overlay view: ${e.message}", e)
            lifecycleOwner?.onDestroy()
            lifecycleOwner = null
            overlayView = null
            currentTransactionId = null
        }
    }

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

    private fun dismissInternal(discard: Boolean) {
        mainHandler.removeCallbacks(autoDismiss)

        if (discard) {
            currentTransactionId?.let { onDiscard(it) }
        }

        val view = overlayView
        if (view != null) {
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to remove overlay view: ${e.message}", e)
            }
        }

        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        overlayView = null
        currentTransactionId = null
        uiState = OverlayUiState.Editing
    }

    private companion object {
        const val AUTO_DISMISS_MS = 120_000L // 2 minutes
        const val SUCCESS_DISMISS_MS = 900L
    }
}
