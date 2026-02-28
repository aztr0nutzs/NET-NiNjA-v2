package com.netninja.v21

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.time.Instant

class MainActivity : AppCompatActivity() {
  private lateinit var webView: WebView
  private lateinit var wifiManager: WifiManager
  private lateinit var prefs: SharedPreferences

  private var bridgeEnabled = false
  private var pendingDiagnosticsJson: String? = null
  private var activeScanRequestId = 0
  private var pendingScanAfterPermission = false
  private var lastScanResults = JSONArray()
  private val handler = Handler(Looper.getMainLooper())

  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
      val granted = grantResults.values.all { it }
      emitBridgeState()
      if (granted && pendingScanAfterPermission) {
        pendingScanAfterPermission = false
        performWifiScan(origin = "permission_granted")
      } else {
        pendingScanAfterPermission = false
        emitNativeEvent(
          type = "scan_status",
          payload = JSONObject()
            .put("status", "permission_denied")
            .put("missingPermissions", JSONArray(missingRuntimeScanPermissions())),
        )
      }
    }

  private val exportLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      val payload = pendingDiagnosticsJson
      pendingDiagnosticsJson = null
      if (uri == null || payload == null) {
        emitNativeEvent(
          type = "export_status",
          payload = JSONObject().put("status", "cancelled"),
        )
        return@registerForActivityResult
      }

      runCatching {
        contentResolver.openOutputStream(uri)?.use { stream ->
          OutputStreamWriter(stream).use { writer ->
            writer.write(payload)
          }
        } ?: error("Unable to open export destination")
      }.onSuccess {
        emitNativeEvent(
          type = "export_status",
          payload = JSONObject()
            .put("status", "saved")
            .put("uri", uri.toString()),
        )
      }.onFailure { error ->
        emitNativeEvent(
          type = "export_status",
          payload = JSONObject()
            .put("status", "error")
            .put("message", error.message ?: "Export failed"),
        )
      }
    }

  private val wifiScanReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (activeScanRequestId == 0) return
      val resultsUpdated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
      deliverLatestScanResults(
        requestId = activeScanRequestId,
        source = if (resultsUpdated) "real" else "cached",
      )
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    bridgeEnabled = prefs.getBoolean(KEY_BRIDGE_ENABLED, false)
    wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    webView = WebView(this)
    setContentView(webView)

    val settings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.allowFileAccess = true
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.mediaPlaybackRequiresUserGesture = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

    val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    WebView.setWebContentsDebuggingEnabled(isDebuggable)

    webView.addJavascriptInterface(NativeBridge(this), BRIDGE_NAME)
    webView.webChromeClient = WebChromeClient()
    webView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

      override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        emitBridgeState()
        if (lastScanResults.length() > 0) {
          emitNativeEvent(
            type = "wifi_scan_results",
            payload = JSONObject()
              .put("source", "cached")
              .put("results", JSONArray(lastScanResults.toString()))
              .put("count", lastScanResults.length()),
          )
        }
      }
    }

    registerWifiReceiver()
    webView.loadUrl("file:///android_asset/www/index.html")
  }

  override fun onDestroy() {
    unregisterReceiver(wifiScanReceiver)
    super.onDestroy()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (this::webView.isInitialized && webView.canGoBack()) webView.goBack()
    else super.onBackPressed()
  }

  fun setBridgeEnabled(enabled: Boolean) {
    bridgeEnabled = enabled
    prefs.edit().putBoolean(KEY_BRIDGE_ENABLED, enabled).apply()
    emitBridgeState()
  }

  fun getBridgeStateJson(): String = bridgeStateObject().toString()

  fun getDeviceInfoJson(requireEnabled: Boolean): String {
    if (requireEnabled && !bridgeEnabled) {
      return errorJson("bridge_disabled", "Enable native bridge before requesting device info.")
    }

    return JSONObject()
      .put("ok", true)
      .put("deviceInfo", deviceInfoObject())
      .toString()
  }

  fun requestWifiScanFromJs(): String {
    if (!bridgeEnabled) {
      return errorJson("bridge_disabled", "Enable native bridge before starting a Wi-Fi scan.")
    }

    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
      emitNativeEvent(
        type = "scan_status",
        payload = JSONObject()
          .put("status", "wifi_unavailable")
          .put("message", "This device does not report Wi-Fi scanning support."),
      )
      return errorJson("wifi_unavailable", "Wi-Fi scanning is not available on this device.")
    }

    if (!isLocationServicesEnabled()) {
      emitNativeEvent(
        type = "scan_status",
        payload = JSONObject()
          .put("status", "location_services_disabled")
          .put("message", "Turn on location services before scanning for nearby networks."),
      )
      return errorJson("location_services_disabled", "Location services are disabled.")
    }

    val missingPermissions = missingRuntimeScanPermissions()
    if (missingPermissions.isNotEmpty()) {
      pendingScanAfterPermission = true
      permissionLauncher.launch(missingPermissions.toTypedArray())
      emitNativeEvent(
        type = "scan_status",
        payload = JSONObject()
          .put("status", "permission_requested")
          .put("missingPermissions", JSONArray(missingPermissions)),
      )
      return JSONObject()
        .put("ok", true)
        .put("status", "permission_requested")
        .toString()
    }

    return performWifiScan(origin = "user")
  }

  fun exportDiagnosticsFromJs(payload: String?): String {
    if (!bridgeEnabled) {
      return errorJson("bridge_disabled", "Enable native bridge before exporting diagnostics.")
    }

    val exportJson = JSONObject()
      .put("exportedAt", nowIsoString())
      .put(
        "app",
        JSONObject()
          .put("applicationId", packageName)
          .put("versionName", appVersionName())
          .put("versionCode", appVersionCode()),
      )
      .put("bridge", bridgeStateObject())
      .put("deviceInfo", deviceInfoObject())
      .put(
        "lastWifiScan",
        JSONObject()
          .put("count", lastScanResults.length())
          .put("results", JSONArray(lastScanResults.toString())),
      )

    parseOptionalJson(payload)?.let { exportJson.put("hudPayload", it) }

    pendingDiagnosticsJson = exportJson.toString(2)
    exportLauncher.launch("netninja-diagnostics-${System.currentTimeMillis()}.json")
    emitNativeEvent(
      type = "export_status",
      payload = JSONObject().put("status", "requested"),
    )

    return JSONObject()
      .put("ok", true)
      .put("status", "requested")
      .toString()
  }

  private fun bridgeStateObject(): JSONObject {
    val missingPermissions = missingRuntimeScanPermissions()
    return JSONObject()
      .put("available", true)
      .put("enabled", bridgeEnabled)
      .put("debugBuild", isDebugBuild())
      .put("canScan", packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI))
      .put("locationServicesEnabled", isLocationServicesEnabled())
      .put("scanPermissionGranted", missingPermissions.isEmpty())
      .put("missingPermissions", JSONArray(missingPermissions))
  }

  private fun deviceInfoObject(): JSONObject =
    JSONObject()
      .put("manufacturer", Build.MANUFACTURER)
      .put("model", Build.MODEL)
      .put("device", Build.DEVICE)
      .put("sdkInt", Build.VERSION.SDK_INT)
      .put("release", Build.VERSION.RELEASE ?: "")
      .put("appVersion", appVersionName())
      .put("bridgeEnabled", bridgeEnabled)
      .put("wifiSupported", packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI))

  private fun emitBridgeState() {
    emitNativeEvent(type = "bridge_state", payload = bridgeStateObject())
  }

  private fun emitNativeEvent(type: String, payload: JSONObject) {
    if (!this::webView.isInitialized) return
    val detail = JSONObject()
      .put("type", type)
      .put("payload", payload)
      .toString()

    webView.post {
      webView.evaluateJavascript(
        """
          (function() {
            var detail = $detail;
            window.dispatchEvent(new CustomEvent('netninja:native', { detail: detail }));
          })();
        """.trimIndent(),
        null,
      )
    }
  }

  @SuppressLint("MissingPermission")
  private fun performWifiScan(origin: String): String {
    val requestId = ++activeScanRequestId
    return runCatching {
      val started = wifiManager.startScan()
      emitNativeEvent(
        type = "scan_status",
        payload = JSONObject()
          .put("status", if (started) "started" else "cached_results")
          .put("origin", origin),
      )

      if (started) {
        handler.postDelayed(
          {
            deliverLatestScanResults(
              requestId = requestId,
              source = "real",
            )
          },
          SCAN_TIMEOUT_MS,
        )
      } else {
        deliverLatestScanResults(
          requestId = requestId,
          source = "cached",
        )
      }

      JSONObject()
        .put("ok", true)
        .put("status", if (started) "started" else "cached_results")
        .toString()
    }.getOrElse { error ->
      emitNativeEvent(
        type = "scan_status",
        payload = JSONObject()
          .put("status", "error")
          .put("message", error.message ?: "Wi-Fi scan failed"),
      )
      errorJson("scan_failed", error.message ?: "Wi-Fi scan failed")
    }
  }

  @SuppressLint("MissingPermission")
  private fun deliverLatestScanResults(requestId: Int, source: String) {
    if (requestId != activeScanRequestId) return
    activeScanRequestId = 0

    val results = wifiManager.scanResults
      .filter { !it.SSID.isNullOrBlank() }
      .sortedByDescending { it.level }
      .distinctBy { it.BSSID }

    val payloadResults = JSONArray()
    results.forEach { result ->
      payloadResults.put(
        JSONObject()
          .put("ssid", result.SSID)
          .put("bssid", result.BSSID)
          .put("signalLevel", result.level)
          .put("frequency", result.frequency)
          .put("capabilities", result.capabilities)
          .put("timestamp", result.timestamp),
      )
    }

    lastScanResults = payloadResults

    emitNativeEvent(
      type = "wifi_scan_results",
      payload = JSONObject()
        .put("source", source)
        .put("count", payloadResults.length())
        .put("results", JSONArray(payloadResults.toString())),
    )
  }

  private fun missingRuntimeScanPermissions(): List<String> {
    val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      required += Manifest.permission.NEARBY_WIFI_DEVICES
    }
    return required.filter { permission ->
      ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
    }
  }

  private fun isLocationServicesEnabled(): Boolean =
    runCatching {
      Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
    }.getOrDefault(false)

  private fun parseOptionalJson(payload: String?): Any? {
    val trimmed = payload?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_BRIDGE_PAYLOAD_CHARS) ?: return null
    return runCatching {
      when {
        trimmed.startsWith("{") -> JSONObject(trimmed)
        trimmed.startsWith("[") -> JSONArray(trimmed)
        else -> trimmed
      }
    }.getOrElse { trimmed }
  }

  private fun errorJson(code: String, message: String): String =
    JSONObject()
      .put("ok", false)
      .put("code", code)
      .put("message", message)
      .toString()

  private fun nowIsoString(): String = Instant.now().toString()

  private fun appVersionName(): String =
    packageManager.getPackageInfo(packageName, 0).versionName ?: ""

  private fun appVersionCode(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageManager.getPackageInfo(packageName, 0).longVersionCode
    } else {
      @Suppress("DEPRECATION")
      packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
    }

  private fun isDebugBuild(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

  private fun registerWifiReceiver() {
    val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(wifiScanReceiver, filter, RECEIVER_NOT_EXPORTED)
    } else {
      registerReceiver(wifiScanReceiver, filter)
    }
  }

  companion object {
    private const val BRIDGE_NAME = "NetNinjaBridge"
    private const val PREFS_NAME = "net_ninja_prefs"
    private const val KEY_BRIDGE_ENABLED = "bridge_enabled"
    private const val MAX_BRIDGE_PAYLOAD_CHARS = 32_000
    private const val SCAN_TIMEOUT_MS = 2_000L
  }
}
