package com.doorskorea.singkro.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Main screen: the web HUD (frontend/, static export bundled as assets) in a WebView, driven by
 * AssistantService through AssistantBus. Works offline; the page talks to the app via SingkroBridge.
 */
class HudActivity : Activity() {
    private val scope = MainScope()
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistantBus.init(this)
        // https origin for the bundled files: Next.js uses absolute /_next/... paths that file:// can't serve
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(0xFF111522.toInt()) // jarvis-black, avoids a white flash before the page paints
            addJavascriptInterface(Bridge(), "SingkroBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    assets.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    // the page missed earlier events: give it the current state
                    send(JSONObject().put("type", "state").put("state", AssistantBus.state.value).toString())
                    AssistantBus.settings()
                }
            }
        }
        setContentView(web)
        web.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/index.html")

        scope.launch { AssistantBus.events.collect(::send) }
        startAssistant()
    }

    override fun onDestroy() {
        scope.cancel()
        web.destroy()
        super.onDestroy()
    }

    private fun send(json: String) {
        web.evaluateJavascript("window.singkroEvent && window.singkroEvent($json)", null)
    }

    // the microphone foreground service may only be started while this screen is visible
    private fun startAssistant() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) AssistantService.start(this) else requestPermissions(needed.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AssistantService.start(this)
        }
    }

    /** Called from the page's JavaScript (JarvisConsole) on a binder thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun ask(text: String) {
            AssistantBus.requests.trySend(AssistantBus.Request.Ask(text.take(500)))
        }

        @JavascriptInterface
        fun listen() {
            AssistantBus.requests.trySend(AssistantBus.Request.Listen)
        }

        @JavascriptInterface
        fun setWakeSound(on: Boolean) = AssistantBus.setWakeSound(on)
    }

    private companion object {
        const val REQ_PERMS = 1
    }
}
