package com.nazhif.jagoan.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Minimal owner that lets a Jetpack Compose [androidx.compose.ui.platform.ComposeView]
 * live inside a WindowManager-added view (i.e. outside an Activity).
 *
 * A ComposeView attached to a window with no ViewTree owners crashes at attach with
 * "ViewTreeLifecycleOwner not found" / saved-state errors. We supply all three owners
 * (Lifecycle, ViewModelStore, SavedStateRegistry) ourselves and drive the lifecycle by hand.
 *
 * Usage order matters:
 *  1. [onCreate] BEFORE attaching the view (performRestore must precede CREATED).
 *  2. [onResume] at/after attach so recomposition runs.
 *  3. [onDestroy] when the overlay is removed, to release the ViewModelStore.
 */
class OverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun onCreate() {
        // Restore must happen while the registry is still INITIALIZED, before CREATED.
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
