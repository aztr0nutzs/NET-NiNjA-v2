package com.netninja.v21.speedtest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

private fun round2(value: Double): Double = ((value * 100.0).roundToInt()) / 100.0
private fun averageOrZero(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.average()

class SpeedtestEngine {
  data class SpeedtestServer(
    val id: String,
    val name: String,
    val pingUrl: String,
    val downloadUrl: String,
    val uploadUrl: String? = null,
  )

  data class SpeedtestConfig(
    val servers: List<SpeedtestServer> = defaultServers(),
    val pingAttempts: Int = 6,
    val downloadDurationMs: Long = 10_000L,
    val uploadDurationMs: Long = 8_000L,
    val downloadParallelism: Int = 4,
    val uploadParallelism: Int = 2,
    val connectTimeoutMs: Int = 4_000,
    val readTimeoutMs: Int = 4_000,
    val updateIntervalMs: Long = 150L,
    val uploadBufferBytes: Int = 32 * 1024,
  )

  data class SpeedtestUpdate(
    val phase: String,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val lossPct: Double? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val progress: Double = 0.0,
    val elapsedMs: Long = 0L,
    val serverId: String? = null,
    val serverName: String? = null,
    val message: String? = null,
    val uploadAvailable: Boolean = true,
  ) {
    fun toJson(): JSONObject {
      val json = JSONObject()
        .put("phase", phase)
        .put("progress", progress.coerceIn(0.0, 1.0))
        .put("elapsedMs", elapsedMs)
        .put("uploadAvailable", uploadAvailable)
      pingMs?.let { json.put("pingMs", round2(it)) }
      jitterMs?.let { json.put("jitterMs", round2(it)) }
      lossPct?.let { json.put("lossPct", round2(it)) }
      downMbps?.let { json.put("downMbps", round2(it)) }
      upMbps?.let { json.put("upMbps", round2(it)) }
      serverId?.let { json.put("serverId", it) }
      serverName?.let { json.put("serverName", it) }
      message?.let { json.put("message", it) }
      return json
    }
  }

  private data class PingSummary(
    val averageMs: Double,
    val jitterMs: Double,
    val lossPct: Double,
    val samples: List<Double>,
  )

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val openConnections = Collections.newSetFromMap(ConcurrentHashMap<HttpURLConnection, Boolean>())
  private val uploadSeed = AtomicLong(System.nanoTime())

  @Volatile
  private var activeJob: Job? = null

  fun startSpeedtest(
    config: SpeedtestConfig,
    callback: (SpeedtestUpdate) -> Unit,
  ) {
    scope.launch {
      abortInternal()
      activeJob = launch {
        runCatching {
          execute(config, callback)
        }.onFailure { error ->
          if (error is CancellationException) return@onFailure
          callback(
            SpeedtestUpdate(
              phase = "error",
              message = error.message ?: "Speedtest failed.",
            ),
          )
        }
      }
    }
  }

  fun abort() {
    scope.launch {
      abortInternal()
    }
  }

  private suspend fun abortInternal() {
    activeJob?.cancelAndJoin()
    activeJob = null
    disconnectAll()
  }

  private suspend fun execute(
    config: SpeedtestConfig,
    callback: (SpeedtestUpdate) -> Unit,
  ) {
    require(config.servers.isNotEmpty()) { "No speedtest servers configured." }

    val selectedServer = selectBestServer(config, callback)
    val pingSummary = measurePingPhase(selectedServer, config, callback)
    val downMbps = measureDownloadPhase(selectedServer, pingSummary, config, callback)
    val uploadResult = measureUploadPhase(selectedServer, pingSummary, downMbps, config, callback)

    callback(
      SpeedtestUpdate(
        phase = "done",
        pingMs = pingSummary.averageMs,
        jitterMs = pingSummary.jitterMs,
        lossPct = pingSummary.lossPct,
        downMbps = downMbps,
        upMbps = uploadResult.first,
        progress = 1.0,
        elapsedMs = config.downloadDurationMs + config.uploadDurationMs,
        serverId = selectedServer.id,
        serverName = selectedServer.name,
        uploadAvailable = uploadResult.second,
      ),
    )
  }

  private suspend fun selectBestServer(
    config: SpeedtestConfig,
    callback: (SpeedtestUpdate) -> Unit,
  ): SpeedtestServer {
    var bestServer: SpeedtestServer? = null
    var bestLatency = Double.MAX_VALUE

    config.servers.forEachIndexed { index, server ->
      currentCoroutineContext().ensureActive()
      val sample = measureSingleLatency(server, config)
      val progress = ((index + 1).toDouble() / config.servers.size.toDouble()).coerceIn(0.0, 1.0) * 0.25
      callback(
        SpeedtestUpdate(
          phase = "ping",
          pingMs = sample,
          progress = progress,
          serverId = server.id,
          serverName = server.name,
          message = "Selecting lowest-latency server",
        ),
      )
      if (sample < bestLatency) {
        bestLatency = sample
        bestServer = server
      }
    }

    return bestServer ?: config.servers.first()
  }

  private suspend fun measurePingPhase(
    server: SpeedtestServer,
    config: SpeedtestConfig,
    callback: (SpeedtestUpdate) -> Unit,
  ): PingSummary {
    val samples = mutableListOf<Double>()
    var failures = 0
    repeat(config.pingAttempts) { index ->
      currentCoroutineContext().ensureActive()
      val sample = runCatching { measureSingleLatency(server, config) }.getOrNull()
      if (sample == null) {
        failures += 1
      } else {
        samples += sample
      }
      val average = averageOrZero(samples)
      val jitter = computeJitter(samples)
      val lossPct = ((failures.toDouble() / (index + 1).toDouble()) * 100.0)
      callback(
        SpeedtestUpdate(
          phase = "ping",
          pingMs = average.takeIf { it > 0.0 },
          jitterMs = jitter.takeIf { it > 0.0 },
          lossPct = lossPct,
          progress = (index + 1).toDouble() / config.pingAttempts.toDouble(),
          elapsedMs = ((index + 1) * config.updateIntervalMs),
          serverId = server.id,
          serverName = server.name,
        ),
      )
      if (index < config.pingAttempts - 1) {
        delay(config.updateIntervalMs)
      }
    }

    if (samples.isEmpty()) {
      error("Unable to reach ${server.name} for latency probes.")
    }

    return PingSummary(
      averageMs = samples.average(),
      jitterMs = computeJitter(samples),
      lossPct = (failures.toDouble() / config.pingAttempts.toDouble()) * 100.0,
      samples = samples.toList(),
    )
  }

  private suspend fun measureDownloadPhase(
    server: SpeedtestServer,
    pingSummary: PingSummary,
    config: SpeedtestConfig,
    callback: (SpeedtestUpdate) -> Unit,
  ): Double = coroutineScope {
    val totalBytes = AtomicLong(0L)
    val workers = (0 until config.downloadParallelism.coerceAtLeast(1)).map { workerIndex ->
      launch(Dispatchers.IO) {
        val buffer = ByteArray(64 * 1024)
        while (isActive) {
          val token = System.nanoTime()
          val url = buildCacheBustedUrl(server.downloadUrl, workerIndex, token)
          val connection = openConnection(url, "GET", config)
          connection.setRequestProperty("Range", "bytes=0-")
          registerConnection(connection)
          try {
            connection.connect()
            BufferedInputStream(connection.inputStream).use { input ->
              while (isActive) {
                val read = input.read(buffer)
                if (read <= 0) break
                totalBytes.addAndGet(read.toLong())
              }
            }
          } finally {
            unregisterConnection(connection)
            connection.disconnect()
          }
        }
      }
    }

    val result = measureTimedThroughput(
      phase = "download",
      durationMs = config.downloadDurationMs,
      totalBytes = totalBytes,
      pingSummary = pingSummary,
      callback = callback,
      server = server,
      config = config,
    )

    workers.forEach { it.cancel() }
    workers.joinAll()
    result
  }

  private suspend fun measureUploadPhase(
    server: SpeedtestServer,
    pingSummary: PingSummary,
    downMbps: Double,
    config: SpeedtestConfig,
    callback: (SpeedtestUpdate) -> Unit,
  ): Pair<Double?, Boolean> = coroutineScope {
    val uploadUrl = server.uploadUrl
    if (uploadUrl.isNullOrBlank()) {
      callback(
        SpeedtestUpdate(
          phase = "upload",
          pingMs = pingSummary.averageMs,
          jitterMs = pingSummary.jitterMs,
          lossPct = pingSummary.lossPct,
          downMbps = downMbps,
          upMbps = null,
          progress = 1.0,
          elapsedMs = config.uploadDurationMs,
          serverId = server.id,
          serverName = server.name,
          message = "Upload endpoint unavailable",
          uploadAvailable = false,
        ),
      )
      null to false
    } else {
      val totalBytes = AtomicLong(0L)
      val workers = (0 until config.uploadParallelism.coerceAtLeast(1)).map { workerIndex ->
        launch(Dispatchers.IO) {
          val payload = ByteArray(config.uploadBufferBytes.coerceAtLeast(8 * 1024))
          while (isActive) {
            fillPayload(payload)
            val connection = openConnection(buildCacheBustedUrl(uploadUrl, workerIndex, System.nanoTime()), "POST", config)
            connection.doOutput = true
            connection.instanceFollowRedirects = true
            connection.setChunkedStreamingMode(payload.size)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            registerConnection(connection)
            try {
              connection.outputStream.use { output ->
                while (isActive) {
                  fillPayload(payload)
                  output.write(payload)
                  output.flush()
                  totalBytes.addAndGet(payload.size.toLong())
                }
              }
              runCatching { connection.responseCode }
              runCatching { connection.inputStream?.close() }
              runCatching { connection.errorStream?.close() }
            } finally {
              unregisterConnection(connection)
              connection.disconnect()
            }
          }
        }
      }

      runCatching {
        val result = measureTimedThroughput(
          phase = "upload",
          durationMs = config.uploadDurationMs,
          totalBytes = totalBytes,
          pingSummary = pingSummary,
          callback = callback,
          server = server,
          config = config,
          downMbps = downMbps,
        )
        result to true
      }.getOrElse {
        callback(
          SpeedtestUpdate(
            phase = "upload",
            pingMs = pingSummary.averageMs,
            jitterMs = pingSummary.jitterMs,
            lossPct = pingSummary.lossPct,
            downMbps = downMbps,
            progress = 1.0,
            elapsedMs = config.uploadDurationMs,
            serverId = server.id,
            serverName = server.name,
            message = "Upload measurement unavailable",
            uploadAvailable = false,
          ),
        )
        null to false
      }.also {
        workers.forEach { worker -> worker.cancel() }
        workers.joinAll()
      }
    }
  }

  private suspend fun measureTimedThroughput(
    phase: String,
    durationMs: Long,
    totalBytes: AtomicLong,
    pingSummary: PingSummary,
    callback: (SpeedtestUpdate) -> Unit,
    server: SpeedtestServer,
    config: SpeedtestConfig,
    downMbps: Double? = null,
  ): Double {
    val startedAt = System.nanoTime()
    var previousBytes = 0L
    var previousAt = startedAt
    var latestMbps = 0.0

    while (true) {
      currentCoroutineContext().ensureActive()
      delay(config.updateIntervalMs)
      val now = System.nanoTime()
      val elapsedMs = ((now - startedAt) / 1_000_000L).coerceAtLeast(1L)
      val bytes = totalBytes.get()
      val deltaBytes = (bytes - previousBytes).coerceAtLeast(0L)
      val deltaNs = (now - previousAt).coerceAtLeast(1L)
      val instantaneousMbps = bytesToMbps(deltaBytes, deltaNs)
      val averageMbps = bytesToMbps(bytes, now - startedAt)
      latestMbps = if (instantaneousMbps > 0.0) instantaneousMbps else averageMbps
      previousBytes = bytes
      previousAt = now

      callback(
        SpeedtestUpdate(
          phase = phase,
          pingMs = pingSummary.averageMs,
          jitterMs = pingSummary.jitterMs,
          lossPct = pingSummary.lossPct,
          downMbps = if (phase == "download") latestMbps else downMbps,
          upMbps = if (phase == "upload") latestMbps else null,
          progress = (elapsedMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0),
          elapsedMs = elapsedMs,
          serverId = server.id,
          serverName = server.name,
        ),
      )

      if (elapsedMs >= durationMs) {
        break
      }
    }

    return latestMbps.coerceAtLeast(0.0)
  }

  private suspend fun measureSingleLatency(
    server: SpeedtestServer,
    config: SpeedtestConfig,
  ): Double {
    val startedAt = System.nanoTime()
    val connection = openConnection(buildCacheBustedUrl(server.pingUrl, 0, startedAt), "HEAD", config)
    registerConnection(connection)
    return try {
      connection.connect()
      runCatching { connection.responseCode }.getOrDefault(HttpURLConnection.HTTP_OK)
      (System.nanoTime() - startedAt) / 1_000_000.0
    } catch (_: Exception) {
      val fallback = openConnection(buildCacheBustedUrl(server.downloadUrl, 0, startedAt), "GET", config)
      fallback.setRequestProperty("Range", "bytes=0-0")
      registerConnection(fallback)
      try {
        val retryStarted = System.nanoTime()
        fallback.connect()
        fallback.inputStream.use { input ->
          val scratch = ByteArray(1)
          runCatching { input.read(scratch) }
        }
        (System.nanoTime() - retryStarted) / 1_000_000.0
      } finally {
        unregisterConnection(fallback)
        fallback.disconnect()
      }
    } finally {
      unregisterConnection(connection)
      connection.disconnect()
    }
  }

  private fun openConnection(
    url: String,
    method: String,
    config: SpeedtestConfig,
  ): HttpURLConnection {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = config.connectTimeoutMs
      readTimeout = config.readTimeoutMs
      useCaches = false
      instanceFollowRedirects = true
      setRequestProperty("Cache-Control", "no-cache, no-store")
      setRequestProperty("Pragma", "no-cache")
      setRequestProperty("User-Agent", USER_AGENT)
      setRequestProperty("Connection", "Keep-Alive")
      setRequestProperty("Accept-Encoding", "identity")
    }
    return connection
  }

  private fun fillPayload(buffer: ByteArray) {
    var seed = uploadSeed.incrementAndGet()
    for (index in buffer.indices) {
      seed = (seed * 6364136223846793005L) + 1442695040888963407L
      buffer[index] = (seed ushr 56).toByte()
    }
  }

  private fun registerConnection(connection: HttpURLConnection) {
    openConnections += connection
  }

  private fun unregisterConnection(connection: HttpURLConnection) {
    openConnections -= connection
  }

  private fun disconnectAll() {
    openConnections.toList().forEach { connection ->
      runCatching { connection.disconnect() }
      openConnections -= connection
    }
  }

  companion object {
    private const val USER_AGENT = "NET-NiNjA/2.1 Android Speedtest"

    fun configFromJson(rawJson: String?): SpeedtestConfig {
      val json = rawJson?.trim()?.takeIf { it.isNotEmpty() }?.let { JSONObject(it) } ?: JSONObject()
      return SpeedtestConfig(
        servers = parseServers(json.optJSONArray("servers")).ifEmpty { defaultServers() },
        pingAttempts = json.optInt("pingAttempts", 6).coerceIn(3, 10),
        downloadDurationMs = json.optLong("downloadDurationMs", 10_000L).coerceIn(8_000L, 12_000L),
        uploadDurationMs = json.optLong("uploadDurationMs", 8_000L).coerceIn(0L, 10_000L),
        downloadParallelism = json.optInt("downloadParallelism", 4).coerceIn(2, 6),
        uploadParallelism = json.optInt("uploadParallelism", 2).coerceIn(1, 4),
        connectTimeoutMs = json.optInt("connectTimeoutMs", 4_000).coerceIn(2_000, 8_000),
        readTimeoutMs = json.optInt("readTimeoutMs", 4_000).coerceIn(2_000, 8_000),
        updateIntervalMs = json.optLong("updateIntervalMs", 150L).coerceIn(80L, 250L),
        uploadBufferBytes = json.optInt("uploadBufferBytes", 32 * 1024).coerceIn(8 * 1024, 256 * 1024),
      )
    }

    fun defaultConfigJson(): JSONObject =
      JSONObject()
        .put("pingAttempts", 6)
        .put("downloadDurationMs", 10_000)
        .put("uploadDurationMs", 8_000)
        .put("downloadParallelism", 4)
        .put("uploadParallelism", 2)
        .put("connectTimeoutMs", 4_000)
        .put("readTimeoutMs", 4_000)
        .put("updateIntervalMs", 150)
        .put("uploadBufferBytes", 32 * 1024)
        .put(
          "servers",
          JSONArray().apply {
            defaultServers().forEach { server ->
              put(
                JSONObject()
                  .put("id", server.id)
                  .put("name", server.name)
                  .put("pingUrl", server.pingUrl)
                  .put("downloadUrl", server.downloadUrl)
                  .put("uploadUrl", server.uploadUrl),
              )
            }
          },
        )

    fun defaultServers(): List<SpeedtestServer> =
      listOf(
        SpeedtestServer(
          id = "hetzner-ash",
          name = "Hetzner ASH",
          pingUrl = "https://ash-speed.hetzner.com/",
          downloadUrl = "https://ash-speed.hetzner.com/100MB.bin",
          uploadUrl = "https://httpbingo.org/upload",
        ),
        SpeedtestServer(
          id = "hetzner-fsn1",
          name = "Hetzner FSN1",
          pingUrl = "https://fsn1-speed.hetzner.com/",
          downloadUrl = "https://fsn1-speed.hetzner.com/100MB.bin",
          uploadUrl = "https://httpbingo.org/upload",
        ),
        SpeedtestServer(
          id = "hetzner-hel1",
          name = "Hetzner HEL1",
          pingUrl = "https://hel1-speed.hetzner.com/",
          downloadUrl = "https://hel1-speed.hetzner.com/100MB.bin",
          uploadUrl = "https://httpbingo.org/upload",
        ),
      )

    private fun parseServers(jsonArray: JSONArray?): List<SpeedtestServer> {
      if (jsonArray == null) return emptyList()
      val servers = mutableListOf<SpeedtestServer>()
      for (index in 0 until jsonArray.length()) {
        val item = jsonArray.optJSONObject(index) ?: continue
        val id = item.optString("id").trim()
        val name = item.optString("name").trim()
        val pingUrl = item.optString("pingUrl").trim()
        val downloadUrl = item.optString("downloadUrl").trim()
        val uploadUrl = item.optString("uploadUrl").trim().ifEmpty { null }
        if (id.isEmpty() || name.isEmpty() || pingUrl.isEmpty() || downloadUrl.isEmpty()) continue
        servers += SpeedtestServer(
          id = id,
          name = name,
          pingUrl = pingUrl,
          downloadUrl = downloadUrl,
          uploadUrl = uploadUrl,
        )
      }
      return servers
    }

    private fun buildCacheBustedUrl(baseUrl: String, workerIndex: Int, token: Long): String {
      val separator = if (baseUrl.contains("?")) "&" else "?"
      return "$baseUrl${separator}nn_worker=$workerIndex&nn_t=$token"
    }

    private fun bytesToMbps(bytes: Long, durationNs: Long): Double {
      if (bytes <= 0L || durationNs <= 0L) return 0.0
      val bits = bytes.toDouble() * 8.0
      val seconds = durationNs.toDouble() / 1_000_000_000.0
      return if (seconds <= 0.0) 0.0 else bits / seconds / 1_000_000.0
    }

    private fun computeJitter(samples: List<Double>): Double {
      if (samples.size < 2) return 0.0
      val deltas = samples.zipWithNext { a, b -> abs(b - a) }
      return averageOrZero(deltas)
    }

  }
}
