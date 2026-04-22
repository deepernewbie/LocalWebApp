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
        recyclerView = findViewById(R.id.recyclerView)
        emptyView    = findViewById(R.id.emptyView)

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

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            openFolderLauncher.launch(null)
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
        val iconBitmap = makeLetterIcon(project.name.firstOrNull()?.uppercase() ?: "W")
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
            Color.parseColor("#6366F1"), Color.parseColor("#EC4899"),
            Color.parseColor("#10B981"), Color.parseColor("#F59E0B"),
            Color.parseColor("#EF4444"), Color.parseColor("#8B5CF6"),
            Color.parseColor("#06B6D4"), Color.parseColor("#F97316")
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
// NativeAudioRecorder — uses AudioRecord for PCM capture with full waveform,
// pause/resume support, and WAV encoding. Bypasses broken WebView mic access.
// ═══════════════════════════════════════════════════════════════════════════════
class NativeAudioRecorder {

    companion object {
        private const val SAMPLE_RATE    = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val NUM_CHANNELS     = 1
    }

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var isRecording = false
    @Volatile private var isPaused    = false

    // Raw PCM buffer — all captured samples, interleaved as 16-bit LE
    private val pcmBuffer = ByteArrayOutputStream()

    // Recent amplitude samples for waveform (ring buffer of floats 0.0-1.0)
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

