package com.madooroy.gregario.drop

import org.junit.Assert
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL

class WorkoutDropTest {
    private val lo = InetAddress.getByName("127.0.0.1")

    private fun post(port: Int, name: String, body: String, header: Boolean = true): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port/upload?name=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        if (header) c.setRequestProperty("X-Gregario", "1")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
    }

    /** A raw request, for headers HttpURLConnection won't let a test set (Host). */
    private fun raw(port: Int, request: String): String = Socket("127.0.0.1", port).use { s ->
        s.getOutputStream().write(request.toByteArray()); s.getInputStream().bufferedReader().readText()
    }

    @Test fun `serves the page, takes workout files, refuses the rest`() {
        val got = ArrayList<Pair<String, String>>()
        val d = WorkoutDrop { n, t -> got += n to t; "Added $n" }
        Assert.assertTrue(d.start(lo, 18095..18099))
        try {
            val page = URL("http://127.0.0.1:${d.port}/").readText()
            Assert.assertTrue(page.contains("Send workouts to the bike") && page.contains("X-Gregario"))
            Assert.assertEquals(200 to "Added VO2 6x2.zwo", post(d.port, "VO2 6x2.zwo", "<workout_file/>"))
            Assert.assertEquals(listOf("VO2 6x2.zwo" to "<workout_file/>"), got)
            Assert.assertEquals(400, post(d.port, "notes.txt", "x").first)                     // not a workout file
            Assert.assertEquals(200 to "Added x.erg", post(d.port, "../../x.erg", "x"))           // path parts dropped
            val big = "x".repeat(com.madooroy.gregario.workout.WorkoutFiles.MAX_CHARS + 1)
            // Refused from the header: the tablet answers 413 and hangs up, often before the sender has finished.
            val r = runCatching { post(d.port, "big.zwo", big).first }
            Assert.assertTrue(r.getOrNull() == 413 || r.exceptionOrNull() is java.io.IOException)
            Assert.assertEquals(2, got.size)                                                    // nothing added
            val c = URL("http://127.0.0.1:${d.port}/data/secret").openConnection() as HttpURLConnection
            Assert.assertEquals(404, c.responseCode)
        } finally { d.stop() }
        Assert.assertTrue(!d.running)
    }

    @Test fun `uploads need the page's header and the tablet's own address (review N2)`() {
        val got = ArrayList<String>()
        val d = WorkoutDrop { n, _ -> got += n; "Added $n" }
        Assert.assertTrue(d.start(lo, 18110..18114))
        try {
            Assert.assertEquals(403, post(d.port, "a.zwo", "x", header = false).first)          // a page from another site
            val rebound = raw(d.port, "POST /upload?name=a.zwo HTTP/1.1\r\nHost: evil.example:${d.port}\r\nX-Gregario: 1\r\nContent-Length: 1\r\n\r\nx")
            Assert.assertTrue(rebound.startsWith("HTTP/1.1 403"))                                // DNS rebinding
            val ok = raw(d.port, "POST /upload?name=a.zwo HTTP/1.1\r\nHost: 127.0.0.1:${d.port}\r\nX-Gregario: 1\r\nContent-Length: 1\r\n\r\nx")
            Assert.assertTrue(ok.startsWith("HTTP/1.1 200")); Assert.assertEquals(listOf("a.zwo"), got)
            Assert.assertTrue(ok.contains("nosniff"))
        } finally { d.stop() }
    }

    @Test fun `stopping ends uploads already in progress (review N4)`() {
        val got = ArrayList<String>()
        val d = WorkoutDrop { n, _ -> got += n; "Added $n" }
        Assert.assertTrue(d.start(lo, 18115..18119))
        val port = d.port
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("POST /upload?name=a.zwo HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-Gregario: 1\r\nContent-Length: 4\r\n\r\nxx".toByteArray())
            Thread.sleep(300)
            d.stop()                                                     // e.g. the rider opened a workout
            Thread.sleep(300)
            runCatching { s.getOutputStream().write("xx".toByteArray()); s.getOutputStream().flush() }
            Thread.sleep(300)
        }
        Assert.assertTrue(got.isEmpty())
    }

    @Test fun `a request that never finishes its header is dropped, not held for ever`() {
        val d = WorkoutDrop { n, _ -> "Added $n" }
        Assert.assertTrue(d.start(lo, 18100..18104))
        try {
            Socket("127.0.0.1", d.port).use { s ->
                s.getOutputStream().write("GET / HTTP/1.1\r\n".toByteArray())   // no blank line
                s.soTimeout = 20_000
                val t0 = System.currentTimeMillis()
                val r = s.getInputStream().read()
                Assert.assertTrue(r == -1 || System.currentTimeMillis() - t0 < 16_000)
            }
        } finally { d.stop() }
    }

    @Test fun `file names are reduced to a plain workout file name`() {
        Assert.assertEquals("a b.zwo", WorkoutDrop.fileName("/upload?name=a%20b.zwo"))
        Assert.assertEquals("x.mrc", WorkoutDrop.fileName("/upload?name=C%3A%5Cdir%5Cx.MRC"))
        Assert.assertEquals("y".repeat(76) + ".erg", WorkoutDrop.fileName("/upload?name=" + "y".repeat(200) + ".erg"))   // keeps its extension
        Assert.assertNull(WorkoutDrop.fileName("/upload?name=.zwo"))
        Assert.assertNull(WorkoutDrop.fileName("/upload?name=a.zwo.exe"))
        Assert.assertNull(WorkoutDrop.fileName("/upload"))
        Assert.assertNull(WorkoutDrop.fileName("/upload?name=%E0%A4%A"))   // bad escape
    }
}
