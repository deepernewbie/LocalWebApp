package com.localwebapp

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.Executors

class WebAppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private var server: SimpleServer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webapp)

        val uriStr = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Web App"
        val folderUri = Uri.parse(uriStr)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply { this.title = title; setDisplayHomeAsUpEnabled(true) }

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)

        setupWebView()

        server = SimpleServer(contentResolver, folderUri).also {
            it.start()
            webView.loadUrl("http://localhost:${it.port}/")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        webView.addJavascriptInterface(object : Any() {
            @JavascriptInterface fun toast(msg: String) =
                runOnUiThread { Toast.makeText(this@WebAppActivity, msg, Toast.LENGTH_SHORT).show() }
            @JavascriptInterface fun log(msg: String) = android.util.Log.d("WebApp", msg)
            @JavascriptInterface fun isNativeApp() = true
            @JavascriptInterface fun getPlatform() = "android"
            @JavascriptInterface fun share(text: String) = runOnUiThread {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
                    "Share"
                ))
            }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                errorView.visibility = View.GONE
            }
            override fun onPageFinished(v: WebView, url: String) {
                progressBar.visibility = View.GONE
            }
            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) {
                    progressBar.visibility = View.GONE
                    errorView.visibility = View.VISIBLE
                    errorText.text = "Error: ${err.description}"
                }
            }
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.startsWith("http://localhost")) return false
                startActivity(Intent(Intent.ACTION_VIEW, req.url))
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                progressBar.progress = p
                if (p == 100) progressBar.visibility = View.GONE
            }
            override fun onReceivedTitle(v: WebView, t: String) { supportActionBar?.title = t }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.d("WebApp-JS", m.message()); return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
    override fun onDestroy() { super.onDestroy(); server?.stop(); webView.destroy() }
    override fun onPause() { super.onPause(); webView.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
}

class SimpleServer(private val resolver: ContentResolver, private val rootUri: Uri) {
    private var socket: ServerSocket? = null
    var port = 0; private set
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var active = false

    fun start() {
        socket = ServerSocket(0).also { port = it.localPort }
        active = true
        pool.submit {
            while (active) {
                try {
                    val client = socket!!.accept()
                    pool.submit { try { handle(client) } finally { client.close() } }
                } catch (e: Exception) { if (active) e.printStackTrace() }
            }
        }
    }

    fun stop() { active = false; try { socket?.close() } catch (e: Exception) {} pool.shutdownNow() }

    private fun handle(s: java.net.Socket) {
        val reader = s.inputStream.bufferedReader(Charsets.ISO_8859_1)
        val out = s.outputStream
        val line = reader.readLine() ?: return
        val parts = line.trim().split(" ")
        if (parts.size < 2) return
        var path = URLDecoder.decode(parts[1].substringBefore("?"), "UTF-8")
        do { val h = reader.readLine() ?: break } while (h.isNotBlank())
        if (path == "/" || path.isEmpty()) path = "/index.html"
        val segments = path.trimStart('/').split("/").filter { it.isNotEmpty() && it != ".." }
        val file = resolve(segments)
        when {
            file == null || !file.exists() -> {
                val idx = resolve(listOf("index.html"))
                if (idx != null && idx.exists()) serveFile(out, idx) else send404(out)
            }
            file.isDirectory -> { val idx = file.findFile("index.html"); if (idx != null) serveFile(out, idx) else send404(out) }
            else -> serveFile(out, file)
        }
    }

    private fun resolve(segments: List<String>): DocumentFile? {
        val ctx = try {
            val f = ContentResolver::class.java.getDeclaredField("mContext").apply { isAccessible = true }
            f.get(resolver) as android.content.Context
        } catch (e: Exception) { return null }
        var node = DocumentFile.fromTreeUri(ctx, rootUri) ?: return null
        for (seg in segments) node = node.findFile(seg) ?: return null
        return node
    }

    private fun serveFile(out: OutputStream, file: DocumentFile) {
        val bytes = resolver.openInputStream(file.uri)?.use { it.readBytes() } ?: run { send404(out); return }
        val mime = mime(file.name ?: "")
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1)); out.write(bytes); out.flush()
    }

    private fun send404(out: OutputStream) {
        val body = "<h1>404 Not Found</h1>".toByteArray()
        val h = "HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\n\r\n"
        out.write(h.toByteArray(Charsets.ISO_8859_1)); out.write(body); out.flush()
    }

    private fun mime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }
}
