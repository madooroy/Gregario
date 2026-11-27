package com.madooroy.gregario.trainer

/**
 * Which Peloton the tablet belongs to, for the ride description ("Ridden on a Peloton Bike+ …").
 * Peloton keeps it in the tablet's global setting `peloton_platform`: "titan" is the Bike+, "v1" the original Bike.
 * The Bike+ tablet (model PLTN-T…) is also used by the Tread and Row, so without the setting only a non-Bike+ Peloton
 * tablet can be named (the original Bike). Anything else: null, and the description stays generic.
 */
object Machine {
    fun name(platform: String?, brand: String?, model: String?): String? {
        val p = platform?.trim()?.lowercase()
        return when {
            p == "titan" -> "Peloton Bike+"
            p == "v1" -> "Peloton Bike"
            !p.isNullOrEmpty() -> null                                         // a Tread, Row or something new: not a bike we know
            brand.equals("Peloton", ignoreCase = true) && model != null && !model.contains("PLTN-T") -> "Peloton Bike"
            else -> null
        }
    }

    /** On the tablet: read the setting (unreadable is just unknown). */
    /**
     * A Peloton tablet, where Grupetto can run: the tablet's peloton_platform setting is there (any Peloton machine), its
     * brand is Peloton, or Grupetto is installed. Anywhere else (a Samsung, a Fire, the emulator) the only bike is a
     * SmartSpin2K on the Wi-Fi. The emulator plays a Bike+ with: adb shell settings put global peloton_platform titan
     */
    fun isPeloton(platform: String?, brand: String?, grupettoInstalled: Boolean): Boolean =
        !platform.isNullOrBlank() || brand.equals("Peloton", ignoreCase = true) || grupettoInstalled

    fun isPeloton(context: android.content.Context): Boolean = isPeloton(platform(context), android.os.Build.BRAND,
        runCatching { context.packageManager.getLaunchIntentForPackage(GRUPETTO) != null }.getOrDefault(false))

    private const val GRUPETTO = "com.spop.poverlay"

    private fun platform(context: android.content.Context): String? =
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "peloton_platform") }.getOrNull()

    fun of(context: android.content.Context): String? = name(
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "peloton_platform") }.getOrNull(),
        android.os.Build.BRAND, android.os.Build.MODEL)
}
