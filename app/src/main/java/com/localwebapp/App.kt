package com.localwebapp

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
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
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

// ═══════════════════════════════════════════════════════════════════════════════
// DebugLog — thread-safe ring buffer of recent runtime events.
//
// Captures console output, errors, bridge calls, network requests, FS ops,
// permission prompts, and lifecycle events. The user opens the overlay (via
// triple-tap on the top edge or a persistent floating button) to view, copy,
// share, or save the log. Designed for the prompt-AI -> run -> debug -> paste
// -> ask-AI-again loop where the user has no laptop.
//
// Sensitive data scrubbing happens at push time:
//   - URL query params named key/token/auth/password/apikey are stripped
//   - base64-looking strings longer than 80 chars are truncated
//   - file content payloads are never stored, only path + size + result
// ═══════════════════════════════════════════════════════════════════════════════
object DebugLog {

    enum class Severity { TRACE, INFO, WARN, ERROR }
    enum class Source   { CONSOLE, ERROR, BRIDGE, FETCH, FS, PERM, USER, LIFECYCLE, AUDIO }

    data class Entry(
        val tsEpochMs: Long,
        val sessionMs: Long,
        val severity:  Severity,
        val source:    Source,
        val message:   String
    )

    private const val MAX_ENTRIES = 2000
    private const val MAX_BYTES   = 256 * 1024  // soft cap; we trim entries when exceeded

    private val ring = ArrayDeque<Entry>(MAX_ENTRIES)
    private val lock = Any()
    private var byteEstimate = 0L

    private val sessionStart = AtomicLong(System.currentTimeMillis())

    // Session metadata captured once per WebApp launch
    @Volatile var couchFlowVersion: String = "unknown"
    @Volatile var webViewVersion:   String = "unknown"
    @Volatile var deviceModel:      String = "${Build.MANUFACTURER} ${Build.MODEL}"
    @Volatile var androidApi:       Int    = Build.VERSION.SDK_INT
    @Volatile var projectName:      String = ""
    @Volatile var entryUrl:         String = ""

    /** Reset session timer and clear log. Call when launching a web app. */
    fun startSession(project: String, entry: String) {
        synchronized(lock) {
            ring.clear()
            byteEstimate = 0
            sessionStart.set(System.currentTimeMillis())
            projectName = project
            entryUrl    = entry
        }
        push(Source.LIFECYCLE, Severity.INFO, "session started: $project")
    }

    fun push(source: Source, severity: Severity, message: String) {
        val now      = System.currentTimeMillis()
        val sessMs   = now - sessionStart.get()
        val scrubbed = scrub(message)
        val entry    = Entry(now, sessMs, severity, source, scrubbed)

        synchronized(lock) {
            ring.addLast(entry)
            byteEstimate += scrubbed.length + 32 // rough overhead per entry

            while (ring.size > MAX_ENTRIES || byteEstimate > MAX_BYTES) {
                val removed = ring.removeFirst()
                byteEstimate -= removed.message.length + 32
            }
        }

        // Mirror to logcat for adb users (no-op on the user's phone but useful
        // when developers run their own builds).
        val logTag = "CF-${source.name}"
        when (severity) {
            Severity.ERROR -> android.util.Log.e(logTag, scrubbed)
            Severity.WARN  -> android.util.Log.w(logTag, scrubbed)
            Severity.INFO  -> android.util.Log.i(logTag, scrubbed)
            Severity.TRACE -> android.util.Log.d(logTag, scrubbed)
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { ring.toList() }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            byteEstimate = 0
        }
        push(Source.LIFECYCLE, Severity.INFO, "log cleared")
    }

    /** Render the log as a plain-text block formatted for AI consumption. */
    fun renderForAi(): String {
        val entries  = snapshot()
        val now      = System.currentTimeMillis()
        val durSec   = (now - sessionStart.get()) / 1000
        val isoStart = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
            .format(Date(sessionStart.get()))

        val sb = StringBuilder()
        sb.append("=== CouchFlow debug log ===\n")
        sb.append("CouchFlow version: ").append(couchFlowVersion).append('\n')
        sb.append("WebView: ").append(webViewVersion).append('\n')
        sb.append("Device: ").append(deviceModel).append(", Android API ").append(androidApi).append('\n')
        sb.append("Project: ").append(projectName).append('\n')
        sb.append("Entry: ").append(entryUrl).append('\n')
        sb.append("Session started: ").append(isoStart).append('\n')
        sb.append("Session duration: ").append(durSec).append("s\n")
        sb.append("Entry count: ").append(entries.size).append('\n')
        sb.append('\n')
        sb.append("--- Events (most recent first) ---\n")

        val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        for (e in entries.reversed()) {
            val ts  = tsFmt.format(Date(e.tsEpochMs))
            val sev = e.severity.name.padEnd(5)
            val src = e.source.name.padEnd(9)
            sb.append('[').append(ts).append("] ")
              .append(sev).append(' ')
              .append(src).append(' ')
              .append(e.message).append('\n')
        }

        sb.append("--- End log ---\n")
        return sb.toString()
    }

    /** Truncate base64-ish strings and strip sensitive URL params. */
    private fun scrub(message: String): String {
        var s = message

        // Strip sensitive query params from URLs in the message
        val sensitiveKeys = listOf("key", "token", "auth", "password", "apikey", "api_key", "secret")
        for (k in sensitiveKeys) {
            val regex = Regex("([?&])${Regex.escape(k)}=([^&\\s]*)", RegexOption.IGNORE_CASE)
            s = regex.replace(s) { mr -> "${mr.groupValues[1]}$k=<redacted>" }
        }

        // Truncate any single token longer than 80 chars that looks base64-ish
        // (high alphanumeric+ratio, no spaces). This catches data: URIs, raw
        // base64, hex blobs without flagging normal text.
        val longTok = Regex("\\b([A-Za-z0-9+/=_-]{80,})\\b")
        s = longTok.replace(s) { mr ->
            val t = mr.groupValues[1]
            "${t.take(40)}…<${t.length}ch>…${t.takeLast(20)}"
        }

        // data: URIs anywhere in the message
        val dataUri = Regex("data:[^,\\s]+,[^\\s\"']{40,}")
        s = dataUri.replace(s) { mr ->
            val t = mr.value
            val comma = t.indexOf(',')
            "${t.substring(0, comma + 1)}<${t.length - comma - 1}ch base64>"
        }

        return s
    }

    /** Convenience helpers used throughout the runtime. */
    fun console(level: String, msg: String) {
        val sev = when (level.lowercase()) {
            "error" -> Severity.ERROR
            "warn", "warning" -> Severity.WARN
            "info"  -> Severity.INFO
            else    -> Severity.TRACE
        }
        push(Source.CONSOLE, sev, msg)
    }
    fun error(msg: String)                 = push(Source.ERROR,     Severity.ERROR, msg)
    fun bridge(method: String, info: String, result: String) =
        push(Source.BRIDGE, Severity.TRACE, "$method($info) -> $result")
    fun fetch(method: String, url: String, status: Int, ms: Long) =
        push(Source.FETCH, if (status in 200..299) Severity.TRACE else Severity.WARN,
             "$method $url -> $status (${ms}ms)")
    fun fs(op: String, path: String, result: String) =
        push(Source.FS, Severity.TRACE, "$op($path) -> $result")
    fun perm(name: String, granted: Boolean) =
        push(Source.PERM, if (granted) Severity.INFO else Severity.WARN,
             "$name -> ${if (granted) "granted" else "denied"}")
    fun audio(event: String) = push(Source.AUDIO, Severity.INFO, event)
    fun lifecycle(event: String) = push(Source.LIFECYCLE, Severity.INFO, event)
    fun user(label: String, payload: String) =
        push(Source.USER, Severity.INFO, "$label: $payload")
}

// ═══════════════════════════════════════════════════════════════════════════════
// RecentProject + adapter
// ═══════════════════════════════════════════════════════════════════════════════
data class RecentProject(
    val name: String,
    val uri: String,
    val lastOpened: Long,
    val missing: Boolean = false
)

