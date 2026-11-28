package com.madooroy.gregario.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UploadsTest {
    private class Fake : Http {
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var replies = ArrayDeque<HttpReply>()
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): HttpReply {
            calls += "$method $url"; bodies += body?.let { String(it, Charsets.ISO_8859_1) } ?: ""
            return replies.removeFirstOrNull() ?: HttpReply(200, "{}")
        }
    }
    private class Store : StravaStore {
        override var clientId = "123"; override var clientSecret = "sec"
        override var accessToken = ""; override var expiresAt = 0L; override var refreshToken = "r1"
    }

    @Test fun `intervals_icu upload pairs the planned workout and sets RPE`() {
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(201, """{"id":"i555","activities":[{"id":"i555"}]}"""))) }
        val res = IcuUploader("key", h).upload(byteArrayOf(1, 2, 3), "r.fit", "Tempo", "two words", "9000002", 7)
        assertEquals("i555", res.id); assertNull(res.warning)
        assertEquals("POST https://intervals.icu/api/v1/athlete/0/activities?name=Tempo&description=two%20words&paired_event_id=9000002", h.calls[0])
        assertEquals("PUT https://intervals.icu/api/v1/activity/i555", h.calls[1])
        assertTrue(h.bodies[1].contains("\"icu_rpe\":7"))
    }

    @Test fun `intervals_icu reply as an array, stale pairing retried without it, RPE failure reported`() {
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(422, "event already paired"),
            HttpReply(200, """[{"icu_athlete_id":"i24785","id":"i9"}]"""), HttpReply(500, "oops"))) }
        val res = IcuUploader("key", h).upload(byteArrayOf(1), "r.fit", "n", "d", "1", 6)
        assertEquals("i9", res.id)
        assertTrue(h.calls[1].endsWith("?name=n&description=d"))                                       // no pairing
        assertTrue(res.warning!!.contains("not paired")); assertTrue(res.warning!!.contains("RPE not set"))
    }

    @Test fun `Strava renews the token, saves the rotated refresh token, uploads and polls`() {
        val st = Store()
        val h = Fake().apply { replies = ArrayDeque(listOf(
            HttpReply(200, """{"access_token":"a2","expires_at":9999999999,"refresh_token":"r2"}"""),
            HttpReply(201, """{"id":42,"id_str":"42","status":"Your activity is still being processed."}"""),
            HttpReply(200, """{"id":42,"id_str":"42","activity_id":16000000000123}"""))) }
        val id = StravaAccount(st, h, now = { 1000 }).upload(byteArrayOf(1), "gregario-x", "Tempo", "d", sleep = {})
        assertEquals("16000000000123", id)
        assertEquals("r2", st.refreshToken); assertEquals("a2", st.accessToken)
        assertEquals(listOf("POST https://www.strava.com/oauth/token", "POST https://www.strava.com/api/v3/uploads",
            "GET https://www.strava.com/api/v3/uploads/42"), h.calls)
    }

    @Test fun `Strava duplicate counts as done`() {
        val st = Store().apply { accessToken = "a"; expiresAt = 9_999_999_999 }
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(201, """{"id":1,"error":"x.fit duplicate of activity 99"}"""))) }
        assertNull(StravaAccount(st, h).upload(byteArrayOf(1), "e", "n", "d", sleep = {}))
    }

    @Test fun `code from the pasted localhost address`() {
        assertEquals("abc123", StravaAccount.codeFrom("http://localhost/exchange_token?state=&code=abc123&scope=read,activity:write"))
        assertEquals("abc123", StravaAccount.codeFrom(" abc123 "))
        assertNull(StravaAccount.codeFrom("http://localhost/exchange_token?error=access_denied"))
    }

    @Test fun `Strava 401 with an unexpired token renews once`() {
        val st = Store().apply { accessToken = "old"; expiresAt = 9_999_999_999 }
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(401, "revoked"),
            HttpReply(200, """{"access_token":"new","expires_at":9999999999,"refresh_token":"r1"}"""),
            HttpReply(201, """{"id":5,"activity_id":8}"""))) }
        assertEquals("8", StravaAccount(st, h).upload(byteArrayOf(1), "e", "n", "d", sleep = {}))
        assertEquals("new", st.accessToken)
    }

    @Test fun `queue stops on permanent errors, skips Strava when not connected, promotes drafts`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "gregario-queue-test2").apply { deleteRecursively() }
        val q = UploadQueue(dir)
        q.add("a", byteArrayOf(1), "A", "d", null, null)
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(400, "bad file"))) }
        var r = q.process(IcuUploader("k", h), null).single()
        assertTrue(r.icuFailed); assertTrue(!r.waiting)
        val h2 = Fake(); q.process(IcuUploader("k", h2), null); assertEquals(0, h2.calls.size)          // not retried
        q.add("b", byteArrayOf(2), "B", "d", null, null)
        val h3 = Fake().apply { replies = ArrayDeque(listOf(HttpReply(201, """{"id":"i2"}"""))) }
        r = q.process(IcuUploader("k", h3), null).first { it.id == "b" }
        assertEquals("i2", r.icu); assertTrue(r.stravaFailed); assertTrue(!r.waiting)                     // not sent to Strava later
        q.saveDraft("c", byteArrayOf(3), "C", "d", "7"); q.saveDraft("d", byteArrayOf(4), "D", "d", null)
        assertEquals(1, q.promoteDrafts(exceptId = "d"))
        assertEquals("7", q.ride("c")!!.eventId); assertNull(q.ride("d"))
        q.add("d", byteArrayOf(4), "D", "d", null, 5); assertTrue(!File(dir, "draft-d.fit").exists())   // saved: draft gone
    }

    @Test fun `queue uploads intervals_icu first, then Strava, and keeps going after a failure`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "gregario-queue-test").apply { deleteRecursively() }
        val q = UploadQueue(dir)
        q.add("20261119-2150", byteArrayOf(9), "QuickTest", "d", "1", 6)
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(500, "down"))) }
        var r = q.process(IcuUploader("k", h), null).single()
        assertTrue(!r.icuDone); assertTrue(r.error!!.contains("500"))
        val st = Store().apply { accessToken = "a"; expiresAt = 9_999_999_999 }
        val h2 = Fake().apply { replies = ArrayDeque(listOf(HttpReply(201, """{"id":"i1"}"""), HttpReply(200, "{}"),
            HttpReply(201, """{"id":5,"activity_id":77}"""))) }
        r = q.process(IcuUploader("k", h2), StravaAccount(st, h2)).single()
        assertEquals("i1", r.icu); assertEquals("77", r.strava); assertNull(r.error)
        assertEquals(listOf("POST https://intervals.icu/api/v1/athlete/0/activities?name=QuickTest&description=d&paired_event_id=1",
            "PUT https://intervals.icu/api/v1/activity/i1", "POST https://www.strava.com/api/v3/uploads"), h2.calls)
        val h3 = Fake(); q.process(IcuUploader("k", h3), StravaAccount(st, h3)); assertEquals(0, h3.calls.size)   // nothing left to do
    }

    @Test fun `no intervals_icu key but Strava connected - the ride goes to Strava (review U1)`() {
        val q = UploadQueue(File(System.getProperty("java.io.tmpdir"), "gregario-queue-u1").apply { deleteRecursively() })
        q.add("a", byteArrayOf(1), "A", "d", "7", null)
        val st = Store().apply { accessToken = "a"; expiresAt = 9_999_999_999 }
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(201, """{"id":5,"activity_id":77}"""))) }
        val r = q.process(null, StravaAccount(st, h)).single()
        assertEquals(UploadQueue.NO_ICU, r.icu); assertEquals("77", r.strava); assertTrue(!r.waiting)
        assertEquals(listOf("POST https://www.strava.com/api/v3/uploads"), h.calls)
        // Neither destination set up: the ride keeps waiting rather than being dropped.
        q.add("b", byteArrayOf(2), "B", "d", null, null)
        assertTrue(q.process(null, null).first { it.id == "b" }.waiting)
    }

    @Test fun `a refused key waits and goes up once the key is fixed (review U3)`() {
        val q = UploadQueue(File(System.getProperty("java.io.tmpdir"), "gregario-queue-u3").apply { deleteRecursively() })
        q.add("a", byteArrayOf(1), "A", "d", null, null)
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(401, "nope"))) }
        var r = q.process(IcuUploader("wrong", h), null).single()
        assertTrue(r.waiting); assertTrue(!r.icuFailed); assertTrue(r.error!!.contains("sign-in"))
        val h2 = Fake().apply { replies = ArrayDeque(listOf(HttpReply(201, """{"id":"i9"}"""))) }
        r = q.process(IcuUploader("right", h2), null).single()
        assertEquals("i9", r.icu)
    }

    @Test fun `a rate limit is not answered with an unpaired upload (review U5)`() {
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(429, "slow down"))) }
        runCatching { IcuUploader("k", h).upload(byteArrayOf(1), "a.fit", "A", "d", "7", null) }
        assertEquals(1, h.calls.size)
    }

    @Test fun `the workout's steps are set on the new activity, and a refusal is only a warning`() {
        val steps = listOf(linkedMapOf<String, Any?>("start_index" to 60, "end_index" to 89, "label" to "On"))
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(201, """{"id":"i7"}"""))) }
        val res = IcuUploader("key", h).upload(byteArrayOf(1), "r.fit", "n", "d", null, null, steps)
        assertEquals("i7", res.id); assertNull(res.warning)
        assertEquals("PUT https://intervals.icu/api/v1/activity/i7/intervals?all=true", h.calls[1])
        assertEquals("""[{"start_index":60,"end_index":89,"label":"On"}]""", h.bodies[1])
        val h2 = Fake().apply { replies = ArrayDeque(listOf(HttpReply(201, """{"id":"i8"}"""), HttpReply(400, "bad"))) }
        val res2 = IcuUploader("key", h2).upload(byteArrayOf(1), "r.fit", "n", "d", null, null, steps)
        assertEquals("i8", res2.id); assertTrue(res2.warning!!.contains("Steps not set"))
    }

    @Test fun `the steps wait in the queue with the ride`() {
        val dir = kotlin.io.path.createTempDirectory("q").toFile()
        val q = UploadQueue(dir)
        q.add("a", byteArrayOf(1), "n", "d", null, null, listOf(linkedMapOf<String, Any?>("start_index" to 5, "end_index" to 40, "label" to "Hold")))
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(201, """{"id":"i9"}"""))) }
        q.process(IcuUploader("k", h), null)
        assertEquals("PUT https://intervals.icu/api/v1/activity/i9/intervals?all=true", h.calls[1])
        assertEquals("""[{"start_index":5,"end_index":40,"label":"Hold"}]""", h.bodies[1])
        dir.deleteRecursively()
    }

    /** Review: a PUT that gets no answer, a rate limit or a server error is retried on the next runs; a refusal is final. */
    @Test fun `the steps are retried after a transient failure`() {
        val dir = kotlin.io.path.createTempDirectory("q").toFile()
        val q = UploadQueue(dir)
        q.add("a", byteArrayOf(1), "n", "d", null, null, listOf(linkedMapOf<String, Any?>("start_index" to 5, "end_index" to 40, "label" to "Hold")))
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(201, """{"id":"i9"}"""), HttpReply(503, "busy"))) }
        var r = q.process(IcuUploader("k", h), null).single()
        assertTrue(r.intervalsPending); assertTrue(r.waiting)
        val h2 = Fake()                                              // 200 {}
        r = q.process(IcuUploader("k", h2), null).single()
        assertEquals(listOf("PUT https://intervals.icu/api/v1/activity/i9/intervals?all=true"), h2.calls)
        assertFalse(r.intervalsPending); assertFalse(r.waiting)
        val h3 = Fake(); q.process(IcuUploader("k", h3), null)
        assertTrue("nothing more", h3.calls.isEmpty())
        dir.deleteRecursively()
    }

    @Test fun `a refused PUT is a warning and not retried`() {
        val dir = kotlin.io.path.createTempDirectory("q").toFile()
        val q = UploadQueue(dir)
        q.add("a", byteArrayOf(1), "n", "d", null, null, listOf(linkedMapOf<String, Any?>("start_index" to 5, "end_index" to 40, "label" to "Hold")))
        val h = Fake().apply { replies = ArrayDeque(listOf(HttpReply(201, """{"id":"i9"}"""), HttpReply(400, "bad"))) }
        val r = q.process(IcuUploader("k", h), null).single()
        assertFalse(r.intervalsPending); assertTrue(r.error!!.contains("Steps not set"))
        dir.deleteRecursively()
    }

    @Test fun `a draft keeps its steps, and an old queue entry without them still loads`() {
        val dir = kotlin.io.path.createTempDirectory("q").toFile()
        val q = UploadQueue(dir)
        q.saveDraft("d", byteArrayOf(4), "D", "d", null, listOf(linkedMapOf<String, Any?>("start_index" to 1, "end_index" to 9, "label" to "On")))
        assertEquals(1, q.promoteDrafts())
        val r = q.ride("d")!!
        assertEquals(1, r.intervals.size); assertTrue(r.intervalsPending)
        java.io.File(dir, "old.json").writeText("""{"name":"Old","description":"","eventId":null,"rpe":null,"icu":"i1","strava":null,"error":null,"icuFailed":false,"stravaFailed":false}""")
        val old = q.ride("old")!!
        assertTrue(old.intervals.isEmpty()); assertFalse(old.intervalsPending)
        dir.deleteRecursively()
    }
}
