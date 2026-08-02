package com.localwebapp

import android.app.Notification
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════════════════════════════════════════
// NotificationCapture — turns incoming messaging notifications into plain JSONL
// files inside the user's picked project folder.
//
// Design follows the CouchFlow philosophy: the web app should not have to know
// this exists. Captured messages simply APPEAR as files in the project folder:
//
//     <project>/inbox/2026-08-02.jsonl
//
// A web app reads them with the ordinary AndroidFS API it already uses. The
// small Android.notif* bridge added in App.kt is only for things a file cannot
// express — "is capture actually switched on?" and "where did I leave off?".
//
// Privacy posture, deliberately conservative:
//   - Nothing is captured until the user grants notification access in system
//     Settings AND flips the master switch AND binds a target project folder.
//   - Only packages on an explicit allowlist are read (WhatsApp by default).
//     Every other notification on the device is dropped before parsing.
//   - Message text is never written to DebugLog — only counts and chat names.
//   - Old day-files are pruned automatically (default 30 days).
//
// Known and unavoidable limits of the NotificationListener approach:
//   - Only messages that actually raise a notification are seen. Muted chats,
//     and messages arriving while WhatsApp is in the foreground, are missed.
//   - There is no access to chat history — capture starts from the moment the
//     user enables it.
//   - Long messages may be truncated by the sending app before they ever reach
//     the notification.
// ═══════════════════════════════════════════════════════════════════════════════

object NotifStore {

    const val PREFS            = "CouchFlow"
    const val K_ENABLED        = "notif_enabled"
    const val K_PACKAGES       = "notif_packages"
    const val K_TARGET_URI     = "notif_target_uri"
    const val K_TARGET_NAME    = "notif_target_name"
    const val K_FILTER_MODE    = "notif_filter_mode"     // "all" | "allow" | "block"
    const val K_FILTER_LIST    = "notif_filter_list"     // one chat name per line
    const val K_RETENTION_DAYS = "notif_retention_days"
    const val K_CURSOR         = "notif_cursor"          // web app's read position

    const val DEFAULT_PACKAGES  = "com.whatsapp,com.whatsapp.w4b"
    const val DEFAULT_RETENTION = 30

    /** Where captured messages live inside CouchFlow's private storage. */
    private fun inboxDir(ctx: Context): File =
        File(ctx.filesDir, "inbox").apply { if (!exists()) mkdirs() }

