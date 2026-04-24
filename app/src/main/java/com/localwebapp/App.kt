package com.localwebapp

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class RecentProject(val name: String, val uri: String, val lastOpened: Long)

class RecentProjectsAdapter(
    private var projects: MutableList<RecentProject>,
    private val onOpen:     (RecentProject) -> Unit,
    private val onDelete:   (RecentProject) -> Unit,
    private val onShortcut: (RecentProject) -> Unit
) : RecyclerView.Adapter<RecentProjectsAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView    = view.findViewById(R.id.projectName)
        val date: TextView    = view.findViewById(R.id.projectDate)
        val deleteBtn: View   = view.findViewById(R.id.deleteButton)
        val shortcutBtn: View = view.findViewById(R.id.shortcutButton)
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
        holder.itemView.setOnClickListener    { onOpen(project) }
        holder.deleteBtn.setOnClickListener   { onDelete(project) }
        holder.shortcutBtn.setOnClickListener { onShortcut(project) }
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
    private lateinit var fab: FloatingActionButton
    private val prefs by lazy { getSharedPreferences("CouchFlow", MODE_PRIVATE) }

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
                        .setNegativeButton("Cancel", null).show()
                } else {
                    launchWebApp(treeUri, docFile.name ?: "Web App")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == "com.localwebapp.OPEN_SHORTCUT") {
            val uri   = intent.getStringExtra("uri")
            val title = intent.getStringExtra("title") ?: "Web App"
            if (uri != null) {
                try {
                    val arr = loadJson()
                    val filtered = (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .filter { it.getString("uri") != uri }.toMutableList()
                    filtered.add(0, JSONObject().apply {
                        put("name", title); put("uri", uri)
                        put("lastOpened", System.currentTimeMillis())
                    })
                    val out = JSONArray()
                    filtered.take(20).forEach { out.put(it) }
                    prefs.edit().putString("projects", out.toString()).apply()
                } catch (e: Exception) {}

                val launchIntent = Intent(this, WebAppActivity::class.java).apply {
                    putExtra(WebAppActivity.EXTRA_URI, uri)
                    putExtra(WebAppActivity.EXTRA_TITLE, title)
                }
                startActivity(launchIntent)
                finish()
                return
            }
        }

        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayShowTitleEnabled(false)

        recyclerView = findViewById(R.id.recyclerView)
        emptyView    = findViewById(R.id.emptyView)
        fab          = findViewById(R.id.fab)

        adapter = RecentProjectsAdapter(
            mutableListOf(),
            onOpen   = { launchWebApp(Uri.parse(it.uri), it.name) },
            onDelete = {
                AlertDialog.Builder(this)
                    .setTitle("Remove \"${it.name}\"?")
                    .setPositiveButton("Remove") { _, _ -> removeProject(it.uri) }
                    .setNegativeButton("Cancel", null).show()
            },
            onShortcut = { createShortcut(it) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fab.setOnClickListener { openFolderLauncher.launch(null) }

        // Let the FAB dodge the navigation bar inset so it clears the
        // Samsung gesture bar regardless of navigation mode.
        ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val p = v.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            p.bottomMargin = (24 * resources.displayMetrics.density).toInt() + navBar.bottom
            v.layoutParams = p
            insets
        }

        loadProjects()
    }

    override fun onResume() { super.onResume(); if (::adapter.isInitialized) loadProjects() }

    private fun launchWebApp(uri: Uri, name: String) {
        saveProject(name, uri.toString())
        val intent = Intent(this, WebAppActivity::class.java)
        intent.putExtra(WebAppActivity.EXTRA_URI, uri.toString())
        intent.putExtra(WebAppActivity.EXTRA_TITLE, name)
        startActivity(intent)
    }

    private fun createShortcut(project: RecentProject) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, "Launcher doesn't support pinning", Toast.LENGTH_LONG).show()
            return
        }
        val shortcutIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.localwebapp.OPEN_SHORTCUT"
            putExtra("uri",   project.uri)
            putExtra("title", project.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val iconBitmap = makeLetterIcon(project.name.firstOrNull()?.uppercase() ?: "C")
        val shortcut = ShortcutInfoCompat.Builder(this, "webapp_${project.uri.hashCode()}")
            .setShortLabel(project.name.take(24))
            .setLongLabel(project.name.take(48))
            .setIcon(IconCompat.createWithBitmap(iconBitmap))
            .setIntent(shortcutIntent).build()
        val success = ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
        if (success) Toast.makeText(this, "Shortcut added", Toast.LENGTH_SHORT).show()
        else         Toast.makeText(this, "Could not add shortcut", Toast.LENGTH_LONG).show()
    }

    private fun makeLetterIcon(letter: String): Bitmap {
        val size    = 192
        val bitmap  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas  = Canvas(bitmap)
        val colors  = listOf(
            Color.parseColor("#8B5CF6"), Color.parseColor("#7C3AED"),
            Color.parseColor("#6366F1"), Color.parseColor("#3B82F6"),
            Color.parseColor("#2563EB"), Color.parseColor("#06B6D4"),
            Color.parseColor("#EC4899"), Color.parseColor("#10B981")
        )
        val color = colors[Math.abs(letter.hashCode()) % colors.size]
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 40f, 40f, bgPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 110f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val yOffset = (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(letter, size / 2f, size / 2f - yOffset, textPaint)
        return bitmap
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
        if (::adapter.isInitialized) loadProjects()
    }

    private fun removeProject(uriStr: String) {
        val arr = loadJson()
        val filtered = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.getString("uri") != uriStr }
        val out = JSONArray()
        filtered.forEach { out.put(it) }
        prefs.edit().putString("projects", out.toString()).apply()
        if (::adapter.isInitialized) loadProjects()
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
        emptyView.visibility    = if (list.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (list.isEmpty()) View.GONE    else View.VISIBLE
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// NativeAudioRecorder
// ═══════════════════════════════════════════════════════════════════════════════
class NativeAudioRecorder {
    companion object {
        private const val SAMPLE_RATE      = 44100
        private const val CHANNEL_CONFIG   = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT     = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val NUM_CHANNELS     = 1
    }

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var isRecording = false
    @Volatile private var isPaused    = false
    private val pcmBuffer = ByteArrayOutputStream()
    private val waveformRing = FloatArray(256)
    private var waveformIdx  = 0
    @Volatile private var currentAmplitude = 0
    private var minBufferSize = 0

    @SuppressLint("MissingPermission")
    fun start(): String {
        if (isRecording) return "error:already recording"
        return try {
            minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize <= 0) return "error:invalid buffer size"
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize * 2)
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release(); return "error:AudioRecord not initialized"
            }
            pcmBuffer.reset(); waveformIdx = 0; currentAmplitude = 0
            isPaused = false; isRecording = true
            rec.startRecording(); recorder = rec
            recordThread = thread(start = true, name = "NativeAudioCapture") {
                val readBuf = ShortArray(minBufferSize)
                val byteBuf = ByteArray(minBufferSize * 2)
                while (isRecording) {
                    val n = try { rec.read(readBuf, 0, readBuf.size) } catch (e: Exception) { -1 }
                    if (n <= 0) continue
                    if (isPaused) continue
                    var peak = 0
                    for (i in 0 until n) {
                        val abs = Math.abs(readBuf[i].toInt())
                        if (abs > peak) peak = abs
                    }
                    currentAmplitude = peak
                    val normalized = peak / 32767.0f
                    synchronized(waveformRing) {
                        waveformRing[waveformIdx] = normalized
                        waveformIdx = (waveformIdx + 1) % waveformRing.size
                    }
                    for (i in 0 until n) {
                        val s = readBuf[i].toInt()
                        byteBuf[i * 2]     = (s and 0xFF).toByte()
                        byteBuf[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    synchronized(pcmBuffer) { pcmBuffer.write(byteBuf, 0, n * 2) }
                }
            }
            "ok"
        } catch (e: Exception) {
            android.util.Log.e("NativeAudio", "Start failed", e)
            cleanup(); "error:${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun pauseRecording():  String { if (!isRecording) return "error:not recording"; isPaused = true;  return "ok" }
    fun resumeRecording(): String { if (!isRecording) return "error:not recording"; isPaused = false; return "ok" }

    fun stop(): String {
        if (!isRecording) return "error:not recording"
        return try {
            isRecording = false
            try { recordThread?.join(500) } catch (e: Exception) {}
            recordThread = null
            try { recorder?.stop() } catch (e: Exception) {}
            try { recorder?.release() } catch (e: Exception) {}
            recorder = null
            val pcmBytes = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }
            if (pcmBytes.isEmpty()) return "error:empty recording"
            val wavBytes = pcmToWav(pcmBytes, SAMPLE_RATE, NUM_CHANNELS, BYTES_PER_SAMPLE * 8)
            val base64   = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            pcmBuffer.reset()
            "data:audio/wav;base64,$base64"
        } catch (e: Exception) {
            cleanup(); "error:${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun getWaveform(count: Int): String {
        val n = count.coerceIn(1, waveformRing.size)
        val out = StringBuilder()
        synchronized(waveformRing) {
            var idx = (waveformIdx - n + waveformRing.size) % waveformRing.size
            for (i in 0 until n) {
                if (i > 0) out.append(',')
                out.append(String.format(Locale.US, "%.3f", waveformRing[idx]))
                idx = (idx + 1) % waveformRing.size
            }
        }
        return out.toString()
    }

    fun getAmplitude():  Int     = currentAmplitude
    fun isActive():      Boolean = isRecording
    fun isPausedState(): Boolean = isPaused

    private fun cleanup() {
        isRecording = false
        try { recorder?.stop() } catch (e: Exception) {}
        try { recorder?.release() } catch (e: Exception) {}
        recorder = null; recordThread = null; pcmBuffer.reset()
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val pcmLen     = pcm.size
        val totalLen   = pcmLen + 36
        val byteRate   = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(pcmLen + 44)
        out[0]='R'.code.toByte(); out[1]='I'.code.toByte(); out[2]='F'.code.toByte(); out[3]='F'.code.toByte()
        out[4]=(totalLen and 0xFF).toByte(); out[5]=((totalLen shr 8) and 0xFF).toByte()
        out[6]=((totalLen shr 16) and 0xFF).toByte(); out[7]=((totalLen shr 24) and 0xFF).toByte()
        out[8]='W'.code.toByte(); out[9]='A'.code.toByte(); out[10]='V'.code.toByte(); out[11]='E'.code.toByte()
        out[12]='f'.code.toByte(); out[13]='m'.code.toByte(); out[14]='t'.code.toByte(); out[15]=' '.code.toByte()
        out[16]=16; out[17]=0; out[18]=0; out[19]=0
        out[20]=1;  out[21]=0
        out[22]=channels.toByte(); out[23]=0
        out[24]=(sampleRate and 0xFF).toByte(); out[25]=((sampleRate shr 8) and 0xFF).toByte()
        out[26]=((sampleRate shr 16) and 0xFF).toByte(); out[27]=((sampleRate shr 24) and 0xFF).toByte()
        out[28]=(byteRate and 0xFF).toByte(); out[29]=((byteRate shr 8) and 0xFF).toByte()
        out[30]=((byteRate shr 16) and 0xFF).toByte(); out[31]=((byteRate shr 24) and 0xFF).toByte()
        out[32]=blockAlign.toByte(); out[33]=0
        out[34]=bitsPerSample.toByte(); out[35]=0
        out[36]='d'.code.toByte(); out[37]='a'.code.toByte(); out[38]='t'.code.toByte(); out[39]='a'.code.toByte()
        out[40]=(pcmLen and 0xFF).toByte(); out[41]=((pcmLen shr 8) and 0xFF).toByte()
        out[42]=((pcmLen shr 16) and 0xFF).toByte(); out[43]=((pcmLen shr 24) and 0xFF).toByte()
        System.arraycopy(pcm, 0, out, 44, pcmLen)
        return out
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// JsFilesystemBridge — exposed as AndroidFS_native
// ═══════════════════════════════════════════════════════════════════════════════
class JsFilesystemBridge(
    private val ctx: android.content.Context,
    private val resolver: ContentResolver,
    private val rootUri: Uri
) {
    private fun resolvePath(path: String, createFile: Boolean): DocumentFile? {
        val root = DocumentFile.fromTreeUri(ctx, rootUri) ?: return null
        val segments = path.split("/", "\\")
            .filter { it.isNotEmpty() && it != ".." && it != "." }
        if (segments.isEmpty()) return null
        var node: DocumentFile = root
        for (i in 0 until segments.size - 1) {
            val name = segments[i]
            val child = node.findFile(name)
            node = if (child != null && child.isDirectory) child
                   else if (createFile) node.createDirectory(name) ?: return null
                   else return null
        }
        val leaf = segments.last()
        val existing = node.findFile(leaf)
        if (existing != null) return existing
        if (createFile) return node.createFile("application/octet-stream", leaf)
        return null
    }

    @JavascriptInterface
    fun read(path: String): String? {
        return try {
            val file = resolvePath(path, false) ?: return null
            if (!file.isFile) return null
            resolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("CF-FS", "read $path: ${e.message}")
            null
        }
    }

    @JavascriptInterface
    fun write(path: String, content: String): String {
        return try {
            val file = resolvePath(path, true) ?: return "error:path"
            val bytes = content.toByteArray(Charsets.UTF_8)
            val stream = resolver.openOutputStream(file.uri, "wt")
                ?: return "error:no output stream"
            stream.use { it.write(bytes) }
            "ok"
        } catch (e: Exception) {
            android.util.Log.e("CF-FS", "write $path: ${e.message}")
            "error:${e.message}"
        }
    }

    @JavascriptInterface
    fun readBase64(path: String): String? {
        return try {
            val file = resolvePath(path, false) ?: return null
            if (!file.isFile) return null
            val bytes = resolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: return null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    @JavascriptInterface
    fun writeBase64(path: String, b64: String): String {
        return try {
            val file = resolvePath(path, true) ?: return "error:path"
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val stream = resolver.openOutputStream(file.uri, "wt")
                ?: return "error:no output stream"
            stream.use { it.write(bytes) }
            "ok"
        } catch (e: Exception) {
            "error:${e.message}"
        }
    }

    @JavascriptInterface
    fun deleteFile(path: String): String {
        return try {
            val file = resolvePath(path, false) ?: return "error:not found"
            if (file.delete()) "ok" else "error:delete failed"
        } catch (e: Exception) {
            "error:${e.message}"
        }
    }

    @JavascriptInterface
    fun list(path: String): String {
        return try {
            val node: DocumentFile? = if (path.isEmpty() || path == "/" || path == ".") {
                DocumentFile.fromTreeUri(ctx, rootUri)
            } else {
                val root = DocumentFile.fromTreeUri(ctx, rootUri)
                if (root == null) {
                    null
                } else {
                    val segments = path.split("/", "\\")
                        .filter { it.isNotEmpty() && it != ".." && it != "." }
                    var n: DocumentFile = root
                    var failed = false
                    for (seg in segments) {
                        val next = n.findFile(seg)
                        if (next == null || !next.isDirectory) { failed = true; break }
                        n = next
                    }
                    if (failed) null else n
                }
            }
            if (node == null) "" else node.listFiles().mapNotNull { it.name }.joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun exists(path: String): Boolean {
        return try {
            val file = resolvePath(path, false)
            file != null && file.exists()
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun mkdir(path: String): String {
        return try {
            val root = DocumentFile.fromTreeUri(ctx, rootUri) ?: return "error:root"
            val segments = path.split("/", "\\")
                .filter { it.isNotEmpty() && it != ".." && it != "." }
            if (segments.isEmpty()) return "error:empty path"
            var node: DocumentFile = root
            for (name in segments) {
                val child = node.findFile(name)
                node = if (child != null && child.isDirectory) child
                       else node.createDirectory(name) ?: return "error:mkdir $name"
            }
            "ok"
        } catch (e: Exception) {
            "error:${e.message}"
        }
    }
}