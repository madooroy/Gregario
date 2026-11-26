package com.madooroy.gregario

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Gregario's own settings, in app-private storage (never in the repo). */
class AppSettings(context: Context) : com.madooroy.gregario.upload.StravaStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Open the panel when the tablet starts (shared by riders; read by [com.madooroy.gregario.ui.BootReceiver]). */
    private val mutableStartOnBoot = MutableStateFlow(prefs.getBoolean(KEY_START_ON_BOOT, false))
    val startOnBoot: StateFlow<Boolean> = mutableStartOnBoot
    fun setStartOnBoot(on: Boolean) { prefs.edit().putBoolean(KEY_START_ON_BOOT, on).apply(); mutableStartOnBoot.value = on }

    // ---- Riders: several people on one bike, each with their own intervals.icu, FTP and Strava. ----
    private val mutableRiders = MutableStateFlow(Riders.decode(prefs.getString("riders", null), prefs.getString("riderName", null).orEmpty()))
    val riders: StateFlow<List<Rider>> = mutableRiders
    private val mutableRider = MutableStateFlow(prefs.getString("rider", Riders.FIRST)?.takeIf { id -> mutableRiders.value.any { it.id == id } } ?: Riders.FIRST)
    /** The rider whose settings, rides and workout files are in use now. */
    val rider: StateFlow<String> = mutableRider
    val riderName get() = mutableRiders.value.firstOrNull { it.id == rider.value }?.name ?: "Rider 1"
    private fun k(base: String, id: String = rider.value) = Riders.key(base, id)

    /** Switch to another rider: every rider-scoped setting below now reads and writes theirs. */
    fun setRider(id: String) {
        if (mutableRiders.value.none { it.id == id }) return
        prefs.edit().putString("rider", id).apply(); mutableRider.value = id
        mutableApiKey.value = prefs.getString(k(KEY_API), "").orEmpty()
        mutableIcuToken.value = prefs.getString(k(KEY_ICU_TOKEN), "").orEmpty()
        mutableIcuAthlete.value = prefs.getString(k(KEY_ICU_ATHLETE), "").orEmpty()
        mutableManualFtp.value = prefs.getInt(k("manualFtp"), 0)
    }

    /** Add a rider (not switched to). Returns their id. */
    fun addRider(name: String): String {
        val id = Riders.newId(mutableRiders.value.map { it.id }, System.currentTimeMillis())
        saveRiders(mutableRiders.value + Rider(id, Riders.clean(name).ifBlank { "Rider ${mutableRiders.value.size + 1}" }))
        return id
    }

    fun renameRider(id: String, name: String) {
        val n = Riders.clean(name).ifBlank { return }
        if (id == Riders.FIRST) prefs.edit().putString("riderName", n).apply()
        saveRiders(mutableRiders.value.map { if (it.id == id) it.copy(name = n) else it })
    }

    /** Remove a rider other than the first, with all their settings. Their ride and workout folders are the caller's. */
    fun removeRider(id: String) {
        if (id == Riders.FIRST || mutableRiders.value.none { it.id == id }) return
        if (rider.value == id) setRider(Riders.FIRST)
        val e = prefs.edit(); prefs.all.keys.filter { it.endsWith("@$id") }.forEach { e.remove(it) }; e.commit()
        saveRiders(mutableRiders.value.filter { it.id != id })
    }

    private fun saveRiders(list: List<Rider>) {
        prefs.edit().putString("riders", Riders.encode(list)).apply()
        mutableRiders.value = Riders.decode(Riders.encode(list), list.first().name)
    }

    /** When someone last said who is riding (ms), and when the last ride ended: see [Riders.needsConfirm]. */
    var riderConfirmedAt: Long
        get() = prefs.getLong("riderConfirmedAt", 0)
        set(v) { prefs.edit().putLong("riderConfirmedAt", v).apply() }
    /**
     * A SmartSpin2K (this address) may still hold a target: Gregario closed while it couldn't be reached. The hand-back
     * is sent the next time Gregario connects to it (review: an SS2K was left in ERG after a Wi-Fi drop or a crash).
     */
    var ss2kOwedHost: String
        get() = prefs.getString("ss2kOwedHost", "").orEmpty()
        set(v) { prefs.edit().putString("ss2kOwedHost", v).apply() }

    var lastRideEndAt: Long
        get() = prefs.getLong("lastRideEndAt", 0)
        set(v) { prefs.edit().putLong("lastRideEndAt", v).apply() }

    /** Save a rider's last FTP under their own key, whoever is current (a Set FTP reply can arrive after a switch). */
    fun setLastFtpFor(id: String, w: Int) { prefs.edit().putInt(k(KEY_FTP, id), w).apply() }

    /** Another rider's uploads: their intervals.icu key and Strava sign-in, without switching to them. */
    fun storeFor(id: String): RiderStore = RiderStore(id)

    inner class RiderStore(val id: String) : com.madooroy.gregario.upload.StravaStore {
        val apiKey: String get() = prefs.getString(k(KEY_API, id), "").orEmpty()
        /** This rider's intervals.icu sign-in (preferred) or API key, for [com.madooroy.gregario.icu.IcuAuth]. */
        val icuCredential: String get() = credential(prefs.getString(k(KEY_ICU_TOKEN, id), "").orEmpty(), apiKey)
        // A rider without their own Strava API application uses the first rider's (Strava allows one athlete per
        // application until Strava raises the limit, so each rider may need their own: strava.com/settings/api).
        override var clientId: String
            get() = prefs.getString(k("stravaClientId", id), null)?.takeIf { it.isNotBlank() } ?: prefs.getString("stravaClientId", "").orEmpty()
            set(v) { prefs.edit().putString(k("stravaClientId", id), v).apply() }
        override var clientSecret: String
            get() = prefs.getString(k("stravaClientSecret", id), null)?.takeIf { it.isNotBlank() } ?: prefs.getString("stravaClientSecret", "").orEmpty()
            set(v) { prefs.edit().putString(k("stravaClientSecret", id), v).apply() }
        override var accessToken: String
            get() = prefs.getString(k("stravaAccess", id), "").orEmpty()
            set(v) { prefs.edit().putString(k("stravaAccess", id), v).apply() }
        override var expiresAt: Long
            get() = prefs.getLong(k("stravaExpires", id), 0)
            set(v) { prefs.edit().putLong(k("stravaExpires", id), v).apply() }
        // commit(), not apply(): a rotated refresh token must be on disk before the old one is forgotten.
        override var refreshToken: String
            get() = prefs.getString(k("stravaRefresh", id), "").orEmpty()
            set(v) { prefs.edit().putString(k("stravaRefresh", id), v).commit() }
    }

    /** "Send to the bike": a page on the tablet's Wi-Fi address for adding workout files from a phone (shared by riders). */
    private val mutableDropEnabled = MutableStateFlow(prefs.getBoolean("dropEnabled", false))
    val dropEnabled: StateFlow<Boolean> = mutableDropEnabled
    fun setDropEnabled(on: Boolean) { prefs.edit().putBoolean("dropEnabled", on).apply(); mutableDropEnabled.value = on }

    /**
     * Which bike Gregario rides through (shared by riders): Grupetto's DirCon on this tablet, or a SmartSpin2K's DirCon
     * over Wi-Fi at the typed address. The address is kept when switching back to Grupetto.
     */
    private val mutableBike = MutableStateFlow(com.madooroy.gregario.trainer.BikeChoice(
        com.madooroy.gregario.trainer.BikeChoice.Kind.of(prefs.getString("bike", null)), prefs.getString("ss2kHost", "").orEmpty()))
    val bike: StateFlow<com.madooroy.gregario.trainer.BikeChoice> = mutableBike
    fun setBike(b: com.madooroy.gregario.trainer.BikeChoice) {
        val c = b.copy(ss2kHost = b.ss2kHost.trim())
        prefs.edit().putString("bike", c.kind.key).putString("ss2kHost", c.ss2kHost).apply(); mutableBike.value = c
    }

    // ---- The current rider's settings ----
    private val mutableApiKey = MutableStateFlow(prefs.getString(k(KEY_API), "").orEmpty())
    val apiKey: StateFlow<String> = mutableApiKey
    fun setApiKey(key: String) { prefs.edit().putString(k(KEY_API), key.trim()).apply(); mutableApiKey.value = key.trim() }

    /** "Connect intervals.icu": the OAuth token and whose account it is, for the rider who started the sign-in. */
    private val mutableIcuToken = MutableStateFlow(prefs.getString(k(KEY_ICU_TOKEN), "").orEmpty())
    val icuToken: StateFlow<String> = mutableIcuToken
    private val mutableIcuAthlete = MutableStateFlow(prefs.getString(k(KEY_ICU_ATHLETE), "").orEmpty())
    val icuAthlete: StateFlow<String> = mutableIcuAthlete
    fun setIcuToken(token: String, athlete: String, riderId: String = rider.value) {
        prefs.edit().putString(k(KEY_ICU_TOKEN, riderId), token).putString(k(KEY_ICU_ATHLETE, riderId), athlete).commit()
        if (riderId == rider.value) { mutableIcuToken.value = token; mutableIcuAthlete.value = athlete }
    }
    /** What the current rider uses for intervals.icu: the sign-in if there is one, else the API key ("" = neither). */
    fun icuCredential(): String = credential(icuToken.value, apiKey.value)
    private fun credential(token: String, key: String) = token.takeIf { it.isNotBlank() }?.let { com.madooroy.gregario.icu.IcuAuth.OAUTH + it } ?: key

    /** Where the panel sits (dragged anywhere). Before Nov 20 only a corner was kept: bottom-left or bottom-right. */
    private val mutablePlacement = MutableStateFlow(PanelPlacement.decode(prefs.getString(KEY_PLACE, null))
        ?: PanelPlacement.corner(right = prefs.getBoolean(KEY_RIGHT, false), bottom = true))
    val placement: StateFlow<PanelPlacement> = mutablePlacement
    fun setPlacement(p: PanelPlacement) { prefs.edit().putString(KEY_PLACE, p.encode()).apply(); mutablePlacement.value = p }

    private val mutableCompact = MutableStateFlow(prefs.getBoolean(KEY_COMPACT, false))
    val compact: StateFlow<Boolean> = mutableCompact
    fun setCompact(compact: Boolean) { prefs.edit().putBoolean(KEY_COMPACT, compact).apply(); mutableCompact.value = compact }

    /** Panel size in percent (100 = 280 x 640 px on the Bike+ screen); everything in the panel scales with it. */
    private val mutablePanelScale = MutableStateFlow(prefs.getInt("panelScale", 125))
    val panelScale: StateFlow<Int> = mutablePanelScale
    fun setPanelScale(p: Int) { prefs.edit().putInt("panelScale", p).apply(); mutablePanelScale.value = p }
    /** How solid the panel's surfaces are (100, 85, 70 or 55 %); text, numbers, lines and borders stay solid. */
    private val mutablePanelOpacity = MutableStateFlow(prefs.getInt("panelOpacity", 100))
    val panelOpacity: StateFlow<Int> = mutablePanelOpacity
    fun setPanelOpacity(p: Int) { prefs.edit().putInt("panelOpacity", p).apply(); mutablePanelOpacity.value = p }

    /** Climb look-ahead in seconds (0, 1 or 2): harder targets go to the bike this early. Default 1 = on the "1". */
    private val mutableClimbLead = MutableStateFlow(prefs.getInt("climbLead", 1))
    val climbLead: StateFlow<Int> = mutableClimbLead
    fun setClimbLead(s: Int) { prefs.edit().putInt("climbLead", s).apply(); mutableClimbLead.value = s }

    /** FTP to ride workout files with when there is no intervals.icu key (0 = not set). */
    private val mutableManualFtp = MutableStateFlow(prefs.getInt(k("manualFtp"), 0))
    val manualFtp: StateFlow<Int> = mutableManualFtp
    fun setManualFtp(w: Int) { prefs.edit().putInt(k("manualFtp"), w).apply(); mutableManualFtp.value = w }

    /** Last FTP read from intervals.icu, for riding offline. */
    var lastFtp: Int
        get() = prefs.getInt(k(KEY_FTP), 0)
        set(v) { prefs.edit().putInt(k(KEY_FTP), v).apply() }

    /** Last workout used (raw intervals.icu event JSON), for riding offline. */
    var lastWorkoutJson: String?
        get() = prefs.getString(k(KEY_LAST), null)
        set(v) { prefs.edit().putString(k(KEY_LAST), v).apply() }

    // Strava sign-in of the current rider. Excluded from Android backup with the rest of this file.
    override var clientId: String
        get() = storeFor(rider.value).clientId
        set(v) { storeFor(rider.value).clientId = v }
    override var clientSecret: String
        get() = storeFor(rider.value).clientSecret
        set(v) { storeFor(rider.value).clientSecret = v }
    override var accessToken: String
        get() = storeFor(rider.value).accessToken
        set(v) { storeFor(rider.value).accessToken = v }
    override var expiresAt: Long
        get() = storeFor(rider.value).expiresAt
        set(v) { storeFor(rider.value).expiresAt = v }
    override var refreshToken: String
        get() = storeFor(rider.value).refreshToken
        set(v) { storeFor(rider.value).refreshToken = v }

    companion object {
        const val PREFS = "gregario"
        private const val KEY_START_ON_BOOT = "startOnBoot"
        /** For the boot receiver, before anything else of Gregario is up. */
        fun startOnBootSet(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_START_ON_BOOT, false)

        const val KEY_ICU_TOKEN = "icuToken"; const val KEY_ICU_ATHLETE = "icuAthlete"
        const val KEY_API = "icuApiKey"; const val KEY_RIGHT = "panelRight"; const val KEY_PLACE = "panelPlace"; const val KEY_COMPACT = "panelCompact"
        const val KEY_FTP = "lastFtp"; const val KEY_LAST = "lastWorkoutJson"
    }
}
