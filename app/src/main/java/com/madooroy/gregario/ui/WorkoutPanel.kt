package com.madooroy.gregario.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madooroy.gregario.Gregario
import com.madooroy.gregario.PanelState
import com.madooroy.gregario.workout.FtpTest
import com.madooroy.gregario.workout.Step
import com.madooroy.gregario.workout.TestResult
import com.madooroy.gregario.workout.Workout
import com.madooroy.gregario.workout.WorkoutPlayer
import com.madooroy.gregario.workout.Zone
import com.madooroy.gregario.workout.watts
import kotlin.math.roundToInt

// Gregario's colours (from its icon): deep navy, gold for the target, light blue for power.
// Surfaces fade with the Opacity setting (100/85/70/55 %); text, numbers, chart lines and borders stay solid.
// A state, so every part of the panel that draws a surface redraws when the setting changes.
private var surface by mutableStateOf(1f)
private val Bg: Color get() = Color(0xFF1B2430).copy(alpha = surface)
private val Card: Color get() = Color(0xFF11161D).copy(alpha = surface)
private val Line = Color(0xFF2A3646)
private val Fg = Color(0xFFE8EEF5)
private val Dim = Color(0xFF9EAEC0)
private val NoteBg: Color get() = Color(0xFF243042).copy(alpha = surface)
private val NoteFg = Color(0xFFCFE0FF)
private val AlertBg: Color get() = Color(0xFF3A1E24).copy(alpha = surface)
private val AlertFg = Color(0xFFFFC9D2)
private val Accent = Color(0xFFF5C542)   // gold: the target
private val Blue = Color(0xFF4FC3F7)     // power, and the interval ring
private val InRange = Color(0xFF5FD17E)  // green: live power inside a range step
private val LostAmber = Color(0xFFFFA000) // the bike link is down (thicker than the 3-2-1 border, with a message)

private fun zoneColor(pct: Double) = if (pct.isNaN()) Dim else Color(Zone.of(pct).argb)
private fun mmss(s: Int) = "${s / 60}:${"%02d".format(s % 60)}"

/**
 * The Gregario panel, sized in screen pixels (280 wide; mockup "Gregario workout panel").
 * [onDrag] gets the drag on the handle so far (screen pixels from where the finger went down), so the window can
 * follow the finger; [onDragEnd] lets it settle by the nearest edges.
 */
@Composable
fun GregarioPanel(gregario: Gregario, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) {
    val state by gregario.state.collectAsState()
    val compact by gregario.settings.compact.collectAsState()
    // 1 dp = 1 screen pixel inside the panel, so sizes match the mockup on the Bike+ whatever its density.
    // The panel is laid out in Bike+ pixels (280 x 640 at 100%) and scaled as a whole by the Panel size setting.
    val scale by gregario.settings.panelScale.collectAsState()
    val opacity by gregario.settings.panelOpacity.collectAsState()
    surface = opacity.coerceIn(40, 100) / 100f
    // See-through panel: a faint dark edge keeps text readable over Peloton's screen.
    val textStyle = if (opacity < 100) androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(
        Color.Black.copy(alpha = if (opacity <= 70) .8f else .5f), Offset(0f, 1f), 3f)) else androidx.compose.ui.text.TextStyle.Default
    CompositionLocalProvider(LocalDensity provides Density(scale / 100f, scale / 100f),
        androidx.compose.material.LocalTextStyle provides textStyle) {
        val riding = state.workout != null && state.snap != null && state.snap!!.state != WorkoutPlayer.State.Finished
        // In the 3-2-1 before a step change the whole border flashes in the next step's zone colour.
        val change = state.snap?.changeIn
        // Lost the bike: a thicker amber border, on and off each second, with the message at the top. It wins over the
        // 3-2-1 (no target reaches the bike anyway until it's back).
        val lost = riding && state.linkLost != null
        // Its own 1 s timer: a paused ride doesn't redraw the panel each second.
        var blink by remember { mutableStateOf(true) }
        LaunchedEffect(lost) { blink = true; while (lost) { kotlinx.coroutines.delay(1000); blink = !blink } }
        val edge = when {
            lost -> if (blink) LostAmber else Line
            change != null && change % 2 == 1 && state.ftp > 0 -> zoneColor((state.snap?.nextWatts ?: 0) * 100.0 / state.ftp)
            else -> Line
        }
        Column(
            Modifier.width(280.dp).background(Bg, RoundedCornerShape(if (compact && riding) 18.dp else 22.dp))
                .border(if (edge == Line) 1.dp else if (lost) 6.dp else 4.dp, edge, RoundedCornerShape(if (compact && riding) 18.dp else 22.dp))
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact && riding) 6.dp else 10.dp)
        ) {
            // When the ride started and when it will be done. "End" follows the time left, so a pause pushes it on.
            val h24 = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
            // Several riders: the full panel names the rider under the title; the mini panel has no title, so its clock
            // line carries the name instead of the words Start and End (with both it would be too long for the panel).
            val rider = if (gregario.settings.riders.value.size > 1 && compact) gregario.settings.riderName else null
            val clock = if (riding) clockLine(state.rideStartMs, state.snap!!.totalRemaining, h24, rider) else null
            Handle(compact, { gregario.settings.setCompact(!compact) }, onDrag, onDragEnd, clock)
            when {
                state.workout == null && compact -> Text(
                    (if (gregario.settings.riders.value.size > 1) (if (gregario.needsRider()) "Who's riding? Tap to expand · " else "${gregario.settings.riderName} · ") else "") +
                    (state.bikeHelp?.let { "${it.title} · tap to expand" } ?: state.today.firstOrNull()?.let { "Today: ${it.name} · ${it.workout.totalSeconds / 60} min" } ?: (state.message ?: "Gregario")),
                    fontSize = 12.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.workout == null -> TodayList(gregario, state)
                state.snap?.state == WorkoutPlayer.State.Finished -> Finished(gregario, state)
                compact -> Compact(gregario, state)
                else -> Full(gregario, state)
            }
        }
    }
}

/** Finger tracking for the handle, in screen pixels: the window moves under the finger, so local positions won't do. */
private class HandleDrag { var downX = 0f; var downY = 0f; var dragging = false }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Handle(compact: Boolean, toggle: () -> Unit, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit, clock: String? = null) {
    val d = remember { HandleDrag() }
    // "tap to shrink · drag to move": always when no ride is running (the clock line takes its place when one starts, so
    // the handle keeps its height); during a ride only for a few seconds after the panel changes size. The gray bar stays.
    // The hint shows before the ride starts only: flashing it after each resize made the panel jump (Nov 25 ride).
    val hint = clock == null
    Column(
        Modifier.fillMaxWidth().height(24.dp + (if (hint) 16.dp else 0.dp) + (if (clock != null) 16.dp else 0.dp))
            .pointerInteropFilter { ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { d.downX = ev.rawX; d.downY = ev.rawY; d.dragging = false; true }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - d.downX; val dy = ev.rawY - d.downY
                        if (!d.dragging && dx * dx + dy * dy > 12f * 12f) d.dragging = true   // past a small slop: a drag, not a tap
                        if (d.dragging) onDrag(dx, dy)
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> { if (d.dragging) onDragEnd() else toggle(); d.dragging = false; true }
                    android.view.MotionEvent.ACTION_CANCEL -> { if (d.dragging) onDragEnd(); d.dragging = false; true }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.padding(top = 2.dp).size(44.dp, 5.dp).background(Color(0xFF4A5363), RoundedCornerShape(3.dp)))
        Spacer(Modifier.height(6.dp))
        if (hint) Text(if (compact) "tap to expand · drag to move" else "tap to shrink · drag to move", fontSize = 11.sp, color = Color(0xFF6B7482))
        if (clock != null) Text(clock, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Dim, maxLines = 1)
    }
}

