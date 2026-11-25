package com.madooroy.gregario.drop

import com.madooroy.gregario.workout.WorkoutFiles
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * "Send to the bike": a tiny web page on the tablet's Wi-Fi address. Open it on a phone or computer on the same
 * network, pick .zwo / .mrc / .erg files, and they are added to "Your workouts" ([onFile] returns the message to show).
 *
 * Deliberately small: two addresses (the page, and POST /upload?name=<file name> with the file as the body), files up
 * to [WorkoutFiles.MAX_CHARS], workout extensions only, two requests at a time, 15 seconds per request, nothing served
 * from the tablet's storage. It listens only on the one address shown on the panel, and runs only while the panel
 * shows the workout list (see Gregario.updateDrop). An upload must carry the X-Gregario header (which a web
 * page from another site can't send without asking the tablet first, and the tablet never agrees) and the tablet's own
 * address as its Host (review N2).
 */
class WorkoutDrop(private val onFile: (name: String, text: String) -> String) {
    @Volatile private var server: ServerSocket? = null
    @Volatile var port: Int = 0; private set
    @Volatile private var host: String? = null
    /** Connections being served, closed by [stop] so nothing finishes once the server is stopped (review N4). */
    private val open = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r -> Thread(r, "gregario-drop-timer").apply { isDaemon = true } }
    private val pool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4)) { r -> Thread(r, "gregario-drop").apply { isDaemon = true } }

    val running get() = server != null

    /**
     * Listens on [bind] only (the address the panel shows: review N3), on the first free port of [ports]; false if
     * none is free. Already running on another address: moved to this one.
     */
    @Synchronized fun start(bind: InetAddress, ports: IntRange = 8095..8099): Boolean {
        if (server != null && server?.inetAddress == bind) return true
        stop()
        for (p in ports) {
            val s = runCatching { ServerSocket(p, 8, bind) }.getOrNull() ?: continue
            server = s; port = p
            host = (if (bind is java.net.Inet6Address) "[${bind.hostAddress}]" else bind.hostAddress) + ":" + p
            Thread({ acceptLoop(s) }, "gregario-drop-accept").apply { isDaemon = true }.start()
            return true
        }
        return false
    }

    @Synchronized fun stop() {
        runCatching { server?.close() }
        server = null; port = 0; host = null
        open.toList().forEach { runCatching { it.close() } }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            // One failed accept (out of file handles for a moment) is not the end: wait and go on (review N8).
            val c = try { s.accept() } catch (e: Exception) { if (s.isClosed) break; Thread.sleep(200); continue }
            // Any failure in one request (a timeout, a dropped connection) ends that request only: an uncaught
            // exception on a thread would take the whole app, and a ride, down.
            try { pool.execute { runCatching { handle(c) } } } catch (e: RejectedExecutionException) { runCatching { c.close() } }
        }
    }

    internal fun handle(c: Socket) = c.use { sock ->
        open += sock
        // One deadline for the whole request, however slowly it trickles in (review N4, N5).
        val deadline = deadlines.schedule({ runCatching { sock.close() } }, 15, TimeUnit.SECONDS)
        try { serve(sock) } finally { deadline.cancel(false); open -= sock }
    }

    private fun serve(sock: Socket) {
        sock.soTimeout = 10_000
        val input = sock.getInputStream(); val out = sock.getOutputStream()
        val head = readHead(input) ?: return reply(out, 400, "text/plain", "Bad request")
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return reply(out, 400, "text/plain", "Bad request")
        fun header(name: String) = lines.drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val (method, target) = parts[0] to parts[1]
        val path = target.substringBefore('?')
        when {
            method == "GET" && (path == "/" || path == "/index.html") -> reply(out, 200, "text/html; charset=utf-8", PAGE)
            method == "POST" && path == "/upload" -> {
                // Only this page, sent to this tablet: a page from another site can't add the header without the
                // tablet's agreement (it answers its OPTIONS check with 404), and a renamed address fails the Host check.
                if (header("X-Gregario") != "1" || !header("Host").equals(host, ignoreCase = true))
                    return reply(out, 403, "text/plain", "Use the page at http://$host/")
                val length = header("Content-Length")?.toIntOrNull() ?: return reply(out, 411, "text/plain", "Length required")
                if (length < 0 || length > WorkoutFiles.MAX_CHARS) return reply(out, 413, "text/plain", "File too large for a workout")
                val name = fileName(target) ?: return reply(out, 400, "text/plain", "Only .zwo, .mrc and .erg files")
                val body = readBody(input, length) ?: return reply(out, 400, "text/plain", "The file didn't arrive in full")
                if (server == null) return reply(out, 503, "text/plain", "The bike isn't taking workouts right now")
                // The reply goes to whoever sent the file: no exception text (it could hold a path), just the outcome.
                val msg = runCatching { onFile(name, String(body, Charsets.UTF_8)) }.getOrElse { "Couldn't add $name" }
                reply(out, 200, "text/plain; charset=utf-8", msg)
            }
            else -> reply(out, 404, "text/plain", "Not found")
        }
    }

    companion object {
        private const val MAX_HEAD = 8192

        /** The header block, up to the blank line; null if it is too long or the connection ends first. */
        internal fun readHead(input: InputStream): String? {
            val b = StringBuilder(); var last4 = 0
            while (b.length < MAX_HEAD) {
                val c = input.read(); if (c < 0) return null
                b.append(c.toChar()); last4 = (last4 shl 8) or c
                if (last4 == 0x0D0A0D0A) return b.toString().trimEnd()
            }
            return null
        }

        private fun readBody(input: InputStream, length: Int): ByteArray? {
            val body = ByteArray(length); var got = 0
            while (got < length) { val n = input.read(body, got, length - got); if (n < 0) return null; got += n }
            return body
        }

        /** The ?name= of an upload, reduced to a plain file name with a workout extension, or null. */
        internal fun fileName(target: String): String? {
            val raw = target.substringAfter('?', "").split('&').firstOrNull { it.startsWith("name=") }?.substringAfter('=') ?: return null
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
            val name = decoded.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }.trim()
            val ext = listOf(".zwo", ".mrc", ".erg").firstOrNull { name.endsWith(it, ignoreCase = true) } ?: return null
            val stem = name.dropLast(4).trim().take(76)          // a long name is shortened, keeping its extension
            return if (stem.isEmpty()) null else stem + name.takeLast(4).lowercase().let { if (it == ext) it else ext }
        }

        private fun reply(out: OutputStream, code: Int, type: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 411 -> "Length Required"
                413 -> "Payload Too Large"; 503 -> "Service Unavailable"; else -> "Bad Request" }
            out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            out.write(bytes); out.flush()
        }

        /** The phone page: pick or drop files, each one is sent as it is, and the tablet's answer shown under it. */
        val PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Send workouts to the bike</title><style>
