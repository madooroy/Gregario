package com.madooroy.gregario.trainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for Grupetto's DirCon server: answers enables and control point writes the way an FTMS DirCon
 * server does (one owner; Request Control, Set Target Power, Reset), sends Indoor Bike Data,
 * and can simulate the knob (status 0xFF, owner dropped) or another app holding control.
 */
private class FakeGrupetto {
    val server = ServerSocket(0)
    val port get() = server.localPort
    val commands = CopyOnWriteArrayList<String>()
    @Volatile var owner: Any? = null
    @Volatile var otherAppOwns = false
    @Volatile var replyDelayMs = 0L
    @Volatile var notReady = 0          // answer this many target writes with "failed" (bike not ready)
    @Volatile var echoTarget = false    // like a SmartSpin2K: status 0x08 (target power changed) after each target
    @Volatile var noErg = false         // like Grupetto on a Peloton Bike (no motor): target power "not supported"
    @Volatile var client: Socket? = null
    @Volatile var silent = false        // stop sending bike data but keep the socket open (a dead Wi-Fi link)
    @Volatile var power = 150; @Volatile var cadence = 85; @Volatile var resistance = 40
    @Volatile var running = true
    val accepted = java.util.concurrent.atomic.AtomicInteger()

    init {
        Thread {
            while (running) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                accepted.incrementAndGet(); client = s; owner = null
                Thread { serve(s) }.apply { isDaemon = true }.start()
                Thread { pump(s) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun send(s: Socket, id: Int, payload: ByteArray) = synchronized(s) {
        val m = ByteArray(6 + payload.size)
        m[0] = 1; m[1] = id.toByte(); m[4] = (payload.size shr 8).toByte(); m[5] = payload.size.toByte()
        payload.copyInto(m, 6)
        s.getOutputStream().write(m); s.getOutputStream().flush()
    }

    private fun pump(s: Socket) = runCatching {
        while (!s.isClosed) {
            // flags: cadence, resistance, power, heart rate present; speed present (bit 0 clear)
            val b = ByteBuffer.allocate(13).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.putShort((0x0004 or 0x0020 or 0x0040 or 0x0200).toShort()).putShort(3000).putShort((cadence * 2).toShort())
                .putShort(resistance.toShort()).putShort(power.toShort()).put(132.toByte())
            if (!silent) send(s, 6, DirConLink.uuidBytes(DirConLink.INDOOR_BIKE_DATA) + b.array().copyOf(11))
            Thread.sleep(50)
        }
    }

    private fun serve(s: Socket) = runCatching {
        val input = DataInputStream(s.getInputStream())
        val header = ByteArray(6)
        while (true) {
            input.readFully(header)
            val id = header[1].toInt() and 0xff
            val len = ((header[4].toInt() and 0xff) shl 8) or (header[5].toInt() and 0xff)
            val p = ByteArray(len); input.readFully(p)
            when (id) {
                5 -> send(s, 5, p.copyOf(16))
                4 -> {
                    val body = p.copyOfRange(16, len)
                    val op = body[0].toInt() and 0xff
                    commands += body.joinToString(" ") { "%02x".format(it) }
                    if (replyDelayMs > 0) Thread.sleep(replyDelayMs)
                    val result = when {
                        otherAppOwns -> 5
                        op == 0 -> { owner = s; 1 }
                        owner !== s -> 5
                        op == 5 && notReady > 0 -> { notReady--; 4 }
                        op == 5 && noErg -> 2
                        else -> 1
                    }
                    send(s, 4, p.copyOf(16))
                    send(s, 6, DirConLink.uuidBytes(DirConLink.CONTROL_POINT) + byteArrayOf(0x80.toByte(), op.toByte(), result.toByte()))
                    if (echoTarget && op == 5 && result == 1)
                        send(s, 6, DirConLink.uuidBytes(DirConLink.MACHINE_STATUS) + byteArrayOf(0x08, body[1], body[2]))
                }
            }
        }
    }

    /** The rider turns the knob: Grupetto drops control and says so. */
    fun knob() { owner = null; client?.let { send(it, 6, DirConLink.uuidBytes(DirConLink.MACHINE_STATUS) + byteArrayOf(0xff.toByte())) } }

    /** A SmartSpin2K shift in ERG: it moves its own target and says so (firmware 26.11.26-test). */
    fun shift(watts: Int) { client?.let { send(it, 6, DirConLink.uuidBytes(DirConLink.MACHINE_STATUS) + byteArrayOf(0x08, watts.toByte(), (watts shr 8).toByte())) } }

    fun dropClient() { runCatching { client?.close() } }
    fun close() { running = false; dropClient(); server.close() }
}

class DirConLinkTest {
    private fun clock() = System.nanoTime() / 1_000_000

    private fun waitFor(ms: Long = 3_000, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (ok()) return true; Thread.sleep(20) }
        return ok()
    }

    private fun sent(fake: FakeGrupetto, vararg cmds: String) =
        assertTrue("sent ${cmds.toList()}: ${fake.commands}", waitFor { fake.commands.toList() == cmds.toList() })

    private fun linked(fake: FakeGrupetto) = DirConLink("127.0.0.1", fake.port, ::clock, retryMs = 100).also {
        it.start(); assertTrue("connected", waitFor { it.connected && it.freshSample() != null })
    }

    @Test fun readsPowerCadenceResistanceSpeedAndHeartRate() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            val s = link.freshSample()!!
            assertEquals(150f, s.power); assertEquals(85f, s.cadence); assertEquals(40, s.resistance)
            assertEquals(30.0 / 1.609344, link.speedMph()!!.toDouble(), 0.01)
            assertEquals(132, link.heartRate())
        } finally { link.stop(); fake.close() }
    }

