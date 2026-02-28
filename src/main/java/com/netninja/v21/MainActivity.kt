package com.netninja.v21

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

  private lateinit var webView: WebView

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    webView = WebView(this)
    setContentView(webView)

    val s = webView.settings
    s.javaScriptEnabled = true
    s.domStorageEnabled = true
    s.cacheMode = WebSettings.LOAD_DEFAULT
    s.allowFileAccess = true
    s.allowContentAccess = false
    s.mediaPlaybackRequiresUserGesture = false
    s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

    val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    WebView.setWebContentsDebuggingEnabled(isDebuggable)

    webView.webChromeClient = WebChromeClient()
    webView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return false
      }
    }

    webView.loadUrl("file:///android_asset/www/index.html")
  }

  override fun onBackPressed() {
    if (this::webView.isInitialized && webView.canGoBack()) webView.goBack()
    else super.onBackPressed()
  }
}
