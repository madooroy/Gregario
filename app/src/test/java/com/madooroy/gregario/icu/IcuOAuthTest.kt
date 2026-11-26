package com.madooroy.gregario.icu

import com.madooroy.gregario.upload.Http
import com.madooroy.gregario.upload.HttpReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IcuOAuthTest {
    private class Fake(val reply: HttpReply) : Http {
        val calls = mutableListOf<String>(); val bodies = mutableListOf<String>()
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): HttpReply {
            calls += "$method $url"; bodies += body?.let { String(it) } ?: ""; return reply
        }
    }

    @Test fun pkceChallengeIsBase64UrlSha256() =
        assertEquals("qqC4PcHJRRRxn_9wM29vlokQMwGK43fBHEo-mS0Kqkg", IcuOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gXk-sEjXk"))

    @Test fun startMakesAValidVerifier() {
        val p = IcuOAuth().start()
        assertTrue(p.verifier.length in 43..128 && p.verifier.all { it.isLetterOrDigit() || it in "-._~" })
        assertTrue(p.state.length >= 16)
    }

    @Test fun authorizeUrlCarriesClientRedirectScopeAndChallenge() {
        val p = IcuOAuth.Pending("v".repeat(43), "abc")
        val u = IcuOAuth().authorizeUrl(p)
        assertTrue(u.startsWith("https://intervals.icu/oauth/authorize?client_id=2036&"))
        assertTrue("redirect_uri=http%3A%2F%2Flocalhost%2Fgregario%2Fintervals" in u)
        assertTrue("scope=ACTIVITY%3AWRITE%2CCALENDAR%3AWRITE%2CSETTINGS%3AWRITE" in u)
        assertTrue("&state=abc&" in u && "code_challenge=${IcuOAuth.challenge(p.verifier)}" in u && u.endsWith("code_challenge_method=S256"))
    }

    @Test fun redirectIsRecognised() {
        assertTrue(IcuOAuth.isRedirect("http://localhost/gregario/intervals?code=1&state=x"))
        assertTrue(IcuOAuth.isRedirect("http://127.0.0.1:8123/gregario/intervals?code=1"))
        assertFalse(IcuOAuth.isRedirect("https://intervals.icu/oauth/authorize?redirect_uri=http://localhost/gregario/intervals"))
        assertFalse(IcuOAuth.isRedirect("http://localhost/exchange_token?code=1"))
    }

    @Test fun finishExchangesTheCodeWithTheVerifierAndNoSecret() {
        val h = Fake(HttpReply(200, """{"token_type":"Bearer","access_token":"tok123","scope":"ACTIVITY:WRITE","athlete":{"id":"i1","name":"Marc Roy"}}"""))
        val p = IcuOAuth.Pending("ver", "st8")
        val g = IcuOAuth(h).finish("http://localhost/gregario/intervals?code=c%2B1&state=st8", p)
        assertEquals(IcuOAuth.Grant("tok123", "Marc Roy", "ACTIVITY:WRITE"), g)
        assertEquals(listOf("POST https://intervals.icu/api/oauth/token"), h.calls)
        val body = h.bodies.single()
        assertTrue("client_id=2036" in body && "code=c%2B1" in body && "code_verifier=ver" in body)
        assertFalse("client_secret" in body)
    }

    @Test fun refusalsSayWhatHappened() {
        val p = IcuOAuth.Pending("ver", "st8")
        fun msg(url: String, reply: HttpReply = HttpReply(200, "{}")) = try { IcuOAuth(Fake(reply)).finish(url, p); fail("expected a refusal"); "" } catch (e: IcuSignInException) { e.message!! }
        assertTrue("didn't approve" in msg("http://localhost/gregario/intervals?error=access_denied&state=st8"))
        assertTrue("attempt" in msg("http://localhost/gregario/intervals?code=1&state=other"))
        assertTrue("HTTP 400" in msg("http://localhost/gregario/intervals?code=1&state=st8", HttpReply(400, "bad verifier")))
        assertTrue("no token" in msg("http://localhost/gregario/intervals?code=1&state=st8"))
    }

    @Test fun headerIsBearerForASignInAndBasicForAKey() {
        assertEquals("Bearer tok", IcuAuth.header(IcuAuth.OAUTH + "tok"))
        assertEquals("Basic " + IcuApi.base64("API_KEY:k"), IcuAuth.header("k"))
    }
}