            val bufSize = minBufferSize * 2 // double for safety
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                bufSize
            )

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return "error:AudioRecord not initialized"
            }

            pcmBuffer.reset()
            waveformIdx = 0
            currentAmplitude = 0
            isPaused = false
            isRecording = true

            rec.startRecording()
            recorder = rec

            // Capture thread
            recordThread = thread(start = true, name = "NativeAudioCapture") {
                val readBuf = ShortArray(minBufferSize)
                val byteBuf = ByteArray(minBufferSize * 2)

                while (isRecording) {
                    val samplesRead = try {
                        rec.read(readBuf, 0, readBuf.size)
                    } catch (e: Exception) { -1 }

                    if (samplesRead <= 0) {
                        if (samplesRead < 0) android.util.Log.e("NativeAudio", "read error $samplesRead")
                        continue
                    }

                    if (isPaused) continue  // discard samples while paused

                    // Compute peak amplitude for waveform
                    var peak = 0
                    for (i in 0 until samplesRead) {
                        val abs = Math.abs(readBuf[i].toInt())
                        if (abs > peak) peak = abs
                    }
                    currentAmplitude = peak

                    // Save peak to ring buffer as 0.0-1.0
                    val normalized = peak / 32767.0f
                    synchronized(waveformRing) {
                        waveformRing[waveformIdx] = normalized
                        waveformIdx = (waveformIdx + 1) % waveformRing.size
                    }

                    // Append PCM samples (little-endian 16-bit) to buffer
                    for (i in 0 until samplesRead) {
                        val s = readBuf[i].toInt()
                        byteBuf[i * 2]     = (s and 0xFF).toByte()
                        byteBuf[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    synchronized(pcmBuffer) {
                        pcmBuffer.write(byteBuf, 0, samplesRead * 2)
                    }
                }
            }

            android.util.Log.d("NativeAudio", "Recording started, sample rate $SAMPLE_RATE")
            "ok"
        } catch (e: Exception) {
            android.util.Log.e("NativeAudio", "Start failed", e)
            cleanup()
            "error:${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun pause(): String {
        if (!isRecording) return "error:not recording"
        isPaused = true
        return "ok"
    }

    fun resume(): String {
        if (!isRecording) return "error:not recording"
        isPaused = false
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
            if (pcmBytes.isEmpty()) return "error:empty recording"

            // Wrap PCM in WAV header so browser MediaElement can play it
            val wavBytes = pcmToWav(pcmBytes, SAMPLE_RATE, NUM_CHANNELS, BYTES_PER_SAMPLE * 8)
            val base64   = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            android.util.Log.d("NativeAudio", "Stopped, ${wavBytes.size} bytes WAV")
            pcmBuffer.reset()
            "data:audio/wav;base64,$base64"
        } catch (e: Exception) {
            android.util.Log.e("NativeAudio", "Stop failed", e)
            cleanup()
            "error:${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Return the most-recent waveform samples as a comma-separated string
     * of values in [0.0, 1.0]. Returns exactly `count` values. Most recent
     * sample is last.
     */
    fun getWaveform(count: Int): String {
        val n = count.coerceIn(1, waveformRing.size)
        val out = StringBuilder()
        synchronized(waveformRing) {
            // Read the last `n` samples from the ring buffer, oldest first
            var idx = (waveformIdx - n + waveformRing.size) % waveformRing.size
            for (i in 0 until n) {
                if (i > 0) out.append(',')
                out.append(String.format(Locale.US, "%.3f", waveformRing[idx]))
                idx = (idx + 1) % waveformRing.size
            }
        }
        return out.toString()
    }

    fun getAmplitude(): Int = currentAmplitude
    fun isActive():    Boolean = isRecording
    fun isPausedState(): Boolean = isPaused

    private fun cleanup() {
        isRecording = false
        try { recorder?.stop() } catch (e: Exception) {}
        try { recorder?.release() } catch (e: Exception) {}
        recorder = null
        recordThread = null
        pcmBuffer.reset()
    }

    /**
     * Wrap raw PCM data in a WAV container so it plays in HTML <audio> elements.
     * Standard 44-byte RIFF/WAVE/fmt/data header for uncompressed PCM.
     */
    private fun pcmToWav(
        pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int
    ): ByteArray {
        val pcmLen     = pcm.size
        val totalLen   = pcmLen + 36
        val byteRate   = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(pcmLen + 44)

        // RIFF header
        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        out[4] = (totalLen and 0xFF).toByte()
        out[5] = ((totalLen shr 8)  and 0xFF).toByte()
        out[6] = ((totalLen shr 16) and 0xFF).toByte()
        out[7] = ((totalLen shr 24) and 0xFF).toByte()
        out[8] = 'W'.code.toByte(); out[9]  = 'A'.code.toByte()
        out[10] = 'V'.code.toByte(); out[11] = 'E'.code.toByte()
        // fmt subchunk
        out[12] = 'f'.code.toByte(); out[13] = 'm'.code.toByte()
        out[14] = 't'.code.toByte(); out[15] = ' '.code.toByte()
        out[16] = 16; out[17] = 0; out[18] = 0; out[19] = 0  // subchunk size = 16
        out[20] = 1;  out[21] = 0                             // PCM format
        out[22] = channels.toByte(); out[23] = 0
        out[24] = (sampleRate and 0xFF).toByte()
        out[25] = ((sampleRate shr 8)  and 0xFF).toByte()
        out[26] = ((sampleRate shr 16) and 0xFF).toByte()
        out[27] = ((sampleRate shr 24) and 0xFF).toByte()
        out[28] = (byteRate and 0xFF).toByte()
        out[29] = ((byteRate shr 8)  and 0xFF).toByte()
        out[30] = ((byteRate shr 16) and 0xFF).toByte()
        out[31] = ((byteRate shr 24) and 0xFF).toByte()
        out[32] = blockAlign.toByte(); out[33] = 0
        out[34] = bitsPerSample.toByte(); out[35] = 0
        // data subchunk
        out[36] = 'd'.code.toByte(); out[37] = 'a'.code.toByte()
        out[38] = 't'.code.toByte(); out[39] = 'a'.code.toByte()
        out[40] = (pcmLen and 0xFF).toByte()
        out[41] = ((pcmLen shr 8)  and 0xFF).toByte()
        out[42] = ((pcmLen shr 16) and 0xFF).toByte()
        out[43] = ((pcmLen shr 24) and 0xFF).toByte()
        System.arraycopy(pcm, 0, out, 44, pcmLen)
        return out
    }
}

class WebAppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI   = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
        private const val NET_CACHE = "netcache"

        /**
         * Transparent audio shim injected after every page load.
         *
         * Patches navigator.mediaDevices.getUserMedia() + MediaRecorder +
         * AudioContext.createMediaStreamSource() for audio-only recording so
         * web apps using standard Web APIs get reliable mic access even when
         * the underlying WebView's getUserMedia is broken (Samsung A55,
         * Android 14, OnePlus Nord etc. — "NotReadableError" bug).
         *
         * Under the hood:
         *   - navigator.mediaDevices.getUserMedia({audio:true})
         *       → returns fake MediaStream (flag __lwa = true)
         *   - new MediaRecorder(fakeStream)
         *       → routes start/stop/pause/resume through Android.rec*
         *       → fires standard dataavailable/start/stop/pause/resume events
         *         with a real Blob (WAV format)
         *   - audioCtx.createMediaStreamSource(fakeStream)
         *       → returns an analyser-compatible node that emits synthetic
         *         time-domain data based on live waveform samples polled
         *         from Android.recWaveform()
         *   - Video requests pass through unchanged.
         */
        private const val AUDIO_SHIM_JS = """
(function(){
  if (typeof Android === 'undefined' || typeof Android.recStart !== 'function') return;
  if (window.__lwa_shim_v2) return;
  window.__lwa_shim_v2 = true;
  console.log('[LWA] transparent native audio shim v2');

  var origGUM = navigator.mediaDevices && navigator.mediaDevices.getUserMedia
                ? navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices) : null;
  var OrigMR = window.MediaRecorder;
  var OrigAC = window.AudioContext || window.webkitAudioContext;

  // ── Fake track ───────────────────────────────────────────────────────────
  function makeTrack() {
    return {
      kind: 'audio',
      id: 'lwa-track-' + Math.random().toString(36).slice(2),
      label: 'Native Android Microphone',
      enabled: true, muted: false, readyState: 'live', contentHint: '',
      stop: function(){ this.readyState='ended'; this.enabled=false; },
      getSettings: function(){ return {sampleRate:44100, channelCount:1, deviceId:'native'}; },
      getCapabilities: function(){ return {}; },
      getConstraints: function(){ return {}; },
      applyConstraints: function(){ return Promise.resolve(); },
      clone: function(){ return makeTrack(); },
      addEventListener: function(){}, removeEventListener: function(){},
      dispatchEvent: function(){ return true; }
    };
  }

  // ── Fake stream ──────────────────────────────────────────────────────────
  function NativeStream() {
    this.id = 'lwa-stream-' + Math.random().toString(36).slice(2);
    this.active = true;
    this.__lwa = true;
    this._tracks = [makeTrack()];
  }
  NativeStream.prototype.getTracks      = function(){ return this._tracks.slice(); };
  NativeStream.prototype.getAudioTracks = function(){ return this._tracks.slice(); };
  NativeStream.prototype.getVideoTracks = function(){ return []; };
  NativeStream.prototype.getTrackById = function(id){
    return this._tracks.filter(function(t){return t.id===id;})[0] || null;
  };
  NativeStream.prototype.addTrack    = function(){};
  NativeStream.prototype.removeTrack = function(){};
  NativeStream.prototype.clone       = function(){ return new NativeStream(); };
  NativeStream.prototype.addEventListener    = function(){};
  NativeStream.prototype.removeEventListener = function(){};
  NativeStream.prototype.dispatchEvent       = function(){ return true; };

  // ── Patch getUserMedia ───────────────────────────────────────────────────
  if (navigator.mediaDevices) {
    navigator.mediaDevices.getUserMedia = function(constraints) {
      var wantA = !!(constraints && constraints.audio);
      var wantV = !!(constraints && constraints.video);
      if (wantA && !wantV) {
        console.log('[LWA] getUserMedia(audio) → native');
        return Promise.resolve(new NativeStream());
      }
      if (origGUM) return origGUM(constraints);
      return Promise.reject(new DOMException('getUserMedia unavailable','NotSupportedError'));
    };
    var origEnum = navigator.mediaDevices.enumerateDevices
                   && navigator.mediaDevices.enumerateDevices.bind(navigator.mediaDevices);
    navigator.mediaDevices.enumerateDevices = function() {
      var fake = [{deviceId:'native', groupId:'native', kind:'audioinput',
                   label:'Native Android Microphone'}];
      if (origEnum) {
        return origEnum().then(function(list){
          var filtered = list.filter(function(d){ return d.kind !== 'audioinput'; });
          return fake.concat(filtered);
        }).catch(function(){ return fake; });
      }
      return Promise.resolve(fake);
    };
  }

  // ── Patch MediaRecorder ──────────────────────────────────────────────────
  function NativeMR(stream, options) {
    this._handlers = {};
    this._native = !!(stream && stream.__lwa);

    if (!this._native && OrigMR) {
      var self = this;
      this._real = new OrigMR(stream, options);
      ['dataavailable','start','stop','pause','resume','error'].forEach(function(ev){
        self._real.addEventListener(ev, function(e){ self._fire(ev, e); });
      });
      Object.defineProperty(this, 'state',    { get: function(){ return self._real.state; }});
      Object.defineProperty(this, 'mimeType', { get: function(){ return self._real.mimeType; }});
      this.stream = stream;
      return;
    }

    this._state   = 'inactive';
    this.stream   = stream;
    this.mimeType = 'audio/wav';
    this.audioBitsPerSecond = 705600;  // 44100 * 16
    this.videoBitsPerSecond = 0;
    var self = this;
    Object.defineProperty(this, 'state', { get: function(){ return self._state; }});
  }

  NativeMR.prototype._fire = function(ev, detail) {
    var handlers = (this._handlers[ev] || []).slice();
    var evt;
    try { evt = new Event(ev); } catch(e) { evt = { type: ev }; }
    if (detail) for (var k in detail) try { evt[k] = detail[k]; } catch(_) {}
    handlers.forEach(function(h){ try { h(evt); } catch(e) { console.error(e); } });
    var cb = this['on' + ev];
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
      var err = new Error(r);
      err.name = 'NotReadableError';
      this._fire('error', { error: err });
      return;
    }
    this._state = 'recording';
    this._fire('start');
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
      var blob = new Blob([buf], { type: 'audio/wav' });
      this._fire('dataavailable', { data: blob });
      this._fire('stop');
    } else {
      var e = new Error(dataUrl || 'recStop failed');
      e.name = 'AbortError';
      this._fire('error', { error: e });
      this._fire('stop');
    }
  };

  NativeMR.prototype.pause = function() {
    if (!this._native) return this._real.pause();
    if (this._state !== 'recording') return;
    var r = Android.recPause();
    if (r === 'ok') { this._state = 'paused'; this._fire('pause'); }
  };
  NativeMR.prototype.resume = function() {
    if (!this._native) return this._real.resume();
    if (this._state !== 'paused') return;
    var r = Android.recResume();
    if (r === 'ok') { this._state = 'recording'; this._fire('resume'); }
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

  // ── Patch AudioContext.createMediaStreamSource for live waveforms ────────
  // When apps feed our fake stream into an AnalyserNode, they expect
  // getByteTimeDomainData() to give real-time sample data. We intercept
  // the analyser's getByteTimeDomainData / getFloatTimeDomainData /
  // getByteFrequencyData and populate them with samples from Android.recWaveform().
  if (OrigAC) {
    var origCreateSrc = OrigAC.prototype.createMediaStreamSource;
    var origCreateAna = OrigAC.prototype.createAnalyser;

    OrigAC.prototype.createMediaStreamSource = function(stream) {
      if (!stream || !stream.__lwa) return origCreateSrc.call(this, stream);
      var ctx = this;
      var src = ctx.createGain();   // silent placeholder node
      src.gain.value = 0;
      src.__lwa = true;
      src.connect = function(dest) {
        // When connected to an analyser, mark that analyser as LWA-fed
        if (dest && dest.__lwa_analyser) dest.__lwa_src = src;
        else if (dest && dest.constructor && dest.constructor.name === 'AnalyserNode') {
          dest.__lwa_src = src;
        }
        return Object.getPrototypeOf(src).connect.call(src, dest);
      };
      return src;
    };

    OrigAC.prototype.createAnalyser = function() {
      var analyser = origCreateAna.call(this);
      analyser.__lwa_analyser = true;
      var origGetByteTD = analyser.getByteTimeDomainData.bind(analyser);
      var origGetFloatTD = analyser.getFloatTimeDomainData
                         ? analyser.getFloatTimeDomainData.bind(analyser) : null;
      var origGetByteFD = analyser.getByteFrequencyData.bind(analyser);

      analyser.getByteTimeDomainData = function(arr) {
        if (!this.__lwa_src) return origGetByteTD(arr);
        fillByteTimeDomain(arr);
      };
      if (origGetFloatTD) {
        analyser.getFloatTimeDomainData = function(arr) {
          if (!this.__lwa_src) return origGetFloatTD(arr);
          fillFloatTimeDomain(arr);
        };
      }
      analyser.getByteFrequencyData = function(arr) {
        if (!this.__lwa_src) return origGetByteFD(arr);
        fillByteFrequency(arr);
      };
      return analyser;
    };
  }

  function getWaveform(n) {
    try {
      var s = Android.recWaveform(n);
      if (!s) return null;
      return s.split(',').map(parseFloat);
    } catch(e) { return null; }
  }

  function fillByteTimeDomain(arr) {
    // Format: Uint8Array, 128 = silence, 0-255 range
    var wave = getWaveform(Math.min(arr.length, 256));
    if (!wave) {
      for (var i = 0; i < arr.length; i++) arr[i] = 128;
      return;
    }
    // Up-sample wave[] to arr.length with linear interp, expand 0-1 → 0-255 bipolar
    var amp = Android.recAmplitude() / 32767.0;
    for (var i = 0; i < arr.length; i++) {
      var t = (i / arr.length) * (wave.length - 1);
      var idx = Math.floor(t);
      var frac = t - idx;
      var v = wave[idx] * (1-frac) + (wave[idx+1] || wave[idx]) * frac;
      // synthesize a sine-ish wave modulated by amplitude envelope
      var phase = (i / arr.length) * Math.PI * 2 * 8; // 8 cycles across buffer
      var sample = Math.sin(phase) * v;
      arr[i] = Math.max(0, Math.min(255, 128 + sample * 120));
    }
  }

  function fillFloatTimeDomain(arr) {
    var wave = getWaveform(Math.min(arr.length, 256));
    if (!wave) { for (var i = 0; i < arr.length; i++) arr[i] = 0; return; }
    for (var i = 0; i < arr.length; i++) {
      var t = (i / arr.length) * (wave.length - 1);
      var idx = Math.floor(t);
      var frac = t - idx;
      var v = wave[idx] * (1-frac) + (wave[idx+1] || wave[idx]) * frac;
      var phase = (i / arr.length) * Math.PI * 2 * 8;
      arr[i] = Math.sin(phase) * v;
    }
  }

  function fillByteFrequency(arr) {
    // Frequency spectrum: fake a typical voice spectrum based on amplitude
    var amp = Android.recAmplitude() / 32767.0;
    for (var i = 0; i < arr.length; i++) {
      // Voice-shaped falloff: more energy in low freqs
      var freqWeight = Math.pow(1 - (i / arr.length), 1.5);
      var noise = Math.random() * 0.5 + 0.5;
      arr[i] = Math.min(255, amp * freqWeight * noise * 255);
    }
  }
})();
"""
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView

    private var server: SimpleServer? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var folderUri: Uri
    private lateinit var appTitle: String

    private val nativeRecorder = NativeAudioRecorder()

    private val startupPermsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            initializeWebApp()
        }

    private val runtimePermsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_webapp)

        val uriStr = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        folderUri  = Uri.parse(uriStr)
        appTitle   = intent.getStringExtra(EXTRA_TITLE) ?: "Web App"

        webView     = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorView   = findViewById(R.id.errorView)
        errorText   = findViewById(R.id.errorText)
        progressBar.visibility = View.VISIBLE

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

    private fun initializeWebApp() {
        val s = SimpleServer(contentResolver, folderUri, filesDir)
        server = s; s.start()
        setupWebView()
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
            @JavascriptInterface fun toast(msg: String) = runOnUiThread {
                Toast.makeText(this@WebAppActivity, msg, Toast.LENGTH_SHORT).show()
            }
            @JavascriptInterface fun log(msg: String) = android.util.Log.d("WebApp", msg)
            @JavascriptInterface fun isNativeApp() = true
            @JavascriptInterface fun getPlatform() = "android"
            @JavascriptInterface fun getAppVersion(): String =
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
            @JavascriptInterface fun share(text: String) = runOnUiThread {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                    }, "Share"))
            }
            @JavascriptInterface fun vibrate() = runOnUiThread {
                val v = getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(100)
            }
            @JavascriptInterface fun getFreeMB(): String {
                return try {
                    val stat = android.os.StatFs(filesDir.path)
                    (stat.availableBlocksLong * stat.blockSizeLong / 1048576).toString()
                } catch (e: Exception) { "0" }
            }

            // ── Native audio bridge (called by the JS shim, not by web apps directly) ──
            @JavascriptInterface fun recStart():  String = nativeRecorder.start()
            @JavascriptInterface fun recStop():   String = nativeRecorder.stop()
            @JavascriptInterface fun recPause():  String = nativeRecorder.pause()
            @JavascriptInterface fun recResume(): String = nativeRecorder.resume()
            @JavascriptInterface fun recAmplitude(): Int  = nativeRecorder.getAmplitude()
            @JavascriptInterface fun recWaveform(count: Int): String = nativeRecorder.getWaveform(count)
            @JavascriptInterface fun recIsActive():  Boolean = nativeRecorder.isActive()
            @JavascriptInterface fun recIsPaused():  Boolean = nativeRecorder.isPausedState()
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                errorView.visibility   = View.GONE
            }
            override fun onPageFinished(v: WebView, url: String) {
                progressBar.visibility = View.GONE
                // Inject the transparent audio shim AFTER page load so web apps
                // using standard getUserMedia + MediaRecorder get reliable mic
                // access via the native Android AudioRecord under the hood.
                v.evaluateJavascript(AUDIO_SHIM_JS, null)
            }
            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) {
                    progressBar.visibility = View.GONE
                    errorView.visibility   = View.VISIBLE
                    errorText.text         = "Error: ${err.description}"
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
                    android.util.Log.d("NetCache", "HIT  ${cacheFile.name}")
                    return streamFromDisk(cacheFile, url)
                }
                return try {
                    android.util.Log.d("NetCache", "MISS ${url.takeLast(60)}")
                    val conn = openConnection(url, request)
                    val code = conn.responseCode
                    if (code !in 200..299) { conn.disconnect(); return null }
                    val contentType = conn.contentType ?: guessMime(url)
                    if (contentType.contains("text/html")) { conn.disconnect(); return null }
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
                        android.util.Log.d("NetCache", "SAVED ${cacheFile.name} (${cacheFile.length()/1048576}MB)")
                    } catch (e: Exception) {
                        tmpFile.delete()
                        android.util.Log.e("NetCache", "Download failed: ${e.message}")
                        conn.disconnect(); return null
                    } finally { conn.disconnect() }
                    if (cacheFile.exists() && cacheFile.length() > 0) streamFromDisk(cacheFile, url) else null
                } catch (e: Exception) {
                    android.util.Log.e("NetCache", "Intercept error: ${e.message}"); null
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                progressBar.progress = p
                if (p == 100) progressBar.visibility = View.GONE
            }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.d("WebApp-JS",
                    "[${m.messageLevel()}] ${m.message()} (line ${m.lineNumber()})")
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
                val fine   = android.Manifest.permission.ACCESS_FINE_LOCATION
                val coarse = android.Manifest.permission.ACCESS_COARSE_LOCATION
                if (checkSelfPermission(fine) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    AlertDialog.Builder(this@WebAppActivity)
                        .setTitle("Location Access")
                        .setMessage("This app wants to access your location.")
                        .setPositiveButton("Allow") { _, _ ->
                            runtimePermsLauncher.launch(arrayOf(fine, coarse))
                            callback.invoke(origin, true, false)
                        }
                        .setNegativeButton("Deny") { _, _ ->
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
    }
    override fun onPause()  { super.onPause();  webView.onPause()  }
    override fun onResume() { super.onResume(); webView.onResume() }
}

