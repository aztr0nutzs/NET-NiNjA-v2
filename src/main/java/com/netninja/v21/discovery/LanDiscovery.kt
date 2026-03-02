package com.netninja.v21.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

private fun round1(value: Double): Double = ((value * 10.0).roundToInt()) / 10.0

class LanDiscovery(
  context: Context,
) {
  data class ScanConfig(
    val maxConcurrency: Int = 64,
    val probeTimeoutMs: Int = 325,
    val maxTargets: Int = 254,
    val maxSubnetPrefix: Int = 24,
    val allowPortFallback: Boolean = true,
    val fallbackPorts: List<Int> = listOf(443, 80, 53, 22, 554),
  )

  data class LanDevice(
    val ipAddress: String,
    val macAddress: String? = null,
    val hostname: String? = null,
    val vendor: String? = null,
    val rttMs: Double? = null,
    val reachable: Boolean = false,
    val deviceType: String? = null,
    val isGateway: Boolean = false,
    val isLocalDevice: Boolean = false,
    val method: String = "unknown",
    val metadata: JSONObject = JSONObject(),
  ) {
    fun toJson(): JSONObject =
      JSONObject()
        .put("ipAddress", ipAddress)
        .put("macAddress", macAddress ?: JSONObject.NULL)
        .put("hostname", hostname ?: JSONObject.NULL)
        .put("vendor", vendor ?: JSONObject.NULL)
        .put("rttMs", rttMs?.let(::round1) ?: JSONObject.NULL)
        .put("reachable", reachable)
        .put("deviceType", deviceType ?: JSONObject.NULL)
        .put("isGateway", isGateway)
        .put("isLocalDevice", isLocalDevice)
        .put("method", method)
        .put("metadata", metadata)
  }

  sealed class Event {
    data class Status(val payload: JSONObject) : Event()
    data class Device(val payload: JSONObject) : Event()
    data class Done(val payload: JSONObject) : Event()
    data class Error(val payload: JSONObject) : Event()
  }

  private data class NetworkContext(
    val network: Network,
    val interfaceName: String?,
    val subnetCidr: String,
    val subnetBase: Long,
    val localIp: String,
    val gatewayIp: String?,
    val targets: List<String>,
    val capped: Boolean,
    val originalPrefix: Int,
    val effectivePrefix: Int,
  )

  private data class FallbackAddress(
    val hostAddress: String,
    val prefixLength: Int,
  )

  private data class DhcpSnapshot(
    val localIp: String?,
    val gatewayIp: String?,
    val prefixLength: Int?,
  )

  private data class SelectedNetwork(
    val network: Network,
    val linkProperties: LinkProperties,
  )

  private data class TcpProbeResult(
    val reachable: Boolean,
    val method: String,
  )

  private val appContext = context.applicationContext
  private val connectivityManager =
    appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val wifiManager =
    appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val deviceCache = ConcurrentHashMap<String, LanDevice>()
  private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

  @Volatile
  private var activeJob: Job? = null

  @Volatile
  private var lastSnapshot = JSONObject()
    .put("capturedAt", 0L)
    .put("count", 0)
    .put("devices", JSONArray())

  fun startScan(
    config: ScanConfig,
    callback: (Event) -> Unit,
  ) {
    scope.launch {
      stopInternal()
      activeJob = launch {
        runCatching {
          deviceCache.clear()
          val networkContext = resolveNetworkContext(config)
          callback(
            Event.Status(
              JSONObject()
                .put("status", "started")
                .put("subnet", networkContext.subnetCidr)
                .put("gatewayIp", networkContext.gatewayIp ?: JSONObject.NULL)
                .put("localIp", networkContext.localIp)
                .put("totalTargets", networkContext.targets.size)
                .put("capped", networkContext.capped)
                .put(
                  "message",
                  if (networkContext.capped) {
                    "Large subnet detected. Scan capped to /${networkContext.effectivePrefix}."
                  } else {
                    "Scanning local subnet ${networkContext.subnetCidr}."
                  },
                ),
            ),
          )

          seedKnownDevices(networkContext, callback)
          scanTargets(networkContext, config, callback)
        }.onFailure { error ->
          if (error is CancellationException) return@onFailure
          callback(
            Event.Error(
              JSONObject()
                .put("status", "error")
                .put("message", error.message ?: "LAN discovery failed."),
            ),
          )
        }
      }
    }
  }

  fun stopScan() {
    scope.launch { stopInternal() }
  }

  fun getLastSnapshotJson(): String = lastSnapshot.toString()

  fun canScan(): Boolean = findPreferredLanNetwork() != null

  private suspend fun stopInternal() {
    activeJob?.cancelAndJoin()
    activeJob = null
    activeSockets.toList().forEach { socket ->
      runCatching { socket.close() }
      activeSockets -= socket
    }
  }

  private suspend fun scanTargets(
    networkContext: NetworkContext,
    config: ScanConfig,
    callback: (Event) -> Unit,
  ) = coroutineScope {
    val semaphore = Semaphore(config.maxConcurrency.coerceIn(8, 64))
    val totalTargets = networkContext.targets.size
    val processed = AtomicInteger(0)

    networkContext.targets.map { ip ->
      async {
        semaphore.withPermit {
          currentCoroutineContext().ensureActive()
          val device = probeHost(ip, networkContext, config)
          val scanned = processed.incrementAndGet()
          callback(
            Event.Status(
              JSONObject()
                .put("status", "progress")
                .put("progress", scanned.toDouble() / max(1, totalTargets).toDouble())
                .put("scanned", scanned)
                .put("totalTargets", totalTargets)
                .put("count", deviceCache.size)
                .put("subnet", networkContext.subnetCidr),
            ),
          )
          device?.let { upsertDevice(it, callback) }
        }
      }
    }.awaitAll()

    harvestArpPeers(networkContext, callback)
    val snapshot = buildSnapshot(networkContext, done = true)
    lastSnapshot = snapshot
    callback(Event.Done(snapshot))
  }

  private fun seedKnownDevices(
    networkContext: NetworkContext,
    callback: (Event) -> Unit,
  ) {
    val arpTable = readArpTable()
    val localDevice = LanDevice(
      ipAddress = networkContext.localIp,
      macAddress = arpTable[networkContext.localIp],
      hostname = resolveLocalHostname(),
      vendor = lookupVendor(arpTable[networkContext.localIp]),
      rttMs = 0.0,
      reachable = true,
      deviceType = "local-device",
      isLocalDevice = true,
      method = "local",
    )
    upsertDevice(localDevice, callback)

    networkContext.gatewayIp?.let { gatewayIp ->
      val gateway = LanDevice(
        ipAddress = gatewayIp,
        macAddress = arpTable[gatewayIp],
        hostname = "Gateway",
        vendor = lookupVendor(arpTable[gatewayIp]),
        reachable = true,
        deviceType = "gateway",
        isGateway = true,
        method = "gateway",
      )
      upsertDevice(gateway, callback)
    }
  }

  private fun upsertDevice(
    device: LanDevice,
    callback: (Event) -> Unit,
  ) {
    val existing = deviceCache[device.ipAddress]
    val merged = mergeDevices(existing, device)
    val changed = existing == null || existing.toJson().toString() != merged.toJson().toString()
    deviceCache[device.ipAddress] = merged
    if (!changed) return
    callback(
      Event.Device(
        JSONObject()
          .put("action", if (existing == null) "deviceFound" else "deviceUpdated")
          .put("device", merged.toJson()),
      ),
    )
  }

  private fun mergeDevices(
    existing: LanDevice?,
    incoming: LanDevice,
  ): LanDevice {
    if (existing == null) return incoming
    return LanDevice(
      ipAddress = incoming.ipAddress,
      macAddress = incoming.macAddress ?: existing.macAddress,
      hostname = incoming.hostname ?: existing.hostname,
      vendor = incoming.vendor ?: existing.vendor,
      rttMs = incoming.rttMs ?: existing.rttMs,
      reachable = incoming.reachable || existing.reachable,
      deviceType = incoming.deviceType ?: existing.deviceType,
      isGateway = incoming.isGateway || existing.isGateway,
      isLocalDevice = incoming.isLocalDevice || existing.isLocalDevice,
      method = if (incoming.method != "unknown") incoming.method else existing.method,
      metadata = JSONObject(existing.metadata.toString()).apply {
        incoming.metadata.keys().forEach { key -> put(key, incoming.metadata.opt(key)) }
      },
    )
  }

  private fun resolveNetworkContext(config: ScanConfig): NetworkContext {
    val selectedNetwork = findPreferredLanNetwork() ?: error("No active LAN network.")
    val linkProperties = selectedNetwork.linkProperties
    val linkAddress = linkProperties.linkAddresses.firstOrNull(::isUsableIpv4LinkAddress)
    val dhcpSnapshot = dhcpSnapshot()
    val fallbackAddress = if (linkAddress == null) fallbackIpv4Address() else null
    val localIp = linkAddress?.address?.hostAddress ?: dhcpSnapshot.localIp ?: fallbackAddress?.hostAddress
      ?: error("No usable IPv4 address found on the active network.")
    val routeGateway = linkProperties.routes.firstOrNull { it.isDefaultGatewayV4() }?.gateway?.hostAddress
    val gatewayIp = routeGateway ?: dhcpSnapshot.gatewayIp

    val originalPrefix =
      (linkAddress?.prefixLength ?: dhcpSnapshot.prefixLength ?: fallbackAddress?.prefixLength ?: 24)
        .coerceIn(8, 30)
    val effectivePrefix = max(originalPrefix, config.maxSubnetPrefix.coerceIn(16, 30))
    val capped = originalPrefix < effectivePrefix
    val hostTargets = buildTargetIps(localIp, effectivePrefix)
      .filter { it != localIp }
      .take(config.maxTargets.coerceIn(32, 512))
    val subnetBase = ipToLong(networkAddress(localIp, effectivePrefix))

    return NetworkContext(
      network = selectedNetwork.network,
      interfaceName = linkProperties.interfaceName,
      subnetCidr = "${networkAddress(localIp, effectivePrefix)}/$effectivePrefix",
      subnetBase = subnetBase,
      localIp = localIp,
      gatewayIp = gatewayIp,
      targets = hostTargets,
      capped = capped || hostTargets.size >= config.maxTargets,
      originalPrefix = originalPrefix,
      effectivePrefix = effectivePrefix,
    )
  }

  private fun probeHost(
    ip: String,
    networkContext: NetworkContext,
    config: ScanConfig,
  ): LanDevice? {
    val arpBefore = readArpTable()
    val inetAddress = InetAddress.getByName(ip)

    val startedAt = System.nanoTime()
    var reachable = icmpReachable(inetAddress, networkContext.interfaceName, config.probeTimeoutMs)
    var method = "icmp"
    var rttMs = if (reachable) (System.nanoTime() - startedAt) / 1_000_000.0 else null

    if (!reachable && config.allowPortFallback) {
      for (port in config.fallbackPorts.take(5)) {
        val portStartedAt = System.nanoTime()
        val tcpResult = tcpReachable(networkContext, ip, port, config.probeTimeoutMs)
        if (tcpResult.reachable) {
          reachable = true
          method = tcpResult.method
          rttMs = (System.nanoTime() - portStartedAt) / 1_000_000.0
          break
        }
      }
    }

    val arpAfter = readArpTable()
    val mac = arpAfter[ip] ?: arpBefore[ip]
    if (!reachable && mac.isNullOrBlank()) return null
    if (!reachable && !mac.isNullOrBlank()) {
      reachable = true
      method = "arp"
    }

    val hostname = resolveHostname(inetAddress)
    val vendor = lookupVendor(mac)
    return LanDevice(
      ipAddress = ip,
      macAddress = mac,
      hostname = hostname,
      vendor = vendor,
      rttMs = rttMs,
      reachable = reachable,
      deviceType = guessDeviceType(ip, hostname, vendor, networkContext),
      isGateway = ip == networkContext.gatewayIp,
      isLocalDevice = ip == networkContext.localIp,
      method = method,
      metadata = JSONObject()
        .put("subnet", networkContext.subnetCidr)
        .put("reachable", reachable)
        .put("interface", networkContext.interfaceName ?: JSONObject.NULL)
        .put("source", "lan-discovery"),
    )
  }

  private fun icmpReachable(
    inetAddress: InetAddress,
    interfaceName: String?,
    timeoutMs: Int,
  ): Boolean {
    val networkInterface = interfaceName?.let { name ->
      runCatching { NetworkInterface.getByName(name) }.getOrNull()
    }
    val timeout = timeoutMs.coerceAtLeast(150)
    return runCatching {
      if (networkInterface != null) {
        inetAddress.isReachable(networkInterface, 1, timeout)
      } else {
        inetAddress.isReachable(timeout)
      }
    }.getOrDefault(false)
  }

  private fun tcpReachable(
    networkContext: NetworkContext,
    ip: String,
    port: Int,
    timeoutMs: Int,
  ): TcpProbeResult {
    val socket = runCatching { networkContext.network.socketFactory.createSocket() }.getOrElse { Socket() }
    activeSockets += socket
    return try {
      socket.connect(InetSocketAddress(ip, port), timeoutMs.coerceAtLeast(120))
      TcpProbeResult(reachable = true, method = "tcp:$port")
    } catch (_: ConnectException) {
      TcpProbeResult(reachable = true, method = "tcp-rst:$port")
    } catch (_: SocketTimeoutException) {
      TcpProbeResult(reachable = false, method = "tcp-timeout:$port")
    } catch (_: NoRouteToHostException) {
      TcpProbeResult(reachable = false, method = "tcp-unroutable:$port")
    } catch (error: SocketException) {
      val message = error.message?.lowercase(Locale.US).orEmpty()
      if ("connection refused" in message) {
        TcpProbeResult(reachable = true, method = "tcp-rst:$port")
      } else {
        TcpProbeResult(reachable = false, method = "tcp-error:$port")
      }
    } catch (_: Exception) {
      TcpProbeResult(reachable = false, method = "tcp-error:$port")
    } finally {
      activeSockets -= socket
      runCatching { socket.close() }
    }
  }

  private fun harvestArpPeers(
    networkContext: NetworkContext,
    callback: (Event) -> Unit,
  ) {
    readArpTable()
      .filterKeys { ip -> isIpInSubnet(ip, networkContext.subnetBase, networkContext.effectivePrefix) }
      .forEach { (ip, mac) ->
        val existing = deviceCache[ip]
        val inetAddress = runCatching { InetAddress.getByName(ip) }.getOrNull()
        val method = existing?.method?.takeIf { it.isNotBlank() && it != "unknown" } ?: "arp"
        upsertDevice(
          LanDevice(
            ipAddress = ip,
            macAddress = mac,
            hostname = existing?.hostname ?: inetAddress?.let(::resolveHostname),
            vendor = existing?.vendor ?: lookupVendor(mac),
            rttMs = existing?.rttMs,
            reachable = true,
            deviceType = existing?.deviceType ?: guessDeviceType(ip, existing?.hostname, existing?.vendor ?: lookupVendor(mac), networkContext),
            isGateway = ip == networkContext.gatewayIp,
            isLocalDevice = ip == networkContext.localIp,
            method = method,
            metadata = JSONObject(existing?.metadata?.toString() ?: "{}")
              .put("subnet", networkContext.subnetCidr)
              .put("interface", networkContext.interfaceName ?: JSONObject.NULL)
              .put("source", "arp-cache"),
          ),
          callback,
        )
      }
  }

  private fun buildSnapshot(
    networkContext: NetworkContext,
    done: Boolean,
  ): JSONObject {
    val devices = deviceCache.values
      .sortedWith(
        compareByDescending<LanDevice> { it.isGateway }
          .thenByDescending { it.isLocalDevice }
          .thenBy { ipToLong(it.ipAddress) },
      )

    return JSONObject()
      .put("status", if (done) "done" else "progress")
      .put("capturedAt", System.currentTimeMillis())
      .put("subnet", networkContext.subnetCidr)
      .put("gatewayIp", networkContext.gatewayIp ?: JSONObject.NULL)
      .put("localIp", networkContext.localIp)
      .put("count", devices.size)
      .put("capped", networkContext.capped)
      .put(
        "devices",
        JSONArray().apply {
          devices.forEach { put(it.toJson()) }
        },
      )
  }

  private fun resolveHostname(inetAddress: InetAddress): String? =
    runCatching {
      val canonical = inetAddress.canonicalHostName?.trim().orEmpty()
      canonical.takeIf { it.isNotEmpty() && it != inetAddress.hostAddress }
    }.getOrNull()

  private fun resolveLocalHostname(): String? =
    runCatching { InetAddress.getLocalHost().hostName?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

  private fun readArpTable(): Map<String, String> =
    runCatching {
      File("/proc/net/arp")
        .takeIf { it.exists() }
        ?.readLines()
        ?.drop(1)
        ?.mapNotNull { line ->
          val parts = line.trim().split(Regex("\\s+"))
          if (parts.size < 4) return@mapNotNull null
          val ip = parts[0]
          val mac = parts[3].takeIf { it.matches(Regex("..:..:..:..:..:..")) } ?: return@mapNotNull null
          ip to mac.uppercase(Locale.US)
        }
        ?.toMap()
        ?: emptyMap()
    }.getOrDefault(emptyMap())

  private fun lookupVendor(macAddress: String?): String? {
    val prefix = macAddress
      ?.uppercase(Locale.US)
      ?.replace("-", ":")
      ?.split(":")
      ?.take(3)
      ?.joinToString(":")
      ?: return null
    return OUI_VENDOR_MAP[prefix]
  }

  private fun guessDeviceType(
    ip: String,
    hostname: String?,
    vendor: String?,
    networkContext: NetworkContext,
  ): String {
    if (ip == networkContext.gatewayIp) return "gateway"
    if (ip == networkContext.localIp) return "local-device"
    val haystack = listOfNotNull(hostname, vendor).joinToString(" ").lowercase(Locale.US)
    return when {
      "printer" in haystack || "epson" in haystack || "hp " in haystack -> "printer"
      "camera" in haystack || "nest" in haystack || "ring" in haystack -> "camera"
      "iphone" in haystack || "android" in haystack || "pixel" in haystack || "samsung" in haystack -> "mobile"
      "tv" in haystack || "roku" in haystack || "chromecast" in haystack || "apple tv" in haystack -> "media"
      "desktop" in haystack || "laptop" in haystack || "pc" in haystack || "intel" in haystack -> "computer"
      else -> "device"
    }
  }

  private fun fallbackIpv4Address(): FallbackAddress? {
    val iface = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
      .firstOrNull { network ->
        runCatching { network.isUp && !network.isLoopback }.getOrDefault(false) &&
          network.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
      } ?: return null
    val inet4 = iface.inetAddresses.toList().firstOrNull { it is Inet4Address && !it.isLoopbackAddress } as? Inet4Address
      ?: return null
    return FallbackAddress(
      hostAddress = inet4.hostAddress ?: return null,
      prefixLength = 24,
    )
  }

  private fun isUsableIpv4LinkAddress(linkAddress: LinkAddress): Boolean =
    linkAddress.address is Inet4Address && !linkAddress.address.isLoopbackAddress

  private fun findPreferredLanNetwork(): SelectedNetwork? {
    val activeNetwork = connectivityManager.activeNetwork
    return connectivityManager.allNetworks
      .mapNotNull { network ->
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return@mapNotNull null
        if (!linkProperties.linkAddresses.any(::isUsableIpv4LinkAddress)) return@mapNotNull null
        val caps = connectivityManager.getNetworkCapabilities(network)
        val isLanTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
          caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val isActive = network == activeNetwork
        val score = when {
          isLanTransport && isActive -> 0
          isLanTransport -> 1
          isActive -> 2
          else -> 3
        }
        score to SelectedNetwork(network = network, linkProperties = linkProperties)
      }
      .minByOrNull { it.first }
      ?.second
  }

  private fun RouteInfo.isDefaultGatewayV4(): Boolean =
    isDefaultRoute && gateway is Inet4Address

  private fun dhcpSnapshot(): DhcpSnapshot =
    runCatching {
      val dhcpInfo = wifiManager.dhcpInfo
      val localIp = dhcpInfo?.ipAddress?.takeIf { it != 0 }?.let(::intToIpv4)
      val gatewayIp = dhcpInfo?.gateway?.takeIf { it != 0 }?.let(::intToIpv4)
      val prefixLength = dhcpInfo?.netmask?.takeIf { it != 0 }?.let(::netmaskToPrefixLength)
      DhcpSnapshot(localIp = localIp, gatewayIp = gatewayIp, prefixLength = prefixLength)
    }.getOrDefault(DhcpSnapshot(localIp = null, gatewayIp = null, prefixLength = null))

  private fun buildTargetIps(
    localIp: String,
    prefixLength: Int,
  ): List<String> {
    val localLong = ipToLong(localIp)
    val mask = prefixMask(prefixLength)
    val network = localLong and mask
    val broadcast = network or mask.inv()
    val firstHost = (network + 1).coerceAtLeast(1)
    val lastHost = (broadcast - 1).coerceAtLeast(firstHost)
    val list = mutableListOf<String>()
    var current = firstHost
    while (current <= lastHost) {
      list += longToIp(current)
      current += 1
    }
    return list
  }

  private fun networkAddress(ip: String, prefixLength: Int): String =
    longToIp(ipToLong(ip) and prefixMask(prefixLength))

  private fun isIpInSubnet(
    ip: String,
    subnetBase: Long,
    prefixLength: Int,
  ): Boolean = (ipToLong(ip) and prefixMask(prefixLength)) == subnetBase

  private fun prefixMask(prefixLength: Int): Long {
    val bits = prefixLength.coerceIn(0, 32)
    return if (bits == 0) 0 else (-1L shl (32 - bits)) and 0xFFFF_FFFFL
  }

  private fun ipToLong(ip: String): Long {
    val parts = ip.split(".")
    require(parts.size == 4) { "Invalid IPv4 address: $ip" }
    return parts.fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
  }

  private fun longToIp(value: Long): String =
    listOf(
      (value shr 24) and 0xFF,
      (value shr 16) and 0xFF,
      (value shr 8) and 0xFF,
      value and 0xFF,
    ).joinToString(".")

  private fun intToIpv4(value: Int): String {
    val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
  }

  private fun netmaskToPrefixLength(netmask: Int): Int {
    val mask = netmask.toLong() and 0xFFFF_FFFFL
    return java.lang.Long.bitCount(mask).coerceIn(0, 32)
  }

  companion object {
    fun configFromJson(rawJson: String?): ScanConfig {
      val json = rawJson?.trim()?.takeIf { it.isNotEmpty() }?.let { JSONObject(it) } ?: JSONObject()
      val ports = json.optJSONArray("fallbackPorts")
        ?.let { array ->
          buildList {
            for (index in 0 until array.length()) {
              val port = array.optInt(index, -1)
              if (port in 1..65535) add(port)
            }
          }
        }
        ?.take(5)
        ?.ifEmpty { listOf(443, 80, 53, 22, 554) }
        ?: listOf(443, 80, 53, 22, 554)
      return ScanConfig(
        maxConcurrency = json.optInt("maxConcurrency", 64).coerceIn(8, 64),
        probeTimeoutMs = json.optInt("probeTimeoutMs", 325).coerceIn(120, 1500),
        maxTargets = json.optInt("maxTargets", 254).coerceIn(32, 512),
        maxSubnetPrefix = json.optInt("maxSubnetPrefix", 24).coerceIn(16, 30),
        allowPortFallback = json.optBoolean("allowPortFallback", true),
        fallbackPorts = ports,
      )
    }

    private val OUI_VENDOR_MAP = mapOf(
      "00:1A:11" to "Google",
      "00:1C:B3" to "Apple",
      "28:CF:DA" to "Apple",
      "3C:5A:B4" to "Google",
      "44:65:0D" to "Amazon",
      "58:EF:68" to "Samsung",
      "68:3E:34" to "Google",
      "70:3A:CB" to "Apple",
      "7C:2F:80" to "Amazon",
      "9C:5C:8E" to "Espressif",
      "A4:77:33" to "Google",
      "B8:27:EB" to "Raspberry Pi",
      "D8:3A:DD" to "Google",
      "DC:A6:32" to "Roku",
      "E4:5F:01" to "Apple",
      "FC:A6:67" to "Amazon",
    )
  }
}