/** How long the handle's "tap to shrink · drag to move" stays up. */

/**
 * "Start 6:46 → End 6:54 · 8:12 left": when the ride began, when it will be done, and the time left (in the tablet's
 * 12/24-hour style). End follows the time left, so a pause pushes it on. Before the first second is recorded,
 * Start is now. With [rider]: "Marc · 6:46 → 6:54 · 8:12 left".
 */
private fun clockLine(startMs: Long?, secondsLeft: Int, h24: Boolean, rider: String? = null): String {
    val fmt = java.text.SimpleDateFormat(if (h24) "H:mm" else "h:mm", java.util.Locale.getDefault())
    val now = System.currentTimeMillis()
    val start = fmt.format(java.util.Date(startMs ?: now)); val end = fmt.format(java.util.Date(now + secondsLeft * 1000L))
    return (if (rider != null) "$rider · $start → $end" else "Start $start → End $end") + " · ${mmss(secondsLeft)} left"
}

@Composable
private fun Label(t: String) = Text(t, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = Dim)

/** Labels over numbers: small and always one line ("HEART RATE", "INT POWER" used to wrap). */
@Composable
private fun SmallLabel(t: String) = Text(t, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = Dim,
    maxLines = 1, softWrap = false)

@Composable
private fun Big(t: String, color: Color = Fg, size: TextUnit = 32.sp) =
    Text(t, fontSize = size, fontWeight = FontWeight.ExtraBold, color = color, lineHeight = size, maxLines = 1)

@Composable
private fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, pad: androidx.compose.ui.unit.Dp = 12.dp) {
    Box(modifier.height(36.dp).background(Card, RoundedCornerShape(18.dp)).border(1.dp, Line, RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(horizontal = pad), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Fg, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Alert(t: String) = Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AlertFg,
    modifier = Modifier.fillMaxWidth().background(AlertBg, RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 7.dp))

/** "No bike found": what's wrong and what to do, with Open Grupetto when that's the fix. Gone once the bike answers. */
@Composable
private fun BikeHelpCard(gregario: Gregario, h: com.madooroy.gregario.BikeHelp) {
    Column(Modifier.fillMaxWidth().background(AlertBg, RoundedCornerShape(12.dp)).border(1.dp, LostAmber.copy(alpha = .6f), RoundedCornerShape(12.dp))
        .padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(h.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fg)
        Text(h.body, fontSize = 12.sp, color = AlertFg)
        if (h.openGrupetto) Pill("Open Grupetto", { gregario.openGrupetto() }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun LinkLost(t: String, small: Boolean = false) = Text(t, fontSize = if (small) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold,
    color = Color(0xFF1A1200), maxLines = 2, overflow = TextOverflow.Ellipsis,
    modifier = Modifier.fillMaxWidth().background(LostAmber, RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = if (small) 4.dp else 7.dp))

/** A button that needs a second tap within 3 s (for End). */
@Composable
private fun ConfirmPill(text: String, confirm: String, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    var armed by remember { mutableStateOf(false) }
    var armedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(3_000); armed = false } }
    // The second tap counts only after 400 ms: a bounced or doubled touch is not a confirmation (review T1).
    Pill(if (armed) confirm else text, {
        val now = android.os.SystemClock.uptimeMillis()
        if (!armed) { armed = true; armedAt = now } else if (now - armedAt >= 400) { armed = false; onConfirm() }
    }, modifier)
}

/** One workout to pick: name, length and kind, its shape. [onRemove]: a Remove pill (second tap confirms). */
@Composable
private fun WorkoutCard(gregario: Gregario, p: com.madooroy.gregario.icu.PlannedWorkout, onRemove: (() -> Unit)? = null) {
    val ok = p.problem == null
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).let { if (ok) it.clickable { gregario.select(p) } else it }.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (ok) Fg else Dim, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            onRemove?.let { ConfirmPill("Remove", "Tap to remove", it) }
        }
        if (ok) {
            Text("${p.workout.totalSeconds / 60} min · ${p.workout.label()}", fontSize = 11.sp, color = Dim)
            Spacer(Modifier.height(6.dp))
            Strip(p.workout, -1.0, Modifier.fillMaxWidth().height(28.dp))
        } else Text("Can't ride here: ${p.problem}", fontSize = 12.sp, color = AlertFg)
    }
}

