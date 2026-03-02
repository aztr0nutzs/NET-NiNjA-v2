package com.netninja.v21

import android.webkit.JavascriptInterface

class NativeBridge(
  private val activity: MainActivity,
) {
  @JavascriptInterface
  fun getPreference(key: String): String? = activity.getPreferenceValue(key)

  @JavascriptInterface
  fun setPreference(key: String, value: String) {
    activity.setPreferenceValue(key, value)
  }

  @JavascriptInterface
  fun removePreference(key: String) {
    activity.removePreferenceValue(key)
  }

  @JavascriptInterface
  fun getBridgeState(): String = activity.getBridgeStateJson()

  @JavascriptInterface
  fun setBridgeEnabled(enabled: Boolean): String {
    activity.setBridgeEnabled(enabled)
    return activity.getBridgeStateJson()
  }

  @JavascriptInterface
  fun getDeviceInfo(): String = activity.getDeviceInfoJson(requireEnabled = true)

  @JavascriptInterface
  fun requestWifiScan(): String = activity.requestWifiScanFromJs()

  @JavascriptInterface
  fun getLastWifiScan(): String = activity.getLastWifiScanJson(requireEnabled = false)

  @JavascriptInterface
  fun exportDiagnostics(payload: String?): String = activity.exportDiagnosticsFromJs(payload)

  @JavascriptInterface
  fun speedtestStart(jsonConfig: String) {
    activity.startSpeedtestFromJs(jsonConfig)
  }

  @JavascriptInterface
  fun speedtestAbort() {
    activity.abortSpeedtestFromJs()
  }

  @JavascriptInterface
  fun speedtestReset() {
    activity.resetSpeedtestFromJs()
  }

  @JavascriptInterface
  fun lanScanStart(jsonConfig: String): String = activity.startLanScanFromJs(jsonConfig)

  @JavascriptInterface
  fun lanScanStop() {
    activity.stopLanScanFromJs()
  }

  @JavascriptInterface
  fun lanScanGetLast(): String = activity.getLastLanScanJson()
}
