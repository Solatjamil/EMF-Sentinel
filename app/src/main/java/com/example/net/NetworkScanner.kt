package com.example.net

import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileReader
import java.net.InetAddress
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** Snapshot of the currently connected Wi-Fi network (real radio data). */
data class WifiContextInfo(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int?,
    val frequencyMhz: Int?,
    val linkSpeedMbps: Int?,
    val estimatedDistanceMeters: Double?
)

/** A real device discovered on the local network. */
data class LanDevice(
    val ip: String,
    val mac: String,
    val vendor: String,
    val reachable: Boolean,
    /** True when the MAC OUI belongs to a chipset commonly used by IP/spy cameras. */
    val cameraChipsetSuspect: Boolean = false
)

/**
 * Real Wi-Fi telemetry + LAN discovery.
 *
 * Honesty notes:
 *  - [estimateDistanceMeters] is free-space path loss; walls/furniture add 3–15 dB each,
 *    so real distances are often 2–4x the estimate. It is an ESTIMATE, not a measurement.
 *  - [scanLocalDevices] enumerates devices ON the same network (ARP cache + bounded ICMP
 *    sweep) — the same technique Fing-style apps use. It cannot see devices' GPS rooms;
 *    placement on the floor plan is done by the user.
 */
object NetworkScanner {

