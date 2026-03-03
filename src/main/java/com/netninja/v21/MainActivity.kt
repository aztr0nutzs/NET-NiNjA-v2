package com.netninja.v21

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.netninja.v21.discovery.LanDiscovery
import com.netninja.v21.speedtest.SpeedtestEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.time.Instant

class MainActivity : AppCompatActivity() {
  private lateinit var webView: WebView
  private lateinit var wifiManager: WifiManager
  private lateinit var connectivityManager: ConnectivityManager
  private lateinit var locationManager: LocationManager
  private lateinit var prefs: SharedPreferences

  private val speedtestEngine = SpeedtestEngine()
  private lateinit var lanDiscovery: LanDiscovery

  private var bridgeEnabled = false
  private var pendingDiagnosticsJson: String? = null
  private var lastLanSnapshot = JSONObject()
    .put("capturedAt", 0L)
    .put("count", 0)
    .put("devices", JSONArray())

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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    bridgeEnabled = prefs.getBoolean(KEY_BRIDGE_ENABLED, true)
    prefs.getString(KEY_LAST_LAN_SNAPSHOT, null)?.let { raw ->
      runCatching { JSONObject(raw) }.getOrNull()?.let { snapshot ->
        lastLanSnapshot = snapshot
      }
    }
    wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    lanDiscovery = LanDiscovery(applicationContext)

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
        if (lastLanSnapshot.optInt("count", 0) > 0) {
          emitNativeEvent(type = "lan_scan_done", payload = JSONObject(lastLanSnapshot.toString()))
        }
      }
    }

    webView.loadUrl("file:///android_asset/www/index.html")
  }

  override fun onDestroy() {
    speedtestEngine.abort()
    lanDiscovery.stopScan()
    super.onDestroy()
  }

  override fun onStop() {
    speedtestEngine.abort()
    lanDiscovery.stopScan()
    super.onStop()
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

  fun getPreferenceValue(key: String): String? = prefs.getString(key, null)

  fun setPreferenceValue(key: String, value: String) {
    prefs.edit().putString(key, value).apply()
  }

  fun removePreferenceValue(key: String) {
    prefs.edit().remove(key).apply()
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

  fun requestWifiScanFromJs(): String = startLanScanFromJs(null)

  fun getLastWifiScanJson(requireEnabled: Boolean): String {
    if (requireEnabled && !bridgeEnabled) {
      return errorJson("bridge_disabled", "Enable native bridge before requesting scan data.")
    }
    return getLastLanScanJson()
  }

  fun startLanScanFromJs(jsonConfig: String?): String {
    if (!bridgeEnabled) {
      return errorJson("bridge_disabled", "Enable native bridge before starting LAN discovery.")
    }

    val config = runCatching { LanDiscovery.configFromJson(jsonConfig) }
      .getOrElse { error ->
        emitNativeEvent(
          type = "lan_scan_error",
          payload = JSONObject()
            .put("status", "error")
            .put("message", error.message ?: "Invalid LAN discovery configuration."),
        )
        return errorJson("invalid_config", error.message ?: "Invalid LAN discovery configuration.")
      }

    lanDiscovery.startScan(config) { event ->
      when (event) {
        is LanDiscovery.Event.Status -> emitNativeEvent(type = "lan_scan_status", payload = event.payload)
        is LanDiscovery.Event.Device -> emitNativeEvent(type = "lan_scan_device", payload = event.payload)
        is LanDiscovery.Event.Done -> {
          lastLanSnapshot = JSONObject(event.payload.toString())
          prefs.edit().putString(KEY_LAST_LAN_SNAPSHOT, lastLanSnapshot.toString()).apply()
          emitNativeEvent(type = "lan_scan_done", payload = event.payload)
        }
        is LanDiscovery.Event.Error -> emitNativeEvent(type = "lan_scan_error", payload = event.payload)
      }
    }

    return JSONObject()
      .put("ok", true)
      .put("status", "started")
      .toString()
  }

  fun stopLanScanFromJs() {
    lanDiscovery.stopScan()
    emitNativeEvent(
      type = "lan_scan_status",
      payload = JSONObject()
        .put("status", "stopped")
        .put("message", "LAN discovery stopped."),
    )
  }

  fun getLastLanScanJson(): String =
    JSONObject()
      .put("ok", true)
      .put("scan", JSONObject(lastLanSnapshot.toString()))
      .toString()

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
      .put("speedtestDefaults", SpeedtestEngine.defaultConfigJson())
      .put("lastLanScan", JSONObject(lastLanSnapshot.toString()))

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
    val missingPermissions = requiredBridgePermissions()
      .filterNot(::hasPermission)
      .map(::permissionLabel)
    val canDiscoverLan = lanDiscovery.canScan() && missingPermissions.isEmpty()
    return JSONObject()
      .put("available", true)
      .put("enabled", bridgeEnabled)
      .put("debugBuild", isDebugBuild())
      .put("canScan", canDiscoverLan)
      .put("locationServicesEnabled", isLocationEnabled())
      .put("scanPermissionGranted", missingPermissions.isEmpty())
      .put("missingPermissions", JSONArray(missingPermissions))
  }

  fun startSpeedtestFromJs(jsonConfig: String?) {
    if (!bridgeEnabled) {
      emitNativeEvent(
        type = "speedtest_error",
        payload = JSONObject()
          .put("phase", "error")
          .put("code", "bridge_disabled")
          .put("message", "Enable native bridge before starting the speedtest."),
      )
      return
    }

    val config = runCatching { SpeedtestEngine.configFromJson(jsonConfig) }
      .getOrElse { error ->
        emitNativeEvent(
          type = "speedtest_error",
          payload = JSONObject()
            .put("phase", "error")
            .put("message", error.message ?: "Invalid speedtest configuration."),
        )
        return
      }

    speedtestEngine.startSpeedtest(config) { update ->
      when (update.phase) {
        "done" -> emitNativeEvent(type = "speedtest_done", payload = update.toJson())
        "error" -> emitNativeEvent(type = "speedtest_error", payload = update.toJson())
        else -> emitNativeEvent(type = "speedtest_update", payload = update.toJson())
      }
    }
  }

  fun abortSpeedtestFromJs() {
    speedtestEngine.abort()
    emitNativeEvent(
      type = "speedtest_error",
      payload = JSONObject()
        .put("phase", "error")
        .put("code", "aborted")
        .put("message", "Speedtest aborted."),
    )
  }

  fun resetSpeedtestFromJs() {
    speedtestEngine.abort()
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
      .put("activeNetwork", connectivityManager.activeNetwork != null)
      .put("dhcpGateway", runCatching { wifiManager.dhcpInfo?.gateway ?: 0 }.getOrDefault(0))

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

  private fun requiredBridgePermissions(): List<String> =
    listOf(
      Manifest.permission.INTERNET,
      Manifest.permission.ACCESS_NETWORK_STATE,
      Manifest.permission.ACCESS_WIFI_STATE,
    )

  private fun hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  private fun permissionLabel(permission: String): String =
    permission.substringAfterLast('.')

  private fun isLocationEnabled(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      locationManager.isLocationEnabled
    } else {
      runCatching {
        Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
      }.getOrDefault(false)
    }

  companion object {
    private const val BRIDGE_NAME = "NetNinjaBridge"
    private const val PREFS_NAME = "netninja_prefs"
    private const val KEY_BRIDGE_ENABLED = "bridge_enabled"
    private const val KEY_LAST_LAN_SNAPSHOT = "last_lan_snapshot"
    private const val MAX_BRIDGE_PAYLOAD_CHARS = 32_000
  }
}