    private fun dayStamp(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

    fun dayFile(ctx: Context, ts: Long): File =
        File(inboxDir(ctx), "${dayStamp(ts)}.jsonl")

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── settings accessors ────────────────────────────────────────────────────

    fun isCaptureOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(K_ENABLED, false)

    fun packages(ctx: Context): Set<String> =
        prefs(ctx).getString(K_PACKAGES, DEFAULT_PACKAGES)!!
            .split(",", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun targetUri(ctx: Context): String? =
        prefs(ctx).getString(K_TARGET_URI, null)?.takeIf { it.isNotEmpty() }

    fun targetName(ctx: Context): String =
        prefs(ctx).getString(K_TARGET_NAME, "") ?: ""

    fun retentionDays(ctx: Context): Int =
        prefs(ctx).getInt(K_RETENTION_DAYS, DEFAULT_RETENTION)

    /**
     * True when the OS has granted notification access to this package. The
     * master switch is meaningless without it, so the settings screen always
     * shows both states separately.
     */
    fun hasSystemAccess(ctx: Context): Boolean = try {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ) ?: ""
        flat.split(":").any { it.contains(ctx.packageName) }
    } catch (e: Exception) { false }

    /**
     * Chat-level filter. "all" captures everything from the allowed packages;
     * "allow" keeps only chats whose name contains one of the listed strings;
     * "block" drops those. Matching is case-insensitive substring — good enough
     * for "Anne", "Work Group", and forgiving of emoji suffixes in chat titles.
     */
    fun passesFilter(ctx: Context, chat: String): Boolean {
        val mode = prefs(ctx).getString(K_FILTER_MODE, "all") ?: "all"
        if (mode == "all") return true
        val list = (prefs(ctx).getString(K_FILTER_LIST, "") ?: "")
            .split("\n", ",")
            .map { it.trim().lowercase(Locale.getDefault()) }
            .filter { it.isNotEmpty() }
        if (list.isEmpty()) return mode != "allow"
        val hay = chat.lowercase(Locale.getDefault())
        val hit = list.any { hay.contains(it) }
        return if (mode == "allow") hit else !hit
    }

    // ── writing ───────────────────────────────────────────────────────────────

    /** Appends one record to today's private JSONL file. Returns false on IO error. */
    @Synchronized
    fun append(ctx: Context, record: JSONObject): Boolean = try {
        val file = dayFile(ctx, record.optLong("ts", System.currentTimeMillis()))
        file.appendText(record.toString() + "\n")
        true
    } catch (e: Exception) {
        DebugLog.error("notif append failed: ${e.message}")
        false
    }

    /**
     * Rebuilds the dedup key set for today from disk. Called when the listener
     * connects, so a service restart mid-day doesn't re-import messages that
     * were already captured.
     */
    @Synchronized
    fun keysForToday(ctx: Context): MutableSet<String> {
        val out = HashSet<String>()
        val f = dayFile(ctx, System.currentTimeMillis())
        if (!f.exists()) return out
        try {
            f.forEachLine { line ->
                if (line.isNotBlank()) {
                    try { out.add(JSONObject(line).optString("key")) } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
        out.remove("")
        return out
    }

    /** Deletes day files older than the retention window. */
    @Synchronized
    fun prune(ctx: Context) {
        try {
            val keepMs = retentionDays(ctx).toLong() * 24L * 60L * 60L * 1000L
            val cutoff = System.currentTimeMillis() - keepMs
            inboxDir(ctx).listFiles()?.forEach { f ->
                if (f.name.endsWith(".jsonl") && f.lastModified() < cutoff) f.delete()
            }
        } catch (e: Exception) {}
    }

    // ── reading ───────────────────────────────────────────────────────────────

    /**
     * Returns up to [limit] records with ts > [since], newest first. Reads day
     * files from newest backwards and stops as soon as the limit is filled, so
     * a "what came in since I last looked" query never touches old files.
     */
    @Synchronized
    fun query(ctx: Context, since: Long, limit: Int): JSONArray {
        val out = JSONArray()
        val files = inboxDir(ctx).listFiles()
            ?.filter { it.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.name }
            ?: return out
        for (f in files) {
            val lines = try { f.readLines() } catch (e: Exception) { continue }
            for (i in lines.indices.reversed()) {
                val line = lines[i]
                if (line.isBlank()) continue
                val obj = try { JSONObject(line) } catch (e: Exception) { continue }
                if (obj.optLong("ts") <= since) continue
                out.put(obj)
                if (out.length() >= limit) return out
            }
        }
        return out
    }

    // ── mirroring into the user's picked folder ───────────────────────────────

    /**
     * Copies today's private day-file into <target>/inbox/<day>.jsonl using SAF.
     *
     * Why mirror instead of writing to SAF directly: SAF writes are slow and
     * append mode ("wa") is not honoured by every DocumentsProvider. Writing
     * locally first means a burst of ten messages costs ten cheap appends and
     * one debounced SAF rewrite, and a failed SAF write can never lose data —
     * the private copy is the source of truth and the next flush retries.
     */
    @Synchronized
    fun mirror(ctx: Context): String {
        val uriStr = targetUri(ctx) ?: return "no-target"
        val src = dayFile(ctx, System.currentTimeMillis())
        if (!src.exists()) return "nothing"
        return try {
            val resolver: ContentResolver = ctx.contentResolver
            val root = DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
                ?: return "error:bad-target"
            if (!root.isDirectory) return "error:target-missing"
            val dir = root.findFile("inbox")?.takeIf { it.isDirectory }
                ?: root.createDirectory("inbox")
                ?: return "error:mkdir"
            val name = src.name
            val doc = dir.findFile(name)
                ?: dir.createFile("application/json", name)
                ?: return "error:create"
            resolver.openOutputStream(doc.uri, "wt")?.use { os ->
                src.inputStream().use { ins -> ins.copyTo(os) }
            } ?: return "error:stream"
            "ok"
        } catch (e: Exception) {
            "error:${e.message}"
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// NotificationCaptureService
//
// Bound by the system once the user grants notification access. Every posted
// notification passes through onNotificationPosted; the vast majority are
// discarded in the first few lines.
// ═══════════════════════════════════════════════════════════════════════════════
class NotificationCaptureService : NotificationListenerService() {

    private val flusher = Executors.newSingleThreadScheduledExecutor()
    private var pendingFlush: ScheduledFuture<*>? = null
    private var seenKeys: MutableSet<String> = HashSet()
    private var lastPruneDay = ""

    override fun onListenerConnected() {
        super.onListenerConnected()
        seenKeys = NotifStore.keysForToday(applicationContext)
        DebugLog.lifecycle("notification listener connected (${seenKeys.size} already captured today)")
        NotifStore.prune(applicationContext)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DebugLog.lifecycle("notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val ctx = applicationContext
        if (sbn == null) return
        if (!NotifStore.isCaptureOn(ctx)) return
        if (sbn.packageName !in NotifStore.packages(ctx)) return

        val n = sbn.notification ?: return

        // Group summaries repeat content already delivered by the child
        // notifications; ongoing ones are WhatsApp's "checking for new
        // messages", backup progress, and active calls — none are messages.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        try {
            val records = extract(sbn, n)
            if (records.isEmpty()) return

            var written = 0
            for (rec in records) {
                val key = rec.optString("key")
                if (key.isEmpty() || !seenKeys.add(key)) continue
                if (NotifStore.append(ctx, rec)) written++
            }
            if (written == 0) return

            // Chat names are logged, message bodies never are.
            DebugLog.push(
                DebugLog.Source.LIFECYCLE, DebugLog.Severity.INFO,
                "captured $written message(s) from ${records[0].optString("chat")}"
            )
            scheduleFlush()
            rollDayIfNeeded()
        } catch (e: Exception) {
            DebugLog.error("notif capture failed: ${e.message}")
        }
    }

    /**
     * Pulls messages out of a notification. MessagingStyle is the good path:
     * it carries a stable per-message timestamp, the real sender for group
     * chats, and every message in the conversation rather than just the latest.
     * Apps that don't use it fall back to title/text extras.
     */
    private fun extract(sbn: StatusBarNotification, n: Notification): List<JSONObject> {
        val ctx = applicationContext
        val out = ArrayList<JSONObject>()
        val app = appLabel(sbn.packageName)
        val style = try {
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(n)
        } catch (e: Exception) { null }

        if (style != null && style.messages.isNotEmpty()) {
            val isGroup = style.isGroupConversation
            val chat = (style.conversationTitle
                ?: n.extras.getCharSequence(Notification.EXTRA_TITLE))
                ?.toString()?.trim().orEmpty()
            if (chat.isEmpty() || !NotifStore.passesFilter(ctx, chat)) return out

            for (m in style.messages) {
                val text = m.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val sender = m.person?.name?.toString()?.trim()
                    ?: if (isGroup) "" else chat
                val ts = if (m.timestamp > 0) m.timestamp else sbn.postTime
                out.add(record(app, chat, sender, isGroup, text, ts))
            }
            return out
        }

        // Fallback path: single latest message only.
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() || text.isEmpty()) return out
        if (isNoise(title, text)) return out
        if (!NotifStore.passesFilter(ctx, title)) return out
        out.add(record(app, title, title, false, text, sbn.postTime))
        return out
    }

    /**
     * Filler notifications that carry no message content. These only appear on
     * the fallback path — MessagingStyle notifications are self-describing.
     */
    private fun isNoise(title: String, text: String): Boolean {
        val t = text.lowercase(Locale.US)
        if (t.matches(Regex("^\\d+ (new )?messages?( from \\d+ chats?)?$"))) return true
        if (t.contains("checking for new messages")) return true
        if (t.startsWith("missed call") || t == "incoming call") return true
        if (title.equals("whatsapp", ignoreCase = true) && text.isBlank()) return true
        return false
    }

    private fun record(
        app: String, chat: String, sender: String,
        group: Boolean, text: String, ts: Long
    ): JSONObject {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(ts))
        // Dedup key excludes ts for the fallback path's sake: reposted
        // notifications get a fresh postTime but identical content. Including
        // the chat and sender keeps identical texts in different chats distinct.
        val key = "$chat|$sender|${text.take(300)}".hashCode().toString(16) +
                  "-" + text.length.toString(16)
        return JSONObject().apply {
            put("ts", ts)
            put("iso", iso)
            put("app", app)
            put("chat", chat)
            put("sender", sender)
            put("group", group)
            put("text", text)
            put("key", key)
        }
    }

    private fun appLabel(pkg: String): String = when {
        pkg.startsWith("com.whatsapp") -> "whatsapp"
        else -> pkg
    }

    /**
     * SAF writes are debounced: a conversation arriving as six rapid-fire
     * notifications produces one folder write, two seconds after the last one.
     */
    private fun scheduleFlush() {
        pendingFlush?.cancel(false)
        pendingFlush = flusher.schedule({
            val result = NotifStore.mirror(applicationContext)
            if (result.startsWith("error")) {
                DebugLog.error("inbox mirror failed: $result")
            }
        }, 2, TimeUnit.SECONDS)
    }

    /** Once per calendar day, refresh the dedup set and prune old files. */
    private fun rollDayIfNeeded() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (today == lastPruneDay) return
        lastPruneDay = today
        seenKeys = NotifStore.keysForToday(applicationContext)
        NotifStore.prune(applicationContext)
    }

    override fun onDestroy() {
        try { flusher.shutdown() } catch (e: Exception) {}
        super.onDestroy()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// NotifSettingsActivity — reached from a project's long-press menu, so the
// project whose folder receives inbox/ is chosen by the act of opening it.
// Built programmatically; no layout XML, no new resources.
// ═══════════════════════════════════════════════════════════════════════════════
class NotifSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_URI  = "target_uri"
        const val EXTRA_TARGET_NAME = "target_name"
    }

    private val bg     = Color.parseColor("#0F1626")
    private val fg     = Color.parseColor("#E6E9EF")
    private val muted  = Color.parseColor("#8A93A6")
    private val accent = Color.parseColor("#8B5CF6")

    private lateinit var statusView: TextView
    private lateinit var masterSwitch: Switch
    private lateinit var packagesField: EditText
    private lateinit var filterGroup: RadioGroup
    private lateinit var filterField: EditText
    private lateinit var retentionField: EditText

    private var targetUri: String? = null
    private var targetName: String = ""

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        targetUri  = intent.getStringExtra(EXTRA_TARGET_URI)
            ?: NotifStore.targetUri(this)
        targetName = intent.getStringExtra(EXTRA_TARGET_NAME)
            ?: NotifStore.targetName(this)

        val prefs = NotifStore.prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        root.addView(heading("Message capture"))
        root.addView(body(
            "Reads incoming notifications from the apps listed below and writes " +
            "them as JSONL into the project folder. Your web app reads them with " +
            "AndroidFS like any other file.\n\n" +
            "Only notifications that actually appear are captured — muted chats " +
            "and messages that arrive while the app is open are missed, and chat " +
            "history is never available."
        ))

        statusView = body("")
        root.addView(statusView)

        root.addView(button("Open Android notification access") {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                toast("Could not open Settings: ${e.message}")
            }
        })

        root.addView(divider())

        root.addView(label("Target folder"))
        root.addView(body(
            if (targetUri == null) "None — open this screen from a project's long-press menu."
            else "$targetName  →  inbox/YYYY-MM-DD.jsonl"
        ))

        root.addView(divider())

        masterSwitch = Switch(this).apply {
            text = "Capture messages"
            setTextColor(fg)
            textSize = 16f
            isChecked = NotifStore.isCaptureOn(this@NotifSettingsActivity)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(masterSwitch)

        root.addView(label("Packages (comma separated)"))
        packagesField = field(prefs.getString(NotifStore.K_PACKAGES, NotifStore.DEFAULT_PACKAGES)!!)
        root.addView(packagesField)
        root.addView(hint("com.whatsapp is the consumer app, com.whatsapp.w4b is Business."))

        root.addView(label("Chat filter"))
        filterGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val modes = listOf("all" to "Capture every chat",
                           "allow" to "Only chats listed below",
                           "block" to "Every chat except those listed")
        val current = prefs.getString(NotifStore.K_FILTER_MODE, "all")
        modes.forEachIndexed { i, (mode, lbl) ->
            filterGroup.addView(RadioButton(this).apply {
                id = 1000 + i
                text = lbl
                setTextColor(fg)
                tag = mode
                isChecked = mode == current
            })
        }
        root.addView(filterGroup)

        filterField = field(prefs.getString(NotifStore.K_FILTER_LIST, "") ?: "").apply {
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(filterField)
        root.addView(hint("One name per line. Case-insensitive partial match against the chat title."))

        root.addView(label("Keep messages for (days)"))
        retentionField = field(
            prefs.getInt(NotifStore.K_RETENTION_DAYS, NotifStore.DEFAULT_RETENTION).toString()
        ).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(retentionField)

        root.addView(divider())

        root.addView(button("Save") { save(); toast("Saved"); refreshStatus() })

        root.addView(button("Write a test message") {
            save()
            val rec = JSONObject().apply {
                val now = System.currentTimeMillis()
                put("ts", now)
                put("iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(now)))
                put("app", "test"); put("chat", "CouchFlow"); put("sender", "CouchFlow")
                put("group", false)
                put("text", "Test message — capture is wired up correctly.")
                put("key", "test-$now")
            }
            NotifStore.append(this, rec)
            // SAF writes can block on slow providers — never on the UI thread.
            Thread {
                val r = NotifStore.mirror(this)
                runOnUiThread {
                    toast(if (r == "ok") "Written to $targetName/inbox/" else "Mirror result: $r")
                }
            }.start()
        })

        root.addView(button("Flush to folder now") {
            Thread {
                val r = NotifStore.mirror(this)
                runOnUiThread { toast("Mirror: $r") }
            }.start()
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        refreshStatus()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun refreshStatus() {
        val access = NotifStore.hasSystemAccess(this)
        val on     = NotifStore.isCaptureOn(this)
        val bound  = NotifStore.targetUri(this) != null
        val state = when {
            !access -> "⚠ Notification access not granted. Nothing is being captured."
            !bound  -> "⚠ No target folder bound. Nothing is being written."
            !on     -> "◦ Access granted, capture switched off."
            else    -> "● Capturing into ${NotifStore.targetName(this)}/inbox/"
        }
        statusView.text = state
        statusView.setTextColor(if (access && on && bound) accent else muted)
    }

    private fun save() {
        val mode = filterGroup.findViewById<RadioButton>(filterGroup.checkedRadioButtonId)
            ?.tag as? String ?: "all"
        val days = retentionField.text.toString().toIntOrNull()
            ?.coerceIn(1, 3650) ?: NotifStore.DEFAULT_RETENTION
        NotifStore.prefs(this).edit().apply {
            putBoolean(NotifStore.K_ENABLED, masterSwitch.isChecked)
            putString(NotifStore.K_PACKAGES, packagesField.text.toString())
            putString(NotifStore.K_FILTER_MODE, mode)
            putString(NotifStore.K_FILTER_LIST, filterField.text.toString())
            putInt(NotifStore.K_RETENTION_DAYS, days)
            targetUri?.let {
                putString(NotifStore.K_TARGET_URI, it)
                putString(NotifStore.K_TARGET_NAME, targetName)
            }
        }.apply()
    }

    // ── tiny view helpers ─────────────────────────────────────────────────────

    private fun heading(s: String) = TextView(this).apply {
        text = s; setTextColor(fg); textSize = 22f
        setPadding(0, 0, 0, dp(12))
    }

    private fun body(s: String) = TextView(this).apply {
        text = s; setTextColor(muted); textSize = 13f
        setPadding(0, 0, 0, dp(12))
    }

    private fun label(s: String) = TextView(this).apply {
        text = s; setTextColor(fg); textSize = 14f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun hint(s: String) = TextView(this).apply {
        text = s; setTextColor(muted); textSize = 11f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun field(value: String) = EditText(this).apply {
        setText(value); setTextColor(fg); textSize = 14f
        setHintTextColor(muted)
        setBackgroundColor(Color.parseColor("#1A2032"))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#232A3D"))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(16); bottomMargin = dp(8) }
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        setTextColor(Color.WHITE)
        setBackgroundColor(accent)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