@Composable
private fun TodayList(gregario: Gregario, s: PanelState) {
    LaunchedEffect(Unit) { gregario.refreshIfStale() }
    // Several riders on one bike: the panel asks who is riding before showing workouts,
    // so nobody rides on someone else's FTP or uploads to their account. "Switch" asks again.
    val riders by gregario.settings.riders.collectAsState()
    val current by gregario.settings.rider.collectAsState()
    var picking by remember { mutableStateOf(false) }
    if (riders.size > 1 && (picking || gregario.needsRider())) {
        Text("Who's riding?", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fg)
        for (r in riders) {
            Row(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp))
                .border(if (r.id == current) 2.dp else 0.dp, if (r.id == current) Accent else Card, RoundedCornerShape(12.dp))
                .clickable { if (gregario.switchRider(r.id)) picking = false }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Fg, modifier = Modifier.weight(1f))
                if (r.id == current) Text("last rider", fontSize = 11.sp, color = Dim)
            }
        }
        Text("Each rider has their own intervals.icu, FTP, Strava and workout files (Gregario app › Riders).",
            fontSize = 11.sp, color = Dim)
        return
    }
    if (riders.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Riding as ${gregario.settings.riderName}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Accent, modifier = Modifier.weight(1f))
        Pill("Switch", { picking = true })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Today", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fg, modifier = Modifier.weight(1f))
        Pill(if (s.loading) "Loading…" else "Refresh", { gregario.refresh() })
        // Gregario's own screen in one tap (settings, riders, bike): the Peloton tablet has no app drawer. Only here,
        // with no workout open, so it can't be hit mid-ride. Its Ride button comes back to Peloton.
        val context = androidx.compose.ui.platform.LocalContext.current
        Pill("⚙", {
            runCatching {
                context.startActivity(android.content.Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }, Modifier.padding(start = 6.dp), pad = 10.dp)
    }
    // Gregario rides through Grupetto's DirCon: say so plainly when Grupetto isn't there (the card, once 10 s have gone).
    s.bikeHelp?.let { BikeHelpCard(gregario, it) } ?: s.bikeLink?.let { Text(it, fontSize = 12.sp, color = NoteFg) }
    s.message?.let { Text(it, fontSize = 12.sp, color = Dim) }
    for (p in s.today) WorkoutCard(gregario, p)
    // Workout files opened with Gregario (Open with / Share from email, Drive, downloads).
    if (s.files.isNotEmpty()) {
        Text("Your workouts", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Fg)
        for (p in s.files) WorkoutCard(gregario, p, onRemove = { gregario.removeFile(p) })
    }
    s.waitingUploads?.let { Text(it, fontSize = 12.sp, color = NoteFg) }
    // "Send to the bike": add .zwo/.mrc/.erg files from a phone or computer on the same Wi-Fi.
    // Scan the code with the phone's camera: the page opens, pick the files, they appear above. The address is for a
    // computer, or a phone without a camera app that reads codes.
    s.dropAddress?.let {
        Row(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QrCode(it, Modifier.size(96.dp))
            Column(Modifier.weight(1f)) {
                Text("Add workouts from your phone", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Fg)
                Text("Scan with the camera (same Wi-Fi), pick .zwo, .mrc or .erg files.", fontSize = 11.sp, color = Dim)
                Text(it, fontSize = 11.sp, color = Accent, maxLines = 1, softWrap = false)
            }
        }
    }
    if (s.ftp > 0) Text("FTP ${s.ftp} W (intervals.icu)", fontSize = 11.sp, color = Color(0xFF6B7482))
}

@Composable
private fun Full(gregario: Gregario, s: PanelState) {
    val w = s.workout!!; val snap = s.snap!!
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            // Two lines, so a long name reads in full beside Pause ("Gregario DirCon te…", Nov 24 ride).
            Text(w.name, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = Fg, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            // Several riders: who is riding, first so a long label cuts the label and not the name (no room on the clock line).
            // "SS2K ·" when riding through a SmartSpin2K (nothing for Grupetto, the usual case).
            Text((if (gregario.settings.riders.value.size > 1) "${gregario.settings.riderName} · " else "") +
                (if (s.bike.kind == com.madooroy.gregario.trainer.BikeChoice.Kind.SS2K) "SS2K · " else "") + w.label(), fontSize = 11.sp,
                color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // One fixed width, a third of the action row (the size of Skip / ERG on / End when there are three), so
        // Start, Pause and Resume never change the title's width.
        val pw = Modifier.width(PAUSE_WIDTH)
        when (snap.state) {
            // Less side padding than other pills: "Resume" was cut to "Resum" in the 80 (Nov 25 ride).
            WorkoutPlayer.State.Ready -> Pill("Start", { gregario.start() }, pw, pad = 4.dp)
            WorkoutPlayer.State.Paused -> Pill("Resume", { gregario.resume() }, pw, pad = 4.dp)
            else -> Pill("Pause", { gregario.pause() }, pw, pad = 4.dp)
        }
    }
    s.linkLost?.let { LinkLost(it) }
    s.alert?.let { Alert(it) }
    s.notice?.let { Notice(it) }
    ChangeFlash(s)
    // Top half fixed: actions and the six numbers, so the interval time is always in view.
    // Only the bottom half swipes, between the charts and the details, and both pages keep one height.
    RideTop(gregario, s)
    var page by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxWidth().height(BOTTOM_HEIGHT).pointerInput(Unit) {
            var dx = 0f
            detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                if (dx < -40f) page = 1 else if (dx > 40f) page = 0
            }) { change, amount -> change.consume(); dx += amount }
        }) {
        if (page == 1) DetailsBottom(s) else ChartsBottom(s)
    }
    PageDots(page) { page = it }
    IntensityRow(gregario, snap.intensityPct)
}

/** Target and Now at 30; four digits (a sprint past 999 W) at 25 so "1050 W" stays in its 77-wide column. */
private fun wattsSize(w: Int?) = if (w != null && w >= 1000) 25.sp else 30.sp

/** The " W" after Target and Now: one line, never wrapped under the number. */
@Composable
private fun UnitW() = Text(" W", fontSize = 14.sp, color = Dim, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 3.dp))

/** Gap between the Target and Now columns. */
private const val NOTE_SECONDS = 10
private val COL_GAP = 8.dp   // 12 cut "60–70% FTP" to "60–70% FT" (Nov 25 ride)

/** Start / Pause / Resume: a third of the 252-wide action row less its two 6-dp gaps. */
private val PAUSE_WIDTH = 80.dp

/** Height of the swiping half: the NEXT row, the zoomed chart and the strip. */
private val BOTTOM_HEIGHT = 200.dp

@Composable
private fun PageDots(page: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..1) Box(Modifier.clickable { onPick(i) }.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Box(Modifier.size(8.dp).background(if (i == page) Fg else Color(0xFF4A5363), CircleShape))
        }
    }
}

/**
 * The bike this workout rides through: a green dot when it answers, gold while Gregario is still looking, and Switch
 * (Grupetto ⇄ SmartSpin2K) before anything is ridden.
 */
@Composable
private fun BikeLine(gregario: Gregario, s: PanelState) {
    val ss2k = s.bike.kind == com.madooroy.gregario.trainer.BikeChoice.Kind.SS2K
    Row(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(if (s.bikeLink == null) TraceCad else Accent, CircleShape))
        Text(s.bike.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Fg, maxLines = 1)
        Text(if (ss2k) "· Wi-Fi" else "· this tablet", fontSize = 13.sp, color = Dim, maxLines = 1, modifier = Modifier.weight(1f))
        Box(Modifier.height(28.dp).background(Card, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable { gregario.switchBike() }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text("Switch", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Fg, maxLines = 1)
        }
    }
}

/**
 * Set to Grupetto, but a SmartSpin2K is switched on: ask before Start. The two answers are stacked full width
 * ("Use SmartSpin2K" and "Keep Grupetto" don't both fit side by side in the panel).
 */
