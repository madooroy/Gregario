package com.madooroy.gregario

import android.os.SystemClock
import com.madooroy.gregario.icu.IcuApi
import com.madooroy.gregario.icu.PlannedWorkout
import com.madooroy.gregario.record.RideLog
import com.madooroy.gregario.record.RideRecorder
import com.madooroy.gregario.session.BikeStatus
import com.madooroy.gregario.session.ErgSink
import com.madooroy.gregario.session.RideStats
import com.madooroy.gregario.session.WorkoutSession
import com.madooroy.gregario.upload.IcuUploader
import com.madooroy.gregario.upload.StravaAccount
import com.madooroy.gregario.upload.UploadQueue
import com.madooroy.gregario.workout.IcuParser
import com.madooroy.gregario.workout.Workout
import com.madooroy.gregario.workout.WorkoutPlayer
import com.madooroy.gregario.trainer.DirConLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the panel shows. */
data class PanelState(
    val message: String? = null,
    val loading: Boolean = false,
    val today: List<PlannedWorkout> = emptyList(),
    /** Workout files opened with Gregario ("Your workouts"), newest first. */
    val files: List<PlannedWorkout> = emptyList(),
    /** The date [today] was fetched for (yyyy-MM-dd), so a new day refetches. */
    val todayDate: String? = null,
    val workout: Workout? = null,
    val ftp: Int = 0,
    val snap: WorkoutPlayer.Snapshot? = null,
    val stats: RideStats? = null,
    val livePower: Int? = null,
    val liveCadence: Int? = null,
    /** Peloton resistance (0–100), shown with cadence so the rider needn't look at Peloton's screen. */
    val liveResistance: Int? = null,
    val liveHeartRate: Int? = null,
    /** Why the workout paused (control taken, target refused); shown until resumed. */
    val alert: String? = null,
    /** Pedaling brings ERG to the first target before Start (only for a few minutes after choosing the workout). */
    val autoStartArmed: Boolean = false,
    /** Watts the rider added with the shifters during this step. */
    val shiftOffset: Int = 0,
    val inWarmup: Boolean = false,
    /** The finished ride has been saved for upload (or discarded): the finish screen shows Done. */
    val rideSaved: Boolean = false,
    /** Where the uploads stand, for the finish screen and the settings ("intervals.icu ✓ · Strava ✓"). */
    val uploadStatus: String? = null,
    /** Strava: "Connected as …", "Not connected", or a problem. */
    val strava: String? = null,
    /** Rides not uploaded yet, for the Today list ("2 rides waiting · <last error>"), or null. */
    val waitingUploads: String? = null,
    /** "Send to the bike" page address (http://192.168.x.x:8095) while it runs, else null. */
    val dropAddress: String? = null,
    /** The ride so far, for the charts' yellow (3-s power) and red (heart rate) lines. Mutated on the main thread only. */
    val trace: List<com.madooroy.gregario.session.TracePoint> = emptyList(),
    val traceSize: Int = 0,
    /** Information that is not a problem (e.g. "Ramp test over · cool down · FTP 312 W"). */
    val notice: String? = null,
    /** Riding the open-ended ramp of a ramp test: the panel shows Done. */
    val rampActive: Boolean = false,
    /** A test's numbers, on the finish screen. */
    val testResult: com.madooroy.gregario.workout.TestResult? = null,
    /** What Set FTP did ("Setting…", "Indoor FTP is now 312 W", or the error). */
    val ftpStatus: String? = null,
    /** The FTP the current workout is ridden at (s.ftp can change after Set FTP). */
    val workoutFtp: Int = 0,
    /** The rider took the knob in a range step: the target is theirs until the step ends. */
    val riderControl: Boolean = false,
    /** Bumped when riders change (added, removed, switched, confirmed), so the panel redraws the rider picker. */
    val riderTick: Int = 0,
    /** Why the bike can't be reached ("Looking for Grupetto…", "Looking for SmartSpin2K…"), or null when connected. */
    val bikeLink: String? = null,
    /** The bike Gregario rides through (the panel's Bike line, and "SS2K ·" under the title during a ride). */
    val bike: com.madooroy.gregario.trainer.BikeChoice = com.madooroy.gregario.trainer.BikeChoice.DEFAULT,
    /** Set to Grupetto, but a SmartSpin2K is switched on and announcing itself: ask before Start (never switch alone). */
    val ss2kOffer: Boolean = false,
    /** Why the Switch pill did nothing ("No SmartSpin2K found…"), under the Bike line until the next change. */
    val bikeNote: String? = null,
    /** Wall-clock time the ride's first second was recorded (the panel's "Start 6:46 → End 6:54"), or null before. */
    val rideStartMs: Long? = null,
    /** The connection to the bike dropped during a workout ("Lost the SmartSpin2K · reconnecting"): amber border. */
    val linkLost: String? = null,
    /** SmartSpin2K riding without Grupetto's DirCon: the numbers come a second late through the SS2K. A hint, or null. */
    val powerHint: String? = null,
)

/**
 * One per app: loads today's workout from intervals.icu and runs it on the bike through Grupetto's DirCon
 * ([DirConLink]: FTMS on this tablet, the way Zwift talks to Grupetto; Grupetto does the ERG on the Bike+).
 * Runs only while the panel is up ([attach] / [detach]); everything happens on the main thread, except the network
 * calls (DirCon answers on the tablet in a few milliseconds).
 */
class Gregario private constructor(private val app: android.app.Application) {
    val settings = AppSettings(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PanelState(ftp = settings.lastFtp))
    val state: StateFlow<PanelState> = mutableState