body{font-family:system-ui,sans-serif;background:#0B0E13;color:#E6E8EB;margin:0;padding:24px;max-width:560px}
h1{font-size:22px;margin:0 0 6px}p{color:#9EAEC0;line-height:1.4}
label{display:block;border:2px dashed #3A4556;border-radius:14px;padding:32px 16px;text-align:center;cursor:pointer;margin:18px 0}
label.over{border-color:#4CC3A0}input{display:none}li{margin:6px 0}.ok{color:#4CC3A0}.no{color:#EF8635}</style></head><body>
<h1>Send workouts to the bike</h1>
<p>Pick .zwo, .mrc or .erg files. They appear under "Your workouts" on the Gregario panel.</p>
<label id="drop">Tap to choose files, or drop them here<input id="f" type="file" multiple accept=".zwo,.mrc,.erg"></label>
<ul id="out"></ul><script>
const out=document.getElementById('out'),drop=document.getElementById('drop');
async function send(files){for(const f of files){const li=document.createElement('li');li.textContent=f.name+': sending…';out.appendChild(li);
try{const r=await fetch('/upload?name='+encodeURIComponent(f.name),{method:'POST',headers:{'X-Gregario':'1'},body:f});const t=await r.text();
li.textContent=f.name+': '+t;li.className=(r.ok&&t.startsWith('Added'))?'ok':'no';}catch(e){li.textContent=f.name+': not sent ('+e+')';li.className='no';}}}
document.getElementById('f').onchange=e=>send(e.target.files);
drop.ondragover=e=>{e.preventDefault();drop.className='over'};drop.ondragleave=()=>drop.className='';
drop.ondrop=e=>{e.preventDefault();drop.className='';send(e.dataTransfer.files)};
</script></body></html>"""
    }
}
