package com.novelreader.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var storage: NovelStorage
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var volumeKeysEnabled = true
    private var lastBackPress = 0L

    private val openTxtLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importUri(uri)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        storage = NovelStorage(this)
        volumeKeysEnabled = storage.loadState()
            .optJSONObject("settings")
            ?.optBoolean("volumeKeys", true) ?: true

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false

            addJavascriptInterface(AndroidBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val uri = request.url
                    if (uri.host != "novelreader.local") return null

                    return when (uri.path) {
                        "/index.html", "/" -> {
                            val stream = assets.open("reader.html")
                            WebResourceResponse("text/html", "UTF-8", stream)
                        }
                        "/api/image" -> {
                            val targetUrl = uri.getQueryParameter("url")
                            val refresh = uri.getQueryParameter("refresh") == "1"
                            if (targetUrl.isNullOrBlank()) {
                                return WebResourceResponse("text/plain", "UTF-8", 400, "Bad Request", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            }
                            try {
                                val (bytes, mime) = storage.fetchAndCacheImage(targetUrl, refresh)
                                val headers = mapOf("Cache-Control" to "public, max-age=86400")
                                WebResourceResponse(mime, null, 200, "OK", headers, ByteArrayInputStream(bytes))
                            } catch (e: Exception) {
                                WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    502,
                                    "Bad Gateway",
                                    emptyMap(),
                                    ByteArrayInputStream((e.message ?: "error").toByteArray())
                                )
                            }
                        }
                        else -> null
                    }
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("https://novelreader.local/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()") { result ->
                    if (result != "true") {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPress < 1800) {
                            finish()
                        } else {
                            lastBackPress = now
                            Toast.makeText(this@MainActivity, "再按一次返回退出阅读器", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeysEnabled) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                webView.evaluateJavascript("window.pageTurn && window.pageTurn(1)", null)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                webView.evaluateJavascript("window.pageTurn && window.pageTurn(-1)", null)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun importUri(uri: Uri) {
        ioExecutor.execute {
            try {
                val filename = resolveDisplayName(uri) ?: "导入的小说.txt"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取文件")
                val result = storage.importTxt(filename, bytes)
                val jsPayload = JSONObject.quote(result.toString())
                runOnUiThread {
                    webView.evaluateJavascript("window.onBookImported && window.onBookImported($jsPayload)", null)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun getState(): String = storage.loadState().toString()

        @JavascriptInterface
        fun saveSettings(json: String) {
            storage.updateSettings(json)
            val obj = JSONObject(json)
            if (obj.has("volumeKeys")) {
                volumeKeysEnabled = obj.optBoolean("volumeKeys", true)
            }
        }

        @JavascriptInterface
        fun saveProgress(bookId: String, chapter: Int, scrollRatio: Double) {
            storage.saveProgress(bookId, chapter, scrollRatio)
        }

        @JavascriptInterface
        fun getBookToc(bookId: String): String = storage.getBookTocJson(bookId).toString()

        @JavascriptInterface
        fun getChapter(bookId: String, index: Int): String = storage.getChapterJson(bookId, index).toString()

        @JavascriptInterface
        fun deleteBook(bookId: String) {
            storage.deleteBook(bookId)
        }

        @JavascriptInterface
        fun clearImageCache(): Int = storage.clearImageCache()

        @JavascriptInterface
        fun pickTxtFile() {
            runOnUiThread {
                openTxtLauncher.launch(arrayOf("text/plain", "*/*"))
            }
        }

        @JavascriptInterface
        fun setSystemBarStyle(bgHex: String, isDark: Boolean) {
            runOnUiThread {
                try {
                    val color = Color.parseColor(bgHex)
                    window.statusBarColor = color
                    window.navigationBarColor = color
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !isDark
                    controller.isAppearanceLightNavigationBars = !isDark
                } catch (_: Exception) {
                }
            }
        }
    }
}
