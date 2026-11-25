package com.madooroy.gregario.trainer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Listens for DirCon servers announced on the Wi-Fi (the way Zwift finds a SmartSpin2K) while the panel is up, and
 * reports the SmartSpin2K ([Ss2kPick]) on the main thread, or null when it goes away. All state is main-thread only.
 *
 * - Android resolves one service at a time, so found services wait in a queue. A resolve that fails (for example
 *   "already active" right after a stop and start: the old one is still in flight) is tried again a little later,
 *   up to [RESOLVE_TRIES] times, instead of being dropped (review).
 * - Each [start] is a new generation: callbacks from an earlier one only free the resolver, they change nothing else.
 * - A device switched off sends no goodbye, so while one is found it is checked every [CHECK_MS] with a plain TCP
 *   connect to its DirCon port; after [CHECK_MISSES] misses in a row it counts as gone (review: "SmartSpin2K found"
 *   could stay up long after it was off).
 */
class Ss2kFinder(
    context: Context, private val log: (String) -> Unit,
    /**
     * Gregario is riding on this host right now: it's plainly there, so no check connection is opened (Nov 25 ride: one
     * every 20 s, 97 in the ride, each a new DirCon client on the SS2K).
     */
    private val inUse: (String) -> Boolean = { false },
    private val onChange: (DirConService?) -> Unit,
) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, DirConService>()   // by instance name
    private val waiting = ArrayDeque<Pair<NsdServiceInfo, Int>>() // (service, tries so far)
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var generation = 0
    private var last: DirConService? = null
    private var misses = 0
    private val check = Runnable { checkStillThere() }

    fun start() {
        if (listener != null) return
        val gen = ++generation
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = log("SS2K finder: listening for $serviceType")
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("SS2K finder: start failed $errorCode")
                main.post { if (listener === this) listener = null }   // only its own listener (review)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post { if (gen == generation) { waiting.addLast(info to 0); resolveNext() } } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post { if (gen == generation) { found.remove(info.serviceName); report() } } }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { log("SS2K finder: ${it.message}"); listener = null }
        resolveNext()
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        generation++
        runCatching { nsd.stopServiceDiscovery(l) }
        // A resolve still in flight keeps the resolver busy until its callback comes back: [resolving] is left alone.
        found.clear(); waiting.clear()
        main.removeCallbacks(check); misses = 0
        report()
    }

    @Suppress("DEPRECATION")   // resolveService: the only way on the Bike+'s Android version
    private fun resolveNext() {
        if (resolving || listener == null) return
        val (next, tries) = waiting.removeFirstOrNull() ?: return
        val gen = generation
        resolving = true
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        if (gen == generation && tries + 1 < RESOLVE_TRIES)
                            main.postDelayed({ if (gen == generation) { waiting.addLast(next to tries + 1); resolveNext() } }, RETRY_RESOLVE_MS)
                        resolveNext()
                    }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val host = (info.host as? Inet4Address)?.hostAddress
                        if (gen == generation && host != null) {
                            val uuids = runCatching { info.attributes["ble-service-uuids"]?.let { String(it) } }.getOrNull().orEmpty()
                            found[info.serviceName] = DirConService(info.serviceName, host, uuids)
                            report()
                        }
                        resolveNext()
                    }
                }
            })
        }.onFailure { resolving = false }
    }

    private fun report() {
        val pick = Ss2kPick.pick(found.values, ownHosts())
        main.removeCallbacks(check); misses = 0
        if (pick != null) main.postDelayed(check, CHECK_MS)
        if (pick != last) { last = pick; log("SS2K finder: ${pick ?: "none"}"); onChange(pick) }
    }

    /** Is the found SS2K still answering on its DirCon port? (Off the main thread; the answer comes back on it.) */
    private fun checkStillThere() {
        val target = last ?: return
        if (inUse(target.host)) { misses = 0; main.postDelayed(check, CHECK_MS); return }
        val gen = generation
        Thread({
            val ok = runCatching { Socket().use { it.connect(InetSocketAddress(target.host, DIRCON_PORT), 1_500) } }.isSuccess
            main.post {
                if (gen != generation || last != target) return@post
                misses = if (ok) 0 else misses + 1
                if (misses >= CHECK_MISSES) {
                    log("SS2K finder: ${target.host} stopped answering")
                    found.values.removeAll { it.host == target.host }
                    report()
                } else main.postDelayed(check, CHECK_MS)
            }
        }, "gregario-ss2k-check").apply { isDaemon = true; start() }
    }

    companion object {
        const val SERVICE_TYPE = "_wahoo-fitness-tnp._tcp."
        private const val DIRCON_PORT = 8081
        private const val RESOLVE_TRIES = 3
        private const val RETRY_RESOLVE_MS = 1_000L
        private const val CHECK_MS = 20_000L
        private const val CHECK_MISSES = 3

        /** This tablet's own IPv4 addresses (Grupetto announces itself from one of them). */
        fun ownHosts(): Set<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.toSet()
        }.getOrDefault(emptySet())
    }
}