class SimpleServer(
    private val resolver: ContentResolver,
    private val rootUri: Uri,
    private val filesDir: File
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

    private fun handle(s: java.net.Socket) {
        val reader = s.inputStream.bufferedReader(Charsets.ISO_8859_1)
        val out    = s.outputStream
        val line   = reader.readLine() ?: return
        val parts  = line.trim().split(" ")
        if (parts.size < 2) return
        var path   = URLDecoder.decode(parts[1].substringBefore("?"), "UTF-8")
        do {
            val h = reader.readLine() ?: break
            if (h.isBlank()) break
        } while (true)
        if (path == "/" || path.isEmpty()) path = "/index.html"
        val segments = path.trimStart('/').split("/").filter { it.isNotEmpty() && it != ".." }
        val file = resolve(segments)
        when {
            file == null || !file.exists() -> {
                val idx = resolve(listOf("index.html"))
                if (idx != null && idx.exists()) serveDocFile(out, idx) else send404(out)
            }
            file.isDirectory -> {
                val idx = file.findFile("index.html")
                if (idx != null) serveDocFile(out, idx) else send404(out)
            }
            else -> serveDocFile(out, file)
        }
    }

    private fun resolve(segments: List<String>): DocumentFile? {
        val ctx = try {
            val f = ContentResolver::class.java.getDeclaredField("mContext")
            f.isAccessible = true
            f.get(resolver) as android.content.Context
        } catch (e: Exception) { return null }
        var node = DocumentFile.fromTreeUri(ctx, rootUri) ?: return null
        for (seg in segments) { node = node.findFile(seg) ?: return null }
        return node
    }

    private fun serveDocFile(out: OutputStream, file: DocumentFile) {
        val bytes = resolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: run { send404(out); return }
        val mime   = getMime(file.name ?: "")
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes); out.flush()
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
        "mp3"         -> "audio/mpeg"
        "wasm"        -> "application/wasm"
        else          -> "application/octet-stream"
    }
}