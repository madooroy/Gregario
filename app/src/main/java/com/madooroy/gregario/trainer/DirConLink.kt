package com.madooroy.gregario.trainer

import com.madooroy.gregario.session.BikeStatus
import com.madooroy.gregario.session.ErgSink
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The newest bike numbers: power (W), cadence (rpm), resistance (Peloton 0–100), when they arrived ([DirConLink] clock, ms). */
/** One Indoor Bike Data reading: watts, rpm, Peloton resistance 0-100, and when it arrived ([DirConLink]'s clock). */
data class BikeSample(val timestamp: Long, val power: Float, val cadence: Float, val resistance: Int)

/**
 * Gregario's connection to the bike: Grupetto's DirCon server on this tablet (127.0.0.1:8081), spoken as a
 * standard FTMS trainer, the way Zwift talks to it. Grupetto keeps doing the actual ERG on the Bike+ motor.
 *
 * - Data: Indoor Bike Data (power, cadence, resistance, speed) and, when Grupetto shares one, heart rate.
 * - ERG: Request Control, then Set Target Power; Reset hands the bike back. Closing the connection also hands it
 *   back (Grupetto lets go of a client that leaves).
 * - The knob: Grupetto drops our control when the rider turns it and sends Fitness Machine Status 0xFF (control
 *   permission lost). [status] reports that as a knob turn; the next target asks for control again.
 * - Shifts: a SmartSpin2K stays in ERG and moves its own target, and (firmware 26.11.26-test on) says so with Fitness
 *   Machine Status 0x08 (target power changed). A target we didn't send becomes the held target, so [status] reports
 *   it and the session takes it as the rider's shift for the rest of the step.
 *
 * Plain JVM (sockets, a reader thread and a control thread), so it is unit-tested without Android. [setErg] and
 * [release] only record what is wanted and return at once: they are called on Android's main thread, where network
 * use throws (Nov 24 ride: "Gregario stopped: null"). The control thread sends it, waits for Grupetto's answer and
 * retries while Grupetto isn't ready; if it can't get the target through, [status] says ERG is off and why.
 */
