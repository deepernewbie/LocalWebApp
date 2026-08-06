package com.localwebapp

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ═══════════════════════════════════════════════════════════════════════════════
// ShareReceiver — the agent's inbox, reachable from every app on the phone.
//
// The WhatsApp-group idea never worked and never could: WhatsApp raises no
// notification for your own messages, so a group containing only you is
// invisible to a notification listener. This is the route that does work.
// Long-press a message in WhatsApp → Share → Yaver, and the text lands in the
// folder. Same for a link from the browser, an address from Maps, a quote from
// a book app.
//
// Better than notification capture in three ways: it works from any app, the
// text arrives complete rather than truncated to notification length, and it
// is unambiguous — you shared it on purpose, so the agent treats it as an
// instruction rather than as background chatter.
//
// The activity has no UI. It writes, confirms with a toast, and finishes, so
// sharing never interrupts what you were doing.
// ═══════════════════════════════════════════════════════════════════════════════
class ShareReceiver : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Exception) {
            DebugLog.error("share receive failed: ${e.message}")
            Toast.makeText(this, "Yaver couldn't save that", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { readUri(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent
                .getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                ?.mapNotNull { readUri(it) }?.joinToString("\n\n---\n\n")
            else -> null
        }?.trim()

        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }

        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
        val from = intent.getStringExtra("android.intent.extra.shortcut.NAME")
            ?: referrer?.host ?: "shared"

        val now = System.currentTimeMillis()
        val record = JSONObject().apply {
            put("ts", now)
            put("iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(now)))
            put("app", "share")
            put("chat", "Shared with me")
            put("sender", from)
            put("group", false)
            put("subject", subject)
            put("text", text.take(20000))
            put("key", "share-$now")
        }

        appendPrivate(record)
        val where = mirror()
        Toast.makeText(this,
            if (where == "ok") "Saved for Yaver" else "Saved (sync: $where)",
            Toast.LENGTH_SHORT).show()
    }

    /** Text files and small documents are inlined; anything else is described. */
    private fun readUri(uri: Uri): String? = try {
        val resolver: ContentResolver = contentResolver
        val type = resolver.getType(uri) ?: ""
        if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) {
            resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(20000) }
        } else {
            val name = DocumentFile.fromSingleUri(this, uri)?.name ?: uri.lastPathSegment
            "[shared file: $name ($type)] $uri"
        }
    } catch (e: Exception) {
        DebugLog.error("could not read shared uri: ${e.message}")
        null
    }

    /**
     * Written into the same day-file the notification listener uses, so the web
     * app reads shares and captured messages through one path.
     */
    private fun appendPrivate(record: JSONObject) {
        val dir = File(filesDir, "inbox").apply { if (!exists()) mkdirs() }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(record.getLong("ts")))
        File(dir, "$day.jsonl").appendText(record.toString() + "\n")
    }

    private fun mirror(): String {
        val uriStr = NotifStore.targetUri(this) ?: return "no folder bound"
        return try {
            val src = NotifStore.dayFile(this, System.currentTimeMillis())
            if (!src.exists()) return "nothing"
            val root = DocumentFile.fromTreeUri(this, Uri.parse(uriStr)) ?: return "bad target"
            val dir = root.findFile("inbox")?.takeIf { it.isDirectory }
                ?: root.createDirectory("inbox") ?: return "mkdir failed"
            val doc = dir.findFile(src.name) ?: dir.createFile("application/json", src.name)
                ?: return "create failed"
            contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
                src.inputStream().use { it.copyTo(os) }
            } ?: return "stream failed"
            "ok"
        } catch (e: Exception) {
            e.message ?: "error"
        }
    }
}