@Composable
private fun Ss2kOffer(gregario: Gregario) {
    Column(Modifier.fillMaxWidth().background(NoteBg, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("SmartSpin2K found on the Wi-Fi.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NoteFg)
        Text("Ride this workout through it instead of Grupetto?", fontSize = 13.sp, color = NoteFg)
        Box(Modifier.fillMaxWidth().height(36.dp).background(Accent, RoundedCornerShape(18.dp)).clickable { gregario.useFoundSs2k() },
            contentAlignment = Alignment.Center) {
            Text("Use SmartSpin2K", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Bg, maxLines = 1)
        }
        Pill("Keep Grupetto", { gregario.keepGrupetto() }, Modifier.fillMaxWidth())
    }
}

/** % FTP as ridden: the workout's % times the intensity (176 W at 107% of a 50% step reads 54%). */
private fun ridden(pct: Double, intensityPct: Int) = pct * intensityPct / 100.0

/** The interval ring: [left] of the ring still to go (blue), the time in the middle, a small line under it. */
@Composable
private fun Ring(left: Float, big: String, under: String) {
    // 70 (was 84) so the target and the live power both fit beside it.
    Box(Modifier.size(70.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 6.dp.toPx(); val r = Size(size.width - w, size.height - w); val o = Offset(w / 2, w / 2)
            drawArc(Line, 0f, 360f, false, o, r, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            drawArc(Blue, -90f, 360f * left.coerceIn(0f, 1f), false, o, r,
                style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(big, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Fg, maxLines = 1)
            Text(under, fontSize = 9.5.sp, color = Dim, maxLines = 1)
        }
    }
}

/** A live number in a pill, with its line colour as a dot (power, heart rate, cadence). */
@Composable
private fun LivePill(dot: Color, text: String, modifier: Modifier) {
    Row(modifier.height(30.dp).background(Card, RoundedCornerShape(15.dp)), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        Box(Modifier.size(6.dp).background(dot, CircleShape)); Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Fg, maxLines = 1, softWrap = false)
    }
}

/** One of the six numbers: label, big value, small line under it. */
@Composable
private fun Cell(label: String, value: String, under: String, modifier: Modifier, color: Color = Fg,
                 align: Alignment.Horizontal = Alignment.Start, strong: String? = null) =
    Column(modifier, horizontalAlignment = align) {
        // Labels small and on one line ("HEART RATE" wrapped at 11 sp); numbers a little smaller too.
        SmallLabel(label)
        Big(value, color, 25.sp)
        // [strong]: a second live number on the small line, bright and larger so it reads at a glance (resistance).
        if (strong == null) Text(under, fontSize = 11.sp, color = Dim, maxLines = 1)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(under, fontSize = 11.sp, color = Dim, maxLines = 1)
            Text(strong, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fg, maxLines = 1)
        }
    }

@Composable
private fun RideTop(gregario: Gregario, s: PanelState) {
    val w = s.workout!!; val snap = s.snap!!
    val pct = ridden(w.pctAt(snap.elapsed.toDouble()), snap.intensityPct)
    when (snap.state) {
        WorkoutPlayer.State.Ready -> {
            // Which bike this workout will ride through, and the question when an SS2K is on but Grupetto is chosen.
            if (s.ss2kOffer) Ss2kOffer(gregario)
            BikeLine(gregario, s)
            s.bikeNote?.let { Text(it, fontSize = 12.sp, color = NoteFg) }
            Text(if (s.autoStartArmed) "Pedal easy: ERG holds ${snap.targetWatts ?: "–"} W · tap Start when ready · FTP ${s.ftp} W"
                 else "Tap Start to begin · FTP ${s.ftp} W", fontSize = 13.sp, color = NoteFg)
            // No Grupetto DirCon yet: say so (the workout can't hold a target before).
            s.bikeHelp?.let { BikeHelpCard(gregario, it) } ?: s.bikeLink?.let { Text(it, fontSize = 12.sp, color = NoteFg) }
            // Beside an SS2K: power is read from Grupetto itself when its DirCon is on (a second sooner than via the SS2K).
            s.powerHint?.let { Text(it, fontSize = 11.sp, color = Dim) }
            Pill("Cancel", { gregario.close() }, Modifier.fillMaxWidth())
        }
        WorkoutPlayer.State.Countdown -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Starting in", fontSize = 15.sp, color = NoteFg, modifier = Modifier.weight(1f))
            Text("${snap.countdown}", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Fg)
        }
        WorkoutPlayer.State.Paused -> if (s.alert == null)
            Text(if (snap.canAutoResume) "Paused · pedal to resume" else "Paused · tap Resume", fontSize = 13.sp, color = NoteFg)
        else -> {}
    }
    // Inline actions (no popup menus from an overlay window); End needs a second tap.
    if (snap.state != WorkoutPlayer.State.Ready) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Ramp test: one tap ends the ramp (the rider has nothing left); the cooldown follows.
        if (s.rampActive) Pill("Done · end ramp", { gregario.doneRamp() }, Modifier.weight(2f))
        else Pill("Skip", { gregario.skip() }, Modifier.weight(1f))
        // Three pills at most (four don't fit): "ERG on" takes the place of "+1 min" while the knob has the step.
        if (s.inWarmup && !s.riderControl) Pill("+1 min", { gregario.extendWarmup() }, Modifier.weight(1f))
        // The knob was turned (on purpose or bumped): ERG is off until the next step, or back now with one tap.
        if (s.riderControl) Pill("ERG on", { gregario.ergBack() }, Modifier.weight(1f))
        // "End?" fits a third of the row ("Tap to end" was cut off when ERG on or +1 min made three pills).
        ConfirmPill("End", "End?", { gregario.end() }, Modifier.weight(1f))
    }
    // One line: the interval ring, what to hold (gold) and the live power (blue), side by side to compare.
    // Below: heart rate, cadence and the bike's resistance as three pills.
    val step = w.steps.getOrNull(snap.stepIndex)
    val top = (step as? Step.Steady)?.top
    val rampFirst = if (s.rampActive) FtpTest.of(w)?.ramp?.first else null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // A ramp test has no real end to a step: its step number says more than the time left.
        if (rampFirst != null) Ring(1f, "${snap.stepIndex - rampFirst + 1}", "ramp step")
        else Ring(snap.stepRemaining / (step?.seconds ?: 1).coerceAtLeast(1).toFloat(), mmss(snap.stepRemaining),
            "of ${mmss(step?.seconds ?: 0)}")
        // Two equal columns read row by row: Target | Now, target watts | live watts (same size), % range | % now,
        // then the watt range. All left-aligned, with room around the ring and between the columns; each row is its own Row, so the pairs line up.
        val ftp = s.workoutFtp.takeIf { it > 0 } ?: s.ftp
        val tw = Modifier.weight(1f); val nw = Modifier.weight(1f)
        // Three ways to ride a step: ERG holds the target; the Knob and the Shifters hand it to the rider. With the knob or
        // the shifters the big number gives way to the word (Marc, Nov 26: "Knob is clearly where target watts should be"),
        // and the line under it says what to aim for: the shifted target, or the step's range in watts.
        val shifting = s.shiftOffset != 0 && !s.riderControl
        val target = snap.targetWatts?.let { it + s.shiftOffset }
        val range = if (top != null && !pct.isNaN()) watts(pct, s.workoutFtp)?.let { lo ->
            watts(ridden(top, snap.intensityPct), s.workoutFtp)?.let { hi -> lo to hi } } else null
        val inRange = range != null && s.livePower != null && s.livePower in range.first..range.second
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(COL_GAP)) {
                Text(when {
                    pct.isNaN() -> "Ride free"
                    s.riderControl && range != null -> "Knob · range"
                    shifting && range != null -> "Shift · range"
                    else -> "Target"
                }, fontSize = 12.sp, color = Dim, maxLines = 1, softWrap = false, textAlign = TextAlign.Start, modifier = tw)
                Text("Now", fontSize = 12.sp, color = Dim, maxLines = 1, textAlign = TextAlign.Start, modifier = nw)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(COL_GAP)) {
                Row(tw, horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                    when {
                        s.riderControl -> Big("Knob", Accent, 26.sp)
                        shifting -> Big("Shifters", Accent, 20.sp)
                        else -> { Big("${target ?: "–"}", Accent, wattsSize(target)); if (target != null) UnitW() }
                    }
                }
                Row(nw, horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                    // Inside the step's range the live watts turn green: on target.
                    Big("${s.livePower ?: "–"}", if (inRange) InRange else Blue, wattsSize(s.livePower))
                    UnitW()
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(COL_GAP)) {
                val (line, bright) = when {
                    pct.isNaN() -> "free" to false
                    // Shifters: the ERG target with the shift, bright, since it's what the rider holds now.
                    shifting && range != null -> "ERG $target · ${"%+d".format(s.shiftOffset)}" to false   // "W" left out: 11 sp in a 79 dp column
                    shifting -> "$target W · ${"%+d".format(s.shiftOffset)}" to true
                    top != null -> "${pct.roundToInt()}–${ridden(top, snap.intensityPct).roundToInt()}% FTP" to false
                    else -> "${pct.roundToInt()}% FTP" to false
                }
                Text(line, fontSize = 11.sp, fontWeight = if (bright) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (bright) Fg else Dim, maxLines = 1, softWrap = false, textAlign = TextAlign.Start, modifier = tw)
                Text(s.livePower?.takeIf { ftp > 0 }?.let { "${(it * 100.0 / ftp).roundToInt()}% FTP" } ?: "",
                    fontSize = 11.sp, color = Dim, maxLines = 1, softWrap = false, textAlign = TextAlign.Start, modifier = nw)
            }
            // A range step ("60–70% FTP") gives its watts too, so the rider on the knob or shifters knows what to aim for.
            // It may run on under the Now column (empty on this line): "197–230 W" was cut to "197–230 V" (Nov 25 ride).
            if (range != null) {
                Text("${range.first}–${range.second} W",
                    // Dim like the % line under ERG; bright on the knob or shifters, where it's what the rider aims for.
                    fontSize = 11.sp, fontWeight = if (s.riderControl || shifting) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (s.riderControl || shifting) Fg else Dim, maxLines = 1, softWrap = false,
                    textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                RangeBar(range.first, range.second, s.livePower, if (shifting) target else null,
                    Modifier.fillMaxWidth().padding(top = 3.dp).height(8.dp))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        LivePill(TraceHr, s.liveHeartRate?.let { "$it bpm" } ?: "no HR", Modifier.weight(1f))
        LivePill(TraceCad, "${s.liveCadence ?: "–"} rpm", Modifier.weight(1f))
        LivePill(Dim, "Res ${s.liveResistance ?: "–"}", Modifier.weight(1f))
    }
    // A step's note shows for its first 10 s and then for the first 10 s of each ride minute, so most of the time the
    // panel is clear (Marc, Nov 26: pictures without test notes; a reminder each minute is enough).
    val intoStep = (step?.seconds ?: 0) - snap.stepRemaining
    snap.note?.takeIf { intoStep < NOTE_SECONDS || snap.elapsed % 60 < NOTE_SECONDS }?.let {
        Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = NoteFg,
            modifier = Modifier.fillMaxWidth().background(NoteBg, RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

/**
 * A range step at a glance: the green band is the range, the white mark is the live power, and in Shifters mode the
 * gold tick is the shifted ERG target. The bar shows half the range again (at least 20 W) on each side.
 */
@Composable
private fun RangeBar(lo: Int, hi: Int, now: Int?, erg: Int?, modifier: Modifier) {
    Canvas(modifier) {
        val pad = maxOf((hi - lo) / 2.0, 20.0)
        val min = lo - pad; val max = hi + pad
        fun x(w: Double) = (((w - min) / (max - min)).coerceIn(0.0, 1.0) * size.width).toFloat()
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(Line, cornerRadius = r)
        drawRoundRect(InRange.copy(alpha = .55f), Offset(x(lo.toDouble()), 0f), Size(x(hi.toDouble()) - x(lo.toDouble()), size.height), r)
        erg?.let { val ex = x(it.toDouble()); drawRect(Accent, Offset(ex - 1.5f, -2f), Size(3f, size.height + 4f)) }
        now?.let { val nx = x(it.toDouble()); drawRect(Color.White, Offset(nx - 2f, -3f), Size(4f, size.height + 6f)) }
    }
}

/** Bottom page 1: the next step, the zoomed chart and the whole-workout strip. */
@Composable
private fun ChartsBottom(s: PanelState) {
    val w = s.workout!!; val snap = s.snap!!
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Zoom(w, snap.elapsed.toDouble(), s.ftp, Modifier.fillMaxWidth().weight(1f), s.trace, s.traceSize)
        Strip(w, snap.elapsed.toDouble(), Modifier.fillMaxWidth().height(42.dp), s.trace, s.traceSize)
        ThenLine(s)
    }
}

/** "Then 165 W · 55% · 2:00 … in 3:42": the next step, under the charts. */
@Composable
private fun ThenLine(s: PanelState) {
    val w = s.workout!!; val snap = s.snap!!
    val nw = snap.nextWatts ?: run { Text("Last step", fontSize = 13.sp, color = Dim); return }
    val nstep = w.steps.getOrNull(snap.stepIndex + 1)
    val npct = ridden(nstep?.pctAt(0.0) ?: Double.NaN, snap.intensityPct)
    // A ramp or range next shows where it goes ("197→148 W", "60→45%").
    val nend = when (nstep) {
        is Step.Ramp -> ridden(nstep.pctAt(nstep.seconds.toDouble()), snap.intensityPct)
        is Step.Steady -> nstep.top?.let { ridden(it, snap.intensityPct) }
        else -> null
    }
    val sep = if (nstep is Step.Ramp) "→" else "–"
    val nendW = nend?.let { watts(it, s.workoutFtp) }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Then", fontSize = 12.sp, color = Dim)
        Box(Modifier.size(5.dp, 16.dp).background(zoneColor(npct)))
        Text(if (nendW != null) "$nw$sep$nendW W" else "$nw W", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Fg,
            maxLines = 1, softWrap = false)
        val pctPart = when { npct.isNaN() -> ""; nend != null -> "${npct.roundToInt()}$sep${nend.roundToInt()}% · "; else -> "${npct.roundToInt()}% · " }
        Text(pctPart + mmss(snap.nextStepSeconds ?: 0), fontSize = 11.sp, color = Dim, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("in ${mmss(snap.stepRemaining)}", fontSize = 12.sp, color = Accent, maxLines = 1)
    }
}

private const val COMING_UP = 6

@Composable
private fun StatRow(label: String, a: String, b: String, c: String) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Short labels, so "109 bpm" fits its column (it was cut to "109", Nov 24 ride).
        Text(label, fontSize = 11.sp, color = Dim, modifier = Modifier.width(52.dp), maxLines = 1, softWrap = false)
        // Each value at its own width, spread across the row: equal thirds ran "137 bpm" into "91 rpm" (Nov 25 ride).
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            for (v in listOf(a, b, c)) Text(v, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Fg, maxLines = 1, softWrap = false)
        }
    }

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, align: Alignment.Horizontal = Alignment.Start) =
    Column(modifier, horizontalAlignment = align) {
        SmallLabel(label); Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fg, maxLines = 1)
    }