    private var session: WorkoutSession? = null
    private var planned: PlannedWorkout? = null
    // Each rider's rides and workout files in their own folder ("rides", "rides-<id>"): see [Riders].
    private fun queueFor(id: String) = UploadQueue(java.io.File(app.filesDir, Riders.dirName("rides", id)))
    private fun filesFor(id: String) = com.madooroy.gregario.workout.WorkoutFiles(java.io.File(app.filesDir, Riders.dirName("workout-files", id)))
    /** The current rider's. Switching riders is refused while a workout is loaded, so a ride stays with its rider. */
    private val queue get() = queueFor(settings.rider.value)
    private val workoutFiles get() = filesFor(settings.rider.value)

    // Gregario's own ride log (no logcat commands needed): bike-link lines (tag LOG_TAG) and warnings while a workout is loaded.
    // adb pull /sdcard/Android/data/com.madooroy.gregario/files/ride-logs
    private val rideLog = RideLog()
    private val rideLogDir get() = app.getExternalFilesDir("ride-logs") ?: java.io.File(app.filesDir, "ride-logs")
    init {
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == LOG_TAG || priority >= android.util.Log.WARN)
                    rideLog.line(tag ?: "?", if (t == null) message else "$message ${t}")
            }
        })
    }
    private fun endRideLog(s: WorkoutSession?) = rideLog.stop(keep = (s?.recorder?.size ?: 0) > 0)
    /**
     * One Strava account per rider, each bound to that rider's own stored sign-in (review R1: an account that followed
     * "whoever is current" sent a ride, or a refreshed sign-in, to the next rider when they switched mid-upload).
     */
    private val stravaAccounts = HashMap<String, StravaAccount>()
    private fun stravaFor(id: String) = synchronized(stravaAccounts) { stravaAccounts.getOrPut(id) { StravaAccount(settings.storeFor(id)) } }
    /** The current rider's (settings screen). */
    val stravaAccount get() = stravaFor(settings.rider.value)
    /** Ride files are written and deleted in order on one thread, so a late draft can't bring back a saved or discarded ride (review U4). */
    private val disk = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "gregario-rides") }.asCoroutineDispatcher()
    private var uploadJob: Job? = null
    private var uploadAgain = false
    private var savedRideId: String? = null
    private var draftAtSecs = 0.0
    /** Wall time = this + elapsedRealtime, so a clock correction mid-ride can't make FIT timestamps go backwards. */
    private var wallBase = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    private var loop: Job? = null
    /** Refreshes the live POWER / CADENCE / RES numbers between the once-a-second workout ticks. */
    private var liveJob: Job? = null
    private val live = com.madooroy.gregario.session.LiveReadout()
    private var refreshJob: Job? = null
    private var attached = false
    private var armedUntil = 0L

    /**
     * The bike connection: bike data in, ERG targets out. Grupetto's DirCon on this tablet, or a SmartSpin2K's DirCon
     * over Wi-Fi (main screen › Bike connection). Rebuilt by [setBike], only while no workout is open.
     */
    private var bikeChoice = settings.bike.value
    private var link = newLink(bikeChoice)

    private fun newLink(b: com.madooroy.gregario.trainer.BikeChoice) =
        DirConLink(host = b.host ?: "", clock = { SystemClock.elapsedRealtime() }, log = { Timber.tag(LOG_TAG).i(it) },
            name = b.name, lookFor = b.lookFor, handBackCommand = b.handBack, holdsAfterDisconnect = b.holdsAfterDisconnect,
            holdsThroughStops = b.holdsThroughStops, followsShifts = b.followsShifts,
            owedAtStart = b.holdsAfterDisconnect && b.host != null && settings.ss2kOwedHost == b.host,
            onOwed = { owed -> settings.ss2kOwedHost = if (owed) b.host.orEmpty() else "" })

    /**
     * Where the bike's numbers come from. With a SmartSpin2K doing the ERG, power, cadence and heart rate are read
     * straight from Grupetto on this tablet (bike data only: Grupetto is never sent a target), because the SS2K only
     * relays Grupetto's reading a second later. Without Grupetto's DirCon, the SS2K's relay is used as before.
     */
    private var reader: DirConLink? = newReader(bikeChoice)

    private fun newReader(b: com.madooroy.gregario.trainer.BikeChoice) =
        if (b.kind != com.madooroy.gregario.trainer.BikeChoice.Kind.SS2K) null
        else DirConLink(host = "127.0.0.1", clock = { SystemClock.elapsedRealtime() }, log = { Timber.tag(LOG_TAG).i("Grupetto reader: $it") },
            name = "Grupetto", lookFor = "switch DirCon on in Grupetto", readOnly = true)

    /** When the panel was attached ([attach]), for the Grupetto-DirCon hint's grace period. */
    private var attachedAt = 0L

    /** The SmartSpin2K announcing itself on the Wi-Fi right now (while the panel is up), or null. */
    private var ss2kFound: com.madooroy.gregario.trainer.DirConService? = null
    /** "Keep Grupetto" was tapped: don't ask again until the panel closes or the SS2K goes away. */
    private var ss2kOfferDismissed = false
    private val ss2kFinder = com.madooroy.gregario.trainer.Ss2kFinder(app, { Timber.tag(LOG_TAG).i(it) },
        inUse = { h -> bikeChoice.kind == com.madooroy.gregario.trainer.BikeChoice.Kind.SS2K && bikeChoice.host == h && link.connected }) { found ->
        if (found == null) ss2kOfferDismissed = false
        ss2kFound = found
        // Set to SmartSpin2K: follow its address (the router may hand it a new one), between workouts only.
        val c = bikeChoice
        if (found != null && c.kind == com.madooroy.gregario.trainer.BikeChoice.Kind.SS2K && found.host != c.ss2kHost && canSwitchBike())
            setBike(c.copy(ss2kHost = found.host))
        else publishBike()
    }

    /** The panel's Bike line and the "SmartSpin2K found" question. */
    private fun publishBike() {
        val offer = bikeChoice.kind == com.madooroy.gregario.trainer.BikeChoice.Kind.GRUPETTO && ss2kFound != null &&
            !ss2kOfferDismissed && canSwitchBike()
        mutableState.update { it.copy(bike = bikeChoice, ss2kOffer = offer, bikeNote = null,
            bikeLink = if (link.connected) null else linkStatus()) }
    }

    /** Bikes change only between workouts, or on the Ready screen before anything is ridden. */
    fun canSwitchBike(): Boolean = session.let { it == null || (it.player.state == WorkoutPlayer.State.Ready && it.recorder.size == 0) }

    /** The panel's Switch pill: Grupetto ⇄ SmartSpin2K (the SS2K found on the Wi-Fi, else the last address). */
    fun switchBike(): Boolean {
        val c = bikeChoice
        if (c.kind == com.madooroy.gregario.trainer.BikeChoice.Kind.SS2K) return setBike(c.copy(kind = com.madooroy.gregario.trainer.BikeChoice.Kind.GRUPETTO))
        // Nothing to switch to: say so, and stay on Grupetto (review: a Grupetto-only rider was left with no bike).
        if (ss2kFound == null && !com.madooroy.gregario.trainer.BikeChoice.validHost(c.ss2kHost.trim())) {
            mutableState.update { it.copy(bikeNote = "No SmartSpin2K found on the Wi-Fi: switch it on, or type its address in Gregario") }
            return false
        }
        return useFoundSs2k()
    }

    /** "Use SmartSpin2K": ride through the SS2K found on the Wi-Fi (or the last address if it hasn't been found yet). */
    fun useFoundSs2k(): Boolean =
        setBike(bikeChoice.copy(kind = com.madooroy.gregario.trainer.BikeChoice.Kind.SS2K, ss2kHost = ss2kFound?.host ?: bikeChoice.ss2kHost))

    /** "Keep Grupetto": no more asking until the panel closes or the SS2K goes away. */
    fun keepGrupetto() { ss2kOfferDismissed = true; publishBike() }

    /** What the panel and settings show about the bike connection. */
    fun linkStatus(): String =
        if (bikeChoice.host == null) "Looking for ${bikeChoice.name}: ${bikeChoice.lookFor}" else link.describe()

    /**
     * Ride through Grupetto or a SmartSpin2K from now on. Refused (false) once a ride has started: a ride never changes
     * bikes halfway. On the Ready screen the chosen workout is set up again on the new bike (the ERG pre-load included).
     * With SmartSpin2K chosen, nothing is ever sent to Grupetto, so the Bike+ motor and SS2K never fight over the resistance.
     */
    fun setBike(b: com.madooroy.gregario.trainer.BikeChoice): Boolean {
        if (!canSwitchBike()) return false
        // SmartSpin2K chosen without a usable address: take the one found on the Wi-Fi (review: it said "type its
        // address" although one had been found).
        val found = ss2kFound
        settings.setBike(if (b.kind == com.madooroy.gregario.trainer.BikeChoice.Kind.SS2K && b.host == null && found != null)
            b.copy(ss2kHost = found.host) else b)
        val old = link
        bikeChoice = settings.bike.value
        link = newLink(bikeChoice)
        // A Ready workout holds the old connection (and may have pre-loaded ERG on it): set it up again on this one.
        val ready = session; val p = planned
        if (ready != null) { ready.releaseNow(); endRideLog(ready); session = null; planned = null }
        old.stop()                                   // hands back anything held, then closes
        reader?.stop(); reader = newReader(bikeChoice)
        if (attached && bikeChoice.host != null) link.start()
        if (attached) reader?.start()
        if (ready != null && p != null) {
            select(p)
            // select() can refuse (rider, FTP): then back to the list, never a Ready screen with no workout (review).
            if (session == null) mutableState.update { it.copy(workout = null, snap = null, stats = null, autoStartArmed = false) }
        }
        publishBike()
        return true
    }

    /** The newest bike numbers: from Grupetto directly when a SmartSpin2K does the ERG, else from the ERG connection. */
    private fun sample() = reader?.freshSample() ?: link.freshSample()
    /** ERG's state for this tick's ride-file second (target, resistance, writes since the last tick). */
    /**
     * The target recorded for this second: what ERG holds, or, while it holds nothing (the knob, ERG not engaged yet),
     * what the workout asks (Marc, Nov 25: a 0 there squashed intervals.icu's power-and-target chart; on a range step
     * the line now stays on the range's bottom). Phase 1/0 tells the two apart.
     */
    private fun ergNow(s: WorkoutSession) = link.takeErg().let { (target, level, writes) ->
        if (target > 0) RideRecorder.Erg(target, level, 1, writes)
        else RideRecorder.Erg(s.player.snapshot().targetWatts?.let { it + s.shiftOffset } ?: 0, level, 0, writes)
    }
    private var lastPedaling = false
    private var lastDataAt = 0L
    /**
     * Pedaling = a fresh bike sample (under 1.5 s old) with cadence above 20 rpm. A gap in the data (the connection
     * dropped) is not a stop: the last answer stands for up to [DATA_GAP_MS] (Nov 26: a Wi-Fi drop read as a stop, the
     * ride paused and needed a tap while the rider kept pedaling at 95 rpm).
     */
    private fun pedaling(): Boolean {
        val s = sample(); val now = SystemClock.elapsedRealtime()
        if (s != null) { lastPedaling = s.cadence > 20f; lastDataAt = now; return lastPedaling }
        return lastPedaling && now - lastDataAt < DATA_GAP_MS
    }
    private fun heartRate() = reader?.heartRate() ?: link.heartRate()
    private fun speedMph() = reader?.takeIf { it.freshSample() != null }?.speedMph() ?: link.speedMph()

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** The overlay added the panel: run the loop. */
    fun attach() {
        if (attached) return
        attached = true
        loadFiles()
        if (bikeChoice.host != null) link.start()   // SmartSpin2K without an address: nothing to connect to yet
        reader?.start()
        attachedAt = SystemClock.elapsedRealtime()
        ss2kFinder.start()
        publishBike()
        startLoop()
        updateDrop()
        // A ride left as a draft (app killed, tablet restarted) is queued now, and anything waiting is uploaded.
        scope.launch {
            val except = session?.let { rideId(it) }
            withContext(Dispatchers.IO) { settings.riders.value.forEach { r -> runCatching { queueFor(r.id).promoteDrafts(exceptId = except) } } }
            processUploads()
        }
    }

    /** The overlay is going away: end any workout, let go of the bike and stop the loop. */
    fun detach() {
        attached = false
        updateDrop()
        loop?.cancel(); loop = null
        liveJob?.cancel(); liveJob = null; live.clear()
        session?.let { s ->
            if (!s.releaseNow()) Timber.w("Gregario: could not hand back the bike on detach")
            // Don't lose a ride when the overlay closes: save it for upload (no RPE) if it is a minute or more.
            if (!state.value.rideSaved && s.recorder.seconds >= 60)
                runCatching { queueRide(s, null) }.getOrNull()?.let { job -> scope.launch { job.await(); processUploads() } }
            else if (!state.value.rideSaved) { val q = queue; val id = rideId(s); scope.launch(disk) { runCatching { q.deleteDraft(id) } } }
            if (s.recorder.size > 0) settings.lastRideEndAt = System.currentTimeMillis()   // "Who's riding?" asks again
            Unit
        }
        processUploads()
        endRideLog(session)
        session = null; planned = null
        mutableState.update { it.copy(workout = null, snap = null, stats = null, alert = null, autoStartArmed = false) }
        link.stop()   // hands the bike back (Reset, or neutral simulation on an SS2K), then closes DirCon
        reader?.stop()
        ss2kFinder.stop(); ss2kOfferDismissed = false
    }

    /** Refetch when the day changed since the last fetch (the panel calls this when it shows the list). */
    fun refreshIfStale() {
        val s = state.value
        if (!s.loading && (s.todayDate != today() || (s.today.isEmpty() && s.message == null))) refresh()
    }

    /** A failed fetch is tried again by itself (10 s, doubling to 5 min): at boot the panel can open before the Wi-Fi. */
    private var refreshRetry: kotlinx.coroutines.Job? = null
    private var refreshFailures = 0

    /** Fetch today's planned workouts and the ride FTP from intervals.icu. */
    fun refresh() {
        updateDrop()   // the Wi-Fi address may have changed since the panel opened (review N6)
        if (refreshJob?.isActive == true) return
        refreshRetry?.cancel(); refreshRetry = null
        val key = settings.icuCredential()
        if (key.isBlank()) {
            // No intervals.icu: workout files only, ridden at the FTP typed in the settings.
            val ftp = settings.manualFtp.value.takeIf { it in FTP_RANGE } ?: 0
            mutableState.update { it.copy(ftp = ftp, todayDate = today(), today = emptyList(),
                message = if (ftp == 0) "Connect intervals.icu (or set your FTP) in the Gregario app"
                          else "intervals.icu not connected: ride your workout files") }
            loadFiles(); return
        }
        val date = today()
        mutableState.update { it.copy(loading = true, message = null) }
        refreshJob = scope.launch {
            try {
                val (ftp, all) = withContext(Dispatchers.IO) {
                    val api = IcuApi(key)
                    (api.rideFtp() ?: settings.lastFtp) to api.plannedWorkouts(date)
                }
                refreshFailures = 0
                val list = all.filterNot { it.done }
                val done = all.filter { it.done }.joinToString(", ") { it.name }
                processUploads()
                val ftpOk = ftp in FTP_RANGE
                if (ftpOk) settings.lastFtp = ftp
                loadFiles(if (ftpOk) ftp else 0)
                mutableState.update {
                    it.copy(loading = false, today = list, todayDate = date, ftp = if (ftpOk) ftp else 0,
                        message = when {
                            !ftpOk -> if (ftp <= 0) "No ride FTP in intervals.icu" else "Ride FTP $ftp W looks wrong (expected ${FTP_RANGE.first}–${FTP_RANGE.last})"
                            list.isEmpty() && done.isNotEmpty() -> "Done today: $done ✓"
                            list.isEmpty() -> "Nothing planned today"
                            else -> null
                        })
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Gregario refresh failed")
                val json = settings.lastWorkoutJson
                val last = json?.let { runCatching { IcuParser.parseEvent(it) }.getOrNull() }?.takeIf { it.problem() == null }
                mutableState.update {
                    it.copy(loading = false, todayDate = date,
                        message = "Couldn't reach intervals.icu (${e.message})" + if (last != null) " · last workout available" else "",
                        today = if (last != null) listOf(PlannedWorkout("last", last.name, "", last, json)) else emptyList())
                }
                val wait = minOf(REFRESH_RETRY_MS shl minOf(refreshFailures, 5), REFRESH_RETRY_MAX_MS); refreshFailures++
                refreshRetry = scope.launch { kotlinx.coroutines.delay(wait); if (session == null) refresh() }
            }
        }
    }

    /** Only with two or more riders: true until someone says who is riding, after each ride, and after two idle hours. */
    fun needsRider(): Boolean =
        Riders.needsConfirm(settings.riders.value.size, settings.riderConfirmedAt, settings.lastRideEndAt, System.currentTimeMillis())

    /**
     * "Who's riding?": switch to [id] (or confirm the same rider) and load their workouts, FTP and Strava.
     * Refused while a workout is loaded, so a ride can't change hands.
     */
    fun switchRider(id: String): Boolean {
        if (session != null || state.value.workout != null) return false
        if (settings.riders.value.none { it.id == id }) return false
        val changed = id != settings.rider.value
        settings.setRider(id)
        settings.riderConfirmedAt = System.currentTimeMillis()
        if (changed) loadRider()
        mutableState.update { it.copy(message = if (it.message == PICK_RIDER) null else it.message, riderTick = it.riderTick + 1) }
        return true
    }

    /** The rider changed: drop everything of the last one's and load this one's workouts, FTP and uploads. */
    private fun loadRider() {
        refreshJob?.cancel(); refreshJob = null
        savedRideId = null
        mutableState.update { it.copy(ftp = settings.lastFtp, today = emptyList(), files = emptyList(), todayDate = null,
            message = null, loading = false, strava = null, uploadStatus = null, waitingUploads = null, ftpStatus = null) }
        loadFiles(); refresh(); processUploads()
    }

    /** Settings: remove a rider (not the first, not the one riding) with their settings, rides and workout files. */
    fun removeRider(id: String): Boolean {
        if (id == Riders.FIRST || session != null || state.value.workout != null) return false
        val wasCurrent = id == settings.rider.value
        settings.removeRider(id)   // switches to the first rider when it was the current one
        synchronized(stravaAccounts) { stravaAccounts.remove(id) }
        runCatching { java.io.File(app.filesDir, Riders.dirName("rides", id)).deleteRecursively() }
        runCatching { java.io.File(app.filesDir, Riders.dirName("workout-files", id)).deleteRecursively() }
        // Review R2: reload the first rider's workouts and FTP (the removed rider's stayed on the panel), and ask again.
        if (wasCurrent) { settings.riderConfirmedAt = 0; loadRider() }
        mutableState.update { it.copy(riderTick = it.riderTick + 1) }
        return true
    }

    /** Load a workout ready to start. For a few minutes, pedaling brings ERG to the first target; only Start starts it. */
    fun select(p: PlannedWorkout) {
        if (needsRider()) { mutableState.update { it.copy(message = PICK_RIDER) }; return }
        val ftp = state.value.ftp
        if (ftp !in FTP_RANGE) { mutableState.update { it.copy(message = "No usable FTP yet: refresh with intervals.icu first") }; return }
        p.problem?.let { why -> mutableState.update { it.copy(message = "${p.name}: $why") }; return }
        if (!p.id.startsWith(FILE_ID)) settings.lastWorkoutJson = p.json
        endRideLog(session)
        val s = WorkoutSession(p.workout, ftp, link)
        session = s; planned = p; draftAtSecs = 0.0
        runCatching { rideLog.start(rideLogDir, p.name) }.onFailure { Timber.w(it, "Gregario: ride log not started") }
        wallBase = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        armedUntil = SystemClock.elapsedRealtime() + AUTO_START_MS
        mutableState.update { it.copy(workout = p.workout, snap = s.player.snapshot(), stats = null, message = null, alert = null,
            rideSaved = false, uploadStatus = null, notice = null, rampActive = false, testResult = null, ftpStatus = null,
            // Nothing of the last ride: its power trace and start time showed on the next Ready screen (Nov 25).
            trace = s.trace, traceSize = 0, rideStartMs = null, shiftOffset = 0, riderControl = false) }
        startLoop()
        updateDrop()
        publishBike()
    }

    /**
     * A workout file opened with Gregario: kept under "Your workouts". Returns what to tell the rider.
     * Called from the import activity (any thread).
     */
    fun importWorkout(fileName: String, text: String): String = synchronized(filesLock) { addFile(fileName, text).also { loadFiles() } }

    /** Adding, removing and listing workout files happen one at a time, from any thread (review N7). */
    private val filesLock = Any()

    private fun addFile(fileName: String, text: String): String = try {
        val files = workoutFiles
        val replacing = files.has(fileName)
        val w = files.add(fileName, text, state.value.ftp.takeIf { it in FTP_RANGE } ?: settings.lastFtp)
        // A file with the same name replaces the old one: say so (review N2), so a changed workout is never a surprise.
        (if (replacing) "Replaced in Gregario: " else "Added to Gregario: ") + "${w.name} (${w.totalSeconds / 60} min)" +
            if (settings.riders.value.size > 1) " for ${settings.riderName}" else ""
    } catch (e: Exception) {
        "Gregario couldn't use $fileName: ${e.message}"
    }

    fun removeFile(p: PlannedWorkout) {
        if (!p.id.startsWith(FILE_ID)) return
        synchronized(filesLock) { workoutFiles.remove(p.id.removePrefix(FILE_ID)); loadFiles() }
    }

    /**
     * Workout files dropped in the inbox folder (no Open with needed, e.g. from a laptop):
     * adb push my.zwo /sdcard/Android/data/com.madooroy.gregario/files/workout-inbox/
     * Each is taken into Your workouts and removed from the inbox; one that can't be used is renamed *.rejected.
     */
    private fun takeInbox() {
        val inbox = app.getExternalFilesDir("workout-inbox") ?: return
        inbox.listFiles()?.filter { it.isFile && !it.name.endsWith(".rejected") }?.forEach { f ->
            val msg = runCatching { addFile(f.name, f.readText()) }.getOrElse { it.message ?: "?" }
            Timber.i("Gregario inbox: %s", msg)
            if (msg.startsWith("Added")) f.delete() else f.renameTo(java.io.File(inbox, f.name + ".rejected"))
        }
    }

    private fun loadFiles(ftp: Int = state.value.ftp): Unit = synchronized(filesLock) {
        runCatching { takeInbox() }
        val list = runCatching { workoutFiles.list(ftp.takeIf { it in FTP_RANGE } ?: settings.lastFtp) }.getOrDefault(emptyList())
        mutableState.update { st -> st.copy(files = list.map { e ->
            PlannedWorkout(FILE_ID + e.id, e.workout?.name ?: e.id, "", e.workout ?: com.madooroy.gregario.workout.Workout(e.id, emptyList()), "", e.problem)
        }) }
    }

    /** Finish screen: save the ride with its RPE (1-10, or null) and upload it to intervals.icu, then Strava. */
    fun saveRide(rpe: Int?) {
        val s = session ?: return
        if (state.value.rideSaved) return
        if (s.recorder.seconds < 30) {
            // Delete the finish draft too, or the next start would queue and upload it (review U2).
            val q = queue; val id = rideId(s); scope.launch(disk) { runCatching { q.deleteDraft(id) } }
            mutableState.update { it.copy(rideSaved = true, uploadStatus = "Too short to save") }; return
        }
        mutableState.update { it.copy(rideSaved = true, uploadStatus = "Saving…") }
        val job = runCatching { queueRide(s, rpe) }.getOrElse { e ->
            mutableState.update { it.copy(rideSaved = false, uploadStatus = "Couldn't save the ride: ${e.message}") }; return
        }
        savedRideId = rideId(s)
        scope.launch {
            val ok = job.await()
            mutableState.update { it.copy(uploadStatus = if (ok) "Uploading…" else "Couldn't save the ride") }
            if (ok) processUploads()
        }
    }

    /** Finish screen: don't keep this ride. */
    fun discardRide() {
        session?.let { s -> val q = queue; val id = rideId(s); scope.launch(disk) { runCatching { q.deleteDraft(id) } } }
        mutableState.update { it.copy(rideSaved = true, uploadStatus = "Not saved") }
    }

    private fun rideId(s: WorkoutSession): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(s.recorder.startedMs ?: (wallBase + SystemClock.elapsedRealtime())))

    /** The ride's name on intervals.icu and Strava: the planned workout's own (Marc, Nov 25), else one made from its steps. */
    private fun rideName(s: WorkoutSession): String = planned?.name?.trim()?.takeIf { it.isNotEmpty() } ?: s.player.workout.title()

    private fun description(s: WorkoutSession, rpe: Int?): String {
        val r = s.recorder; val t = r.totals()
        // Just the basics plus total output, no app name, no per-interval lines
        // (intervals.icu shows every interval as a lap). FTP tests keep their result lines.
        return listOfNotNull(
            when {
                bikeChoice.kind == com.madooroy.gregario.trainer.BikeChoice.Kind.SS2K -> "Ridden on a Peloton bike with a SmartSpin2K."
                s.noErgBike -> "Ridden on a Peloton bike, following the targets."
                else -> "Ridden on a Peloton bike through Grupetto."
            },
            "Total Output: ${t.kj} kJ",
            "NP ${t.np} W · IF ${"%.2f".format(Locale.US, t.intensityFactor)} · TSS ${Math.round(t.tss)} at FTP ${r.ftp} W" +
                if (s.player.intensityPct != 100) " · intensity ${s.player.intensityPct}%" else "",
            rpe?.let { "RPE $it/10" },
            s.testResult()?.lines()?.joinToString("\n")?.ifBlank { null }).joinToString("\n")
    }

    /** Build the ride file here; write it to this rider's queue on the ride-files thread, after any draft write. */
    private fun queueRide(s: WorkoutSession, rpe: Int?): kotlinx.coroutines.Deferred<Boolean> {
        val q = queue; val id = rideId(s); val fit = s.recorder.fit(); val name = rideName(s)
        val desc = description(s, rpe); val event = planned?.id?.takeIf { it != "last" }
        val steps = runCatching { s.recorder.icuIntervals() }.getOrDefault(emptyList())
        return scope.async(disk) { runCatching { q.add(id, fit, name, desc, event, rpe, steps) }.onFailure { Timber.w(it, "Gregario: ride not queued") }.isSuccess }
    }

    /** Write the ride so far as a draft (every minute, and at the finish): a killed app loses at most a minute. */
    private fun saveDraft(s: WorkoutSession) {
        if (s.recorder.size == 0) return
        draftAtSecs = s.recorder.seconds
        val fit = runCatching { s.recorder.fit() }.getOrNull() ?: return
        val id = rideId(s); val name = rideName(s); val desc = description(s, null)
        val event = planned?.id?.takeIf { it != "last" }; val q = queue
        val steps = runCatching { s.recorder.icuIntervals() }.getOrDefault(emptyList())
        scope.launch(disk) { runCatching { q.saveDraft(id, fit, name, desc, event, steps) } }
    }

    /**
     * Upload whatever is waiting (also at start, after a ride, and at each refresh). If a run is already going,
     * one more follows it, so a ride saved meanwhile goes up at once. The finish screen shows the ride just saved.
     */
    fun processUploads() {
        if (uploadJob?.isActive == true) { uploadAgain = true; return }
        uploadJob = scope.launch {
            do {
                uploadAgain = false
                // Every rider's waiting rides go up with that rider's own intervals.icu key and Strava sign-in.
                val current = settings.rider.value
                val rides = withContext(Dispatchers.IO) {
                    var mineNow = emptyList<UploadQueue.Ride>()
                    for (r in settings.riders.value) {
                        val store = settings.storeFor(r.id)
                        val strava = stravaFor(r.id)
                        val done = runCatching { queueFor(r.id).process(store.icuCredential.takeIf { it.isNotBlank() }?.let { IcuUploader(it) },
                            strava.takeIf { it.configured }) }.getOrDefault(emptyList())
                        if (r.id == current) mineNow = done
                    }
                    mineNow
                }
                val mine = savedRideId?.let { id -> rides.firstOrNull { it.id == id } }
                val waiting = rides.filter { it.waiting }
                mutableState.update { st ->
                    st.copy(
                        uploadStatus = mine?.let { r ->
                            listOfNotNull(
                                if (r.icu == UploadQueue.NO_ICU) null else if (r.icuDone) "intervals.icu ✓" else if (r.icuFailed) "intervals.icu ✗" else "intervals.icu waiting",
                                if (r.stravaDone) "Strava ✓" else if (r.stravaFailed) null else "Strava waiting",
                                r.error).joinToString(" · ")
                        } ?: st.uploadStatus,
                        waitingUploads = if (waiting.isEmpty()) null else
                            "${waiting.size} ride${if (waiting.size > 1) "s" else ""} waiting to upload" +
                                (waiting.last().error?.let { " · $it" } ?: ""))
                }
            } while (uploadAgain)
        }
    }

    /** Settings: connect Strava with the client id and secret and the pasted localhost address. */
    fun connectStrava(clientId: String, clientSecret: String, pasted: String) {
        mutableState.update { it.copy(strava = "Connecting…") }
        val acct = stravaAccount   // this rider's, even if someone switches while it connects
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { acct.connect(clientId, clientSecret, pasted); "Connected as " + acct.athleteName() }
                    .getOrElse { "Not connected: ${it.message}" }
            }
            mutableState.update { it.copy(strava = msg) }
        }
    }

    fun checkStrava() {
        val acct = stravaAccount
        if (!acct.configured) { mutableState.update { it.copy(strava = "Not connected") }; return }
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { "Connected as " + acct.athleteName() }.getOrElse { "Problem: ${it.message}" }
            }
            mutableState.update { it.copy(strava = msg) }
        }
    }

    /** "Connect intervals.icu" finished: save the sign-in for the rider who started it, then load workouts and send waiting rides. */
    fun icuSignedIn(grant: com.madooroy.gregario.icu.IcuOAuth.Grant, riderId: String) {
        settings.setIcuToken(grant.token, grant.athlete, riderId)
        if (riderId == settings.rider.value) refresh()
        processUploads()
    }
    /** Forget the sign-in on this tablet (the rider can also revoke Gregario at intervals.icu › Settings › Apps). */
    fun disconnectIcu() { settings.setIcuToken("", ""); refresh() }

    fun forgetStrava() { stravaAccount.forget(); mutableState.update { it.copy(strava = "Not connected") } }

    // Each button acts on the bike at once (one session tick), not at the next loop round up to a second later.
    fun start() { session?.start(); tickNow() }
    fun pause() { session?.player?.pause(); tickNow() }
    fun resume() { session?.resume(); tickNow() }
    fun skip() { session?.player?.skip(); tickNow() }
    /** "ERG on": the rider had the knob; ERG takes this step back now. */
    fun ergBack() { session?.ergBack(); tickNow() }
    fun extendWarmup() { session?.player?.extendWarmup(60); tickNow() }
    fun changeIntensity(d: Int) { session?.player?.changeIntensity(d); tickNow() }
    fun end() { session?.player?.end(); tickNow() }
    /** Ramp test: the rider is done; go to the cooldown. */
    fun doneRamp() { session?.endRamp(); tickNow() }

    /**
     * After a test: set the indoor ride FTP in intervals.icu to [watts] (the rider tapped Set FTP and confirmed).
     * Later workouts use it; this ride keeps the FTP it was ridden at.
     */
    fun setIndoorFtp(watts: Int) {
        val key = settings.icuCredential(); val rider = settings.rider.value
        if (key.isBlank()) { mutableState.update { it.copy(ftpStatus = "intervals.icu not connected") }; return }
        if (watts !in FTP_RANGE) { mutableState.update { it.copy(ftpStatus = "$watts W is outside ${FTP_RANGE.first}–${FTP_RANGE.last} W") }; return }
        mutableState.update { it.copy(ftpStatus = "Setting indoor FTP to $watts W…") }
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { IcuApi(key).setIndoorFtp(watts) }.fold(
                    { "Indoor FTP is now $it W in intervals.icu" }, { "Couldn't set FTP: ${it.message}" })
            }
            // Saved for the rider who asked; shown only if they are still the rider (review R3).
            if (msg.startsWith("Indoor")) {
                settings.setLastFtpFor(rider, watts)
                if (settings.rider.value == rider) mutableState.update { it.copy(ftp = watts) }
            }
            mutableState.update { it.copy(ftpStatus = msg) }
        }
    }

    private fun tickNow() {
        // After the loop failed (review): the panel's buttons must not tick a broken session and crash the app.
        if (loop?.isActive != true) return
        val s = session ?: return
        val now = SystemClock.elapsedRealtime()
        val smp = sample()
        s.tick(now, pedaling(), smp?.power ?: 0f, smp?.cadence ?: 0f, heartRate()?.toFloat(), wallBase + now, speedMph(), smp?.resistance, ergNow(s),
            dataOk = smp != null)
        afterTick(s)
        publish()
    }

    /** Drafts: every minute of riding, and once at the finish. */
    private fun afterTick(s: WorkoutSession) {
        val finished = s.player.state == WorkoutPlayer.State.Finished
        if (!state.value.rideSaved && s.recorder.size > 0 &&
            (s.recorder.seconds - draftAtSecs >= 60 || (finished && draftAtSecs != s.recorder.seconds))) saveDraft(s)
    }

    /** Leave the selected or finished workout and go back to today's list (also the Ready screen's Cancel). */
    fun close() {
        if ((session?.recorder?.size ?: 0) > 0) settings.lastRideEndAt = System.currentTimeMillis()   // ask "who's riding?" again
        session?.releaseNow()
        endRideLog(session)
        session = null; planned = null
        mutableState.update { it.copy(workout = null, snap = null, stats = null, alert = null, autoStartArmed = false,
            rideSaved = false, notice = null, rampActive = false, testResult = null, ftpStatus = null,
            trace = emptyList(), traceSize = 0, rideStartMs = null, shiftOffset = 0, riderControl = false) }
        updateDrop()
        publishBike()
    }

    /**
     * "Send to the bike": the small web page for adding workout files from a phone or computer on the same Wi-Fi.
     * It runs only while the panel is up and showing the workout list (not during a ride), and if it is switched on.
     */
    private val drop = com.madooroy.gregario.drop.WorkoutDrop { name, text ->
        importWorkout(name, text).substringBefore(" for ")   // the reply goes to the network: no rider names
    }

    fun updateDrop() {
        val want = attached && settings.dropEnabled.value && state.value.workout == null
        scope.launch(disk) {   // one thread, so start/stop calls run in the order they were made
            // Only on the tablet's private Wi-Fi address, and not at all without one (review N3).
            val ip = if (want) wifiAddress() else null
            val address = if (ip != null && drop.start(ip)) "http://${ip.hostAddress}:${drop.port}" else { drop.stop(); null }
            mutableState.update { it.copy(dropAddress = address) }
        }
    }

    /** The tablet's private IPv4 address, preferring Wi-Fi (wlan) over other interfaces, for "Send to the bike". */
    private fun wifiAddress(): java.net.Inet4Address? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
    }.getOrNull()

    private fun publish() {
        val s = session ?: return
        updateLive()
        val armed = s.player.state == WorkoutPlayer.State.Ready && SystemClock.elapsedRealtime() < armedUntil
        mutableState.update {
            it.copy(snap = s.player.snapshot(), stats = s.stats(), livePower = live.power, liveCadence = live.cadence, liveResistance = live.resistance,
                liveHeartRate = heartRate(), workout = s.player.workout,
                alert = s.alert, autoStartArmed = armed, shiftOffset = s.shiftOffset, inWarmup = s.player.inWarmup(),
                trace = s.trace, traceSize = s.trace.size, workoutFtp = s.player.ftp, riderControl = s.riderStep >= 0, notice = s.notice, rampActive = s.player.inRamp(),
                rideStartMs = s.recorder.startedMs,
                testResult = if (s.player.state == WorkoutPlayer.State.Finished) s.testResult() else null)
        }
    }

    /** Four times a second while a workout is open: only the live numbers change, the workout is not ticked. */
    private fun startLiveLoop() {
        if (!attached || liveJob?.isActive == true) return
        liveJob = scope.launch {
            while (isActive) {
                updateLive()
                val note = if (link.connected) null else linkStatus()
                // Lost mid-workout (it was connected): the panel's amber border until it is back.
                val lost = if (session != null && !link.connected && link.lostAt != null) "Lost the ${bikeChoice.name} · reconnecting" else null
                val hint = reader?.let { rd ->
                    if (!rd.connected && SystemClock.elapsedRealtime() - attachedAt > HINT_GRACE_MS)
                        "Switch DirCon on in Grupetto: power then comes straight from Grupetto, a second sooner" else null
                }
                mutableState.update {
                    val st0 = if (it.bikeLink == note) it else it.copy(bikeLink = note)
                    val st = if (st0.linkLost == lost && st0.powerHint == hint) st0 else st0.copy(linkLost = lost, powerHint = hint)
                    if (session == null || (st.livePower == live.power && st.liveCadence == live.cadence && st.liveResistance == live.resistance)) st
                    else st.copy(livePower = live.power, liveCadence = live.cadence, liveResistance = live.resistance)
                }
                delay(LIVE_REFRESH_MS)
            }
        }
    }

    private fun updateLive() {
        val smp = sample()
        live.offer(smp?.timestamp, smp?.power, smp?.cadence, smp?.resistance)
    }

    private fun startLoop() {
        startLiveLoop()
        if (!attached || loop?.isActive == true) return
        loop = scope.launch {
            try {
                while (isActive) {
                    val began = SystemClock.elapsedRealtime()
                    val s = session
                    if (s != null) {
                        s.player.climbLeadSeconds = settings.climbLead.value.toDouble()
                        // Never starts on its own: before Start, pedaling only brings ERG to the first target.
                        s.player.preload = s.player.state == WorkoutPlayer.State.Ready && SystemClock.elapsedRealtime() < armedUntil
                        val smp = sample()
                        val now = SystemClock.elapsedRealtime()
                        s.tick(now, pedaling(), smp?.power ?: 0f, smp?.cadence ?: 0f,
                            heartRate()?.toFloat(), wallBase + now, speedMph(), smp?.resistance, ergNow(s), dataOk = smp != null)
                        afterTick(s)
                        publish()
                    }
                    // No workout open: a panel left up overnight (or opened at boot, small) fetches the new day by itself.
                    if (session == null) refreshIfStale()
                    // Once a second, but exactly on time for the next target change (a step boundary or a climb's
                    // lead), instead of up to a second late; the work done this round is taken off the wait.
                    val untilChange = session?.player?.msToNextChange()?.plus(15)
                    val wait = minOf(1000 - (SystemClock.elapsedRealtime() - began), untilChange ?: Long.MAX_VALUE)
                    delay(wait.coerceIn(20, 1000))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "Gregario loop failed")
                session?.releaseNow()
                mutableState.update { it.copy(alert = "Gregario stopped: ${e.javaClass.simpleName}${e.message?.let { m -> " ($m)" } ?: ""} · bike released") }
            }
        }
    }

    companion object {
        val FTP_RANGE = 50..600
        /** Lines kept in the ride log besides warnings: the bike link (connects, ERG, shifts, hand-backs) and the SS2K finder. */
        const val LOG_TAG = "Bike"
        private const val REFRESH_RETRY_MS = 10_000L
        private const val REFRESH_RETRY_MAX_MS = 5 * 60_000L
        private const val AUTO_START_MS = 10 * 60_000L
        /** How often the live POWER / CADENCE / RES numbers refresh (the bike is read every 200 ms). */
        private const val LIVE_REFRESH_MS = 250L
        /** A gap in the bike data shorter than this is not a stop (see [pedaling]). */
        private const val DATA_GAP_MS = 60_000L
        /** How long the Grupetto reader may take to connect before the DirCon hint shows. */
        private const val HINT_GRACE_MS = 10_000L
        /** PlannedWorkout id prefix for workout files. */
        private const val FILE_ID = "file:"
        private const val PICK_RIDER = "Who's riding? Pick a rider first"

        @Volatile private var instance: Gregario? = null
        fun get(app: android.app.Application): Gregario =
            instance ?: synchronized(this) { instance ?: Gregario(app).also { instance = it } }
    }
}
