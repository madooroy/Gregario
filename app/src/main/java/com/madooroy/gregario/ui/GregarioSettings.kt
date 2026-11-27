package com.madooroy.gregario.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madooroy.gregario.Gregario

/** Gregario settings (the app's main screen, below the panel button): riders, intervals.icu, panel, workout files, Strava. */
@Composable
fun ColumnScope.GregarioSettings(gregario: Gregario) {
    val saved by gregario.settings.apiKey.collectAsState()
    val place by gregario.settings.placement.collectAsState()
    val state by gregario.state.collectAsState()
    val riders by gregario.settings.riders.collectAsState()
    val rider by gregario.settings.rider.collectAsState()
    var key by remember(rider) { mutableStateOf(saved) }
    run {
        RidersSection(gregario, riders, rider, riding = state.workout != null)
        if (riders.size > 1) Text("Settings for ${gregario.settings.riderName}", fontSize = 16.sp)
        val token by gregario.settings.icuToken.collectAsState()
        val athlete by gregario.settings.icuAthlete.collectAsState()
        val context = androidx.compose.ui.platform.LocalContext.current
        Text("intervals.icu", fontSize = 16.sp)
        if (token.isNotBlank()) {
            Text("Connected as $athlete. Today's workouts and your FTP come from intervals.icu, and rides go back there.",
                fontSize = 14.sp, color = Color(0xFF9EAEC0))
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ gregario.disconnectIcu() }) { Text("Disconnect intervals.icu") }
        } else {
            Text("Workouts come from your intervals.icu calendar, and rides go back there. Tap Connect, sign in to " +
                "intervals.icu and approve Gregario. Gregario never sees your password.", fontSize = 14.sp, color = Color(0xFF9EAEC0))
            Spacer(Modifier.height(8.dp))
            var showKey by remember(rider) { mutableStateOf(saved.isNotBlank()) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ runCatching { context.startActivity(android.content.Intent(context, IcuSignInActivity::class.java)) } }) {
                    Text("Connect intervals.icu")
                }
                if (!showKey) TextButton({ showKey = true }) { Text("Use an API key instead") }
            }
            if (showKey) {
                Text("API key: intervals.icu › Settings › Developer Settings. It stays on this tablet, in Gregario's private storage.",
                    fontSize = 13.sp, color = Color(0xFF9EAEC0))
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("intervals.icu API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
                Spacer(Modifier.height(6.dp))
                Button({ gregario.settings.setApiKey(key); gregario.refresh() }) { Text("Save & test") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Drag the panel by its handle to put it anywhere; this button steps through the four corners.
            OutlinedButton({ gregario.settings.setPlacement(com.madooroy.gregario.PanelPlacement.nextCorner(place)) }) { Text("Panel: ${place.label()}") }
            val size by gregario.settings.panelScale.collectAsState()
            OutlinedButton({ gregario.settings.setPanelScale(PANEL_SIZES[(PANEL_SIZES.indexOf(size).coerceAtLeast(0) + 1) % PANEL_SIZES.size]) }) {
                Text("Panel size: $size%")
            }
            val opacity by gregario.settings.panelOpacity.collectAsState()
            OutlinedButton({ gregario.settings.setPanelOpacity(PANEL_OPACITY[(PANEL_OPACITY.indexOf(opacity).coerceAtLeast(0) + 1) % PANEL_OPACITY.size]) }) {
                Text("Opacity: $opacity%")
            }
        }
        // Its own row: four buttons in one ran off the right edge of a narrower screen (Opacity was hidden).
        Spacer(Modifier.height(6.dp))
        val lead by gregario.settings.climbLead.collectAsState()
        OutlinedButton({ gregario.settings.setClimbLead((lead + 1) % 3) }) {
            Text(when (lead) { 0 -> "Climb lead: off"; 1 -> "Climb lead: 1 s (on \"1\")"; else -> "Climb lead: 2 s" })
        }
        // Without intervals.icu: workout files (Open with Gregario) are ridden at this FTP.
        if (saved.isBlank() && token.isBlank()) {
            Spacer(Modifier.height(10.dp))
            val manual by gregario.settings.manualFtp.collectAsState()
            var ftpText by remember(rider) { mutableStateOf(if (manual > 0) manual.toString() else "") }
            Text("No intervals.icu? Open a .zwo, .mrc or .erg file with Gregario (from email, Drive or downloads) " +
                "and ride it at this FTP.", fontSize = 13.sp, color = Color(0xFF9EAEC0))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(ftpText, { ftpText = it.filter(Char::isDigit).take(3) }, Modifier.width(140.dp),
                    label = { Text("FTP (W)") }, singleLine = true)
                val w = ftpText.toIntOrNull()
                Button({ gregario.settings.setManualFtp(w!!); gregario.refresh() }, enabled = w != null && w in Gregario.FTP_RANGE) { Text("Save FTP") }
            }
        }
        AddWorkoutsSection(gregario, state.dropAddress)
        Spacer(Modifier.height(10.dp))
        val status = when {
            state.loading -> "Checking intervals.icu…"
            state.message != null -> state.message!!
            (saved.isNotBlank() || token.isNotBlank()) && state.ftp > 0 -> "Connected · FTP ${state.ftp} W · ${state.today.size} workout(s) today"
            else -> ""
        }
        Text(status, fontSize = 14.sp)
        state.uploadStatus?.let { Text("Last ride: $it", fontSize = 13.sp, color = Color(0xFF9EAEC0)) }

        // Strava: the rider's own Strava API application, connected once.
        Spacer(Modifier.height(16.dp))
        Text("Strava", fontSize = 16.sp)
        LaunchedEffect(rider) { gregario.checkStrava() }
        Text(state.strava ?: "", fontSize = 14.sp)
        if (!gregario.stravaAccount.configured) {
            var id by remember(rider) { mutableStateOf(gregario.settings.clientId) }
            var secret by remember(rider) { mutableStateOf(gregario.settings.clientSecret) }
            var pasted by remember(rider) { mutableStateOf("") }
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            Text("1. From strava.com/settings/api (create an application there once): Client ID and Client Secret. " +
                "2. Copy the link and open it on the laptop, press Authorize. 3. The page that fails to load: copy its " +
                "address (localhost/…?code=…) and paste it below, or type it if the laptop and tablet do not share a clipboard.",
                fontSize = 13.sp, color = Color(0xFF9EAEC0))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(id, { id = it }, Modifier.weight(1f), label = { Text("Client ID") }, singleLine = true)
                OutlinedTextField(secret, { secret = it }, Modifier.weight(1f), label = { Text("Client Secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ clipboard.setText(androidx.compose.ui.text.AnnotatedString(gregario.stravaAccount.authorizeUrl(id.trim()))) },
                    enabled = id.isNotBlank()) { Text("Copy authorize link") }
            }
            OutlinedTextField(pasted, { pasted = it }, Modifier.fillMaxWidth(), label = { Text("Paste the localhost address") }, singleLine = true)
            Button({ gregario.connectStrava(id, secret, pasted) }, enabled = id.isNotBlank() && secret.isNotBlank() && pasted.isNotBlank()) {
                Text("Connect Strava")
            }
        } else {
            OutlinedButton({ gregario.forgetStrava() }) { Text("Disconnect Strava") }
        }
    }
}