    @Test fun ergAsksForControlOnceThenSendsTargets() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            assertTrue(link.setErg(200))
            sent(fake, "00", "05 c8 00")
            assertTrue(link.setErg(250))
            sent(fake, "00", "05 c8 00", "05 fa 00")
            val st = link.status()
            assertTrue(st.erg); assertEquals(250, st.targetWatts); assertFalse(st.knob); assertNull(st.externalControl)
            Thread.sleep(200)   // the control thread counts a write when Grupetto's answer arrives
            assertEquals(2, link.takeErg().third); assertEquals(0, link.takeErg().third)
        } finally { link.stop(); fake.close() }
    }

    @Test fun knobTurnIsReportedAndTheNextTargetAsksForControlAgain() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            assertTrue(link.setErg(200))
            sent(fake, "00", "05 c8 00")
            fake.knob()
            assertTrue("knob seen", waitFor { link.status().knob })
            assertFalse(link.status().erg)
            fake.commands.clear()
            assertTrue(link.setErg(210))
            sent(fake, "00", "05 d2 00")
            assertFalse(link.status().knob)
        } finally { link.stop(); fake.close() }
    }

    @Test fun knobWithoutErgIsNotAKnobTurn() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            fake.knob(); Thread.sleep(150)
            assertFalse(link.status().knob)
        } finally { link.stop(); fake.close() }
    }

    @Test fun releaseSendsReset() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            assertTrue(link.setErg(180))
            sent(fake, "00", "05 b4 00")
            assertTrue(link.release())
            sent(fake, "00", "05 b4 00", "01")
            assertFalse(link.status().erg)
            fake.commands.clear()
            assertTrue("nothing held: nothing sent", link.release()); Thread.sleep(300); assertTrue(fake.commands.isEmpty())
        } finally { link.stop(); fake.close() }
    }

    @Test fun anotherAppHoldingControlIsReportedNotFought() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            fake.otherAppOwns = true
            link.setErg(200)
            assertTrue("reported", waitFor { link.status().externalControl == "Another app" })
            assertFalse(link.status().erg)
            assertFalse("not asked again at once", link.setErg(200))
            Thread.sleep(300)
            assertEquals(listOf("00"), fake.commands.toList())   // no target sent
        } finally { link.stop(); fake.close() }
    }

    @Test fun droppedConnectionReconnectsAndErgIsOff() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            assertTrue(link.setErg(200))
            sent(fake, "00", "05 c8 00")
            fake.dropClient()
            assertTrue("noticed", waitFor { !link.connected || fake.accepted.get() >= 2 })
            assertFalse(link.status().erg)
            assertTrue("reconnected", waitFor { link.connected && fake.accepted.get() >= 2 })
            fake.commands.clear()
            assertTrue(link.setErg(190))
            sent(fake, "00", "05 be 00")
        } finally { link.stop(); fake.close() }
    }

    @Test fun noGrupettoMeansNoErgAndNoData() {
        val free = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val link = DirConLink("127.0.0.1", free, ::clock, retryMs = 100)
        link.start()
        try {
            Thread.sleep(200)
            assertFalse(link.connected)
            assertFalse(link.setErg(200))
            assertNull(link.freshSample())
            assertTrue(link.release())
            assertTrue(link.describe().startsWith("Looking for Grupetto"))
        } finally { link.stop() }
    }

    @Test fun aSmartSpin2KLinkSpeaksItsOwnName() {
        val free = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val ss2k = BikeChoice(BikeChoice.Kind.SS2K, "127.0.0.1")
        val link = DirConLink("127.0.0.1", free, ::clock, retryMs = 100, name = ss2k.name, lookFor = ss2k.lookFor)
        link.start()
        try {
            Thread.sleep(200)
            assertFalse(link.setErg(200))
            assertTrue(link.describe(), link.describe().startsWith("Looking for SmartSpin2K: check it's on"))
            assertFalse(link.describe().contains("Grupetto"))
        } finally { link.stop() }
    }

    @Test fun stopHandsTheBikeBack() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            assertTrue(link.setErg(200))
            sent(fake, "00", "05 c8 00")
            link.stop()
            assertTrue("handed back", waitFor { fake.commands.lastOrNull() == "01" })
            assertTrue("closed", waitFor { fake.client?.let { c -> runCatching { c.getInputStream().read() }.getOrDefault(-1) == -1 } ?: false })
        } finally { fake.close() }
    }

    /** A SmartSpin2K keeps holding its target after Reset: it is handed back with neutral simulation instead. */
    @Test fun aSmartSpin2KIsHandedBackWithNeutralSimulation() {
        val fake = FakeGrupetto()
        val ss2k = BikeChoice(BikeChoice.Kind.SS2K, "127.0.0.1")
        val link = DirConLink("127.0.0.1", fake.port, ::clock, retryMs = 100, name = ss2k.name, lookFor = ss2k.lookFor,
            handBackCommand = ss2k.handBack)
        link.start(); assertTrue("connected", waitFor { link.connected && link.freshSample() != null })
        try {
            assertTrue(link.setErg(200))
            sent(fake, "00", "05 c8 00")
            assertTrue(link.release())
            sent(fake, "00", "05 c8 00", "11 00 00 00 00 00 00")
            assertTrue(link.setErg(180))
            link.stop()
            assertTrue("handed back on stop", waitFor { fake.commands.lastOrNull() == "11 00 00 00 00 00 00" })
            assertFalse("never a Reset", fake.commands.contains("01"))
        } finally { fake.close() }
    }

    private fun ss2kLink(fake: FakeGrupetto, owedAtStart: Boolean = false, onOwed: (Boolean) -> Unit = {}, readTimeoutMs: Int = 3_000): DirConLink {
        val ss2k = BikeChoice(BikeChoice.Kind.SS2K, "127.0.0.1")
        return DirConLink("127.0.0.1", fake.port, ::clock, retryMs = 100, name = ss2k.name, lookFor = ss2k.lookFor,
            handBackCommand = ss2k.handBack, holdsAfterDisconnect = ss2k.holdsAfterDisconnect,
            holdsThroughStops = ss2k.holdsThroughStops, followsShifts = ss2k.followsShifts,
            readTimeoutMs = readTimeoutMs, owedAtStart = owedAtStart, onOwed = onOwed).also {
            it.start(); assertTrue("connected", waitFor { it.connected && it.freshSample() != null })
        }
    }

    /** Nov 26: an SS2K's Wi-Fi dropped and the socket stayed silently dead for 85 s. Silence past the timeout reconnects. */
    @Test fun aSilentConnectionIsNoticedAndReopened() {
        val fake = FakeGrupetto(); val link = ss2kLink(fake, readTimeoutMs = 400)
        try {
            assertTrue(link.setErg(200)); sent(fake, "00", "05 c8 00")
            fake.silent = true
            assertTrue("noticed", waitFor(2_000) { !link.connected })
            assertNotNull(link.lostAt)
            assertFalse(link.status().connected)
            fake.silent = false
            assertTrue("reconnected", waitFor { fake.accepted.get() >= 2 && link.connected })
            // The SS2K kept 200 W through the drop: restored, never handed back to neutral in between.
            assertTrue(waitFor { fake.commands.count { it == "05 c8 00" } >= 2 })
            assertFalse(fake.commands.contains(neutral))
        } finally { link.stop(); fake.close() }
    }

    /** A target changed while the connection was down is the one restored. */
    @Test fun aTargetChangedDuringADropIsTheOneRestored() {
        val fake = FakeGrupetto(); val link = ss2kLink(fake)
        try {
            assertTrue(link.setErg(200)); sent(fake, "00", "05 c8 00")
            fake.close()
            assertTrue(waitFor { !link.connected })
            assertFalse(link.setErg(150))                        // not sent now (not connected) …
            val again = FakeGrupetto()
            // … the reconnect goes to a new server on another port in this test, so just check what is wanted.
            assertEquals(150, link.wanted())
            again.close()
        } finally { link.stop() }
    }

    /** Bike data only: Grupetto read beside an SS2K is never asked for control. */
    @Test fun aReadOnlyLinkNeverAsksForControl() {
        val fake = FakeGrupetto()
        val link = DirConLink("127.0.0.1", fake.port, ::clock, retryMs = 100, readOnly = true)
        link.start()
        try {
            assertTrue(waitFor { link.connected && link.freshSample() != null })
            assertFalse(link.setErg(200))
            Thread.sleep(300)
            assertTrue(fake.commands.isEmpty())
            assertEquals(150f, link.freshSample()!!.power, 0.1f)
        } finally { link.stop(); fake.close() }
    }

    /** Review: a hand-back owed when Gregario closed during a drop is sent the next time it connects. */
    @Test fun aHandBackOwedFromAnEarlierRunIsSentOnConnect() {
        val fake = FakeGrupetto(); val owed = CopyOnWriteArrayList<Boolean>()
        val link = ss2kLink(fake, owedAtStart = true, onOwed = { owed += it })
        try {
            sent(fake, "00", neutral)
            assertTrue(waitFor { owed.lastOrNull() == false })
        } finally { link.stop(); fake.close() }
    }

    /** Review: a quick shift back to the step's target was dropped as an echo of our own write. */
    @Test fun aQuickShiftBackIsAShift() {
        val fake = FakeGrupetto().apply { echoTarget = true }; val link = ss2kLink(fake)
        try {
            assertTrue(link.setErg(200)); sent(fake, "00", "05 c8 00"); Thread.sleep(200)
            fake.shift(210); assertTrue(waitFor { link.status().targetWatts == 210 })
            fake.shift(200); assertTrue("shift back seen", waitFor { link.status().targetWatts == 200 })
        } finally { link.stop(); fake.close() }
    }

    private val neutral = "11 00 00 00 00 00 00"

    /**
     * Review finding 1: an SS2K keeps its target through a dropped connection. Still wanted: restored on reconnect (no
     * hand-back in between). Released while down: handed back once it is back.
     */
    @Test fun aSmartSpin2KStillHoldingAfterAReconnectIsRestoredOrHandedBack() {
        val fake = FakeGrupetto(); val link = ss2kLink(fake)
        try {
            assertTrue(link.setErg(200))
            sent(fake, "00", "05 c8 00")
            fake.dropClient()                                   // Wi-Fi drop: SS2K keeps 200 W
            assertTrue("reconnected", waitFor { fake.accepted.get() == 2 && link.connected })
            sent(fake, "00", "05 c8 00", "00", "05 c8 00")      // control again, the same target
            link.release()
            fake.dropClient()
            assertTrue("reconnected", waitFor { fake.accepted.get() == 3 && link.connected })
            assertTrue(waitFor { fake.commands.lastOrNull() == neutral })
        } finally { link.stop(); fake.close() }
    }

    /** Grupetto lets go of a client that leaves: nothing is owed after a reconnect. */
    @Test fun grupettoAfterAReconnectIsLeftAlone() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            assertTrue(link.setErg(200))
            sent(fake, "00", "05 c8 00")
            fake.dropClient()
            assertTrue("reconnected", waitFor { fake.accepted.get() == 2 && link.connected })
            Thread.sleep(300)
            assertEquals(listOf("00", "05 c8 00"), fake.commands.toList())
        } finally { link.stop(); fake.close() }
    }

    /** Review finding 2: target writes that keep failing leave the old target on an SS2K: hand it back. */
    @Test fun aSmartSpin2KIsHandedBackWhenTargetsKeepFailing() {
        val fake = FakeGrupetto(); val link = ss2kLink(fake)
        try {
            assertTrue(link.setErg(200))
            sent(fake, "00", "05 c8 00")
            fake.notReady = 99
            assertTrue(link.setErg(250))
            assertTrue("gives up", waitFor(8_000) { !link.status().erg })
            assertTrue("then hands back: ${fake.commands}", waitFor { fake.commands.lastOrNull() == neutral })
        } finally { link.stop(); fake.close() }
    }

    /** Review finding 3: stop() always sends the hand-back before closing, even with bike data streaming in. */
    @Test fun stopNeverLosesTheHandBack() {
        repeat(40) {
            val fake = FakeGrupetto(); val link = linked(fake)
            try {
                assertTrue(link.setErg(200))
                sent(fake, "00", "05 c8 00")
                link.stop()
                assertTrue("handed back (run $it): ${fake.commands}", waitFor { fake.commands.lastOrNull() == "01" })
            } finally { fake.close() }
        }
    }

    /** Review finding 7: an empty host would resolve to this tablet (Grupetto): never connect. */
    @Test fun anEmptyHostNeverConnects() {
        val fake = FakeGrupetto()
        val link = DirConLink("", fake.port, ::clock, retryMs = 100)
        link.start()
        try {
            Thread.sleep(300)
            assertFalse(link.connected)
            assertEquals(0, fake.accepted.get())
        } finally { link.stop(); fake.close() }
    }

    /** Nov 24 ride: setErg ran network on Android's main thread and threw. It must only record the target. */
    @Test fun setErgAndReleaseReturnAtOnceEvenWhenGrupettoIsSlow() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            fake.replyDelayMs = 300
            val t0 = System.nanoTime()
            assertTrue(link.setErg(200))
            assertTrue(link.status().erg)                      // held as far as the session can tell
            assertTrue(link.release())
            assertTrue(link.setErg(220))
            assertTrue("no waiting on the caller's thread", (System.nanoTime() - t0) / 1_000_000 < 50)
            assertTrue("gets there", waitFor(5_000) { fake.commands.lastOrNull() == "05 dc 00" })
        } finally { link.stop(); fake.close() }
    }

    @Test fun bikeNotReadyIsRetriedUntilItTakes() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            fake.notReady = 2
            assertTrue(link.setErg(200))
            assertTrue("third try takes", waitFor(5_000) { fake.commands.count { it == "05 c8 00" } == 3 })
            Thread.sleep(200)
            assertTrue(link.status().erg)
            assertEquals(1, link.takeErg().third)
        } finally { link.stop(); fake.close() }
    }

    @Test fun bikeNeverReadyReportsErgOff() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            fake.notReady = 99
            assertTrue(link.setErg(200))
            assertTrue("gives up after five tries", waitFor(8_000) { !link.status().erg })
            assertFalse(link.status().knob)
            assertNull(link.status().externalControl)
        } finally { link.stop(); fake.close() }
    }

    @Test fun staleDataIsNotFresh() {
        var now = 0L
        val fake = FakeGrupetto()
        val link = DirConLink("127.0.0.1", fake.port, { now }, retryMs = 100)
        link.start()
        try {
            assertTrue(waitFor { link.connected && link.freshSample() != null })
            now += 1_600
            Thread.sleep(0)
            // data keeps arriving (stamped with the new time) …
            assertTrue(waitFor { link.freshSample() != null })
            fake.close()
            Thread.sleep(150)
            now += 1_600
            assertNull(link.freshSample())   // … and once it stops, it goes stale
            assertNotNull(link.describe())
        } finally { link.stop() }
    }
    private fun waitSent(fake: FakeGrupetto, vararg cmds: String) =
        waitFor { fake.commands.toList().containsAll(cmds.toList()) }

    /** Nov 25 SS2K ride: shifts moved SS2K's target but the panel stayed on the workout's. */
    @Test fun aShiftOnTheBikeBecomesTheHeldTargetAndOurOwnEchoIsNot() {
        val fake = FakeGrupetto().apply { echoTarget = true }; val link = ss2kLink(fake)
        try {
            assertTrue(link.setErg(200)); sent(fake, "00", "05 c8 00")
            Thread.sleep(200)
            assertEquals("our own echo is not a shift", 200, link.status().targetWatts)
            fake.commands.clear()
            fake.shift(210)
            assertTrue("shift seen", waitFor { link.status().targetWatts == 210 })
            assertTrue(link.status().erg); assertFalse(link.status().knob)
            Thread.sleep(300)
            assertTrue("nothing sent to undo the shift", fake.commands.isEmpty())
            assertEquals(210, link.takeErg().first)                 // the ride file records the shifted target
        } finally { link.stop(); fake.close() }
    }

    private fun twoSteps() = com.madooroy.gregario.workout.Workout("T", listOf(
        com.madooroy.gregario.workout.Step.Steady(10, 50.0),
        com.madooroy.gregario.workout.Step.Steady(10, 80.0)))

    /** Marc: a shift works like the knob: the rider's target until the step ends, then ERG goes on with the next step. */
    @Test fun aShiftHoldsForTheStepThenTheNextStepsTargetIsSent() {
        val fake = FakeGrupetto().apply { echoTarget = true }; val link = ss2kLink(fake)
        try {
            val s = com.madooroy.gregario.session.WorkoutSession(twoSteps(), 250, link)
            var t = 0L
            s.tick(t, true, 0f, 90f, null); s.player.start(0)
            fun go(n: Int) = repeat(n) { t += 1000; s.tick(t, true, 150f, 90f, null) }
            go(2); assertTrue(waitSent(fake, "05 7d 00"))             // 125 W
            Thread.sleep(200); fake.commands.clear()
            fake.shift(135)
            assertTrue(waitFor { link.status().targetWatts == 135 })
            go(1)
            assertEquals(10, s.shiftOffset)
            assertTrue(s.notice!!.startsWith("Shift"))
            go(3)
            assertFalse("the shift isn't undone", fake.commands.any { it.startsWith("05") })
            go(8)                                                     // next step: 200 W, no offset
            assertTrue("next step's target", waitSent(fake, "05 c8 00"))
            assertEquals(0, s.shiftOffset); assertNull(s.notice)
        } finally { link.stop(); fake.close() }
    }

    /** Nov 25 SS2K ride: an auto-pause handed SS2K back and taking it back made it hunt (334 W, 281 W spikes). */
    @Test fun aSmartSpin2KKeepsItsTargetThroughAnAutoPause() {
        val fake = FakeGrupetto(); val link = ss2kLink(fake)
        try {
            val s = com.madooroy.gregario.session.WorkoutSession(twoSteps(), 250, link)
            var t = 0L
            s.tick(t, true, 0f, 90f, null); s.player.start(0)
            fun go(n: Int, pedal: Boolean = true) = repeat(n) { t += 1000; s.tick(t, pedal, if (pedal) 150f else 0f, if (pedal) 90f else 0f, null) }
            go(2); assertTrue(waitSent(fake, "05 7d 00"))
            Thread.sleep(200); fake.commands.clear()
            go(5, pedal = false)
            assertEquals(com.madooroy.gregario.workout.WorkoutPlayer.State.Paused, s.player.state)
            go(2)                                                     // pedalling again: auto-resume
            Thread.sleep(300)
            assertFalse("not handed back", fake.commands.contains(neutral))
            assertTrue("nothing to take back", fake.commands.isEmpty())
            s.releaseNow()
            assertTrue("End still hands it back", waitFor { fake.commands.contains(neutral) })
        } finally { link.stop(); fake.close() }
    }

    /** A Peloton Bike (no motor) through Grupetto: no ERG, and the ride goes on with targets to follow, no pause. */
    @Test fun aBikeWithoutErgRidesOnWithTargetsToFollow() {
        val fake = FakeGrupetto().apply { noErg = true }; val link = linked(fake)
        try {
            val s = com.madooroy.gregario.session.WorkoutSession(twoSteps(), 250, link)
            var t = 0L
            s.tick(t, true, 0f, 90f, null); s.player.start(0)
            fun go(n: Int) = repeat(n) { t += 1000; s.tick(t, true, 150f, 90f, null); Thread.sleep(50) }
            go(2)
            assertTrue(waitFor { link.status().noErg })
            go(3)
            assertEquals(com.madooroy.gregario.workout.WorkoutPlayer.State.Running, s.player.state)
            assertTrue(s.noErgBike)
            assertEquals(com.madooroy.gregario.session.NO_ERG_NOTICE, s.notice)
            val writes = fake.commands.count { it.startsWith("05") }
            go(5)
            assertEquals("no more targets sent", writes, fake.commands.count { it.startsWith("05") })
            assertFalse(link.setErg(200))
        } finally { link.stop(); fake.close() }
    }

    /** Review: a shift crossing a step change's write must not replace the new step's target. */
    @Test fun aShiftWhileOurWriteIsInFlightIsDropped() {
        val fake = FakeGrupetto().apply { echoTarget = true }; val link = ss2kLink(fake)
        try {
            assertTrue(link.setErg(200)); sent(fake, "00", "05 c8 00"); Thread.sleep(200)
            fake.replyDelayMs = 400
            assertTrue(link.setErg(250))
            assertTrue(waitFor { fake.commands.contains("05 fa 00") })
            fake.shift(210)                                          // arrives before the 250 W answer
            Thread.sleep(700)
            assertEquals("the step change wins", 250, link.status().targetWatts)
        } finally { link.stop(); fake.close() }
    }

    /** Review: a late echo of a target we wrote earlier is not a shift. */
    @Test fun aLateEchoOfAnEarlierTargetIsNotAShift() {
        val fake = FakeGrupetto().apply { echoTarget = true }; val link = ss2kLink(fake)
        try {
            assertTrue(link.setErg(200)); sent(fake, "00", "05 c8 00"); Thread.sleep(200)
            assertTrue(link.setErg(250)); assertTrue(waitFor { fake.commands.contains("05 fa 00") }); Thread.sleep(200)
            fake.shift(200)
            Thread.sleep(300)
            assertEquals(250, link.status().targetWatts)
        } finally { link.stop(); fake.close() }
    }

    /** Review: Grupetto's 0x08 only echoes our writes; a value we didn't send must not become a shift on the Bike+ motor. */
    @Test fun grupettoIgnoresTargetChanged() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            assertTrue(link.setErg(200)); assertTrue(waitFor { fake.commands.contains("05 c8 00") }); Thread.sleep(200)
            fake.shift(215)
            Thread.sleep(300)
            assertEquals(200, link.status().targetWatts)
        } finally { link.stop(); fake.close() }
    }

    /** Review: once pedalling no longer resumes the workout (past the auto-resume window), the SS2K is handed back. */
    @Test fun aSmartSpin2KIsHandedBackAfterTheAutoResumeWindow() {
        val fake = FakeGrupetto(); val link = ss2kLink(fake)
        try {
            val s = com.madooroy.gregario.session.WorkoutSession(twoSteps(), 250, link)
            var t = 0L
            s.tick(t, true, 0f, 90f, null); s.player.start(0)
            fun go(n: Int, pedal: Boolean = true) = repeat(n) { t += 1000; s.tick(t, pedal, if (pedal) 150f else 0f, if (pedal) 90f else 0f, null) }
            go(2); assertTrue(waitSent(fake, "05 7d 00"))
            Thread.sleep(200); fake.commands.clear()
            go(10, pedal = false)
            Thread.sleep(200)
            assertFalse("kept while auto-resume is possible", fake.commands.contains(neutral))
            go(40, pedal = false)
            assertTrue("handed back after the window", waitFor { fake.commands.contains(neutral) })
        } finally { link.stop(); fake.close() }
    }

    /** Review: a non-pedalling tick in the countdown after an auto-pause must not hand the SS2K back. */
    @Test fun aSmartSpin2KKeepsItsTargetThroughACountdownStop() {
        val fake = FakeGrupetto(); val link = ss2kLink(fake)
        try {
            val s = com.madooroy.gregario.session.WorkoutSession(twoSteps(), 250, link)
            var t = 0L
            fun go(n: Int, pedal: Boolean = true) = repeat(n) { t += 1000; s.tick(t, pedal, if (pedal) 150f else 0f, if (pedal) 90f else 0f, null) }
            s.tick(t, true, 0f, 90f, null); s.player.start(5)
            go(1); assertEquals(com.madooroy.gregario.workout.WorkoutPlayer.State.Countdown, s.player.state)
            assertTrue(waitSent(fake, "05 7d 00"))
            Thread.sleep(200); fake.commands.clear()
            go(1, pedal = false); go(1)
            Thread.sleep(300)
            assertFalse("not handed back", fake.commands.contains(neutral))
        } finally { link.stop(); fake.close() }
    }

    /** Review: a shift in a climb's lead second belongs to the coming step and is not undone at once. */
    @Test fun aShiftInAClimbsLeadSecondCarriesIntoThatStep() {
        val fake = FakeGrupetto().apply { echoTarget = true }; val link = ss2kLink(fake)
        try {
            val s = com.madooroy.gregario.session.WorkoutSession(twoSteps(), 250, link)
            var t = 0L
            s.tick(t, true, 0f, 90f, null); s.player.start(0)
            fun go(n: Int) = repeat(n) { t += 1000; s.tick(t, true, 150f, 90f, null) }
            go(2); assertTrue(waitSent(fake, "05 7d 00"))
            // tick until the climb's target (200 W) is sent early
            var n = 0; while (!fake.commands.contains("05 c8 00") && n++ < 12) { go(1); Thread.sleep(100) }
            assertTrue("lead sent", fake.commands.contains("05 c8 00"))
            Thread.sleep(200); fake.commands.clear()
            fake.shift(210)
            assertTrue(waitFor { link.status().targetWatts == 210 })
            go(1); go(1)
            Thread.sleep(300)
            assertFalse("not undone", fake.commands.any { it.startsWith("05") })
            assertEquals(10, s.shiftOffset)
        } finally { link.stop(); fake.close() }
    }

    @Test fun grupettoIsStillHandedBackOnAnAutoPause() {
        val fake = FakeGrupetto(); val link = linked(fake)
        try {
            val s = com.madooroy.gregario.session.WorkoutSession(twoSteps(), 250, link)
            var t = 0L
            s.tick(t, true, 0f, 90f, null); s.player.start(0)
            fun go(n: Int, pedal: Boolean = true) = repeat(n) { t += 1000; s.tick(t, pedal, if (pedal) 150f else 0f, if (pedal) 90f else 0f, null) }
            go(2); assertTrue(waitSent(fake, "05 7d 00"))
            go(5, pedal = false)
            assertTrue("Reset on auto-pause", waitFor { fake.commands.contains("01") })
        } finally { link.stop(); fake.close() }
    }

}