class DirConLink(
    private val host: String = "127.0.0.1",
    private val port: Int = PORT,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
    private val retryMs: Long = 1_000,
    private val replyTimeoutMs: Long = 500,
    /** Who answers on [host]: "Grupetto", or "SmartSpin2K" (same DirCon and FTMS, over Wi-Fi), for the panel's words. */
    private val name: String = "Grupetto",
    /** After "Looking for <name>: " while it can't be reached. */
    private val lookFor: String = "open Grupetto and switch DirCon on",
    /**
     * What hands the bike back. Grupetto: Reset. A SmartSpin2K only acknowledges Reset and keeps holding the last target
     * (its firmware sets no mode on Reset), so it gets neutral simulation instead, as SS2K's own app sends at a
     * workout's end: [BikeChoice.handBack].
     */
    private val handBackCommand: ByteArray = byteArrayOf(OP_RESET.toByte()),
    /**
     * The bike keeps holding our last target when the connection drops (a SmartSpin2K does; Grupetto lets go of a client
     * that leaves). Then a hand-back stays owed across reconnects and is sent as soon as the connection is back.
     */
    private val holdsAfterDisconnect: Boolean = false,
    /** See [ErgSink.holdsThroughStops]: a SmartSpin2K. */
    override val holdsThroughStops: Boolean = false,
    /** The bike's FTMS "target changed" (0x08) can be the rider's shift (a SmartSpin2K); otherwise it is ignored. */
    private val followsShifts: Boolean = false,
    /**
     * Bike data only: no control is ever requested or sent. Used to read power from Grupetto on this tablet while a
     * SmartSpin2K does the ERG (Grupetto is the bike's own reading; the SS2K only relays it, a second later).
     */
    private val readOnly: Boolean = false,
    /** A dead connection is noticed when nothing arrives for this long (DirCon servers send bike data every second). */
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    /** A hand-back owed from an earlier run of Gregario (it closed while the SS2K couldn't be reached): sent on connect. */
    owedAtStart: Boolean = false,
    /** Called when a hand-back becomes owed or is settled, so it can be kept across a restart of Gregario. */
    private val onOwed: (Boolean) -> Unit = {},
) : ErgSink {

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null
    @Volatile private var controlThread: Thread? = null
    /** What the workout wants: watts, or null to hand the bike back. Written by [setErg]/[release]. */
    @Volatile private var desired: Int? = null
    /** What Grupetto last accepted from us (null: nothing held). Control thread only. */
    @Volatile private var applied: Int? = null
    private val wake = Object()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private val writeLock = Any()
    private var seq = 0

    /** Connected to Grupetto's DirCon now. */
    @Volatile var connected = false; private set
    /** When the connection was last lost while running ([clock]), or null while connected or never connected. */
    @Volatile var lostAt: Long? = null; private set
    /** Counts connections: a change tells the session it is a new connection even if no tick saw it down. */
    @Volatile private var generation = 0

    // Bike data, written by the reader thread.
    @Volatile private var sample: BikeSample? = null
    @Volatile private var speedKmh: Float? = null
    @Volatile private var hr: Int? = null
    @Volatile private var hrAt = 0L

    // Control, as Grupetto last answered it.
    @Volatile private var haveControl = false
    @Volatile private var ergOn = false
    @Volatile private var targetWatts = 0
    @Volatile private var knob = false
    /** The bike answered that it can't do ERG at all (Grupetto on a Peloton Bike, or with Bike+ ERG off). */
    @Volatile private var noErg = false
    @Volatile private var externalAt: Long? = null
    @Volatile private var message = "Looking for $name"
    @Volatile private var writes = 0
    /** Targets written in the last [ECHO_MS], with when: the bike's "target changed" echo of any of them is not a shift. */
    private val recentSent = ArrayDeque<Pair<Int, Long>>()
    /** A Set Target Power is on its way or awaiting its answer. */
    @Volatile private var inFlight = false
    /** Guards [desired]/[applied]/[targetWatts] set by the session against a shift taken on the reader thread. */
    private val stateLock = Any()
    /**
     * A target went to the bike and has not been handed back yet (review: an SS2K was left holding ERG after a reconnect,
     * after failed writes, or when the hand-back raced the shutdown). Set when a target is sent; cleared only when the
     * hand-back is answered, another app holds the bike, or the bike let go by itself (knob; Grupetto on disconnect).
     */
    @Volatile private var owed = owedAtStart
        set(v) { if (field != v) { field = v; if (holdsAfterDisconnect) runCatching { onOwed(v) } } }
    /** [stop] is handing the bike back: no reconnecting, and the reader keeps reading so the answer arrives. */
    @Volatile private var stopping = false
    /** [start] was called while a stop was finishing: start again once it has. */
    @Volatile private var startAfterStop = false
    private val replies = LinkedBlockingQueue<IntArray>()   // control point answers: [op, result]
    private val controlLock = Any()

    fun start() {
        if (stopping) { startAfterStop = true; return }
        if (running) return
        lostAt = null   // a drop from an earlier run is not this run's (review: a fresh Ready screen said "Lost")
        // No address (a SmartSpin2K not found or typed yet): nothing to connect to. An empty host would resolve to this
        // tablet, which is Grupetto (review).
        if (host.isBlank()) { message = "No address"; return }
        running = true
        thread = Thread(::run, "gregario-dircon").apply { isDaemon = true; start() }
        if (!readOnly) controlThread = Thread(::controlLoop, "gregario-dircon-control").apply { isDaemon = true; start() }
    }

    /** Hand the bike back (if we hold ERG) and close the connection. Returns at once; the work runs off this thread. */
    fun stop() {
        startAfterStop = false
        lostAt = null
        if (!running || stopping) return
        // Held, owed, or on its way (a target sent but not yet answered): hand it back either way.
        val held = connected && (applied != null || haveControl || desired != null || owed)
        desired = null
        // The reader keeps running until the hand-back is answered: it used to stop on the next bike message and close the
        // socket before the hand-back went out (review: 2 of 450 stops lost it).
        stopping = true
        val reader = thread; val control = controlThread
        Thread({
            control?.interrupt(); runCatching { control?.join(1_000) }
            if (held) runCatching { synchronized(controlLock) { handBack() } }
            running = false
            thread = null; controlThread = null
            closeSocket(); reader?.interrupt(); runCatching { reader?.join(1_000) }
            stopping = false
            if (startAfterStop) { startAfterStop = false; start() }
        }, "gregario-dircon-stop").apply { isDaemon = true; start() }
    }

    /** The target the workout wants held now (null: none), for tests. */
    internal fun wanted(): Int? = desired

    /** The newest bike numbers, if they arrived in the last 1.5 s. */
    fun freshSample(): BikeSample? = sample?.takeIf { clock() - it.timestamp <= FRESH_MS }

    /** Speed from Grupetto (mph), for the ride file. */
    fun speedMph(): Float? = speedKmh?.let { it / 1.609344f }

    /** Heart rate from Grupetto (its Bluetooth strap), if it arrived in the last 5 s. */
    fun heartRate(): Int? = hr?.takeIf { clock() - hrAt <= HR_FRESH_MS && it > 0 }

    /** What ERG did this second, for the ride file (target, resistance, writes since the last call). */
    fun takeErg(): Triple<Int, Int, Int> { val w = writes; writes = 0; return Triple(if (ergOn) targetWatts else 0, sample?.resistance ?: 0, w) }

    /** For the panel and the settings screen. */
    fun describe(): String = when {
        !running -> "Not started"
        !connected -> "Looking for $name: $lookFor"
        else -> "Connected to $name · $message"
    }

    // ---- ErgSink ----

    override fun setErg(watts: Int): Boolean {
        if (readOnly) return false
        if (!connected) {
            // A bike that holds its target through a drop (SmartSpin2K): remember the newest target, so the reconnect
            // restores it at once instead of handing back and taking control again (a jolt mid-interval).
            if (holdsAfterDisconnect && desired != null) synchronized(stateLock) { desired = watts.coerceIn(0, MAX_WATTS) }
            message = "$name not connected"; return false
        }
        externalAt?.let { if (clock() - it < EXTERNAL_MS) return false }
        if (noErg) {
            // Retried once a minute: the rider may switch Bike+ ERG on in Grupetto mid-ride (review: noErg was sticky).
            if (clock() - noErgAt < NO_ERG_RETRY_MS) return false
            noErg = false
        }
        val w = watts.coerceIn(0, MAX_WATTS)
        // Shown as held at once (the session checks status() next tick); the control thread sends it.
        // [fails] is not reset here: a ramp sends a new target every second, which hid failing writes (review).
        synchronized(stateLock) { desired = w; ergOn = true; targetWatts = w; knob = false }
        message = "ERG $w W"
        synchronized(wake) { wake.notifyAll() }
        return true
    }

    override fun release(): Boolean {
        synchronized(stateLock) { desired = null; ergOn = false; targetWatts = 0 }
        synchronized(wake) { wake.notifyAll() }
        return true
    }

    /** Failed tries at the current target (Grupetto not ready, no answer); after [MAX_FAILS] ERG is reported off. */
    @Volatile private var fails = 0

    /** Sends what [desired] says whenever it differs from what Grupetto holds, retrying once a second. */
    private fun controlLoop() {
        while (running && !stopping) {
            val want = desired
            if (connected && (want != applied || (want == null && owed))) try {
                synchronized(controlLock) { if (want == null) handBack() else apply(want) }
            } catch (e: Exception) {
                log("DirCon control: ${e.javaClass.simpleName} ${e.message}")
            }
            val retry = connected && (desired != applied || (desired == null && owed))
            synchronized(wake) {
                if (desired == want || retry) try { wake.wait(if (retry) RETRY_MS else 250) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun apply(w: Int) {
        if (!haveControl) {
            when (val r = control(byteArrayOf(OP_REQUEST_CONTROL.toByte()))) {
                RESULT_SUCCESS -> { haveControl = true; externalAt = null }
                RESULT_NOT_PERMITTED -> { refused("Another app is controlling the bike"); return }
                RESULT_NOT_SUPPORTED -> { if (!followsShifts) cannotErg() else failed(resultText(r, name)); return }
                else -> { failed(if (r == null) "No answer from $name" else resultText(r, name)); return }
            }
        }
        if (desired != w) return   // changed while we waited: the next pass sends the new one
        owed = true                // from here the bike may hold it, answered or not
        synchronized(stateLock) { recentSent.addLast(w to clock()); inFlight = true }
        val r = try { control(byteArrayOf(OP_SET_TARGET_POWER.toByte(), (w and 0xff).toByte(), (w shr 8 and 0xff).toByte())) }
            finally { inFlight = false }
        when (r) {
            RESULT_SUCCESS -> { synchronized(stateLock) { applied = w }; writes++; fails = 0; message = "ERG $w W" }
            RESULT_NOT_PERMITTED -> { haveControl = false; failed("$name refused control") }
            // Grupetto: no ERG on this bike. (A SmartSpin2K answers the same while it has no power reading: retried.)
            RESULT_NOT_SUPPORTED -> if (!followsShifts) cannotErg() else failed(resultText(r, name))
            else -> failed(if (r == null) "No answer from $name" else resultText(r, name))
        }
    }

    private fun handBack() {
        if (haveControl || owed) {
            // Owed after a reconnect: control first (a new connection starts without it), then the hand-back.
            if (!haveControl) when (val r = control(byteArrayOf(OP_REQUEST_CONTROL.toByte()))) {
                RESULT_SUCCESS -> haveControl = true
                RESULT_NOT_PERMITTED -> { owed = false; applied = null; message = "Another app is controlling the bike"; return }
                else -> { message = if (r == null) "No answer from $name" else resultText(r, name); return }
            }
            val r = control(handBackCommand)
            if (r != RESULT_SUCCESS && r != RESULT_NOT_PERMITTED) { message = if (r == null) "No answer from $name" else resultText(r, name); return }
            message = "Bike handed back"
        }
        haveControl = false; applied = null; owed = false
    }

    /** Another app holds the bike: report it (the session pauses) and stop trying until it asks again. */
    private fun refused(why: String) {
        externalAt = clock(); message = why; owed = false   // the bike is theirs now
        desired = null; ergOn = false; targetWatts = 0; applied = null
    }

    /** The bike can't do ERG: stop asking for the rest of this connection; the session rides on without it. */
    @Volatile private var noErgAt = 0L
    private fun cannotErg() {
        if (!noErg) log("DirCon: $name can't do ERG on this bike: targets are followed with the knob")
        noErg = true; noErgAt = clock(); owed = false; message = resultText(RESULT_NOT_SUPPORTED, name)
        synchronized(stateLock) { desired = null; ergOn = false; targetWatts = 0; applied = null }
    }

    /** The target didn't go through: keep trying; after [MAX_FAILS] tries report ERG off so the session pauses. */
    private fun failed(why: String) {
        message = why
        if (++fails >= MAX_FAILS) { desired = null; ergOn = false; targetWatts = 0; applied = null; fails = 0 }
    }

    override fun status(): BikeStatus {
        val external = externalAt?.takeIf { clock() - it < EXTERNAL_MS }?.let { "Another app" }
        return BikeStatus(erg = connected && ergOn, targetWatts = targetWatts, externalControl = external,
            message = if (connected) message else "$name not connected", knob = knob, noErg = noErg, connected = connected,
            connection = generation)
    }

    // ---- connection ----

    private fun run() {
        while (running && !stopping) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 1_500)
                s.tcpNoDelay = true
                // Nothing for [readTimeoutMs] = the connection is dead (Nov 26: an SS2K's Wi-Fi dropped and came back
                // with the socket silently dead; it took 85 s and a reset to notice). Bike data arrives every second.
                s.soTimeout = readTimeoutMs
                socket = s; out = s.getOutputStream()
                haveControl = false; ergOn = false; knob = false; noErg = false; applied = null; replies.clear()
                if (!holdsAfterDisconnect) desired = null   // an SS2K keeps the wanted target: restored on this connection
                for (c in if (readOnly) listOf(INDOOR_BIKE_DATA, HEART_RATE) else listOf(INDOOR_BIKE_DATA, HEART_RATE, MACHINE_STATUS, CONTROL_POINT))
                    send(MSG_ENABLE_NOTIFICATIONS, uuidBytes(c) + byteArrayOf(1))
                fails = 0
                generation++
                connected = true; lostAt = null
                message = "ready"
                log("DirCon connected to $host:$port")
                read(DataInputStream(s.getInputStream()))
            } catch (e: IOException) {
                if (running && connected) log("DirCon connection lost: ${e.message}")
            } finally {
                if (connected) lostAt = clock()
                connected = false
                haveControl = false; ergOn = false; targetWatts = 0; applied = null
                if (!holdsAfterDisconnect) desired = null
                if (!holdsAfterDisconnect) owed = false   // Grupetto lets go of a client that leaves; an SS2K doesn't
                closeSocket()
            }
            if (running) try { Thread.sleep(retryMs) } catch (_: InterruptedException) { }
        }
    }

    private fun read(input: DataInputStream) {
        val header = ByteArray(6)
        while (running) {
            input.readFully(header)
            val id = header[1].toInt() and 0xff
            val code = header[3].toInt() and 0xff
            val length = ((header[4].toInt() and 0xff) shl 8) or (header[5].toInt() and 0xff)
            val payload = ByteArray(length)
            input.readFully(payload)
            when (id) {
                MSG_NOTIFICATION -> if (length >= 16) notification(uuidAt(payload), payload.copyOfRange(16, length))
                MSG_WRITE -> if (code != 0) replies.offer(intArrayOf(-1, RESULT_FAILED))   // the write itself was refused
            }
        }
    }

    private fun notification(uuid: UUID, data: ByteArray) {
        when (uuid) {
            INDOOR_BIKE_DATA -> indoorBikeData(data)
            HEART_RATE -> if (data.isNotEmpty()) {
                val v = if (data[0].toInt() and 1 != 0 && data.size >= 3) u16(data, 1) else if (data.size >= 2) data[1].toInt() and 0xff else 0
                hr = v; hrAt = clock()
            }
            MACHINE_STATUS -> if (data.size >= 3 && (data[0].toInt() and 0xff) == STATUS_TARGET_POWER_CHANGED) {
                if (followsShifts) shift(s16(data, 1))
            } else if (data.isNotEmpty() && (data[0].toInt() and 0xff) == STATUS_CONTROL_PERMISSION_LOST) {
                // Grupetto stopped ERG and let go of us: the knob (or a command the bike didn't take).
                if (ergOn) knob = true
                haveControl = false; ergOn = false; targetWatts = 0
                desired = null; applied = null; owed = false   // the bike let go by itself
                message = "Resistance changed manually"
                log("DirCon: control permission lost (knob)")
            }
            CONTROL_POINT -> if (data.size >= 3 && (data[0].toInt() and 0xff) == 0x80)
                replies.offer(intArrayOf(data[1].toInt() and 0xff, data[2].toInt() and 0xff))
        }
    }

    /**
     * The bike's target moved (FTMS 0x08). Our own Set Target Power is echoed this way too, possibly late: only a target
     * we haven't sent in the last [ECHO_MS], arriving while no write of ours is pending, is the rider's shift. It becomes
     * what we hold, so nothing is sent to undo it. A shift that crosses a step change's write is dropped: the step wins.
     */
    private fun shift(w: Int) = synchronized(stateLock) {
        val now = clock()
        while (recentSent.isNotEmpty() && now - recentSent.first().second > ECHO_MS) recentSent.removeFirst()
        val pending = inFlight || desired != applied
        if (ergOn && w > 0 && !pending && w != targetWatts && recentSent.none { it.first == w }) {
            targetWatts = w; desired = w; applied = w
            // Echoes of our earlier writes came before this announcement on the same ordered stream: forget them, so
            // a quick shift back to the step's target is a shift, not an echo (review: it was dropped).
            recentSent.clear()
            recentSent.addLast(w to now)
            message = "Shift: $w W"
            log("DirCon: target changed on the bike (shift) to $w W")
        }
    }

    /** FTMS Indoor Bike Data: the fields Grupetto sends, in spec order. */
    private fun indoorBikeData(d: ByteArray) {
        if (d.size < 2) return
        val flags = u16(d, 0)
        var i = 2
        var speed: Float? = null; var cadence: Float? = null; var resistance: Int? = null; var power: Float? = null
        fun has(n: Int) = i + n <= d.size
        if (flags and 0x0001 == 0) { if (!has(2)) return; speed = u16(d, i) / 100f; i += 2 }
        if (flags and 0x0002 != 0) i += 2
        if (flags and 0x0004 != 0) { if (!has(2)) return; cadence = u16(d, i) / 2f; i += 2 }
        if (flags and 0x0008 != 0) i += 2
        if (flags and 0x0010 != 0) i += 3
        if (flags and 0x0020 != 0) { if (!has(2)) return; resistance = s16(d, i); i += 2 }
        if (flags and 0x0040 != 0) { if (!has(2)) return; power = s16(d, i).toFloat(); i += 2 }
        if (flags and 0x0080 != 0) i += 2
        if (flags and 0x0100 != 0) i += 5
        if (flags and 0x0200 != 0 && has(1)) { val v = d[i].toInt() and 0xff; if (v > 0) { hr = v; hrAt = clock() }; i += 1 }
        speed?.let { speedKmh = it }
        val now = clock()
        val last = sample
        if (power != null || cadence != null)
            sample = BikeSample(timestamp = now, power = power ?: last?.power ?: 0f, cadence = cadence ?: last?.cadence ?: 0f,
                resistance = resistance ?: last?.resistance ?: 0)
    }

    /** Send a control point command and wait for Grupetto's answer for that op. Null: no answer (or not connected). */
    private fun control(body: ByteArray): Int? {
        replies.clear()
        try { send(MSG_WRITE, uuidBytes(CONTROL_POINT) + body) } catch (e: IOException) { closeSocket(); return null }
        val op = body[0].toInt() and 0xff
        val deadline = System.nanoTime() + replyTimeoutMs * 1_000_000
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return null
            val r = try { replies.poll(left, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) { return null } ?: return null
            if (r[0] == op || r[0] == -1) return r[1]
        }
    }

    private fun send(id: Int, payload: ByteArray) {
        val o = out ?: throw IOException("not connected")
        synchronized(writeLock) {
            seq = (seq + 1) and 0xff
            val msg = ByteArray(6 + payload.size)
            msg[0] = 1; msg[1] = id.toByte(); msg[2] = seq.toByte(); msg[3] = 0
            msg[4] = (payload.size shr 8).toByte(); msg[5] = payload.size.toByte()
            payload.copyInto(msg, 6)
            o.write(msg); o.flush()
        }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null; out = null
    }

    companion object {
        const val PORT = 8081
        const val MAX_WATTS = 2000
        private const val FRESH_MS = 1_500L
        /** How long the bike's echo of a target we wrote may take to arrive (Wi-Fi, a slow answer). */
        private const val ECHO_MS = 3_000L
        private const val HR_FRESH_MS = 5_000L
        /** How long "another app has control" is reported after Grupetto said so, before we try again. */
        private const val EXTERNAL_MS = 5_000L
        /** Retry a target that didn't go through once a second, up to [MAX_FAILS] times. */
        private const val RETRY_MS = 1_000L
        private const val MAX_FAILS = 5
        /** No bike data for this long: the connection is dead and is reopened. */
        /** Grupetto (1 Hz, its sensor poll can stall a moment): a reconnect there means asking for control again. */
        const val READ_TIMEOUT_MS = 5_000
        /** SmartSpin2K (about 2 Hz, always): 3 s of nothing is a dead link, and reconnecting it costs nothing. */
        const val SS2K_READ_TIMEOUT_MS = 3_000
        /** After "can't do ERG", a target is tried again this often. */
        private const val NO_ERG_RETRY_MS = 60_000L

        private const val MSG_WRITE = 0x04
        private const val MSG_ENABLE_NOTIFICATIONS = 0x05
        private const val MSG_NOTIFICATION = 0x06

        const val OP_REQUEST_CONTROL = 0x00
        const val OP_RESET = 0x01
        const val OP_SET_TARGET_POWER = 0x05
        const val RESULT_SUCCESS = 1
        const val RESULT_NOT_SUPPORTED = 2
        const val RESULT_INVALID = 3
        const val RESULT_FAILED = 4
        const val RESULT_NOT_PERMITTED = 5
        private const val STATUS_CONTROL_PERMISSION_LOST = 0xFF
        private const val STATUS_TARGET_POWER_CHANGED = 0x08

        private fun ble(short: Int) = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))
        val INDOOR_BIKE_DATA: UUID = ble(0x2AD2)
        val CONTROL_POINT: UUID = ble(0x2AD9)
        val MACHINE_STATUS: UUID = ble(0x2ADA)
        val HEART_RATE: UUID = ble(0x2A37)

        fun resultText(r: Int, name: String = "Grupetto") = when (r) {
            RESULT_NOT_SUPPORTED -> if (name == "Grupetto") "Grupetto can't hold ERG here (Bike+ ERG off, or a Peloton Bike without a motor)"
                                    // SS2K answers "not supported" to a target when it has no power reading.
                                    else "$name has no power reading: is Grupetto's Bluetooth power broadcast on?"
            RESULT_INVALID -> "$name refused the value"
            RESULT_FAILED -> "Bike not ready: pedal"
            RESULT_NOT_PERMITTED -> "Another app is controlling the bike"
            else -> "$name answered $r"
        }

        fun uuidBytes(u: UUID): ByteArray =
            ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()

        private fun uuidAt(b: ByteArray): UUID { val bb = ByteBuffer.wrap(b, 0, 16); return UUID(bb.long, bb.long) }
        private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
        private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
    }
}
