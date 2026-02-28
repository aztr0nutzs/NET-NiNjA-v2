package com.netninja.v21

import android.webkit.JavascriptInterface

class NativeBridge(
  private val activity: MainActivity,
) {
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
}