/** Bottom page 2 (swipe): this interval's averages, the ride so far, and the next steps (up to [COMING_UP]). */
@Composable
private fun DetailsBottom(s: PanelState) {
    val w = s.workout!!; val snap = s.snap!!; val st = s.stats
    // Compact so all the COMING UP lines fit in the fixed bottom height (three left room for more, Nov 25 ride).
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Two rows: this interval (power, heart rate, cadence), and the whole ride (power, kJ, TSS).
        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatRow("Interval", st?.intervalPower?.let { "$it W" } ?: "–", st?.intervalHr?.let { "$it bpm" } ?: "–",
                st?.intervalCadence?.let { "$it rpm" } ?: "–")
            StatRow("Ride", st?.let { "${it.avgPower} W" } ?: "–", st?.let { "${it.kj} kJ" } ?: "–",
                st?.let { "TSS ${it.tss}" } ?: "–")
        }
        Text("Coming up", fontSize = 11.sp, color = Dim)
        val next = ((snap.stepIndex + 1)..minOf(w.steps.lastIndex, snap.stepIndex + COMING_UP)).toList()
        if (next.isEmpty()) Text("Last step", fontSize = 13.sp, color = Dim)
        for (i in next) {
            val step = w.steps[i]
            val p0 = ridden(step.pctAt(0.0), snap.intensityPct)
            val p1 = ridden(step.pctAt(step.seconds.toDouble()), snap.intensityPct)
            val top = (step as? Step.Steady)?.top?.let { ridden(it, snap.intensityPct) }
            val wattsText = when {
                p0.isNaN() -> "free"
                top != null -> "${watts(p0, s.workoutFtp)}–${watts(top, s.workoutFtp)} W"
                step is Step.Ramp -> "${watts(p0, s.workoutFtp)}→${watts(p1, s.workoutFtp)} W"
                else -> "${watts(p0, s.workoutFtp)} W"
            }
            val pctText = when {
                p0.isNaN() -> ""
                top != null -> "${p0.roundToInt()}–${top.roundToInt()}%"
                step is Step.Ramp -> "${p0.roundToInt()}→${p1.roundToInt()}%"
                else -> "${p0.roundToInt()}%"
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(5.dp, 14.dp).background(zoneColor(p0), RoundedCornerShape(2.dp)))
                // Watts take their full width so a ramp ("197→148 W") or range ("165–230 W") is never cut off
                // (a down ramp showed as "197→1"); the % column gives way instead.
                Text(wattsText, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Fg, maxLines = 1, softWrap = false)
                Text(pctText, fontSize = 10.sp, color = Dim, maxLines = 1, softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(mmss(step.seconds), fontSize = 10.sp, color = Dim, maxLines = 1, softWrap = false)
                Text("in ${mmss(w.starts[i] - snap.elapsed)}", fontSize = 10.sp, color = Accent, maxLines = 1, softWrap = false,
                    textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
            }
        }
    }
}

