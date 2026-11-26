package com.madooroy.gregario.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.madooroy.gregario.AppSettings
import timber.log.Timber

/** What to do when the tablet has started, given "Start on boot" and the "draw over other apps" permission. */
enum class BootStart {
    /** Start on boot is off (the default): nothing. */
    Off,
    /** On, but the permission is missing (only the main screen can ask for it): nothing, and say why in the log. */
    NoPermission,
    /** Open the panel; it finds Grupetto's DirCon or the SmartSpin2K by itself as they come up. */
    OpenPanel;

    companion object {
        const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"   // Intent.ACTION_BOOT_COMPLETED
        fun decide(action: String?, startOnBoot: Boolean, canDrawOverlays: Boolean): BootStart? = when {
            action != BOOT_COMPLETED -> null
            !startOnBoot -> Off
            !canDrawOverlays -> NoPermission
            else -> OpenPanel
        }
    }
}

/** Opens the workout panel when the tablet has started, if the rider turned "Start on boot" on in the main screen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (BootStart.decide(intent.action, AppSettings.startOnBootSet(context),
                canDrawOverlays(context))) {
            BootStart.OpenPanel -> runCatching { PanelService.open(context) }
                .onSuccess { Timber.i("Gregario boot: panel opened") }
                .onFailure { Timber.w(it, "Gregario boot: panel not opened") }
            BootStart.NoPermission -> Timber.w("Gregario boot: no permission to draw over other apps, panel not opened")
            BootStart.Off, null -> Unit
        }
    }
}
