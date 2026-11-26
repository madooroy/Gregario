package com.madooroy.gregario.ui

import android.app.NotificationChannel
import android.app.Service
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.madooroy.gregario.R
import com.madooroy.gregario.Gregario
import com.madooroy.gregario.PanelPlacement
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the floating workout panel on screen over other apps (Peloton, Netflix, YouTube) as a foreground service,
 * so Android doesn't stop the ride when another app is in front.
 */
class PanelService : Service() {
    /** The panel window's lifecycle for Compose (the service itself is not a LifecycleOwner). */
    private val window = PanelWindowOwner()
    private var windowManager: WindowManager? = null
    private var panelView: ComposeView? = null
    private var placementJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        window.open()
        startInForeground()
        if (!canDrawOverlays(this)) {
            Timber.w("Gregario: no permission to draw over other apps")
            stopSelf(); return
        }
        runCatching { addPanel() }.onFailure { Timber.e(it, "Gregario: panel not added"); stopSelf(); return }
        mutableRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removePanel()
        window.close()
        mutableRunning.value = false
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workout panel", NotificationManager.IMPORTANCE_LOW))
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), immutable)
        val close = PendingIntent.getService(this, 1, Intent(this, PanelService::class.java).setAction(ACTION_CLOSE), immutable)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gregario)
            .setContentTitle("Gregario panel is open")
            .setContentText("Tap for settings")
            .setContentIntent(open)
            .addAction(0, "Close panel", close)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /** The panel: a window the rider drags anywhere by its handle; at rest it is anchored to its nearest edges. */
    private fun addPanel() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val gregario = Gregario.get(application)
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) LayoutParams.TYPE_APPLICATION_OVERLAY else LayoutParams.TYPE_PHONE
        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, type,
            LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        disableMoveAnimation(params)
        fun screen() = resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        fun place(p: PanelPlacement) {
            params.gravity = (if (p.bottom) Gravity.BOTTOM else Gravity.TOP) or (if (p.right) Gravity.END else Gravity.START)
            params.x = p.x; params.y = p.y
        }
        place(gregario.settings.placement.value)
        // While dragging it is placed by its top-left corner and follows the finger (screen pixels, from the handle).
        var dragFrom: Pair<Int, Int>? = null
        lateinit var view: ComposeView
        view = ComposeView(this).apply {
            window.own(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(window))
            setContent {
                GregarioPanel(gregario,
                    onDrag = { dx, dy ->
                        val (sw, sh) = screen()
                        val from = dragFrom ?: gregario.settings.placement.value.topLeft(view.width, view.height, sw, sh).also {
                            dragFrom = it
                            params.gravity = Gravity.TOP or Gravity.START
                        }
                        params.x = (from.first + dx.toInt()).coerceIn(0, maxOf(0, sw - view.width))
                        params.y = (from.second + dy.toInt()).coerceIn(0, maxOf(0, sh - view.height))
                        runCatching { wm.updateViewLayout(view, params) }
                    },
                    onDragEnd = {
                        if (dragFrom != null) {
                            val (sw, sh) = screen()
                            dragFrom = null
                            val p = PanelPlacement.dropped(params.x, params.y, view.width, view.height, sw, sh)
                            if (p == gregario.settings.placement.value) { place(p); runCatching { wm.updateViewLayout(view, params) } }
                            else gregario.settings.setPlacement(p)    // the collector below moves the window
                        }
                    })
            }
        }
        wm.addView(view, params)
        panelView = view
        placementJob = window.lifecycleScope.launch {
            gregario.settings.placement.collect { p ->
                if (dragFrom == null) { place(p); runCatching { wm.updateViewLayout(view, params) } }
            }
        }
        gregario.attach()
    }

    private fun removePanel() {
        // End any workout and hand the bike back to Grupetto before the panel goes.
        if (panelView != null) Gregario.get(application).detach()
        placementJob?.cancel(); placementJob = null
        panelView?.let { v -> windowManager?.let { runCatching { it.removeViewImmediate(v) } } }
        panelView = null
    }

    /** No slide animation when the panel moves: Android's hidden PRIVATE_FLAG_NO_MOVE_ANIMATION, set if the platform has it. */
    private fun disableMoveAnimation(params: LayoutParams) {
        runCatching {
            val c = LayoutParams::class.java
            val field = c.getField("privateFlags")
            field.setInt(params, field.getInt(params) or c.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION").getInt(params))
        }
    }

    companion object {
        private const val CHANNEL = "gregario_panel"
        private const val NOTIFICATION_ID = 4401
        private const val ACTION_CLOSE = "com.madooroy.gregario.CLOSE_PANEL"

        private val mutableRunning = MutableStateFlow(false)
        /** True while the panel is on screen. */
        val running: StateFlow<Boolean> = mutableRunning

        fun open(context: Context) = ContextCompat.startForegroundService(context, Intent(context, PanelService::class.java))
        fun close(context: Context) { context.stopService(Intent(context, PanelService::class.java)) }
    }
}