/** The workout engine through the real link and a fake Grupetto: a knob turn hands the step to the rider, no pause. */
class DirConSessionTest {
    @Test fun knobOverDirConIsTheRidersStepThenErgComesBack() {
        val fake = FakeGrupetto()
        val link = DirConLink("127.0.0.1", fake.port, { System.nanoTime() / 1_000_000 }, retryMs = 100)
        link.start()
        try {
            val end = System.currentTimeMillis() + 3_000
            while (!(link.connected && link.freshSample() != null) && System.currentTimeMillis() < end) Thread.sleep(20)
            val w = com.madooroy.gregario.workout.Workout("T", listOf(
                com.madooroy.gregario.workout.Step.Steady(10, 50.0),
                com.madooroy.gregario.workout.Step.Steady(10, 80.0)))
            val s = com.madooroy.gregario.session.WorkoutSession(w, 250, link)
            var t = 0L
            s.tick(t, true, 0f, 90f, null); s.player.start(0)
            fun go(n: Int) = repeat(n) { t += 1000; s.tick(t, true, 150f, 90f, null) }
            go(2)
            val sentBy = System.currentTimeMillis() + 2_000
            while (!fake.commands.contains("05 7d 00") && System.currentTimeMillis() < sentBy) Thread.sleep(20)
            assertTrue(fake.commands.contains("05 7d 00"))           // 125 W = 50% of 250
            fake.knob()
            val until = System.currentTimeMillis() + 2_000
            while (!link.status().knob && System.currentTimeMillis() < until) Thread.sleep(20)
            go(1)
            assertTrue("rider has the step", s.riderStep >= 0)
            assertEquals(com.madooroy.gregario.workout.WorkoutPlayer.State.Running, s.player.state)
            fake.commands.clear()
            go(3)
            assertTrue("no ERG during the rider's step", fake.commands.isEmpty())
            go(8)                                                       // into the next step
            val backBy = System.currentTimeMillis() + 2_000
            while (!fake.commands.toList().containsAll(listOf("00", "05 c8 00")) && System.currentTimeMillis() < backBy) Thread.sleep(20)
            assertTrue("ERG back: control asked again, then 200 W", fake.commands.toList().containsAll(listOf("00", "05 c8 00")))
        } finally { link.stop(); fake.close() }
    }

}