    fun currentWifiInfo(context: Context): WifiContextInfo {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo ?: return WifiContextInfo(null, null, null, null, null, null)
            if (info.networkId == -1 || info.rssi == 0) {
                return WifiContextInfo(null, null, null, null, null, null)
            }
            var ssid = info.ssid?.removePrefix("\"")?.removeSuffix("\"")
            if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") ssid = null
            val freq = info.frequency.takeIf { it > 0 }
            WifiContextInfo(
                ssid = ssid,
                bssid = info.bssid,
                rssi = info.rssi,
                frequencyMhz = freq,
                linkSpeedMbps = info.linkSpeed,
                estimatedDistanceMeters = if (freq != null) estimateDistanceMeters(info.rssi, freq) else null
            )
        } catch (t: Throwable) {
            WifiContextInfo(null, null, null, null, null, null)
        }
    }

    /** Free-space path-loss distance estimate in meters from RSSI + frequency. */
    fun estimateDistanceMeters(rssiDbm: Int, freqMhz: Int): Double {
        if (freqMhz <= 0 || rssiDbm == 0) return 0.0
        val d = 10.0.pow((27.55 - (20.0 * log10(freqMhz.toDouble())) + abs(rssiDbm)) / 20.0)
        return (d * 10.0).toInt() / 10.0
    }

    /**
     * Discovers live devices on the local /24 subnet of the connected gateway:
     * 1) passive /proc/net/arp read, 2) bounded concurrent ICMP sweep, 3) ARP re-read
     * for MACs + OUI vendor mapping. Runs on Dispatchers.IO.
     */
    suspend fun scanLocalDevices(context: Context, maxHosts: Int = 254): List<LanDevice> =
        withContext(Dispatchers.IO) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val dhcp: DhcpInfo = wm.dhcpInfo ?: return@withContext emptyList()
                val gateway = intToIp(dhcp.gateway)
                if (gateway == "0.0.0.0") return@withContext emptyList()
                val subnet = gateway.substringBeforeLast('.')
                val selfIp = intToIp(dhcp.ipAddress)

                // Passive first: anything already in the kernel ARP cache.
                val passive = readArpTable().associateBy { it.ip }

                // Active sweep (bounded, concurrent, short timeout).
                val jobs = (1..maxHosts).map { host ->
                    async {
                        val ip = "$subnet.$host"
                        try {
                            if (InetAddress.getByName(ip).isReachable(200)) ip else null
                        } catch (t: Throwable) {
                            null
                        }
                    }
                }
                val reachable = jobs.awaitAll().filterNotNull().toMutableSet()
                reachable.add(selfIp)
                reachable.add(gateway)

                val arpAfter = readArpTable()
                val byIp = (passive + arpAfter.associateBy { it.ip })

                reachable
                    .sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 999 }
                    .map { ip ->
                        val cached = byIp[ip]
                        val mac = cached?.mac.orEmpty()
                        val isGateway = ip == gateway
                        LanDevice(
                            ip = ip,
                            mac = mac,
                            vendor = when {
                                isGateway -> "Your Router / Gateway"
                                ip == selfIp -> "This Phone"
                                else -> vendorFromMac(mac)
                            },
                            reachable = true,
                            cameraChipsetSuspect = !isGateway && isCameraChipset(mac)
                        )
                    }
                    .distinctBy { it.ip }
            } catch (t: Throwable) {
                emptyList()
            }
        }

    fun readArpTable(): List<LanDevice> {
        val devices = mutableListOf<LanDevice>()
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                reader.readLine() // header
                var line = reader.readLine()
                while (line != null) {
                    val parts = line.split("\\s+".toRegex()).filter { it.isNotBlank() }
                    if (parts.size >= 6) {
                        val ip = parts[0]
                        val flags = parts[2]
                        val mac = parts[3].uppercase()
                        if (flags != "0x0" && mac != "00:00:00:00:00:00") {
                            devices.add(
                                LanDevice(
                                    ip = ip,
                                    mac = mac,
                                    vendor = vendorFromMac(mac),
                                    reachable = true,
                                    cameraChipsetSuspect = isCameraChipset(mac)
                                )
                            )
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (t: Throwable) {
            // Best-effort — some devices restrict /proc/net/arp.
        }
        return devices.distinctBy { it.ip }
    }

    private fun intToIp(i: Int): String =
        "${i and 0xFF}.${i shr 8 and 0xFF}.${i shr 16 and 0xFF}.${i shr 24 and 0xFF}"

    private fun oui(mac: String): String = mac.replace(":", "").take(6)

    fun vendorFromMac(mac: String): String {
        if (mac.isBlank()) return "Unknown device"
        return OUI_VENDORS[oui(mac)] ?: "Unknown vendor"
    }

    private fun isCameraChipset(mac: String): Boolean = oui(mac) in CAMERA_CHIPSET_OUIS

    /** OUI prefixes — common consumer devices + IP camera / spy-cam chipsets. */
    private val OUI_VENDORS: Map<String, String> = mapOf(
        "3C5A37" to "Google", "F88FCA" to "Google", "94EB2C" to "Google", "546009" to "Google",
        "001A11" to "Google", "D83ADD" to "Google", "A47733" to "Google",
        "F4F5D8" to "Google", "F4F5E8" to "Google", "3814B5" to "Google",
        "FCD733" to "Apple", "A4B197" to "Apple", "3C2EFF" to "Apple", "F0B479" to "Apple",
        "BC9FEF" to "Apple", "649ABE" to "Apple", "88C663" to "Apple", "EC35B3" to "Apple",
        "84FCFE" to "Apple", "70A2B3" to "Apple", "4898CA" to "Samsung", "E8508B" to "Samsung",
        "8CF5A3" to "Samsung", "B47C9C" to "Samsung", "34C3AC" to "Samsung", "D0DFC7" to "Samsung",
        "6CB7F4" to "Samsung", "C06599" to "Samsung", "784476" to "Xiaomi", "64CC2E" to "Xiaomi",
        "50EC50" to "Xiaomi", "9C9D7E" to "Xiaomi", "F8A45F" to "Xiaomi", "28E31F" to "Xiaomi",
        "24CF24" to "Huawei", "407D0F" to "Huawei", "DCD916" to "Huawei", "ACE215" to "Huawei",
        "F4C714" to "Huawei", "10C61F" to "Huawei", "50A72B" to "TP-Link", "F4EC38" to "TP-Link",
        "B04E26" to "TP-Link", "30B5C2" to "TP-Link", "60E327" to "TP-Link", "149D99" to "TP-Link",
        "1C3BF3" to "TP-Link", "C025E9" to "TP-Link", "28EE52" to "D-Link", "340804" to "D-Link",
        "B8A386" to "D-Link", "9CD643" to "D-Link", "E46F13" to "Netgear", "A42BB0" to "Netgear",
        "100D7F" to "Netgear", "C40415" to "Netgear", "744401" to "Netgear", "08BD43" to "Netgear",
        "FC528D" to "Espressif (IoT/ESP cam chipset)", "24D7EB" to "Espressif (IoT/ESP cam chipset)",
        "246F28" to "Espressif (IoT/ESP cam chipset)", "30AEA4" to "Espressif (IoT/ESP cam chipset)",
        "3C71BF" to "Espressif (IoT/ESP cam chipset)", "7C9EBD" to "Espressif (IoT/ESP cam chipset)",
        "8CAAB5" to "Espressif (IoT/ESP cam chipset)", "AC67B2" to "Espressif (IoT/ESP cam chipset)",
        "B4E62D" to "Espressif (IoT/ESP cam chipset)", "C4DD57" to "Espressif (IoT/ESP cam chipset)",
        "D8A01D" to "Espressif (IoT/ESP cam chipset)", "E8DB84" to "Espressif (IoT/ESP cam chipset)",
        "14A0F8" to "Hikvision IP camera", "4432C8" to "Hikvision IP camera",
        "54880D" to "Hikvision IP camera", "685E1C" to "Hikvision IP camera",
        "28B44C" to "Hikvision IP camera", "B4A384" to "Hikvision IP camera",
        "3CE3E7" to "Dahua IP camera", "9002A9" to "Dahua IP camera", "A44E2D" to "Dahua IP camera",
        "4C11BF" to "Dahua IP camera", "E0508B" to "Dahua IP camera", "BC322D" to "Dahua IP camera",
        "D850E6" to "ASUSTek", "049226" to "ASUSTek", "10BF48" to "ASUSTek", "B06EBF" to "ASUSTek",
        "34CE00" to "Xiaomi Comm.", "9C99A0" to "Xiaomi Comm.", "F483CD" to "Xiaomi Comm.",
        "EC41E7" to "Tuya Smart (IoT)", "D4A651" to "Tuya Smart (IoT)", "10D561" to "Tuya Smart (IoT)",
        "50895C" to "Tuya Smart (IoT)", "84E342" to "Tuya Smart (IoT)", "1869D8" to "Tuya Smart (IoT)",
        "441A91" to "Wyze Cam", "D03F27" to "Wyze Cam", "2CAA8E" to "Wyze Cam",
        "B48A0A" to "Amazon (Ring/Echo)", "34D270" to "Amazon (Ring/Echo)",
        "44650D" to "Amazon (Ring/Echo)", "6854FD" to "Amazon (Ring/Echo)",
        "74C246" to "Amazon (Ring/Echo)", "F0272D" to "Amazon (Ring/Echo)",
        "00044B" to "Nvidia", "48B02D" to "Nvidia", "E005C5" to "Intel", "84A9C4" to "Intel",
        "8C8D28" to "Intel", "F83441" to "Intel", "9463D1" to "Intel", "A0C589" to "Intel",
        "B49691" to "Intel", "DC962C" to "Intel", "001302" to "Intel",
        "D4BE74" to "LG Electronics", "C488E5" to "LG Electronics", "64BC58" to "LG Electronics",
        "7888CA" to "LG Electronics", "A89255" to "LG Electronics", "1C5A3E" to "Sony",
        "30899B" to "Sony", "4C21D0" to "Sony", "78C881" to "Sony", "AC9B0A" to "Sony",
        "B0C5CA" to "Sony", "00E04C" to "Realtek (IoT/cam chip)", "525400" to "QEMU/VM",
        "0242AC" to "Docker bridge", "DCA632" to "Raspberry Pi", "B827EB" to "Raspberry Pi",
        "E45F01" to "Raspberry Pi", "DC6672" to "Xiaomi Comm.", "28D127" to "Xiaomi Comm.",
        "74A528" to "Amazon (Ring/Echo)", "E01C41" to "Ring (doorbell cam)",
        "9C7613" to "Ring (doorbell cam)", "C05627" to "Ring (doorbell cam)",
        "A4DA22" to "Xiaomi Comm.", "68B599" to "Xiaomi Comm.", "04CF8C" to "Xiaomi Comm.",
        "0C1DAF" to "Xiaomi Comm.", "940E6B" to "Huawei Tech", "48AD08" to "Huawei Tech",
        "ACE87B" to "Huawei Tech", "203D66" to "TP-Link Systems", "54AF97" to "TP-Link Systems",
        "5C628B" to "TP-Link Systems", "A42A95" to "TP-Link Systems"
    )

    /** OUI prefixes strongly associated with networked camera products. */
    private val CAMERA_CHIPSET_OUIS: Set<String> = setOf(
        "14A0F8", "4432C8", "54880D", "685E1C", "28B44C", "B4A384", // Hikvision
        "3CE3E7", "9002A9", "A44E2D", "4C11BF", "E0508B", "BC322D", // Dahua
        "441A91", "D03F27", "2CAA8E", // Wyze
        "E01C41", "9C7613", "C05627", // Ring
        "FC528D", "24D7EB", "246F28", "30AEA4", "3C71BF", "7C9EBD"  // Espressif ESP-CAM
    )
}

/**
 * Defensive RF monitor — detects the *signature of someone else jamming/interfering*
 * with nearby Wi-Fi: a synchronized, across-the-board RSSI collapse of all visible
 * APs plus the connected network within a short window. Purely passive and legal.
 */
class JammingDetector(private val windowSize: Int = 6) {

    private val history = ArrayDeque<Pair<List<Int>, Int?>>()

    /**
     * Feed the latest scan snapshot (RSSI levels of visible APs) + the connected RSSI.
     * Returns true when the recent window shows a suspicious synchronized drop.
     */
    fun evaluate(apLevels: List<Int>, connectedRssi: Int?): Boolean {
        history.addLast(apLevels.filter { it < 0 } to connectedRssi)
        while (history.size > windowSize) history.removeFirst()
        if (history.size < 4) return false

        val oldest = history.first()
        val newest = history.last()
        if (oldest.first.size < 3 || newest.first.size < 3) return false

        val oldAvg = oldest.first.average()
        val newAvg = newest.first.average()
        val connectedDrop = if (oldest.second != null && newest.second != null) {
            (oldest.second ?: 0) - (newest.second ?: 0)
        } else 0

        // Signature: everything nearby weakened together by >15 dB and the connected
        // link by >12 dB — consistent with broadband interference, not normal fading.
        return (oldAvg - newAvg > 15.0) && connectedDrop > 12
    }

    fun reset() = history.clear()
}