@Composable
private fun IntensityRow(gregario: Gregario, intensityPct: Int) {
    // Easier / Harder: the whole workout down or up by 1%, with where it stands in the middle.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TintPill("Easier", Color(0xFF1E3A2B).copy(alpha = surface), TraceCad, { gregario.changeIntensity(-1) }, Modifier.weight(1f))
        Text("$intensityPct%", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Fg, textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp))
        TintPill("Harder", AlertBg, TraceHr, { gregario.changeIntensity(+1) }, Modifier.weight(1f))
    }
}

/** A pill with its own tint: a dark fill and text in the matching light colour. */
@Composable
private fun TintPill(text: String, fill: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.height(38.dp).background(fill, RoundedCornerShape(19.dp)).border(1.dp, fg.copy(alpha = .35f), RoundedCornerShape(19.dp))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

/**
 * The last 3 seconds before a step change: a bar in the next step's zone colour that flashes each second,
 * with the next target and the count (a 3-2-1, without beeps).
 */
@Composable
private fun ChangeFlash(s: PanelState, big: Boolean = true) {
    val snap = s.snap ?: return
    val n = snap.changeIn ?: return
    val nw = snap.nextWatts ?: return
    val c = zoneColor(if (s.ftp > 0) nw * 100.0 / s.ftp else Double.NaN)
    val on = n % 2 == 1
    Row(Modifier.fillMaxWidth().background(if (on) c else c.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
        .padding(horizontal = 10.dp, vertical = if (big) 8.dp else 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("NEXT  $nw W", fontSize = if (big) 20.sp else 15.sp, fontWeight = FontWeight.Bold,
            color = if (on) Color(0xFF0F1115) else Fg, modifier = Modifier.weight(1f))
        Text("$n", fontSize = if (big) 30.sp else 22.sp, fontWeight = FontWeight.Black, color = if (on) Color(0xFF0F1115) else Fg)
    }
}

@Composable
private fun Compact(gregario: Gregario, s: PanelState) {
    val w = s.workout!!; val snap = s.snap!!
    s.linkLost?.let { LinkLost(it, small = true) }
    ChangeFlash(s, big = false)
    // Anything that needs a decision shows here too, with its button.
    when (snap.state) {
        WorkoutPlayer.State.Ready -> {
            s.alert?.let { Text(it, fontSize = 12.sp, color = AlertFg, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (s.ss2kOffer) Text("SmartSpin2K found · tap to expand and choose", fontSize = 12.sp, color = NoteFg, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (s.autoStartArmed && s.alert == null) "${w.name} · pedal easy: ERG ${snap.targetWatts ?: "–"} W"
                     else "${w.name} · tap Start", fontSize = 13.sp, color = NoteFg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Pill("Start", { gregario.start() }); Pill("Cancel", { gregario.close() })
            }
        }
        WorkoutPlayer.State.Paused -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.alert ?: if (snap.canAutoResume) "Paused · pedal to resume" else "Paused", fontSize = 12.sp,
                color = if (s.alert != null) AlertFg else NoteFg, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Pill("Resume", { gregario.resume() })
        }
        WorkoutPlayer.State.Countdown -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Starting in", fontSize = 15.sp, color = NoteFg, modifier = Modifier.weight(1f))
            Text("${snap.countdown}", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Fg)
        }
        else -> {}
    }
    val pct = ridden(w.pctAt(snap.elapsed.toDouble()), snap.intensityPct)
    val npct = ridden(w.steps.getOrNull(snap.stepIndex + 1)?.pctAt(0.0) ?: Double.NaN, snap.intensityPct)
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) { Label("TARGET"); Big(if (s.riderControl) "Knob" else "${snap.targetWatts?.let { it + s.shiftOffset } ?: "–"}", Accent, 28.sp) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Label("INTERVAL"); Big(mmss(snap.stepRemaining), size = 28.sp) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Label("NEXT")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(6.dp, 18.dp).background(zoneColor(npct), RoundedCornerShape(2.dp)))
                Text("${snap.nextWatts ?: "–"}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Fg)
            }
        }
    }
    if (s.rampActive) Pill("Done · end ramp", { gregario.doneRamp() }, Modifier.fillMaxWidth())
    s.notice?.let { Text(it, fontSize = 12.sp, color = NoteFg, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    Strip(w, snap.elapsed.toDouble(), Modifier.fillMaxWidth().height(14.dp))
}

@Composable
private fun Finished(gregario: Gregario, s: PanelState) {
    val st = s.stats
    Text("Workout complete", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Dim)
    Text(s.workout!!.name, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Fg)
    if (st != null) Row {
        Column(Modifier.weight(1f)) { Label("AVG"); Text("${st.avgPower} W", color = Fg, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f)) { Label("NP"); Text("${st.np} W", color = Fg, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f)) { Label("WORK"); Text("${st.kj} kJ", color = Fg, fontWeight = FontWeight.Bold) }
    }
    s.testResult?.let { TestResults(gregario, s, it) }
    if (!s.rideSaved) {
        // RPE 1-10 as intervals.icu stores it, grouped into five levels: tap a number,
        // see intervals.icu's own word for it, then Save.
        var rpe by remember { mutableStateOf<Int?>(null) }
        Label("HOW HARD WAS IT? (RPE)")
        for ((group, nums) in RPE_GROUPS) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val c = rpeColor(nums.first())
            Text(group, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c, modifier = Modifier.weight(1.3f))
            for (k in 0 until 3) {
                val n = nums.getOrNull(k)
                if (n == null) { Spacer(Modifier.weight(1f)); continue }   // rows line up in three columns
                val on = rpe == n
                Box(Modifier.weight(1f).height(34.dp).background(if (on) c else Card, RoundedCornerShape(9.dp))
                    .border(1.dp, if (on) c else Line, RoundedCornerShape(9.dp)).clickable { rpe = n },
                    contentAlignment = Alignment.Center) {
                    Text("$n", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (on) Color(0xFF0F1115) else Fg)
                }
            }
        }
        Text(rpe?.let { rpeWord(it) } ?: "Tap a number", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = rpe?.let { rpeColor(it) } ?: Dim)
        Pill(if (rpe != null) "Save & upload" else "Save & upload without RPE", { gregario.saveRide(rpe) }, Modifier.fillMaxWidth())
        ConfirmPill("Discard ride", "Tap again to discard", { gregario.discardRide() }, Modifier.fillMaxWidth())
    } else {
        s.uploadStatus?.let { Text(it, fontSize = 13.sp, color = NoteFg) }
        // The ride's .fit to Drive, email or Files: for a rider with nothing connected, or anyone who wants the file.
        if (s.uploadStatus != "Too short to save" && s.uploadStatus != "Not saved")
            Pill("Share ride file", { gregario.shareRide() }, Modifier.fillMaxWidth())
        Pill("Done", { gregario.close() }, Modifier.fillMaxWidth())
    }
}

/**
 * A test's numbers, large enough to read in a screenshot for other apps: the ramp's best minute and the
 * 20-minute average, each with the FTP it gives and a Set FTP button (indoor FTP in intervals.icu, second tap confirms).
 */
@Composable
private fun TestResults(gregario: Gregario, s: PanelState, r: TestResult) {
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp))
        .padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("TEST RESULT")
        if (r.best1 != null && r.ftpFromRamp != null) {
            TestRow(gregario, s, "RAMP · BEST 1 MIN", r.best1, "75%", r.ftpFromRamp)
            Text("${r.rampStepsDone} ramp steps", fontSize = 12.sp, color = Dim)
        }
        if (r.best20 != null && r.ftpFrom20 != null) TestRow(gregario, s, "20-MIN AVERAGE", r.best20, "95%", r.ftpFrom20)
        Text("Ridden at FTP ${s.workoutFtp} W", fontSize = 11.sp, color = Dim)
        s.ftpStatus?.let { Text(it, fontSize = 13.sp, color = NoteFg) }
    }
}

