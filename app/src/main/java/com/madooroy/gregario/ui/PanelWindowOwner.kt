package com.madooroy.gregario.ui

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * What Compose needs around a window that has no Activity: a lifecycle and a saved-state registry. The panel service
 * holds one, [open]s it when the panel goes up, [own]s the panel's view, and [close]s it when the panel comes down.
 * Nothing is restored between panels: the panel's state lives in [com.madooroy.gregario.Gregario].
 */
class PanelWindowOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /** Up and running: Compose content starts as soon as the view is attached. */
    fun open() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** Give the view (the panel's root) this lifecycle and registry. */
    fun own(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    /** Down for good: Compose disposes its content, and coroutines in its lifecycle scope end. */
    fun close() {
        if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** Allowed to draw the panel over other apps? Android 5 (API 21–22) grants it at install and has no such check (review). */
internal fun canDrawOverlays(context: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)