class RecentProjectsAdapter(
    private var projects: MutableList<RecentProject>,
    private val onOpen:     (RecentProject) -> Unit,
    private val onDelete:   (RecentProject) -> Unit,
    private val onShortcut: (RecentProject) -> Unit,
    private val onRepick:   (RecentProject) -> Unit
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
        if (project.missing) {
            holder.date.text = "Folder missing — tap to re-pick"
            holder.itemView.setOnClickListener { onRepick(project) }
        } else {
            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            holder.date.text = "Last opened: ${sdf.format(Date(project.lastOpened))}"
            holder.itemView.setOnClickListener { onOpen(project) }
        }
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

// ═══════════════════════════════════════════════════════════════════════════════
// MainActivity
// ═══════════════════════════════════════════════════════════════════════════════
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
            onShortcut = { createShortcut(it) },
            onRepick   = { offerRepick(it) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fab.setOnClickListener { openFolderLauncher.launch(null) }
        // Long-press the FAB to toggle the persistent debug button shown
        // inside web apps. Off by default — triple-tapping the top edge of
        // any web app also opens the debug overlay.
        fab.setOnLongClickListener {
            val current = prefs.getBoolean("debug_button_visible", false)
            val next    = !current
            prefs.edit().putBoolean("debug_button_visible", next).apply()
            Toast.makeText(this,
                if (next) "Debug button enabled. It will appear inside web apps."
                else      "Debug button hidden. Triple-tap the top edge to open the log.",
                Toast.LENGTH_LONG).show()
            true
        }

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

    private fun offerRepick(project: RecentProject) {
        AlertDialog.Builder(this)
            .setTitle("\"${project.name}\" folder is missing")
            .setMessage("The original folder may have been moved, renamed, or its " +
                    "permission revoked. Pick the folder again to restore access?")
            .setPositiveButton("Pick folder") { _, _ ->
                // Open picker; on success, the new URI replaces the old entry.
                pendingRepickFor = project.uri
                openFolderLauncher.launch(null)
            }
            .setNegativeButton("Remove from list") { _, _ -> removeProject(project.uri) }
            .show()
    }

    private var pendingRepickFor: String? = null

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

    /**
     * Load projects, marking entries as `missing` when the SAF URI is no
     * longer accessible (folder was deleted/renamed/moved, or permission was
     * revoked). The row stays in the list with a "Folder missing — re-pick"
     * affordance instead of silently disappearing.
     */
    private fun loadProjects() {
        val arr = loadJson()
        val list = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val uri  = o.getString("uri")
            val name = o.getString("name")
            val opened = o.getLong("lastOpened")
            val missing = !isFolderAccessible(uri)
            RecentProject(name, uri, opened, missing)
        }
        adapter.updateProjects(list)
        emptyView.visibility    = if (list.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (list.isEmpty()) View.GONE    else View.VISIBLE
    }

    private fun isFolderAccessible(uriStr: String): Boolean {
        return try {
            val uri  = Uri.parse(uriStr)
            val doc  = DocumentFile.fromTreeUri(this, uri)
            doc != null && doc.exists() && doc.isDirectory
        } catch (e: Exception) {
            false
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// NativeAudioRecorder — instrumented with DebugLog calls for lifecycle events
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
            DebugLog.audio("recorder started @ ${SAMPLE_RATE}Hz mono 16-bit")
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
            DebugLog.error("audio start failed: ${e.message}")
            cleanup(); "error:${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun pauseRecording():  String {
        if (!isRecording) return "error:not recording"
        isPaused = true
        DebugLog.audio("recorder paused")
        return "ok"
    }
    fun resumeRecording(): String {
        if (!isRecording) return "error:not recording"
        isPaused = false
        DebugLog.audio("recorder resumed")
        return "ok"
    }

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
            if (pcmBytes.isEmpty()) {
                DebugLog.audio("recorder stopped: empty recording")
                return "error:empty recording"
            }
            val wavBytes = pcmToWav(pcmBytes, SAMPLE_RATE, NUM_CHANNELS, BYTES_PER_SAMPLE * 8)
            val base64   = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            DebugLog.audio("recorder stopped: ${pcmBytes.size}B PCM -> ${wavBytes.size}B WAV")
            pcmBuffer.reset()
            "data:audio/wav;base64,$base64"
        } catch (e: Exception) {
            DebugLog.error("audio stop failed: ${e.message}")
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
// JsFilesystemBridge — instrumented; logs each FS op with path + result, never
// the file content. New AndroidFS_native.stat() returns "size|mtime|isDir".
// ═══════════════════════════════════════════════════════════════════════════════
class JsFilesystemBridge(
    private val ctx: Context,
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
        val r = try {
            val file = resolvePath(path, false)
            if (file == null || !file.isFile) null
            else resolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            DebugLog.fs("read", path, "error:${e.message}")
            return null
        }
        DebugLog.fs("read", path, if (r == null) "null" else "${r.length}ch")
        return r
    }

    @JavascriptInterface
    fun write(path: String, content: String): String {
        return try {
            val file = resolvePath(path, true)
            if (file == null) {
                DebugLog.fs("write", path, "error:path"); return "error:path"
            }
            val bytes = content.toByteArray(Charsets.UTF_8)
            val stream = resolver.openOutputStream(file.uri, "wt")
            if (stream == null) {
                DebugLog.fs("write", path, "error:no output stream"); return "error:no output stream"
            }
            stream.use { it.write(bytes) }
            DebugLog.fs("write", path, "${bytes.size}B ok")
            "ok"
        } catch (e: Exception) {
            DebugLog.fs("write", path, "error:${e.message}")
            "error:${e.message}"
        }
    }

    @JavascriptInterface
    fun readBase64(path: String): String? {
        return try {
            val file = resolvePath(path, false)
            if (file == null || !file.isFile) {
                DebugLog.fs("readBase64", path, "null"); return null
            }
            val bytes = resolver.openInputStream(file.uri)?.use { it.readBytes() }
            if (bytes == null) {
                DebugLog.fs("readBase64", path, "null"); return null
            }
            DebugLog.fs("readBase64", path, "${bytes.size}B ok")
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            DebugLog.fs("readBase64", path, "error:${e.message}")
            null
        }
    }

    @JavascriptInterface
    fun writeBase64(path: String, b64: String): String {
        return try {
            val file = resolvePath(path, true)
            if (file == null) {
                DebugLog.fs("writeBase64", path, "error:path"); return "error:path"
            }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val stream = resolver.openOutputStream(file.uri, "wt")
            if (stream == null) {
                DebugLog.fs("writeBase64", path, "error:no output stream"); return "error:no output stream"
            }
            stream.use { it.write(bytes) }
            DebugLog.fs("writeBase64", path, "${bytes.size}B ok")
            "ok"
        } catch (e: Exception) {
            DebugLog.fs("writeBase64", path, "error:${e.message}")
            "error:${e.message}"
        }
    }

    @JavascriptInterface
    fun deleteFile(path: String): String {
        return try {
            val file = resolvePath(path, false)
            if (file == null) {
                DebugLog.fs("delete", path, "error:not found"); return "error:not found"
            }
            val ok = file.delete()
            DebugLog.fs("delete", path, if (ok) "ok" else "error:delete failed")
            if (ok) "ok" else "error:delete failed"
        } catch (e: Exception) {
            DebugLog.fs("delete", path, "error:${e.message}")
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
            val result = if (node == null) "" else node.listFiles().mapNotNull { it.name }.joinToString("\n")
            val count = if (result.isEmpty()) 0 else result.count { it == '\n' } + 1
            DebugLog.fs("list", path.ifEmpty { "/" }, "$count items")
            result
        } catch (e: Exception) {
            DebugLog.fs("list", path, "error:${e.message}")
            ""
        }
    }

    @JavascriptInterface
    fun exists(path: String): Boolean {
        return try {
            val file = resolvePath(path, false)
            val ok = file != null && file.exists()
            DebugLog.fs("exists", path, ok.toString())
            ok
        } catch (e: Exception) {
            DebugLog.fs("exists", path, "error:${e.message}")
            false
        }
    }

    @JavascriptInterface
    fun mkdir(path: String): String {
        return try {
            val root = DocumentFile.fromTreeUri(ctx, rootUri) ?: return "error:root"
            val segments = path.split("/", "\\")
                .filter { it.isNotEmpty() && it != ".." && it != "." }
            if (segments.isEmpty()) {
                DebugLog.fs("mkdir", path, "error:empty path"); return "error:empty path"
            }
            var node: DocumentFile = root
            for (name in segments) {
                val child = node.findFile(name)
                node = if (child != null && child.isDirectory) child
                       else node.createDirectory(name) ?: run {
                           DebugLog.fs("mkdir", path, "error:create $name")
                           return "error:mkdir $name"
                       }
            }
            DebugLog.fs("mkdir", path, "ok")
            "ok"
        } catch (e: Exception) {
            DebugLog.fs("mkdir", path, "error:${e.message}")
            "error:${e.message}"
        }
    }

    /**
     * Returns "size|mtime|isDir" or null. mtime in milliseconds since epoch,
     * size in bytes (0 for directories). The shim parses this into an object.
     * Lets web apps build list views without round-tripping content.
     */
    @JavascriptInterface
    fun stat(path: String): String? {
        return try {
            val file = resolvePath(path, false)
            if (file == null || !file.exists()) {
                DebugLog.fs("stat", path, "null"); return null
            }
            val size  = if (file.isFile) file.length() else 0L
            val mtime = file.lastModified()
            val isDir = file.isDirectory
            val result = "$size|$mtime|$isDir"
            DebugLog.fs("stat", path, result)
            result
        } catch (e: Exception) {
            DebugLog.fs("stat", path, "error:${e.message}")
            null
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════════════
// WebAppActivity — fullscreen WebView, edge-respecting layout, debug overlay
// ═══════════════════════════════════════════════════════════════════════════════
class WebAppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI   = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
        private const val NET_CACHE = "netcache"

        // Updated shim adds:
        //   - window.CouchFlow.debug(label, payload) -> pushes USER event
        //   - global error/unhandledrejection listeners that surface to the log
        //   - AndroidFS.stat() returning {size, mtime, isDir}
        // No web-app-visible API removed; everything from v3 still works.
        private const val LWA_SHIM_JS = """
(function(){
  if (window.__couchflow_shim_v5) return;
  window.__couchflow_shim_v5 = true;

  var features = [];
  var readyResolve;
  var readyPromise = new Promise(function(r){ readyResolve = r; });

  var lwaObj = {
    version:  'v5',
    features: features,
    ready:    readyPromise,
    isNative: typeof Android !== 'undefined' && typeof Android.isNativeApp === 'function'
  };

  function debugPush(label, payload) {
    try {
      if (typeof Android !== 'undefined' && Android.debugUserEvent) {
        var s;
        try { s = (typeof payload === 'string') ? payload : JSON.stringify(payload); }
        catch(e) { s = String(payload); }
        Android.debugUserEvent(String(label), s == null ? '' : s);
      }
    } catch(e) {}
  }
  lwaObj.debug = debugPush;

  // Convenience wrapper so web apps can wire their own "View log" button to
  // CouchFlow.openDebugLog() without name-checking Android.openDebugLog.
  lwaObj.openDebugLog = function() {
    try {
      if (typeof Android !== 'undefined' && Android.openDebugLog) {
        Android.openDebugLog();
        return true;
      }
    } catch(e) {}
    return false;
  };

  window.LWA = lwaObj;
  window.CouchFlow = lwaObj;

  window.addEventListener('error', function(ev) {
    try {
      var msg = (ev.message || 'error') +
                (ev.filename ? ' @ ' + ev.filename + ':' + (ev.lineno||0) + ':' + (ev.colno||0) : '') +
                (ev.error && ev.error.stack ? '\n' + ev.error.stack : '');
      if (typeof Android !== 'undefined' && Android.debugError) Android.debugError(msg);
    } catch(_) {}
  });
  window.addEventListener('unhandledrejection', function(ev) {
    try {
      var reason = ev.reason;
      var msg = 'Unhandled promise rejection: ' +
        (reason && reason.stack ? reason.stack : (reason && reason.message ? reason.message : String(reason)));
      if (typeof Android !== 'undefined' && Android.debugError) Android.debugError(msg);
    } catch(_) {}
  });

  // navigator.share patching: Web Share API Level 2 supports {files: File[]}.
  // Route file shares through Android.shareFiles so web apps use the standard
  // Web API and never need to know about CouchFlow.
  if (typeof navigator !== 'undefined' && typeof Android !== 'undefined') {
    var origShare = navigator.share ? navigator.share.bind(navigator) : null;

    function blobToBase64(blob) {
      return new Promise(function(resolve, reject) {
        var fr = new FileReader();
        fr.onload = function() {
          var s = fr.result || '';
          var comma = s.indexOf(',');
          resolve(comma >= 0 ? s.slice(comma + 1) : s);
        };
        fr.onerror = function() { reject(fr.error || new Error('FileReader failed')); };
        fr.readAsDataURL(blob);
      });
    }

    navigator.share = function(data) {
      data = data || {};
      if (data.files && data.files.length > 0 && Android.shareFiles) {
        var promises = [];
        for (var i = 0; i < data.files.length; i++) {
          (function(f, idx) {
            promises.push(blobToBase64(f).then(function(b64) {
              return {
                name:     f.name || ('file-' + idx),
                mimeType: f.type || 'application/octet-stream',
                base64:   b64
              };
            }));
          })(data.files[i], i);
        }
        return Promise.all(promises).then(function(payload) {
          var json = JSON.stringify(payload);
          var r = Android.shareFiles(json);
          if (r === 'ok') return undefined;
          throw new Error(r || 'shareFiles failed');
        });
      }
      if ((data.text || data.url) && Android.share) {
        var t = (data.title ? data.title + '\n' : '') +
                (data.text  ? data.text  : '') +
                (data.url   ? (data.text ? '\n' : '') + data.url : '');
        Android.share(t);
        return Promise.resolve();
      }
      if (origShare) return origShare(data);
      return Promise.reject(new DOMException('share unsupported', 'NotSupportedError'));
    };

    navigator.canShare = function(data) {
      if (!data) return true;
      if (data.files && data.files.length > 0) return !!Android.shareFiles;
      if (data.text || data.url || data.title) return !!Android.share;
      return false;
    };
  }

  if (typeof Android !== 'undefined' && typeof Android.recStart === 'function') {
    features.push('audio');

    var origGUM = navigator.mediaDevices && navigator.mediaDevices.getUserMedia
                  ? navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices) : null;
    var OrigMR = window.MediaRecorder;
    var OrigAC = window.AudioContext || window.webkitAudioContext;

    function makeTrack() {
      return {
        kind:'audio', id:'cf-track-'+Math.random().toString(36).slice(2),
        label:'Native Android Microphone',
        enabled:true, muted:false, readyState:'live', contentHint:'',
        stop: function(){ this.readyState='ended'; this.enabled=false; },
        getSettings: function(){ return {sampleRate:44100, channelCount:1, deviceId:'native'}; },
        getCapabilities: function(){ return {}; }, getConstraints: function(){ return {}; },
        applyConstraints: function(){ return Promise.resolve(); },
        clone: function(){ return makeTrack(); },
        addEventListener: function(){}, removeEventListener: function(){},
        dispatchEvent: function(){ return true; }
      };
    }
    function NativeStream() {
      this.id = 'cf-stream-' + Math.random().toString(36).slice(2);
      this.active = true; this.__lwa = true;
      this._tracks = [makeTrack()];
    }
    NativeStream.prototype.getTracks      = function(){ return this._tracks.slice(); };
    NativeStream.prototype.getAudioTracks = function(){ return this._tracks.slice(); };
    NativeStream.prototype.getVideoTracks = function(){ return []; };
    NativeStream.prototype.getTrackById = function(id){
      return this._tracks.filter(function(t){return t.id===id;})[0] || null;
    };
    NativeStream.prototype.addTrack=function(){}; NativeStream.prototype.removeTrack=function(){};
    NativeStream.prototype.clone=function(){ return new NativeStream(); };
    NativeStream.prototype.addEventListener=function(){};
    NativeStream.prototype.removeEventListener=function(){};
    NativeStream.prototype.dispatchEvent=function(){ return true; };

    if (navigator.mediaDevices) {
      navigator.mediaDevices.getUserMedia = function(constraints) {
        var wantA = !!(constraints && constraints.audio);
        var wantV = !!(constraints && constraints.video);
        if (wantA && !wantV) return Promise.resolve(new NativeStream());
        if (origGUM) return origGUM(constraints);
        return Promise.reject(new DOMException('getUserMedia unavailable','NotSupportedError'));
      };
      var origEnum = navigator.mediaDevices.enumerateDevices
                     && navigator.mediaDevices.enumerateDevices.bind(navigator.mediaDevices);
      navigator.mediaDevices.enumerateDevices = function() {
        var fake = [{deviceId:'native', groupId:'native', kind:'audioinput',
                     label:'Native Android Microphone'}];
        if (origEnum) return origEnum().then(function(list){
          return fake.concat(list.filter(function(d){ return d.kind !== 'audioinput'; }));
        }).catch(function(){ return fake; });
        return Promise.resolve(fake);
      };
    }

    function NativeMR(stream, options) {
      this._handlers = {};
      this._native = !!(stream && stream.__lwa);
      if (!this._native && OrigMR) {
        var self = this;
        this._real = new OrigMR(stream, options);
        ['dataavailable','start','stop','pause','resume','error'].forEach(function(ev){
          self._real.addEventListener(ev, function(e){ self._fire(ev, e); });
        });
        Object.defineProperty(this, 'state',    { get:function(){ return self._real.state; }});
        Object.defineProperty(this, 'mimeType', { get:function(){ return self._real.mimeType; }});
        this.stream = stream; return;
      }
      this._state='inactive'; this.stream=stream; this.mimeType='audio/wav';
      this.audioBitsPerSecond=705600; this.videoBitsPerSecond=0;
      var self = this;
      Object.defineProperty(this, 'state', { get:function(){ return self._state; }});
    }
    NativeMR.prototype._fire = function(ev, detail) {
      var handlers = (this._handlers[ev] || []).slice();
      var evt; try { evt = new Event(ev); } catch(e) { evt = {type:ev}; }
      if (detail) for (var k in detail) try { evt[k] = detail[k]; } catch(_) {}
      handlers.forEach(function(h){ try { h(evt); } catch(e) { console.error(e); } });
      var cb = this['on'+ev];
      if (typeof cb === 'function') try { cb(evt); } catch(e) { console.error(e); }
    };
    NativeMR.prototype.addEventListener = function(ev, fn) {
      (this._handlers[ev] = this._handlers[ev] || []).push(fn);
    };
    NativeMR.prototype.removeEventListener = function(ev, fn) {
      if (!this._handlers[ev]) return;
      this._handlers[ev] = this._handlers[ev].filter(function(x){ return x !== fn; });
    };
    NativeMR.prototype.dispatchEvent = function(){ return true; };
    NativeMR.prototype.start = function(timeslice) {
      if (!this._native) return this._real.start(timeslice);
      if (this._state !== 'inactive') return;
      var r = Android.recStart();
      if (r !== 'ok') {
        var err = new Error(r); err.name = 'NotReadableError';
        this._fire('error', { error:err }); return;
      }
      this._state = 'recording'; this._fire('start');
    };
    NativeMR.prototype.stop = function() {
      if (!this._native) return this._real.stop();
      if (this._state === 'inactive') return;
      var dataUrl = Android.recStop();
      this._state = 'inactive';
      if (typeof dataUrl === 'string' && dataUrl.indexOf('data:') === 0) {
        var b64 = dataUrl.split(',')[1] || '';
        var bin = atob(b64);
        var buf = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
        var blob = new Blob([buf], { type:'audio/wav' });
        this._fire('dataavailable', { data:blob });
        this._fire('stop');
      } else {
        var e = new Error(dataUrl || 'recStop failed'); e.name = 'AbortError';
        this._fire('error', { error:e }); this._fire('stop');
      }
    };
    NativeMR.prototype.pause = function() {
      if (!this._native) return this._real.pause();
      if (this._state !== 'recording') return;
      if (Android.recPause() === 'ok') { this._state='paused'; this._fire('pause'); }
    };
    NativeMR.prototype.resume = function() {
      if (!this._native) return this._real.resume();
      if (this._state !== 'paused') return;
      if (Android.recResume() === 'ok') { this._state='recording'; this._fire('resume'); }
    };
    NativeMR.prototype.requestData = function() {
      if (!this._native && this._real) this._real.requestData();
    };
    NativeMR.isTypeSupported = function(type) {
      if (!type) return false;
      if (/audio\/wav/i.test(type) || /audio\/x-wav/i.test(type)) return true;
      return OrigMR ? OrigMR.isTypeSupported(type) : false;
    };
    window.MediaRecorder = NativeMR;

    if (OrigAC) {
      var origCreateSrc = OrigAC.prototype.createMediaStreamSource;
      var origCreateAna = OrigAC.prototype.createAnalyser;
      OrigAC.prototype.createMediaStreamSource = function(stream) {
        if (!stream || !stream.__lwa) return origCreateSrc.call(this, stream);
        var src = this.createGain(); src.gain.value = 0; src.__lwa = true;
        var origConnect = src.connect.bind(src);
        src.connect = function(dest) {
          if (dest && dest.__lwa_analyser) dest.__lwa_src = src;
          return origConnect(dest);
        };
        return src;
      };
      OrigAC.prototype.createAnalyser = function() {
        var analyser = origCreateAna.call(this);
        analyser.__lwa_analyser = true;
        var origGetByteTD  = analyser.getByteTimeDomainData.bind(analyser);
        var origGetFloatTD = analyser.getFloatTimeDomainData ? analyser.getFloatTimeDomainData.bind(analyser) : null;
        var origGetByteFD  = analyser.getByteFrequencyData.bind(analyser);
        analyser.getByteTimeDomainData = function(arr) {
          if (!this.__lwa_src) return origGetByteTD(arr);
          fillByteTimeDomain(arr);
        };
        if (origGetFloatTD) analyser.getFloatTimeDomainData = function(arr) {
          if (!this.__lwa_src) return origGetFloatTD(arr);
          fillFloatTimeDomain(arr);
        };
        analyser.getByteFrequencyData = function(arr) {
          if (!this.__lwa_src) return origGetByteFD(arr);
          fillByteFrequency(arr);
        };
        return analyser;
      };
    }
    function getWaveform(n) {
      try { var s = Android.recWaveform(n); return s ? s.split(',').map(parseFloat) : null; }
      catch(e) { return null; }
    }
    function fillByteTimeDomain(arr) {
      var wave = getWaveform(Math.min(arr.length, 256));
      if (!wave) { for (var i = 0; i < arr.length; i++) arr[i] = 128; return; }
      for (var i = 0; i < arr.length; i++) {
        var t = (i / arr.length) * (wave.length - 1);
        var idx = Math.floor(t), frac = t - idx;
        var v = wave[idx] * (1-frac) + (wave[idx+1] || wave[idx]) * frac;
        var phase = (i / arr.length) * Math.PI * 2 * 8;
        arr[i] = Math.max(0, Math.min(255, 128 + Math.sin(phase) * v * 120));
      }
    }
    function fillFloatTimeDomain(arr) {
      var wave = getWaveform(Math.min(arr.length, 256));
      if (!wave) { for (var i = 0; i < arr.length; i++) arr[i] = 0; return; }
      for (var i = 0; i < arr.length; i++) {
        var t = (i / arr.length) * (wave.length - 1);
        var idx = Math.floor(t), frac = t - idx;
        var v = wave[idx] * (1-frac) + (wave[idx+1] || wave[idx]) * frac;
        var phase = (i / arr.length) * Math.PI * 2 * 8;
        arr[i] = Math.sin(phase) * v;
      }
    }
    function fillByteFrequency(arr) {
      var amp = Android.recAmplitude() / 32767.0;
      for (var i = 0; i < arr.length; i++) {
        var w = Math.pow(1 - (i / arr.length), 1.5);
        arr[i] = Math.min(255, amp * w * (Math.random() * 0.5 + 0.5) * 255);
      }
    }
  }

  var FSB = window.AndroidFS_native;
  if (FSB && typeof FSB.read === 'function') {
    features.push('fs');

    window.AndroidFS = {
      read: function(path) {
        return new Promise(function(res){
          try { res(FSB.read(String(path))); } catch(e) { res(null); }
        });
      },
      write: function(path, content) {
        return new Promise(function(res){
          try {
            var s = (typeof content === 'string') ? content : JSON.stringify(content);
            res(FSB.write(String(path), s) === 'ok');
          } catch(e) { res(false); }
        });
      },
      readBytes: function(path) {
        return new Promise(function(res){
          try {
            var b64 = FSB.readBase64(String(path));
            if (!b64) return res(null);
            var bin = atob(b64);
            var arr = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
            res(arr);
          } catch(e) { res(null); }
        });
      },
      writeBytes: function(path, uint8array) {
        return new Promise(function(res){
          try {
            var chunks = [];
            for (var i = 0; i < uint8array.length; i += 0x8000) {
              chunks.push(String.fromCharCode.apply(null, uint8array.subarray(i, i + 0x8000)));
            }
            var b64 = btoa(chunks.join(''));
            res(FSB.writeBase64(String(path), b64) === 'ok');
          } catch(e) { res(false); }
        });
      },
      delete: function(path) {
        return new Promise(function(res){
          try { res(FSB.deleteFile(String(path)) === 'ok'); } catch(e) { res(false); }
        });
      },
      list: function(path) {
        return new Promise(function(res){
          try {
            var s = FSB.list(path ? String(path) : '');
            res(s ? s.split('\n').filter(function(x){ return x.length > 0; }) : []);
          } catch(e) { res([]); }
        });
      },
      exists: function(path) {
        return new Promise(function(res){
          try { res(!!FSB.exists(String(path))); } catch(e) { res(false); }
        });
      },
      mkdir: function(path) {
        return new Promise(function(res){
          try { res(FSB.mkdir(String(path)) === 'ok'); } catch(e) { res(false); }
        });
      },
      stat: function(path) {
        return new Promise(function(res){
          try {
            var s = FSB.stat(String(path));
            if (!s) return res(null);
            var parts = s.split('|');
            res({
              size:  parseInt(parts[0], 10) || 0,
              mtime: parseInt(parts[1], 10) || 0,
              isDir: parts[2] === 'true'
            });
          } catch(e) { res(null); }
        });
      }
    };

    var origFetch = window.fetch ? window.fetch.bind(window) : null;
    window.fetch = function(input, init) {
      var url = (typeof input === 'string') ? input : (input && input.url);
      var method = (init && init.method ? init.method : 'GET').toUpperCase();
      if (url && method !== 'GET' && method !== 'HEAD') {
        var u; try { u = new URL(url, location.href); } catch(e) { u = null; }
        if (u && u.origin === location.origin) {
          var path = decodeURIComponent(u.pathname.replace(/^\//, ''));
          if (method === 'PUT' || method === 'POST') {
            var body = init && init.body;
            return new Promise(function(resolve){
              var done = function(ok) {
                resolve(new Response(ok ? 'ok' : 'fail', {
                  status: ok ? 200 : 500,
                  headers: { 'Content-Type': 'text/plain' }
                }));
              };
              if (body instanceof Blob) {
                body.arrayBuffer().then(function(ab){
                  window.AndroidFS.writeBytes(path, new Uint8Array(ab)).then(done);
                });
              } else if (body instanceof ArrayBuffer) {
                window.AndroidFS.writeBytes(path, new Uint8Array(body)).then(done);
              } else if (typeof body === 'string') {
                window.AndroidFS.write(path, body).then(done);
              } else if (body == null) {
                window.AndroidFS.write(path, '').then(done);
              } else {
                try { window.AndroidFS.write(path, JSON.stringify(body)).then(done); }
                catch(e) { done(false); }
              }
            });
          }
          if (method === 'DELETE') {
            return window.AndroidFS.delete(path).then(function(ok){
              return new Response(ok ? 'ok' : 'fail', { status: ok ? 200 : 500 });
            });
          }
        }
      }
      return origFetch ? origFetch(input, init)
                       : Promise.reject(new Error('fetch unavailable'));
    };
  }

  if (typeof Android !== 'undefined') {
    features.push('share', 'vibrate', 'toast', 'debug');
    if (Android.shareFiles) features.push('share-files');
  }

  readyResolve({
    features: features.slice(),
    version:  'v5',
    isNative: lwaObj.isNative
  });
  console.log('[CouchFlow] shim v5 ready - features:', features.join(','));
})();
"""
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var debugButton: View
    private lateinit var debugTopZone: View

    private var server: SimpleServer? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var folderUri: Uri
    private lateinit var appTitle: String

    private val nativeRecorder = NativeAudioRecorder()
    private var fsBridge: JsFilesystemBridge? = null

    // Triple-tap detector for the top edge — opens the debug overlay even when
    // the persistent debug button is hidden.
    private val tripleTapTimes = LongArray(3)
    private var tripleTapIdx = 0
    private val TRIPLE_TAP_WINDOW_MS = 800L

    private val startupPermsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Log permission outcomes so the user can show their AI assistant
            // when something doesn't work due to denied permissions.
            for ((perm, granted) in results) {
                DebugLog.perm(perm.substringAfterLast('.').lowercase(), granted)
            }
            initializeWebApp()
        }

    private val runtimePermsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            for ((perm, granted) in results) {
                DebugLog.perm(perm.substringAfterLast('.').lowercase(), granted)
            }
            val allGranted = results.values.all { it }
            val pending    = pendingPermissionRequest
            if (pending != null) {
                if (allGranted) pending.grant(pending.resources) else pending.deny()
            }
            pendingPermissionRequest = null
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            filePathCallback?.onReceiveValue(uris.toTypedArray())
            filePathCallback = null
        }

    private val imageCaptureUri by lazy {
        androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.fileprovider",
            File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        )
    }

    private val imageCaptureCallback by lazy {
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) filePathCallback?.onReceiveValue(arrayOf(imageCaptureUri))
            else         filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private val netCacheDir by lazy { File(filesDir, NET_CACHE).also { it.mkdirs() } }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // System bars keep their own space. Web apps render only in the safe
        // area between status bar and gesture bar — `top: 0` and `bottom: 24px`
        // refer to visible coordinates, no overlap or clipping. We still tint
        // both bars to the web app's theme color (see syncStatusBarToWebView)
        // so the screen looks unified.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced     = false
            window.isNavigationBarContrastEnforced = false
        }

        supportActionBar?.hide()
        setContentView(R.layout.activity_webapp)

        val uriStr = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        folderUri  = Uri.parse(uriStr)
        appTitle   = intent.getStringExtra(EXTRA_TITLE) ?: "Web App"

        webView      = findViewById(R.id.webView)
        progressBar  = findViewById(R.id.progressBar)
        errorView    = findViewById(R.id.errorView)
        errorText    = findViewById(R.id.errorText)
        debugButton  = findViewById(R.id.debugButton)
        debugTopZone = findViewById(R.id.debugTopZone)
        progressBar.visibility = View.VISIBLE

        // Initialize debug log session metadata
        DebugLog.couchFlowVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        DebugLog.webViewVersion = try {
            WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        DebugLog.startSession(appTitle, uriStr)

        // Persistent debug button — hidden by default. Long-press the FAB on
        // the home screen to enable.
        val prefs = getSharedPreferences("CouchFlow", MODE_PRIVATE)
        val debugBtnVisible = prefs.getBoolean("debug_button_visible", false)
        debugButton.visibility = if (debugBtnVisible) View.VISIBLE else View.GONE
        debugButton.setOnClickListener { openDebugOverlay() }

        // Top-edge invisible zone for triple-tap. Layout puts it ~36dp tall
        // across the top, with no background — taps that don't land here
        // pass through to the WebView normally.
        debugTopZone.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                tripleTapTimes[tripleTapIdx] = System.currentTimeMillis()
                tripleTapIdx = (tripleTapIdx + 1) % tripleTapTimes.size
                val now    = System.currentTimeMillis()
                val oldest = tripleTapTimes.min()
                if (oldest > 0 && (now - oldest) < TRIPLE_TAP_WINDOW_MS) {
                    // Reset so we don't immediately fire again
                    for (i in tripleTapTimes.indices) tripleTapTimes[i] = 0
                    openDebugOverlay()
                    return@setOnTouchListener true
                }
            }
            false
        }

        val neededPerms = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
            neededPerms.add(android.Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
            neededPerms.add(android.Manifest.permission.CAMERA)

        if (neededPerms.isEmpty()) initializeWebApp()
        else startupPermsLauncher.launch(neededPerms.toTypedArray())
    }

    private fun openDebugOverlay() {
        DebugLog.lifecycle("debug overlay opened")
        startActivity(Intent(this, DebugOverlayActivity::class.java))
    }

    private fun initializeWebApp() {
        fsBridge = JsFilesystemBridge(this, contentResolver, folderUri)
        val s = SimpleServer(contentResolver, folderUri, filesDir, LWA_SHIM_JS)
        server = s; s.start()
        setupWebView()
        webView.loadUrl("http://localhost:${s.port}/")
        DebugLog.lifecycle("webview loaded http://localhost:${s.port}/")
    }

    private fun syncStatusBarToWebView() {
        val js = """
            (function(){
              try {
                var meta = document.querySelector('meta[name="theme-color"]');
                if (meta && meta.content) return meta.content.trim();
                var bg = getComputedStyle(document.body).backgroundColor;
                if (bg && bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent') return bg;
                var htmlBg = getComputedStyle(document.documentElement).backgroundColor;
                if (htmlBg && htmlBg !== 'rgba(0, 0, 0, 0)' && htmlBg !== 'transparent') return htmlBg;
                return '#000000';
              } catch(e) { return '#000000'; }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            val colorStr = raw?.trim()?.removeSurrounding("\"") ?: "#000000"
            val parsed = parseCssColor(colorStr) ?: return@evaluateJavascript
            runOnUiThread { applyBarColor(parsed) }
        }
    }

    private fun parseCssColor(css: String): Int? {
        val s = css.trim()
        try {
            if (s.startsWith("#")) return android.graphics.Color.parseColor(s)
            if (s.startsWith("rgb")) {
                val inside = s.substringAfter('(').substringBefore(')')
                val parts  = inside.split(',').map { it.trim() }
                if (parts.size >= 3) {
                    val r = parts[0].toFloat().toInt().coerceIn(0, 255)
                    val g = parts[1].toFloat().toInt().coerceIn(0, 255)
                    val b = parts[2].toFloat().toInt().coerceIn(0, 255)
                    return android.graphics.Color.rgb(r, g, b)
                }
            }
            return android.graphics.Color.parseColor(s)
        } catch (e: Exception) { return null }
    }

    private fun applyBarColor(color: Int) {
        window.statusBarColor     = color
        window.navigationBarColor = color
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        val useDarkIcons = luminance > 0.5
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars     = useDarkIcons
        controller.isAppearanceLightNavigationBars = useDarkIcons
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
        webView.setBackgroundColor(android.graphics.Color.BLACK)

        // Single Android bridge object. Each method logs to DebugLog so the
        // user can see exactly which calls happened in what order.
        webView.addJavascriptInterface(object : Any() {
            @JavascriptInterface fun toast(msg: String): Unit = runOnUiThread {
                DebugLog.bridge("Android.toast", "\"${truncate(msg, 80)}\"", "ok")
                Toast.makeText(this@WebAppActivity, msg, Toast.LENGTH_SHORT).show()
            }
            @JavascriptInterface fun log(msg: String) {
                DebugLog.bridge("Android.log", "\"${truncate(msg, 80)}\"", "ok")
                android.util.Log.d("CouchFlow", msg)
            }
            @JavascriptInterface fun isNativeApp(): Boolean {
                DebugLog.bridge("Android.isNativeApp", "", "true")
                return true
            }
            @JavascriptInterface fun getPlatform(): String {
                DebugLog.bridge("Android.getPlatform", "", "android")
                return "android"
            }
            @JavascriptInterface fun getAppVersion(): String {
                val v = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
                DebugLog.bridge("Android.getAppVersion", "", v)
                return v
            }
            @JavascriptInterface fun share(text: String): Unit = runOnUiThread {
                DebugLog.bridge("Android.share", "\"${truncate(text, 60)}\"", "intent")
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                    }, "Share"))
            }

            /**
             * Open the in-app debug log overlay. Web apps that want to expose
             * their own "Show debug log" button (e.g. in a settings panel)
             * call this so users don't need to know the persistent-button
             * toggle gesture.
             */
            @JavascriptInterface fun openDebugLog(): Unit = runOnUiThread {
                DebugLog.bridge("Android.openDebugLog", "", "ok")
                openDebugOverlay()
            }

            /**
             * Share files from the web app via the system share sheet.
             *
             * Receives a JSON-stringified array of {name, mimeType, base64}.
             * Writes each entry to a temp file under cacheDir/share-tmp/,
             * then fires ACTION_SEND_MULTIPLE with FileProvider content URIs.
             *
             * The shim patches navigator.share() to call this when the web
             * app passes {files: [Blob]}, so most web apps will use the
             * standard Web Share API and never see this method directly.
             *
             * Cap: each base64 payload is ~10MB max (about 7.5MB binary).
             * Larger payloads should be split or written to disk first.
             */
            @JavascriptInterface fun shareFiles(payloadJson: String): String {
                return try {
                    val arr = org.json.JSONArray(payloadJson)
                    if (arr.length() == 0) return "error:no files"

                    val tmpDir = File(cacheDir, "share-tmp").also { d ->
                        d.mkdirs()
                        d.listFiles()?.forEach { it.delete() }
                    }
                    val uris = ArrayList<Uri>()
                    var sharedMime: String? = null
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val name = (o.optString("name").ifEmpty { "file-$i" })
                            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            .take(80)
                        val mimeType = o.optString("mimeType")
                            .ifEmpty { "application/octet-stream" }
                        val b64 = o.optString("base64")
                        if (b64.isEmpty()) continue
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val outFile = File(tmpDir, "${System.currentTimeMillis()}-$i-$name")
                        outFile.outputStream().use { it.write(bytes) }
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this@WebAppActivity,
                            "${packageName}.fileprovider",
                            outFile
                        )
                        uris.add(uri)
                        if (sharedMime == null) sharedMime = mimeType
                        else if (sharedMime != mimeType) sharedMime = "*/*"
                    }

                    if (uris.isEmpty()) return "error:no valid files"

                    runOnUiThread {
                        val intent = if (uris.size == 1) {
                            Intent(Intent.ACTION_SEND).apply {
                                type = sharedMime ?: "*/*"
                                putExtra(Intent.EXTRA_STREAM, uris[0])
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        } else {
                            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = sharedMime ?: "*/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        }
                        startActivity(Intent.createChooser(intent, "Share"))
                    }
                    DebugLog.bridge("Android.shareFiles",
                        "${uris.size} file(s)", "intent")
                    "ok"
                } catch (e: Exception) {
                    DebugLog.bridge("Android.shareFiles", "json", "error:${e.message}")
                    "error:${e.message}"
                }
            }

            @JavascriptInterface fun vibrate(): Unit = runOnUiThread {
                DebugLog.bridge("Android.vibrate", "", "ok")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(100)
            }
            @JavascriptInterface fun getFreeMB(): String {
                val mb = try {
                    val stat = android.os.StatFs(filesDir.path)
                    (stat.availableBlocksLong * stat.blockSizeLong / 1048576).toString()
                } catch (e: Exception) { "0" }
                DebugLog.bridge("Android.getFreeMB", "", mb)
                return mb
            }

            @JavascriptInterface fun recStart():  String  {
                val r = nativeRecorder.start()
                DebugLog.bridge("Android.recStart", "", r); return r
            }
            @JavascriptInterface fun recStop():   String  {
                val r = nativeRecorder.stop()
                DebugLog.bridge("Android.recStop", "",
                    if (r.startsWith("data:")) "data:audio/wav (${r.length}ch)" else r)
                return r
            }
            @JavascriptInterface fun recPause():  String  {
                val r = nativeRecorder.pauseRecording()
                DebugLog.bridge("Android.recPause", "", r); return r
            }
            @JavascriptInterface fun recResume(): String  {
                val r = nativeRecorder.resumeRecording()
                DebugLog.bridge("Android.recResume", "", r); return r
            }
            @JavascriptInterface fun recAmplitude(): Int  = nativeRecorder.getAmplitude()
            @JavascriptInterface fun recWaveform(count: Int): String = nativeRecorder.getWaveform(count)
            @JavascriptInterface fun recIsActive(): Boolean = nativeRecorder.isActive()
            @JavascriptInterface fun recIsPaused(): Boolean = nativeRecorder.isPausedState()

            @JavascriptInterface fun setBarColor(cssColor: String) {
                DebugLog.bridge("Android.setBarColor", "\"$cssColor\"", "ok")
                val parsed = parseCssColor(cssColor) ?: return
                runOnUiThread { applyBarColor(parsed) }
            }

            // Debug log push API. Web apps call CouchFlow.debug(label, payload)
            // and that shim function calls this method. payloadJson is already
            // a JSON-stringified value (or empty string if no payload).
            @JavascriptInterface fun debugUserEvent(label: String, payloadJson: String) {
                DebugLog.user(label, truncate(payloadJson, 200))
            }
            // Surfaces uncaught errors and unhandled rejections from the page.
            @JavascriptInterface fun debugError(msg: String) {
                DebugLog.error(truncate(msg, 1000))
            }
        }, "Android")

        fsBridge?.let { webView.addJavascriptInterface(it, "AndroidFS_native") }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                errorView.visibility   = View.GONE
                DebugLog.lifecycle("page started: ${truncate(url, 120)}")
            }
            override fun onPageFinished(v: WebView, url: String) {
                progressBar.visibility = View.GONE
                DebugLog.lifecycle("page finished: ${truncate(url, 120)}")
                syncStatusBarToWebView()
            }
            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) {
                    progressBar.visibility = View.GONE
                    errorView.visibility   = View.VISIBLE
                    errorText.text         = "Error: ${err.description}"
                    DebugLog.error("page error: ${err.errorCode} ${err.description}")
                }
            }
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.startsWith("http://localhost")) return false
                if (url.startsWith("mailto:") || url.startsWith("tel:")) {
                    startActivity(Intent(Intent.ACTION_VIEW, req.url)); return true
                }
                startActivity(Intent(Intent.ACTION_VIEW, req.url)); return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url    = request.url.toString()
                val method = request.method ?: "GET"
                if (method != "GET")   return null
                if (!shouldCache(url)) return null
                val cacheFile = urlToCacheFile(url)
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    DebugLog.fetch("CACHE", url, 200, 0)
                    return streamFromDisk(cacheFile, url)
                }
                val started = System.currentTimeMillis()
                return try {
                    val conn = openConnection(url, request)
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        conn.disconnect()
                        DebugLog.fetch("GET", url, code, System.currentTimeMillis() - started)
                        return null
                    }
                    val contentType = conn.contentType ?: guessMime(url)
                    if (contentType.contains("text/html")) {
                        conn.disconnect(); return null
                    }
                    cacheFile.parentFile?.mkdirs()
                    val tmpFile = File(cacheFile.parent, "${cacheFile.name}.tmp")
                    tmpFile.delete()
                    val buf = ByteArray(512 * 1024)
                    try {
                        FileOutputStream(tmpFile).use { fos ->
                            val net = conn.inputStream
                            var n: Int
                            while (net.read(buf).also { n = it } != -1) fos.write(buf, 0, n)
                        }
                        tmpFile.renameTo(cacheFile)
                    } catch (e: Exception) {
                        tmpFile.delete(); conn.disconnect()
                        DebugLog.fetch("GET", url, -1, System.currentTimeMillis() - started)
                        return null
                    } finally { conn.disconnect() }
                    DebugLog.fetch("GET", url, code, System.currentTimeMillis() - started)
                    if (cacheFile.exists() && cacheFile.length() > 0) streamFromDisk(cacheFile, url) else null
                } catch (e: Exception) {
                    DebugLog.fetch("GET", url, -1, System.currentTimeMillis() - started)
                    null
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    detail.didCrash() else true
                val msg = if (didCrash) "WebView render process crashed (OOM or fatal)"
                          else            "WebView render process killed by system"
                DebugLog.error(msg)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    errorView.visibility   = View.VISIBLE
                    errorText.text         = "$msg.\n\nOpen the debug log for details."
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                progressBar.progress = p
                if (p == 100) progressBar.visibility = View.GONE
            }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val level = m.messageLevel().name
                val msg   = "${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})"
                DebugLog.console(level, msg)
                return true
            }
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@WebAppActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@WebAppActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onJsPrompt(view: WebView, url: String, message: String,
                                     defaultValue: String?, result: JsPromptResult): Boolean {
                val input = android.widget.EditText(this@WebAppActivity)
                input.setText(defaultValue ?: "")
                AlertDialog.Builder(this@WebAppActivity).setMessage(message).setView(input)
                    .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val perms = mutableListOf<String>()
                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        perms.add(android.Manifest.permission.CAMERA)
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        perms.add(android.Manifest.permission.RECORD_AUDIO)
                    if (perms.isEmpty()) { request.grant(request.resources); return@runOnUiThread }
                    val allGranted = perms.all {
                        checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (allGranted) request.grant(request.resources)
                    else {
                        pendingPermissionRequest = request
                        runtimePermsLauncher.launch(perms.toTypedArray())
                    }
                }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
                if (checkSelfPermission(fine) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    DebugLog.perm("location", true)
                    callback.invoke(origin, true, false)
                } else {
                    AlertDialog.Builder(this@WebAppActivity)
                        .setTitle("Location Access")
                        .setPositiveButton("Allow") { _, _ ->
                            runtimePermsLauncher.launch(arrayOf(fine,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION))
                            callback.invoke(origin, true, false)
                        }
                        .setNegativeButton("Deny") { _, _ ->
                            DebugLog.perm("location", false)
                            callback.invoke(origin, false, false)
                        }.show()
                }
            }
            override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>,
                                            params: FileChooserParams): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val acceptTypes = params.acceptTypes.joinToString(",")
                val isImage = acceptTypes.contains("image")
                if (isImage && params.isCaptureEnabled) {
                    try { imageCaptureCallback.launch(imageCaptureUri); return true }
                    catch (e: Exception) {}
                }
                if (isImage) {
                    AlertDialog.Builder(this@WebAppActivity).setTitle("Get Image")
                        .setItems(arrayOf("Take Photo", "Choose from Gallery")) { _, which ->
                            if (which == 0) {
                                try { imageCaptureCallback.launch(imageCaptureUri) }
                                catch (e: Exception) {
                                    filePathCallback?.onReceiveValue(null); filePathCallback = null
                                }
                            } else fileChooserLauncher.launch("image/*")
                        }.setOnCancelListener {
                            filePathCallback?.onReceiveValue(null); filePathCallback = null
                        }.show()
                } else {
                    fileChooserLauncher.launch(
                        if (acceptTypes.isNotEmpty() && acceptTypes != "*/*") acceptTypes else "*/*")
                }
                return true
            }
            private var customView: View? = null
            private var customViewCallback: CustomViewCallback? = null
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view; customViewCallback = callback
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                (window.decorView as android.widget.FrameLayout)
                    .addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
            override fun onHideCustomView() {
                (window.decorView as android.widget.FrameLayout).removeView(customView)
                customView = null; customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else "${s.take(max - 1)}…"

    private fun streamFromDisk(file: File, url: String): WebResourceResponse {
        return WebResourceResponse(
            guessMime(url), null, 200, "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Content-Length"              to file.length().toString(),
                "Cache-Control"               to "no-cache"
            ),
            FileInputStream(file)
        )
    }

    private fun openConnection(url: String, request: WebResourceRequest): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod           = "GET"
            instanceFollowRedirects = true
            connectTimeout          = 30_000
            readTimeout             = 120_000
            request.requestHeaders?.forEach { (k, v) ->
                if (!k.equals("Range", ignoreCase = true)) setRequestProperty(k, v)
            }
            connect()
        }
    }

    private fun shouldCache(url: String): Boolean {
        val lower = url.lowercase()
        val cacheExts = listOf(".onnx", ".wasm", ".bin", ".ot", ".gguf",
            ".safetensors", ".pt", ".pth", ".tflite")
        if (cacheExts.any { lower.contains(it) }) return true
        val cacheDomains = listOf("huggingface.co", "cdn-lfs", "jsdelivr.net",
            "esm.run", "cdn.jsdelivr.net")
        if (cacheDomains.any { lower.contains(it) }) {
            if (lower.endsWith(".html")) return false
            return true
        }
        return false
    }

    private fun urlToCacheFile(url: String): File {
        val uri     = Uri.parse(url)
        val lastSeg = uri.lastPathSegment?.replace(Regex("[^a-zA-Z0-9._-]"), "_") ?: "file"
        val hash    = url.hashCode().toLong() and 0xFFFFFFFFL
        val name    = "${hash}_${lastSeg}".take(120)
        val host    = uri.host?.replace(".", "_")?.take(30) ?: "misc"
        return File(netCacheDir, "$host/$name")
    }

    private fun guessMime(url: String): String {
        val lower = url.lowercase().substringBefore("?")
        return when {
            lower.endsWith(".onnx")        -> "application/octet-stream"
            lower.endsWith(".wasm")        -> "application/wasm"
            lower.endsWith(".json")        -> "application/json"
            lower.endsWith(".js")          -> "application/javascript"
            lower.endsWith(".mjs")         -> "application/javascript"
            lower.endsWith(".bin")         -> "application/octet-stream"
            lower.endsWith(".safetensors") -> "application/octet-stream"
            else                           -> "application/octet-stream"
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onDestroy() {
        super.onDestroy()
        if (nativeRecorder.isActive()) nativeRecorder.stop()
        server?.stop()
        webView.destroy()
        DebugLog.lifecycle("activity destroyed")
    }
    override fun onPause()  {
        super.onPause();  webView.onPause();  DebugLog.lifecycle("paused")
    }
    override fun onResume() {
        super.onResume(); webView.onResume(); DebugLog.lifecycle("resumed")
        // Refresh debug button visibility in case the user toggled it.
        val prefs = getSharedPreferences("CouchFlow", MODE_PRIVATE)
        val debugBtnVisible = prefs.getBoolean("debug_button_visible", false)
        debugButton.visibility = if (debugBtnVisible) View.VISIBLE else View.GONE
    }
    override fun onLowMemory() {
        super.onLowMemory(); DebugLog.lifecycle("low memory warning")
    }
}


// ═══════════════════════════════════════════════════════════════════════════════
// SimpleServer — HTTP server with stable port, HTML shim injection, and Range
// support so <audio> / <video> elements can scrub through media files.
// ═══════════════════════════════════════════════════════════════════════════════
class SimpleServer(
    private val resolver: ContentResolver,
    private val rootUri: Uri,
    private val filesDir: File,
    private val shimJs: String
) {
    private var serverSocket: ServerSocket? = null
    var port = 0
        private set
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var active = false

    private val injectedHead: ByteArray by lazy {
        val sb = StringBuilder()
        sb.append("<meta name=\"lwa-features\" content=\"fs,audio,share,vibrate,debug\">\n")
        sb.append("<meta name=\"lwa-version\" content=\"v4\">\n")
        sb.append("<meta name=\"couchflow-version\" content=\"v4\">\n")
        sb.append("<script data-couchflow=\"shim\">")
        sb.append(shimJs)
        sb.append("</script>\n")
        sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun start() {
        val basePort = 39000 + (Math.abs(rootUri.toString().hashCode()) % 1000)
        var bound = false
        for (offset in 0..10) {
            try {
                serverSocket = ServerSocket(basePort + offset)
                port = basePort + offset
                bound = true
                android.util.Log.d("CouchFlow-Server", "Bound stable port $port for $rootUri")
                break
            } catch (e: Exception) { }
        }
        if (!bound) {
            serverSocket = ServerSocket(0).also { port = it.localPort }
            android.util.Log.w("CouchFlow-Server", "Stable port unavailable, using random $port")
        }

        active = true
        executor.submit {
            while (active) {
                try {
                    val client = serverSocket!!.accept()
                    executor.submit { try { handle(client) } finally { client.close() } }
                } catch (e: Exception) { if (active) e.printStackTrace() }
            }
        }
    }

    fun stop() {
        active = false
        try { serverSocket?.close() } catch (e: Exception) {}
        executor.shutdownNow()
    }

    private data class ParsedRequest(
        val method: String,
        val path: String,
        val rangeStart: Long?,
        val rangeEnd: Long?
    )

    private fun parseRequest(reader: java.io.BufferedReader): ParsedRequest? {
        val line = reader.readLine() ?: return null
        val parts = line.trim().split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val path = URLDecoder.decode(parts[1].substringBefore("?"), "UTF-8")

        var rangeHeader: String? = null
        do {
            val h = reader.readLine() ?: break
            if (h.isBlank()) break
            if (h.startsWith("Range:", ignoreCase = true)) {
                rangeHeader = h.substringAfter(":").trim()
            }
        } while (true)

        var start: Long? = null
        var end: Long? = null
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").trim()
            // Only handle the single "start-end" or "start-" form (most common
            // case from <audio>/<video> scrubbing). Multi-range is rare.
            val dash = spec.indexOf('-')
            if (dash > 0) {
                start = spec.substring(0, dash).toLongOrNull()
                end   = spec.substring(dash + 1).toLongOrNull()
            } else if (dash == 0) {
                // suffix form "-N" — last N bytes; skip
            }
        }
        return ParsedRequest(method, path, start, end)
    }

    private fun handle(s: java.net.Socket) {
        val reader = s.inputStream.bufferedReader(Charsets.ISO_8859_1)
        val out    = s.outputStream
        val req = parseRequest(reader) ?: return

        var path = req.path
        if (path == "/" || path.isEmpty()) path = "/index.html"
        val segments = path.trimStart('/').split("/").filter { it.isNotEmpty() && it != ".." }
        val file = resolve(segments)
        when {
            file == null || !file.exists() -> {
                val idx = resolve(listOf("index.html"))
                if (idx != null && idx.exists()) serveDocFile(out, idx, req)
                else send404(out)
            }
            file.isDirectory -> {
                val idx = file.findFile("index.html")
                if (idx != null) serveDocFile(out, idx, req) else send404(out)
            }
            else -> serveDocFile(out, file, req)
        }
    }

    private fun resolve(segments: List<String>): DocumentFile? {
        val ctx = try {
            val f = ContentResolver::class.java.getDeclaredField("mContext")
            f.isAccessible = true
            f.get(resolver) as Context
        } catch (e: Exception) { return null }
        var node = DocumentFile.fromTreeUri(ctx, rootUri) ?: return null
        for (seg in segments) { node = node.findFile(seg) ?: return null }
        return node
    }

    /**
     * Serve a file. HTML responses get the shim injected. Non-HTML responses
     * support Range requests so media elements can seek without redownloading.
     */
    private fun serveDocFile(out: OutputStream, file: DocumentFile, req: ParsedRequest) {
        val name = file.name ?: ""
        val mime = getMime(name)
        val totalSize = file.length()

        if (mime.startsWith("text/html")) {
            // HTML: read fully, inject shim, send with Content-Length
            val originalBytes = resolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: run { send404(out); return }
            val bytes = injectShim(originalBytes)
            if (req.method == "HEAD") {
                writeHeader(out, 200, "OK", mime, bytes.size.toLong(), null, null)
                return
            }
            writeHeader(out, 200, "OK", mime, bytes.size.toLong(), null, null)
            out.write(bytes); out.flush()
            return
        }

        // HEAD request: headers only, no body
        if (req.method == "HEAD") {
            writeHeader(out, 200, "OK", mime, totalSize, null, null)
            return
        }

        // Range request: 206 Partial Content with the requested slice
        if (req.rangeStart != null) {
            val start = req.rangeStart.coerceAtLeast(0)
            val end   = (req.rangeEnd ?: (totalSize - 1)).coerceAtMost(totalSize - 1)
            if (start > end || start >= totalSize) {
                writeHeader(out, 416, "Range Not Satisfiable", mime, 0, null, totalSize)
                return
            }
            val length = end - start + 1
            writeHeader(out, 206, "Partial Content", mime, length, start to end, totalSize)
            // Stream the requested slice
            resolver.openInputStream(file.uri)?.use { input ->
                // Skip to start
                var skipped = 0L
                val skipBuf = ByteArray(64 * 1024)
                while (skipped < start) {
                    val toSkip = minOf(skipBuf.size.toLong(), start - skipped)
                    val n = input.read(skipBuf, 0, toSkip.toInt())
                    if (n < 0) break
                    skipped += n
                }
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
                out.flush()
            }
            return
        }

        // Normal full GET: stream the whole file, no buffering in memory
        writeHeader(out, 200, "OK", mime, totalSize, null, null)
        resolver.openInputStream(file.uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n)
            }
            out.flush()
        }
    }

    private fun writeHeader(
        out: OutputStream, code: Int, status: String, mime: String,
        contentLength: Long, range: Pair<Long, Long>?, totalSize: Long?
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Content-Length: ").append(contentLength).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Cache-Control: no-cache\r\n")
        if (range != null && totalSize != null) {
            sb.append("Content-Range: bytes ")
              .append(range.first).append('-').append(range.second)
              .append('/').append(totalSize).append("\r\n")
        }
        if (code == 416 && totalSize != null) {
            sb.append("Content-Range: bytes */").append(totalSize).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun injectShim(html: ByteArray): ByteArray {
        val text = try { String(html, Charsets.UTF_8) }
                   catch (e: Exception) { return html }

        val lower = text.lowercase()

        // Find the earliest of: end of <head ...>, OR first <script (before
        // any </head>). The shim must run before any user script.
        val headIdx     = lower.indexOf("<head")
        val firstScript = lower.indexOf("<script")

        // Where to insert the shim:
        //   1. Inside <head> right after its open tag (preferred)
        //   2. If <script> appears before <head>'s end, inject before that script
        //   3. If no <head>, build a synthetic <head> containing the shim
        if (headIdx >= 0) {
            val tagEnd = text.indexOf('>', headIdx)
            if (tagEnd > 0) {
                // Check whether a <script> appears between the doctype and <head>
                if (firstScript in 0 until headIdx) {
                    // Inject before that script
                    val before = text.substring(0, firstScript).toByteArray(Charsets.UTF_8)
                    val after  = text.substring(firstScript).toByteArray(Charsets.UTF_8)
                    return concat(before, injectedHead, after)
                }
                val before = text.substring(0, tagEnd + 1).toByteArray(Charsets.UTF_8)
                val after  = text.substring(tagEnd + 1).toByteArray(Charsets.UTF_8)
                return concat(before, injectedHead, after)
            }
        }

        val htmlIdx = lower.indexOf("<html")
        if (htmlIdx >= 0) {
            val tagEnd = text.indexOf('>', htmlIdx)
            if (tagEnd > 0) {
                val inject = ("<head>" + String(injectedHead, Charsets.UTF_8) + "</head>")
                    .toByteArray(Charsets.UTF_8)
                val before = text.substring(0, tagEnd + 1).toByteArray(Charsets.UTF_8)
                val after  = text.substring(tagEnd + 1).toByteArray(Charsets.UTF_8)
                return concat(before, inject, after)
            }
        }

        val prefix = ("<head>" + String(injectedHead, Charsets.UTF_8) + "</head>")
            .toByteArray(Charsets.UTF_8)
        return concat(prefix, html)
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val total = arrays.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (a in arrays) {
            System.arraycopy(a, 0, out, offset, a.size); offset += a.size
        }
        return out
    }

    private fun send404(out: OutputStream) {
        val body   = "<h1>404 Not Found</h1>".toByteArray()
        val header = "HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(body); out.flush()
    }

    private fun getMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css"         -> "text/css; charset=utf-8"
        "js", "mjs"   -> "application/javascript; charset=utf-8"
        "json"        -> "application/json"
        "png"         -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif"         -> "image/gif"
        "svg"         -> "image/svg+xml"
        "webp"        -> "image/webp"
        "woff2"       -> "font/woff2"
        "woff"        -> "font/woff"
        "ttf"         -> "font/ttf"
        "mp4"         -> "video/mp4"
        "webm"        -> "video/webm"
        "mp3"         -> "audio/mpeg"
        "wav"         -> "audio/wav"
        "ogg"         -> "audio/ogg"
        "m4a"         -> "audio/mp4"
        "wasm"        -> "application/wasm"
        else          -> "application/octet-stream"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DebugOverlayActivity — full-screen log viewer with filter chips, Clear/Copy/
// Share/Save buttons. Renders the DebugLog ring buffer in reverse-chronological
// order. The "Copy" / "Share" / "Save" buttons output AI-friendly text.
// ═══════════════════════════════════════════════════════════════════════════════
class DebugOverlayActivity : AppCompatActivity() {

    private lateinit var listView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var filterAll: View
    private lateinit var filterErrors: View
    private lateinit var filterConsole: View
    private lateinit var filterBridge: View
    private lateinit var filterFetch: View
    private lateinit var filterFs: View
    private lateinit var btnClear: View
    private lateinit var btnCopy: View
    private lateinit var btnShare: View
    private lateinit var btnSave: View
    private lateinit var btnClose: View

    private var activeFilter: DebugLog.Source? = null
    private var errorsOnly = false

    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor     = Color.parseColor("#0F1626")
        window.navigationBarColor = Color.parseColor("#0F1626")
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        supportActionBar?.hide()
        setContentView(R.layout.activity_debug_overlay)

        listView      = findViewById(R.id.debugList)
        emptyText     = findViewById(R.id.debugEmpty)
        filterAll     = findViewById(R.id.filterAll)
        filterErrors  = findViewById(R.id.filterErrors)
        filterConsole = findViewById(R.id.filterConsole)
        filterBridge  = findViewById(R.id.filterBridge)
        filterFetch   = findViewById(R.id.filterFetch)
        filterFs      = findViewById(R.id.filterFs)
        btnClear      = findViewById(R.id.btnClear)
        btnCopy       = findViewById(R.id.btnCopy)
        btnShare      = findViewById(R.id.btnShare)
        btnSave       = findViewById(R.id.btnSave)
        btnClose      = findViewById(R.id.btnClose)

        listView.layoutManager = LinearLayoutManager(this)
        refreshList()

        filterAll.setOnClickListener     { activeFilter = null; errorsOnly = false; refreshList() }
        filterErrors.setOnClickListener  { activeFilter = null; errorsOnly = true;  refreshList() }
        filterConsole.setOnClickListener { activeFilter = DebugLog.Source.CONSOLE; errorsOnly = false; refreshList() }
        filterBridge.setOnClickListener  { activeFilter = DebugLog.Source.BRIDGE;  errorsOnly = false; refreshList() }
        filterFetch.setOnClickListener   { activeFilter = DebugLog.Source.FETCH;   errorsOnly = false; refreshList() }
        filterFs.setOnClickListener      { activeFilter = DebugLog.Source.FS;      errorsOnly = false; refreshList() }

        btnClear.setOnClickListener {
            DebugLog.clear()
            refreshList()
        }
        btnCopy.setOnClickListener {
            val text = DebugLog.renderForAi()
            copyToClipboard(text)
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        btnShare.setOnClickListener {
            val text = DebugLog.renderForAi()
            copyToClipboard(text)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "CouchFlow debug log")
            }, "Share debug log"))
        }
        btnSave.setOnClickListener { saveLogToDownloads() }
        btnClose.setOnClickListener { finish() }
    }

    override fun onResume() { super.onResume(); refreshList() }

    private fun visibleEntries(): List<DebugLog.Entry> {
        val all = DebugLog.snapshot()
        val filtered = when {
            errorsOnly -> all.filter {
                it.severity == DebugLog.Severity.ERROR ||
                it.source   == DebugLog.Source.ERROR
            }
            activeFilter != null -> all.filter { it.source == activeFilter }
            else -> all
        }
        return filtered.reversed()
    }

    private fun refreshList() {
        val entries = visibleEntries()
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        listView.adapter = EntryAdapter(entries)
    }

    private inner class EntryAdapter(val items: List<DebugLog.Entry>) :
        RecyclerView.Adapter<EntryAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val text: TextView = v.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            // Build a simple TextView programmatically to avoid needing yet
            // another XML file. Monospace, small, generous padding.
            val tv = TextView(parent.context).apply {
                id = android.R.id.text1
                setPadding(24, 12, 24, 12)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#E5E7EB"))
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            val ts  = tsFmt.format(Date(e.tsEpochMs))
            val sev = e.severity.name.padEnd(5)
            val src = e.source.name.padEnd(9)
            val color = when (e.severity) {
                DebugLog.Severity.ERROR -> "#FCA5A5"
                DebugLog.Severity.WARN  -> "#FCD34D"
                DebugLog.Severity.INFO  -> "#A7F3D0"
                DebugLog.Severity.TRACE -> "#9CA3AF"
            }
            val srcColor = when (e.source) {
                DebugLog.Source.BRIDGE    -> "#A78BFA"
                DebugLog.Source.FS        -> "#7DD3FC"
                DebugLog.Source.FETCH     -> "#FDBA74"
                DebugLog.Source.PERM      -> "#F0ABFC"
                DebugLog.Source.USER      -> "#86EFAC"
                DebugLog.Source.LIFECYCLE -> "#9CA3AF"
                DebugLog.Source.AUDIO     -> "#FCD34D"
                else                      -> "#E5E7EB"
            }
            val html = """[$ts] <font color="$color">$sev</font> <font color="$srcColor">$src</font> ${
                android.text.Html.escapeHtml(e.message)
            }""".trimIndent()
            @Suppress("DEPRECATION")
            holder.text.text = android.text.Html.fromHtml(html)
        }

        override fun getItemCount(): Int = items.size
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("CouchFlow log", text))
    }

    /**
     * Save the rendered log to Downloads/CouchFlow/debug-{timestamp}.log via
     * MediaStore on Android Q+, falling back to direct write on older Androids.
     * Visible in the system file manager and shareable from there.
     */
    private fun saveLogToDownloads() {
        val text = DebugLog.renderForAi()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val filename = "couchflow-debug-$ts.log"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/CouchFlow")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw Exception("MediaStore insert failed")
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                Toast.makeText(this, "Saved to Downloads/CouchFlow/$filename",
                    Toast.LENGTH_LONG).show()
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val cfDir = File(downloadsDir, "CouchFlow").also { it.mkdirs() }
                val file = File(cfDir, filename)
                file.writeText(text)
                Toast.makeText(this, "Saved to ${file.absolutePath}",
                    Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
