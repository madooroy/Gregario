package com.madooroy.gregario.trainer

/**
 * A DirCon server announced on the Wi-Fi (mDNS "_wahoo-fitness-tnp._tcp"): its instance name, IPv4 address, and its
 * "ble-service-uuids" TXT record (the Bluetooth services it carries, which for an SS2K includes its own service).
 */
data class DirConService(val name: String, val host: String, val serviceUuids: String = "")

/**
 * Which announced DirCon server is the SmartSpin2K. Grupetto announces the same service from this tablet, and a Wahoo
 * trainer or a phone app elsewhere in the house can too, so only something that is plainly an SS2K is picked:
 * - its name says SmartSpin (SS2K announces itself under its device name, "SmartSpin2k" unless renamed), or
 * - it lists SS2K's own Bluetooth service ([SS2K_SERVICE]) among its services;
 * - never anything on this tablet (its own addresses, loopback) or named Grupetto.
 * A renamed SS2K without that service listed is not guessed at (review: a lone KICKR would have got the targets);
 * its address is typed once in Gregario's settings instead.
 */
object Ss2kPick {
    /** SmartSpin2K's own BLE service (firmware lib/SS2K/include/Constants.h), as SS2K lists it in its DirCon TXT record. */
    const val SS2K_SERVICE = "77776277-7877-7774-4466-896665500000"

    fun isSs2k(s: DirConService) =
        s.name.contains("smartspin", ignoreCase = true) || s.serviceUuids.contains(SS2K_SERVICE, ignoreCase = true)

    fun pick(found: Collection<DirConService>, ownHosts: Set<String>): DirConService? =
        found.firstOrNull { it.host !in ownHosts && !it.host.startsWith("127.") && !it.name.contains("grupetto", ignoreCase = true) && isSs2k(it) }
}
