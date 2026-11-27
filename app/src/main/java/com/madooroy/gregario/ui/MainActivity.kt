package com.madooroy.gregario.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madooroy.gregario.BuildConfig
import com.madooroy.gregario.Gregario
import com.madooroy.gregario.trainer.BikeChoice
import kotlinx.coroutines.delay

/**
 * Gregario's own screen: open or close the floating panel, see whether Grupetto's DirCon is reachable, and the
 * settings (riders, intervals.icu, panel, workout files, Strava).
 */
class MainActivity : ComponentActivity() {
    private var canDraw by mutableStateOf(false)
    /** Opened from the panel's "Type its address": put the cursor in the SmartSpin2K address box, keyboard up. */
    private var focusAddress by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TYPE_ADDRESS, false)) focusAddress = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_TYPE_ADDRESS, false)) focusAddress = true
        val gregario = Gregario.get(application)
        setContent {
            MaterialTheme(colors = darkColors(primary = Accent, background = Bg, surface = Bg)) {
                Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Fg) { Screen(gregario) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        canDraw = canDrawOverlays(this)
    }

    @Composable
    private fun Screen(gregario: Gregario) {
        val running by PanelService.running.collectAsState()
        var link by remember { mutableStateOf(gregario.linkStatus()) }
        LaunchedEffect(Unit) { while (true) { link = gregario.linkStatus(); delay(1_000) } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                Text("Gregario", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text((if (gregario.pelotonTablet) "Structured workouts on Peloton bikes, through Grupetto or a SmartSpin2K"
                      else "Structured workouts with a SmartSpin2K") + " · version ${BuildConfig.VERSION_NAME}",
                    fontSize = 13.sp, color = Dim)
                Spacer(Modifier.height(16.dp))

                // 1. Permission to float over other apps.
                if (!canDraw) {
                    Text("Gregario needs permission to show its workout panel over other apps.", fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Button({ askToDraw() }) { Text("Grant permission") }
                    Spacer(Modifier.height(16.dp))
                }

                // 2. The panel.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (running) OutlinedButton({ PanelService.close(this@MainActivity) }) { Text("Close panel") }
                    else Button({ openPanel() }, enabled = canDraw) { Text("Open panel") }
                    if (running) Button({ goHome() }) { Text("Ride (hide this screen)") }
                }
                // Open the panel when the tablet starts.
                val startOnBoot by gregario.settings.startOnBoot.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Start on boot", fontSize = 15.sp)
                    Switch(startOnBoot, { gregario.settings.setStartOnBoot(it) }, enabled = canDraw)
                }
                Spacer(Modifier.height(8.dp))

                // 3. The bike: Grupetto's DirCon on this tablet, or a SmartSpin2K's over Wi-Fi.
                LinkCard(gregario, link, running)
                Spacer(Modifier.height(20.dp))

                GregarioSettings(gregario)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable
    private fun LinkCard(gregario: Gregario, link: String, running: Boolean) {
        val choice by gregario.settings.bike.collectAsState()
        val state by gregario.state.collectAsState()
        // A workout on its Ready screen (nothing ridden yet) can still change bikes or address: it is set up again.
        val riding = state.workout != null && !gregario.canSwitchBike()
        val addressFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(focusAddress, choice.kind) {
            if (focusAddress && choice.kind == BikeChoice.Kind.SS2K) {
                delay(300)   // the box must be laid out first
                runCatching { addressFocus.requestFocus(); keyboard?.show() }
                focusAddress = false
            }
        }
        val ss2k = choice.kind == BikeChoice.Kind.SS2K
        var address by remember(choice) { mutableStateOf(choice.ss2kHost) }
        Column(Modifier.fillMaxWidth().background(Card, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(14.dp)) {
            Text("Bike connection", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(if (running) link else "Opens with the panel", fontSize = 14.sp,
                color = if (link.startsWith("Connected") || !running) Fg else Note)
            Spacer(Modifier.height(8.dp))
            // Which bike: switched only between workouts (a ride never changes bikes halfway).
            // Off a Peloton only the SmartSpin2K works: no choice to show.
            if (gregario.pelotonTablet) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in BikeChoice.Kind.values()) {
                    val pick = { gregario.setBike(choice.copy(kind = k, ss2kHost = address)); Unit }
                    if (k == choice.kind) Button(pick, enabled = !riding) { Text(k.label) }
                    else OutlinedButton(pick, enabled = !riding) { Text(k.label) }
                }
            }
            if (ss2k) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material.OutlinedTextField(address, { address = it.trim().take(64) },
                        Modifier.widthIn(max = 260.dp).focusRequester(addressFocus), label = { Text("Address (only if not found)") }, singleLine = true,
                        isError = address.isNotEmpty() && !BikeChoice.validHost(address))
                    OutlinedButton({ gregario.setBike(choice.copy(ss2kHost = address)) },
                        enabled = !riding && BikeChoice.validHost(address) && address != choice.ss2kHost) { Text("Save") }
                }
            }
            if (riding) Text("Finish or end the workout to change bikes.", fontSize = 13.sp, color = Note)
            Spacer(Modifier.height(6.dp))
            Text(if (!ss2k) "Gregario talks to Grupetto over DirCon on this tablet, the way Zwift does; Grupetto holds the watts. " +
                    "In Grupetto: switch on DirCon (and Bike+ ERG on a Bike+), and keep Grupetto running. Disconnect Zwift " +
                    "or any other app controlling the bike first. On a Peloton Bike (no motor), Gregario shows each target " +
                    "for you to follow with the knob; for ERG there, add a SmartSpin2K."
                 else if (!gregario.pelotonTablet) "Gregario talks to the SmartSpin2K over DirCon on the Wi-Fi; SS2K holds the " +
                    "watts by turning the knob. It is found by itself once it is on and on the same Wi-Fi as this tablet; type its " +
                    "IP address only if it isn't. The SmartSpin2K needs a power source: pair your power meter (or the bike's " +
                    "Bluetooth power) in the SmartSpin2K's own web page. Disconnect Zwift or any other app controlling it first."
                 else "Gregario talks to the SmartSpin2K over DirCon on the Wi-Fi; SS2K holds the watts by turning the knob. " +
                    "It is found by itself once it is on and on the same Wi-Fi; type its IP address only if it isn't. " +
                    "Keep Grupetto running with its Bluetooth power broadcast (SS2K reads its power from Grupetto); Gregario " +
                    "sends Grupetto nothing in this mode, so a Bike+'s motor never fights the SS2K.", fontSize = 13.sp, color = Dim)
        }
    }

    /**
     * Hide this screen and go to the tablet's home: on a Peloton that's the Peloton app, elsewhere the launcher.
     * (moveTaskToBack showed whatever was open before, such as Settings' App info.)
     */
    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(home) }.isFailure) moveTaskToBack(true)
    }

    private fun openPanel() {
        if (!canDrawOverlays(this)) { askToDraw(); return }
        PanelService.open(this)
    }

    private fun askToDraw() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }


    companion object {
        private val Bg = Color(0xFF11161D)
        private val Card = Color(0xFF1B2430)
        private val Fg = Color(0xFFE8EEF5)
        private val Dim = Color(0xFF9EAEC0)
        private val Note = Color(0xFFF5C542)
        private val Accent = Color(0xFF4FC3F7)
        private const val EXTRA_TYPE_ADDRESS = "typeAddress"

        /** Gregario's screen with the cursor in the SmartSpin2K address box (the panel's "Type its address"). */
        fun typeAddress(context: android.content.Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TYPE_ADDRESS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
}