@Composable
private fun TestRow(gregario: Gregario, s: PanelState, what: String, watts: Int, how: String, ftp: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) { Label(what); Big("$watts W", size = 26.sp) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { Label("FTP ($how)"); Big("$ftp W", Accent, 26.sp) }
    }
    if (ftp != s.ftp) ConfirmPill("Set indoor FTP to $ftp W", "Tap again to set $ftp W", { gregario.setIndoorFtp(ftp) }, Modifier.fillMaxWidth())
    else Text("FTP is already $ftp W", fontSize = 12.sp, color = Dim)
}

@Composable
private fun Notice(t: String) = Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NoteFg,
    modifier = Modifier.fillMaxWidth().background(NoteBg, RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 7.dp))

/** RPE rows: five levels, holding intervals.icu's 1-10 by its own wording. */
private val RPE_GROUPS = listOf("Easy" to listOf(1, 2, 3), "Moderate" to listOf(4, 5), "Hard" to listOf(6, 7),
    "Very Hard" to listOf(8, 9), "Maximum" to listOf(10))
/** intervals.icu's own words for RPE 1-10, as its RPE picker shows them. */
private val RPE_WORDS = listOf("Nothing at all", "Very easy", "Easy", "Comfortable", "Slightly challenging",
    "Difficult", "Hard", "Very hard", "Extremely hard", "Max effort")
private fun rpeWord(n: Int): String {
    val word = RPE_WORDS.getOrNull(n - 1) ?: return "$n/10"
    return "$n · $word"
}
private fun rpeColor(n: Int) = when (n) {
    in 1..3 -> Color(0xFF469C2D); in 4..5 -> Color(0xFFF7CD47); in 6..7 -> Color(0xFFEF8635)
    in 8..9 -> Color(0xFFCB2C4C); else -> Color(0xFF5F36C3)
}

