package com.madooroy.gregario.icu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcuApiTest {
    private val withDoc = """[{"id":101,"category":"WORKOUT","name":"VO2 6x2","start_date_local":"2026-11-20T00:00:00",
        "workout_doc":{"steps":[{"duration":360,"power":{"units":"%ftp","value":50}},
          {"reps":2,"steps":[{"duration":120,"power":{"units":"%ftp","value":125}},{"duration":240,"power":{"units":"%ftp","value":40}}]}]}},
        {"id":102,"category":"NOTE","name":"Rest day"},
        {"id":103,"category":"WORKOUT","name":"Endurance 60"}]"""
    private val single103 = """{"id":103,"category":"WORKOUT","name":"Endurance 60","workout_doc":{"steps":[{"duration":3000,"power":{"units":"%ftp","value":70}}]}}"""

    @Test fun `today's workouts, fetching the full event when the list leaves out the steps`() {
        val asked = mutableListOf<String>()
        val api = IcuApi("secret", http = { url, auth ->
            asked += url; assertEquals("Basic " + IcuApi.base64("API_KEY:secret"), auth)
            if (url.endsWith("/events/103")) single103 else withDoc
        })
        val list = api.plannedWorkouts("2026-11-20")
        assertEquals(listOf("VO2 6x2", "Endurance 60"), list.map { it.name })
        assertEquals(360 + 2 * 360, list[0].workout.totalSeconds)
        assertEquals(3000, list[1].workout.totalSeconds)
        assertEquals("https://intervals.icu/api/v1/athlete/0/events?oldest=2026-11-20&newest=2026-11-20&category=WORKOUT", asked[0])
        assertEquals(2, asked.size)
    }

    @Test fun `cached event json parses back to the same workout`() {
        val api = IcuApi("k", http = { url, _ -> if (url.endsWith("/events/103")) single103 else withDoc })
        val p = api.plannedWorkouts("2026-11-20").first()
        assertEquals(p.workout, com.madooroy.gregario.workout.IcuParser.parseEvent(p.json))
    }

    @Test fun `an unexpected reply for one event skips it instead of crashing`() {
        val api = IcuApi("k", http = { _, _ -> withDoc })   // single-event fetch also returns the list
        assertEquals(listOf("VO2 6x2"), api.plannedWorkouts("2026-11-20").map { it.name })
    }

    @Test fun `only rides, and a bad workout is listed with its reason`() {
        val list = """[{"id":1,"category":"WORKOUT","type":"Rowing","name":"Row","workout_doc":{"steps":[{"duration":600,"power":{"units":"%ftp","value":80}}]}},
            {"id":2,"category":"WORKOUT","type":"VirtualRide","name":"Odd","workout_doc":{"steps":[{"duration":600,"power":{"units":"w","value":200}}]}},
            {"id":3,"category":"WORKOUT","type":"Ride","name":"Good","workout_doc":{"steps":[{"duration":600,"power":{"units":"%ftp","value":60}}]}}]"""
        val got = IcuApi("k", http = { _, _ -> list }).plannedWorkouts("2026-11-20")
        assertEquals(listOf("Odd", "Good"), got.map { it.name })
        assertEquals(true, got[0].problem!!.contains("%FTP")); assertNull(got[1].problem)
    }

    @Test fun `a workout already ridden today is marked done, not offered`() {
        val list = """[{"id":5,"category":"WORKOUT","type":"Ride","name":"QuickTest","paired_activity_id":"i123",
              "workout_doc":{"steps":[{"duration":300,"power":{"units":"%ftp","value":66}}]}},
            {"id":6,"category":"WORKOUT","type":"Ride","name":"Later","paired_activity_id":null,
              "workout_doc":{"steps":[{"duration":600,"power":{"units":"%ftp","value":60}}]}}]"""
        val got = IcuApi("k", http = { _, _ -> list }).plannedWorkouts("2026-11-20")
        assertEquals(listOf(true, false), got.map { it.done })
        assertEquals("Later", got.single { !it.done }.name)
    }

    @Test fun `a sample calendar - repeats, cadence notes, a row and an empty copy`() {
        val body = javaClass.getResource("/icu-events-sample.json")!!.readText()
        val got = IcuApi("k", http = { _, _ -> body }).plannedWorkouts("2026-11-19").associateBy { it.name }
        assertEquals(setOf("VO2 1-min repeats", "Threshold 4x8 with spin-ups", "Copied from another app"), got.keys)   // the row is left out
        val vo2 = got["VO2 1-min repeats"]!!
        assertNull(vo2.problem); assertEquals(3600, vo2.workout.totalSeconds); assertEquals(38, vo2.workout.steps.size)
        assertEquals("VO2 · 115% · 1-min efforts", vo2.workout.label())
        val thr = got["Threshold 4x8 with spin-ups"]!!.workout
        assertEquals(3600, thr.totalSeconds)
        assertEquals("Threshold · 95% · 8-min efforts", thr.label())
        assertEquals("Easy spin-ups, no big power 3x · 110 rpm", thr.steps[1].text)
        assertEquals("85 rpm", thr.steps[2].text)
        assertEquals(true, got["Copied from another app"]!!.problem!!.startsWith("No steps"))
    }

    @Test fun `indoor FTP of 0 falls back to FTP`() {
        assertEquals(329, IcuApi("k", http = { _, _ -> """{"sportSettings":[{"types":["VirtualRide"],"ftp":329,"indoor_ftp":0}]}""" }).rideFtp())
    }

    @Test fun `ride FTP prefers indoor VirtualRide settings`() {
        val athlete = """{"sportSettings":[{"types":["Run"],"ftp":250},{"types":["Ride","Gravel Ride"],"ftp":305,"indoor_ftp":329},
            {"types":["VirtualRide"],"ftp":329}]}"""
        assertEquals(329, IcuApi("k", http = { _, _ -> athlete }).rideFtp())
        assertNull(IcuApi("k", http = { _, _ -> "{}" }).rideFtp())
    }

    @Test fun `base64 matches the standard encoding`() {
        for (s in listOf("", "a", "ab", "abc", "API_KEY:3x9k2", "API_KEY:éé"))
            assertEquals(java.util.Base64.getEncoder().encodeToString(s.toByteArray()), IcuApi.base64(s))
    }
}
