package com.localwebapp

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

data class RecentProject(val name: String, val uri: String, val lastOpened: Long)

class RecentProjectsAdapter(
    private var projects: MutableList<RecentProject>,
    private val onOpen: (RecentProject) -> Unit,
    private val onDelete: (RecentProject) -> Unit
) : RecyclerView.Adapter<RecentProjectsAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.projectName)
        val date: TextView = view.findViewById(R.id.projectDate)
        val deleteBtn: View = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_project, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val project = projects[position]
        holder.name.text = project.name
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        holder.date.text = "Last opened: ${sdf.format(Date(project.lastOpened))}"
        holder.itemView.setOnClickListener { onOpen(project) }
        holder.deleteBtn.setOnClickListener { onDelete(project) }
    }

    override fun getItemCount() = projects.size

    fun updateProjects(newProjects: List<RecentProject>) {
        projects.clear()
        projects.addAll(newProjects)
        notifyDataSetChanged()
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RecentProjectsAdapter
    private lateinit var emptyView: View
    private val prefs by lazy { getSharedPreferences("LocalWebApp", MODE_PRIVATE) }

    private val openFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { treeUri ->
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(treeUri, flags)
                val docFile = DocumentFile.fromTreeUri(this, treeUri)
                if (docFile == null || !docFile.isDirectory) {
                    Toast.makeText(this, "Invalid folder", Toast.LENGTH_SHORT).show()
                    return@let
                }
                if (docFile.findFile("index.html") == null) {
                    AlertDialog.Builder(this)
                        .setTitle("No index.html found")
                        .setMessage("Open anyway?")
                        .setPositiveButton("Open") { _, _ ->
                            launchWebApp(treeUri, docFile.name ?: "Web App")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    launchWebApp(treeUri, docFile.name ?: "Web App")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        adapter = RecentProjectsAdapter(
            mutableListOf(),
            onOpen = { launchWebApp(Uri.parse(it.uri), it.name) },
            onDelete = {
                AlertDialog.Builder(this)
                    .setTitle("Remove \"${it.name}\"?")
                    .setPositiveButton("Remove") { _, _ -> removeProject(it.uri) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            openFolderLauncher.launch(null)
        }
        loadProjects()
    }

    override fun onResume() { super.onResume(); loadProjects() }

    private fun launchWebApp(uri: Uri, name: String) {
        saveProject(name, uri.toString())
        val intent = Intent(this, WebAppActivity::class.java)
        intent.putExtra(WebAppActivity.EXTRA_URI, uri.toString())
        intent.putExtra(WebAppActivity.EXTRA_TITLE, name)
        startActivity(intent)
    }

    private fun saveProject(name: String, uriStr: String) {
        val arr = loadJson()
        val filtered = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.getString("uri") != uriStr }.toMutableList()
        filtered.add(0, JSONObject().apply {
            put("name", name); put("uri", uriStr)
            put("lastOpened", System.currentTimeMillis())
        })
        val out = JSONArray()
        filtered.take(20).forEach { out.put(it) }
        prefs.edit().putString("projects", out.toString()).apply()
        loadProjects()
    }

    private fun removeProject(uriStr: String) {
        val arr = loadJson()
        val filtered = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.getString("uri") != uriStr }
        val out = JSONArray()
        filtered.forEach { out.put(it) }
        prefs.edit().putString("projects", out.toString()).apply()
        loadProjects()
    }

    private fun loadJson(): JSONArray {
        return try { JSONArray(prefs.getString("projects", "[]")) }
        catch (e: Exception) { JSONArray() }
    }

    private fun loadProjects() {
        val arr = loadJson()
        val list = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RecentProject(o.getString("name"), o.getString("uri"), o.getLong("lastOpened"))
        }
        adapter.updateProjects(list)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }
}

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
    private var permissionRequest: PermissionRequest? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionRequest?.grant(permissionRequest!!.resources)
            } else {
                permissionRequest?.deny()
            }
            permissionRequest = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webapp)

        val uriStr = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Web App"
        val folderUri = Uri.parse(uriStr)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            this.title = title
            setDisplayHomeAsUpEnabled(true)
        }

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)

        setupWebView()

        val s = SimpleServer(contentResolver, folderUri)
        server = s
        s.start()
        webView.loadUrl("http://localhost:${s.port}/")
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
            @JavascriptInterface
            fun toast(msg: String) = runOnUiThread {
                Toast.makeText(this@WebAppActivity, msg, Toast.LENGTH_SHORT).show()
            }
            @JavascriptInterface fun log(msg: String) = android.util.Log.d("WebApp", msg)
            @JavascriptInterface fun isNativeApp() = true
            @JavascriptInterface fun getPlatform() = "android"
            @JavascriptInterface fun share(text: String) = runOnUiThread {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }, "Share"
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
            override fun onReceivedError(
                v: WebView, req: WebResourceRequest, err: WebResourceError
            ) {
                if (req.isForMainFrame) {
                    progressBar.visibility = View.GONE
                    errorView.visibility = View.VISIBLE
                    errorText.text = "Error: ${err.description}"
                }
            }
            override fun shouldOverrideUrlLoading(
                v: WebView, req: WebResourceRequest
            ): Boolean {
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
            override fun onReceivedTitle(v: WebView, t: String) {
                supportActionBar?.title = t
            }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.d("WebApp-JS", m.message())
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                val needsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (needsCamera) {
                    if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(request.resources)
                    } else {
                        permissionRequest = request
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }
                } else {
                    request.grant(request.resources)
                }
            }
            override fun onJsAlert(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@WebAppActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
            override fun onJsConfirm(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@WebAppActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .show()
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        webView.destroy()
    }

    override fun onPause() { super.onPause(); webView.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
}

class SimpleServer(
    private val resolver: ContentResolver,
    private val rootUri: Uri
) {
    private var serverSocket: ServerSocket? = null
    var port = 0
        private set
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var active = false

    fun start() {
        serverSocket = ServerSocket(0).also { port = it.localPort }
        active = true
        executor.submit {
            while (active) {
                try {
                    val client = serverSocket!!.accept()
                    executor.submit {
                        try { handle(client) } finally { client.close() }
                    }
                } catch (e: Exception) {
                    if (active) e.printStackTrace()
                }
            }
        }
    }

    fun stop() {
        active = false
        try { serverSocket?.close() } catch (e: Exception) {}
        executor.shutdownNow()
    }

    private fun handle(s: java.net.Socket) {
        val reader = s.inputStream.bufferedReader(Charsets.ISO_8859_1)
        val out = s.outputStream
        val line = reader.readLine() ?: return
        val parts = line.trim().split(" ")
        if (parts.size < 2) return
        var path = URLDecoder.decode(parts[1].substringBefore("?"), "UTF-8")
        do {
            val h = reader.readLine() ?: break
            if (h.isBlank()) break
        } while (true)
        if (path == "/" || path.isEmpty()) path = "/index.html"
        val segments = path.trimStart('/').split("/")
            .filter { it.isNotEmpty() && it != ".." }
        val file = resolve(segments)
        when {
            file == null || !file.exists() -> {
                val idx = resolve(listOf("index.html"))
                if (idx != null && idx.exists()) serveFile(out, idx) else send404(out)
            }
            file.isDirectory -> {
                val idx = file.findFile("index.html")
                if (idx != null) serveFile(out, idx) else send404(out)
            }
            else -> serveFile(out, file)
        }
    }

    private fun resolve(segments: List<String>): DocumentFile? {
        val ctx = try {
            val f = ContentResolver::class.java.getDeclaredField("mContext")
            f.isAccessible = true
            f.get(resolver) as android.content.Context
        } catch (e: Exception) { return null }
        var node = DocumentFile.fromTreeUri(ctx, rootUri) ?: return null
        for (seg in segments) {
            node = node.findFile(seg) ?: return null
        }
        return node
    }

    private fun serveFile(out: OutputStream, file: DocumentFile) {
        val bytes = resolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: run { send404(out); return }
        val mime = getMime(file.name ?: "")
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Cache-Control: no-cache\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun send404(out: OutputStream) {
        val body = "<h1>404 Not Found</h1>".toByteArray()
        val header = "HTTP/1.1 404 Not Found\r\n" +
            "Content-Type: text/html\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun getMime(name: String) = when (
        name.substringAfterLast('.', "").lowercase()
    ) {
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