/** Whole workout as zone-colored blocks, with a marker at [t] (t < 0: no marker). */
@Composable
private fun Strip(w: Workout, t: Double, modifier: Modifier,
                  trace: List<com.madooroy.gregario.session.TracePoint> = emptyList(), traceSize: Int = 0) {
    val maxPct = maxOf(130.0, w.steps.maxOfOrNull { st -> if (st is Step.Ramp) maxOf(st.startPct, st.endPct) else maxOf((st as? Step.Steady)?.top ?: 0.0, st.pctAt(0.0).takeIf { !it.isNaN() } ?: 0.0) } ?: 0.0)
    Canvas(modifier.clipToBounds()) {
        val total = w.totalSeconds.toFloat().coerceAtLeast(1f)
        // Finished steps shaded (55%), the current step and what's to come solid; no marker (a picker card): all solid.
        w.steps.forEachIndexed { i, st ->
            val alpha = if (t >= 0 && w.starts[i] + st.seconds <= t) .55f else 1f
            drawStep(st, w.starts[i].toFloat(), 0f, total, maxPct, size, alpha = alpha)
        }
        val x = if (t >= 0) (t / total * size.width).toFloat().coerceIn(0f, size.width) else 0f
        if (traceSize > 1) drawTrace(trace, 0f, total, maxPct, thin = true)
        if (t >= 0) drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2f)
    }
}

private val TracePower = Color(0xFF4FC3F7)   // light blue: power (3-s average in the strip, 1-s in the zoom)
private val TraceHr = Color(0xFFF28B82)      // coral: heart rate, thin
private val TraceCad = Color(0xFF8FD9A8)     // green: cadence, thin
// Heart rate and cadence low in the chart, each on its own scale from 0
// (150 bpm sits about a third of the way up, 90 rpm about a quarter), so they stay below the power blocks.
private const val HR_TOP = 400f; private const val CAD_TOP = 350f

/** The ride's light-blue power line (3-s in the strip, 1-s in the zoom; workout's % FTP scale), thin coral heart-rate and green cadence lines (own scales). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrace(
    trace: List<com.madooroy.gregario.session.TracePoint>, from: Float, span: Float, maxPct: Double, thin: Boolean) {
    val h = size.height; val wd = size.width
    fun x(t: Int) = (t - from) / span * wd
    val power = androidx.compose.ui.graphics.Path(); val hr = androidx.compose.ui.graphics.Path()
    val cad = androidx.compose.ui.graphics.Path()
    var pOpen = false; var hOpen = false; var cOpen = false; var prevT = Int.MIN_VALUE
    for (p in trace) {
        val px = x(p.t)
        if (px < -2f || px > wd + 2f) { pOpen = false; hOpen = false; cOpen = false; prevT = p.t; continue }
        val gap = p.t - prevT > 3                                   // paused: start a new segment
        // The thin whole-ride strip draws the 3-s average; the zoomed chart draws each second as it was (no average).
        val py = (h * (1 - (if (thin) p.pct3s else p.pct1s) / maxPct)).toFloat().coerceIn(0f, h)
        if (!pOpen || gap) power.moveTo(px, py) else power.lineTo(px, py); pOpen = true
        val b = p.hr
        if (b != null) {
            val hy = (h * (1 - b / HR_TOP)).coerceIn(0f, h)
            if (!hOpen || gap) hr.moveTo(px, hy) else hr.lineTo(px, hy); hOpen = true
        } else hOpen = false
        if (p.cadence > 0f) {
            val cy = (h * (1 - p.cadence / CAD_TOP)).coerceIn(0f, h)
            if (!cOpen || gap) cad.moveTo(px, cy) else cad.lineTo(px, cy); cOpen = true
        } else cOpen = false
        prevT = p.t
    }
    drawPath(cad, TraceCad.copy(alpha = .85f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (thin) 1f else 1.5f))
    drawPath(hr, TraceHr, style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (thin) 1f else 1.5f))
    drawPath(power, TracePower, style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (thin) 1.5f else 2.5f))
}

/** The current step with ~75 s before and ~105 s after, FTP line dashed. */
@Composable
private fun Zoom(w: Workout, t: Double, ftp: Int, modifier: Modifier,
                 trace: List<com.madooroy.gregario.session.TracePoint> = emptyList(), traceSize: Int = 0) {
    Canvas(modifier.clipToBounds()) {
        val a = (t - 75).toFloat(); val span = 180f; val maxPct = 130.0
        w.steps.forEachIndexed { i, st ->
            drawStep(st, w.starts[i].toFloat(), a, span, maxPct, size, alpha = if (w.starts[i] <= t) .9f else .5f)
        }
        if (traceSize > 1) drawTrace(trace, a, span, maxPct, thin = false)
        val x = ((t - a) / span * size.width).toFloat()
        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2f)
        val fy = (size.height * (1 - 100 / maxPct)).toFloat()
        drawLine(Color.White.copy(alpha = .35f), Offset(0f, fy), Offset(size.width, fy), 1f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStep(
    st: Step, start: Float, from: Float, span: Float, maxPct: Double, area: Size, alpha: Float,
) {
    val x0 = (start - from) / span * area.width
    val x1 = (start + st.seconds - from) / span * area.width
    if (x1 < 0 || x0 > area.width) return
    val pieces = if (st is Step.Ramp) 8 else 1
    for (k in 0 until pieces) {
        val tMid = st.seconds * (k + .5) / pieces
        val pct = st.pctAt(tMid).let { if (it.isNaN()) 50.0 else it }
        val px0 = x0 + (x1 - x0) * k / pieces; val px1 = x0 + (x1 - x0) * (k + 1) / pieces
        // A ramp piece wholly outside the view would get a negative width and be drawn past the edge (Nov 22 photo).
        if (px1 <= 0f || px0 >= area.width) continue
        val h = (area.height * (pct / maxPct).coerceIn(0.0, 1.0)).toFloat()
        drawRect(zoneColor(pct).copy(alpha = alpha), Offset(px0.coerceAtLeast(0f), area.height - h),
            Size(px1.coerceAtMost(area.width) - px0.coerceAtLeast(0f), h))
        // A range step ("60–70%"): the block is the bottom (what ERG holds); the rest of the range sits on top as a
        // lighter band in the top's zone color, with a thin line at the top.
        val top = (st as? Step.Steady)?.top
        if (top != null && top > pct) {
            val ht = (area.height * (top / maxPct).coerceIn(0.0, 1.0)).toFloat()
            val bx = px0.coerceAtLeast(0f); val bw = px1.coerceAtMost(area.width) - bx
            drawRect(zoneColor(top).copy(alpha = alpha * .38f), Offset(bx, area.height - ht), Size(bw, ht - h))
            drawLine(zoneColor(top).copy(alpha = alpha), Offset(bx, area.height - ht), Offset(bx + bw, area.height - ht), 1.5f)
        }
    }
}

/** A QR code for [text] (ZXing), drawn black on white with its quiet zone, so any phone camera reads it on the dark panel. */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching {
            com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        }.getOrNull()
    } ?: return
    Canvas(modifier.background(Color.White, RoundedCornerShape(6.dp))) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width)
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + .5f, cell + .5f))
    }
}