/**
 * Riders sharing the bike: pick whose settings to edit, rename, add, remove. Picking one here also makes
 * them the rider (as the panel's "Who's riding?" does). Locked mid-workout so a ride can't change hands.
 */
@Composable
private fun RidersSection(gregario: Gregario, riders: List<com.madooroy.gregario.Rider>, rider: String, riding: Boolean) {
    Text("Riders", fontSize = 16.sp)
    Text("Each rider has their own intervals.icu sign-in, FTP, Strava and workout files. With two or more, the panel asks " +
        "who's riding once a day.", fontSize = 13.sp, color = Color(0xFF9EAEC0))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in riders) {
            if (r.id == rider) Button({}, enabled = !riding) { Text(r.name) }
            else OutlinedButton({ gregario.switchRider(r.id) }, enabled = !riding) { Text(r.name) }
        }
    }
    var name by remember(rider, riders) { mutableStateOf(riders.firstOrNull { it.id == rider }?.name.orEmpty()) }
    var newName by remember { mutableStateOf("") }
    var confirmRemove by remember(rider) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(name, { name = it.take(24) }, Modifier.width(200.dp), label = { Text("Name") }, singleLine = true)
        OutlinedButton({ gregario.settings.renameRider(rider, name) }, enabled = name.isNotBlank()) { Text("Rename") }
        if (rider != com.madooroy.gregario.Riders.FIRST) OutlinedButton({
            if (confirmRemove) { gregario.removeRider(rider); confirmRemove = false } else confirmRemove = true
        }, enabled = !riding) { Text(if (confirmRemove) "Tap again: remove with their rides" else "Remove rider") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(newName, { newName = it.take(24) }, Modifier.width(200.dp), label = { Text("New rider's name") }, singleLine = true)
        OutlinedButton({ gregario.settings.addRider(newName); newName = "" }, enabled = newName.isNotBlank() && riders.size < 8) { Text("Add rider") }
    }
    Spacer(Modifier.height(16.dp))
}

/** Panel sizes to cycle through (100% = 280 x 640 px, about an iPhone on the Bike+ screen). */
private val PANEL_SIZES = listOf(100, 115, 125, 135, 150)
private val PANEL_OPACITY = listOf(100, 85, 70, 55)

/**
 * Getting workout files onto the tablet without intervals.icu: Android's own file picker (Downloads, a USB stick),
 * or "Send to the bike" from a phone or computer on the same Wi-Fi.
 */
@Composable
private fun AddWorkoutsSection(gregario: Gregario, dropAddress: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        status = "Adding…"
        kotlin.concurrent.thread(name = "gregario-pick") {
            val msgs = uris.map { uri ->
                runCatching {
                    val name = runCatching {
                        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment ?: "workout"
                    val text = resolver.openInputStream(uri)!!.use { com.madooroy.gregario.workout.readWorkoutText(it) }
                    gregario.importWorkout(name, text)
                }.getOrElse { "Couldn't add that file: ${it.message}" }
            }
            status = msgs.joinToString("\n")
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("Add workout files", fontSize = 16.sp)
    Text("For .zwo, .mrc and .erg files. They show on the panel under \"Your workouts\".", fontSize = 13.sp, color = Color(0xFF9EAEC0))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedButton({ runCatching { pick.launch(arrayOf("*/*")) }.onFailure { status = "This tablet has no file picker: use Send to the bike" } }) {
            Text("Choose files…")
        }
        val on by gregario.settings.dropEnabled.collectAsState()
        OutlinedButton({ gregario.settings.setDropEnabled(!on); gregario.updateDrop() }) { Text(if (on) "Send to the bike: on" else "Send to the bike: off") }
    }
    status?.let { Text(it, fontSize = 13.sp, color = Color(0xFF9EAEC0)) }
    Text(dropAddress?.let { "From a phone or computer on the same Wi-Fi, open $it while the panel shows today's workouts." }
        ?: "Send to the bike: a page for adding files from a phone on the same Wi-Fi. Its address shows here and on the " +
            "panel's workout list while the panel is open.", fontSize = 13.sp, color = Color(0xFF9EAEC0))
}
